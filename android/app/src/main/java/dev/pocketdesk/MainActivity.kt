package dev.pocketdesk

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.pocketdesk.net.CaptureState
import dev.pocketdesk.net.HelloInfo
import dev.pocketdesk.net.RemoteInput
import dev.pocketdesk.net.Stats
import dev.pocketdesk.service.RemoteService
import dev.pocketdesk.session.SessionHub
import dev.pocketdesk.ui.KeySinkEditText
import dev.pocketdesk.ui.ScanActivity
import dev.pocketdesk.ui.ScreenView
import java.util.Locale

class MainActivity : Activity(), SessionHub.Listener {

    /** One toolbar button: a modifier (latched) or a key (tapped once). */
    private data class KeyDef(val name: String, val label: String, val modifier: Boolean = false)

    /** A stream preset, applied on the host without restarting it. */
    private data class Preset(val label: String, val fps: Int, val quality: Int, val scale: Double)

    private lateinit var prefs: Prefs

    private lateinit var connectPanel: View
    private lateinit var remotePanel: View
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var tokenField: EditText
    private lateinit var connectButton: Button
    private lateinit var scanButton: Button
    private lateinit var rememberBox: CheckBox
    private lateinit var statusText: TextView
    private lateinit var recentRow: View
    private lateinit var recentHost: TextView
    private lateinit var recentConnect: TextView
    private lateinit var recentForget: TextView
    private lateinit var screenView: ScreenView
    private lateinit var keySink: KeySinkEditText
    private lateinit var toolbar: View
    private lateinit var toolbarHandle: TextView
    private lateinit var toolbarButtons: LinearLayout
    private lateinit var statsText: TextView

    private var readOnly = false
    private var backgroundHintShown = false
    private var appliedForSession = false
    private var captureState: CaptureState? = null
    private val mods = linkedSetOf<String>()
    private val modifierChips = HashMap<String, TextView>()

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
        scanButton = findViewById(R.id.scanButton)
        rememberBox = findViewById(R.id.rememberBox)
        statusText = findViewById(R.id.statusText)
        recentRow = findViewById(R.id.recentRow)
        recentHost = findViewById(R.id.recentHost)
        recentConnect = findViewById(R.id.recentConnect)
        recentForget = findViewById(R.id.recentForget)
        screenView = findViewById(R.id.screenView)
        keySink = findViewById(R.id.keySink)
        toolbar = findViewById(R.id.toolbar)
        toolbarHandle = findViewById(R.id.toolbarHandle)
        toolbarButtons = findViewById(R.id.toolbarButtons)
        statsText = findViewById(R.id.statsText)

        hostField.setText(prefs.host)
        portField.setText(prefs.port.toString())
        tokenField.setText(prefs.token)
        rememberBox.isChecked = prefs.remember
        rememberBox.setOnCheckedChangeListener { _, checked -> prefs.remember = checked }
        updateRecent()

        scanButton.setOnClickListener {
            startActivityForResult(Intent(this, ScanActivity::class.java), SCAN_REQUEST)
        }
        connectButton.setOnClickListener { connect() }
        recentConnect.setOnClickListener { useSaved() }
        recentForget.setOnClickListener { forgetSaved() }
        toolbarHandle.setOnClickListener { setToolbarHidden(false) }

        screenView.onMove = { x, y -> send(RemoteInput.move(x, y)) }
        screenView.onButton = { button, down -> send(RemoteInput.button(button, down)) }
        screenView.onClick = { button -> send(RemoteInput.click(button)) }
        screenView.onScroll = { dy, dx -> send(RemoteInput.scroll(dy, dx)) }

        keySink.onText = { text -> send(RemoteInput.text(text)) }
        keySink.onKeyName = { name -> sendKey(name) }

