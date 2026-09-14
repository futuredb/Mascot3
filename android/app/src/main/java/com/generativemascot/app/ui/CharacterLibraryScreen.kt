package com.generativemascot.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.generativemascot.app.R
import com.generativemascot.app.data.resolveMediaUrl
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun CharacterLibraryScreen(
    heroes: List<HeroLibraryItem>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onNewHero: () -> Unit,
) {
    MascotSystemBars(FigmaCanvas)
    var confirmNewHero by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var initialPositionApplied by remember { mutableStateOf(false) }
    val strip = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val selected = heroes.firstOrNull { it.id == selectedId }
        ?: heroes.firstOrNull { it.active }
        ?: heroes.firstOrNull()

    LaunchedEffect(Unit) { onRefresh() }
    LaunchedEffect(heroes.map { it.id }) {
        if (!initialPositionApplied && heroes.isNotEmpty()) {
            val initial = heroes.indexOfFirst { it.active }.takeIf { it >= 0 } ?: 0
            selectedId = heroes[initial].id
            strip.scrollToItem(initial)
            initialPositionApplied = true
        }
    }
    LaunchedEffect(strip, heroes) {
        snapshotFlow {
            val info = strip.layoutInfo
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { item ->
                abs(item.offset + item.size / 2 - viewportCenter)
            }?.key as? String
        }.filterNotNull().distinctUntilChanged().collect { centeredId ->
            if (heroes.any { it.id == centeredId }) selectedId = centeredId
        }
    }

    fun commitSelection() {
        selected?.let { onSelect(it.id) }
    }

    BackHandler { onBack() }
    val palette = heroPalette(selected?.baseStill ?: selected?.baseFrames?.firstOrNull())

    BoxWithConstraints(
        Modifier.fillMaxSize().background(FigmaCanvas).safeDrawingPadding(),
    ) {
        val carouselItemWidth = 82.dp
        val carouselAvailableWidth = (maxWidth - 48.dp).coerceAtLeast(carouselItemWidth)
        val carouselPadding = ((carouselAvailableWidth - carouselItemWidth) / 2).coerceAtLeast(0.dp)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                Text(
                    "МОИ ГЕРОИ",
                    fontFamily = RubikOne,
                    fontSize = 24.sp,
                    color = FigmaInk,
                )
                IconButton(
                    onClick = { confirmNewHero = true },
                    modifier = Modifier.align(Alignment.CenterEnd).size(44.dp)
                        .clip(CircleShape).background(Color.White.copy(alpha = .64f)),
                ) {
                    Icon(Icons.Rounded.Add, "Создать нового героя", tint = FigmaInk)
                }
            }

            Box(
                Modifier.weight(1f).widthIn(max = 384.dp).fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp)
                    .clip(RoundedCornerShape(48.dp))
                    .background(heroGradient(palette)),
            ) {
                AnimatedContent(
                    targetState = selected,
                    modifier = Modifier.fillMaxSize(),
                    contentKey = { it?.id },
                    transitionSpec = {
                        fadeIn(tween(160, easing = MascotEase))
                            .togetherWith(fadeOut(tween(90)))
                    },
                    label = "live hero preview",
                ) { hero ->
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            hero?.name?.takeIf { it.isNotBlank() }?.uppercase() ?: "ГЕРОЙ",
                            color = Color.White.copy(alpha = .66f),
                            fontFamily = RubikBubbles,
                            fontSize = 46.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        AsyncImage(
                            model = resolveMediaUrl(hero?.baseStill ?: hero?.baseFrames?.firstOrNull()),
                            contentDescription = hero?.name ?: "Выбранный герой",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        Text(
                            "Листай героев снизу — превью меняется сразу",
                            color = palette.top.copy(alpha = .62f),
                            fontFamily = SbSansDisplayMedium,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clip(CircleShape)
                                .background(Color.White.copy(alpha = .34f))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            Box(
                Modifier.fillMaxWidth().height(132.dp)
                    .clip(RoundedCornerShape(48.dp))
                    .background(Color.White.copy(alpha = .48f))
                    .border(1.dp, Color.White.copy(alpha = .72f), RoundedCornerShape(48.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val ovalHeight = size.height * .72f
                    drawArc(
                        color = FigmaInk.copy(alpha = .10f),
                        startAngle = 202f,
                        sweepAngle = 136f,
                        useCenter = false,
                        topLeft = Offset(size.width * .06f, size.height * .18f),
                        size = Size(size.width * .88f, ovalHeight),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                if (heroes.isEmpty()) {
                    Text(
                        "Герои ещё загружаются…",
                        color = FigmaInk.copy(alpha = .48f),
                        fontFamily = SbSansDisplayMedium,
                        fontSize = 13.sp,
                    )
                } else {
                    LazyRow(
                        state = strip,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = carouselPadding, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        flingBehavior = rememberSnapFlingBehavior(lazyListState = strip),
                    ) {
                        items(heroes.size, key = { heroes[it].id }) { index ->
                            val hero = heroes[index]
                            val layoutInfo = strip.layoutInfo
                            val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.key == hero.id }
                            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                            val itemCenter = itemInfo?.let { it.offset + it.size / 2f } ?: viewportCenter
                            val halfViewport = ((layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 2f)
                                .coerceAtLeast(1f)
                            val distance = ((itemCenter - viewportCenter) / halfViewport).coerceIn(-1f, 1f)
                            val focus = 1f - abs(distance)
                            val targetScale = .72f + focus * .28f
                            val scale by animateFloatAsState(
                                targetValue = targetScale,
                                animationSpec = tween(90, easing = MascotEase),
                                label = "carousel depth",
                            )
                            val active = hero.id == selected?.id
                            Column(
                                modifier = Modifier.width(carouselItemWidth).fillMaxHeight()
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationY = (1f - focus) * 18.dp.toPx()
                                        rotationY = -distance * 16f
                                        alpha = .44f + focus * .56f
                                        cameraDistance = 18f * density
                                    }
                                    .mascotClickable(onClickLabel = "Показать ${hero.name ?: "героя"}") {
                                        scope.launch { strip.animateScrollToItem(index) }
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                HeroThumbnail(
                                    hero,
                                    Modifier.size(68.dp)
                                        .border(
                                            2.dp,
                                            if (active) FigmaInk.copy(alpha = .72f) else Color.Transparent,
                                            CircleShape,
                                        ),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    hero.name ?: "Герой",
                                    color = FigmaInk,
                                    fontFamily = SbSansDisplayMedium,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().height(84.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(54.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = .62f)),
                ) {
                    Icon(
                        painterResource(R.drawable.close_gallery_icon),
                        "Отменить выбор",
                        tint = FigmaInk,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Box(
                    Modifier.size(62.dp).clip(CircleShape)
                        .background(if (selected != null) FigmaInk else FigmaInk.copy(alpha = .28f))
                        .mascotClickable(
                            enabled = selected != null,
                            onClickLabel = "Выбрать ${selected?.name ?: "героя"}",
                            onClick = ::commitSelection,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Groups,
                        contentDescription = "Подтвердить выбор героя",
                        tint = Color.White,
                        modifier = Modifier.size(29.dp),
                    )
                }
            }
        }
    }

    if (confirmNewHero) {
        AlertDialog(
            onDismissRequest = { confirmNewHero = false },
            title = { Text("Создать нового героя?") },
            text = {
                Text(
                    "Это запустит отдельную платную генерацию внешности. Видео-анимации создаются только позже и после отдельного подтверждения.",
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmNewHero = false }) { Text("Отмена") }
            },
            confirmButton = {
                TextButton(onClick = { confirmNewHero = false; onNewHero() }) { Text("Создать") }
            },
        )
    }
}
