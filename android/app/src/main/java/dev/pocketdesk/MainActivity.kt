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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import dev.pocketdesk.net.CaptureState
import dev.pocketdesk.net.HelloInfo
import dev.pocketdesk.net.RemoteInput
import dev.pocketdesk.net.Stats
import dev.pocketdesk.service.RemoteService
import dev.pocketdesk.session.AudioHub
import dev.pocketdesk.session.SessionHub
import dev.pocketdesk.ui.KeySinkEditText
import dev.pocketdesk.ui.ScanActivity
import dev.pocketdesk.ui.ScreenView
import dev.pocketdesk.updates.CheckResult
import dev.pocketdesk.updates.UpdateInfo
import dev.pocketdesk.updates.Updater
import java.io.File
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
    private lateinit var usbBox: CheckBox
    private lateinit var usbHint: TextView
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
    private lateinit var updateRow: View
    private lateinit var updateTitle: TextView
    private lateinit var updateDetail: TextView
    private lateinit var updateAction: TextView
    private lateinit var updateLater: TextView

    private val updater by lazy { Updater(this) }
    private var pendingUpdate: UpdateInfo? = null
    private var pendingApk: File? = null
    private var awaitingExtend = false
    private var audioPlaying = false
    private var audioUnavailableShown = false

    private var readOnly = false
    private var backgroundHintShown = false
    private var appliedForSession = false
    private var resumed = false

    // Auto-quality: compare what the host sent with what this phone managed to
    // draw. Two bad windows in a row drop one preset, once per session.
    private val ui = Handler(Looper.getMainLooper())
    private var renderedFrames = 0
    private var healthStart = 0L
    private var slowWindows = 0
    private var autoDowngraded = false
    private var captureState: CaptureState? = null
    private val mods = linkedSetOf<String>()
    private val modifierChips = HashMap<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        AudioHub.ensure(this)
        AudioHub.onState = { playing, detail ->
            runOnUiThread {
                audioPlaying = playing
                if (!playing && detail != null) {
                    toast(getString(R.string.audio_failed, detail))
                }
            }
        }

        connectPanel = findViewById(R.id.connectScroll)
        remotePanel = findViewById(R.id.remotePanel)
        hostField = findViewById(R.id.hostField)
        portField = findViewById(R.id.portField)
        tokenField = findViewById(R.id.tokenField)
        connectButton = findViewById(R.id.connectButton)
        scanButton = findViewById(R.id.scanButton)
        rememberBox = findViewById(R.id.rememberBox)
        usbBox = findViewById(R.id.usbBox)
        usbHint = findViewById(R.id.usbHint)
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
        updateRow = findViewById(R.id.updateRow)
        updateTitle = findViewById(R.id.updateTitle)
        updateDetail = findViewById(R.id.updateDetail)
        updateAction = findViewById(R.id.updateAction)
        updateLater = findViewById(R.id.updateLater)

        hostField.setText(prefs.wifiHost.ifBlank { prefs.host })
        portField.setText(prefs.port.toString())
        tokenField.setText(prefs.token)
        rememberBox.isChecked = prefs.remember
        rememberBox.setOnCheckedChangeListener { _, checked -> prefs.remember = checked }

        usbBox.isChecked = prefs.usbCable
        setUsbMode(prefs.usbCable, applyPreset = false)
        usbBox.setOnCheckedChangeListener { _, checked -> setUsbMode(checked, applyPreset = true) }
        updateRecent()

        scanButton.setOnClickListener {
            startActivityForResult(Intent(this, ScanActivity::class.java), SCAN_REQUEST)
        }
        connectButton.setOnClickListener { connect() }
        recentConnect.setOnClickListener { useSaved() }
        recentForget.setOnClickListener { forgetSaved() }
        toolbarHandle.setOnClickListener { setToolbarHidden(false) }
        updateAction.setOnClickListener { startUpdate() }
        updateLater.setOnClickListener { updateRow.visibility = View.GONE }

        screenView.onMove = { x, y -> send(RemoteInput.move(x, y)) }
        screenView.onMoveRel = { dx, dy -> send(RemoteInput.moveRel(dx, dy)) }
        screenView.onButton = { button, down, pad ->
            send(RemoteInput.button(button, down, pad))
        }
        screenView.onClick = { button, pad -> send(RemoteInput.click(button, pad)) }
        screenView.onScroll = { dy, dx, pad -> send(RemoteInput.scroll(dy, dx, pad)) }

        keySink.onText = { text -> send(RemoteInput.text(text)) }
        keySink.onKeyName = { name -> sendKey(name) }

        screenView.setFill(prefs.fillScreen)
        applyInputMode()
        buildToolbar()
        applyToolbarVisibility()
        SessionHub.addListener(this)

        // Quiet check on launch: costs one HEAD-sized request, at most every
        // few hours, and never blocks the connect screen.
        maybeCheckForUpdates(force = false)
    }

    // ── connecting ────────────────────────────────────────────────────

    private fun connect(deviceName: String = "") {
        val fieldHost = hostField.text.toString().trim()
        val usb = prefs.usbCable
        val host = if (usb) "127.0.0.1" else fieldHost.ifBlank { "127.0.0.1" }
        val port = portField.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: Prefs.DEFAULT_PORT
        val token = tokenField.text.toString().trim()

        if (!usb && host != "127.0.0.1") prefs.wifiHost = host

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

    /**
     * USB mode connects to the host through `adb reverse`, so the address is
     * always localhost; the saved Wi-Fi address is kept separately.
     */
    private fun setUsbMode(enabled: Boolean, applyPreset: Boolean) {
        if (enabled) {
            val typed = hostField.text.toString().trim()
            if (typed.isNotEmpty() && typed != "127.0.0.1") prefs.wifiHost = typed
        }

        prefs.usbCable = enabled
        usbHint.visibility = if (enabled) View.VISIBLE else View.GONE
        hostField.isEnabled = !enabled
        if (enabled) {
            hostField.setText("127.0.0.1")
        } else {
            val lan = prefs.wifiHost.ifBlank { prefs.host }
            hostField.setText(if (lan == "127.0.0.1") "" else lan)
        }

        if (!applyPreset) return
        if (enabled) {
            prefs.saveStream(30, 90, 1.0)
            if (SessionHub.isLive) SessionHub.setQuality(30, 90, 1.0)
            toast(getString(R.string.usb_quality))
        } else if (prefs.fps == 30 && prefs.quality == 90) {
            // Only step down if the cable preset is what is active.
            prefs.saveStream(15, 65, 1.0)
            if (SessionHub.isLive) SessionHub.setQuality(15, 65, 1.0)
        }
    }

    private fun useSaved() {
        if (!prefs.hasSaved) return
        hostField.setText(
            if (prefs.usbCable) "127.0.0.1"
            else prefs.wifiHost.ifBlank { prefs.host },
        )
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
        val shownHost = prefs.wifiHost.ifBlank { prefs.host }
        recentHost.text = "$label · $shownHost:${prefs.port}"
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
                stopAudio()
                showConnectPanel(detail ?: "Disconnected.")
            }

            SessionHub.State.IDLE -> {
                connectButton.isEnabled = true
                stopAudio()
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
                awaitingExtend = true
                SessionHub.setDisplay("extend", w, h)
            }
        }

        if (prefs.audioOnPhone) startAudio()
    }

    override fun onFrame(bitmap: Bitmap) {
        renderedFrames++
        screenView.setFrame(bitmap)
    }

    override fun onStats(stats: Stats) {
        val second = captureState?.mode == "extend" || prefs.secondScreen
        val usb = if (prefs.usbCable) getString(R.string.mode_usb) + " · " else ""
        val prefix = usb + if (second) getString(R.string.mode_second_screen) + " · " else ""
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
        if (awaitingExtend) {
            awaitingExtend = false
            if (state.mode != "extend") {
                // We asked for a virtual monitor and the host stayed on mirror
                // (no Hyprland, or the request failed): do not keep asking.
                prefs.secondScreen = false
                toast(getString(R.string.second_screen_needs_host))
            }
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
        if (resumed) startHealthCheck()
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
        stopHealthCheck()
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
        stopAudio()
        SessionHub.stop(getString(R.string.notification_stopped))
        RemoteService.stop(this)
        appliedForSession = false
        captureState = null
        showConnectPanel("Disconnected.")
    }

    // ── updates ───────────────────────────────────────────────────────

    private fun maybeCheckForUpdates(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.lastUpdateCheck < UPDATE_CHECK_INTERVAL) return
        prefs.lastUpdateCheck = now

        updater.check { result ->
            when (result) {
                is CheckResult.Available -> showUpdateCard(result.info)
                CheckResult.UpToDate -> if (force) toast(getString(R.string.update_up_to_date))
                is CheckResult.Failed ->
                    if (force) toast(getString(R.string.update_check_failed, result.message))
            }
        }
    }

    private fun showUpdateCard(info: UpdateInfo) {
        pendingUpdate = info
        updateRow.visibility = View.VISIBLE
        updateTitle.setText(R.string.update_available)
        updateAction.text = getString(R.string.update_install)
        updateAction.isEnabled = true
        updateLater.visibility = View.VISIBLE
        updateDetail.text = getString(
            R.string.update_detail,
            info.version,
            formatSize(info.size),
        )
    }

    private fun startUpdate() {
        val ready = pendingApk
        if (ready != null && ready.exists()) {
            installUpdate(ready)
            return
        }

        val info = pendingUpdate ?: return
        if (SessionHub.isLive) {
            AlertDialog.Builder(this)
                .setTitle(R.string.update_session_title)
                .setMessage(getString(R.string.update_session_message, info.version))
                .setPositiveButton(R.string.update_session_confirm) { _, _ ->
                    stopSession()
                    downloadUpdate(info)
                }
                .setNegativeButton(R.string.update_later, null)
                .show()
            return
        }

        downloadUpdate(info)
    }

    private fun downloadUpdate(info: UpdateInfo) {
        updateAction.isEnabled = false
        updateAction.text = "0%"
        updateLater.visibility = View.GONE
        updateTitle.setText(R.string.update_downloading)

        updater.download(
            info,
            onProgress = { percent -> updateAction.text = "$percent%" },
            onDone = { file, error ->
                updateAction.isEnabled = true
                updateAction.text = getString(R.string.update_install)
                updateTitle.setText(R.string.update_available)

                if (file == null) {
                    updateLater.visibility = View.VISIBLE
                    toast(getString(R.string.update_failed, error ?: "unknown error"))
                    return@download
                }
                pendingApk = file
                installUpdate(file)
            },
        )
    }

    private fun installUpdate(file: File) {
        if (!updater.canInstall()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setPositiveButton(R.string.update_permission_open) { _, _ ->
                    runCatching { startActivity(updater.unknownSourcesSettings()) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        if (updater.install(file)) {
            toast(getString(R.string.update_ready))
        } else {
            // No handler for package:// — send people to the release page.
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, updater.releasesPage())) }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "APK"
        val mb = bytes / 1_000_000.0
        return String.format(Locale.US, "%.1f MB", mb)
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
            getString(
                if (prefs.inputMode == "touchpad") R.string.menu_input_touchpad
                else R.string.menu_input_touch,
            ),
            getString(
                if (prefs.audioOnPhone) R.string.menu_audio_phone
                else R.string.menu_audio_machine,
            ),
            if (prefs.secondScreen) getString(R.string.menu_mirror) else getString(R.string.menu_extend),
            getString(R.string.menu_hide_toolbar),
            if (prefs.fillScreen) getString(R.string.menu_fit) else getString(R.string.menu_fill),
            getString(R.string.menu_check_updates),
        )

        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickKeys()
                    1 -> pickQuality()
                    2 -> pickInputMode()
                    3 -> pickAudio()
                    4 -> toggleSecondScreen()
                    5 -> setToolbarHidden(true)
                    6 -> toggleFill()
                    7 -> maybeCheckForUpdates(force = true)
                }
            }
            .show()
    }

    // ── input modes ───────────────────────────────────────────────────

    private fun applyInputMode() {
        val mode = if (prefs.inputMode == "touchpad") {
            ScreenView.InputMode.TOUCHPAD
        } else {
            ScreenView.InputMode.TOUCH
        }
        screenView.setInputMode(mode)
        screenView.setPadOptions(prefs.padSpeed, prefs.naturalScroll, prefs.tapToClick)
    }

    private fun pickInputMode() {
        val modes = arrayOf(
            getString(R.string.input_touch),
            getString(R.string.input_touchpad),
        )
        val checked = if (prefs.inputMode == "touchpad") 1 else 0

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.input_mode_title)
            .setSingleChoiceItems(modes, checked) { _, which ->
                prefs.inputMode = if (which == 1) "touchpad" else "touch"
                applyInputMode()
                toast(
                    if (which == 1) getString(R.string.touchpad_hint)
                    else getString(R.string.touch_hint),
                )
            }
            .setPositiveButton(R.string.done, null)
            .setNeutralButton(R.string.touchpad_options, null)
            .create()

        dialog.setOnShowListener {
            // Neutral is "options"; do not dismiss when it is used.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                pickTouchpadOptions()
            }
        }
        dialog.show()
    }

    private fun startAudio() {
        if (!SessionHub.isLive) return
        val info = SessionHub.hello
        if (info != null && !info.audio) {
            if (!audioUnavailableShown) {
                audioUnavailableShown = true
                toast(getString(R.string.audio_failed, "the host has no parec/pulseaudio-utils"))
            }
            return
        }
        val url = SessionHub.audioUrl(prefs.muteHost) ?: return
        AudioHub.start(this, url)
    }

    private fun stopAudio() {
        AudioHub.stop()
        audioPlaying = false
    }

    private fun pickAudio() {
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val machine = RadioButton(this).apply {
            text = getString(R.string.audio_machine)
            isChecked = !prefs.audioOnPhone
        }
        val phone = RadioButton(this).apply {
            text = getString(R.string.audio_phone)
            isChecked = prefs.audioOnPhone
        }
        group.addView(machine)
        group.addView(phone)

        val mute = CheckBox(this).apply {
            text = getString(R.string.option_mute_host)
            isChecked = prefs.muteHost
            isEnabled = phone.isChecked
            setPadding(0, dp(8), 0, 0)
        }
        phone.setOnCheckedChangeListener { _, checked -> mute.isEnabled = checked }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(group)
            addView(mute)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.audio_title)
            .setView(layout)
            .setPositiveButton(R.string.done) { _, _ ->
                prefs.audioOnPhone = phone.isChecked
                prefs.muteHost = mute.isChecked
                if (prefs.audioOnPhone) {
                    startAudio()
                    toast(getString(R.string.audio_hint))
                } else {
                    stopAudio()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickTouchpadOptions() {
        val labels = arrayOf(
            getString(R.string.option_tap_to_click),
            getString(R.string.option_natural_scroll),
        )
        val checked = booleanArrayOf(prefs.tapToClick, prefs.naturalScroll)

        AlertDialog.Builder(this)
            .setTitle(R.string.touchpad_options)
            .setMultiChoiceItems(labels, checked) { _, which, value ->
                when (which) {
                    0 -> prefs.tapToClick = value
                    1 -> prefs.naturalScroll = value
                }
                applyInputMode()
            }
            .setPositiveButton(R.string.done, null)
            .setNeutralButton(R.string.pointer_speed) { _, _ -> pickPadSpeed() }
            .show()
    }

    private fun pickPadSpeed() {
        val labels = arrayOf(
            getString(R.string.speed_slow),
            getString(R.string.speed_normal),
            getString(R.string.speed_fast),
        )
        val speeds = floatArrayOf(1.0f, 1.6f, 2.4f)
        val checked = speeds.indexOfFirst { kotlin.math.abs(it - prefs.padSpeed) < 0.05f }
            .coerceAtLeast(1)

        AlertDialog.Builder(this)
            .setTitle(R.string.pointer_speed)
            .setSingleChoiceItems(labels, checked) { _, which ->
                prefs.padSpeed = speeds[which]
                applyInputMode()
            }
            .setPositiveButton(R.string.done, null)
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

    private fun startHealthCheck() {
        ui.removeCallbacks(healthCheck)
        renderedFrames = 0
        slowWindows = 0
        healthStart = SystemClock.elapsedRealtime()
        ui.postDelayed(healthCheck, HEALTH_INTERVAL)
    }

    private fun stopHealthCheck() {
        ui.removeCallbacks(healthCheck)
    }

    private val healthCheck = object : Runnable {
        override fun run() {
            if (!resumed || !SessionHub.isLive) return

            val now = SystemClock.elapsedRealtime()
            val elapsed = (now - healthStart).coerceAtLeast(1)
            val rendered = renderedFrames * 1000.0 / elapsed
            renderedFrames = 0
            healthStart = now

            val stats = SessionHub.lastStats
            // Only judge when the host is actually sending something: a static
            // desktop legitimately delivers almost no frames.
            if (stats != null && stats.fps >= 8 && rendered < stats.fps * 0.6) {
                slowWindows++
                if (slowWindows >= 2 && !autoDowngraded) {
                    autoDowngraded = true
                    autoDowngrade()
                }
            } else {
                slowWindows = 0
            }

            ui.postDelayed(this, HEALTH_INTERVAL)
        }
    }

    /**
     * The phone could not keep up for ~10 s. Step down one preset for this
     * session (nothing is saved, so the next session starts where you left it).
     */
    private fun autoDowngrade() {
        val current = prefs.quality.takeIf { it > 0 } ?: captureState?.quality ?: 65
        val index = PRESETS.indexOfFirst { it.quality == current }
        val next = when {
            // The host can start at any quality (75 by default), which matches no
            // preset. Step down relative to what it is sending.
            index < 0 -> if (current > 50) PRESETS[1] else PRESETS[0]
            PRESETS[index].fps >= 25 -> PRESETS[1]   // smooth -> balanced
            index == 2 -> PRESETS[1]                 // sharp  -> balanced
            index == 1 -> PRESETS[0]                 // balanced -> low data
            else -> null
        } ?: return

        SessionHub.setQuality(next.fps, next.quality, next.scale)
        toast("Slow link — switched to ${next.fps} fps, quality ${next.quality}. Change it in ⋯ → Quality.")
    }

    private fun toggleFill() {
        prefs.fillScreen = !prefs.fillScreen
        screenView.setFill(prefs.fillScreen)
        toast(
            if (prefs.fillScreen) "Fill: sides cropped, no black bars."
            else "Fit: whole screen visible, with black bars.",
        )
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
        awaitingExtend = true
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

        // USB carries far more than Wi-Fi, so the virtual monitor gets more pixels.
        val targetLongEdge = if (prefs.usbCable) 2400.0 else 1600.0
        val factor = (targetLongEdge / longSide).coerceAtMost(1.0)
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
        resumed = true
        // Frames were paused while we were away; ask for them again.
        SessionHub.setVisible(true)
        if (SessionHub.isLive) {
            showRemote()
            SessionHub.lastStats?.let { onStats(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        // No point decoding or transferring frames nobody can see.
        SessionHub.setVisible(false)
        stopHealthCheck()
        if (::keySink.isInitialized) hideKeyboard()
    }

    override fun onDestroy() {
        SessionHub.removeListener(this)
        // Audio is process-wide: if the session is still alive it keeps playing
        // in the background. Only detach the UI callback.
        AudioHub.onState = null
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

        if (host != "127.0.0.1") prefs.wifiHost = host
        if (!prefs.usbCable) hostField.setText(host)
        portField.setText(port.toString())
        tokenField.setText(token)

        statusText.text = if (name.isBlank()) "Paired with $host" else "Paired with $name"
        connect(name)
    }

    private companion object {
        const val SCAN_REQUEST = 42
        const val REQUEST_NOTIFICATIONS = 43
        const val HEALTH_INTERVAL = 5_000L

        /** At most one GitHub request every six hours unless asked explicitly. */
        const val UPDATE_CHECK_INTERVAL = 6 * 60 * 60 * 1000L

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
            Preset("Cable · 30 fps · q90", 30, 90, 1.0),
        )
    }
}
