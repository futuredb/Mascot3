package com.generativemascot.app.overlay

import android.content.Context
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** Settings preview shares the real size and bubble geometry; resizing retains the decoder. */
internal class OverlayPreviewView(context: Context, private val artwork: OverlayArtwork) : FrameLayout(context) {
    private val hero = OverlayHeroView(context, artwork)
    private val card = OverlayHintView(context, {}, {}, { setCollapsed(true) }).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val compact = OverlayCompactView(context, artwork) { setCollapsed(false) }.apply { visibility = GONE }
    private var collapsed = false
    private var pinching = false
    private val pinch = OverlayPinchGesture(context.overlayDp(2).toFloat(),
        onBegin = { _, _ -> true },
        onScale = { factor, _, _ ->
            heroScale = OverlaySize.constrain(heroScale * factor)
        },
        onEnd = { OverlaySize.save(context, heroScale) })
    var heroScale = 1f
        set(value) { if (field != value) { field = value; requestLayout() } }
    var animationEnabled = true
        set(value) { field = value; hero.animationEnabled = value }
    init {
        isMotionEventSplittingEnabled = false
        addView(hero)
        addView(card)
        addView(compact)
    }
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) pinching = false
        if (!pinching && event.pointerCount > 1) {
            pinching = true
            android.view.MotionEvent.obtain(event).also { cancel ->
                cancel.action = android.view.MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel); cancel.recycle()
            }
        }
        pinch.onTouchEvent(event)
        if (!pinching) super.dispatchTouchEvent(event)
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) pinching = false
        return true
    }
    override fun onDetachedFromWindow() { pinch.cancel(); super.onDetachedFromWindow() }

    private fun setCollapsed(value: Boolean) {
        collapsed = value
        hero.animationEnabled = animationEnabled
        hero.visibility = VISIBLE
        card.visibility = if (value) GONE else VISIBLE
        compact.visibility = if (value) VISIBLE else GONE
        requestLayout()
    }
    private fun area() = OverlayArea(context.overlayDp(8), context.overlayDp(32),
        measuredWidth - context.overlayDp(8), measuredHeight - context.overlayDp(12))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(context.overlayDp(338), widthMeasureSpec)
        val top = context.overlayDp(32)
        val bottom = context.overlayDp(12)
        val gap = context.overlayDp(12)
        val area = OverlayArea(context.overlayDp(8), top, width - context.overlayDp(8), context.overlayDp(2_000))
        val size = OverlaySize.dimensions(heroScale, resources.displayMetrics.density, area, artwork)
        card.compositionScale = heroScale
        card.measure(MeasureSpec.makeMeasureSpec(minOf((context.overlayDp(248) * heroScale).roundToInt(), area.width), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(context.overlayDp(1_000), MeasureSpec.AT_MOST))
        val bodyHeight = card.measuredHeight - card.tailHeight
        val visible = OverlayFraming.visibleBounds(artwork, size.width, size.height)
        val heroBelowHead = visible.height() * .78f
        val required = top + bodyHeight + gap + heroBelowHead + bottom
        val height = resolveSize(maxOf(context.overlayDp(360), kotlin.math.ceil(required).toInt()), heightMeasureSpec)
        // The settings block grows with the composition. A fixed-height host can
        // still fit the preview without changing the real overlay size preference.
        val fit = minOf(1f, ((height - top - bodyHeight - gap - bottom) / heroBelowHead).coerceAtLeast(.1f))
        hero.measure(MeasureSpec.makeMeasureSpec((size.width * fit).roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((size.height * fit).roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY))
        compact.measure(MeasureSpec.makeMeasureSpec(context.overlayDp(48), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(context.overlayDp(48), MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val area = area()
        val visibleAtRest = hero.hintBounds(hero.measuredWidth, hero.measuredHeight, false)
        val point = OverlayPoint((measuredWidth / 2f - visibleAtRest.centerX()).roundToInt(), area.top - visibleAtRest.top.roundToInt() + context.overlayDp(8))
        hero.layout(point.x, point.y, point.x + hero.measuredWidth, point.y + hero.measuredHeight)
        val visible = hero.hintBounds(hero.width, hero.height)
        val anchor = OverlayPoint(point.x + visible.left.roundToInt(), point.y)
        val visibleWidth = visible.width().roundToInt().coerceAtLeast(1)
        val neutral = hero.hintBounds(hero.width, hero.height, transformed = false)
        val toRight = point.x + neutral.centerX() < area.left + area.width / 2
        card.pointTowardHero(toRight)
        val cardPoint = OverlayLayout.bubble(area, anchor, visibleWidth, card.measuredWidth, card.measuredHeight,
            context.overlayDp(2), hero.hintAnchorY(hero.width, hero.height), card.tailInset, card.tailHeight,
            toRight = toRight)
        card.aimAtHero(point.x + hero.hintTargetX(hero.width, hero.height, toRight) - cardPoint.x)
        card.layout(cardPoint.x, cardPoint.y, cardPoint.x + card.measuredWidth, cardPoint.y + card.measuredHeight)
        val bell = OverlayLayout.clamp(area, compact.measuredWidth, compact.measuredHeight,
            point.x + neutral.right.roundToInt() - compact.measuredWidth * 5 / 6,
            point.y + neutral.bottom.roundToInt() - compact.measuredHeight * 5 / 6)
        compact.layout(bell.x, bell.y, bell.x + compact.measuredWidth, bell.y + compact.measuredHeight)
    }
}
