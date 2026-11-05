package com.generativemascot.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.generativemascot.app.R
import com.generativemascot.app.data.resolveMediaUrl

@Composable
internal fun PersonalityGenerationScene(
    generating: Boolean, previewUrl: String?, heroName: String?, nameDraft: String, error: String?,
    acceptEnabled: Boolean, onGenerate: () -> Unit, onRegenerate: () -> Unit, onAccept: () -> Unit,
    onNameChange: (String) -> Unit, onSaveName: () -> Unit, onCancel: () -> Unit,
    onBackground: () -> Unit,
) {
    var editingName by remember { mutableStateOf(false) }
    val ready = !previewUrl.isNullOrBlank() && !generating
    MascotSystemBars(Color(0xfff2f2f2))
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xfff2f2f2))) {
        val sx = maxWidth / 393f
        val sy = maxHeight / 852f
        Image(painterResource(R.drawable.selection_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (!ready) {
            Text("ТАМ\nКТО-ТО\nЕСТЬ", fontFamily = FontExtended, fontSize = 34.sp, lineHeight = 40.sp,
                textAlign = TextAlign.Center, color = Color.Black,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = sy * 143))
            Image(painterResource(R.drawable.selection_cloud), "Облачко", Modifier.offset(x = sx * 42, y = sy * 308).size(sx * 299, sy * 273), contentScale = ContentScale.FillBounds)
            if (generating || error == null) Text("Ждём персонажа", color = Color.Black.copy(alpha = .35f), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = sy * 772))
            else Button(onClick = onGenerate, modifier = Modifier.align(Alignment.TopCenter).offset(y = sy * 745),
                colors = ButtonDefaults.buttonColors(containerColor = FigmaInk)) { Text("Попробовать снова") }
        } else {
            Text((heroName ?: "Герой").uppercase(), fontFamily = FontExtended, fontSize = 50.sp,
                maxLines = 1, textAlign = TextAlign.Center, color = Color(0xff333333),
                modifier = Modifier.align(Alignment.TopCenter).offset(y = sy * 109).fillMaxWidth().padding(horizontal = 16.dp))
            MascotSmallCircleAction(Icons.Rounded.Edit, "Изменить имя", { onNameChange(heroName.orEmpty()); editingName = true },
                Color(0xff222222), Color.White, modifier = Modifier.offset(x = (maxWidth - MascotSmallActionSize) / 2, y = sy * 182))
            AsyncImage(model = resolveMediaUrl(previewUrl), contentDescription = "Новый герой", contentScale = ContentScale.Fit,
                modifier = Modifier.offset(x = sx * 29, y = sy * 249).size(sx * 335, sy * 354))
            Row(Modifier.align(Alignment.TopCenter).offset(y = sy * 745), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MascotSmallCircleAction(Icons.Rounded.Refresh, "Другой герой", onRegenerate, Color.White.copy(alpha = .65f), Color.Black, enabled = acceptEnabled)
                Button(onClick = onAccept, enabled = acceptEnabled, colors = ButtonDefaults.buttonColors(containerColor = FigmaInk)) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("ОСТАВИМ")
                }
            }
        }
        if (generating && !ready) {
            Box(Modifier.offset(x = sx * 336, y = sy * 47).size(48.dp)
                .mascotClickable(onClickLabel = "Свернуть ожидание", onClick = onBackground),
                contentAlignment = Alignment.Center) {
                HomeFigmaIcon("creation-close", null, size = 15.dp)
            }
        }
        if (!generating && (ready || error != null)) {
            Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = 4.dp)) {
                MascotTopAction(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onCancel)
            }
        }
        error?.let { Text(it, color = Color(0xffb33c30), fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 125.dp)) }
    }
    if (editingName) AlertDialog(onDismissRequest = { editingName = false }, title = { Text("Имя героя") },
        text = { OutlinedTextField(value = nameDraft, onValueChange = onNameChange, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSaveName(); editingName = false }, enabled = nameDraft.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { editingName = false }) { Text("Отмена") } })
}

private val FontExtended = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.sb_sans_display_extended_semibold))
