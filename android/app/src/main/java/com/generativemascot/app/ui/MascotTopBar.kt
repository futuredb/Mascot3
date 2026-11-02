package com.generativemascot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.generativemascot.app.R

@Composable
internal fun MascotTopAction(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true, light: Boolean = false) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = .36f))) {
        Icon(icon, description, tint = (if (light) Color.White else FigmaInk).copy(alpha = if (enabled) .90f else .35f), modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun MascotTopBar(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null, lightTitle: Boolean = false,
    backArrow: Boolean = false, leading: @Composable (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Box(modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        val titleModifier = Modifier.fillMaxWidth().padding(horizontal = 58.dp)
            .then(if (onTitleClick != null) Modifier.mascotClickable(onClickLabel = "Анимации и имя героя", onClick = onTitleClick) else Modifier)
        Text(title.uppercase(), modifier = titleModifier, textAlign = TextAlign.Center,
            fontFamily = RubikBubbles, fontSize = 28.sp, color = if (lightTitle) Color.White.copy(alpha = .82f) else FigmaInk,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.align(Alignment.CenterStart)) {
            if (onBack != null) IconButton(onClick = onBack,
                modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = .36f))) {
                if (backArrow) Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = if (lightTitle) Color.White else FigmaInk, modifier = Modifier.size(22.dp))
                else Icon(painterResource(R.drawable.close_gallery_icon), "Закрыть", tint = if (lightTitle) Color.White else FigmaInk, modifier = Modifier.size(22.dp))
            } else leading?.invoke()
        }
        Box(Modifier.align(Alignment.CenterEnd)) { trailing?.invoke() }
    }
}

/** Shared placement for navigation inside the chat and its settings. */
@Composable
internal fun MascotChatBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.padding(top = 25.dp, end = 16.dp)) {
        MascotTopAction(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack)
    }
}
