package com.generativemascot.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.generativemascot.app.physics.PhoneInteractionMotion
import com.generativemascot.app.physics.PhoneMotion
import com.generativemascot.app.physics.PhonePose
import com.generativemascot.app.physics.TiltLighting
import kotlinx.coroutines.isActive

@Stable
internal class HeroPhoneInteraction(val motion: PhoneMotion) {
    val lighting = TiltLighting()
    var pose by mutableStateOf(PhonePose())
        internal set
}

@Composable
internal fun rememberHeroPhoneInteraction(heroId: String?, enabled: Boolean, onShake: () -> Unit): HeroPhoneInteraction {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(heroId, context) { HeroPhoneInteraction(PhoneMotion(context)) }
    val shaken by rememberUpdatedState(onShake)
    LaunchedEffect(state, enabled, lifecycle) {
        if (!enabled) { state.pose = PhonePose(); state.lighting.reset(); return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val movement = PhoneInteractionMotion()
            var previous = 0L
            try {
                if (!state.motion.start()) return@repeatOnLifecycle
                while (isActive) withFrameNanos { now ->
                    val sample = state.motion.sample
                    if (movement.observe(sample)) shaken()
                    if (previous > 0) {
                        val seconds=(now-previous)/1e9f
                        state.pose = movement.advance(seconds, sample)
                        state.lighting.advance(seconds, sample)
                    }
                    previous = now
                }
            } finally {
                state.motion.stop()
                state.pose = PhonePose()
                state.lighting.reset()
            }
        }
    }
    return state
}
