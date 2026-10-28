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

    /** Touchpad mode: move the pointer by desktop pixels, not to a position. */
    fun moveRel(dx: Float, dy: Float): String =
        JSONObject()
            .put("t", "move_rel")
            .put("dx", dx.toDouble())
            .put("dy", dy.toDouble())
            .toString()

    fun button(button: String, down: Boolean, pad: Boolean = false): String =
        JSONObject()
            .put("t", "button")
            .put("button", button)
            .put("down", down)
            .put("pad", pad)
            .toString()

    fun click(button: String, pad: Boolean = false): String =
        JSONObject()
            .put("t", "click")
            .put("button", button)
            .put("pad", pad)
            .toString()

    fun scroll(dy: Float, dx: Float = 0f, pad: Boolean = false): String =
        JSONObject()
            .put("t", "scroll")
            .put("dy", dy.toDouble())
            .put("dx", dx.toDouble())
            .put("pad", pad)
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
