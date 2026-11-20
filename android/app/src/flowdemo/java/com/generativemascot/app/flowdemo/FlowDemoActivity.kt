package com.generativemascot.app.flowdemo

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.generativemascot.app.data.GenerationRoute
import com.generativemascot.app.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Only local media + timer-driven UI state. No production Application/ViewModel/API/Worker. */
class FlowDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MascotTheme {
                if (intent.getStringExtra("demoStage") == "launch") {
                    MascotLaunchScreen()
                } else {
                val sample by produceState<Result<Sample>?>(null) {
                    value = withContext(Dispatchers.IO) { runCatching { loadSample(this@FlowDemoActivity) } }
                }
                val media = sample?.getOrNull()
                if (media == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (sample?.isFailure == true) "Не удалось открыть локального героя" else "Открываем демо…")
                    }
                } else DemoFlow(media, intent.getStringExtra("demoStage"))
                }
            }
        }
    }
}

private const val SAMPLE_ID = "716cdb43-703b-4d7e-8a79-27f03d92b99c"
private data class Sample(val base: String, val neutral: String, val videos: Map<String, String>, val pickerBases: List<String>)

private fun loadSample(context: Context): Sample {
    val prefix = "bundled-heroes/$SAMPLE_ID"
    val directory = File(context.filesDir, "flow-demo-sample").apply { mkdirs() }
    fun local(relative: String): String {
        val file = File(directory, relative).apply { parentFile?.mkdirs() }
        if (!file.isFile || file.length() == 0L) {
            context.assets.open("$prefix/$relative").use { source ->
                file.outputStream().use { source.copyTo(it) }
            }
        }
        return file.toURI().toString()
    }
    fun pickerBase(id: String): String {
        val file = File(directory, "picker-$id.png")
        if (!file.isFile || file.length() == 0L) context.assets.open("bundled-heroes/$id/base.png").use { source ->
            file.outputStream().use { source.copyTo(it) }
        }
        return file.toURI().toString()
    }
    return Sample(local("base.png"), local("videos/neutral.png"),
        ANIMATION_STATE_KEYS.associateWith { local("videos/$it.mp4") }, listOf(
            pickerBase("9f226b89-df35-4d3a-9b36-96c28ea1bb62"),
            pickerBase("a83e9d30-1270-40e6-902c-b44774e84c17"),
        ))
}

