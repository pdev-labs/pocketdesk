package dev.pocketdesk.session

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import dev.pocketdesk.net.CaptureState
import dev.pocketdesk.net.HelloInfo
import dev.pocketdesk.net.PocketDeskClient
import dev.pocketdesk.net.Stats
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The live session, owned by the process rather than by the Activity.
 *
 * A foreground service keeps the process alive, so swiping the app out of
 * recents does not drop the connection. When the Activity comes back it attaches
 * as a listener and immediately receives the last frame, hello and stats.
 */
object SessionHub : PocketDeskClient.Listener {

    enum class State { IDLE, CONNECTING, CONNECTED, RECONNECTING, FAILED }

    interface Listener {
        fun onState(state: State, detail: String?) {}
        fun onHello(info: HelloInfo) {}
        fun onFrame(bitmap: Bitmap) {}
        fun onStats(stats: Stats) {}
        fun onError(message: String) {}
        fun onCapture(state: CaptureState) {}
    }

    private const val MAX_ATTEMPTS = 5

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val reconnect = Handler(Looper.getMainLooper())

    private var client: PocketDeskClient? = null
    private var host = ""
    private var port = 0
    private var token = ""
    private var attempts = 0
    private var stopped = false
    private var visible = true

    var state: State = State.IDLE
        private set

    var hello: HelloInfo? = null
        private set

    var latestFrame: Bitmap? = null
        private set

    var lastStats: Stats? = null
        private set

    /** Quality / second-screen state last reported by the host. */
    var capture: CaptureState? = null
        private set

    var readOnly = false
        private set

    /** `host:port`, for the notification and dialogs. */
    var target = ""
        private set

    val isLive: Boolean
        get() = state == State.CONNECTED || state == State.CONNECTING || state == State.RECONNECTING

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        listener.onState(state, null)
        hello?.let(listener::onHello)
        latestFrame?.let(listener::onFrame)
        capture?.let(listener::onCapture)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun connect(host: String, port: Int, token: String) {
        stopped = false
        attempts = 0
        this.host = host
        this.port = port
        this.token = token
        target = "$host:$port"

        reconnect.removeCallbacksAndMessages(null)
        open()
    }

    fun send(payload: String) {
        if (readOnly) return
        client?.send(payload)
    }

    /** Stream settings are not input, so they also work in view-only mode. */
    private fun sendAlways(payload: String) {
        client?.send(payload)
    }

    /**
     * Tell the host whether anyone is looking. While hidden it stops sending
     * frames (the socket, stats and pings stay up), which saves battery and
     * bandwidth; on return the latest frame is pushed immediately.
     */
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        client?.setDecodeEnabled(value)
        sendAlways(if (value) "{\"t\":\"resume\"}" else "{\"t\":\"pause\"}")
    }

    /** Change fps/quality/scale live; the host clamps and confirms. */
    fun setQuality(fps: Int? = null, quality: Int? = null, scale: Double? = null) {
        val payload = JSONObject().put("t", "set_quality")
        fps?.let { payload.put("fps", it) }
        quality?.let { payload.put("quality", it) }
        scale?.let { payload.put("scale", it) }
        sendAlways(payload.toString())
    }

    /**
     * `mode = "extend"` adds a virtual monitor sized to the phone and streams
     * that instead of the physical screen; `"mirror"` goes back.
     */
    fun setDisplay(mode: String, width: Int, height: Int) {
        val payload = JSONObject()
            .put("t", "set_display")
            .put("mode", mode)
            .put("width", width)
            .put("height", height)
        sendAlways(payload.toString())
    }

    fun stop(detail: String? = null) {
        stopped = true
        reconnect.removeCallbacksAndMessages(null)
        client?.close()
        client = null
        attempts = 0
        hello = null
        latestFrame = null
        lastStats = null
        capture = null
        readOnly = false
        publish(State.IDLE, detail)
    }

    // ── PocketDeskClient.Listener (called on the main thread) ─────────

    override fun onHello(info: HelloInfo) {
        hello = info
        readOnly = info.readOnly || !info.input
        attempts = 0
        publish(State.CONNECTED, null)
        // Reconnected while in the background: do not start streaming yet.
        if (!visible) sendAlways("{\"t\":\"pause\"}")
        listeners.forEach { it.onHello(info) }
    }

    override fun onFrame(bitmap: Bitmap) {
        latestFrame = bitmap
        listeners.forEach { it.onFrame(bitmap) }
    }

    override fun onStats(stats: Stats) {
        lastStats = stats
        listeners.forEach { it.onStats(stats) }
    }

    override fun onCapture(state: CaptureState) {
        capture = state
        listeners.forEach { it.onCapture(state) }
    }

    override fun onError(message: String) {
        listeners.forEach { it.onError(message) }
    }

    override fun onClosed(reason: String, httpCode: Int?) {
        if (stopped) return

        // Bad credentials and lockouts are not transient: retrying only turns a
        // typo into a longer lockout.
        when (httpCode) {
            401 -> {
                publish(State.FAILED, "Wrong pairing code. Scan the QR again or type the code shown by the host.")
                return
            }
            429 -> {
                publish(State.FAILED, "Too many wrong attempts. Wait a minute, then try again.")
                return
            }
        }

        if (attempts >= MAX_ATTEMPTS) {
            publish(State.FAILED, reason)
            return
        }

        attempts += 1
        val delay = (1000L shl (attempts - 1)).coerceAtMost(8000L)
        publish(State.RECONNECTING, reason)
        reconnect.postDelayed({
            if (!stopped) open()
        }, delay)
    }

    private fun open() {
        client?.close()
        publish(if (attempts > 0) State.RECONNECTING else State.CONNECTING, null)

        val url = "ws://$host:$port/ws?token=${URLEncoder.encode(token, "UTF-8")}"
        client = PocketDeskClient(this).also {
            it.setDecodeEnabled(visible)
            it.connect(url)
        }
    }

    private fun publish(next: State, detail: String?) {
        state = next
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { it.onState(next, detail) }
        } else {
            main.post { listeners.forEach { it.onState(next, detail) } }
        }
    }
}
