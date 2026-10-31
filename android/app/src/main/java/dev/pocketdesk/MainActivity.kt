package dev.pocketdesk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import dev.pocketdesk.net.CaptureState
import dev.pocketdesk.net.Discovery
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

class MainActivity : AppCompatActivity(), SessionHub.Listener {

    /** One toolbar button: a modifier (latched) or a key (tapped once). */
    private data class KeyDef(val name: String, val label: String, val modifier: Boolean = false)

    /** A stream preset, applied on the host without restarting it. */
    private data class Preset(val label: String, val fps: Int, val quality: Int, val scale: Double)

    private lateinit var prefs: Prefs

    private lateinit var connectPanel: View
    private lateinit var remotePanel: View
    private lateinit var homeTab: View
    private lateinit var devicesTab: View
    private lateinit var settingsTab: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var tokenField: EditText
    private lateinit var connectButton: Button
    private lateinit var scanButton: Button
    private lateinit var rememberBox: CheckBox
    private lateinit var usbBox: CheckBox
    private lateinit var usbHint: TextView
    private lateinit var discoveryStatus: TextView
    private lateinit var discoveredList: LinearLayout
    private lateinit var discoverAgain: Button
    private lateinit var statusText: TextView
    private lateinit var recentRow: View
    private lateinit var recentHost: TextView
    private lateinit var recentConnect: Button
    private lateinit var recentForget: Button
    private lateinit var screenView: ScreenView
    private lateinit var keySink: KeySinkEditText
    private lateinit var toolbar: View
    private lateinit var toolbarHandle: View
    private lateinit var toolbarButtons: LinearLayout
    private lateinit var statsText: TextView
    private lateinit var updateRow: View
    private lateinit var updateTitle: TextView
    private lateinit var updateDetail: TextView
    private lateinit var updateAction: Button
    private lateinit var updateLater: Button

    // Devices tab
    private lateinit var deviceName: TextView
    private lateinit var deviceAddress: TextView
    private lateinit var deviceTransport: TextView
    private lateinit var deviceActions: View
    private lateinit var deviceConnect: Button
    private lateinit var deviceForget: Button
    private lateinit var deviceScan: Button
    private lateinit var deviceManual: Button
    private lateinit var deviceUsbSwitch: SwitchMaterial
    private lateinit var deviceUsbHint: TextView

    // Settings tab
    private lateinit var settingsQuality: View
    private lateinit var settingsQualityValue: TextView
    private lateinit var settingsScreen: View
    private lateinit var settingsScreenValue: TextView
    private lateinit var settingsInput: View
    private lateinit var settingsInputValue: TextView
    private lateinit var settingsScroll: View
    private lateinit var settingsScrollValue: TextView
    private lateinit var settingsAudio: View
    private lateinit var settingsAudioValue: TextView
    private lateinit var settingsKeys: View
    private lateinit var settingsKeysValue: TextView
    private lateinit var settingsSecondSwitch: SwitchMaterial
    private lateinit var settingsUpdates: View
    private lateinit var settingsSource: View
    private lateinit var settingsVersion: TextView

    private var syncingUsb = false
    private var syncingSecond = false

    private val updater by lazy { Updater(this) }
    private var pendingUpdate: UpdateInfo? = null
    private var pendingApk: File? = null
    private var awaitingExtend = false
    private val discovery by lazy {
        Discovery { hosts -> runOnUiThread { renderDiscovered(hosts) } }
    }
    private var discoveredHosts: List<Discovery.Host> = emptyList()
    private val discoveryTimeout = Runnable {
        if (!isFinishing && !isDestroyed && discoveredHosts.isEmpty()) {
            discoveryStatus.text = getString(R.string.discovery_none)
        }
    }
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
    private val modifierChips = HashMap<String, MaterialButton>()

    private val hideToolbarRunnable = Runnable { collapseToolbar(animate = true) }

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

        // Edge-to-edge: the shell pads itself by the system bars and the IME;
        // the stream draws under everything.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        connectPanel = findViewById(R.id.appShell)
        homeTab = findViewById(R.id.homeTab)
        devicesTab = findViewById(R.id.devicesTab)
        settingsTab = findViewById(R.id.settingsTab)
        bottomNav = findViewById(R.id.bottomNav)
        remotePanel = findViewById(R.id.remotePanel)
        hostField = findViewById(R.id.hostField)
        portField = findViewById(R.id.portField)
        tokenField = findViewById(R.id.tokenField)
        connectButton = findViewById(R.id.connectButton)
        scanButton = findViewById(R.id.scanButton)
        rememberBox = findViewById(R.id.rememberBox)
        usbBox = findViewById(R.id.usbBox)
        usbHint = findViewById(R.id.usbHint)
        discoveryStatus = findViewById(R.id.discoveryStatus)
        discoveredList = findViewById(R.id.discoveredList)
        discoverAgain = findViewById(R.id.discoverAgain)
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

