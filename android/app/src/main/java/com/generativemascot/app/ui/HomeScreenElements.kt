package com.generativemascot.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.generativemascot.app.R

private val homeGlass = Brush.verticalGradient(listOf(Color.White.copy(alpha = .06f), Color.White.copy(alpha = .4f)))

@Composable
internal fun HomeFigmaIcon(asset: String, description: String?, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data("file:///android_asset/figma-home/$asset.svg")
            .decoderFactory(SvgDecoder.Factory()).build(),
        contentDescription = description,
        modifier = modifier.size(size),
    )
}

@Composable
internal fun homeHeadingFontSize(text: String, availableWidth: Int, maxSize: Float = 70f): androidx.compose.ui.unit.TextUnit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(text, availableWidth, maxSize, density.fontScale, density.density) {
        val annotated = AnnotatedString(text)
        var low = 1f
        var high = maxSize
        repeat(12) {
            val candidate = (low + high) / 2f
            val width = measurer.measure(annotated, style = TextStyle(
                fontFamily = SbSansDisplayExtendedSemibold, fontWeight = FontWeight.SemiBold,
                fontSize = candidate.sp), maxLines = 1, softWrap = false).size.width
            if (width <= availableWidth - with(density) { 8.dp.toPx() }) low = candidate else high = candidate
        }
        low.sp
    }
}

@Composable
internal fun HomeHeading(name: String, modifier: Modifier = Modifier) {
    val text = name.uppercase()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val fontSize = homeHeadingFontSize(text, constraints.maxWidth)
        Text(text, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Clip,
            fontFamily = SbSansDisplayExtendedSemibold, fontWeight = FontWeight.SemiBold, fontSize = fontSize,
            style = TextStyle(brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = .44f), Color.Transparent))))
        Text(text, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Clip,
            fontFamily = SbSansDisplayExtendedSemibold, fontWeight = FontWeight.SemiBold, fontSize = fontSize,
            style = TextStyle(color = Color.White.copy(alpha = .55f), drawStyle = Stroke(width = 1f)))
    }
}

@Composable
internal fun HomeNameEditor(value: TextFieldValue, onChange: (TextFieldValue) -> Unit,
    onSave: () -> Unit, focusRequester: FocusRequester, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val displayed = value.text.map { it.uppercaseChar() }.joinToString("")
        val fontSize = homeHeadingFontSize(displayed, constraints.maxWidth)
        BasicTextField(
            value = value,
            onValueChange = { if (it.text.length <= 50 && '\n' !in it.text) onChange(it) },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontFamily = SbSansDisplayExtendedSemibold,
                fontWeight = FontWeight.SemiBold, fontSize = fontSize, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            visualTransformation = VisualTransformation { input ->
                TransformedText(AnnotatedString(input.text.map { it.uppercaseChar() }.joinToString("")), OffsetMapping.Identity)
            },
        )
    }
}

@Composable
internal fun HomeSettingsButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(50.dp).clip(CircleShape).background(homeGlass)
        .border(2.dp, Color.White.copy(alpha = .16f), CircleShape)
        .mascotClickable(enabled = enabled, onClickLabel = "Настройки", onClick = onClick),
        contentAlignment = Alignment.Center) {
        HomeFigmaIcon("settings", null)
    }
}

@Composable
internal fun HomeQuoteCard(line: String, palette: HeroPalette, offer: Boolean,
    onAcceptOffer: () -> Unit, onDismissOffer: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(32.dp)).background(homeGlass)
        .border(2.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(32.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(painterResource(R.drawable.quote_mark_vector), null, tint = palette.top,
            modifier = Modifier.size(width = 22.dp, height = 13.dp))
        Spacer(Modifier.height(10.dp))
        Text(line, color = palette.top.copy(alpha = .88f), fontFamily = SbSansDisplayMedium,
            fontSize = 18.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 18.dp))
        if (offer) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HomeOfferButton("Давай", onAcceptOffer, palette)
                HomeOfferButton("Нет", onDismissOffer, palette)
            }
        }
    }
}

@Composable
private fun HomeOfferButton(label: String, onClick: () -> Unit, palette: HeroPalette) {
    Box(Modifier.height(32.dp).clip(RoundedCornerShape(10.dp))
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .13f), Color.White.copy(alpha = .8f))))
        .border(2.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(10.dp))
        .mascotClickable(onClickLabel = label, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = palette.top, fontFamily = SbSansTextMedium, fontSize = 13.sp)
    }
}

@Composable
internal fun HomeChatButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.height(50.dp).clip(CircleShape).background(FigmaInk)
        .mascotClickable(enabled = enabled, onClickLabel = "Открыть чат", onClick = onClick)
        .padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        HomeFigmaIcon("chat", null)
        Text("ЧАТ", color = Color.White, fontFamily = SbSansDisplayMedium, fontWeight = FontWeight.Bold, fontSize = 13.4.sp)
    }
}

@Composable
internal fun HomeSettingsMenu(onClose: () -> Unit, onHeroes: () -> Unit, onAnimations: () -> Unit,
    onOverlay: () -> Unit, onBehavior: () -> Unit, onCapabilities: () -> Unit,
    onApiKeys: () -> Unit, onExportHeroes: () -> Unit, onPinWidget: () -> Unit, onDelete: () -> Unit,
    modifier: Modifier = Modifier, contentModifier: Modifier = Modifier) {
    BackHandler { onClose() }
    Box(modifier.fillMaxSize().background(Color(0xFFEAEBEC).copy(alpha = .90f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Box(contentModifier.align(Alignment.TopEnd).padding(top = 42.dp, end = 27.dp).size(30.dp)
            .mascotClickable(onClickLabel = "Закрыть настройки", onClick = onClose), contentAlignment = Alignment.Center) {
            HomeFigmaIcon("menu-close", null, size = 15.dp)
        }
        Column(contentModifier.align(Alignment.TopEnd).fillMaxWidth().padding(top = 103.dp, end = 36.dp),
            horizontalAlignment = Alignment.End) {
            HomeMenuItem("Настройки персонажа", "menu-edit", 13.35.dp, onHeroes)
            HomeMenuItem("Оживить персонажа", "menu-play", 15.74.dp, onAnimations)
            HomeMenuItem("Виджет на главном", "menu-widget", 18.dp, onOverlay)
            HomeMenuItem("Поведение и доступы", "menu-lightning", 16.dp, onBehavior)
            HomeMenuItem("API-ключи", "menu-key", 16.dp, onApiKeys)
            HomeMenuItem("Экспорт героев", "menu-widget", 16.dp, onExportHeroes)
            Box(Modifier.height(72.dp).homeMenuClickable("Удалить персонажа", onDelete), contentAlignment = Alignment.CenterEnd) {
                Text("Удалить персонажа", color = Color(0xFF777777),
                    fontFamily = SbSansTextMedium, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun HomeMenuItem(label: String, icon: String, iconSize: Dp, onClick: () -> Unit) {
    Row(Modifier.height(72.dp).homeMenuClickable(label, onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.Black, fontFamily = SbSansTextMedium, fontSize = 14.sp)
        Box(Modifier.size(28.dp).clip(CircleShape).background(FigmaInk),
            contentAlignment = Alignment.Center) {
            HomeFigmaIcon(icon, null, size = iconSize)
        }
    }
}

private fun Modifier.homeMenuClickable(label: String, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 18f / 14f else 1f,
        tween(130, easing = MascotEase), label = "menu press response")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(1f, .5f)
    }.clickable(interactionSource = source, indication = null, onClickLabel = label, onClick = onClick)
}
