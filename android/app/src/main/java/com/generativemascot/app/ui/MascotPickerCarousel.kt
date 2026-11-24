package com.generativemascot.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs

internal data class MascotPickerItem(val id: String, val label: String, val selectable: Boolean = true)

/** The shared centered strip used by both hero selection and animation previews. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MascotPickerCarousel(
    items: List<MascotPickerItem>,
    selectedId: String?,
    onSelected: (String) -> Unit,
    state: LazyListState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onExtraClick: ((String) -> Unit)? = null,
    content: @Composable (MascotPickerItem, Boolean, Modifier) -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val latestSelected by rememberUpdatedState(selectedId)
    val latestOnSelected by rememberUpdatedState(onSelected)
    val itemIds = remember(items) { items.map { it.id } }
    LaunchedEffect(state, enabled, itemIds) {
        if (!enabled) return@LaunchedEffect
        val initial = latestSelected
        val initialIndex = items.indexOfFirst { it.id == initial }
        if (initialIndex >= 0) state.scrollToItem(initialIndex)
        var ready = initialIndex < 0
        snapshotFlow {
            val info = state.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.key as? String
        }.filterNotNull().distinctUntilChanged().collect { id ->
            if (!ready) {
                if (id == initial) ready = true
            } else if (items.any { it.id == id && it.selectable } && id != latestSelected) {
                latestOnSelected(id)
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }
    BoxWithConstraints(modifier) {
        val width = 76.dp
        val padding = ((maxWidth - width) / 2).coerceAtLeast(0.dp)
        LazyRow(
            state = state, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = padding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = state, snapPosition = SnapPosition.Center),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                Box(Modifier.width(width).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val itemModifier = Modifier.size(if (item.selectable) 58.dp else 54.dp)
                        .graphicsLayer {
                            val info = state.layoutInfo
                            val layout = info.visibleItemsInfo.firstOrNull { it.key == item.id }
                            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            val itemCenter = layout?.let { it.offset + it.size / 2f } ?: center
                            val halfViewport = ((info.viewportEndOffset - info.viewportStartOffset) / 2f).coerceAtLeast(1f)
                            val distance = ((itemCenter - center) / halfViewport).coerceIn(-1f, 1f)
                            val focus = 1f - abs(distance)
                            val scale = .93f + focus * .07f
                            scaleX = scale; scaleY = scale
                            translationY = (1f - focus) * 15.dp.toPx()
                            rotationY = -distance * 15f
                            alpha = .55f + focus * .45f
                            cameraDistance = 18f * density.density
                        }
                        .mascotClickable(enabled = enabled, onClickLabel = item.label) {
                            if (!item.selectable) onExtraClick?.invoke(item.id)
                            else if (!state.isScrollInProgress) scope.launch { state.animateScrollToItem(index) }
                        }
                    content(item, item.id == selectedId, itemModifier)
                }
            }
        }
    }
}

@Composable
internal fun MascotPickerBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).mascotClickable(onClickLabel = "Назад", onClick = onBack), contentAlignment = Alignment.Center) {
        HomeFigmaIcon("hero-back", null, size = 24.dp)
    }
}
