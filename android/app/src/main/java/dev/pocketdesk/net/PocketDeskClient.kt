package dev.pocketdesk.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What the host told us when the socket opened. */
data class HelloInfo(
    val version: String,
    val fps: Int,
    val input: Boolean,
    val readOnly: Boolean,
    val inputError: String?,
)

/** Throughput as measured by the host, not by the phone. */
data class Stats(val fps: Double, val kbps: Long)

/**
 * One WebSocket per phone. JPEG frames come down as binary messages, input goes
 * up as JSON. Decoding happens off the main thread and only the newest frame is
 * kept, so a slow phone degrades to fewer fps instead of seconds of lag.
 */
class PocketDeskClient(private val listener: Listener) {

    interface Listener {
        fun onHello(info: HelloInfo)
        fun onFrame(bitmap: Bitmap)
        fun onStats(stats: Stats)
        fun onError(message: String)
        fun onClosed(reason: String, httpCode: Int?)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val decoder = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pocketdesk-decoder").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())
    private val pendingFrame = AtomicReference<ByteArray?>(null)
    private val decoding = AtomicBoolean(false)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closed = false

    fun connect(url: String) {
        closed = false
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                queueFrame(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleText(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed) return
                val detail = response?.let { " (HTTP ${it.code})" } ?: ""
                val message = t.message?.takeIf { it.isNotBlank() } ?: "connection failed"
                post { listener.onClosed(message + detail, response?.code) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closed) return
                post { listener.onClosed(reason.ifBlank { "connection closed" }, null) }
            }
        })
    }

    fun send(payload: String): Boolean = socket?.send(payload) ?: false

    fun close() {
        closed = true
        socket?.close(1000, "closing")
        socket = null
    }

    private fun post(block: () -> Unit) {
        main.post { block() }
    }

    private fun handleText(text: String) {
        val json = try {
            JSONObject(text)
        } catch (ignored: Throwable) {
            return
        }

        when (json.optString("t")) {
            "hello" -> post {
                listener.onHello(
                    HelloInfo(
                        version = json.optString("version"),
                        fps = json.optInt("fps", 15),
                        input = json.optBoolean("input", false),
                        readOnly = json.optBoolean("read_only", false),
                        inputError = if (json.isNull("input_error")) null else json.optString("input_error"),
                    ),
                )
            }

            "stats" -> post {
                listener.onStats(
                    Stats(
                        fps = json.optDouble("fps", 0.0),
                        kbps = json.optLong("kbps", 0),
                    ),
                )
            }

            "error" -> post {
                listener.onError(json.optString("msg", "host error"))
            }
        }
    }

    private fun queueFrame(bytes: ByteArray) {
        pendingFrame.set(bytes)
        scheduleDecode()
    }

    private fun scheduleDecode() {
        if (!decoding.compareAndSet(false, true)) return
        decoder.execute {
            try {
                while (true) {
                    val bytes = pendingFrame.getAndSet(null) ?: break
                    val bitmap = try {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (t: Throwable) {
                        null
                    }
                    if (bitmap != null) post { listener.onFrame(bitmap) }
                }
            } finally {
                decoding.set(false)
                if (pendingFrame.get() != null) scheduleDecode()
            }
        }
    }
}
