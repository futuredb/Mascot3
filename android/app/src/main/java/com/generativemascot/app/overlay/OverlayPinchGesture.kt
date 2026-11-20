package com.generativemascot.app.overlay

import android.view.MotionEvent
import kotlin.math.hypot

/** Two-finger scaling for a small overlay, without a screen-sized minimum span. */
internal class OverlayPinchGesture(
    private val slop: Float,
    private val onBegin: (Float, Float) -> Boolean,
    private val onScale: (Float, Float, Float) -> Unit,
    private val onEnd: () -> Unit,
) {
    private var first = -1
    private var second = -1
    private var previousSpan = 0f
    private var active = false
    private var scaling = false

    fun cancel() {
        val wasActive = active
        active = false
        scaling = false
        first = -1; second = -1
        if (wasActive) onEnd()
    }

    private fun seed(event: MotionEvent, excluded: Int = -1) {
        val indices = (0 until event.pointerCount).filter { it != excluded }
        if (indices.size < 2) return
        val a = indices[0]; val b = indices[1]
        first = event.getPointerId(a); second = event.getPointerId(b)
        previousSpan = hypot(event.getX(a) - event.getX(b), event.getY(a) - event.getY(b))
        val x = (event.getX(a) + event.getX(b)) / 2 + event.rawX - event.x
        val y = (event.getY(a) + event.getY(b)) / 2 + event.rawY - event.y
        active = onBegin(x, y)
    }

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancel()
            MotionEvent.ACTION_POINTER_DOWN -> if (first == -1) seed(event)
            MotionEvent.ACTION_POINTER_UP -> {
                val lifted = event.getPointerId(event.actionIndex)
                if (lifted == first || lifted == second) {
                    cancel()
                    seed(event, event.actionIndex)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val a = event.findPointerIndex(first); val b = event.findPointerIndex(second)
                if (a < 0 || b < 0) return
                val span = hypot(event.getX(a) - event.getX(b), event.getY(a) - event.getY(b))
                // Avoid a singular scale when the fingertips meet.
                if (!span.isFinite() || span < slop) return
                val x = (event.getX(a) + event.getX(b)) / 2 + event.rawX - event.x
                val y = (event.getY(a) + event.getY(b)) / 2 + event.rawY - event.y
                // The second finger arms the gesture immediately. Do not throw
                // away the first inward movement, especially at maximum size.
                if (!scaling && kotlin.math.abs(span - previousSpan) < slop) return
                scaling = true
                if (active && previousSpan > 0f && span != previousSpan) {
                    onScale(span / previousSpan, x, y)
                }
                previousSpan = span
            }
        }
    }
}
