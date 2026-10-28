package dev.pocketdesk

import android.content.Context

/**
 * Small wrapper around SharedPreferences.
 *
 * `remember` is independent of the credentials: unticking it clears the saved
 * machine but keeps the preference for the next time the app opens.
 */
class Prefs(context: Context) {

    private val store = context.getSharedPreferences("pocketdesk", Context.MODE_PRIVATE)

    var host: String
        get() = store.getString("host", "") ?: ""
        set(value) {
            store.edit().putString("host", value).apply()
        }

    var port: Int
        get() = store.getInt("port", DEFAULT_PORT)
        set(value) {
            store.edit().putInt("port", value).apply()
        }

    var token: String
        get() = store.getString("token", "") ?: ""
        set(value) {
            store.edit().putString("token", value).apply()
        }

    /** Friendly machine name from the QR, e.g. `arch`. */
    var name: String
        get() = store.getString("name", "") ?: ""
        set(value) {
            store.edit().putString("name", value).apply()
        }

    /** Whether the machine and code are saved at all. */
    var remember: Boolean
        get() = store.getBoolean("remember", true)
        set(value) {
            store.edit().putBoolean("remember", value).apply()
        }

    /** Comma-separated toolbar keys, in catalog order. Empty = defaults. */
    var keys: String
        get() = store.getString("keys", "") ?: ""
        set(value) {
            store.edit().putString("keys", value).apply()
        }

    val keyList: List<String>
        get() = keys.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Crop the sides of the stream instead of letterboxing it. */
    var fillScreen: Boolean
        get() = store.getBoolean("fill_screen", false)
        set(value) {
            store.edit().putBoolean("fill_screen", value).apply()
        }

    /** Toolbar collapsed, so the desktop gets the whole screen. */
    var toolbarHidden: Boolean
        get() = store.getBoolean("toolbar_hidden", false)
        set(value) {
            store.edit().putBoolean("toolbar_hidden", value).apply()
        }

    /** Stream the phone as a second monitor instead of mirroring. */
    var secondScreen: Boolean
        get() = store.getBoolean("second_screen", false)
        set(value) {
            store.edit().putBoolean("second_screen", value).apply()
        }

    /** Last quality preset; `fps <= 0` means "use whatever the host chose". */
    var fps: Int
        get() = store.getInt("fps", 0)
        set(value) {
            store.edit().putInt("fps", value).apply()
        }

    var quality: Int
        get() = store.getInt("quality", 0)
        set(value) {
            store.edit().putInt("quality", value).apply()
        }

    var scale: Float
        get() = store.getFloat("scale", 1f)
        set(value) {
            store.edit().putFloat("scale", value).apply()
        }

    /** When the app last asked GitHub for a new release (epoch millis). */
    var lastUpdateCheck: Long
        get() = store.getLong("last_update_check", 0L)
        set(value) {
            store.edit().putLong("last_update_check", value).apply()
        }

    fun saveStream(fps: Int, quality: Int, scale: Double) {
        store.edit()
            .putInt("fps", fps)
            .putInt("quality", quality)
            .putFloat("scale", scale.toFloat())
            .apply()
    }

    val hasSaved: Boolean
        get() = host.isNotBlank() && token.isNotBlank()

    fun save(host: String, port: Int, token: String, name: String) {
        store.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("token", token)
            .putString("name", name)
            .apply()
    }

    /** Forget the machine, keep the "remember" preference itself. */
    fun clearSaved() {
        store.edit()
            .remove("host")
            .remove("port")
            .remove("token")
            .remove("name")
            .apply()
    }

    companion object {
        const val DEFAULT_PORT = 8765
    }
}