        buildToolbar()
        applyToolbarVisibility()
        SessionHub.addListener(this)
    }

    // ── connecting ────────────────────────────────────────────────────

    private fun connect(deviceName: String = "") {
        val host = hostField.text.toString().trim().ifBlank { "127.0.0.1" }
        val port = portField.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: Prefs.DEFAULT_PORT
        val token = tokenField.text.toString().trim()

        if (token.length < 4) {
            statusText.text = "Scan the QR code, or type the 8-character code from the host."
            return
        }

        val name = deviceName.ifBlank { if (prefs.host == host) prefs.name else "" }
        if (rememberBox.isChecked) {
            prefs.save(host, port, token, name)
        } else {
            // "One-time login": nothing is kept after this session.
            prefs.clearSaved()
        }
        updateRecent()

        appliedForSession = false
        requestNotificationPermission()
        SessionHub.connect(host, port, token)
        // The foreground service keeps the process (and this session) alive when
        // the task is swiped away.
        RemoteService.start(this)
    }

    private fun useSaved() {
        if (!prefs.hasSaved) return
        hostField.setText(prefs.host)
        portField.setText(prefs.port.toString())
        tokenField.setText(prefs.token)
        connect(prefs.name)
    }

    private fun forgetSaved() {
        prefs.clearSaved()
        hostField.setText("")
        portField.setText(Prefs.DEFAULT_PORT.toString())
        tokenField.setText("")
        updateRecent()
        statusText.text = "Saved machine forgotten."
    }

    private fun updateRecent() {
        if (!prefs.hasSaved) {
            recentRow.visibility = View.GONE
            return
        }
        recentRow.visibility = View.VISIBLE
        val label = prefs.name.ifBlank { prefs.host }
        recentHost.text = "$label · ${prefs.host}:${prefs.port}"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    // ── session callbacks (main thread) ───────────────────────────────

    override fun onState(state: SessionHub.State, detail: String?) {
        when (state) {
            SessionHub.State.CONNECTING -> {
                appliedForSession = false
                connectButton.isEnabled = false
                statusText.text = getString(R.string.notification_connecting, SessionHub.target)
            }

            SessionHub.State.CONNECTED -> {
                connectButton.isEnabled = true
                showRemote()
                statsText.text = if (readOnly) "view only" else "connected"
            }

            SessionHub.State.RECONNECTING -> {
                connectButton.isEnabled = true
                statusText.text = "Connection lost — retrying…"
                statsText.text = "reconnecting…"
            }

            SessionHub.State.FAILED -> {
                connectButton.isEnabled = true
                showConnectPanel(detail ?: "Disconnected.")
            }

            SessionHub.State.IDLE -> {
                connectButton.isEnabled = true
                if (detail != null) {
                    showConnectPanel(detail)
                } else if (remotePanel.visibility == View.VISIBLE) {
                    showConnectPanel("Disconnected.")
                }
            }
        }
    }

    override fun onHello(info: HelloInfo) {
        readOnly = info.readOnly || !info.input
        captureState = CaptureState(
            fps = info.fps,
            quality = info.quality,
            scale = 1.0,
            mode = info.mode,
            width = 0,
            height = 0,
            preset = "",
        )
        showRemote()
        statsText.text = if (readOnly) "view only" else "${info.fps} fps · q${info.quality}"

        if (!info.input && !info.readOnly) {
            toast(info.inputError?.substringBefore('\n') ?: "Host is view-only")
        }

        // Re-apply this phone's choices once per connection. The host forgets
        // them when it restarts, so sending them on every connect keeps the
        // stream the way the user set it.
        if (!appliedForSession) {
            appliedForSession = true
            if (prefs.quality > 0) {
                SessionHub.setQuality(
                    prefs.fps.takeIf { it > 0 },
                    prefs.quality,
                    prefs.scale.toDouble(),
                )
            }
            if (prefs.secondScreen) {
                val (w, h) = phoneTarget()
                SessionHub.setDisplay("extend", w, h)
            }
        }
    }

    override fun onFrame(bitmap: Bitmap) {
        screenView.setFrame(bitmap)
    }

    override fun onStats(stats: Stats) {
        val second = captureState?.mode == "extend" || prefs.secondScreen
        val prefix = if (second) getString(R.string.mode_second_screen) + " · " else ""
        statsText.text = if (readOnly) {
            String.format(Locale.US, "%sview only\n%.0f fps", prefix, stats.fps)
        } else {
            String.format(Locale.US, "%s%.0f fps · %d kbps", prefix, stats.fps, stats.kbps)
        }
    }

    override fun onCapture(state: CaptureState) {
        val previous = captureState
        captureState = state

        if (state.mode == "extend" && state.width > 0 && previous?.mode != "extend") {
            toast(getString(R.string.second_screen_ready, state.width, state.height))
        }
        if (state.mode == "mirror" && previous?.mode == "extend" && prefs.secondScreen) {
            // The host dropped the virtual monitor (for example after a restart).
            prefs.secondScreen = false
            toast(getString(R.string.mirroring_again))
        }
    }

    override fun onError(message: String) {
        toast(message)
        if (message.contains("virtual monitor", ignoreCase = true) ||
            message.contains("second-screen", ignoreCase = true)
        ) {
            prefs.secondScreen = false
        }
    }

    // ── switching screens ─────────────────────────────────────────────

    private fun showRemote() {
        if (remotePanel.visibility != View.VISIBLE) {
            connectPanel.visibility = View.GONE
            remotePanel.visibility = View.VISIBLE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        applyToolbarVisibility()
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
        applyToolbarVisibility()
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

    // ── stopping ──────────────────────────────────────────────────────

    private fun confirmStop() {
        if (!SessionHub.isLive) {
            stopSession()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.stop_title)
            .setMessage(getString(R.string.stop_message, SessionHub.target))
            .setPositiveButton(R.string.stop_confirm) { _, _ -> stopSession() }
            .setNegativeButton(R.string.stop_cancel, null)
            .show()
    }

    private fun stopSession() {
        SessionHub.stop(getString(R.string.notification_stopped))
        RemoteService.stop(this)
        appliedForSession = false
        captureState = null
        showConnectPanel("Disconnected.")
    }

    // ── toolbar ───────────────────────────────────────────────────────

    private fun buildToolbar() {
        toolbarButtons.removeAllViews()
        modifierChips.clear()
        mods.clear()

        toolbarButtons.addView(chip("Keys") { toggleKeyboard() })

        val selected = prefs.keyList.ifEmpty { DEFAULT_KEYS }
        for (name in selected) {
            val def = KEY_CATALOG.firstOrNull { it.name == name } ?: continue
            val view = chip(def.label) {
                if (def.modifier) toggleModifier(def.name) else sendKey(def.name)
            }
            if (def.modifier) modifierChips[def.name] = view
            toolbarButtons.addView(view)
        }

        toolbarButtons.addView(chip("⋯") { showSessionMenu() })
        toolbarButtons.addView(chip("Exit") { confirmStop() })
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

    private fun applyToolbarVisibility() {
        val remote = remotePanel.visibility == View.VISIBLE
        val hidden = remote && prefs.toolbarHidden
        toolbar.visibility = if (hidden) View.GONE else View.VISIBLE
        toolbarHandle.visibility = if (hidden) View.VISIBLE else View.GONE
    }

    private fun setToolbarHidden(hidden: Boolean) {
        prefs.toolbarHidden = hidden
        applyToolbarVisibility()
        if (hidden) toast("Toolbar hidden — tap ☰ to bring it back.")
    }

    private fun showSessionMenu() {
        val items = arrayOf(
            getString(R.string.menu_keys),
            getString(R.string.menu_quality),
            if (prefs.secondScreen) getString(R.string.menu_mirror) else getString(R.string.menu_extend),
            getString(R.string.menu_hide_toolbar),
        )

        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickKeys()
                    1 -> pickQuality()
                    2 -> toggleSecondScreen()
                    3 -> setToolbarHidden(true)
                }
            }
            .show()
    }

    private fun pickKeys() {
        val selected = prefs.keyList.ifEmpty { DEFAULT_KEYS }.toMutableSet()
        val labels = KEY_CATALOG.map { it.label }.toTypedArray()
        val checked = KEY_CATALOG.map { it.name in selected }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.pick_keys)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val name = KEY_CATALOG[which].name
                if (isChecked) selected.add(name) else selected.remove(name)
            }
            .setPositiveButton(R.string.done) { _, _ ->
                prefs.keys = KEY_CATALOG.filter { it.name in selected }
                    .joinToString(",") { it.name }
                buildToolbar()
            }
            .setNeutralButton(R.string.reset_default) { _, _ ->
                prefs.keys = DEFAULT_KEYS.joinToString(",")
                buildToolbar()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickQuality() {
        val current = captureState
        val checked = PRESETS.indexOfFirst {
            it.fps == current?.fps && it.quality == current?.quality
        }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.quality_title)
            .setSingleChoiceItems(PRESETS.map { it.label }.toTypedArray(), checked) { dialog, which ->
                val preset = PRESETS[which]
                prefs.saveStream(preset.fps, preset.quality, preset.scale)
                SessionHub.setQuality(preset.fps, preset.quality, preset.scale)
                toast(getString(R.string.stream_now, preset.fps, preset.quality))
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleSecondScreen() {
        if (prefs.secondScreen) {
            prefs.secondScreen = false
            SessionHub.setDisplay("mirror", 0, 0)
            toast(getString(R.string.mirroring_again))
            return
        }

        val (width, height) = phoneTarget()
        prefs.secondScreen = true
        SessionHub.setDisplay("extend", width, height)
        toast(getString(R.string.second_screen_asking, width, height))
    }

    /**
     * The virtual monitor's size: the phone's aspect ratio at 1600 px on the
     * long edge, even numbers only. 1600x720 for a 20:9 display, which is
     * ~1.4 MB/s at 15 fps — a comfortable fit for Wi-Fi.
     */
    private fun phoneTarget(): Pair<Int, Int> {
        val bounds: Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val metrics = resources.displayMetrics
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }

        val longSide = maxOf(bounds.width(), bounds.height()).toDouble()
        val shortSide = minOf(bounds.width(), bounds.height()).toDouble()
        if (longSide <= 0.0 || shortSide <= 0.0) return 1600 to 900

        val factor = (1600.0 / longSide).coerceAtMost(1.0)
        val width = (((longSide * factor).toInt()) / 2) * 2
        val height = (((shortSide * factor).toInt()) / 2) * 2
        return width.coerceAtLeast(640) to height.coerceAtLeast(360)
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
        if (readOnly) return
        SessionHub.send(payload)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ── lifecycle ─────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (remotePanel.visibility == View.VISIBLE) {
            // Keep the session; it lives in the foreground service.
            moveTaskToBack(true)
            if (!backgroundHintShown) {
                backgroundHintShown = true
                toast(getString(R.string.background_hint))
            }
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        if (SessionHub.isLive) {
            showRemote()
            SessionHub.lastStats?.let { onStats(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::keySink.isInitialized) hideKeyboard()
    }

    override fun onDestroy() {
        SessionHub.removeListener(this)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SCAN_REQUEST || resultCode != RESULT_OK) return

        val raw = data?.getStringExtra(ScanActivity.EXTRA_URI) ?: return
        val uri = Uri.parse(raw)
        if (uri.scheme != "pocketdesk") {
            toast(getString(R.string.not_a_pocketdesk_code))
            return
        }

        val host = uri.getQueryParameter("h") ?: uri.getQueryParameter("host") ?: return
        val port = (uri.getQueryParameter("p") ?: uri.getQueryParameter("port"))
            ?.toIntOrNull() ?: Prefs.DEFAULT_PORT
        val token = uri.getQueryParameter("t") ?: uri.getQueryParameter("token") ?: return
        val name = uri.getQueryParameter("n") ?: uri.getQueryParameter("name").orEmpty()

        hostField.setText(host)
        portField.setText(port.toString())
        tokenField.setText(token)

        statusText.text = if (name.isBlank()) "Paired with $host" else "Paired with $name"
        connect(name)
    }

    private companion object {
        const val SCAN_REQUEST = 42
        const val REQUEST_NOTIFICATIONS = 43

        /** Everything the picker can put in the toolbar. Names match the host. */
        val KEY_CATALOG = listOf(
            KeyDef("ctrl", "Ctrl", true),
            KeyDef("alt", "Alt", true),
            KeyDef("shift", "Shift", true),
            KeyDef("super", "Super", true),
            KeyDef("esc", "Esc"),
            KeyDef("tab", "Tab"),
            KeyDef("enter", "Enter"),
            KeyDef("backspace", "Bksp"),
            KeyDef("space", "Space"),
            KeyDef("delete", "Del"),
            KeyDef("insert", "Ins"),
            KeyDef("home", "Home"),
            KeyDef("end", "End"),
            KeyDef("pageup", "PgUp"),
            KeyDef("pagedown", "PgDn"),
            KeyDef("left", "←"),
            KeyDef("up", "↑"),
            KeyDef("down", "↓"),
            KeyDef("right", "→"),
            KeyDef("printscreen", "PrtSc"),
            KeyDef("f1", "F1"),
            KeyDef("f2", "F2"),
            KeyDef("f3", "F3"),
            KeyDef("f4", "F4"),
            KeyDef("f5", "F5"),
            KeyDef("f6", "F6"),
            KeyDef("f7", "F7"),
            KeyDef("f8", "F8"),
            KeyDef("f9", "F9"),
            KeyDef("f10", "F10"),
            KeyDef("f11", "F11"),
            KeyDef("f12", "F12"),
        )

        val DEFAULT_KEYS = listOf(
            "ctrl", "alt", "shift", "super",
            "esc", "tab", "enter", "backspace",
            "left", "up", "down", "right",
        )

        val PRESETS = listOf(
            Preset("Low data · 10 fps · q45 · 0.75×", 10, 45, 0.75),
            Preset("Balanced · 15 fps · q65", 15, 65, 1.0),
            Preset("Sharp · 20 fps · q82", 20, 82, 1.0),
            Preset("Smooth · 30 fps · q60", 30, 60, 1.0),
        )
    }
}
