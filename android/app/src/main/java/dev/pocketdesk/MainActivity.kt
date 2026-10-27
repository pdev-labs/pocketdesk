package dev.pocketdesk

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.pocketdesk.net.HelloInfo
import dev.pocketdesk.net.PocketDeskClient
import dev.pocketdesk.net.RemoteInput
import dev.pocketdesk.net.Stats
import dev.pocketdesk.ui.KeySinkEditText
import dev.pocketdesk.ui.ScreenView
import java.net.URLEncoder
import java.util.Locale

class MainActivity : Activity(), PocketDeskClient.Listener {

    private lateinit var prefs: Prefs

    private lateinit var connectPanel: View
    private lateinit var remotePanel: View
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var tokenField: EditText
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var screenView: ScreenView
    private lateinit var keySink: KeySinkEditText
    private lateinit var toolbarButtons: LinearLayout
    private lateinit var statsText: TextView

    private var client: PocketDeskClient? = null
    private var readOnly = false
    private var userDisconnected = false
    private var connecting = false
    private var attempts = 0

    private val mods = linkedSetOf<String>()
    private val modifierChips = HashMap<String, TextView>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        connectPanel = findViewById(R.id.connectScroll)
        remotePanel = findViewById(R.id.remotePanel)
        hostField = findViewById(R.id.hostField)
        portField = findViewById(R.id.portField)
        tokenField = findViewById(R.id.tokenField)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)
        screenView = findViewById(R.id.screenView)
        keySink = findViewById(R.id.keySink)
        toolbarButtons = findViewById(R.id.toolbarButtons)
        statsText = findViewById(R.id.statsText)

        hostField.setText(prefs.host)
        portField.setText(prefs.port.toString())
        tokenField.setText(prefs.token)

        connectButton.setOnClickListener { connect() }

        screenView.onMove = { x, y -> if (!readOnly) send(RemoteInput.move(x, y)) }
        screenView.onButton = { button, down -> if (!readOnly) send(RemoteInput.button(button, down)) }
        screenView.onClick = { button -> if (!readOnly) send(RemoteInput.click(button)) }
        screenView.onScroll = { dy, dx -> if (!readOnly) send(RemoteInput.scroll(dy, dx)) }

        keySink.onText = { text -> if (!readOnly) send(RemoteInput.text(text)) }
        keySink.onKeyName = { name -> sendKey(name) }

        buildToolbar()
    }

    // ── connection ────────────────────────────────────────────────────

    private fun connect() {
        val host = hostField.text.toString().trim().ifBlank { "127.0.0.1" }
        val port = portField.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: Prefs.DEFAULT_PORT
        val token = tokenField.text.toString().trim()

        if (token.length < 8) {
            statusText.text = "Paste the pairing token printed by the host."
            return
        }

        prefs.save(host, port, token)
        userDisconnected = false
        attempts = 0
        open(host, port, token)
    }

    private fun open(host: String, port: Int, token: String) {
        connecting = true
        client?.close()

        val url = "ws://$host:$port/ws?token=${URLEncoder.encode(token, "UTF-8")}"
        statusText.text = "Connecting to $host:$port…"
        connectButton.isEnabled = false
        client = PocketDeskClient(this).also { it.connect(url) }
    }

    override fun onHello(info: HelloInfo) {
        connecting = false
        attempts = 0
        readOnly = info.readOnly || !info.input
        connectButton.isEnabled = true

        showRemote()
        statsText.text = if (readOnly) "view only" else "${info.fps} fps"
        if (!info.input && !info.readOnly) {
            toast(info.inputError?.substringBefore('\n') ?: "Host is view-only")
        }
    }

    override fun onFrame(bitmap: Bitmap) {
        screenView.setFrame(bitmap)
    }

    override fun onStats(stats: Stats) {
        statsText.text = if (readOnly) {
            String.format(Locale.US, "view only\n%.0f fps", stats.fps)
        } else {
            String.format(Locale.US, "%.0f fps · %d kbps", stats.fps, stats.kbps)
        }
    }

    override fun onError(message: String) {
        toast(message)
        statusText.text = message
    }

    override fun onClosed(reason: String) {
        connecting = false
        if (userDisconnected) return

        if (attempts >= 4) {
            showConnectPanel("Disconnected: $reason")
            return
        }

        attempts += 1
        val delay = (1000L shl (attempts - 1)).coerceAtMost(8000L)
        statusText.text = "Connection lost — retrying in ${delay / 1000}s"
        statsText.text = "reconnecting…"
        handler.postDelayed({
            if (!userDisconnected) open(prefs.host, prefs.port, prefs.token)
        }, delay)
    }

    private fun disconnect() {
        userDisconnected = true
        handler.removeCallbacksAndMessages(null)
        client?.close()
        client = null
        connecting = false
        mods.clear()
        updateModifierChips()
        showConnectPanel("Disconnected.")
    }

    // ── UI state ──────────────────────────────────────────────────────

    private fun showRemote() {
        connectPanel.visibility = View.GONE
        remotePanel.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
    }

    private fun showConnectPanel(message: String) {
        remotePanel.visibility = View.GONE
        connectPanel.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        showSystemBars()
        hideKeyboard()
        statusText.text = message
        connectButton.isEnabled = true
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::remotePanel.isInitialized && remotePanel.visibility == View.VISIBLE) {
            hideSystemBars()
        }
    }

    // ── toolbar ───────────────────────────────────────────────────────

    private fun buildToolbar() {
        toolbarButtons.removeAllViews()
        modifierChips.clear()

        toolbarButtons.addView(chip("Keys") { toggleKeyboard() })

        for ((name, label) in listOf(
            "ctrl" to "Ctrl",
            "alt" to "Alt",
            "shift" to "Shift",
            "super" to "Super",
        )) {
            val view = chip(label) { toggleModifier(name) }
            modifierChips[name] = view
            toolbarButtons.addView(view)
        }

        for ((name, label) in listOf(
            "esc" to "Esc",
            "tab" to "Tab",
            "enter" to "Enter",
            "backspace" to "Bksp",
            "left" to "←",
            "up" to "↑",
            "down" to "↓",
            "right" to "→",
        )) {
            toolbarButtons.addView(chip(label) { sendKey(name) })
        }

        toolbarButtons.addView(chip("Exit") { disconnect() })
    }

    private fun chip(label: String, onClick: () -> Unit): TextView {
        val view = TextView(this)
        view.text = label
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        view.setTextColor(getColor(R.color.text))
        view.gravity = Gravity.CENTER
        view.setPadding(dp(11), dp(7), dp(11), dp(7))
        view.background = getDrawable(R.drawable.bg_chip)
        view.isClickable = true
        view.isFocusable = false
        view.setOnClickListener { onClick() }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.marginEnd = dp(6)
        view.layoutParams = params
        return view
    }

    private fun toggleModifier(name: String) {
        if (!mods.add(name)) mods.remove(name)
        updateModifierChips()
    }

    private fun updateModifierChips() {
        for ((name, view) in modifierChips) {
            val active = name in mods
            view.isSelected = active
            view.setTextColor(getColor(if (active) R.color.on_accent else R.color.text))
        }
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (keySink.hasFocus()) {
            imm.hideSoftInputFromWindow(keySink.windowToken, 0)
            keySink.clearFocus()
        } else {
            keySink.requestFocus()
            keySink.post { imm.showSoftInput(keySink, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(keySink.windowToken, 0)
        keySink.clearFocus()
    }

    private fun sendKey(name: String) {
        if (readOnly) return
        send(RemoteInput.key(name, mods.toList()))
    }

    private fun send(payload: String) {
        client?.send(payload)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ── lifecycle ─────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (remotePanel.visibility == View.VISIBLE) {
            disconnect()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::keySink.isInitialized) hideKeyboard()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        client?.close()
        client = null
        super.onDestroy()
    }
}
