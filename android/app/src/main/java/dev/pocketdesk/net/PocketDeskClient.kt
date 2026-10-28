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
    val quality: Int,
    val mode: String,
    val input: Boolean,
    val readOnly: Boolean,
    val inputError: String?,
)

/** What the host is capturing right now (replies to quality/display changes). */
data class CaptureState(
    val fps: Int,
    val quality: Int,
    val scale: Double,
    val mode: String,
    val width: Int,
    val height: Int,
    val preset: String,
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
        fun onCapture(state: CaptureState) {}
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

    /**
     * When the app is in the background the host pauses the stream, so there is
     * nothing to decode. The last raw frame is kept, so returning to the app
     * paints immediately instead of waiting for the next change.
     */
    @Volatile
    private var decodeEnabled = true

    /**
     * Three buffers rotated per frame. Reusing them keeps ~4.6 MB per frame out
     * of the GC, and three slots guarantee we never decode into a bitmap the
     * view may still be drawing.
     */
    private val ring = arrayOfNulls<Bitmap>(3)
    private var ringIndex = 0
    private val decodeOptions = BitmapFactory.Options().apply { inMutable = true }

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closed = false

    fun connect(url: String) {
        closed = false
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!decodeEnabled) return
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

    /** Stop decoding while the app is not on screen; no allocations, no GC. */
    fun setDecodeEnabled(enabled: Boolean) {
        decodeEnabled = enabled
        if (enabled) scheduleDecode()
    }

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
                        quality = json.optInt("quality", 75),
                        mode = json.optString("mode", "mirror"),
                        input = json.optBoolean("input", false),
                        readOnly = json.optBoolean("read_only", false),
                        inputError = if (json.isNull("input_error")) null else json.optString("input_error"),
                    ),
                )
            }

            "capture" -> post {
                listener.onCapture(
                    CaptureState(
                        fps = json.optInt("fps", 15),
                        quality = json.optInt("quality", 75),
                        scale = json.optDouble("scale", 1.0),
                        mode = json.optString("mode", "mirror"),
                        width = json.optInt("width", 0),
                        height = json.optInt("height", 0),
                        preset = json.optString("preset", "balanced"),
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
        if (!decodeEnabled) return
        if (!decoding.compareAndSet(false, true)) return
        decoder.execute {
            try {
                while (true) {
                    if (!decodeEnabled) break
                    val bytes = pendingFrame.getAndSet(null) ?: break
                    val bitmap = decode(bytes) ?: continue
                    post { listener.onFrame(bitmap) }
                }
            } finally {
                decoding.set(false)
                if (decodeEnabled && pendingFrame.get() != null) scheduleDecode()
            }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val slot = ringIndex
        decodeOptions.inBitmap = ring[slot]?.takeIf { it.isMutable && !it.isRecycled }

        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (t: Throwable) {
            // A different frame size (rotation, quality change) invalidates the
            // reusable buffer: decode once more without it.
            decodeOptions.inBitmap = null
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            } catch (t2: Throwable) {
                null
            }
        }

        decodeOptions.inBitmap = null
        if (bitmap != null) {
            ring[slot] = bitmap
            ringIndex = (slot + 1) % ring.size
        }
        return bitmap
    }
}
