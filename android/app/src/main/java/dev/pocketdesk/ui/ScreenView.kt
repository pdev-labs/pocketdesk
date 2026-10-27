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
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Draws the newest frame letterboxed in the middle, and turns touch into a
 * pointer: touch = move + press, drag = drag, tap = click, long press = right
 * click, two fingers = wheel scroll.
 */
class ScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onMove: ((Float, Float) -> Unit)? = null
    var onButton: ((String, Boolean) -> Unit)? = null
    var onClick: ((String) -> Unit)? = null
    var onScroll: ((Float, Float) -> Unit)? = null

    private var frame: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val destination = RectF()
    private val density = resources.displayMetrics.density
    private val slop = 10f * density
    private val handler = Handler(Looper.getMainLooper())

    private var mode = MODE_NONE
    private var downX = 0f
    private var downY = 0f
    private var pressed = false
    private var moved = false
    private var lastMoveAt = 0L
    private var scrollAnchor = 0f
    private var scrollAccum = 0f

    private val longPress = Runnable {
        if (mode == MODE_TOUCH && pressed && !moved) {
            onButton?.invoke("left", false)
            pressed = false
            onClick?.invoke("right")
        }
    }

    fun setFrame(bitmap: Bitmap) {
        frame = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = frame ?: return
        if (bitmap.width <= 0 || bitmap.height <= 0 || width == 0 || height == 0) return

        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f

        destination.set(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                mode = MODE_TOUCH
                normalize(event.x, event.y)?.let { (nx, ny) ->
                    onMove?.invoke(nx, ny)
                    onButton?.invoke("left", true)
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
                        onButton?.invoke("left", false)
                        pressed = false
                    }
                    mode = MODE_SCROLL
                    scrollAnchor = averageY(event)
                    scrollAccum = 0f
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == MODE_SCROLL && event.pointerCount >= 2) {
                    val y = averageY(event)
                    scrollAccum += y - scrollAnchor
                    scrollAnchor = y

                    val notch = 46f * density
                    if (abs(scrollAccum) >= notch) {
                        onScroll?.invoke(scrollAccum / notch, 0f)
                        scrollAccum = 0f
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
                    onButton?.invoke("left", false)
                    pressed = false
                }
                mode = MODE_NONE
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (pressed) {
                    onButton?.invoke("left", false)
                    pressed = false
                }
                mode = MODE_NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun averageY(event: MotionEvent): Float {
        var sum = 0f
        for (index in 0 until event.pointerCount) {
            sum += event.getY(index)
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
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_TOUCH = 1
        const val MODE_SCROLL = 2
    }
}
