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
