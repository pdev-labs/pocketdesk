package dev.pocketdesk.session

import android.content.Context
import dev.pocketdesk.net.AudioClient

/**
 * Process-wide owner of the audio socket.
 *
 * Audio deliberately outlives the Activity: it keeps playing while the app is
 * in the background, and `SessionHub.stop()` (including the notification's
 * Stop action) can shut it down without an Activity being alive.
 */
object AudioHub {

    private var client: AudioClient? = null

    /** UI callback; set by the Activity and cleared when it goes away. */
    var onState: ((Boolean, String?) -> Unit)? = null

    fun ensure(context: Context) {
        if (client != null) return
        val app = context.applicationContext
        client = AudioClient(app) { playing, detail ->
            onState?.invoke(playing, detail)
        }
    }

    fun start(context: Context, url: String) {
        ensure(context)
        client?.connect(url)
    }

    fun stop() {
        client?.close()
    }
}
