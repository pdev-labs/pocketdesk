package dev.pocketdesk

import android.content.Context

/** Small wrapper around SharedPreferences so the pairing survives restarts. */
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

    fun save(host: String, port: Int, token: String) {
        store.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("token", token)
            .apply()
    }

    companion object {
        const val DEFAULT_PORT = 8765
    }
}
