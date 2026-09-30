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
                val sample by produceState<Result<Sample>?>(null) {
                    value = withContext(Dispatchers.IO) { runCatching { loadSample(this@FlowDemoActivity) } }
                }
                val media = sample?.getOrNull()
                if (media == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (sample?.isFailure == true) "Не удалось открыть локального героя" else "Открываем демо…")
                    }
                } else DemoFlow(media)
            }
        }
    }
}

private const val SAMPLE_ID = "716cdb43-703b-4d7e-8a79-27f03d92b99c"
private data class Sample(val base: String, val neutral: String, val videos: Map<String, String>)

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
    return Sample(local("base.png"), local("videos/neutral.png"),
        ANIMATION_STATE_KEYS.associateWith { local("videos/$it.mp4") })
}

@Composable
private fun DemoFlow(sample: Sample) {
    var stage by rememberSaveable { mutableStateOf("knock") }
    var greetingPending by rememberSaveable { mutableStateOf(false) }
    var gallery by rememberSaveable { mutableStateOf(false) }
    var heroName by rememberSaveable { mutableStateOf("Уна") }
    var editableName by rememberSaveable { mutableStateOf("Уна") }
    var readyCsv by rememberSaveable { mutableStateOf("") }
    var batchCsv by rememberSaveable { mutableStateOf("") }
    var batchBusy by rememberSaveable { mutableStateOf(false) }
    var batchSerial by rememberSaveable { mutableIntStateOf(0) }
    var welcomeSerial by rememberSaveable { mutableLongStateOf(0L) }
    var consumedWelcome by rememberSaveable { mutableLongStateOf(0L) }
    val ready = readyCsv.split(',').filter { it.isNotBlank() }.toSet()
    val batch = batchCsv.split(',').filter { it.isNotBlank() }
    val videos = sample.videos.filterKeys { it in ready }

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
        if (gallery) gallery = false else restart()
    }

    Box(Modifier.fillMaxSize()) {
        when {
            gallery -> AnimationLibraryScreen(
                mascotId = "flow-demo-$SAMPLE_ID", animations = emptyMap(), videoUrls = videos,
                legacyVideoUrl = null, neutralFrameUrl = sample.neutral, heroName = heroName,
                generationRoute = GenerationRoute.OPENROUTER_DIRECT,
                packReady = ready.size == ANIMATION_STATE_KEYS.size, packBusy = batchBusy || greetingPending,
                packStatus = if (batchBusy || greetingPending) "running" else null,
                packMessage = if (greetingPending) "Готовим приветствие — одну анимацию…"
                    else if (batchBusy) "Демо: открываем готовые анимации, без запросов к моделям" else null,
                generationError = null, batchActions = if (greetingPending) listOf("greeting") else batch,
                batchId = if (greetingPending) "demo-greeting" else if (batch.isEmpty()) null else "demo-$batchSerial",
                editableName = editableName, onNameChange = { editableName = it },
                onSaveName = { heroName = editableName.trim().ifBlank { "Уна" } },
                onBack = { gallery = false }, onRefresh = {},
                onComplete = { count ->
                    if (!batchBusy && !greetingPending) {
                        batchCsv = ANIMATION_STATE_KEYS.filter { it !in ready }.take(count).joinToString(",")
                        batchSerial++
                        batchBusy = batchCsv.isNotBlank()
                    }
                },
            )
            stage == "home" -> KnockHeroScreen(
                generating = false, previewUrl = sample.base, neutralVideoFrameUrl = sample.neutral,
                animationVideoUrls = videos, error = null, accepted = true,
                heroLoading = greetingPending,
                mascotId = "flow-demo-$SAMPLE_ID", heroName = heroName,
                welcomeRequestId = welcomeSerial.takeIf { it > 0L && !greetingPending },
                onConsumeWelcome = { request ->
                    if (request <= consumedWelcome) false else { consumedWelcome = request; true }
                },
                heroLibrary = listOf(HeroLibraryItem("flow-demo-$SAMPLE_ID", heroName, baseStill = sample.base, active = true)),
                generationSourceLabel = "Демо · без расходов",
                onKnockComplete = {}, onAccept = {}, onPoll = {}, acceptEnabled = false,
                onNewHero = { restart() }, onAnimations = { gallery = true },
            )
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
        TextButton(
            onClick = { restart() },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 2.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF785A77)),
        ) { Text("ДЕМО · 0 ₽ · начать заново", fontSize = 11.sp) }
    }
}
