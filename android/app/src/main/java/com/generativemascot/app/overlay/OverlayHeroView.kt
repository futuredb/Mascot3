package com.generativemascot.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.generativemascot.app.R
import com.generativemascot.app.physics.PhonePose
import com.generativemascot.app.ui.applyBackgroundKey
import com.generativemascot.app.ui.releaseMascotVideoPlayer
import kotlin.math.roundToInt

/** Silent local idle/reaction clips, keyed on the GPU. Decoder lifetime follows the window. */
@OptIn(UnstableApi::class)
class OverlayHeroView(context: Context, private val artwork: OverlayArtwork) : FrameLayout(context) {
    private val content = FrameLayout(context)
    private val poster = OverlayBitmapView(context, artwork, fullCanvas = true)
    private var phonePose = PhonePose()
    private var player: ExoPlayer? = null
    private var videoView: PlayerView? = null
    private var resumePosition = 0L
    private var failed = false
    private val reactions = OverlayReactions(artwork.reactions)
    private var playbackListener: Player.Listener? = null
    var animationEnabled = true
        set(value) {
            field = value
            poster.animationEnabled = value
            poster.invalidate()
            updatePlayback()
        }

    init {
        isMotionEventSplittingEnabled = false
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        poster.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(poster, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun hintAnchorY(w: Int, h: Int): Int {
        val body = hintBounds(w, h)
        return (body.top + body.height() * .78f).roundToInt()
    }
    internal fun hintBounds(w: Int, h: Int, transformed: Boolean = true) = OverlayFraming.visibleBounds(artwork, w, h).also {
        if (transformed) content.matrix.mapRect(it)
    }
    internal fun hintTargetX(w: Int, h: Int, cardOnRight: Boolean): Float {
        val visible = hintBounds(w, h)
        return visible.centerX()
    }

    internal fun touchRegion(): android.graphics.Region {
        val bounds = hintBounds(width, height)
        val rect = android.graphics.Rect()
        bounds.roundOut(rect)
        rect.intersect(0, 0, width, height)
        return android.graphics.Region(rect)
    }

    override fun performClick(): Boolean { react(); super.performClick(); return true }
    override fun onInterceptTouchEvent(event: MotionEvent) = true

    internal fun applyPhonePose(pose: PhonePose) {
        phonePose = pose
        if (width <= 0 || height <= 0) return
        val motion = pose.artwork()
        content.pivotX = width / 2f; content.pivotY = height / 2f
        content.cameraDistance = maxOf(width, height) * 20f
        content.translationX = 0f; content.translationY = 0f
        content.rotationX = motion.rotationX; content.rotationY = motion.rotationY
        content.rotation = motion.rotationZ
        content.scaleX = motion.scaleX; content.scaleY = motion.scaleY
        val frame = OverlayFraming.destination(artwork, width, height)
        val projected = RectF(frame)
        content.matrix.mapRect(projected)
        // Guard the full source, not just the idle silhouette, at every phone pose.
        val correction = minOf(1f, (width - 2f) / projected.width(), (height - 2f) / projected.height())
        if (correction < 1f) {
            content.scaleX *= correction; content.scaleY *= correction
            projected.set(frame); content.matrix.mapRect(projected)
        }
        val density = resources.displayMetrics.density
        content.translationX = (motion.translationX * density)
            .coerceIn(-projected.left, width - projected.right)
        content.translationY = (motion.translationY * density)
            .coerceIn(-projected.top, height - projected.bottom)
    }

    /** Repeated taps during a reaction are consumed, never queued or restarted. */
    fun react(): Boolean {
        val current = player ?: return false
        if (!animationEnabled || !isAttachedToWindow || !isShown || failed) return false
        val clip = reactions.take() ?: return false
        playClip(current, clip, repeat = false)
        return true
    }

    private fun playClip(current: ExoPlayer, clip: OverlayVideo, repeat: Boolean, position: Long = 0) {
        videoView?.let { applyBackgroundKey(it, clip.keyColor, tuning = clip.tuning) }
        current.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        // Keep the outgoing texture until the incoming clip produces a frame. All clips use
        // the same fixed viewport, so tapping never rescales the hero or moves the card.
        current.setMediaItem(MediaItem.fromUri(Uri.fromFile(clip.file)), position)
        current.prepare()
        current.play()
    }

    private fun returnToIdle(current: ExoPlayer) {
        reactions.finish()
        resumePosition = 0
        artwork.video?.let { playClip(current, it, repeat = true) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        failed = false
        updatePlayback()
    }

    override fun onDetachedFromWindow() {
        stopPlayback()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (childCount > 0) updatePlayback()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (childCount > 0) {
            if (isVisible) updatePlayback() else stopPlayback()
        }
    }

    private fun updatePlayback() {
        if (!isAttachedToWindow || windowVisibility != VISIBLE || !isShown ||
            !animationEnabled || !ValueAnimator.areAnimatorsEnabled()) {
            stopPlayback()
            return
        }
        val source = artwork.video ?: return
        if (player != null || failed) return
        try {
            val view = LayoutInflater.from(context).inflate(R.layout.mascot_video_player, this, false) as PlayerView
            videoView = view
            view.alpha = 0f // Do not expose a green decoder frame before the matte is installed.
            view.isClickable = false
            view.isFocusable = false
            view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            view.setKeepContentOnPlayerReset(true)
            view.setShutterBackgroundColor(Color.TRANSPARENT)
            (view.videoSurfaceView as? TextureView)?.isOpaque = false
            content.addView(view)
            val current = ExoPlayer.Builder(context.applicationContext).build()
            player = current
            current.volume = 0f
            current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            view.player = current
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (player !== current || !isAttachedToWindow) return
                    view.alpha = 1f
                    poster.visibility = INVISIBLE
                }
                override fun onPlayerError(error: PlaybackException) {
                    // Release outside the callback and keep a clean transparent poster on failure.
                    val brokenItem = current.currentMediaItem
                    post {
                        if (player !== current || current.currentMediaItem != brokenItem) return@post
                        if (reactions.active != null) {
                            reactions.discardActive()
                            returnToIdle(current)
                        } else { failed = true; stopPlayback() }
                    }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (player === current && playbackState == Player.STATE_ENDED && reactions.active != null)
                        returnToIdle(current)
                }
            }
            playbackListener = listener
            current.addListener(listener)
            playClip(current, source, repeat = true, position = resumePosition)
            requestLayout()
        } catch (_: Exception) {
            failed = true
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        val current = player
        player = null // Ignore queued callbacks from a detached/previous decoder.
        videoView?.let { it.player = null; content.removeView(it) }
        videoView = null
        if (current != null) {
            resumePosition = if (reactions.active != null) 0 else current.currentPosition.coerceAtLeast(0)
            playbackListener?.let(current::removeListener)
            releaseMascotVideoPlayer(current)
        }
        playbackListener = null
        reactions.finish()
        poster.visibility = VISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        videoView?.let {
            val frame = OverlayFraming.destination(artwork, measuredWidth, measuredHeight)
            it.measure(MeasureSpec.makeMeasureSpec(frame.width().roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(frame.height().roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        videoView?.let {
            val frame = OverlayFraming.destination(artwork, width, height)
            val x = frame.left.roundToInt()
            val y = frame.top.roundToInt()
            it.layout(x, y, x + it.measuredWidth, y + it.measuredHeight)
        }
        applyPhonePose(phonePose)
    }
}
