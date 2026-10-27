package dev.pocketdesk.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builders for the JSON control messages the host understands.
 * Coordinates are normalized inside the streamed frame (0..1).
 */
object RemoteInput {

    fun move(x: Float, y: Float): String =
        JSONObject()
            .put("t", "move")
            .put("x", x.toDouble())
            .put("y", y.toDouble())
            .toString()

    fun button(button: String, down: Boolean): String =
        JSONObject()
            .put("t", "button")
            .put("button", button)
            .put("down", down)
            .toString()

    fun click(button: String): String =
        JSONObject()
            .put("t", "click")
            .put("button", button)
            .toString()

    fun scroll(dy: Float, dx: Float = 0f): String =
        JSONObject()
            .put("t", "scroll")
            .put("dy", dy.toDouble())
            .put("dx", dx.toDouble())
            .toString()

    fun key(name: String, mods: Collection<String>): String {
        val message = JSONObject()
            .put("t", "key")
            .put("name", name)
        if (mods.isNotEmpty()) {
            message.put("mods", JSONArray(mods.toList()))
        }
        return message.toString()
    }

    fun text(value: String): String =
        JSONObject()
            .put("t", "text")
            .put("s", value)
            .toString()
}
