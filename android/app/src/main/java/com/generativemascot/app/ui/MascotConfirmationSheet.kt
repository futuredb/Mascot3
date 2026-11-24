package com.generativemascot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared confirmation surface for irreversible actions and future app dialogs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MascotConfirmationSheet(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    busy: Boolean = false,
    enabled: Boolean = true,
    error: String? = null,
    dismissLabel: String = "Отмена",
    dismissible: Boolean = true,
    alternativeLabel: String? = null,
    onAlternative: (() -> Unit)? = null,
) {
    MascotModalSheet(title, onDismiss, dismissible && !busy) {
        MascotSheetMessage(message)
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(error, fontFamily = SbSansTextRegular, fontSize = 13.sp,
                color = Color(0xFFC43535), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        MascotSheetAction(confirmLabel, onConfirm, enabled = enabled && !busy,
            destructive = destructive, busy = busy)
        if (alternativeLabel != null && onAlternative != null) {
            Spacer(Modifier.height(8.dp))
            MascotSheetAction(alternativeLabel, onAlternative, enabled = enabled && !busy, secondary = true)
        }
        Spacer(Modifier.height(8.dp))
        MascotSheetAction(dismissLabel, onDismiss, enabled = !busy, secondary = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MascotModalSheet(
    title: String,
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { if (dismissible) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { dismissible || it != SheetValue.Hidden }),
        containerColor = Color(0xFFF4F4F4),
        scrimColor = Color.Black.copy(alpha = .42f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 4.dp).size(36.dp, 4.dp)
            .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .18f))) },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontFamily = SbSansDisplayMedium, fontSize = 22.sp,
                color = FigmaInk, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
internal fun MascotSheetMessage(message: String) {
    Text(message, fontFamily = SbSansTextRegular, fontSize = 15.sp, lineHeight = 21.sp,
        color = FigmaInk.copy(alpha = .72f), textAlign = TextAlign.Center)
}

@Composable
internal fun MascotSheetAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    secondary: Boolean = false,
    destructive: Boolean = false,
    busy: Boolean = false,
) {
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                secondary -> Color.White
                destructive -> Color(0xFFC73737)
                else -> FigmaInk
            },
            contentColor = if (secondary) FigmaInk else Color.White,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(21.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(label, fontFamily = SbSansDisplayMedium, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}
