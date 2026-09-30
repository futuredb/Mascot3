package com.generativemascot.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.generativemascot.app.R
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.data.GenerationRoute
import com.generativemascot.app.data.defaultAnimationBatchSize
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

val ANIMATION_STATE_KEYS = HeroLocalStore.LIBRARY_VIDEO_ACTIONS
val ANIMATION_PACK_SIZE = ANIMATION_STATE_KEYS.size
private val actionDescriptions = mapOf(
    "idle" to "Тихо осматривается и ждёт, когда ты снова окажешься рядом",
    "resting" to "Отпускает напряжение и спокойно отдыхает, оставаясь рядом",
    "thinking" to "Неторопливо ищет ответ и перебирает идеи",
    "at_glass" to "Подходит совсем близко и будто касается стекла",
    "watching" to "Внимательно следит за тем, что происходит вокруг",
    "joyful" to "Искренне радуется твоему вниманию",
    "sleeping" to "Засыпает и спокойно дышит",
    "sad" to "Немного грустит и ждёт твоей поддержки",
    "angry" to "Сердится выразительно, но совсем не страшно",
    "refusal" to "Уверенно показывает, что сейчас не согласен",
    "frightened" to "Пугается неожиданности и осторожно приходит в себя",
    "curious" to "С любопытством изучает что-то новое",
    "tender" to "Делится тихим и очень тёплым моментом",
    "stretching" to "Хорошенько потягивается и снова становится бодрым",
    "greeting" to "Приветствует тебя своим особенным способом",
    "signature_move" to "Показывает фирменный жест, который умеет только он",
    "dancing" to "Пускается в свой самый любимый танец",
)

