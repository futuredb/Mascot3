package com.generativemascot.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.generativemascot.app.R

private class GenerationStartSound(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder().setMaxStreams(1)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private var sample = 0
    private var stream = 0
    private var released = false
    private val gate = GenerationSoundGate(play = {
        // UI feedback should not override the user's silent mode or sound-effect volume.
        if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            audio.getStreamVolume(AudioManager.STREAM_SYSTEM) > 0) {
            if (stream != 0) pool.stop(stream)
            stream = pool.play(sample, 1f, 1f, 1, 0, 1f) // loop=0: exactly once.
        }
    }, stop = {
        if (stream != 0) pool.stop(stream)
        stream = 0
    })

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (id == sample) {
                gate.onLoaded(status == 0)
                if (status != 0) Log.w("GenerationSound", "Could not load generation sound")
            }
        }
        sample = pool.load(context, R.raw.generation_start, 1)
    }

    fun resume() = gate.resume()
    fun pause() = gate.pause()
    fun play() = gate.request()
    fun release() {
        if (released) return
        gate.close()
        released = true
        pool.release()
    }
}

@Composable
internal fun rememberGenerationStartSound(): () -> Unit {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val sound = remember(context, owner) { GenerationStartSound(context.applicationContext) }
    DisposableEffect(sound, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> sound.resume()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> sound.pause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) sound.resume()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            sound.release()
        }
    }
    return remember(sound) { { sound.play() } }
}
