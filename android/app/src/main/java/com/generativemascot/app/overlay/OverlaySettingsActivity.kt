package com.generativemascot.app.overlay

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import com.generativemascot.app.ui.FadeSettingsActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import com.generativemascot.app.R
import com.generativemascot.app.ui.SbSansDisplayMedium
import com.generativemascot.app.ui.SbSansTextMedium
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.generativemascot.app.MascotApp
import com.generativemascot.app.ui.MascotTheme
import com.generativemascot.app.ui.MascotSystemBars
import com.generativemascot.app.ui.MascotSettingsHeader
import com.generativemascot.app.ui.HomeFigmaIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
class OverlaySettingsActivity : FadeSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MascotApp
        setContent { MascotTheme {
            val status by MascotOverlayService.status.collectAsState()
            var permissions by remember { mutableStateOf(OverlayPermissionState.read(this)) }
            var showPermissions by androidx.compose.runtime.saveable.rememberSaveable {
                mutableStateOf(false)
            }
            var startAfterPermissions by remember { mutableStateOf(false) }
            var heroId by remember { mutableStateOf<String?>(null) }
            var heroName by remember { mutableStateOf("Герой") }
            var art by remember { mutableStateOf<OverlayArtwork?>(null) }
            var message by remember { mutableStateOf<String?>(null) }
            var heroScale by remember { mutableFloatStateOf(OverlaySize.read(this)) }

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
                        decoded = loadOverlayArtwork(this@OverlaySettingsActivity, id, fast = true)
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
            MascotSystemBars(Color(0xFFEAEBEC), Color(0xFFEAEBEC))
            Surface(Modifier.fillMaxSize(), color = Color(0xFFEAEBEC)) {
                Column(Modifier.safeDrawingPadding()) {
                    MascotSettingsHeader("Виджет на главном", onBack = { finish() })
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OverlaySettingsRow("Виджет на главном", "Добавить", onClick = {
                        lifecycleScope.launch {
                            com.generativemascot.app.widget.requestPinMascotWidget(this@OverlaySettingsActivity, heroId)
                        }
                    })
                    OverlaySettingsRow("Поверх окон", if (status.running) "Включено" else "Включить", checked = status.running,
                        enabled = art != null && !status.loading, onClick = {
                            if (status.running) MascotOverlayService.stop(this@OverlaySettingsActivity)
                            else {
                                permissions = OverlayPermissionState.read(this@OverlaySettingsActivity)
                                if (permissions.complete) startHero()
                                else { startAfterPermissions = true; showPermissions = true }
                            }
                        })
                    Box(Modifier.padding(top = 14.dp).align(Alignment.CenterHorizontally).widthIn(max = 329.dp).fillMaxWidth().aspectRatio(309f / 306f)
                        .clip(RoundedCornerShape(30.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFFFDAAF), Color(0xFFE0CDFF), Color(0xFFABDAD9))))) {
                        if (art == null && message == null) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp),
                                color = Color.Black.copy(alpha = .55f), strokeWidth = 2.dp)
                        }
                        art?.let { artwork ->
                            AndroidView(factory = { OverlayPreviewView(it, artwork) },
                                update = { it.heroScale = heroScale; it.animationEnabled = resumed && !status.running },
                                modifier = Modifier.fillMaxSize())
                        }
                        Text("ПРИМЕР", Modifier.padding(16.dp), color = Color.Black.copy(alpha = .38f), fontSize = 10.sp)
                    }
                    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp)) {
                        Text("Размер ${(heroScale * 100).roundToInt()}%", fontFamily = SbSansDisplayMedium, fontSize = 18.sp, color = Color.Black)
                        OverlayScaleSlider(heroScale, onValueChange = {
                            heroScale = OverlaySize.normalize(it)
                            OverlaySize.save(this@OverlaySettingsActivity, heroScale)
                        })
                    }
                    HorizontalDivider(Modifier.padding(top = 22.dp, bottom = 18.dp),
                        color = Color.Black.copy(alpha = .08f))
                    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Где показывать оверлей", fontFamily = SbSansDisplayMedium,
                            fontSize = 18.sp, color = Color.Black)
                        Text("Настрой, в каких приложениях герой будет появляться.",
                            fontFamily = SbSansTextMedium, fontSize = 13.sp,
                            lineHeight = 18.sp, color = Color.Black.copy(alpha = .5f))
                    }
                    Spacer(Modifier.height(10.dp))
                    OverlayAppRulesSection(resumed, showTitle = false, onPermissions = {
                        startAfterPermissions = false
                        showPermissions = true
                    })
                    (message ?: status.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
                    }
                }
            }
        } }
    }
}

@Composable
private fun OverlayScaleSlider(value: Float, onValueChange: (Float) -> Unit) {
    val update by rememberUpdatedState(onValueChange)
    var pressed by remember { mutableStateOf(false) }
    val thumbScale by animateFloatAsState(if (pressed) .94f else 1f,
        animationSpec = androidx.compose.animation.core.tween(120), label = "overlay size thumb")
    val fraction = ((value - OverlaySize.MIN) / (OverlaySize.MAX - OverlaySize.MIN)).coerceIn(0f, 1f)
    BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp)
        .progressSemantics(value, OverlaySize.MIN..OverlaySize.MAX)
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                pressed = true
                try {
                    fun apply(x: Float) {
                        val next = OverlaySize.MIN + (x / size.width.coerceAtLeast(1)) * (OverlaySize.MAX - OverlaySize.MIN)
                        update(next)
                    }
                    apply(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        apply(change.position.x)
                        change.consume()
                        if (!change.pressed) break
                    }
                } finally {
                    pressed = false
                }
            }
        }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = .12f)))
        Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(CircleShape).background(Color.Black))
        val travel = maxWidth - 28.dp
        Box(Modifier.offset(x = travel * fraction).size(28.dp)
            .shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White)
            .border(1.dp, Color.Black.copy(alpha = .08f), CircleShape)
            .graphicsLayer { scaleX = thumbScale; scaleY = thumbScale })
    }
}

@Composable
private fun OverlaySettingsRow(title: String, subtitle: String, onClick: () -> Unit,
    checked: Boolean? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().height(73.dp).border(1.dp, Color(0xFFE0E1E5), RoundedCornerShape(30.dp))
        .clip(RoundedCornerShape(30.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
            HomeFigmaIcon(if (checked == null) "menu-widget" else "menu-lightning", null, size = 18.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontFamily = SbSansDisplayMedium, fontSize = 15.sp, color = Color.Black)
            Text(subtitle, fontFamily = SbSansTextMedium, fontSize = 12.sp, color = Color.Black.copy(alpha = .5f))
        }
        if (checked != null) Box(Modifier.size(width = 46.dp, height = 20.dp).clip(CircleShape)
            .background(if (checked) Color.Black else Color(0xFFB3B3B6)).padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(width = 28.dp, height = 16.dp).clip(CircleShape).background(Color.White))
        }
    }
}
