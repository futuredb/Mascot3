package com.generativemascot.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.generativemascot.app.R

/** The Figma launch artwork is shown while the app restores its saved state. */
@Composable
internal fun MascotLaunchScreen() {
    MascotSystemBars(Color(0xFFD6D6D6), Color(0xFFF7F7F7))
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFFF2F2F2))) {
        Image(painterResource(R.drawable.selection_background), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        val cloudWidth = maxWidth * (298.729f / 393f)
        Image(painterResource(R.drawable.selection_cloud), null,
            Modifier.offset(x = maxWidth * (42f / 393f), y = maxHeight * (289f / 852f))
                .size(width = cloudWidth, height = cloudWidth * (273f / 298.729f)),
            contentScale = ContentScale.FillBounds)
    }
}
