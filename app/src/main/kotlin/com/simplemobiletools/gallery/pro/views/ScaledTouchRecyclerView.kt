package com.simplemobiletools.gallery.pro.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/**
 * Horizontal RecyclerView that scrolls faster than the finger.
 *
 * The strip is only 48dp wide per thumbnail, so a full swipe across the screen went through
 * barely four of them. RecyclerView only looks at the distance between two consecutive touch
 * events, so mirroring the event position around the touch down point multiplies exactly that
 * distance - the scroll, and the fling speed it collects on the way, both end up `touchScale`
 * times as fast without touching the scroll logic itself.
 */
class ScaledTouchRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    /** 1 = the list follows the finger 1:1, 2 = twice as fast. */
    var touchScale = 2f

    private var downX = 0f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = ev.x
        } else if (ev.actionMasked == MotionEvent.ACTION_MOVE && touchScale != 1f) {
            // x' = (1 - scale) * downX + scale * x, which keeps the direction of the finger and only
            // multiplies the distance between two consecutive events
            ev.offsetLocation((touchScale - 1f) * (ev.x - downX), 0f)
        }
        return super.dispatchTouchEvent(ev)
    }
}
