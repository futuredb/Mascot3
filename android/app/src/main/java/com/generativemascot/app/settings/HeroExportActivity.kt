package com.generativemascot.app.settings

import android.os.Bundle
import com.generativemascot.app.ui.FadeSettingsActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.generativemascot.app.data.HeroArchiveExporter
import com.generativemascot.app.data.HeroRecoveryExporter
import com.generativemascot.app.data.LightingPreparation
import com.generativemascot.app.ui.FigmaCanvas
import com.generativemascot.app.ui.FigmaInk
import com.generativemascot.app.ui.MascotSettingsHeader
import com.generativemascot.app.ui.MascotSystemBars
import com.generativemascot.app.ui.MascotTheme
import com.generativemascot.app.ui.SbSansDisplayMedium
import com.generativemascot.app.ui.SbSansTextRegular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Export is user-triggered and remains app-private externally; no network or sharing. */
class HeroExportActivity : FadeSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MascotTheme {
                MascotSystemBars(FigmaCanvas, FigmaCanvas)
                val scope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf<String?>(null) }
                Column(Modifier.fillMaxSize().background(FigmaCanvas).safeDrawingPadding()) {
                    MascotSettingsHeader("Экспорт героев", onBack = { if (!busy) finish() })
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("Сохраним героев, их анимации и готовые карты света в локальный архив. Переписка, ключи и загруженные референсы в него не войдут.",
                            fontFamily = SbSansTextRegular, fontSize = 15.sp, lineHeight = 22.sp, color = FigmaInk)
                        Box(Modifier.fillMaxWidth().height(52.dp).clip(CircleShape)
                            .background(if (busy) FigmaInk.copy(alpha = .35f) else FigmaInk)
                            .clickable(enabled = !busy) {
                                busy = true; message = null
                                scope.launch {
                                    val outcome = runCatching { withContext(Dispatchers.IO) { HeroArchiveExporter(this@HeroExportActivity).export() } }
                                    message = outcome.fold(onSuccess = { result ->
                                        "Готово: героев — ${result.heroes}, карт героев — ${result.imageMaps}, карт анимаций — ${result.videoMaps}.\n\n${result.file.name}"
                                    }, onFailure = { it.message ?: "Не удалось создать архив" })
                                    busy = false
                                }
                            }, contentAlignment = Alignment.Center) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text("Создать архив", fontFamily = SbSansDisplayMedium, fontSize = 14.sp, color = Color.White)
                        }
                        message?.let { Text(it, fontFamily = SbSansTextRegular, fontSize = 14.sp, lineHeight = 20.sp, color = FigmaInk) }
                        Text("Локальная резервная копия", fontFamily = SbSansDisplayMedium, fontSize = 18.sp, color = FigmaInk)
                        Text("Полный архив для восстановления: герои, все анимации, карты, настройки, переписка и доступные фото-референсы. API-ключи не войдут. Храни его лично, не отправляй в командную сборку.",
                            fontFamily = SbSansTextRegular, fontSize = 14.sp, lineHeight = 20.sp, color = FigmaInk)
                        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(CircleShape)
                            .background(if (busy) FigmaInk.copy(alpha = .35f) else FigmaInk)
                            .clickable(enabled = !busy) {
                                busy = true; message = null
                                scope.launch {
                                    val outcome = runCatching { withContext(Dispatchers.IO) { HeroRecoveryExporter(this@HeroExportActivity).export() } }
                                    message = outcome.fold(onSuccess = { result ->
                                        "Резервная копия проверена: героев — ${result.heroes}, файлов — ${result.files}." +
                                            (if (result.missingImages > 0) " Недоступных изображений: ${result.missingImages}." else "") + "\n\n${result.file.name}"
                                    }, onFailure = { it.message ?: "Не удалось создать резервную копию" })
                                    busy = false
                                }
                            }.padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text("Создать личную копию", fontFamily = SbSansDisplayMedium, fontSize = 13.sp, color = Color.White)
                        }
                        Text("Подготовка карт света", fontFamily = SbSansDisplayMedium, fontSize = 18.sp, color = FigmaInk)
                        Text("Можно временно оставить в очереди только Уну, Милоша и Леонко. Новые персонажи продолжат добавляться автоматически. Готовые карты и герои сохранятся.",
                            fontFamily = SbSansTextRegular, fontSize = 14.sp, lineHeight = 20.sp, color = FigmaInk)
                        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(CircleShape)
                            .background(if (busy) FigmaInk.copy(alpha = .35f) else FigmaInk)
                            .clickable(enabled = !busy) {
                                busy = true; message = null
                                scope.launch {
                                    val outcome = runCatching {
                                        LightingPreparation(this@HeroExportActivity).limitExistingTo(setOf(
                                            "716cdb43-703b-4d7e-8a79-27f03d92b99c",
                                            "cfbb8855-b0ab-4bdb-a4cc-d2360e16f174",
                                            "f9cac344-a786-466e-823c-a0951743d538",
                                        ))
                                    }
                                    message = outcome.fold(onSuccess = { "Очередь ограничена тремя персонажами." },
                                        onFailure = { it.message ?: "Не удалось ограничить очередь" })
                                    busy = false
                                }
                            }.padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text("Готовить карты только для Уны, Милоша и Леонко", fontFamily = SbSansDisplayMedium,
                                fontSize = 13.sp, lineHeight = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Color.White)
                        }
                        androidx.compose.material3.TextButton(enabled = !busy, onClick = {
                            busy = true; message = null
                            scope.launch {
                                val outcome = runCatching { LightingPreparation(this@HeroExportActivity).clearExistingLimit() }
                                message = outcome.fold(onSuccess = { "Подготовка карт для всех персонажей возобновлена." },
                                    onFailure = { it.message ?: "Не удалось возобновить очередь" })
                                busy = false
                            }
                        }) { Text("Вернуть подготовку для всех", color = FigmaInk) }
                    }
                }
            }
        }
    }
}
