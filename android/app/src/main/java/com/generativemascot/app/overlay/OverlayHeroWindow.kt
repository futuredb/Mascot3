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
    private var resizeViewport: Rect? = null
    private val resizeTarget = Rect()
    private var resizeViewportLaidOut = false
    val isResizing get() = resizeViewport != null
    init {
        clipChildren = true
        addView(hero)
    }
    fun configure(p: WindowManager.LayoutParams): WindowManager.LayoutParams {
        resizeViewport = null
        resizeViewportLaidOut = false
        virtualWidth = p.width; virtualHeight = p.height
        hero.layoutParams = p
        crop.set(cropFor(p.width, p.height))
        requestLayout()
        return windowParams(p, Rect(crop).apply { offset(p.x, p.y) })
    }

    /** Reserve enough screen space once, leaving the decoder's child layout intact. */
    fun beginResize(p: WindowManager.LayoutParams, smallest: Rect, largest: Rect): WindowManager.LayoutParams {
        val viewport = Rect(crop).apply { offset(p.x, p.y) }
        for (bounds in listOf(smallest, largest)) {
            viewport.union(cropFor(bounds.width(), bounds.height()).apply { offset(bounds.left, bounds.top) })
        }
        resizeViewport = viewport
        resizeViewportLaidOut = false
        resizeTarget.set(p.x, p.y, p.x + p.width, p.y + p.height)
        requestLayout()
        return windowParams(p, viewport)
    }

    fun previewResize(target: Rect) {
        resizeTarget.set(target)
        if (resizeViewportLaidOut) applyResizeTransform()
    }

    private fun applyResizeTransform() {
        val viewport = resizeViewport
        if (viewport == null) {
            hero.scaleX = 1f; hero.scaleY = 1f
            hero.translationX = 0f; hero.translationY = 0f
            return
        }
        hero.pivotX = 0f; hero.pivotY = 0f
        hero.scaleX = resizeTarget.width().toFloat() / virtualWidth
        hero.scaleY = resizeTarget.height().toFloat() / virtualHeight
        hero.translationX = (resizeTarget.left - viewport.left - hero.left).toFloat()
        hero.translationY = (resizeTarget.top - viewport.top - hero.top).toFloat()
    }

    private fun cropFor(width: Int, height: Int): Rect {
        val full = OverlayFraming.source(artwork)
        val frame = OverlayFraming.destination(artwork, width, height)
        val source = artwork.canvasBounds ?: if (artwork.video == null) artwork.bounds else full
        val scale = frame.width() / full.width()
        val visible = RectF(frame.left + (source.left - full.left) * scale,
            frame.top + (source.top - full.top) * scale,
            frame.left + (source.right - full.left) * scale,
            frame.top + (source.bottom - full.top) * scale)
        // Reserve room for gyro rotation and translation without enlarging to the video canvas.
        visible.inset(-maxOf(8f * resources.displayMetrics.density, visible.height() * .12f),
            -maxOf(8f * resources.displayMetrics.density, visible.width() * .12f))
        return Rect().apply {
            visible.roundOut(this)
            intersect(0, 0, width, height)
        }
    }

    private fun windowParams(p: WindowManager.LayoutParams, bounds: Rect) =
        WindowManager.LayoutParams().apply {
            copyFrom(p); x = bounds.left; y = bounds.top
            width = bounds.width(); height = bounds.height()
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // Android 12+ permits pass-through only up to the system opacity limit.
            // Only the card owns input, so the hero can use almost the full limit.
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val limit = context.getSystemService(android.hardware.input.InputManager::class.java)
                    .maximumObscuringOpacityForTouch.coerceIn(0f, 1f)
                alpha = limit
            }
        }
    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        hero.measure(MeasureSpec.makeMeasureSpec(virtualWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(virtualHeight, MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        hero.layout(-crop.left, -crop.top, virtualWidth - crop.left, virtualHeight - crop.top)
        resizeViewportLaidOut = isResizing
        applyResizeTransform()
    }
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
}
