package com.generativemascot.app.physics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.TextureView
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.generativemascot.app.MainActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.generativemascot.app.data.BundledHeroImporter
import com.generativemascot.app.ui.VideoBackgroundKey
import com.generativemascot.app.ui.keyBitmap
import com.generativemascot.app.ui.applyBackgroundKey
import com.generativemascot.app.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Paused first video frame versus the authored PNG, through the real decoder. */
@OptIn(UnstableApi::class)
class VideoColorAcceptanceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val device: RuleChain = RuleChain.outerRule(PhysicsDeviceScreenRule()).around(compose)
    private fun content(block: @Composable () -> Unit) { compose.runOnUiThread { compose.activity.setContent(content=block) } }
    @Test fun untaggedVideoMatchesNeutralAnchor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BundledHeroImporter({context.assets.list(it).orEmpty()},{context.assets.open(it)},File(context.filesDir,"hero")).importAll()
        val hero = File(context.filesDir,"hero/${PhysicsStore.PILOT_ID}/videos")
        val out = File(context.getExternalFilesDir(null),"physics-acceptance").apply { mkdirs() }
        val original = requireNotNull(BitmapFactory.decodeFile(File(hero,"neutral.png").path))
        val anchor = keyBitmap(original, Color.GREEN); original.recycle()
        val ready=Array(2){AtomicBoolean()}
        val textures=arrayOfNulls<TextureView>(2)
        content {
            Column { repeat(2) { pass ->
                AndroidView(modifier=Modifier.size(320.dp),factory={ ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.mascot_video_player,FrameLayout(ctx),false) as PlayerView).also { view ->
                        val texture=view.videoSurfaceView as TextureView
                        textures[pass]=texture;texture.isOpaque=false
                        val builder=ExoPlayer.Builder(ctx)
                        val player=builder.build()
                        view.tag=player
                        view.player=player
                        if(pass==1)applyBackgroundKey(view,Color.GREEN)
                        else player.setVideoEffects(listOf(VideoBackgroundKey(Color.GREEN)))
                        player.addListener(object:Player.Listener { override fun onRenderedFirstFrame() { ready[pass].set(true) } })
                        player.setMediaItem(MediaItem.fromUri(File(hero,"greeting.mp4").toURI().toString()))
                        player.prepare()
                    }
                },onRelease={ (it.tag as ExoPlayer).release() })
            } }
        }
        val errors=mutableListOf<Double>()
        repeat(2) { pass ->
            compose.waitUntil(20000) { ready[pass].get() }
            val texture=requireNotNull(textures[pass])
            // onRenderedFirstFrame precedes the TextureView update callback.
            compose.waitUntil(10000) {
                var drawn=false
                compose.runOnIdle {
                    texture.bitmap?.let { b ->
                        drawn=(b.height/4 until b.height*3/4 step 8).any { y ->
                            (b.width/4 until b.width*3/4 step 8).any { x -> Color.alpha(b.getPixel(x,y))>250 }
                        }
                        b.recycle()
                    }
                }
                drawn
            }
            compose.runOnIdle {
                val actual=requireNotNull(texture.bitmap)
                val expected=Bitmap.createScaledBitmap(anchor,actual.width,actual.height,true)
                File(out,"video-color-$pass.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG,100,it) }
                var difference=0.0;var channels=0
                for(y in 2 until actual.height-2)for(x in 2 until actual.width-2) {
                    val a=expected.getPixel(x,y);val b=actual.getPixel(x,y)
                    if(Color.alpha(a)>250 && Color.alpha(b)>250) {
                        difference+=abs(Color.red(a)-Color.red(b))+abs(Color.green(a)-Color.green(b))+abs(Color.blue(a)-Color.blue(b));channels+=3
                    }
                }
                assertTrue(channels>30000)
                errors+=difference/channels
                actual.recycle();expected.recycle()
            }
        }
        anchor.recycle()
        File(out,"video-color-result.json").writeText("""{"default_rgb_mae":${errors[0]},"explicit_bt601_rgb_mae":${errors[1]}}""")
        assertTrue("Colour correction did not improve: $errors",errors[1]<errors[0]*.75)
        assertTrue("Video and anchor colours differ: $errors",errors[1]<8.0)
    }
}
