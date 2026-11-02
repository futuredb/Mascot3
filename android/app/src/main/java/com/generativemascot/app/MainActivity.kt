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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import com.generativemascot.app.overlay.OverlayPermissionIntro
import com.generativemascot.app.overlay.OverlayPermissionsDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.data.GenerationRoute
import com.generativemascot.app.ui.AppViewModel
import com.generativemascot.app.ui.AnimationLibraryScreen
import com.generativemascot.app.ui.PersonalityGenerationScene
import com.generativemascot.app.ui.CharacterTypeScreen
import com.generativemascot.app.ui.KnockHeroScreen
import com.generativemascot.app.ui.MascotChatScreen
import com.generativemascot.app.ui.GenerationScene
import com.generativemascot.app.ui.MascotTheme
import com.generativemascot.app.ui.SettingsScreen
import com.generativemascot.app.ui.ServerIssue
import com.generativemascot.app.ui.stateTitle
import com.generativemascot.app.ui.MascotTransition
import com.generativemascot.app.ui.FigmaCanvas
import com.generativemascot.app.ui.heroFlowContentKey
import com.generativemascot.app.ui.showsHeroHome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels {
        val app = application as MascotApp
        AppViewModel.factory(app.api, app.session, app.heroStore, app.openRouterSettings)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.observeBehavior()
        handleWidgetIntent(intent)
        setContent {
            MascotTheme {
                var showPermissions by rememberSaveable { mutableStateOf(OverlayPermissionIntro.needed(this)) }
                if (showPermissions) OverlayPermissionsDialog(this, onDismiss = {
                    OverlayPermissionIntro.seen(this)
                    showPermissions = false
                })
                val liveState by vm.state.collectAsState()
                var showChat by rememberSaveable { mutableStateOf(false) }
                var showCharacterType by rememberSaveable { mutableStateOf(false) }
                val transitionScope = rememberCoroutineScope()
                BackHandler(showChat) { showChat = false }
                BackHandler(liveState.route == "animations") {
                    vm.closeAnimationLibrary()
                }
                BackHandler(liveState.route == "settings") {
                    vm.backToHero()
                }
                BackHandler(liveState.route == "create" && !liveState.creating) {
                    vm.cancelHeroCreation()
                }
                // Creation is paid and must not be interrupted by navigating back.
                // Once it finishes or fails, back returns to the previous hero.
                BackHandler(liveState.newCreationFlow && !liveState.showsHeroHome) {
                    if (!liveState.creating) vm.cancelHeroCreation()
                }
                MascotTransition(
                    target = liveState,
                    modifier = Modifier.fillMaxSize().background(FigmaCanvas),
                    contentKey = ::heroFlowContentKey,
                ) { state ->
                if (!state.ready) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else when (state.route) {
                    "knock", "onboarding", "create", "progress", "meet", "hero" -> {
                        val mascot = state.mascot
                        if (!state.showsHeroHome) {
                            if (state.newCreationFlow) PersonalityGenerationScene(
                                generating = state.creating,
                                previewUrl = mascot?.previewUrl,
                                heroName = mascot?.name,
                                nameDraft = state.name,
                                error = state.error,
                                acceptEnabled = !state.busy && !state.creating && mascot?.status == "AWAITING_ACCEPTANCE",
                                onGenerate = vm::createHero,
                                onRegenerate = vm::replaceHero,
                                onAccept = vm::accept,
                                onNameChange = vm::onName,
                                onSaveName = vm::saveNameInPlace,
                                onCancel = vm::cancelHeroCreation,
                            ) else GenerationScene(
                                generating = state.creating,
                                previewUrl = mascot?.previewUrl,
                                error = state.error,
                                heroName = mascot?.name,
                                greetingStage = state.greetingPending,
                                generationLabel = state.generationLabel,
                                onKnockComplete = vm::createHero,
                                onAccept = vm::accept,
                                onGenerate = vm::replaceHero,
                                onPoll = vm::pollProgress,
                                onDeferGreeting = vm::deferGreeting,
                                acceptEnabled = !state.busy && !state.creating &&
                                    (mascot?.status == "AWAITING_ACCEPTANCE" || state.greetingPending),
                            )
                        } else {
                        Box(Modifier.fillMaxSize()) {
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
                            error = state.error ?: state.serverMessage?.takeUnless { it == ServerIssue.UNREACHABLE.message },
                            accepted = mascot?.status == "READY",
                            heroLoading = state.greetingPending && state.creating,
                            greetingInterrupted = state.greetingPending && !state.creating && !state.busy,
                            onResumeGreeting = vm::accept,
                            onDeferGreeting = vm::deferGreeting,
                            mascotId = mascot?.id,
                            heroName = mascot?.name,
                            welcomeRequestId = state.welcomeRequestId,
                            onConsumeWelcome = vm::consumeWelcome,
                            heroLibrary = state.heroLibrary,
                            stateKey = state.context?.stateKey,
                            stateLabel = if (mascot?.status == "READY") "Нажми, погладь или потяни" else null,
                            generationLabel = state.generationLabel,
                            generationSourceLabel = if (state.generationRoute == GenerationRoute.OPENROUTER_DIRECT) {
                                "свой OpenRouter"
                            } else {
                                "командный сервер"
                            },
                            onKnockComplete = vm::createHero,
                            onAccept = vm::accept,
                            onSelectHero = vm::selectLibraryHero,
                            onNewHero = { showCharacterType = true },
                            onAnimations = vm::openAnimationLibrary,
                            onChat = { showChat = true },
                            screenActive = !showChat && !showCharacterType,
                            onGenerate = vm::replaceHero,
                            onPoll = vm::pollProgress,
                            onRefresh = vm::refreshQuietly,
                            onRefreshHeroes = vm::refreshHeroLibrary,
                            onGenerationSettings = vm::openSettings,
                            onOverlaySettings = {
                                startActivity(Intent(this@MainActivity, com.generativemascot.app.overlay.OverlaySettingsActivity::class.java)
                                    .putExtra(com.generativemascot.app.overlay.MascotOverlayService.EXTRA_HERO, mascot?.id))
                            },
                            acceptEnabled = !state.busy && mascot?.status == "AWAITING_ACCEPTANCE",
                            onStateGesture = { (application as MascotApp).behavior.gesture(it) },
                            onAnimationVisible = { (application as MascotApp).behavior.observed("app", it) },
                            onOneShotFinished = { (application as MascotApp).behavior.clipFinished(it) },
                        )
                        if (showCharacterType) Box(Modifier.fillMaxSize().zIndex(2f)) {
                            CharacterTypeScreen(onClose = { if (!vm.state.value.creating) showCharacterType = false }, onFinish = { choice ->
                                vm.startHeroCreation(choice).also { started ->
                                    // Keep the selector covering the outgoing home until
                                    // its crossfade has completed; never reveal home in between.
                                    if (started) transitionScope.launch { delay(250); showCharacterType = false }
                                }
                            }, creationInProgress = liveState.creating)
                        }
                        if (mascot != null) Box(Modifier.fillMaxSize().zIndex(if (showChat) 1f else -1f)
                            .then(if (showChat) Modifier else Modifier.clearAndSetSemantics { })) {
                        MascotChatScreen(
                            mascotId = mascot.id,
                            heroName = mascot.name ?: "Маскот",
                            previewUrl = state.context?.stillUrl ?: mascot.previewUrl,
                            animationVideoUrl = state.context?.animationUrl,
                            animationFrames = state.context?.animationFrames.orEmpty(),
                            animationFps = state.context?.animationFps ?: HeroLocalStore.FULL_FRAME_FPS,
                            animationVideoUrls = HeroLocalStore.current?.actionVideoUrls(mascot.id).orEmpty(),
                            neutralFrameUrl = HeroLocalStore.current?.neutralVideoFrameFile(mascot.id)?.toURI()?.toString(),
                            active = showChat,
                            onBack = { showChat = false },
                        )
                        }
                        }
                        }
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
                        generationRoute = state.generationRoute,
                        packReady = state.animationPackReady,
                        packBusy = state.creating || state.packBusy,
                        packStatus = if (state.greetingPending) {
                            if (state.creating) "running" else "failed"
                        } else state.mascot?.stages?.get("animations"),
                        packMessage = if (state.greetingPending) state.error ?: state.generationLabel else state.packMessage,
                        generationError = state.error,
                        batchActions = state.mascot?.animationBatchActions?.ifEmpty { state.animationBatchActions }
                            ?: state.animationBatchActions,
                        batchId = state.mascot?.animationBatchId ?: state.animationBatchId,
                        editableName = state.name,
                        onNameChange = vm::onName,
                        onSaveName = vm::saveNameInPlace,
                        onBack = vm::closeAnimationLibrary,
                        onRefresh = vm::refreshAnimationLibrary,
                        onComplete = vm::completeAnimations,
                        greetingRequiresResume = state.greetingPending && !state.creating && !state.busy,
                        onResumeGreeting = vm::accept,
                    )
                    "settings" -> SettingsScreen(
                        generationRoute = state.generationRoute,
                        openRouterKeyPresent = state.openRouterKeyPresent,
                        openRouterKeyDraft = state.openRouterKeyDraft,
                        openRouterChecking = state.openRouterChecking,
                        openRouterMessage = state.openRouterMessage,
                        generationLocked = state.creating || state.packBusy,
                        onOpenRouterKey = vm::onOpenRouterKey,
                        onUseTeamServer = vm::useTeamServer,
                        onUseOpenRouter = vm::saveAndUseOpenRouterKey,
                        onBack = vm::backToHero,
                        onBehaviorSettings = { startActivity(Intent(this, com.generativemascot.app.state.BehaviorSettingsActivity::class.java)) },
                    )
                }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onAppForegrounded()
    }

    override fun onStop() {
        // A rotation is not leaving and re-entering the app.
        if (!isChangingConfigurations) vm.onAppBackgrounded()
        super.onStop()
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