        deviceName = findViewById(R.id.deviceName)
        deviceAddress = findViewById(R.id.deviceAddress)
        deviceTransport = findViewById(R.id.deviceTransport)
        deviceActions = findViewById(R.id.deviceActions)
        deviceConnect = findViewById(R.id.deviceConnect)
        deviceForget = findViewById(R.id.deviceForget)
        deviceScan = findViewById(R.id.deviceScan)
        deviceManual = findViewById(R.id.deviceManual)
        deviceUsbSwitch = findViewById(R.id.deviceUsbSwitch)
        deviceUsbHint = findViewById(R.id.deviceUsbHint)

        settingsQuality = findViewById(R.id.settingsQuality)
        settingsQualityValue = findViewById(R.id.settingsQualityValue)
        settingsScreen = findViewById(R.id.settingsScreen)
        settingsScreenValue = findViewById(R.id.settingsScreenValue)
        settingsInput = findViewById(R.id.settingsInput)
        settingsInputValue = findViewById(R.id.settingsInputValue)
        settingsScroll = findViewById(R.id.settingsScroll)
        settingsScrollValue = findViewById(R.id.settingsScrollValue)
        settingsAudio = findViewById(R.id.settingsAudio)
        settingsAudioValue = findViewById(R.id.settingsAudioValue)
        settingsKeys = findViewById(R.id.settingsKeys)
        settingsKeysValue = findViewById(R.id.settingsKeysValue)
        settingsSecondSwitch = findViewById(R.id.settingsSecondSwitch)
        settingsUpdates = findViewById(R.id.settingsUpdates)
        settingsSource = findViewById(R.id.settingsSource)
        settingsVersion = findViewById(R.id.settingsVersion)

