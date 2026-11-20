package com.generativemascot.app

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import com.generativemascot.app.overlay.OverlayPermissionIntro
import com.generativemascot.app.overlay.OverlayPermissionsDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.data.GenerationRoute
import com.generativemascot.app.data.MascotDto
import com.generativemascot.app.ui.AppViewModel
import com.generativemascot.app.ui.AnimationLibraryScreen
import com.generativemascot.app.ui.PersonalityGenerationScene
import com.generativemascot.app.ui.CharacterCreationFlow
import com.generativemascot.app.ui.KnockHeroScreen
import com.generativemascot.app.ui.MascotLaunchScreen
import com.generativemascot.app.ui.MascotConfirmationSheet
import com.generativemascot.app.ui.MascotChatScreen
import com.generativemascot.app.ui.ChatOverlayTransition
import com.generativemascot.app.ui.GenerationScene
import com.generativemascot.app.ui.MascotTheme
import com.generativemascot.app.ui.SettingsScreen
import com.generativemascot.app.ui.ServerIssue
import com.generativemascot.app.ui.stateTitle
import com.generativemascot.app.ui.MascotTransition
import com.generativemascot.app.ui.FigmaCanvas
import com.generativemascot.app.ui.heroFlowContentKey
import com.generativemascot.app.ui.showsHeroHome
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels {
        val app = application as MascotApp
        AppViewModel.factory(app.api, app.session, app.heroStore, app.openRouterSettings)
    }

    private var pendingOverlayChat by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.observeBehavior()
        handleWidgetIntent(intent)
        setContent {
            MascotTheme {
                var showPermissions by rememberSaveable { mutableStateOf(OverlayPermissionIntro.needed(this)) }
                val liveState by vm.state.collectAsState()
                val cachedHero by produceState<MascotDto?>(null, liveState.ready) {
                    if (!liveState.ready) {
                        val app = application as MascotApp
                        value = try {
                            withContext(Dispatchers.IO) {
                                val id = app.session.acceptedMascotId() ?: return@withContext null
                                val portrait = app.heroStore.baseFile(id) ?: return@withContext null
                                MascotDto(id = id, name = app.heroStore.mascotName(id), status = "READY",
                                    previewUrl = portrait.toURI().toString())
                            }
                        } catch (_: IOException) {
                            null
                        }
                    }
                }
                var showLaunch by rememberSaveable { mutableStateOf(savedInstanceState == null) }
                LaunchedEffect(Unit) {
                    if (showLaunch) { delay(750); showLaunch = false }
                }
                var showChat by rememberSaveable { mutableStateOf(false) }
                var openChatCapabilities by rememberSaveable { mutableStateOf(false) }
                var openChatBehavior by rememberSaveable { mutableStateOf(false) }
                var deleteHeroId by rememberSaveable { mutableStateOf<String?>(null) }
                val launchVisible = showLaunch || !liveState.ready
                val chatAvailable = !launchVisible && heroFlowContentKey(liveState) == "home"
                val chatHero = if (!liveState.ready) {
                    cachedHero?.takeIf { pendingOverlayChat == null || pendingOverlayChat == it.id }
                } else liveState.mascot?.takeIf { liveState.showsHeroHome }
                Box(Modifier.fillMaxSize()) {
                if (!launchVisible) {
                if (showPermissions) OverlayPermissionsDialog(this@MainActivity, onDismiss = {
                    OverlayPermissionIntro.seen(this@MainActivity)
                    showPermissions = false
                }, compact = true)
                LaunchedEffect(liveState.mascot?.id) {
                    if (deleteHeroId != null && deleteHeroId != liveState.mascot?.id) deleteHeroId = null
                }
                fun requestDeleteHero(id: String?) {
                    if (id != null) { vm.clearDeleteHeroError(); deleteHeroId = id }
                }
                var showCharacterType by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(pendingOverlayChat, liveState) {
                    val id = pendingOverlayChat ?: return@LaunchedEffect
                    when (overlayChatNavigation(liveState, id)) {
                        OverlayChatNavigation.WAIT -> Unit
                        OverlayChatNavigation.SELECT -> vm.selectLibraryHero(id)
                        OverlayChatNavigation.OPEN -> {
                            if (liveState.route !in setOf("knock", "onboarding", "create", "progress", "meet", "hero")) vm.backToHero()
                            showCharacterType = false
                            showChat = true
                            pendingOverlayChat = null
                        }
                    }
                }
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
                    if (liveState.creating) vm.deferHeroCreation { moveTaskToBack(true) }
                    else vm.cancelHeroCreation()
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
                                onBackground = { vm.deferHeroCreation { moveTaskToBack(true) } },
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
                            greetingCanResume = state.greetingCanResume,
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
                            onDeleteHero = { requestDeleteHero(mascot?.id) },
                            onRenameHero = vm::renameHero,
                            onNewHero = {
                                if (state.greetingPending) Toast.makeText(this@MainActivity, "Дождитесь завершения приветствия", Toast.LENGTH_SHORT).show()
                                else if (state.backgroundCreation) Toast.makeText(this@MainActivity, "Сначала откройте нового персонажа", Toast.LENGTH_SHORT).show()
                                else showCharacterType = true
                            },
                            onAnimations = { selectedId ->
                                if (state.backgroundCreation && state.creating) Toast.makeText(this@MainActivity, "Дождитесь завершения создания персонажа", Toast.LENGTH_SHORT).show()
                                else if (selectedId != null && selectedId != mascot?.id && (state.creating || state.packBusy))
                                    Toast.makeText(this@MainActivity, "Дождитесь завершения создания анимаций", Toast.LENGTH_SHORT).show()
                                else vm.openAnimationLibraryForHero(selectedId)
                            },
                            onChat = { showChat = true },
                            onCapabilities = { openChatCapabilities = true; showChat = true },
                            onBehavior = { openChatBehavior = true; showChat = true },
                            screenActive = !showChat && !showCharacterType,
                            onGenerate = vm::replaceHero,
                            onPoll = vm::pollProgress,
                            onRefresh = vm::refreshQuietly,
                            onRefreshHeroes = vm::refreshHeroLibrary,
                            onGenerationSettings = vm::openSettings,
                            onApiKeys = {
                                startActivity(Intent(this@MainActivity, com.generativemascot.app.settings.ApiKeysActivity::class.java))
                            },
                            onExportHeroes = {
                                startActivity(Intent(this@MainActivity, com.generativemascot.app.settings.HeroExportActivity::class.java))
                            },
                            onOverlaySettings = {
                                startActivity(Intent(this@MainActivity, com.generativemascot.app.overlay.OverlaySettingsActivity::class.java)
                                    .putExtra(com.generativemascot.app.overlay.MascotOverlayService.EXTRA_HERO, mascot?.id))
                            },
                            onPinWidget = {
                                lifecycleScope.launch {
                                    com.generativemascot.app.widget.requestPinMascotWidget(this@MainActivity, mascot?.id)
                                }
                            },
                            acceptEnabled = !state.busy && mascot?.status == "AWAITING_ACCEPTANCE",
                            onStateGesture = { (application as MascotApp).behavior.gesture(it) },
                            onAnimationVisible = { (application as MascotApp).behavior.observed("app", it) },
                            onOneShotFinished = { (application as MascotApp).behavior.clipFinished(it) },
                        )
                        if (state.backgroundCreation) Button(
                            onClick = vm::showBackgroundCreation,
                            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp).zIndex(.5f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xff222222).copy(alpha = .88f)),
                        ) {
                            Text(when {
                                state.creating -> "Создаём персонажа…"
                                state.pendingCreationError != null -> "Не удалось создать · открыть"
                                else -> "Персонаж готов · открыть"
                            }, fontSize = 12.sp, color = Color.White)
                        }
                        if (showCharacterType) Box(Modifier.fillMaxSize().zIndex(2f)) {
                            CharacterCreationFlow(onClose = { if (!vm.state.value.creating) showCharacterType = false }, onFinish = { choice ->
                                vm.startHeroCreation(choice).also { started ->
                                    // Keep the selector covering the outgoing home until
                                    // its crossfade has completed; never reveal home in between.
                                    if (started) transitionScope.launch { delay(250); showCharacterType = false }
                                }
                            }, creationInProgress = liveState.creating, onCreationFailure = vm::heroCreationFailureMessage)
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
                        onDelete = { requestDeleteHero(state.mascot?.id) },
                        onBack = vm::closeAnimationLibrary,
                        onRefresh = vm::refreshAnimationLibrary,
                        onComplete = vm::completeAnimations,
                        onCompleteChat = vm::completeChatAnimations,
                        onGenerateAction = vm::generateSingleAnimation,
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
                        onBehaviorSettings = { startActivity(Intent(this@MainActivity, com.generativemascot.app.state.BehaviorSettingsActivity::class.java)) },
                    )
                }
                }
                }
                // Keep one chat and WebView per selected hero across the splash and home transitions.
                if (chatHero != null) key(chatHero.id) {
                    val heroContext = liveState.context.takeIf { liveState.mascot?.id == chatHero.id }
                    ChatOverlayTransition(visible = chatAvailable && showChat) { chatVisible ->
                        MascotChatScreen(
                            mascotId = chatHero.id,
                            heroName = chatHero.name ?: "Маскот",
                            previewUrl = heroContext?.stillUrl ?: chatHero.previewUrl,
                            animationVideoUrl = heroContext?.animationUrl,
                            animationFrames = heroContext?.animationFrames.orEmpty(),
                            animationFps = heroContext?.animationFps ?: HeroLocalStore.FULL_FRAME_FPS,
                            animationVideoUrls = HeroLocalStore.current?.actionVideoUrls(chatHero.id).orEmpty(),
                            neutralFrameUrl = HeroLocalStore.current?.neutralVideoFrameFile(chatHero.id)?.toURI()?.toString(),
                            active = chatAvailable,
                            portraitVisible = chatAvailable && chatVisible,
                            openCapabilities = openChatCapabilities,
                            onCapabilitiesOpened = { openChatCapabilities = false },
                            openBehavior = openChatBehavior,
                            onBehaviorOpened = { openChatBehavior = false },
                            onBack = { showChat = false },
                        )
                    }
                }
                if (launchVisible) Box(Modifier.fillMaxSize().zIndex(2f)) {
                    MascotLaunchScreen()
                }
                val deletingId = deleteHeroId
                if (!launchVisible && deletingId != null && liveState.mascot?.id == deletingId) {
                    val locked = liveState.creating || liveState.packBusy || liveState.greetingPending || liveState.backgroundCreation
                    MascotConfirmationSheet(
                        title = "Удалить персонажа?",
                        message = "Персонаж, его анимации и история чата будут удалены с устройства. Это действие нельзя отменить.",
                        confirmLabel = "Удалить персонажа", destructive = true,
                        busy = liveState.deletingHeroId == deletingId, enabled = !locked,
                        error = liveState.deleteHeroError ?: if (liveState.backgroundCreation && !liveState.creating)
                            "Сначала откройте нового персонажа." else if (locked) "Дождитесь завершения создания персонажа." else null,
                        onConfirm = { vm.deleteHero(deletingId) },
                        onDismiss = { deleteHeroId = null; vm.clearDeleteHeroError() },
                    )
                }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.generativemascot.app.overlay.MascotOverlayService.setAppVisible(true)
        vm.onAppForegrounded()
        com.generativemascot.app.widget.WidgetDepthService.startIfPresent(this)
    }

    override fun onStop() {
        // A rotation is not leaving and re-entering the app.
        if (!isChangingConfigurations) {
            com.generativemascot.app.overlay.MascotOverlayService.setAppVisible(false)
            vm.onAppBackgrounded()
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
        val chatHero = intent?.getStringExtra(EXTRA_OVERLAY_CHAT_HERO)
        if (chatHero != null && chatHero == com.generativemascot.app.overlay.OverlaySession.hero(this)) {
            pendingOverlayChat = chatHero
        }
        intent?.removeExtra(EXTRA_OVERLAY_CHAT_HERO)
        if (intent?.getBooleanExtra("widget_interact", false) == true) {
            vm.onOpenedFromWidget()
        }
    }
    companion object {
        internal const val EXTRA_OVERLAY_CHAT_HERO = "overlay_chat_hero"
        internal fun overlayChatIntent(context: android.content.Context, heroId: String) =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OVERLAY_CHAT_HERO, heroId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

}

internal enum class OverlayChatNavigation { WAIT, SELECT, OPEN }

internal fun overlayChatNavigation(state: com.generativemascot.app.ui.AppUiState, heroId: String): OverlayChatNavigation {
    if (!state.ready || state.deletingHeroId != null) return OverlayChatNavigation.WAIT
    if (state.mascot?.id != heroId) {
        return if (state.creating || state.packBusy || state.greetingPending || state.backgroundCreation)
            OverlayChatNavigation.WAIT else OverlayChatNavigation.SELECT
    }
    return if (state.showsHeroHome) OverlayChatNavigation.OPEN else OverlayChatNavigation.WAIT
}
