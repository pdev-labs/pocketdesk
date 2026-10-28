package dev.pocketdesk.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the newest frame letterboxed in the middle and turns touch into input.
 *
 * Two modes:
 *
 *  - **Direct touch**: the finger is the pointer. Touch moves it, releasing
 *    clicks, dragging selects, a long press right-clicks, two fingers scroll.
 *    This is the natural mode when the phone mirrors the desktop.
 *
 *  - **Touchpad**: the phone behaves like a laptop trackpad. One finger moves
 *    the pointer relatively, a tap clicks, holding starts a drag, two fingers
 *    scroll, a two-finger tap right-clicks and a three-finger tap middle-clicks.
 *    Better for the second-screen mode, where the cursor is far from the
 *    content you are looking at.
 */
class ScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class InputMode { TOUCH, TOUCHPAD }

    // Absolute pointer (direct touch).
    var onMove: ((Float, Float) -> Unit)? = null
    // Relative pointer (touchpad).
    var onMoveRel: ((Float, Float) -> Unit)? = null
    var onButton: ((String, Boolean, Boolean) -> Unit)? = null
    var onClick: ((String, Boolean) -> Unit)? = null
    // dy notches, dx notches, touchpad?
    var onScroll: ((Float, Float, Boolean) -> Unit)? = null

    var inputMode = InputMode.TOUCH
        private set

    private var padSpeed = 1.6f
    private var naturalScroll = true
    private var tapToClick = true

    private var frame: Bitmap? = null

    /** Fill crops the sides instead of showing black bars. */
    private var fill = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val destination = RectF()
    private val density = resources.displayMetrics.density
    private val slop = 10f * density
    private val handler = Handler(Looper.getMainLooper())

    // Direct-touch state.
    private var mode = MODE_NONE
    private var downX = 0f
    private var downY = 0f
    private var pressed = false
    private var moved = false
    private var lastMoveAt = 0L
    private var scrollAnchorY = 0f
    private var scrollAnchorX = 0f
    private var scrollAccumY = 0f
    private var scrollAccumX = 0f

    // Touchpad state.
    private var padPointers = 0
    private var padMaxPointers = 0
    private var padDownAt = 0L
    private var padLastX = 0f
    private var padLastY = 0f
    private var padTravel = 0f
    private var padDragging = false
    private var padPendingDrag = false
    private var padTapCandidate = false
    private var lastPadTapAt = 0L
    private var lastPadTapX = 0f
    private var lastPadTapY = 0f
    private var padScrollAccumX = 0f
    private var padScrollAccumY = 0f

    private val longPress = Runnable {
        if (mode == MODE_TOUCH && pressed && !moved) {
            onButton?.invoke("left", false, false)
            pressed = false
            onClick?.invoke("right", false)
        }
    }

    /** Hold still on the touchpad: press the left button, then drag with it. */
    private val padLongPress = Runnable {
        if (inputMode == InputMode.TOUCHPAD && padPointers == 1 &&
            padTravel < slop && !padDragging
        ) {
            padDragging = true
            padTapCandidate = false
            onButton?.invoke("left", true, true)
            haptic()
        }
    }

    fun setFrame(bitmap: Bitmap) {
        frame = bitmap
        invalidate()
    }

    fun setFill(value: Boolean) {
        if (fill == value) return
        fill = value
        invalidate()
    }

    val isFilling: Boolean
        get() = fill

    fun setInputMode(value: InputMode) {
        if (inputMode == value) return
        releasePad()
        handler.removeCallbacks(longPress)
        mode = MODE_NONE
        pressed = false
        inputMode = value
    }

    fun setPadOptions(speed: Float, natural: Boolean, tap: Boolean) {
        padSpeed = speed.coerceIn(0.5f, 4f)
        naturalScroll = natural
        tapToClick = tap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = frame ?: return
        if (bitmap.width <= 0 || bitmap.height <= 0 || width == 0 || height == 0) return

        val scale = if (fill) {
            // Cover: the frame is scaled until it covers the view; the excess is
            // cropped, and touch mapping already works against `destination`.
            max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        } else {
            min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        }
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f

        destination.set(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (inputMode == InputMode.TOUCHPAD) {
            return onTouchpadEvent(event)
        }
        return onDirectTouchEvent(event)
    }

    // ── direct touch (absolute) ───────────────────────────────────────

    private fun onDirectTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                mode = MODE_TOUCH
                normalize(event.x, event.y)?.let { (nx, ny) ->
                    onMove?.invoke(nx, ny)
                    onButton?.invoke("left", true, false)
                    pressed = true
                    lastMoveAt = System.currentTimeMillis()
                }
                handler.postDelayed(longPress, 650)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    handler.removeCallbacks(longPress)
                    if (pressed) {
                        onButton?.invoke("left", false, false)
                        pressed = false
                    }
                    mode = MODE_SCROLL
                    scrollAnchorY = averageY(event)
                    scrollAnchorX = averageX(event)
                    scrollAccumY = 0f
                    scrollAccumX = 0f
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == MODE_SCROLL && event.pointerCount >= 2) {
                    val y = averageY(event)
                    val x = averageX(event)
                    scrollAccumY += y - scrollAnchorY
                    scrollAccumX += x - scrollAnchorX
                    scrollAnchorY = y
                    scrollAnchorX = x

                    val notch = 46f * density
                    val dy = scrollAccumY / notch * if (naturalScroll) 1f else -1f
                    val dx = scrollAccumX / notch
                    if (abs(scrollAccumY) >= notch || abs(scrollAccumX) >= notch) {
                        onScroll?.invoke(dy, dx, false)
                        scrollAccumY = 0f
                        scrollAccumX = 0f
                    }
                } else if (mode == MODE_TOUCH) {
                    if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                        if (!moved) handler.removeCallbacks(longPress)
                        moved = true
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastMoveAt >= 12) {
                        lastMoveAt = now
                        normalize(event.x, event.y)?.let { (nx, ny) -> onMove?.invoke(nx, ny) }
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> return true

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (mode == MODE_TOUCH && pressed) {
                    onButton?.invoke("left", false, false)
                    pressed = false
                }
                mode = MODE_NONE
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (pressed) {
                    onButton?.invoke("left", false, false)
                    pressed = false
                }
                mode = MODE_NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ── touchpad (relative + gestures) ────────────────────────────────

    private fun onTouchpadEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                padPointers = 1
                padMaxPointers = 1
                padDownAt = System.currentTimeMillis()
                padLastX = event.x
                padLastY = event.y
                padTravel = 0f
                padDragging = false
                padTapCandidate = true
                padScrollAccumX = 0f
                padScrollAccumY = 0f

                val now = System.currentTimeMillis()
                val nearLast = hypot(event.x - lastPadTapX, event.y - lastPadTapY) < slop * 2
                // Second tap of a double tap: if the finger then moves, drag.
                padPendingDrag = now - lastPadTapAt < 300 && nearLast
                handler.postDelayed(padLongPress, 420)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                padPointers = event.pointerCount
                padMaxPointers = max(padMaxPointers, padPointers)
                handler.removeCallbacks(padLongPress)
                padPendingDrag = false

                if (padDragging) {
                    onButton?.invoke("left", false, true)
                    padDragging = false
                }
                if (padPointers == 2) {
                    // A two-finger tap is still allowed; a drag is not.
                    padScrollAccumX = 0f
                    padScrollAccumY = 0f
                    padLastX = averageX(event)
                    padLastY = averageY(event)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (padPointers >= 2) {
                    val x = averageX(event)
                    val y = averageY(event)
                    val dx = x - padLastX
                    val dy = y - padLastY
                    padLastX = x
                    padLastY = y
                    padTravel += hypot(dx, dy)
                    if (padTravel > slop) padTapCandidate = false

                    padScrollAccumY += dy
                    padScrollAccumX += dx
                    val notch = 40f * density
                    if (abs(padScrollAccumY) >= notch || abs(padScrollAccumX) >= notch) {
                        var dyNotches = padScrollAccumY / notch
                        val dxNotches = padScrollAccumX / notch
                        if (!naturalScroll) dyNotches = -dyNotches
                        onScroll?.invoke(dyNotches, dxNotches, true)
                        padScrollAccumY = 0f
                        padScrollAccumX = 0f
                    }
                } else if (padPointers == 1) {
                    val dx = event.x - padLastX
                    val dy = event.y - padLastY
                    padLastX = event.x
                    padLastY = event.y
                    padTravel += hypot(dx, dy)

                    if (padTravel > slop) {
                        padTapCandidate = false
                        if (padPendingDrag && !padDragging) {
                            padDragging = true
                            onButton?.invoke("left", true, true)
                            haptic()
                        }
                    }

                    if (dx != 0f || dy != 0f) {
                        onMoveRel?.invoke(dx * padSpeed, dy * padSpeed)
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                handler.removeCallbacks(padLongPress)
                val remaining = event.pointerCount - 1
                padPointers = remaining
                if (remaining == 1) {
                    // Do not let the surviving finger jerk the pointer.
                    val index = if (event.actionIndex == 0) 1 else 0
                    padLastX = event.getX(index)
                    padLastY = event.getY(index)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(padLongPress)
                val now = System.currentTimeMillis()
                val short = now - padDownAt < 340

                if (padDragging) {
                    onButton?.invoke("left", false, true)
                    padDragging = false
                } else if (padTapCandidate && short && padTravel <= slop * 2) {
                    when {
                        padMaxPointers >= 3 -> {
                            onClick?.invoke("middle", true)
                            haptic()
                        }
                        padMaxPointers == 2 -> {
                            onClick?.invoke("right", true)
                            haptic()
                        }
                        tapToClick -> {
                            onClick?.invoke("left", true)
                            haptic()
                        }
                    }
                    if (padMaxPointers == 1) {
                        lastPadTapAt = now
                        lastPadTapX = event.x
                        lastPadTapY = event.y
                    }
                }
                resetPad()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                releasePad()
                resetPad()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun releasePad() {
        handler.removeCallbacks(padLongPress)
        if (padDragging) {
            onButton?.invoke("left", false, true)
            padDragging = false
        }
    }

    private fun resetPad() {
        padPointers = 0
        padMaxPointers = 0
        padTravel = 0f
        padTapCandidate = false
        padPendingDrag = false
        padScrollAccumX = 0f
        padScrollAccumY = 0f
    }

    private fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun averageY(event: MotionEvent): Float {
        var sum = 0f
        for (index in 0 until event.pointerCount) {
            sum += event.getY(index)
        }
        return sum / event.pointerCount.coerceAtLeast(1)
    }

    private fun averageX(event: MotionEvent): Float {
        var sum = 0f
        for (index in 0 until event.pointerCount) {
            sum += event.getX(index)
        }
        return sum / event.pointerCount.coerceAtLeast(1)
    }

    private fun normalize(x: Float, y: Float): Pair<Float, Float>? {
        if (destination.width() <= 0f || destination.height() <= 0f) return null
        val nx = ((x - destination.left) / destination.width()).coerceIn(0f, 1f)
        val ny = ((y - destination.top) / destination.height()).coerceIn(0f, 1f)
        return nx to ny
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        releasePad()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_TOUCH = 1
        const val MODE_SCROLL = 2
    }
}