@Composable
fun AnimationLibraryScreen(
    mascotId: String?,
    animations: Map<String, List<String>>,
    videoUrls: Map<String, String>,
    legacyVideoUrl: String?,
    neutralFrameUrl: String?,
    heroName: String?,
    generationRoute: GenerationRoute,
    packReady: Boolean,
    packBusy: Boolean,
    packStatus: String?,
    packMessage: String?,
    generationError: String?,
    batchActions: List<String>,
    batchId: String?,
    editableName: String,
    onNameChange: (String) -> Unit,
    onSaveName: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onComplete: (Int) -> Unit,
    greetingRequiresResume: Boolean = false,
    onResumeGreeting: () -> Unit = {},
) {
    val displayName = remember(mascotId, heroName) {
        heroHeadingName(mascotId?.let { HeroLocalStore.current?.mascotName(it) }, heroName)
    }
    var confirmPack by remember { mutableStateOf(false) }
    var selectedAction by remember { mutableStateOf<String?>(null) }
    var editingName by rememberSaveable(mascotId) { mutableStateOf(false) }
    val context = LocalContext.current
    val receiptPrefs = remember(context) {
        context.getSharedPreferences("mascot_ui_receipts", Context.MODE_PRIVATE)
    }
    LaunchedEffect(Unit) { onRefresh() }
    val progress = animationPackProgress(videoUrls)
    val playableActions = animationPlayableActions(videoUrls, animations, !legacyVideoUrl.isNullOrBlank())
    val readyCount = progress.readyCount
    var selectedCount by rememberSaveable(mascotId) {
        mutableIntStateOf(defaultAnimationBatchSize(progress.remainingCount))
    }
    LaunchedEffect(progress.remainingCount) {
        selectedCount = if (progress.remainingCount == 0) 0 else selectedCount.coerceIn(1, progress.remainingCount)
        if (progress.remainingCount == 0) confirmPack = false
    }
    val inProgress = !packReady && (packBusy || packStatus == "running")
    val syncing = !packReady && packStatus == "ready"
    val sequential = generationRoute == GenerationRoute.OPENROUTER_DIRECT
    val selectedBatch = batchActions.ifEmpty { if (inProgress || syncing) ANIMATION_STATE_KEYS else emptyList() }
    val batchReadyCount = selectedBatch.count { it in progress.readyActions }
    val batchComplete = selectedBatch.isNotEmpty() && batchReadyCount == selectedBatch.size && !inProgress && !syncing
    val activeAction = progress.activeAction(inProgress, sequential, selectedBatch)
    val interrupted = !packReady && packStatus == "failed"
    val bannerSignature = "$mascotId:$batchId:$packReady:$inProgress:$syncing:$interrupted:$batchComplete"
    val readyReceiptKey = if (packReady) "animations_ready_seen:$mascotId" else "animation_batch_seen:$batchId"
    val readyWasSeen = remember(mascotId, packReady, batchId, batchComplete) {
        (packReady || batchComplete) && mascotId != null && receiptPrefs.getBoolean(readyReceiptKey, false)
    }
    var dismissedBanner by rememberSaveable(mascotId, packReady) {
        mutableStateOf(if (readyWasSeen) bannerSignature else null)
    }
    val showStatusBanner = when {
        packReady -> dismissedBanner != bannerSignature
        inProgress || syncing || interrupted || (batchComplete && !readyWasSeen) -> dismissedBanner != bannerSignature
        else -> false
    }
    LaunchedEffect(showStatusBanner, packReady, batchComplete, bannerSignature) {
        if (showStatusBanner && (packReady || batchComplete) && mascotId != null) {
            // A completed-pack receipt is useful once, immediately after the
            // result arrives. Persist it before the delay so returning to this
            // screen can never show the same message again.
            receiptPrefs.edit().putBoolean(readyReceiptKey, true).apply()
            delay(4_000)
            dismissedBanner = bannerSignature
        }
    }
    LaunchedEffect(inProgress, syncing, packReady) {
        while ((inProgress || syncing) && !packReady) {
            delay(5_000)
            onRefresh()
        }
    }
    BackHandler { if (selectedAction != null) selectedAction = null else onBack() }
    val baseStill = remember(mascotId) { mascotId?.let { HeroLocalStore.current?.baseFile(it)?.toURI()?.toString() } }
    val palette = heroPalette(baseStill ?: neutralFrameUrl)
    MascotSystemBars(if (selectedAction == null) FigmaCanvas else palette.top,
        if (selectedAction == null) FigmaCanvas else palette.end)
    MascotTransition(selectedAction, Modifier.fillMaxSize()) { action ->
        if (action != null) {
            ActionDetail(
                mascotId, action, displayName, animations[action].orEmpty(),
                videoUrls, legacyVideoUrl, neutralFrameUrl, baseStill, palette,
                onSelect = { selectedAction = it },
                onClose = { selectedAction = null },
            )
        } else {
            Column(
                Modifier.fillMaxSize().background(FigmaCanvas).safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (editingName) {
                    HeroNameInput(
                        value = editableName,
                        onValueChange = onNameChange,
                        onSave = { onSaveName(); editingName = false },
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            displayName.uppercase(),
                            color = FigmaInk,
                            fontSize = 28.sp,
                            fontFamily = RubikOne,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(onClick = { editingName = true }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                Icons.Rounded.Edit,
                                "Изменить имя",
                                tint = FigmaInk.copy(alpha = .48f),
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
                if (showStatusBanner) {
                    Spacer(Modifier.height(16.dp))
                    AnimationPackStatus(
                        readyCount = readyCount,
                        batchReadyCount = batchReadyCount,
                        batchTotal = selectedBatch.size,
                        batchComplete = batchComplete,
                        packReady = packReady,
                        inProgress = inProgress,
                        syncing = syncing,
                        interrupted = interrupted,
                        activeAction = activeAction,
                        generationRoute = generationRoute,
                        message = packMessage,
                        onDismiss = {
                            dismissedBanner = bannerSignature
                            if ((packReady || batchComplete) && mascotId != null) {
                                receiptPrefs.edit().putBoolean(readyReceiptKey, true).apply()
                            }
                        },
                    )
                }
                if (!generationError.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(generationError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                        maxLines = 5, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                    contentPadding = PaddingValues(vertical = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    items(ANIMATION_STATE_KEYS, key = { it }) { stateKey ->
                        val frames = animations[stateKey].orEmpty()
                        val video = videoUrls[stateKey] ?: legacyVideoUrl?.takeIf { stateKey in LEGACY_PERFORMANCE_ACTIONS }
                        val cardStatus = progress.cardStatus(stateKey, inProgress, syncing, interrupted, sequential, selectedBatch)
                        val available = stateKey in playableActions
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .mascotClickable(enabled = available, onClickLabel = "Посмотреть анимацию") {
                                    selectedAction = stateKey
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(.96f).clip(RoundedCornerShape(32.dp))
                                    .background(Color.White.copy(alpha = .48f))
                                    .border(1.dp, Color.White.copy(alpha = .60f), RoundedCornerShape(32.dp)),
                            ) {
                                ActionThumbnail(video, frames.getOrNull(frames.size / 2) ?: baseStill,
                                    stateTitle(stateKey), Modifier.fillMaxSize().padding(8.dp)
                                        .alpha(if (available) 1f else if (cardStatus == AnimationCardStatus.WORKING) .65f else .35f))
                                if (available) {
                                    Icon(
                                        Icons.Rounded.PlayArrow, null, tint = FigmaInk.copy(alpha = .70f),
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                                            .size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = .75f)).padding(4.dp),
                                    )
                                } else if (cardStatus == AnimationCardStatus.WORKING) {
                                    CircularProgressIndicator(
                                        color = FigmaInk.copy(alpha = .65f), strokeWidth = 2.dp,
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(20.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(stateTitle(stateKey), fontFamily = SbSansDisplayMedium, fontSize = 13.sp,
                                color = FigmaInk, textAlign = TextAlign.Center)
                            Text(
                                when {
                                    cardStatus == AnimationCardStatus.READY -> "Готово · можно смотреть"
                                    cardStatus == AnimationCardStatus.WORKING -> "В работе…"
                                    cardStatus == AnimationCardStatus.QUEUED -> "В очереди"
                                    cardStatus == AnimationCardStatus.PROCESSING -> "Ожидаем результат"
                                    cardStatus == AnimationCardStatus.SAVING -> "Сохраняется…"
                                    cardStatus == AnimationCardStatus.PAUSED -> "Ожидает продолжения"
                                    available -> "Превью · видео ещё не создано"
                                    else -> "Ещё не создано"
                                },
                                color = FigmaInk.copy(alpha = if (available) .58f else .40f),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                if (!packReady && mascotId != null) {
                    Text(
                        when {
                            (inProgress || syncing) && readyCount > 0 -> "Готовые анимации уже можно смотреть"
                            inProgress -> "Первый ролик ещё в работе — он появится здесь"
                            syncing -> "Получаем первые готовые ролики на телефон"
                            else -> "Готово $readyCount из $ANIMATION_PACK_SIZE · осталось ${progress.remainingCount}"
                        },
                        color = FigmaInk.copy(alpha = .52f), fontSize = 11.sp, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (greetingRequiresResume) onResumeGreeting() else {
                                selectedCount = defaultAnimationBatchSize(progress.remainingCount)
                                confirmPack = true
                            }
                        }, enabled = !inProgress && !syncing && progress.remainingCount > 0,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = FigmaInk),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text(
                            when {
                                greetingRequiresResume -> "Продолжить приветствие"
                                inProgress -> "Создаём: $batchReadyCount из ${selectedBatch.size} готовы"
                                syncing -> "Сохраняем: $readyCount из $ANIMATION_PACK_SIZE"
                                interrupted -> "Продолжить · осталось ${progress.remainingCount}"
                                else -> "Оживить персонажа"
                            },
                            fontFamily = SbSansDisplayMedium,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                CloseControl("Закрыть действия", onBack)
            }
        }
    }
    if (confirmPack && progress.remainingCount > 0) {
        AlertDialog(
            onDismissRequest = { confirmPack = false },
            title = { Text("Сколько анимаций создать?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Готово $readyCount из $ANIMATION_PACK_SIZE · можно добавить ${progress.remainingCount}")
                    Text("$selectedCount", style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    if (progress.remainingCount > 1) {
                        Slider(value = selectedCount.toFloat(),
                            onValueChange = { selectedCount = it.roundToInt().coerceIn(1, progress.remainingCount) },
                            valueRange = 1f..progress.remainingCount.toFloat(),
                            steps = (progress.remainingCount - 2).coerceAtLeast(0))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { selectedCount = defaultAnimationBatchSize(progress.remainingCount) }) {
                                Text("По умолчанию · ${defaultAnimationBatchSize(progress.remainingCount)}")
                            }
                            TextButton(onClick = { selectedCount = progress.remainingCount }) {
                                Text("Все · ${progress.remainingCount}")
                            }
                        }
                    }
                    Text("Seedance 2 Mini · каждый ролик — 6–8 секунд платной генерации. " +
                        "Готовые ролики пропустим, сохранённые задания продолжим. " +
                        "После выбранного количества остановимся. Результаты сохраняются по одному.")
                }
            },
            dismissButton = { TextButton(onClick = { confirmPack = false }) { Text("Отмена") } },
            confirmButton = {
                TextButton(onClick = { confirmPack = false; onComplete(selectedCount) }, enabled = !inProgress && !syncing) {
                    Text("${if (interrupted) "Продолжить" else "Создать"} · $selectedCount")
                }
            },
        )
    }
}

@Composable
private fun AnimationPackStatus(
    readyCount: Int,
    batchReadyCount: Int,
    batchTotal: Int,
    batchComplete: Boolean,
    packReady: Boolean,
    inProgress: Boolean,
    syncing: Boolean,
    interrupted: Boolean,
    activeAction: String?,
    generationRoute: GenerationRoute,
    message: String?,
    onDismiss: () -> Unit,
) {
    val title = when {
        packReady -> "Все $ANIMATION_PACK_SIZE анимаций готовы"
        inProgress -> "Выбранная партия: $batchReadyCount из $batchTotal готовы"
        batchComplete -> "Выбранные $batchTotal анимаций готовы"
        syncing -> "Сохраняем анимации: $readyCount из $ANIMATION_PACK_SIZE"
        interrupted -> "Готово $readyCount из $ANIMATION_PACK_SIZE — можно продолжить"
        else -> "Анимации пока не созданы"
    }
    val detail = when {
        packReady -> "Нажми на карточку, чтобы посмотреть действие"
        batchComplete -> "Всего готово $readyCount из $ANIMATION_PACK_SIZE. Остальные можно добавить позже."
        inProgress -> {
            val current = activeAction?.let { "В работе: ${stateTitle(it).lowercase()}. " }.orEmpty()
            val background = if (generationRoute == GenerationRoute.OPENROUTER_DIRECT) {
                "Можно выйти с экрана: генерацией занимается фоновое задание на телефоне. Нужен интернет."
            } else {
                "Можно выйти с экрана — работа продолжится на сервере."
            }
            "${current}Всего готово $readyCount из $ANIMATION_PACK_SIZE. Каждый ролик сохраняется сразу. $background"
        }
        syncing -> "Генерация закончилась; переносим готовые видео на телефон"
        interrupted -> "Готовые видео сохранены и повторно генерироваться не будут"
        else -> "После подтверждения прогресс будет виден прямо здесь"
    }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = .46f))
            .border(1.dp, Color.White.copy(alpha = .62f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = FigmaInk,
                fontFamily = SbSansDisplayMedium,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    painterResource(R.drawable.close_gallery_icon),
                    "Скрыть сообщение",
                    tint = FigmaInk.copy(alpha = .52f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = {
                if ((inProgress || batchComplete) && batchTotal > 0) batchReadyCount.toFloat() / batchTotal
                else readyCount.toFloat() / ANIMATION_PACK_SIZE
            },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
            color = FigmaInk.copy(alpha = .82f),
            trackColor = FigmaInk.copy(alpha = .12f),
        )
        Spacer(Modifier.height(8.dp))
        Text(detail, color = FigmaInk.copy(alpha = .52f), fontSize = 11.sp, lineHeight = 15.sp)
        if (!packReady && !inProgress && !message.isNullOrBlank() && message != title) {
            Spacer(Modifier.height(4.dp))
            Text(message, color = FigmaInk.copy(alpha = .52f), fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun ActionDetail(
    mascotId: String?,
    action: String,
    heroName: String?,
    frames: List<String>,
    videoUrls: Map<String, String>,
    legacyVideoUrl: String?,
    neutralFrameUrl: String?,
    baseStill: String?,
    palette: HeroPalette,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
) {
    // Do not allocate a decoder while the grid is still exiting.
    var play by remember(action) { mutableStateOf(false) }
    LaunchedEffect(action) { delay(300); play = true }
    val videoUrl = videoUrls[action]
    Column(
        Modifier.fillMaxSize().background(heroGradient(palette)).safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            heroName?.takeIf { it.isNotBlank() }?.uppercase() ?: "ГЕРОЙ",
            fontFamily = RubikBubbles, fontSize = 40.sp,
            color = Color.White.copy(alpha = .64f), textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (play && videoUrl != null) {
                SoraMascotVideo(
                    videoUrls = mapOf(action to videoUrl),
                    neutralFrameUrl = neutralFrameUrl, action = action,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (play && legacyVideoUrl != null) {
                SoraMascotVideo(videoUrl = legacyVideoUrl, action = action, modifier = Modifier.fillMaxSize())
            } else if (play && frames.isNotEmpty()) {
                PngSequence(frames = frames, fps = HeroLocalStore.FULL_FRAME_FPS, decodeSize = 500, fallback = baseStill,
                    mascotIdHint = mascotId, puppetAction = action, contentDescription = stateTitle(action),
                    modifier = Modifier.fillMaxSize())
            } else ActionThumbnail(videoUrl, baseStill, stateTitle(action), Modifier.fillMaxSize())
        }
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp))
                .background(Color.White.copy(alpha = .32f))
                .border(1.dp, Color.White.copy(alpha = .24f), RoundedCornerShape(32.dp))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(painterResource(R.drawable.quote_mark_vector), null, tint = palette.top,
                modifier = Modifier.size(24.dp))
            Text(actionDescriptions[action].orEmpty(), color = palette.top,
                fontFamily = SbSansDisplayMedium, fontSize = 16.sp, lineHeight = 22.sp,
                textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        LazyRow(
            Modifier.fillMaxWidth().height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            lazyRowItems(ANIMATION_STATE_KEYS, key = { it }) { key ->
                val available = videoUrls[key] != null || key == action
                Box(
                    Modifier.size(56.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = if (key == action) .60f else .20f))
                        .border(2.dp, if (key == action) Color.White else Color.Transparent, CircleShape)
                        .mascotClickable(enabled = available, onClickLabel = stateTitle(key)) { onSelect(key) },
                ) {
                    ActionThumbnail(videoUrls[key], baseStill, stateTitle(key), Modifier.fillMaxSize().padding(4.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        CloseControl("Назад к действиям", onClose)
    }
}

@Composable
private fun CloseControl(description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(R.drawable.close_gallery_icon), description, Modifier.size(24.dp), tint = FigmaInk)
    }
}