@Composable
private fun DemoFlow(sample: Sample, startStage: String? = null) {
    var confirmDelete by remember { mutableStateOf(false) }
    var stage by rememberSaveable { mutableStateOf(when (startStage) { "home", "picker", "greeting_error", "animations", "chat_preview" -> "home"; "waiting" -> "waiting"; else -> "knock" }) }
    var waitingInBackground by rememberSaveable { mutableStateOf(false) }
    var greetingPending by rememberSaveable { mutableStateOf(false) }
    var demoGreetingInterrupted by rememberSaveable { mutableStateOf(startStage == "greeting_error") }
    var gallery by rememberSaveable { mutableStateOf(startStage == "animations") }
    var openCapabilities by rememberSaveable { mutableStateOf(false) }
    var openBehavior by rememberSaveable { mutableStateOf(false) }
    var heroName by rememberSaveable { mutableStateOf("Уна") }
    var activeDemoId by rememberSaveable { mutableStateOf("flow-demo-v2-$SAMPLE_ID") }
    var editableName by rememberSaveable { mutableStateOf("Уна") }
    var readyCsv by rememberSaveable { mutableStateOf(when (startStage) { "animations" -> "idle,joyful,greeting,dancing"; "chat_preview" -> "greeting"; else -> "" }) }
    var batchCsv by rememberSaveable { mutableStateOf("") }
    var batchBusy by rememberSaveable { mutableStateOf(false) }
    var batchSerial by rememberSaveable { mutableIntStateOf(0) }
    var welcomeSerial by rememberSaveable { mutableLongStateOf(0L) }
    var consumedWelcome by rememberSaveable { mutableLongStateOf(0L) }
    val ready = readyCsv.split(',').filter { it.isNotBlank() }.toSet()
    val batch = batchCsv.split(',').filter { it.isNotBlank() }
    val videos = sample.videos.filterKeys { it in ready }
    val demoHeroes = if (startStage == "picker") listOf(
        HeroLibraryItem("flow-demo-v2-$SAMPLE_ID", heroName, baseStill = sample.base),
        HeroLibraryItem("flow-demo-picker-a", "Аким", baseStill = sample.pickerBases[0]),
        HeroLibraryItem("flow-demo-picker-b", "Марс", baseStill = sample.pickerBases[1]),
    ) else listOf(HeroLibraryItem("flow-demo-v2-$SAMPLE_ID", heroName, baseStill = sample.base))
    val activeDemoHero = demoHeroes.firstOrNull { it.id == activeDemoId } ?: demoHeroes.first()

    fun restart() {
        stage = "knock"
        greetingPending = false
        gallery = false
        readyCsv = ""
        batchCsv = ""
        batchBusy = false
        batchSerial++
    }

    LaunchedEffect(stage) {
        when (stage) {
            "base" -> { delay(5_000); stage = "approval" }
        }
    }
    LaunchedEffect(greetingPending) {
        if (greetingPending) {
            delay(5_000)
            readyCsv = "greeting"
            welcomeSerial++
            greetingPending = false
        }
    }
    LaunchedEffect(batchBusy, batchSerial) {
        if (batchBusy) {
            for (action in batch) {
                if (action !in readyCsv.split(',')) {
                    delay(2_000)
                    readyCsv = (readyCsv.split(',').filter { it.isNotBlank() } + action).distinct().joinToString(",")
                }
            }
            batchBusy = false
        }
    }
    BackHandler(enabled = gallery || stage != "knock") {
        if (gallery) gallery = false else if (stage == "chat") stage = "home"
        else if (stage == "waiting") { waitingInBackground = true; stage = "home" } else restart()
    }

    if (confirmDelete) MascotConfirmationSheet(
        title = "Удалить персонажа?",
        message = "Персонаж, его анимации и история чата будут удалены с устройства. Это действие нельзя отменить.",
        confirmLabel = "Удалить персонажа", destructive = true,
        onConfirm = { confirmDelete = false; gallery = false; stage = "deleted" },
        onDismiss = { confirmDelete = false },
    )
    Box(Modifier.fillMaxSize()) {
        when {
            stage == "waiting" -> PersonalityGenerationScene(
                generating = true, previewUrl = null, heroName = null, nameDraft = "", error = null,
                acceptEnabled = false, onGenerate = {}, onRegenerate = {}, onAccept = {}, onNameChange = {},
                onSaveName = {}, onCancel = {}, onBackground = { waitingInBackground = true; stage = "home" },
            )
            stage == "deleted" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Демо-персонаж удалён")
            }
            stage == "create" -> CharacterTypeScreen(onClose = { stage = "home" }, onFinish = { false })
            gallery -> AnimationLibraryScreen(
                mascotId = activeDemoHero.id, animations = emptyMap(), videoUrls = videos,
                legacyVideoUrl = null, neutralFrameUrl = sample.neutral, heroName = activeDemoHero.name,
                generationRoute = GenerationRoute.OPENROUTER_DIRECT,
                packReady = ready.size == ANIMATION_STATE_KEYS.size, packBusy = batchBusy || greetingPending,
                packStatus = if (batchBusy || greetingPending) "running" else null,
                packMessage = if (greetingPending) "Готовим приветствие — одну анимацию…"
                    else if (batchBusy) "Демо: открываем готовые анимации, без запросов к моделям" else null,
                generationError = null, batchActions = if (greetingPending) listOf("greeting") else batch,
                batchId = if (greetingPending) "demo-greeting" else if (batch.isEmpty()) null else "demo-$batchSerial",
                onDelete = { confirmDelete = true },
                onBack = { gallery = false }, onRefresh = {},
                onComplete = { count ->
                    if (!batchBusy && !greetingPending) {
                        batchCsv = ANIMATION_STATE_KEYS.filter { it !in ready }.take(count).joinToString(",")
                        batchSerial++
                        batchBusy = batchCsv.isNotBlank()
                    }
                },
            )
            stage == "home" || stage == "chat" -> Box(Modifier.fillMaxSize()) {
            KnockHeroScreen(
                generating = false, previewUrl = activeDemoHero.baseStill, neutralVideoFrameUrl = sample.neutral,
                animationVideoUrls = videos, error = if (demoGreetingInterrupted) "Приветствие создано, но не прошло проверку: начало и конец анимации заметно различаются. Исходное видео сохранено. Повторный платный запрос не отправлялся." else null, accepted = true,
                heroLoading = greetingPending,
                greetingInterrupted = demoGreetingInterrupted, greetingCanResume = false,
                onDeferGreeting = { demoGreetingInterrupted = false },
                mascotId = activeDemoHero.id, heroName = activeDemoHero.name,
                welcomeRequestId = welcomeSerial.takeIf { it > 0L && !greetingPending },
                onConsumeWelcome = { request ->
                    if (request <= consumedWelcome) false else { consumedWelcome = request; true }
                },
                heroLibrary = demoHeroes.map { it.copy(active = it.id == activeDemoHero.id) },
                generationSourceLabel = "Демо · без расходов",
                onKnockComplete = {}, onAccept = {}, onPoll = {}, acceptEnabled = false,
                onRenameHero = { _, name -> heroName = name },
                onDeleteHero = { confirmDelete = true },
                onNewHero = { stage = "create" }, onSelectHero = { activeDemoId = it },
                onAnimations = { id -> if (id != null) activeDemoId = id; gallery = true },
                onChat = { stage = "chat" },
                onCapabilities = { openCapabilities = true; stage = "chat" },
                onBehavior = { openBehavior = true; stage = "chat" },
                screenActive = stage == "home",
            )
            if (waitingInBackground && stage == "home") Button(
                onClick = { waitingInBackground = false; stage = "waiting" },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xff222222)),
            ) { Text("Создаём персонажа…", fontSize = 12.sp) }
            ChatOverlayTransition(visible = stage == "chat") { chatActive ->
                MascotChatScreen(
                    mascotId = "flow-demo-v2-$SAMPLE_ID", heroName = heroName, previewUrl = sample.base,
                    animationVideoUrl = null, animationFrames = emptyList(), animationFps = 12,
                    animationVideoUrls = videos, neutralFrameUrl = sample.neutral, active = chatActive,
                    openCapabilities = openCapabilities, onCapabilitiesOpened = { openCapabilities = false },
                    openBehavior = openBehavior, onBehaviorOpened = { openBehavior = false },
                    onBack = { stage = "home" },
                )
            }
            }
            else -> GenerationScene(
                generating = stage == "base",
                previewUrl = if (stage == "approval") sample.base else null,
                error = null, heroName = heroName,
                onKnockComplete = { if (stage == "knock") stage = "base" },
                onAccept = {
                    if (stage == "approval") {
                        greetingPending = true
                        stage = "home"
                    }
                },
                onGenerate = { if (stage == "approval") stage = "base" },
                onPoll = {}, acceptEnabled = stage == "approval",
                generationLabel = when (stage) {
                    "base" -> "Создаём внешность героя…"
                    else -> null
                },
            )
        }
        // Always make the offline nature clear; also offers a one-tap replay of the flow.
        if (startStage != "home" && startStage != "picker" && startStage != "greeting_error" && startStage != "waiting") TextButton(
            onClick = { restart() },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 2.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF785A77)),
        ) { Text("ДЕМО · 0 ₽ · начать заново", fontSize = 11.sp) }
    }
}
