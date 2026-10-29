package dev.pocketdesk.net

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Plays the host's desktop audio.
 *
 * The host sends raw PCM (s16le, 48 kHz, stereo) in 20 ms messages, so there is
 * no codec to negotiate: the bytes go straight into [AudioTrack]. The socket is
 * separate from the video socket, which means audio can keep playing while the
 * screen is paused in the background.
 */
class AudioClient(
    context: Context,
    private val listener: Listener,
) {

    fun interface Listener {
        fun onAudioState(playing: Boolean, detail: String?)
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    private var legacyFocus: AudioManager.OnAudioFocusChangeListener? = null

    @Volatile
    private var closed = false

    @Volatile
    private var reportedWriteError = false

    fun connect(url: String) {
        close()
        closed = false
        reportedWriteError = false

        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("t")) {
                    "audio" -> {
                        startTrack(
                            rate = json.optInt("rate", 48_000),
                            channels = json.optInt("channels", 2),
                        )
                        listener.onAudioState(true, null)
                    }
                    "error" -> {
                        listener.onAudioState(false, json.optString("msg", "unknown error"))
                        close()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val output = track ?: return
                val data = bytes.toByteArray()
                val written = runCatching {
                    output.write(data, 0, data.size)
                }.getOrElse { error ->
                    if (!closed) listener.onAudioState(false, error.message ?: "playback failed")
                    return
                }
                if (written < 0 && !reportedWriteError) {
                    reportedWriteError = true
                    listener.onAudioState(false, "the audio device rejected playback ($written)")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed) listener.onAudioState(false, t.message ?: "audio connection failed")
                stopTrack()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopTrack()
            }
        })
    }

    private fun startTrack(rate: Int, channels: Int) {
        stopTrack()

        val channelMask = if (channels >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val minimum = AudioTrack.getMinBufferSize(
            rate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // ~200 ms: enough to ride out Wi-Fi jitter without feeling delayed.
        val bufferSize = maxOf(minimum, rate * channels * 2 / 5)

        val output = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(channelMask)
                .build()

            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            // AudioTrack.Builder is API 23; Android 5.x needs the old constructor.
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                rate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM,
            )
        }

        requestFocus()
        output.play()
        track = output
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener { }
                .build()
            focus = request
            runCatching { audioManager.requestAudioFocus(request) }
        } else {
            // AudioFocusRequest is API 26; Android 5.x uses the listener form.
            val listener = AudioManager.OnAudioFocusChangeListener { }
            legacyFocus = listener
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
            }
        }
    }

    private fun stopTrack() {
        track?.let { output ->
            runCatching { output.pause() }
            runCatching { output.flush() }
            runCatching { output.stop() }
            runCatching { output.release() }
        }
        track = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            focus = null
        } else {
            legacyFocus?.let {
                @Suppress("DEPRECATION")
                runCatching { audioManager.abandonAudioFocus(it) }
            }
            legacyFocus = null
        }
    }

    fun close() {
        closed = true
        // Tell the host before closing: it restores its own speakers as soon
        // as this arrives, instead of waiting for TCP to notice the close.
        socket?.send("{\"t\":\"stop\"}")
        socket?.close(1000, "session ended")
        socket = null
        stopTrack()
        listener.onAudioState(false, null)
    }
}
