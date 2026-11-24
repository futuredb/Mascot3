package com.generativemascot.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.sin

internal fun Context.overlayDp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

private const val OVERLAY_MESSAGE_MAX_LINES = 3
private const val OVERLAY_MESSAGE_SIZE_SP = 17.5f
private const val OVERLAY_MESSAGE_COMPACT_SIZE_SP = 15.5f

/** Small transparent window: input is never captured over the rest of the display. */
internal class OverlayBitmapView(context: Context, private val artwork: OverlayArtwork,
    private val fullCanvas: Boolean = false) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var began = 0L
    var animationEnabled = true
    init { isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); began = SystemClock.uptimeMillis(); invalidate() }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun hintAnchorY(w: Int, h: Int): Int {
        if (fullCanvas) {
            val source = OverlayFraming.source(artwork)
            val target = OverlayFraming.destination(artwork, w, h)
            return (target.top + (artwork.bounds.top + artwork.bounds.height() * .22f - source.top) *
                target.height() / source.height() + context.overlayDp(8)).toInt()
        }
        val fit = minOf(w * .94f / artwork.bounds.width(), h * .94f / artwork.bounds.height())
        val visibleHeight = artwork.bounds.height() * fit
        // Follow the visible head area, not transparent margins or the bottom of a short hero.
        return (h * .97f - visibleHeight * .78f + context.overlayDp(8)).toInt()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (artwork.frames.isEmpty() || artwork.frames.first().isRecycled) return
        val animate = animationEnabled && ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow
        val elapsed = if (animate) (SystemClock.uptimeMillis() - began).coerceAtLeast(0) else 0
        val frame = artwork.frames[((elapsed / artwork.intervalMs) % artwork.frames.size).toInt()]
        val breathing = if (animate && artwork.frames.size == 1 && artwork.video == null)
            sin(elapsed / 3800.0 * Math.PI * 2).toFloat() else 0f
        val source = if (fullCanvas) OverlayFraming.source(artwork) else artwork.bounds
        if (fullCanvas) {
            destination.set(OverlayFraming.destination(artwork, width, height))
            destination.top -= destination.height() * breathing * .006f
        } else {
            val scale = minOf(width * .94f / source.width(), height * .94f / source.height())
            val w = source.width() * scale
            val h = source.height() * scale * (1 + breathing * .006f)
            destination.set((width - w) / 2, height * .97f - h, (width + w) / 2, height * .97f)
        }
        canvas.drawBitmap(frame, source, destination, paint)
        if (animate && visibility == VISIBLE && artwork.video == null)
            postInvalidateDelayed(if (artwork.frames.size > 1) artwork.intervalMs else 50)
    }
}

