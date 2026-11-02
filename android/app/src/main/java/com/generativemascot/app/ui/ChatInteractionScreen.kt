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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.generativemascot.app.R
import com.generativemascot.app.data.HeroLocalStore

private class InteractionBridge(private val select: (Int) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    @JavascriptInterface fun select(index: Int, x: Double, y: Double) {
        if (index in 0..3 && x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0)
            handler.post { select(index) }
    }
}

private val headings = listOf("ГОТОВЛЮ РЕШЕНИЯ,\nКОГДА ПОПРОСИТЕ", "НА ШАГ ВПЕРЕДИ", "РЯДОМ, КОГДА НУЖЕН", "ЗАМЕЧАЮ И\nПОДСКАЗЫВАЮ")
private val descriptions = listOf("Помогу разобраться в сложном\nи предложу готовый следующий шаг.",
    "Сам замечаю важное и готовлю решения,\nчтобы вам оставалось только выбрать.",
    "Отвечу на ваш вопрос и подскажу путь.\nРешение всегда остаётся за вами.",
    "Вовремя поделюсь идеей или советом.\nВы сами выбираете, что делать дальше.")
private val fieldTitles = listOf("Готовлю решения\nкогда попросите", "На шаг\nвпереди", "Рядом, когда\nнужен", "Замечаю\nи подсказываю")

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ChatInteractionScreen(mascotId: String, heroName: String, previewUrl: String?, selectedIndex: Int,
    onSave: (Int) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    MascotSystemBars(FigmaCanvas, FigmaCanvas)
    var choice by remember { mutableIntStateOf(selectedIndex.coerceIn(0, 3)) }
    val select by rememberUpdatedState<(Int) -> Unit>({ choice = it })
    val initialIndex = remember { choice }
    val image = remember(mascotId) { HeroLocalStore.current?.baseFile(mascotId) }
    val extended = remember { FontFamily(Font(R.font.sb_sans_display_extended_semibold)) }
    val regular = SbSansTextRegular
    BoxWithConstraints(Modifier.fillMaxSize().background(FigmaCanvas).safeDrawingPadding()) {
        val w = maxWidth
        val h = maxHeight
        val fieldWidth = (w - 78.dp).coerceAtMost(315.dp)
        val fieldHeight = fieldWidth * (281f / 315f)
        MascotChatIdentity(heroName, Modifier.align(Alignment.TopStart).offset(x = 20.dp, y = MascotChatIdentityTop).size(MascotChatIdentitySize)) {
            AsyncImage(model = image ?: previewUrl, contentDescription = heroName, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.align(Alignment.TopCenter).offset(y = h * .265f).fillMaxWidth().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(headings[choice], fontFamily = extended, fontSize = 20.sp, lineHeight = 23.sp,
                textAlign = TextAlign.Center, color = Color.Black)
            Spacer(Modifier.height(4.dp))
            Text(descriptions[choice], fontFamily = regular, fontSize = 14.sp, lineHeight = 19.6.sp,
                color = Color.Black.copy(alpha = .5f), textAlign = TextAlign.Center)
        }
        Box(Modifier.align(Alignment.TopCenter).offset(y = h * .449f).width(fieldWidth).height(fieldHeight)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.rgb(234, 235, 236))
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.post {
                                val height = (view.height / view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                                view.evaluateJavascript("""(function(){
                                  const style=document.createElement('style');
                                  style.textContent='html,body,#surface,#content{height:${height}px!important;width:100%!important;margin:0!important;padding:0!important;overflow:hidden!important} #characterTitle,.intro,.axis,.selection{display:none!important} #content{max-width:none!important} .matrix-wrap{width:100%!important;max-width:none!important;height:100%!important;margin:0!important} #field{width:100%!important;height:100%!important;aspect-ratio:auto!important}';
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
            }, onRelease = { view -> view.removeJavascriptInterface("MascotPersonality"); view.destroy() })
            Text(fieldTitles[choice], modifier = Modifier.align(Alignment.Center), fontFamily = SbSansDisplayMedium,
                fontSize = 22.sp, lineHeight = 25.sp, textAlign = TextAlign.Center, color = Color.White)
        }
        Text("Готовлю решения", modifier = Modifier.align(Alignment.TopCenter).offset(x = w * .19f, y = h * .42f),
            fontFamily = SbSansTextMedium, fontSize = 11.sp, color = Color.Black.copy(alpha = .32f))
        Text("Подсказываю", modifier = Modifier.align(Alignment.TopCenter).offset(x = -w * .20f, y = h * .449f + fieldHeight + 8.dp),
            fontFamily = SbSansTextMedium, fontSize = 11.sp, color = Color.Black.copy(alpha = .32f))
        val fieldLeft = (w - fieldWidth) / 2
        val fieldTop = h * .449f
        Box(Modifier.align(Alignment.TopStart).offset(x = fieldLeft - 24.dp, y = fieldTop + fieldHeight * .67f - 42.dp)
            .width(16.dp).height(84.dp), contentAlignment = Alignment.Center) {
            Text("По запросу", modifier = Modifier.wrapContentSize(unbounded = true).graphicsLayer { rotationZ = -90f },
                fontFamily = SbSansTextMedium, fontSize = 11.sp, maxLines = 1, softWrap = false, color = Color.Black.copy(alpha = .32f))
        }
        Box(Modifier.align(Alignment.TopStart).offset(x = fieldLeft + fieldWidth + 8.dp, y = fieldTop + fieldHeight * .25f - 42.dp)
            .width(16.dp).height(84.dp), contentAlignment = Alignment.Center) {
            Text("Проактивно", modifier = Modifier.wrapContentSize(unbounded = true).graphicsLayer { rotationZ = -90f },
                fontFamily = SbSansTextMedium, fontSize = 11.sp, maxLines = 1, softWrap = false, color = Color.Black.copy(alpha = .32f))
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 33.dp).clip(CircleShape).background(FigmaInk)
            .clickable { onSave(choice) }.padding(horizontal = 28.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
            Text("СОХРАНИТЬ", fontFamily = SbSansDisplayMedium, fontSize = 13.4.sp, color = Color.White)
        }
    }
}
