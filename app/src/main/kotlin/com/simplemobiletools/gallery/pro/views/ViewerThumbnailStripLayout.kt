package com.simplemobiletools.gallery.pro.views

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Container of the bottom thumbnail strip.
 *
 * A normal horizontal swipe belongs to the RecyclerView inside, a long press turns the
 * container itself into a vertically draggable handle so the strip can be moved further
 * up, away from the bottom actions. Once the long press fires the gesture is taken over
 * from the child (it receives an ACTION_CANCEL), so the two can never fight each other.
 */
class ViewerThumbnailStripLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Called once the long press turned the strip into a movable handle. */
    var onDragStart: (() -> Unit)? = null

    /** Vertical delta in pixels, positive means the finger moved down. */
    var onDrag: ((dy: Float) -> Unit)? = null

    var onDragEnd: (() -> Unit)? = null

    /** A quick upward flick on the strip, used to open the fullscreen thumbnails grid. */
    var onSwipeUp: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val swipeUpThreshold = resources.getDimension(com.simplemobiletools.gallery.pro.R.dimen.viewer_thumbnail_strip_swipe_threshold)

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var longPressFired = false
    private var isDragging = false
    private var isSwipingUp = false

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // the strip sits right above the navigation bar, so an upward drag on it would be
            // taken by the system as the "back" gesture. Keep this band for the strip itself.
            addOnLayoutChangeListener { view, _, top, _, bottom, _, _, _, _ ->
                view.systemGestureExclusionRects = listOf(Rect(0, 0, view.width, bottom - top))
            }
        }
    }

    private val longPressRunnable = Runnable {
        if (isDragging || longPressFired) {
            return@Runnable
        }
        longPressFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onDragStart?.invoke()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                longPressFired = false
                isDragging = false
                isSwipingUp = false
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, longPressTimeout)
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging || isSwipingUp) {
                    return true
                }
                if (!longPressFired) {
                    // an upward flick opens the grid; it is the one gesture that must never be
                    // mistaken for the horizontal strip swipe, so it wins as soon as it is clearly up
                    val draggedUp = downY - ev.y
                    val draggedSideways = abs(ev.x - downX)
                    if (draggedUp > swipeUpThreshold && draggedUp > draggedSideways * 1.2f) {
                        removeCallbacks(longPressRunnable)
                        isSwipingUp = true
                        return true
                    }

                    // any real movement before the long press is a regular strip swipe
                    if (draggedSideways > touchSlop || abs(ev.y - downY) > touchSlop) {
                        removeCallbacks(longPressRunnable)
                    }
                } else if (abs(ev.y - downY) > touchSlop) {
                    isDragging = true
                    lastY = ev.y
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                // a long press or a flick that ends without further movement must not turn into a click
                if (longPressFired || isSwipingUp) {
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPressRunnable)
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // decide before the flags are reset, the gesture stays ours until the finger is off
        val handled = isDragging || longPressFired || isSwipingUp
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dy = ev.y - lastY
                    lastY = ev.y
                    onDrag?.invoke(dy)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    onDragEnd?.invoke()
                }
                if (isSwipingUp) {
                    isSwipingUp = false
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        onSwipeUp?.invoke()
                    }
                }
                longPressFired = false
                removeCallbacks(longPressRunnable)
            }
        }
        return handled
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }
}
