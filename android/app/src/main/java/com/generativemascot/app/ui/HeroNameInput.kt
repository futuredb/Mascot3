package com.generativemascot.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A heading that becomes editable, without a field outline or a separate surface. */
@Composable
internal fun HeroNameInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(value, selection = TextRange(0, value.length)))
    }
    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(value, selection = TextRange(value.length))
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val save = {
        if (value.isNotBlank()) {
            focusManager.clearFocus()
            keyboard?.hide()
            onSave()
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Balance the save button so the name stays centered like the normal heading.
        Spacer(Modifier.width(48.dp))
        BasicTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                onValueChange(it.text)
            },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                .focusRequester(focusRequester)
                .testTag("hero-name-input")
                .semantics { contentDescription = "Имя героя" },
            singleLine = true,
            textStyle = TextStyle(color = FigmaInk, fontSize = 28.sp,
                fontFamily = RubikOne, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(FigmaInk),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { save() }),
            decorationBox = { innerTextField ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (fieldValue.text.isEmpty()) {
                        Text("Имя героя", color = FigmaInk.copy(alpha = .35f), fontSize = 20.sp,
                            maxLines = 1, textAlign = TextAlign.Center)
                    }
                    innerTextField()
                }
            },
        )
        IconButton(onClick = { save() }, enabled = value.isNotBlank(), modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Check, contentDescription = "Сохранить имя",
                tint = FigmaInk.copy(alpha = if (value.isNotBlank()) .8f else .24f),
                modifier = Modifier.size(22.dp))
        }
    }
}
