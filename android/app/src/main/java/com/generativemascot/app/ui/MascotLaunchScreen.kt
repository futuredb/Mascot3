package com.generativemascot.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import com.generativemascot.app.R

/** Calm launch screen while the app restores its saved state. */
@Composable
internal fun MascotLaunchScreen() {
    val background = Color(0xFFE8EBEA)
    MascotSystemBars(background, background)
    BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
        val artHeight = maxHeight * .28f
        val artWidth = artHeight * (1020f / 900f)
        Image(
            painterResource(R.drawable.type_person_figma), contentDescription = null,
            modifier = Modifier.align(Alignment.TopCenter).offset(x = artWidth * -.056f, y = maxHeight * .62f)
                .size(width = artWidth, height = artHeight),
            contentScale = ContentScale.Fit,
        )
        Text(
            "Скоро увидимся", color = Color(0xFFB2B4B4), fontSize = 12.sp,
            fontFamily = SbSansTextRegular,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = maxHeight * .90f),
        )
    }
}
