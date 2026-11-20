package com.generativemascot.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.generativemascot.app.R
import com.generativemascot.app.data.CharacterCreationChoice
import com.generativemascot.app.data.CharacterPersonality
import com.generativemascot.app.data.characterPersonalities
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val extended = FontFamily(Font(R.font.sb_sans_display_extended_semibold))
private val regular = FontFamily(Font(R.font.sb_sans_display_regular))

internal data class PersonalityItemPosition(val index: Int, val offset: Int, val size: Int)

internal fun personalityIndexNearestCenter(items: List<PersonalityItemPosition>, start: Int, end: Int): Int? {
    val center = (start.toLong() + end.toLong()) / 2
    return items.minByOrNull { kotlin.math.abs(it.offset.toLong() + it.size / 2L - center) }?.index
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CharacterPersonalityScreen(type: String, preview: Int, previewAspect: Float, onFinish: (CharacterCreationChoice) -> Boolean,
    onFailure: () -> String,
    onBack: () -> Unit, onClose: () -> Unit, canNavigateBack: Boolean = true, showPreview: Boolean = true) {
    var selectedId by remember { mutableStateOf(characterPersonalities.first().id) }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = characterPersonalities.first { it.id == selectedId }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val fling = rememberSnapFlingBehavior(lazyListState = scroll, snapPosition = SnapPosition.Center)
    val orbPlayer = rememberPersonalityOrbPlayer(selectedId, scroll.isScrollInProgress)
    val bottomFade by animateFloatAsState(if (scroll.canScrollForward) 1f else 0f, tween(150), label = "list edge fade")
    LaunchedEffect(scroll) {
        snapshotFlow {
            val layout = scroll.layoutInfo
            personalityIndexNearestCenter(layout.visibleItemsInfo.map { PersonalityItemPosition(it.index, it.offset, it.size) },
                layout.viewportStartOffset, layout.viewportEndOffset)
        }.collect { index ->
            if (index != null && index in characterPersonalities.indices) {
                selectedId = characterPersonalities[index].id
                error = null
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sx = maxWidth / 393f
        val sy = maxHeight / 852f
        val listHeight = sy * 255
        val edgePadding = ((listHeight - 108.dp) / 2f).coerceAtLeast(0.dp)
        val previewWidth = 284f * previewAspect
        if (showPreview) Image(painterResource(preview), null,
            Modifier.offset(x = sx * ((393f - previewWidth) / 2f), y = sy * 147).size(sx * previewWidth, sy * 284), contentScale = ContentScale.FillBounds)
        if (canNavigateBack) {
            Box(Modifier.offset(x = sx * 13, y = sy * 48).size(48.dp)
                .mascotClickable(onClickLabel = "Назад", onClick = onBack), contentAlignment = Alignment.Center) {
                HomeFigmaIcon("creation-back", null, size = 24.dp)
            }
            Box(Modifier.offset(x = maxWidth - sx * 57, y = sy * 47).size(48.dp)
                .mascotClickable(onClickLabel = "Закрыть", onClick = onClose), contentAlignment = Alignment.Center) {
                HomeFigmaIcon("creation-close", null, size = 15.dp)
            }
        }
        LazyColumn(
            state = scroll,
            flingBehavior = fling,
            contentPadding = PaddingValues(top = edgePadding, bottom = edgePadding),
            modifier = Modifier.offset(x = sx * 44, y = sy * 489).size(sx * 305, listHeight)
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, .69f to Color.Transparent,
                        1f to Color(0xfff2f2f2).copy(alpha = bottomFade)), size = size)
                },
        ) {
            items(characterPersonalities.size, key = { characterPersonalities[it].id }) { index ->
                val style = characterPersonalities[index]
                PersonalityRow(style, style.id == selectedId, orbPlayer, onClick = {
                    scope.launch { scroll.animateScrollToItem(index) }
                })
            }
        }
        MascotSmallCircleAction(
            icon = Icons.Rounded.Check,
            label = "Подтвердить характер ${selected.name}",
            onClick = { error = if (onFinish(CharacterCreationChoice(type, selected.id))) null else onFailure() },
            enabled = canNavigateBack,
            background = Color(0xff222222), tint = Color.White,
            modifier = Modifier.offset(x = (maxWidth - MascotSmallActionSize) / 2, y = sy * 745),
        )
        error?.let { message ->
            Text(message,
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 28.dp).offset(y = sy * 105),
                color = Color(0xff9e3030), fontFamily = regular, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PersonalityRow(style: CharacterPersonality, selected: Boolean, orbPlayer: PersonalityOrbPlayer, onClick: () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    val selectionAlpha by animateFloatAsState(if (selected) 1f else 0f, tween(180), label = "card selection")
    val orbSize by animateDpAsState(if (selected) 80.dp else 40.dp, tween(220, easing = MascotEase), label = "orb size")
    val leadingPadding by animateDpAsState(if (selected) 16.dp else 36.dp, tween(220, easing = MascotEase), label = "card inset")
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth().height(108.dp)
            .clip(shape).background(Color(0xffebebeb).copy(alpha = .7f * selectionAlpha))
            .border(1.dp, Color.White.copy(alpha = selectionAlpha), shape)
            .clickable(interactionSource = source, indication = null, onClickLabel = "Выбрать стиль ${style.name}", onClick = onClick)
            .padding(start = leadingPadding, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PersonalityOrb(style.id, selected, orbPlayer, Modifier.size(orbSize))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            val longName = style.name.length > 19
            Text(style.name, fontFamily = extended, fontSize = if (selected && !longName) 15.sp else if (selected) 12.sp else 11.sp,
                lineHeight = if (selected && !longName) 18.sp else if (selected) 15.sp else 14.sp, color = Color.Black)
            if (selected) Text(style.description, fontFamily = regular, fontSize = 14.sp, lineHeight = 18.sp,
                color = Color.Black.copy(alpha = .5f))
        }
    }
}

