package com.generativemascot.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.generativemascot.app.data.resolveMediaUrl

internal fun heroNameDraft(name: String): TextFieldValue =
    TextFieldValue(name, selection = TextRange(name.length))

@Composable
internal fun HeroNameEditScene(
    name: TextFieldValue,
    portrait: String?,
    onNameChange: (TextFieldValue) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focus) {
        focus.requestFocus()
        keyboard?.show()
    }
    val confirm = {
        if (name.text.isNotBlank()) {
            keyboard?.hide()
            onConfirm()
        }
    }
    BackHandler {
        keyboard?.hide()
        onCancel()
    }

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val scale = maxWidth / 393f
        HomeNameEditor(name, onNameChange, confirm, focus,
            Modifier.align(Alignment.TopCenter).offset(y = scale * 104).padding(horizontal = 20.dp)
                .fillMaxWidth().height(scale * 88), color = FigmaInk)
        Box(Modifier.align(Alignment.TopCenter).offset(y = scale * 174).zIndex(1f).size(40.dp)
            .clip(CircleShape).background(FigmaInk.copy(alpha = if (name.text.isNotBlank()) 1f else .4f))
            .mascotClickable(enabled = name.text.isNotBlank(), onClickLabel = "Сохранить имя", onClick = confirm),
            contentAlignment = Alignment.Center) {
            HomeFigmaIcon("hero-confirm", "Сохранить имя", size = 18.dp)
        }
        AsyncImage(resolveMediaUrl(portrait), null,
            Modifier.align(Alignment.TopCenter).offset(y = scale * 249).size(scale * 335, scale * 354),
            contentScale = ContentScale.Crop)
    }
}
