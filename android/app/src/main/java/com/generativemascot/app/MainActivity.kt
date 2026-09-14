package com.generativemascot.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.ui.AppViewModel
import com.generativemascot.app.ui.AnimationLibraryScreen
import com.generativemascot.app.ui.KnockHeroScreen
import com.generativemascot.app.ui.MascotTheme
import com.generativemascot.app.ui.SettingsScreen
import com.generativemascot.app.ui.stateTitle
import com.generativemascot.app.ui.MascotTransition
import com.generativemascot.app.ui.FigmaCanvas

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels {
        val app = application as MascotApp
        AppViewModel.factory(app.api, app.session, app.heroStore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleWidgetIntent(intent)
        setContent {
            MascotTheme {
                val liveState by vm.state.collectAsState()
                BackHandler(liveState.route == "animations") {
                    vm.closeAnimationLibrary()
                }
                MascotTransition(
                    target = liveState,
                    modifier = Modifier.fillMaxSize().background(FigmaCanvas),
                    contentKey = {
                        when (it.route) {
                            "knock", "onboarding", "create", "progress", "meet", "hero" ->
                                "home:${it.mascot?.status == "READY"}:${it.creating}"
                            else -> it.route
                        }
                    },
                ) { state ->
                if (!state.ready) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else when (state.route) {
                    "knock", "onboarding", "create", "progress", "meet", "hero" -> {
                        val mascot = state.mascot
                        KnockHeroScreen(
                            generating = state.creating,
                            previewUrl = if (mascot?.status == "READY") {
                                state.context?.stillUrl ?: mascot.previewUrl
                            } else {
                                mascot?.previewUrl
                            },
                            animationFrames = state.context?.animationFrames.orEmpty(),
                            animationVideoUrl = state.context?.animationUrl,
                            animationVideoUrls = mascot?.id
                                ?.let { HeroLocalStore.current?.actionVideoUrls(it) }
                                .orEmpty(),
                            neutralVideoFrameUrl = mascot?.id
                                ?.let { HeroLocalStore.current?.neutralVideoFrameFile(it)?.toURI()?.toString() },
                            animationFps = state.context?.animationFps ?: HeroLocalStore.FULL_FRAME_FPS,
                            error = state.error,
                            accepted = mascot?.status == "READY",
                            mascotId = mascot?.id,
                            heroName = mascot?.name,
                            heroLibrary = state.heroLibrary,
                            stateKey = state.context?.stateKey,
                            stateLabel = if (mascot?.status == "READY") "Нажми, погладь или потяни" else null,
                            generationLabel = state.generationLabel,
                            onKnockComplete = vm::createHero,
                            onAccept = vm::accept,
                            onSelectHero = vm::selectLibraryHero,
                            onNewHero = vm::replaceHero,
                            onAnimations = vm::openAnimationLibrary,
                            onGenerate = vm::replaceHero,
                            onPoll = vm::pollProgress,
                            onRefresh = vm::refreshQuietly,
                            onRefreshHeroes = vm::refreshHeroLibrary,
                            acceptEnabled = !state.busy && mascot?.status == "AWAITING_ACCEPTANCE",
                        )
                    }
                    "animations" -> AnimationLibraryScreen(
                        mascotId = state.mascot?.id,
                        animations = state.animationLibrary,
                        videoUrls = state.mascot?.id
                            ?.let { HeroLocalStore.current?.actionVideoUrls(it) }
                            .orEmpty(),
                        legacyVideoUrl = state.mascot?.id
                            ?.let { HeroLocalStore.current?.performanceVideoFile(it)?.toURI()?.toString() },
                        neutralFrameUrl = state.mascot?.id
                            ?.let { HeroLocalStore.current?.neutralVideoFrameFile(it)?.toURI()?.toString() },
                        heroName = state.mascot?.name,
                        packReady = state.animationPackReady,
                        packBusy = state.creating || state.packBusy,
                        packStatus = state.mascot?.stages?.get("animations"),
                        packMessage = state.packMessage,
                        editableName = state.name,
                        onNameChange = vm::onName,
                        onSaveName = vm::saveNameInPlace,
                        onBack = vm::closeAnimationLibrary,
                        onRefresh = vm::refreshAnimationLibrary,
                        onComplete = vm::completeAnimations,
                    )
                    "settings" -> SettingsScreen(state.name, vm::onName, vm::saveName, vm::deleteAccount)
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("widget_interact", false) == true) {
            vm.onOpenedFromWidget()
        }
    }
}
