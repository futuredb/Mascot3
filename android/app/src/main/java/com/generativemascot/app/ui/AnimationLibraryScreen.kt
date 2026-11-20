package com.generativemascot.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.generativemascot.app.R
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.data.ChatAnimationClip
import com.generativemascot.app.data.GenerationRoute
import com.generativemascot.app.data.defaultAnimationBatchSize
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

val ANIMATION_STATE_KEYS = HeroLocalStore.LIBRARY_VIDEO_ACTIONS
val ANIMATION_PACK_SIZE = ANIMATION_STATE_KEYS.size
@Composable
private fun AnimationLibraryTopBar(title: String, onBack: () -> Unit, onDelete: () -> Unit, canDelete: Boolean) {
    Box(Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 52.dp).offset(y = 18.dp),
            contentAlignment = Alignment.Center) {
            val name = title.uppercase()
            Text(name, fontFamily = SbSansDisplayExtendedSemibold, fontWeight = FontWeight.SemiBold,
                fontSize = homeHeadingFontSize(name, constraints.maxWidth, maxSize = 34f),
                maxLines = 1, textAlign = TextAlign.Center, color = Color.Black)
        }
        Box(Modifier.align(Alignment.CenterStart).size(48.dp)
            .mascotClickable(onClickLabel = "Назад", onClick = onBack), contentAlignment = Alignment.Center) {
            HomeFigmaIcon("library-back", null, size = 24.dp)
        }
        Box(Modifier.align(Alignment.CenterEnd).size(48.dp)
            .mascotClickable(enabled = canDelete, onClickLabel = "Удалить персонажа", onClick = onDelete),
            contentAlignment = Alignment.Center) { HomeFigmaIcon("hero-delete", null, size = 24.dp) }
    }
}

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
    onDelete: () -> Unit,
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
    var chatClips by remember(mascotId) { mutableStateOf(emptyList<ChatAnimationClip>()) }
    val foreground = rememberAppResumed()
    LaunchedEffect(mascotId, foreground, selectedAction) {
        if (!foreground || selectedAction != null) return@LaunchedEffect
        while (isActive && mascotId != null) {
            chatClips = withContext(Dispatchers.IO) { HeroLocalStore.current?.chatAnimations(mascotId).orEmpty() }
            delay(10_000)
        }
    }
    val context = LocalContext.current
    val receiptPrefs = remember(context) {
        context.getSharedPreferences("mascot_ui_receipts", Context.MODE_PRIVATE)
    }
    LaunchedEffect(Unit) { onRefresh() }
    val progress = animationPackProgress(videoUrls)
    val playableActions = animationPlayableActions(videoUrls, animations, !legacyVideoUrl.isNullOrBlank())
    val previewItems = remember(playableActions, videoUrls, animations, legacyVideoUrl, chatClips) {
        ANIMATION_STATE_KEYS.filter { it in playableActions }.map { action ->
            AnimationPreviewItem(action, stateTitle(action), action, videoUrls[action], animations[action].orEmpty(), false)
        } + chatClips.map { clip ->
            AnimationPreviewItem("chat:${clip.id}", clip.title, "idle", clip.videoUrl, emptyList(), true)
        }
    }
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
    LaunchedEffect(inProgress, syncing, packReady, foreground) {
        while (foreground && (inProgress || syncing) && !packReady) {
            delay(5_000)
            onRefresh()
        }
    }
    BackHandler { if (selectedAction != null) selectedAction = null else onBack() }
    val baseStill = remember(mascotId) { mascotId?.let { HeroLocalStore.current?.baseFile(it)?.toURI()?.toString() } }
    val palette = heroPalette(baseStill ?: neutralFrameUrl)
    MascotSystemBars(if (selectedAction == null) FigmaCanvas else palette.top,
        if (selectedAction == null) FigmaCanvas else palette.end)
    MascotTransition(selectedAction, Modifier.fillMaxSize(), contentKey = { it != null }) { action ->
        val selected = previewItems.firstOrNull { it.id == action }
        if (selected != null) {
            ActionDetail(
                mascotId = mascotId, selected = selected, items = previewItems, heroName = displayName,
                legacyVideoUrl = legacyVideoUrl, neutralFrameUrl = neutralFrameUrl, baseStill = baseStill, palette = palette,
                onSelect = { selectedAction = it }, onClose = { selectedAction = null },
            )
        } else {
            Column(
                Modifier.fillMaxSize().background(FigmaCanvas).safeDrawingPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimationLibraryTopBar(displayName, onBack = onBack, onDelete = onDelete, canDelete = mascotId != null)
                if (showStatusBanner) {
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.padding(horizontal = 24.dp)) {
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
                }
                if (!generationError.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(generationError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                        maxLines = 5, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp).clipToBounds(),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    if (chatClips.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "chat-header") {
                            Text("ИЗ ЧАТА", fontFamily = RubikOne, fontSize = 16.sp, color = FigmaInk)
                        }
                        items(chatClips, key = { "chat-${it.id}" }) { clip ->
                            Column(Modifier.fillMaxWidth().mascotClickable(onClickLabel = "Посмотреть реакцию из чата") { selectedAction = "chat:${clip.id}" }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.fillMaxWidth().aspectRatio(.96f).clip(RoundedCornerShape(32.dp))
                                    .background(Color.White.copy(alpha = .48f)).border(1.dp, Color.White.copy(alpha = .60f), RoundedCornerShape(32.dp))) {
                                    ActionThumbnail(clip.videoUrl, baseStill, clip.title, Modifier.fillMaxSize().padding(8.dp))
                                    Icon(Icons.Rounded.PlayArrow, null, tint = FigmaInk.copy(alpha = .70f), modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = .75f)).padding(4.dp))
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(clip.title, fontFamily = SbSansDisplayMedium, fontSize = 13.sp, color = FigmaInk, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }, key = "actions-header") {
                            Text("ЭМОЦИИ", fontFamily = RubikOne, fontSize = 16.sp, color = FigmaInk)
                        }
                    }
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
                            val status = when (cardStatus) {
                                AnimationCardStatus.WORKING -> "В работе…"
                                AnimationCardStatus.QUEUED -> "В очереди"
                                AnimationCardStatus.PROCESSING -> "Ожидаем результат"
                                AnimationCardStatus.SAVING -> "Сохраняется…"
                                AnimationCardStatus.PAUSED -> "Ожидает продолжения"
                                else -> null
                            }
                            if (status != null) Text(status, color = FigmaInk.copy(alpha = .52f),
                                fontSize = 11.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
                if (!packReady && mascotId != null) {
                    if (inProgress || syncing) {
                        Text(if (syncing) "Сохраняем анимации" else "Создаём анимации",
                            color = FigmaInk.copy(alpha = .52f), fontSize = 11.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = {
                            if (greetingRequiresResume) onResumeGreeting() else {
                                selectedCount = defaultAnimationBatchSize(progress.remainingCount)
                                confirmPack = true
                            }
                        }, enabled = !inProgress && !syncing && progress.remainingCount > 0,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = FigmaInk),
                        contentPadding = PaddingValues(horizontal = 28.dp),
                        modifier = Modifier.widthIn(max = 320.dp).height(50.dp),
                    ) {
                        Text(
                            when {
                                greetingRequiresResume -> "Продолжить приветствие"
                                inProgress -> "Создаём анимации"
                                syncing -> "Сохраняем анимации"
                                interrupted -> "Продолжить"
                                else -> "Оживить персонажа"
                            }.uppercase(),
                            fontFamily = SbSansDisplayBold, fontWeight = FontWeight.Bold, fontSize = 13.4.sp,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    if (confirmPack && progress.remainingCount > 0) {
        AlertDialog(
            onDismissRequest = { confirmPack = false },
            title = { Text("Сколько анимаций создать?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Выберите количество анимаций для создания")
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
                    Text("Каждый ролик — 6–8 секунд платной генерации. " +
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

private data class AnimationPreviewItem(
    val id: String,
    val title: String,
    val action: String,
    val videoUrl: String?,
    val frames: List<String>,
    val isChat: Boolean,
)

@Composable
private fun ActionDetail(
    mascotId: String?,
    selected: AnimationPreviewItem,
    items: List<AnimationPreviewItem>,
    heroName: String?,
    legacyVideoUrl: String?,
    neutralFrameUrl: String?,
    baseStill: String?,
    palette: HeroPalette,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
) {
    val strip = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentSelection by rememberUpdatedState(selected.id)
    var play by remember(selected.id) { mutableStateOf(false) }
    var frameVisible by remember(selected.id) { mutableStateOf(false) }
    val hasVideo = selected.videoUrl != null || (legacyVideoUrl != null && selected.action in LEGACY_PERFORMANCE_ACTIONS)
    LaunchedEffect(selected.id) { delay(200); play = true }
    val phoneInteraction = rememberHeroPhoneInteraction(mascotId, play) {}
    BoxWithConstraints(Modifier.fillMaxSize().background(heroGradient(palette)).safeDrawingPadding()) {
        val width = maxWidth
        val height = maxHeight
        MascotPickerBackButton(onBack = onClose, modifier = Modifier.align(Alignment.TopStart).padding(start = 15.dp, top = 33.dp))
        HomeHeading(heroName?.takeIf { it.isNotBlank() } ?: "Герой", Modifier.align(Alignment.TopCenter)
            .padding(top = height * .115f, start = 24.dp, end = 24.dp).fillMaxWidth())
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = height * .205f)
                .requiredSize(width = width * 1.18f, height = height * .50f)
                .testTag("animation-preview-swipe-area")
                .pointerInput(items.map { it.id }) {
                    var startIndex = 0
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { startIndex = items.indexOfFirst { it.id == currentSelection }.coerceAtLeast(0); drag = 0f },
                        onHorizontalDrag = { change, amount -> change.consume(); drag += amount; strip.dispatchRawDelta(-amount) },
                        onDragEnd = {
                            val info = strip.layoutInfo
                            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                            val nearest = info.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - center) }?.index ?: startIndex
                            val target = if (nearest == startIndex && kotlin.math.abs(drag) >= 32.dp.toPx())
                                startIndex + if (drag < 0f) 1 else -1 else nearest
                            if (items.isNotEmpty()) scope.launch { strip.animateScrollToItem(target.coerceIn(0, items.lastIndex)) }
                        },
                        onDragCancel = {
                            val index = items.indexOfFirst { it.id == currentSelection }
                            if (index >= 0) scope.launch { strip.animateScrollToItem(index) }
                        },
                    )
                }, contentAlignment = Alignment.Center,
        ) {
            key(selected.id) {
                when {
                    play && selected.videoUrl != null && selected.isChat -> SoraMascotVideo(
                        videoUrl = selected.videoUrl, action = "idle", fullClip = true,
                        lighting = phoneInteraction.lighting, modifier = Modifier.fillMaxSize(), onFirstFrame = { frameVisible = true })
                    play && selected.videoUrl != null -> SoraMascotVideo(
                        videoUrls = mapOf(selected.action to selected.videoUrl), neutralFrameUrl = neutralFrameUrl,
                        action = selected.action, lighting = phoneInteraction.lighting, modifier = Modifier.fillMaxSize(), onFirstFrame = { frameVisible = true })
                    play && legacyVideoUrl != null && selected.action in LEGACY_PERFORMANCE_ACTIONS -> SoraMascotVideo(
                        videoUrl = legacyVideoUrl, action = selected.action, lighting = phoneInteraction.lighting,
                        modifier = Modifier.fillMaxSize(), onFirstFrame = { frameVisible = true })
                    play && selected.frames.isNotEmpty() -> PngSequence(
                        frames = selected.frames, fps = HeroLocalStore.FULL_FRAME_FPS, decodeSize = 500, fallback = baseStill,
                        mascotIdHint = mascotId, puppetAction = selected.action, contentDescription = selected.title,
                        modifier = Modifier.fillMaxSize())
                    else -> ActionThumbnail(selected.videoUrl, baseStill, selected.title, Modifier.fillMaxSize())
                }
            }
            if (hasVideo && !frameVisible) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(32.dp),
                    color = Color.White.copy(alpha = .8f), strokeWidth = 2.5.dp,
                )
            }
        }
        Text(selected.title, fontFamily = SbSansDisplayMedium, fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp, color = Color.White.copy(alpha = .9f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = height * .285f, start = 24.dp, end = 24.dp))
        MascotPickerCarousel(
            items = items.map { MascotPickerItem(it.id, "Посмотреть ${it.title}") },
            selectedId = selected.id, onSelected = onSelect, state = strip,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = height * .153f)
                .width((width - 48.dp).coerceAtLeast(1.dp)).height(80.dp).testTag("animation-preview-carousel"),
        ) { item, isSelected, modifier ->
            val animation = items.first { it.id == item.id }
            Box(modifier.clip(CircleShape).background(Color.White.copy(alpha = .25f))
                .border(2.dp, if (isSelected) Color.White.copy(alpha = .92f) else Color.White.copy(alpha = .24f), CircleShape),
                contentAlignment = Alignment.Center) {
                ActionThumbnail(animation.videoUrl, baseStill, animation.title, Modifier.fillMaxSize().padding(3.dp))
            }
        }
    }
}
