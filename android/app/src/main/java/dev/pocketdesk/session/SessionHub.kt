package dev.pocketdesk.session

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import dev.pocketdesk.net.HelloInfo
import dev.pocketdesk.net.PocketDeskClient
import dev.pocketdesk.net.Stats
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

    var state: State = State.IDLE
        private set

    var hello: HelloInfo? = null
        private set

    var latestFrame: Bitmap? = null
        private set

    var lastStats: Stats? = null
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

    fun stop(detail: String? = null) {
        stopped = true
        reconnect.removeCallbacksAndMessages(null)
        client?.close()
        client = null
        attempts = 0
        hello = null
        latestFrame = null
        lastStats = null
        readOnly = false
        publish(State.IDLE, detail)
    }

    // ── PocketDeskClient.Listener (called on the main thread) ─────────

    override fun onHello(info: HelloInfo) {
        hello = info
        readOnly = info.readOnly || !info.input
        attempts = 0
        publish(State.CONNECTED, null)
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
        client = PocketDeskClient(this).also { it.connect(url) }
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
