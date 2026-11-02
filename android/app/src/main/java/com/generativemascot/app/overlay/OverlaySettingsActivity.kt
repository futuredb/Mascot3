package com.generativemascot.app.overlay

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.generativemascot.app.MascotApp
import com.generativemascot.app.ui.MascotTheme
import com.generativemascot.app.ui.MascotTopBar
import com.generativemascot.app.ui.MascotSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
class OverlaySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MascotApp
        setContent { MascotTheme {
            val status by MascotOverlayService.status.collectAsState()
            var permissions by remember { mutableStateOf(OverlayPermissionState.read(this)) }
            var showPermissions by androidx.compose.runtime.saveable.rememberSaveable {
                mutableStateOf(!permissions.complete)
            }
            var startAfterPermissions by remember { mutableStateOf(false) }
            var heroId by remember { mutableStateOf<String?>(null) }
            var heroName by remember { mutableStateOf("Герой") }
            var art by remember { mutableStateOf<OverlayArtwork?>(null) }
            var message by remember { mutableStateOf<String?>(null) }
            var heroScale by remember { mutableFloatStateOf(OverlaySize.read(this)) }
            var showRules by remember { mutableStateOf(false) }
            var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
            LaunchedEffect(Unit) {
                val id = intent.getStringExtra(MascotOverlayService.EXTRA_HERO) ?: app.session.acceptedMascotId()
                if (id == null || !id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) {
                    message = "Сначала выберите сохранённого героя."; return@LaunchedEffect
                }
                heroId = id
                var decoded: OverlayArtwork? = null
                try {
                    withContext(Dispatchers.IO) {
                        heroName = app.heroStore.mascotName(id) ?: "Герой"
                        decoded = loadOverlayArtwork(this@OverlaySettingsActivity, id)
                    }
                    art = decoded; decoded = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { message = "Дождитесь сохранения изображения героя." }
                finally { decoded?.recycle() }
            }
            DisposableEffect(art) { val owned = art; onDispose { owned?.recycle() } }
            DisposableEffect(Unit) {
                val preferences = getSharedPreferences(OverlaySize.PREFERENCES, MODE_PRIVATE)
                val sizeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == OverlaySize.KEY) heroScale = OverlaySize.read(this@OverlaySettingsActivity)
                }
                preferences.registerOnSharedPreferenceChangeListener(sizeListener)
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        permissions = OverlayPermissionState.read(this@OverlaySettingsActivity)
                        heroScale = OverlaySize.read(this@OverlaySettingsActivity)
                    }
                    resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                }
                lifecycle.addObserver(observer)
                onDispose {
                    lifecycle.removeObserver(observer)
                    preferences.unregisterOnSharedPreferenceChangeListener(sizeListener)
                }
            }
            fun startHero() {
                heroId?.let { id ->
                    runCatching { MascotOverlayService.start(this@OverlaySettingsActivity, id) }
                        .onFailure { message = "Не удалось включить героя. Попробуйте ещё раз." }
                        .onSuccess { message = null }
                }
            }
            if (showPermissions) OverlayPermissionsDialog(this,
                onDismiss = {
                    showPermissions = false; startAfterPermissions = false
                    permissions = OverlayPermissionState.read(this)
                    OverlayPermissionIntro.seen(this)
                },
                onContinue = if (startAfterPermissions) ({
                    showPermissions = false; startAfterPermissions = false
                    permissions = OverlayPermissionState.read(this)
                    OverlayPermissionIntro.seen(this)
                    startHero()
                }) else null)
            MascotSystemBars(Color(0xFFF7F6FB))
            Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F6FB)) {
                Column(Modifier.safeDrawingPadding()) {
                    MascotTopBar("Герой рядом", onBack = { finish() }, backArrow = true)
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(heroName, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            Text(if (status.running) "Поверх приложений" else "Выключен", color = Color(0xFF696575), fontSize = 14.sp)
                        }
                        Switch(checked = status.running, enabled = art != null && !status.loading, onCheckedChange = { enabled ->
                            if (!enabled) MascotOverlayService.stop(this@OverlaySettingsActivity)
                            else {
                                permissions = OverlayPermissionState.read(this@OverlaySettingsActivity)
                                if (permissions.complete) startHero()
                                else { startAfterPermissions = true; showPermissions = true }
                            }
                        })
                    }
                    if (!permissions.complete) OutlinedButton(onClick = { startAfterPermissions = false; showPermissions = true },
                        modifier = Modifier.fillMaxWidth()) { Text("Настроить разрешения") }
                    Box(Modifier.fillMaxWidth().heightIn(min = 360.dp).clip(RoundedCornerShape(28.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFFFDAAF), Color(0xFFE0CDFF), Color(0xFFABDAD9))))) {
                        art?.let { artwork ->
                            AndroidView(factory = { OverlayPreviewView(it, artwork) },
                                update = { it.heroScale = heroScale; it.animationEnabled = resumed && !status.running },
                                modifier = Modifier.fillMaxWidth().wrapContentHeight())
                        }
                        Text("ПРИМЕР", Modifier.padding(20.dp), color = Color(0xFF5D5471), fontSize = 10.sp)
                    }
                    Column {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Размер · ${(heroScale * 100).roundToInt()}%", fontWeight = FontWeight.Medium)
                            TextButton(onClick = {
                                heroScale = 1f; OverlaySize.save(this@OverlaySettingsActivity, heroScale)
                            }, enabled = heroScale != 1f) { Text("Сбросить") }
                        }
                        Slider(value = heroScale, onValueChange = {
                            heroScale = OverlaySize.normalize(it)
                            OverlaySize.save(this@OverlaySettingsActivity, heroScale)
                        }, valueRange = OverlaySize.MIN..OverlaySize.MAX, steps = 15,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Размер плавающего героя" })
                    }
                    Text("Перетаскивайте героя. Меняйте размер двумя пальцами.", color = Color(0xFF696575), fontSize = 13.sp)
                    TextButton(onClick = { showRules = !showRules }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showRules) "Где показывать  ⌃" else "Где показывать  ›")
                    }
                    if (showRules) OverlayAppRulesSection(resumed, onPermissions = {
                        startAfterPermissions = false; showPermissions = true
                    })
                    (message ?: status.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
                    }
                }
            }
        } }
    }
}
