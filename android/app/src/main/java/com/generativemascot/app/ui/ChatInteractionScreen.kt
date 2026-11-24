package com.generativemascot.app.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private class InteractionBridge(private val select: (Int) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    @JavascriptInterface fun select(index: Int, x: Double, y: Double) {
        if (index in 0..3 && x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0)
            handler.post { select(index) }
    }
}

internal val interactionDescriptions = listOf("По запросу подготовлю готовое решение,\nа выполнять действия будете вы.",
    "Сам добавляю встречи в календарь\nи ставлю напоминание за час.",
    "Только отвечаю на вопросы и объясняю,\nкак сделать всё самостоятельно.",
    "Замечаю важное, напоминаю и советую.\nРешения и действия остаются за вами.")
internal val interactionFieldTitles = listOf("Готовлю решения\nкогда попросите", "На шаг\nвпереди", "Рядом, когда\nнужен", "Замечаю\nи подсказываю")
internal val interactionDisplayTitles = listOf("Готовлю решения, когда попросишь", "На шаг впереди", "Рядом, когда нужен", "Замечаю и подсказываю")

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun InteractionPersonalityField(choice: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val select by rememberUpdatedState(onSelect)
    val initialIndex = remember { choice.coerceIn(0, 3) }
    var fieldView by remember { mutableStateOf<WebView?>(null) }
    Box(modifier.clip(RoundedCornerShape(28.dp))) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
            WebView(context).apply {
                fieldView = this
                setBackgroundColor(android.graphics.Color.rgb(234, 235, 236))
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                addOnLayoutChangeListener { view, _, top, _, bottom, _, oldTop, _, oldBottom ->
                    if (bottom - top != oldBottom - oldTop && view is WebView) {
                        val height = (view.height / view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                        view.evaluateJavascript("document.documentElement?.style.setProperty('--mascot-field-height','${height}px')", null)
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.post {
                            val height = (view.height / view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                            view.evaluateJavascript("""(function(){
                              document.documentElement.style.setProperty('--mascot-field-height','${height}px');
                              const style=document.createElement('style');
                              style.textContent='html,body,#surface,#content{height:var(--mascot-field-height)!important;width:100%!important;margin:0!important;padding:0!important;overflow:hidden!important} #characterTitle,.intro,.axis,.selection{display:none!important} #content{max-width:none!important} .matrix-wrap{width:100%!important;max-width:none!important;height:100%!important;margin:0!important} #field{width:100%!important;height:100%!important;aspect-ratio:auto!important}';
                              document.head.append(style);
                            })();""".trimIndent(), null)
                            view.requestLayout(); view.invalidate()
                        }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                }
                addJavascriptInterface(InteractionBridge { select(it) }, "MascotPersonality")
                loadUrl("file:///android_asset/personality/index.html?selection=$initialIndex")
            }
        }, onRelease = { view -> fieldView = null; view.removeJavascriptInterface("MascotPersonality"); view.destroy() })
        Text(interactionFieldTitles[choice.coerceIn(0, 3)], modifier = Modifier.align(Alignment.Center), fontFamily = SbSansDisplayMedium,
            fontSize = 22.sp, lineHeight = 25.sp, textAlign = TextAlign.Center, color = Color.White)
        // Consume the gesture in Compose before the parent scroll can claim it.
        // WebView remains responsible for drawing the animated field.
        Box(Modifier.matchParentSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun update(x: Float, y: Float) {
                    val nx = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val ny = (y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    fieldView?.evaluateJavascript("window.setPersonalityPoint && window.setPersonalityPoint($nx,$ny)", null)
                }
                update(down.position.x, down.position.y)
                down.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    update(change.position.x, change.position.y)
                    change.consume()
                    if (!change.pressed) break
                }
            }
        })
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ChatInteractionScreen(selectedIndex: Int,
    calendarEnabled: Boolean, onCalendarChange: (Boolean) -> Unit,
    profanityEnabled: Boolean, onProfanityChange: (Boolean) -> Unit,
    overlayStreamEnabled: Boolean,
    streamEnabled: Boolean,
    onSave: (Int, Boolean, Boolean) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    MascotSystemBars(FigmaCanvas, FigmaCanvas)
    var choice by remember { mutableIntStateOf(selectedIndex.coerceIn(0, 3)) }
    var overlayStreamChoice by remember { mutableStateOf(overlayStreamEnabled) }
    var streamChoice by remember { mutableStateOf(streamEnabled) }
    val regular = SbSansTextRegular
    BoxWithConstraints(Modifier.fillMaxSize().background(FigmaCanvas).safeDrawingPadding()) {
        val fieldWidth = (maxWidth - 40.dp).coerceAtMost(353.dp)
        val fieldHeight = fieldWidth * (315f / 353f)
        val integrationsEnabled = choice == 1 || choice == 3
        Column(Modifier.fillMaxSize().padding(top = 113.dp, start = 20.dp, end = 20.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(fieldWidth).height(fieldHeight)) {
                InteractionPersonalityField(choice, { choice = it }, Modifier.fillMaxSize())
                Text("Готовлю решения", modifier = Modifier.align(Alignment.TopCenter).padding(top = 15.dp),
                    fontFamily = SbSansTextMedium, fontSize = 11.sp, color = Color.White.copy(alpha = .55f))
                Text("Проактивно", modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                    .graphicsLayer { rotationZ = -90f }, fontFamily = SbSansTextMedium,
                    fontSize = 11.sp, maxLines = 1, color = Color.White.copy(alpha = .55f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(interactionDisplayTitles[choice], fontFamily = SbSansDisplayMedium,
                    fontSize = 18.sp, lineHeight = 21.6.sp, letterSpacing = (-0.45).sp, color = Color.Black)
                Text(interactionDescriptions[choice].replace('\n', ' '), fontFamily = regular,
                    fontSize = 14.sp, lineHeight = 19.6.sp, letterSpacing = (-0.35).sp, color = Color.Black)
            }
            HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 10.dp),
                color = Color.Black.copy(alpha = .08f))
            ChatCapabilitiesCard(calendarEnabled, onCalendarChange, profanityEnabled, onProfanityChange,
                controlsEnabled = integrationsEnabled)
            HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 10.dp),
                color = Color.Black.copy(alpha = .08f))
            Row(Modifier.fillMaxWidth().toggleable(value = streamChoice, role = Role.Switch,
                onValueChange = { streamChoice = it }).padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Потоковая анимация в чате", fontFamily = SbSansDisplayMedium, fontSize = 15.sp, color = FigmaInk)
                    Text("При выключении поток в чате отключается и токены на него не расходуются",
                        fontFamily = SbSansTextMedium, fontSize = 12.sp, lineHeight = 16.sp, color = FigmaInk.copy(alpha = .55f))
                }
                Spacer(Modifier.width(12.dp))
                MascotSwitchTrack(streamChoice)
            }
            if (!com.generativemascot.app.BuildConfig.USE_OMNI) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color.Black.copy(alpha = .08f))
                Row(Modifier.fillMaxWidth().toggleable(value = overlayStreamChoice, role = Role.Switch,
                    onValueChange = { overlayStreamChoice = it }).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Потоковая анимация в оверлее", fontFamily = SbSansDisplayMedium, fontSize = 15.sp, color = FigmaInk)
                        Text("Персонаж оживает в оверлее; время соединения оплачивается", fontFamily = SbSansTextMedium,
                            fontSize = 12.sp, lineHeight = 16.sp, color = FigmaInk.copy(alpha = .55f))
                    }
                    Spacer(Modifier.width(12.dp))
                    MascotSwitchTrack(overlayStreamChoice)
                }
            }
            Spacer(Modifier.height(110.dp))
        }
        MascotSettingsHeader("Поведение и доступы", onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter).background(FigmaCanvas))
        CommunicationSaveButton(
            onClick = { onSave(choice, overlayStreamChoice, streamChoice) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 33.dp),
        )
    }
}
