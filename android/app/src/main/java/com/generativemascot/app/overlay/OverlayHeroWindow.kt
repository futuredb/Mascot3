package com.generativemascot.app.overlay

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/** Tight OS window around all poses, with the original video coordinates kept inside. */
internal class OverlayHeroWindow(context: Context, val hero: OverlayHeroView,
    private val artwork: OverlayArtwork) : ViewGroup(context) {
    val crop = Rect()
    private var virtualWidth = 1
    private var virtualHeight = 1
    init {
        clipChildren = true
        addView(hero)
    }
    fun configure(p: WindowManager.LayoutParams): WindowManager.LayoutParams {
        virtualWidth = p.width; virtualHeight = p.height
        hero.layoutParams = p
        val full = OverlayFraming.source(artwork)
        val frame = OverlayFraming.destination(artwork, p.width, p.height)
        val source = artwork.canvasBounds ?: if (artwork.video == null) artwork.bounds else full
        val scale = frame.width() / full.width()
        val visible = RectF(frame.left + (source.left - full.left) * scale,
            frame.top + (source.top - full.top) * scale,
            frame.left + (source.right - full.left) * scale,
            frame.top + (source.bottom - full.top) * scale)
        // Reserve room for gyro rotation and translation without enlarging to the video canvas.
        visible.inset(-maxOf(8f * resources.displayMetrics.density, visible.height() * .12f),
            -maxOf(8f * resources.displayMetrics.density, visible.width() * .12f))
        visible.roundOut(crop)
        crop.intersect(0, 0, p.width, p.height)
        requestLayout()
        return WindowManager.LayoutParams().apply {
            copyFrom(p); x = p.x + crop.left; y = p.y + crop.top
            width = crop.width(); height = crop.height()
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // Android 12+ permits pass-through only up to the system opacity limit.
            // Only the card owns input, so the hero can use almost the full limit.
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val limit = context.getSystemService(android.hardware.input.InputManager::class.java)
                    .maximumObscuringOpacityForTouch.coerceIn(0f, 1f)
                alpha = limit
            }
        }
    }
    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        hero.measure(MeasureSpec.makeMeasureSpec(virtualWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(virtualHeight, MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        hero.layout(-crop.left, -crop.top, virtualWidth - crop.left, virtualHeight - crop.top)
    }
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
}
