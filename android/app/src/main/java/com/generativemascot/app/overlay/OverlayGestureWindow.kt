package com.generativemascot.app.overlay

import android.content.Context
import android.graphics.Region
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/** One Android input owner for the entire companion, including buttons and both fingers. */
internal class OverlayGestureWindow(context: Context,
    private val targets: () -> List<Pair<View, Region>>,
) : View(context) {
    private var owner: View? = null
    init { isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun touchRegion(): Region = if (owner != null) Region(0, 0, width, height) else Region().apply {
        targets().forEach { (_, region) -> op(region, Region.Op.UNION) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            owner = targets().firstOrNull { (_, region) -> region.contains(event.rawX.toInt(), event.rawY.toInt()) }?.first
            updateInputRegion()
        }
        val target = owner ?: return false
        val params = target.layoutParams as? WindowManager.LayoutParams ?: return false
        MotionEvent.obtain(event).also { forwarded ->
            // Offset local coordinates only: raw coordinates remain screen coordinates,
            // even while the visual windows move or resize underneath this input window.
            forwarded.offsetLocation(event.rawX - event.x - params.x, event.rawY - event.y - params.y)
            target.dispatchTouchEvent(forwarded)
            forwarded.recycle()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            owner = null
            updateInputRegion()
        }
        return true
    }
    private fun updateInputRegion() {
        // Android can route a second pointer to another window despite SPLIT_TOUCH
        // being absent. Capture the screen only while an overlay gesture is active.
        if (android.os.Build.VERSION.SDK_INT >= 33) rootSurfaceControl?.setTouchableRegion(touchRegion())
        invalidate()
    }
    override fun onDetachedFromWindow() { owner = null; super.onDetachedFromWindow() }
}