        val root = findViewById<View>(R.id.root)
        // Re-run after every layout pass; the guard inside makes it a no-op
        // unless the window width (or orientation) actually changed.
        root.viewTreeObserver.addOnGlobalLayoutListener { applyContentGutter() }
        val shell = connectPanel
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            shell.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            (toolbar.layoutParams as? FrameLayout.LayoutParams)?.topMargin = cutout.top + dp(10)
            (toolbarHandle.layoutParams as? FrameLayout.LayoutParams)?.topMargin =
                cutout.top + dp(10)
            insets
        }

        hostField.setText(prefs.wifiHost.ifBlank { prefs.host })
        portField.setText(prefs.port.toString())
        tokenField.setText(prefs.token)
        rememberBox.isChecked = prefs.remember
        rememberBox.setOnCheckedChangeListener { _, checked -> prefs.remember = checked }

        setUsbMode(prefs.usbCable, applyPreset = false)
        usbBox.setOnCheckedChangeListener { _, checked ->
            if (!syncingUsb) setUsbMode(checked, applyPreset = true)
        }
        deviceUsbSwitch.setOnCheckedChangeListener { _, checked ->
            if (!syncingUsb) setUsbMode(checked, applyPreset = true)
        }

        // ── navigation ─────────────────────────────────────────────────
        bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        applyContentGutter()
        showTab(R.id.nav_home)

        scanButton.setOnClickListener { scan() }
        deviceScan.setOnClickListener { scan() }
        connectButton.setOnClickListener { connect() }
        recentConnect.setOnClickListener { useSaved() }
        recentForget.setOnClickListener { forgetSaved() }
        deviceConnect.setOnClickListener { useSaved() }
        deviceForget.setOnClickListener { forgetSaved() }
        deviceManual.setOnClickListener { focusPairingCode() }
        toolbarHandle.setOnClickListener {
            prefs.toolbarHidden = false
            revealToolbar()
        }
        updateAction.setOnClickListener { startUpdate() }
        updateLater.setOnClickListener { updateRow.visibility = View.GONE }
        discoverAgain.setOnClickListener {
            stopDiscovery()
            startDiscovery()
        }

        // ── settings rows ──────────────────────────────────────────────
        settingsQuality.setOnClickListener { pickQuality() }
        settingsScreen.setOnClickListener { pickScreenMode() }
        settingsInput.setOnClickListener { pickInputMode() }
        settingsScroll.setOnClickListener { toggleScroll() }
        settingsAudio.setOnClickListener { pickAudio() }
        settingsKeys.setOnClickListener { pickKeys() }
        settingsUpdates.setOnClickListener { maybeCheckForUpdates(force = true) }
        settingsSource.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, updater.releasesPage())) }
        }
        settingsSecondSwitch.setOnCheckedChangeListener { _, checked ->
            if (!syncingSecond) setSecondScreen(checked)
        }

        screenView.onMove = { x, y -> send(RemoteInput.move(x, y)) }
        screenView.onMoveRel = { dx, dy -> send(RemoteInput.moveRel(dx, dy)) }
        screenView.onButton = { button, down, pad ->
            send(RemoteInput.button(button, down, pad))
        }
        screenView.onClick = { button, pad -> send(RemoteInput.click(button, pad)) }
        screenView.onScroll = { dy, dx, pad -> send(RemoteInput.scroll(dy, dx, pad)) }

        keySink.onText = { text -> send(RemoteInput.text(text, mods.toList())) }
        keySink.onKeyName = { name -> sendKey(name) }

        applyScreenMode()
        applyInputMode()
        buildToolbar()
        updateRecent()
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
        stopDiscovery()
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
        deviceUsbHint.visibility = if (enabled) View.VISIBLE else View.GONE
        syncingUsb = true
        if (usbBox.isChecked != enabled) usbBox.isChecked = enabled
        if (deviceUsbSwitch.isChecked != enabled) deviceUsbSwitch.isChecked = enabled
        syncingUsb = false
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
        refreshSettings()
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
        statusText.text = getString(R.string.status_forgotten)
    }

    private fun updateRecent() {
        val saved = prefs.hasSaved
        recentRow.visibility = if (saved) View.VISIBLE else View.GONE
        deviceActions.visibility = if (saved) View.VISIBLE else View.GONE
        deviceTransport.visibility = if (saved) View.VISIBLE else View.GONE

        if (!saved) {
            deviceName.text = getString(R.string.device_none)
            deviceAddress.text = getString(R.string.device_none_hint)
            refreshSettings()
            return
        }

        val label = prefs.name.ifBlank { prefs.host }
        val shownHost = prefs.wifiHost.ifBlank { prefs.host }
        // A machine connected by address has no separate name; do not print it twice.
        recentHost.text =
            if (label == shownHost) "$label:${prefs.port}" else "$label · $shownHost:${prefs.port}"
        deviceName.text = label
        deviceAddress.text =
            if (label == shownHost) "Port ${prefs.port}" else "$shownHost:${prefs.port}"
        deviceTransport.text = getString(
            if (prefs.usbCable) R.string.device_usb_mode else R.string.device_wifi_mode,
        )
        refreshSettings()
    }

    /** Row summaries on the Settings and Devices tabs. */
    private fun refreshSettings() {
        if (!::prefs.isInitialized || !::settingsQualityValue.isInitialized) return

        settingsQualityValue.text = if (prefs.quality > 0) {
            "${prefs.fps.takeIf { it > 0 } ?: "?"} fps · q${prefs.quality}"
        } else {
            getString(R.string.quality_host_default)
        }
        settingsScreenValue.text = getString(
            when (prefs.screenMode) {
                "fill" -> R.string.screen_fill_short
                "stretch" -> R.string.screen_stretch_short
                else -> R.string.screen_fit_short
            },
        )
        settingsInputValue.text = getString(
            if (prefs.inputMode == "touchpad") R.string.input_touchpad_short
            else R.string.input_touch_short,
        )
        settingsScrollValue.text = getString(
            if (prefs.naturalScroll) R.string.scroll_natural_short
            else R.string.scroll_traditional_short,
        )
        settingsAudioValue.text = getString(
            if (prefs.audioOnPhone) R.string.audio_phone_short
            else R.string.audio_machine_short,
        )
        settingsKeysValue.text = getString(
            R.string.keys_count,
            prefs.keyList.ifEmpty { DEFAULT_KEYS }.size,
        )
        settingsVersion.text = "PocketDesk ${BuildConfig.VERSION_NAME}"

        syncingSecond = true
        if (settingsSecondSwitch.isChecked != prefs.secondScreen) {
            settingsSecondSwitch.isChecked = prefs.secondScreen
        }
        syncingSecond = false

        syncingUsb = true
        if (deviceUsbSwitch.isChecked != prefs.usbCable) {
            deviceUsbSwitch.isChecked = prefs.usbCable
        }
        syncingUsb = false
        deviceUsbHint.visibility = if (prefs.usbCable) View.VISIBLE else View.GONE
    }

    /**
     * Wide windows get a ~640 dp content column. Measured from the laid-out
     * view rather than the resource configuration: this activity handles
     * orientation changes itself, so `values-land` would stay applied after
     * rotating back. Called from a global layout listener because the width is
     * only final after a layout pass.
     */
    private fun applyContentGutter() {
        if (!::homeTab.isInitialized) return
        val width = homeTab.width
        if (width <= 0) return
        val density = resources.displayMetrics.density
        val extraDp = (width / density - 640f) / 2f
        val extra = if (extraDp > 0f) (extraDp * density).toInt() else 0
        for (tab in arrayOf(homeTab, devicesTab, settingsTab)) {
            if (tab.paddingLeft != extra || tab.paddingRight != extra) {
                tab.setPadding(extra, 0, extra, 0)
            }
        }
    }

    private fun showTab(itemId: Int) {
        homeTab.visibility = if (itemId == R.id.nav_home) View.VISIBLE else View.GONE
        devicesTab.visibility = if (itemId == R.id.nav_devices) View.VISIBLE else View.GONE
        settingsTab.visibility = if (itemId == R.id.nav_settings) View.VISIBLE else View.GONE
        if (itemId == R.id.nav_devices || itemId == R.id.nav_settings) refreshSettings()
        hideKeyboard()
    }

    private fun scan() {
        startActivityForResult(Intent(this, ScanActivity::class.java), SCAN_REQUEST)
    }

    private fun focusPairingCode() {
        bottomNav.selectedItemId = R.id.nav_home
        tokenField.postDelayed(
            {
                tokenField.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(tokenField, InputMethodManager.SHOW_IMPLICIT)
            },
            150,
        )
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
                refreshSettings()
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
        stopDiscovery()
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
        bottomNav.selectedItemId = R.id.nav_home
        refreshSettings()
        if (resumed) startDiscovery()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
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

        MaterialAlertDialogBuilder(this)
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
            MaterialAlertDialogBuilder(this)
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
            MaterialAlertDialogBuilder(this)
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

    /**
     * A modal sheet that opens fully expanded. In a landscape session the
     * window is only ~390 dp tall, and a sheet left at its collapsed height
     * would show a single clipped row.
     */
    private fun newSheet(layout: Int): Pair<BottomSheetDialog, View> {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(layout, null)
        sheet.setContentView(content)
        val inSession = remotePanel.visibility == View.VISIBLE
        sheet.setOnShowListener {
            sheet.behavior.skipCollapsed = true
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED

            if (inSession) {
                // The sheet is its own window: without this the status bar
                // comes back over the stream while it is open. The pill would
                // also peek out on either side of the sheet.
                ui.removeCallbacks(hideToolbarRunnable)
                toolbar.visibility = View.GONE
                toolbarHandle.visibility = View.GONE
                sheet.window?.let { win ->
                    WindowCompat.setDecorFitsSystemWindows(win, false)
                    WindowInsetsControllerCompat(win, win.decorView).apply {
                        systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
        }
        if (inSession) {
            sheet.setOnDismissListener {
                if (remotePanel.visibility == View.VISIBLE) applyToolbarVisibility()
            }
        }
        return sheet to content
    }

    // ── floating session pill ─────────────────────────────────────────

    private fun buildToolbar() {
        toolbarButtons.removeAllViews()
        modifierChips.clear()
        mods.clear()

        toolbarButtons.addView(chip("Keys", checkable = false) { toggleKeyboard() })

        val selected = prefs.keyList.ifEmpty { DEFAULT_KEYS }
        for (name in selected) {
            val def = KEY_CATALOG.firstOrNull { it.name == name } ?: continue
            val view = chip(def.label, checkable = def.modifier) {
                if (def.modifier) toggleModifier(def.name) else sendKey(def.name)
            }
            if (def.modifier) modifierChips[def.name] = view
            toolbarButtons.addView(view)
        }

        toolbarButtons.addView(chip("⋯", checkable = false) { showSessionMenu() })
        toolbarButtons.addView(chip("Exit", checkable = false) { confirmStop() })
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) {
            return ContextCompat.getColor(this, R.color.text)
        }
        return if (value.resourceId != 0) {
            ContextCompat.getColor(this, value.resourceId)
        } else {
            value.data
        }
    }

    /**
     * A pill chip. Modifiers are checkable and use the M3 primary container;
     * one-shot keys stay neutral. Colours come from the theme, so the chips
     * follow the Material You palette.
     */
    private fun chip(label: String, checkable: Boolean, onClick: () -> Unit): MaterialButton {
        val primary = themeColor(com.google.android.material.R.attr.colorPrimary)
        val onPrimary = themeColor(com.google.android.material.R.attr.colorOnPrimary)
        val container = themeColor(com.google.android.material.R.attr.colorSurfaceVariant)
        val onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface)

        val button = layoutInflater.inflate(
            R.layout.item_toolbar_chip,
            toolbarButtons,
            false,
        ) as MaterialButton
        button.text = label
        button.textSize = 12f
        button.isAllCaps = false
        button.minWidth = 0
        button.minimumWidth = 0
        button.insetTop = 0
        button.insetBottom = 0
        button.setPadding(dp(11), 0, dp(11), 0)
        button.cornerRadius = dp(18)
        button.isCheckable = checkable
        button.backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(primary, container),
        )
        button.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(onPrimary, onSurface),
            ),
        )
        button.isFocusable = false
        button.setOnClickListener {
            pokeToolbar()
            onClick()
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(34),
        )
        params.marginEnd = dp(6)
        button.layoutParams = params
        return button
    }

    private fun pokeToolbar() {
        if (remotePanel.visibility != View.VISIBLE || toolbar.visibility != View.VISIBLE) return
        ui.removeCallbacks(hideToolbarRunnable)
        ui.postDelayed(hideToolbarRunnable, TOOLBAR_TIMEOUT)
    }

    /** Fade the pill in and start the auto-hide timer. */
    private fun revealToolbar() {
        if (remotePanel.visibility != View.VISIBLE) return
        ui.removeCallbacks(hideToolbarRunnable)
        toolbar.animate().cancel()
        toolbar.visibility = View.VISIBLE
        toolbar.alpha = 0f
        toolbar.animate().alpha(1f).setDuration(160).start()
        toolbarHandle.visibility = View.GONE
        if (!prefs.toolbarHidden) {
            ui.postDelayed(hideToolbarRunnable, TOOLBAR_TIMEOUT)
        }
    }

    private fun collapseToolbar(animate: Boolean) {
        ui.removeCallbacks(hideToolbarRunnable)
        if (remotePanel.visibility != View.VISIBLE) return
        if (animate) {
            toolbar.animate().cancel()
            toolbar.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction { toolbar.visibility = View.GONE }
                .start()
        } else {
            toolbar.animate().cancel()
            toolbar.alpha = 0f
            toolbar.visibility = View.GONE
        }
        toolbarHandle.visibility = View.VISIBLE
    }

    private fun applyToolbarVisibility() {
        if (remotePanel.visibility != View.VISIBLE) {
            ui.removeCallbacks(hideToolbarRunnable)
            toolbar.visibility = View.GONE
            toolbarHandle.visibility = View.GONE
            return
        }
        if (prefs.toolbarHidden) {
            collapseToolbar(animate = false)
        } else {
            revealToolbar()
        }
    }

    private fun setToolbarHidden(hidden: Boolean) {
        prefs.toolbarHidden = hidden
        if (hidden) {
            collapseToolbar(animate = true)
            toast("Toolbar hidden — tap ☰ to bring it back.")
        } else {
            revealToolbar()
        }
    }

    private fun showSessionMenu() {
        pokeToolbar()
        val (sheet, content) = newSheet(R.layout.sheet_session)

        content.findViewById<TextView>(R.id.sessionTarget).text = SessionHub.target
        content.findViewById<TextView>(R.id.actionInputValue).text = getString(
            if (prefs.inputMode == "touchpad") R.string.input_touchpad_short
            else R.string.input_touch_short,
        )
        content.findViewById<TextView>(R.id.actionScrollValue).text = getString(
            if (prefs.naturalScroll) R.string.scroll_natural_short
            else R.string.scroll_traditional_short,
        )
        content.findViewById<TextView>(R.id.actionAudioValue).text = getString(
            if (prefs.audioOnPhone) R.string.audio_phone_short
            else R.string.audio_machine_short,
        )
        content.findViewById<TextView>(R.id.actionSecondValue).text = getString(
            if (prefs.secondScreen) R.string.session_second_on else R.string.session_second_off,
        )
        content.findViewById<TextView>(R.id.actionScreenValue).text = getString(
            when (prefs.screenMode) {
                "fill" -> R.string.screen_fill_short
                "stretch" -> R.string.screen_stretch_short
                else -> R.string.screen_fit_short
            },
        )

        fun action(id: Int, run: () -> Unit) {
            content.findViewById<View>(id).setOnClickListener {
                sheet.dismiss()
                run()
            }
        }

        action(R.id.actionKeys) { pickKeys() }
        action(R.id.actionQuality) { pickQuality() }
        action(R.id.actionInput) { pickInputMode() }
        action(R.id.actionScroll) { toggleScroll() }
        action(R.id.actionAudio) { pickAudio() }
        action(R.id.actionSecond) { setSecondScreen(!prefs.secondScreen) }
        action(R.id.actionScreen) { pickScreenMode() }
        action(R.id.actionHide) { setToolbarHidden(true) }
        action(R.id.actionUpdates) { maybeCheckForUpdates(force = true) }
        action(R.id.actionDisconnect) { confirmStop() }

        sheet.show()
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

    private fun applyPreset(preset: Preset) {
        prefs.saveStream(preset.fps, preset.quality, preset.scale)
        SessionHub.setQuality(preset.fps, preset.quality, preset.scale)
        toast(getString(R.string.stream_now, preset.fps, preset.quality))
        refreshSettings()
    }

    private fun pickQuality() {
        val (sheet, content) = newSheet(R.layout.sheet_quality)
        val group = content.findViewById<RadioGroup>(R.id.qualityGroup)
        val ids = intArrayOf(
            R.id.quality0,
            R.id.quality1,
            R.id.quality2,
            R.id.quality3,
            R.id.quality4,
        )

        // Nothing saved means "whatever the host was started with"; a live
        // session may instead match a preset we sent earlier.
        val saved = PRESETS.indexOfFirst { it.fps == prefs.fps && it.quality == prefs.quality }
        val sent = PRESETS.indexOfFirst {
            it.fps == captureState?.fps && it.quality == captureState?.quality
        }
        group.check(
            when {
                saved >= 0 -> ids[saved]
                prefs.quality == 0 -> R.id.qualityHost
                sent >= 0 -> ids[sent]
                else -> R.id.qualityHost
            },
        )
        group.setOnCheckedChangeListener { _, checked ->
            if (checked == R.id.qualityHost) {
                prefs.saveStream(0, 0, 1.0)
                refreshSettings()
                if (SessionHub.isLive) toast(getString(R.string.quality_host_default_live))
                sheet.dismiss()
                return@setOnCheckedChangeListener
            }
            val index = ids.indexOf(checked)
            if (index >= 0) {
                applyPreset(PRESETS[index])
                sheet.dismiss()
            }
        }

        sheet.show()
    }

    private fun pickInputMode() {
        val (sheet, content) = newSheet(R.layout.sheet_input)
        val group = content.findViewById<RadioGroup>(R.id.inputGroup)

        group.check(
            if (prefs.inputMode == "touchpad") R.id.inputTouchpad else R.id.inputTouch,
        )
        group.setOnCheckedChangeListener { _, checked ->
            prefs.inputMode = if (checked == R.id.inputTouchpad) "touchpad" else "touch"
            applyInputMode()
            refreshSettings()
        }

        val tap = content.findViewById<SwitchMaterial>(R.id.switchTapToClick)
        tap.isChecked = prefs.tapToClick
        tap.setOnCheckedChangeListener { _, checked ->
            prefs.tapToClick = checked
            applyInputMode()
        }

        val natural = content.findViewById<SwitchMaterial>(R.id.switchNaturalScroll)
        natural.isChecked = prefs.naturalScroll
        natural.setOnCheckedChangeListener { _, checked ->
            prefs.naturalScroll = checked
            applyInputMode()
            refreshSettings()
        }

        val speed = content.findViewById<Slider>(R.id.padSpeed)
        val speedValue = content.findViewById<TextView>(R.id.padSpeedValue)
        fun showSpeed(value: Float) {
            speedValue.text = String.format(java.util.Locale.US, "%.1f×", value)
        }
        val stops = listOf(1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f, 2.2f, 2.4f)
        speed.value = stops.minByOrNull { kotlin.math.abs(it - prefs.padSpeed) } ?: 1.6f
        showSpeed(speed.value)
        speed.addOnChangeListener { _, value, fromUser ->
            showSpeed(value)
            if (fromUser) {
                prefs.padSpeed = value
                applyInputMode()
            }
        }

        content.findViewById<Button>(R.id.inputDone).setOnClickListener { sheet.dismiss() }
        sheet.show()
    }

    private fun toggleScroll() {
        prefs.naturalScroll = !prefs.naturalScroll
        applyInputMode()
        refreshSettings()
        toast(
            if (prefs.naturalScroll) getString(R.string.scroll_natural_hint)
            else getString(R.string.scroll_traditional_hint),
        )
    }

    private fun startAudio() {
        if (!SessionHub.isLive) return
        val info = SessionHub.hello
        if (info != null && !info.audio) {
            if (!audioUnavailableShown) {
                audioUnavailableShown = true
                toast(getString(R.string.audio_failed, getString(R.string.audio_no_host_capture)))
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
        val (sheet, content) = newSheet(R.layout.sheet_audio)
        val group = content.findViewById<RadioGroup>(R.id.audioGroup)
        val machine = content.findViewById<com.google.android.material.radiobutton.MaterialRadioButton>(
            R.id.audioMachine,
        )
        val phone = content.findViewById<com.google.android.material.radiobutton.MaterialRadioButton>(
            R.id.audioPhone,
        )
        val mute = content.findViewById<SwitchMaterial>(R.id.switchMuteHost)

        group.check(if (prefs.audioOnPhone) phone.id else machine.id)
        mute.isChecked = prefs.muteHost
        mute.isEnabled = prefs.audioOnPhone
        group.setOnCheckedChangeListener { _, checked ->
            mute.isEnabled = checked == phone.id
        }

        content.findViewById<Button>(R.id.audioCancel).setOnClickListener { sheet.dismiss() }
        content.findViewById<Button>(R.id.audioDone).setOnClickListener {
            val onPhone = group.checkedRadioButtonId == phone.id
            prefs.audioOnPhone = onPhone
            prefs.muteHost = mute.isChecked
            if (onPhone) {
                startAudio()
                toast(getString(R.string.audio_hint))
            } else {
                stopAudio()
            }
            refreshSettings()
            sheet.dismiss()
        }

        sheet.show()
    }

    private fun pickKeys() {
        val (sheet, content) = newSheet(R.layout.sheet_keys)
        val group = content.findViewById<ChipGroup>(R.id.keysGroup)
        val selected = prefs.keyList.ifEmpty { DEFAULT_KEYS }.toMutableSet()

        fun fill(checked: Set<String>) {
            group.removeAllViews()
            for (def in KEY_CATALOG) {
                val chip = layoutInflater.inflate(R.layout.item_key_chip, group, false) as Chip
                chip.text = def.label
                chip.isChecked = def.name in checked
                chip.setOnCheckedChangeListener { _, on ->
                    if (on) selected.add(def.name) else selected.remove(def.name)
                }
                group.addView(chip)
            }
        }
        fill(selected)

        content.findViewById<Button>(R.id.keysReset).setOnClickListener {
            selected.clear()
            selected.addAll(DEFAULT_KEYS)
            fill(selected)
        }
        content.findViewById<Button>(R.id.keysDone).setOnClickListener {
            prefs.keys = KEY_CATALOG.filter { it.name in selected }
                .joinToString(",") { it.name }
            buildToolbar()
            refreshSettings()
            sheet.dismiss()
        }

        sheet.show()
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

    private fun applyScreenMode() {
        screenView.setDisplayMode(
            when (prefs.screenMode) {
                "fill" -> ScreenView.DisplayMode.FILL
                "stretch" -> ScreenView.DisplayMode.STRETCH
                else -> ScreenView.DisplayMode.FIT
            },
        )
    }

    private fun pickScreenMode() {
        val (sheet, content) = newSheet(R.layout.sheet_screen)
        val group = content.findViewById<RadioGroup>(R.id.screenGroup)
        val ids = intArrayOf(R.id.screenFit, R.id.screenFill, R.id.screenStretch)
        val values = arrayOf("fit", "fill", "stretch")
        val hints = intArrayOf(
            R.string.screen_fit_hint,
            R.string.screen_fill_hint,
            R.string.screen_stretch_hint,
        )

        group.check(ids[values.indexOf(prefs.screenMode).coerceAtLeast(0)])
        group.setOnCheckedChangeListener { _, checked ->
            val index = ids.indexOf(checked)
            if (index >= 0) {
                prefs.screenMode = values[index]
                applyScreenMode()
                toast(getString(hints[index]))
                refreshSettings()
                sheet.dismiss()
            }
        }

        sheet.show()
    }

    // ── finding the host ──────────────────────────────────────────────

    private fun startDiscovery() {
        if (SessionHub.isLive || !::discoveryStatus.isInitialized) return
        if (discovery.isRunning) return
        discoveredHosts = emptyList()
        discoveredList.removeAllViews()
        discoveredList.visibility = View.GONE
        discoveryStatus.text = getString(R.string.discovery_looking)
        discovery.start()
        ui.removeCallbacks(discoveryTimeout)
        ui.postDelayed(discoveryTimeout, 4_500)
    }

    private fun stopDiscovery() {
        discovery.stop()
        ui.removeCallbacks(discoveryTimeout)
    }

    private fun renderDiscovered(hosts: List<Discovery.Host>) {
        if (isFinishing || isDestroyed) return
        discoveredHosts = hosts
        if (hosts.isEmpty()) {
            discoveredList.visibility = View.GONE
            return
        }

        ui.removeCallbacks(discoveryTimeout)
        discoveryStatus.text = getString(R.string.discovery_found)
        discoveredList.visibility = View.VISIBLE
        discoveredList.removeAllViews()

        for (host in hosts) {
            val row = layoutInflater.inflate(R.layout.item_host, discoveredList, false)
            row.findViewById<TextView>(R.id.hostName).text =
                host.name.ifBlank { host.address }
            row.findViewById<TextView>(R.id.hostAddress).text =
                "${host.address}:${host.port}"
            row.setOnClickListener { useDiscovered(host) }
            discoveredList.addView(row)
        }
    }

    private fun useDiscovered(host: Discovery.Host) {
        if (prefs.usbCable) usbBox.isChecked = false
        hostField.setText(host.address)
        portField.setText(host.port.toString())
        prefs.wifiHost = host.address
        updateRecent()

        val token = tokenField.text.toString().trim()
        if (token.isNotBlank() && (prefs.name.isBlank() || prefs.name == host.name)) {
            connect(host.name)
        } else {
            statusText.text = getString(R.string.discovery_selected, host.name)
            tokenField.requestFocus()
        }
    }

    private fun setSecondScreen(enabled: Boolean) {
        if (prefs.secondScreen == enabled) {
            refreshSettings()
            return
        }

        if (!enabled) {
            prefs.secondScreen = false
            if (SessionHub.isLive) SessionHub.setDisplay("mirror", 0, 0)
            toast(getString(R.string.mirroring_again))
            refreshSettings()
            return
        }

        prefs.secondScreen = true
        if (SessionHub.isLive) {
            val (width, height) = phoneTarget()
            awaitingExtend = true
            SessionHub.setDisplay("extend", width, height)
            toast(getString(R.string.second_screen_asking, width, height))
        }
        refreshSettings()
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
            view.isChecked = active
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        // `isActive`, not `hasFocus`: after Back the sink can still hold focus
        // while no keyboard is visible, and then the first tap did nothing.
        if (keySink.hasFocus() && imm.isActive(keySink)) {
            imm.hideSoftInputFromWindow(keySink.windowToken, 0)
            keySink.clearFocus()
        } else {
            keySink.requestFocus()
            ui.removeCallbacks(hideToolbarRunnable)
            // Older IMEs ignore the request while the window is still settling.
            keySink.postDelayed(
                { imm.showSoftInput(keySink, InputMethodManager.SHOW_IMPLICIT) },
                100,
            )
            keySink.postDelayed({ imm.showSoftInput(keySink, 0) }, 400)
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
        when {
            remotePanel.visibility == View.VISIBLE -> {
                // Keep the session; it lives in the foreground service.
                moveTaskToBack(true)
                if (!backgroundHintShown) {
                    backgroundHintShown = true
                    toast(getString(R.string.background_hint))
                }
            }

            bottomNav.selectedItemId != R.id.nav_home -> {
                bottomNav.selectedItemId = R.id.nav_home
            }

            else -> super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshSettings()
        if (!SessionHub.isLive) startDiscovery()
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
        stopDiscovery()
        // No point decoding or transferring frames nobody can see.
        SessionHub.setVisible(false)
        stopHealthCheck()
        ui.removeCallbacks(hideToolbarRunnable)
        if (::keySink.isInitialized) hideKeyboard()
    }

    override fun onDestroy() {
        SessionHub.removeListener(this)
        stopDiscovery()
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

        /** How long the session pill stays visible without a touch. */
        const val TOOLBAR_TIMEOUT = 4_500L

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