/** The card owns the full pointer stream, including gestures starting on buttons. */
class OverlayHintView(context: Context, onNext: () -> Unit, onHide: () -> Unit,
    onCollapse: () -> Unit = {}, onChat: () -> Unit = {}) : FrameLayout(context) {
    private val card = OverlayHintContent(context, onNext, onHide, onCollapse, onChat)
    internal var gestureTouch: View.OnTouchListener? = null
    private var pinching = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var baseWidth = 1
    private var baseHeight = 1
    internal var compositionScale = 1f
        set(value) { val next = value.coerceAtLeast(.01f); if (field != next) { field = next; requestLayout() } }
    val tailInset get() = context.overlayDp(32)
    val tailHeight get() = (context.overlayDp(20) * compositionScale).toInt()
    init { isMotionEventSplittingEnabled = false; clipChildren = false; clipToPadding = false; addView(card) }
    fun setMessage(text: String, hasMore: Boolean) = card.setMessage(text, hasMore)
    fun pointTowardHero(onLeft: Boolean) = card.pointTowardHero(onLeft)
    fun aimAtHero(x: Float) = card.aimAtHero(x / compositionScale)
    internal fun tailCenters() = card.tailCenters().map { PointF(it.x * compositionScale, it.y * compositionScale) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        baseWidth = (width / compositionScale).toInt().coerceAtLeast(1)
        card.measure(MeasureSpec.makeMeasureSpec(baseWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(heightMeasureSpec) / compositionScale).toInt(), MeasureSpec.AT_MOST))
        baseHeight = card.measuredHeight
        setMeasuredDimension(width, kotlin.math.ceil(baseHeight * compositionScale).toInt())
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        card.pivotX = 0f; card.pivotY = 0f
        card.scaleX = compositionScale; card.scaleY = compositionScale
        card.layout(0, 0, baseWidth, baseHeight)
    }
    internal fun touchRegion(): android.graphics.Region {
        val path = android.graphics.Path()
        val s = compositionScale
        val d = resources.displayMetrics.density * s
        path.addRoundRect(RectF(0f, 20*d, width-17*d, height-16*d), 20*d, 20*d, android.graphics.Path.Direction.CW)
        path.addCircle(width-24*d, 24*d, 24*d, android.graphics.Path.Direction.CW)
        return android.graphics.Region().apply { setPath(path, android.graphics.Region(0, 0, width, height)) }
    }
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            pinching = false; dragging = false; downX = event.rawX; downY = event.rawY
        }
        val beginsPinch = !pinching && event.pointerCount > 1
        val beginsDrag = !pinching && !dragging && event.actionMasked == android.view.MotionEvent.ACTION_MOVE &&
            kotlin.math.hypot(event.rawX - downX, event.rawY - downY) > touchSlop
        if (beginsPinch || beginsDrag) {
            if (beginsPinch) pinching = true else dragging = true
            android.view.MotionEvent.obtain(event).also { cancel ->
                cancel.action = android.view.MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel); cancel.recycle()
            }
        }
        gestureTouch?.onTouch(this, event)
        val handled = if (pinching || dragging) true else super.dispatchTouchEvent(event)
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
            pinching = false; dragging = false
        }
        return handled || gestureTouch != null
    }
}

