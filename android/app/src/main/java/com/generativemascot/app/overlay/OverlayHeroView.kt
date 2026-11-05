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
import com.generativemascot.app.physics.PhoneSample
import com.generativemascot.app.physics.PhoneMotion
import com.generativemascot.app.physics.InstalledPhysics
import com.generativemascot.app.physics.PhysicsView
import com.generativemascot.app.physics.TiltLighting
import com.generativemascot.app.ui.applyBackgroundKey
import com.generativemascot.app.ui.createMascotVideoPlayer
import com.generativemascot.app.ui.loadVideoLighting
import com.generativemascot.app.ui.VideoLightingBinding
import com.generativemascot.app.ui.releaseMascotVideoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Silent local idle/reaction clips, keyed on the GPU. Decoder lifetime follows the window. */
@OptIn(UnstableApi::class)
class OverlayHeroView(context: Context, private val artwork: OverlayArtwork,
    initialPhysics: InstalledPhysics? = null, sharedMotion: PhoneMotion? = null) : FrameLayout(context) {
    private val content = FrameLayout(context)
    private val poster = OverlayBitmapView(context, artwork, fullCanvas = true)
    private var phonePose = PhonePose()
    private var player: ExoPlayer? = null
    private var videoView: PlayerView? = null
    private var resumePosition = 0L
    private var failed = false
    private val reactions = OverlayReactions(artwork.reactions)
    private var playbackListener: Player.Listener? = null
    private val lighting = TiltLighting()
    private var installed: InstalledPhysics? = null
    private var physicsView: PhysicsView? = null
    private var physicsReady = false
    private var videoReady = false
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var prepareClip: Job? = null

    internal fun updateLighting(seconds: Float, sample: PhoneSample) { lighting.advance(seconds, sample) }
    internal fun resetLighting() { lighting.reset() }
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
        if (initialPhysics != null && sharedMotion != null) installPhysics(initialPhysics, sharedMotion)
    }

    internal fun installPhysics(pack: InstalledPhysics, sharedMotion: PhoneMotion) {
        if (physicsView != null) return
        installed = pack
        val physics = PhysicsView(context, pack, sharedMotion = sharedMotion, sharedLighting = lighting)
        physicsView = physics
        physics.let {
            // A non-zero alpha keeps the TextureView consuming frames while
            // the first GL frame is prepared (zero alpha can stall readiness).
            physics.alpha = .01f
            physics.onReady = {
                physicsReady = true
                physics.alpha = if (reactions.active == null) 1f else .01f
                if (reactions.active == null) { videoView?.alpha = 0f; player?.pause() }
                poster.visibility = INVISIBLE
                applyPhonePose(phonePose)
            }
            physics.onFailure = {
                physicsReady = false; physics.alpha = 0f
                if (videoReady) { videoView?.alpha = 1f; poster.visibility = INVISIBLE; player?.play() }
            }
            content.addView(physics, 1)
        }
        if (isAttachedToWindow) updatePlayback()
        requestLayout()
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
        val motion = pose.artwork(preparedPhysics = physicsReady && reactions.active == null)
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
        prepareClip?.cancel()
        videoView?.animate()?.cancel()
        val uri = Uri.fromFile(clip.file)
        videoView?.let { applyBackgroundKey(it, clip.keyColor, tuning = clip.tuning, forceVideoEffect = true) }
        current.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        current.setMediaItem(MediaItem.fromUri(uri), position)
        current.prepare()
        current.play()
        prepareClip = playbackScope.launch {
            val pack = loadVideoLighting(context, clip.file.toURI().toString())
            if (player !== current || !isAttachedToWindow || current.currentMediaItem?.localConfiguration?.uri != uri) return@launch
            if (pack != null) videoView?.let { applyBackgroundKey(it, clip.keyColor, VideoLightingBinding(pack, lighting), tuning = clip.tuning, forceVideoEffect = true) }
        }
    }

    private fun returnToIdle(current: ExoPlayer) {
        reactions.finish()
        resumePosition = 0
        if (physicsReady) {
            physicsView?.alpha = 1f
            videoView?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction { if (player === current && reactions.active == null) current.pause() }?.start()
            applyPhonePose(phonePose)
        } else artwork.video?.let { playClip(current, it, repeat = true) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        failed = false
        updatePlayback()
    }

    override fun onDetachedFromWindow() {
        stopPlayback()
        physicsReady = false
        physicsView?.pause()
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
            physicsView?.pause()
            stopPlayback()
            return
        }
        physicsView?.resume()
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
            val current = createMascotVideoPlayer(context.applicationContext)
            player = current
            current.volume = 0f
            current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            view.player = current
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (player !== current || !isAttachedToWindow) return
                    videoReady = true
                    view.alpha = if (physicsReady && reactions.active == null) 0f else 1f
                    if (physicsReady && reactions.active != null) physicsView?.alpha = .01f
                    if (physicsReady && reactions.active == null) current.pause()
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
        prepareClip?.cancel()
        prepareClip = null
        val current = player
        player = null // Ignore queued callbacks from a detached/previous decoder.
        videoReady = false
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
        physicsView?.let {
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
        physicsView?.let { view ->
            val frame = OverlayFraming.destination(artwork, width, height)
            val x = frame.left.roundToInt(); val y = frame.top.roundToInt()
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
            installed?.let { pack ->
                val fitted = minOf(view.width.toFloat() / pack.pack.width, view.height.toFloat() / pack.pack.height) * pack.pack.width
                view.scaleX = pack.framing.scale; view.scaleY = pack.framing.scale
                view.translationX = pack.framing.offsetX * fitted; view.translationY = pack.framing.offsetY * fitted
            }
        }
        applyPhonePose(phonePose)
    }
}
