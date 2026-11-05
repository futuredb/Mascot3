package com.generativemascot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
) {
    ModalBottomSheet(
        onDismissRequest = { if (!busy && dismissible) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { dismissible || it != SheetValue.Hidden }),
        containerColor = Color(0xFFF4F4F4),
        scrimColor = Color.Black.copy(alpha = .42f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 4.dp).size(36.dp, 4.dp)
            .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .18f))) },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontFamily = SbSansDisplayMedium, fontSize = 22.sp,
                color = FigmaInk, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(message, fontFamily = SbSansTextRegular, fontSize = 15.sp, lineHeight = 21.sp,
                color = FigmaInk.copy(alpha = .65f), textAlign = TextAlign.Center)
            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(error, fontFamily = SbSansTextRegular, fontSize = 13.sp,
                    color = Color(0xFFC43535), textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onConfirm, enabled = enabled && !busy, shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (destructive) Color(0xFFC73737) else FigmaInk),
                modifier = Modifier.fillMaxWidth().height(54.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(21.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(confirmLabel, fontFamily = SbSansDisplayMedium, fontSize = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDismiss, enabled = !busy, shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = FigmaInk),
                modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text(dismissLabel, fontFamily = SbSansDisplayMedium, fontSize = 16.sp)
            }
        }
    }
}
