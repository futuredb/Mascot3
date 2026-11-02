package com.generativemascot.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.generativemascot.app.data.HeroLocalStore

@Composable
internal fun ChatProfileScreen(mascotId: String, heroName: String, previewUrl: String?,
    modeTitle: String, modeDescription: String, onBack: () -> Unit, onInteractionSettings: () -> Unit) {
    BackHandler(onBack = onBack)
    val file = remember(mascotId) { HeroLocalStore.current?.baseFile(mascotId) }
    val palette = heroPalette(previewUrl)
    MascotSystemBars(palette.top, palette.end)
    val motion = rememberInfiniteTransition(label = "profile mascot")
    val shift by motion.animateFloat(initialValue = 0f, targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = keyframes {
            durationMillis = 2000
            0f at 0 using androidx.compose.animation.core.FastOutSlowInEasing
            -5.625f at 1600 using androidx.compose.animation.core.FastOutSlowInEasing
            0f at 2000
        }, repeatMode = RepeatMode.Restart), label = "profile float")
    BoxWithConstraints(Modifier.fillMaxSize().background(heroGradient(palette)).safeDrawingPadding()) {
        val height = maxHeight
        Text(heroName.uppercase(), modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).align(Alignment.TopCenter).offset(y = height * .095f),
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontFamily = RubikOne, fontSize = 64.sp, color = Color.White.copy(alpha = .36f))
        AsyncImage(model = file ?: previewUrl, contentDescription = heroName, contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = height * .24f + shift.dp)
                .fillMaxWidth(.86f).height(height * .43f))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 36.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp))
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .08f), Color.White.copy(alpha = .40f))))
                .border(2.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(32.dp))
                .clickable(onClickLabel = "Изменить стиль общения", onClick = onInteractionSettings)
                .padding(horizontal = 18.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(modeDescription, color = palette.top, fontFamily = SbSansDisplayMedium, fontSize = 17.sp,
                    lineHeight = 22.sp, textAlign = TextAlign.Center)
            }
            Text(modeTitle, modifier = Modifier.padding(top = 10.dp), color = palette.top.copy(alpha = .65f),
                fontFamily = SbSansDisplayMedium, fontSize = 12.sp, textAlign = TextAlign.Center)
            Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).clip(CircleShape).background(heroGradient(palette)).border(2.dp, Color.White.copy(alpha = .55f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    AsyncImage(model = file ?: previewUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.offset(x = (-8).dp).size(58.dp).clip(CircleShape).background(Color.White.copy(alpha = .32f))
                    .border(2.dp, Color.White.copy(alpha = .55f), CircleShape)
                    .clickable(onClickLabel = "Настроить общение", onClick = onInteractionSettings), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Tune, "Настроить общение", tint = palette.top, modifier = Modifier.size(25.dp))
                }
            }
        }
        MascotTopAction(Icons.AutoMirrored.Rounded.ArrowBack, "Назад в чат", onBack, light = true)
    }
}
