package dev.pocketdesk.ui

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

/**
 * A 1dp, almost invisible EditText used only to own the soft keyboard.
 *
 * The IME never edits real text: committed text is forwarded to the host as a
 * `text` message, backspace and friends become `key` messages. That is what
 * makes typing into a remote window feel native.
 */
class KeySinkEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    var onText: ((String) -> Unit)? = null
    var onKeyName: ((String) -> Unit)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        // Plain text, not a password variation: `VISIBLE_PASSWORD` made Gboard
        // offer "suggest strong password" and made the system refuse
        // screenshots while the keyboard was up.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_NORMAL or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

        val base = super.onCreateInputConnection(outAttrs) ?: return null

        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) onText?.invoke(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(0)) { onKeyName?.invoke("backspace") }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> onKeyName?.invoke("backspace")
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                            onKeyName?.invoke("enter")

                        KeyEvent.KEYCODE_TAB -> onKeyName?.invoke("tab")
                        KeyEvent.KEYCODE_ESCAPE -> onKeyName?.invoke("esc")
                        KeyEvent.KEYCODE_DPAD_LEFT -> onKeyName?.invoke("left")
                        KeyEvent.KEYCODE_DPAD_RIGHT -> onKeyName?.invoke("right")
                        KeyEvent.KEYCODE_DPAD_UP -> onKeyName?.invoke("up")
                        KeyEvent.KEYCODE_DPAD_DOWN -> onKeyName?.invoke("down")
                        else -> Unit
                    }
                }
                return true
            }
        }
    }
}
