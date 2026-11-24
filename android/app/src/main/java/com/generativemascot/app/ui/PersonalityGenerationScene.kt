package com.generativemascot.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.generativemascot.app.R
import com.generativemascot.app.data.resolveMediaUrl
import com.generativemascot.app.data.CharacterCommunication

@Composable
internal fun PersonalityGenerationScene(
    generating: Boolean, previewUrl: String?, heroName: String?, error: String?,
    acceptEnabled: Boolean, onGenerate: () -> Unit, communication: CharacterCommunication,
    onRegenerate: (CharacterCommunication) -> Unit, onAccept: (CharacterCommunication) -> Unit,
    onSaveName: (String) -> Unit, onCancel: () -> Unit,
    onBackground: () -> Unit,
) {
    var editingName by rememberSaveable(previewUrl) { mutableStateOf(false) }
    var nameDraft by rememberSaveable(previewUrl, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val reviewScroll = rememberScrollState()
    var profile by rememberSaveable(previewUrl, stateSaver = communicationSaver) { mutableStateOf(communication) }
    val ready = !previewUrl.isNullOrBlank() && !generating
    MascotSystemBars(Color(0xfff2f2f2))
    if (ready && editingName) {
        HeroNameEditScene(nameDraft, previewUrl, { nameDraft = it }, onConfirm = {
            onSaveName(nameDraft.text.trim())
            editingName = false
        }, onCancel = { editingName = false }, modifier = Modifier.background(characterSettingsBackground).imePadding())
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xfff2f2f2))) {
        val sx = maxWidth / 393f
        val sy = maxHeight / 852f
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
            Column(Modifier.fillMaxSize().background(characterSettingsBackground).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(reviewScroll)
                    .statusBarsPadding().padding(top = 74.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center) {
                        val heading = (heroName ?: "Герой").uppercase()
                        Text(heading, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                            style = TextStyle(brush = Brush.verticalGradient(listOf(FigmaInk, Color(0xffb9baba))),
                                fontFamily = SbSansDisplayExtendedSemibold, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                fontSize = homeHeadingFontSize(heading, constraints.maxWidth, maxSize = 59f),
                                textAlign = TextAlign.Center))
                    }
                    MascotSmallCircleAction(Icons.Rounded.Edit, "Изменить имя", {
                        nameDraft = heroNameDraft(heroName.orEmpty())
                        editingName = true
                    }, FigmaInk, Color.White, modifier = Modifier.size(40.dp))
                    AsyncImage(model = resolveMediaUrl(previewUrl), contentDescription = "Новый герой", contentScale = ContentScale.Fit,
                        modifier = Modifier.padding(top = 18.dp).size(sx * 275, 342.dp))
                    CommunicationSliders(profile, { profile = it }, Modifier.padding(top = 32.dp).widthIn(max = 400.dp)
                        .fillMaxWidth(.84f), enabled = acceptEnabled)
                }
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MascotSmallCircleAction(Icons.Rounded.Refresh, "Другой герой", { onRegenerate(profile) },
                        FigmaInk, Color.White, enabled = acceptEnabled)
                    Button(onClick = { onAccept(profile) }, enabled = acceptEnabled, modifier = Modifier.height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FigmaInk)) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("ОСТАВИМ", fontFamily = SbSansDisplayBold)
                    }
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

}

private val FontExtended = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.sb_sans_display_extended_semibold))