/** Figma 7:11, adapted to a 248 dp touch window. Assets are bundled exports. */
private class OverlayHintContent(context: Context, onNext: () -> Unit, onHide: () -> Unit,
    onCollapse: () -> Unit = {}, onChat: () -> Unit = {}) : FrameLayout(context) {
    val tailInset get() = dp(32)
    val tailHeight get() = dp(20)
    private var heroTargetX: Float? = null
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val title = label("", OVERLAY_MESSAGE_SIZE_SP, Color.BLACK)
    private var measuredTextWidth = 0
    private lateinit var nextButton: TextView
    private val tail = android.graphics.BitmapFactory.decodeResource(resources, com.generativemascot.app.R.drawable.overlay_tail)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    init {
        setWillNotDraw(false)
        clipToPadding = false
        setPadding(0, dp(20), dp(17), dp(16))
        content.setPadding(dp(12), dp(28), dp(12), dp(12))
        content.background = overlayCardBackground(context, 20)
        content.elevation = dp(5).toFloat()
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        title.typeface = resources.getFont(com.generativemascot.app.R.font.sb_sans_display_medium)
        title.gravity = Gravity.CENTER
        title.setLineSpacing(0f, 1f)
        title.maxLines = OVERLAY_MESSAGE_MAX_LINES
        title.ellipsize = TextUtils.TruncateAt.END
        content.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        fun button(text: String, width: Int, action: () -> Unit): TextView {
            val view = label(text, 16f, Color.WHITE).apply {
                gravity = Gravity.CENTER; isClickable = true; isFocusable = true
                setAutoSizeTextTypeUniformWithConfiguration(12, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.rgb(29,29,29), Color.rgb(56,56,56))).apply { cornerRadius = dp(12).toFloat() }
                setOnClickListener { action() }
            }
            actions.addView(view, LinearLayout.LayoutParams(dp(width), dp(40)).apply { rightMargin = dp(7) })
            return view
        }
        nextButton = button("Дальше", 72, onNext)
        button("Скрыть", 76, onHide)
        actions.addView(android.widget.ImageView(context).apply {
            setImageResource(com.generativemascot.app.R.drawable.overlay_chat)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            tag = "hint-chat"; contentDescription = "Открыть чат"; isClickable = true; isFocusable = true
            setOnClickListener { onChat() }
        }, LinearLayout.LayoutParams(dp(38), dp(40)))
        content.addView(actions, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)).apply { topMargin = dp(12) })
        addView(android.widget.ImageView(context).apply {
            setImageResource(com.generativemascot.app.R.drawable.overlay_collapse)
            rotation = 90f
            elevation = dp(8).toFloat()
            tag = "collapse"; contentDescription = "Свернуть героя"
            isClickable = true; isFocusable = true
            setPadding(dp(5), dp(5), dp(5), dp(5))
            setOnClickListener { onCollapse() }
        }, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.RIGHT).apply {
            topMargin = -dp(20); rightMargin = -dp(17)
        })
    }
    fun setMessage(text: String, hasMore: Boolean) {
        if (title.text != text) {
            measuredTextWidth = 0
            title.text = text
        }
        nextButton.visibility = if (hasMore) VISIBLE else GONE
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight -
            content.paddingLeft - content.paddingRight - title.compoundPaddingLeft - title.compoundPaddingRight
        if (textWidth > 0 && textWidth != measuredTextWidth) {
            measuredTextWidth = textWidth
            val regularPaint = TextPaint(title.paint).apply {
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                    OVERLAY_MESSAGE_SIZE_SP, resources.displayMetrics)
            }
            val regularLayout = StaticLayout.Builder.obtain(title.text, 0, title.length(), regularPaint, textWidth)
                .setBreakStrategy(title.breakStrategy)
                .setHyphenationFrequency(title.hyphenationFrequency)
                .setMaxLines(OVERLAY_MESSAGE_MAX_LINES)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            title.textSize = if (regularLayout.lineCount == OVERLAY_MESSAGE_MAX_LINES) {
                OVERLAY_MESSAGE_COMPACT_SIZE_SP
            } else {
                OVERLAY_MESSAGE_SIZE_SP
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    fun pointTowardHero(onLeft: Boolean) { /* The new card is centred below the body. */ }
    fun aimAtHero(x: Float) { if (x.isFinite() && heroTargetX != x) { heroTargetX = x; invalidate() } }
    internal fun tailCenters(): List<PointF> {
        val x = (heroTargetX ?: (width - dp(17)) / 2f).coerceIn(dp(16).toFloat(), (width - dp(48)).coerceAtLeast(dp(16)).toFloat())
        return listOf(PointF(x, dp(19).toFloat()), PointF(x-dp(3),dp(14).toFloat()), PointF(x-dp(5),dp(10).toFloat()))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = tailCenters().last().x
        canvas.drawBitmap(tail, null, RectF(x-dp(2), dp(8).toFloat(), x+dp(13), dp(24).toFloat()), paint)
    }
    private fun dp(n: Int) = context.overlayDp(n)
    private fun label(text: String, size: Float, color: Int) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(color)
        typeface = resources.getFont(com.generativemascot.app.R.font.sb_sans_display_medium)
        includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL
    }
}

internal fun overlayCardBackground(context: Context, radius: Int) = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.WHITE, Color.WHITE, Color.rgb(217,217,225))
).apply { cornerRadius = context.overlayDp(radius).toFloat() }

/** Collapsed overlay control. */
internal class OverlayCompactView(context: Context, onExpand: () -> Unit) : FrameLayout(context) {
    private val badge = android.widget.ImageView(context).apply {
        setImageResource(com.generativemascot.app.R.drawable.overlay_status)
        elevation = context.overlayDp(6).toFloat()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun setHasMessage(value: Boolean) { badge.visibility = if (value) VISIBLE else GONE }
    init {
        clipChildren = false
        contentDescription = "Меню персонажа. Удерживайте, чтобы открыть действия"
        isClickable = true; isFocusable = true
        addView(android.widget.ImageView(context).apply {
            setImageResource(com.generativemascot.app.R.drawable.overlay_handle)
            setPadding(context.overlayDp(8), context.overlayDp(8), context.overlayDp(8), context.overlayDp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.WHITE, Color.WHITE, Color.rgb(217, 217, 225))).apply {
                cornerRadius = context.overlayDp(32).toFloat()
            }
            elevation = context.overlayDp(5).toFloat()
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(context.overlayDp(40), context.overlayDp(40), Gravity.CENTER))
        addView(badge, LayoutParams(context.overlayDp(10), context.overlayDp(10)).apply {
            leftMargin = context.overlayDp(31); topMargin = context.overlayDp(3)
        })
        setOnClickListener { onExpand() }
    }
    override fun onInterceptTouchEvent(event: android.view.MotionEvent) = true
}
