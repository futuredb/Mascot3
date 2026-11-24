package com.generativemascot.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.generativemascot.app.R
import com.generativemascot.app.data.CharacterCommunication
import com.generativemascot.app.data.CommunicationTrait
import com.generativemascot.app.data.resolveMediaUrl
import com.generativemascot.app.worker.CALENDAR_REMINDER_PREFERENCES
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay

internal val communicationSaver = Saver<CharacterCommunication, String>(
    save = { Json.encodeToString(CharacterCommunication.serializer(), it) },
    restore = { Json.decodeFromString(CharacterCommunication.serializer(), it) },
)

private val communicationDraftsSaver = mapSaver(
    save = { drafts: Map<String, CharacterCommunication> ->
        drafts.mapValues { (_, profile) -> Json.encodeToString(CharacterCommunication.serializer(), profile) }
    },
    restore = { drafts ->
        drafts.mapValues { (_, profile) -> Json.decodeFromString(CharacterCommunication.serializer(), profile as String) }
    },
)

internal val characterSettingsBackground = Brush.verticalGradient(
    0f to Color(0xfff2f2f2), .16f to Color(0xffe9eaea), .84f to Color(0xffe9eaea), 1f to Color(0xfff2f2f2),
)

@Composable
internal fun CommunicationSliders(
    profile: CharacterCommunication, onChange: (CharacterCommunication) -> Unit, modifier: Modifier = Modifier,
    rowHeight: Dp = 48.dp, gap: Dp = 12.dp, enabled: Boolean = true, lockCountdown: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        CommunicationTrait.entries.forEach { trait ->
            val countdown = lockCountdown.takeIf { trait in moodLockedCommunicationTraits }
            CommunicationSlider(trait.title, profile.label(trait), profile[trait],
                { onChange(profile.withLevel(trait, it)) }, rowHeight, enabled && countdown == null, countdown)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunicationSlider(
    title: String, label: String, level: Int, onChange: (Int) -> Unit, height: Dp, enabled: Boolean, countdown: String?,
) {
    val shape = RoundedCornerShape(13.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).clip(shape).background(Color.White)
        .border(1.dp, Color(0xffdfdfdf), shape)) {
        BoxWithConstraints(Modifier.fillMaxSize().alpha(if (countdown == null) 1f else .46f)) {
            val inset = 11.dp
            val center = inset + (maxWidth - inset * 2) * (level / 100f)
            val fill = center + inset
            val finishesRight = level == 100
            Box(Modifier.fillMaxHeight().width(fill).clip(RoundedCornerShape(topEnd = 13.dp, bottomEnd = 13.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xffc9cbca), Color(0xffbfc0c0),
                    if (finishesRight) Color(0xffd9d9d9) else Color(0xfff1f1f1)))))
            for (position in 0..2) {
                val marker = inset + (maxWidth - inset * 2) * (position / 2f)
                Box(Modifier.align(Alignment.CenterStart).offset(x = marker - 3.dp)
                    .size(6.dp).background(Color.Black.copy(alpha = .12f), CircleShape))
            }
            Box(Modifier.align(Alignment.CenterStart).offset(x = center - 7.dp).width(14.dp)
                .height((height - 10.dp).coerceAtLeast(18.dp)).background(Color.White, CircleShape),
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(6.dp).background(Color(0xffe5e5e5), CircleShape))
            }
            Text(label, Modifier.align(Alignment.CenterStart).padding(start = 28.dp, end = if (countdown == null) 24.dp else 96.dp),
                fontFamily = SbSansTextRegular, fontSize = 12.sp, letterSpacing = 0.sp, color = Color(0xff414241),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (countdown != null) Row(
            Modifier.align(Alignment.CenterEnd).padding(end = 26.dp)
                .background(FigmaInk, CircleShape).padding(horizontal = 5.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(9.dp), tint = Color.White)
            Text(countdown, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 10.sp)
        }
        Slider(
            value = level.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = 0f..100f, steps = 1,
            enabled = enabled, modifier = Modifier.fillMaxSize().semantics {
                contentDescription = title
                stateDescription = if (countdown == null) label else "$label. Герой обижен. До разблокировки $countdown"
            },
            thumb = { Spacer(Modifier.width(22.dp)) }, track = { Spacer(Modifier.fillMaxWidth()) },
        )
    }
}

@Composable
internal fun EditCommunicationNotes(notes: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    var draft by rememberSaveable(notes) { mutableStateOf(notes) }
    MascotModalSheet("Изменить стиль общения", onDismiss = onClose) {
        OutlinedTextField(
            value = draft,
            onValueChange = { if (it.length <= 500) draft = it },
            label = { Text("Своими словами", fontFamily = SbSansTextRegular) },
            textStyle = TextStyle(fontFamily = SbSansTextRegular, fontSize = 16.sp, lineHeight = 22.sp),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                focusedBorderColor = FigmaInk, unfocusedBorderColor = Color(0xFFB9BABA),
            ),
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 5,
        )
        Spacer(Modifier.height(20.dp))
        MascotSheetAction("Сохранить", onClick = { onSave(draft.trim()); onClose() })
        Spacer(Modifier.height(8.dp))
        MascotSheetAction("Отмена", onClick = onClose, secondary = true)
    }
}

@Composable
internal fun CreationCommunicationPage(
    preview: Int, profile: CharacterCommunication, onChange: (CharacterCommunication) -> Unit,
    onBack: () -> Unit, onSave: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    MascotSystemBars(Color(0xfff2f2f2))
    Column(Modifier.fillMaxSize().background(characterSettingsBackground).verticalScroll(rememberScrollState())
        .statusBarsPadding().navigationBarsPadding().padding(bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().padding(start = 13.dp, top = 16.dp)) {
            MascotTopAction(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack)
        }
        Spacer(Modifier.height(32.dp))
        val portraitFilter = remember(preview) {
            // These grayscale illustrations have a light matte baked into the bitmap.
            val matte = if (preview == R.drawable.creation_communication_creature) 235f else 231f
            val scale = 255f / matte
            ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                -.2126f * scale, -.7152f * scale, -.0722f * scale, 0f, 255f,
            )))
        }
        Image(painterResource(preview), null, Modifier.size(273.dp), contentScale = ContentScale.Fit,
            colorFilter = portraitFilter)
        Spacer(Modifier.height(42.dp))
        val heading = profile.notes.ifBlank { profile.summary() }
        Text(heading.ifBlank { "Как с тобой общаться" }, Modifier.padding(horizontal = 30.dp),
            fontFamily = SbSansDisplayMedium, fontSize = 20.sp, lineHeight = 24.sp, maxLines = 3,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = if (heading.isBlank()) Color(0xff767676) else FigmaInk)
        Text("стиль общения", Modifier.padding(top = 6.dp), fontFamily = SbSansTextRegular, fontSize = 12.sp,
            color = Color(0xff727272))
        CommunicationSliders(profile, onChange, Modifier.padding(top = 24.dp).widthIn(max = 400.dp)
            .fillMaxWidth(.84f), gap = 16.dp)
        Row(Modifier.padding(top = 64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            MascotSmallCircleAction(Icons.Rounded.Edit, "Добавить описание", { editing = true }, FigmaInk, Color.White)
            CommunicationSaveButton(onSave)
        }
    }
    if (editing) EditCommunicationNotes(profile.notes, { onChange(profile.copy(notes = it)) }, { editing = false })
}

private val communicationSaveButtonHeight = 50.dp
private val settingsPortraitWidth = 275.dp
private val settingsPortraitHeight = 290.dp

private data class CharacterSettingsPreview(val name: String, val portrait: Painter? = null)

@Composable
private fun rememberCharacterSettingsPreview(heroId: String, name: String, portrait: String?): CharacterSettingsPreview {
    val context = LocalContext.current
    val density = LocalDensity.current
    val model = resolveMediaUrl(portrait)
    val request = remember(context, model, density) {
        ImageRequest.Builder(context).data(model).size(
            with(density) { settingsPortraitWidth.roundToPx() },
            with(density) { settingsPortraitHeight.roundToPx() },
        ).build()
    }
    val imageState = rememberAsyncImagePainter(request, contentScale = ContentScale.Fit).state
    var visible by remember { mutableStateOf(CharacterSettingsPreview(name)) }
    LaunchedEffect(heroId, name, request, imageState) {
        when {
            model == null -> visible = CharacterSettingsPreview(name)
            imageState is AsyncImagePainter.State.Success && imageState.result.request.data == request.data ->
                visible = CharacterSettingsPreview(name, imageState.painter)
            imageState is AsyncImagePainter.State.Error && imageState.result.request.data == request.data ->
                visible = CharacterSettingsPreview(name)
        }
    }
    return visible
}

@Composable
internal fun CommunicationSaveButton(onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = communicationSaveButtonHeight),
        colors = ButtonDefaults.buttonColors(containerColor = FigmaInk),
        contentPadding = PaddingValues(horizontal = 28.dp)) {
        Text("СОХРАНИТЬ", fontFamily = SbSansDisplayBold, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun CharacterCommunicationSettings(
    heroId: String, name: String, portrait: String?, initial: CharacterCommunication?, heroes: List<HeroLibraryItem>,
    stripState: LazyListState,
    onClose: () -> Unit, onSave: (CharacterCommunication) -> Boolean, onRename: (String) -> Unit,
    onOpenAnimations: () -> Unit, onNew: () -> Unit, onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val savedProfile = initial ?: remember(context, heroId) { defaultCommunicationProfile(context, heroId) }
    var drafts by rememberSaveable(stateSaver = communicationDraftsSaver) {
        mutableStateOf(emptyMap<String, CharacterCommunication>())
    }
    val profile = drafts[heroId] ?: savedProfile
    val updateProfile: (CharacterCommunication) -> Unit = { drafts = drafts + (heroId to it) }
    val lockExpiry = rememberCommunicationLockExpiry(heroId)
    val countdown by produceState<String?>(null, lockExpiry) {
        do {
            val now = System.currentTimeMillis()
            value = communicationLockCountdown(lockExpiry, now)
            if (value == null) break
            delay(((lockExpiry - now - 1) % 1000) + 1)
        } while (true)
    }
    LaunchedEffect(heroId, lockExpiry, savedProfile) {
        if (lockExpiry > 0 && heroId in drafts) updateProfile(profile.keepMoodLockedLevels(savedProfile))
    }
    var editingName by rememberSaveable(heroId) { mutableStateOf(false) }
    var nameDraft by rememberSaveable(heroId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberScrollState()
    val confirmName = {
        val requested = nameDraft.text.trim()
        if (requested.isNotEmpty()) {
            if (requested != name) onRename(requested)
            editingName = false
            keyboard?.hide()
        }
    }
    var editingNotes by rememberSaveable(heroId) { mutableStateOf(false) }
    var error by rememberSaveable(heroId) { mutableStateOf(false) }
    val preview = rememberCharacterSettingsPreview(heroId, name, portrait)
    val headingHeight = with(LocalDensity.current) { 60.sp.toDp() }
    MascotSystemBars(Color(0xfff2f2f2))
    BackHandler(onBack = onClose)
    val saveButtonPadding = 12.dp
    Box(Modifier.fillMaxSize().background(characterSettingsBackground).navigationBarsPadding().imePadding()) {
        if (editingName) {
            HeroNameEditScene(nameDraft, portrait, { nameDraft = it }, confirmName,
                onCancel = { editingName = false })
        } else Column(
            Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding()
                .padding(bottom = communicationSaveButtonHeight + saveButtonPadding * 2 + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                MascotTopAction(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onClose)
                MascotTopAction(Icons.Rounded.GridView, "Библиотека анимаций", onOpenAnimations)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(headingHeight).padding(horizontal = 20.dp)) {
                val width = constraints.maxWidth
                MascotTransition(preview.name.uppercase(), Modifier.fillMaxSize()) { heading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(heading,
                            style = TextStyle(brush = Brush.verticalGradient(listOf(FigmaInk, Color(0xffb9baba))),
                                fontFamily = SbSansDisplayExtendedSemibold, fontWeight = FontWeight.SemiBold,
                                fontSize = homeHeadingFontSize(heading, width, maxSize = 53f),
                                textAlign = TextAlign.Center),
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                    }
                }
            }
            MascotSmallCircleAction(Icons.Rounded.Edit, "Изменить имя", {
                nameDraft = heroNameDraft(name)
                editingName = true
            }, FigmaInk, Color.White, modifier = Modifier.size(40.dp))
            val portraitOffset = rememberMascotPortraitBob()
            MascotTransition(preview, Modifier.padding(top = 12.dp)
                .size(settingsPortraitWidth, settingsPortraitHeight)
                .graphicsLayer { translationY = portraitOffset.value.dp.toPx() },
            ) { displayed ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    displayed.portrait?.let { Image(it, displayed.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
            }
            val avatars = remember(heroes, heroId, name, portrait) {
                if (heroes.any { it.id == heroId }) heroes
                else listOf(HeroLibraryItem(heroId, name, baseStill = portrait)) + heroes
            }
            Row(Modifier.padding(top = 13.dp, bottom = 44.dp).widthIn(max = 380.dp).fillMaxWidth(.86f),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                MascotSmallCircleAction(Icons.Rounded.Add, "Новый персонаж", onNew, Color.White.copy(alpha = .5f),
                    FigmaInk, modifier = Modifier.size(50.dp))
                LazyRow(state = stripState, modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(avatars, key = { it.id }) { hero ->
                        val active = hero.id == heroId
                        val label = if (active) name else hero.name ?: "Персонаж"
                        val thumbnail = hero.baseStill ?: hero.baseFrames.firstOrNull()
                        val avatarBackground = if (active) Brush.verticalGradient(listOf(Color(0xfffbc293), Color(0xffecdfd9)))
                            else androidx.compose.ui.graphics.SolidColor(Color(0xffdee2e2))
                        Box(Modifier.size(54.dp).clip(CircleShape).background(avatarBackground)
                            .semantics { selected = active }
                            .then(if (active) Modifier else Modifier.mascotClickable(onClickLabel = label) { onSelect(hero.id) }),
                            contentAlignment = Alignment.Center) {
                            AsyncImage(resolveMediaUrl(if (active) portrait ?: thumbnail else thumbnail), label,
                                Modifier.size(48.dp).graphicsLayer {
                                    if (active) translationY = portraitOffset.value.dp.toPx() else translationY = 0f
                                },
                                contentScale = ContentScale.Fit)
                        }
                    }
                }
            }
            SettingsCard("Стиль общения", action = { editingNotes = true }) {
                if (profile.notes.isNotBlank()) {
                    Text(profile.notes, color = FigmaInk, fontFamily = SbSansTextRegular,
                        fontSize = 14.sp, lineHeight = 20.sp)
                }
                CommunicationSliders(profile, updateProfile, lockCountdown = countdown)
            }
            Spacer(Modifier.height(28.dp))
            CalendarSettingsCards(heroId)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .padding(vertical = saveButtonPadding, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error) Text("Не удалось сохранить стиль общения", color = Color(0xff9e3030), fontSize = 13.sp)
            CommunicationSaveButton(enabled = !editingName || nameDraft.text.isNotBlank(), onClick = {
                if (editingName) confirmName()
                error = !onSave(profile)
            })
        }
    }
    if (editingNotes) EditCommunicationNotes(profile.notes, { updateProfile(profile.copy(notes = it)) }, { editingNotes = false })
}

@Composable
private fun CalendarSettingsCards(heroId: String) {
    val context = LocalContext.current
    val calendar = remember(context) { context.getSharedPreferences("mascot_calendar", Context.MODE_PRIVATE) }
    val reminders = remember(context) { context.getSharedPreferences(CALENDAR_REMINDER_PREFERENCES, Context.MODE_PRIVATE) }
    val notificationKey = "$heroId:push"
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var calendarEnabled by remember { mutableStateOf(calendar.getBoolean("enabled", false) && granted(Manifest.permission.READ_CALENDAR)) }
    var pushEnabled by remember(heroId) { mutableStateOf(reminders.getBoolean(notificationKey, true) &&
        (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))) }
    var showCalendarConsent by rememberSaveable { mutableStateOf(false) }
    fun savePush(enabled: Boolean) {
        pushEnabled = enabled
        reminders.edit().putBoolean(notificationKey, enabled).apply()
    }
    fun saveAccess(enabled: Boolean) {
        calendarEnabled = enabled
        calendar.edit().putBoolean("enabled", enabled).apply()
    }
    val pushPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), ::savePush)
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), ::saveAccess)

    SettingsCard("Напоминания") {
        Box(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(13.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xffc9cbca), Color.White)))
            .padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
            Text("Только важное", fontFamily = SbSansTextRegular, fontSize = 12.sp, color = FigmaInk)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Отправка пушей", Modifier.weight(1f), fontFamily = SbSansTextMedium, fontSize = 14.sp)
            MascotSettingsSwitch("Отправка пушей", pushEnabled) { enabled ->
                if (enabled && Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    pushPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else savePush(enabled)
            }
        }
    }
    Spacer(Modifier.height(28.dp))
    SettingsCard("Доступы") {
        ChatCapability(R.drawable.chat_google_calendar, "Google Calendar", "Поможет планировать дела", calendarEnabled) {
            if (!it) saveAccess(false)
            else if (granted(Manifest.permission.READ_CALENDAR)) saveAccess(true)
            else showCalendarConsent = true
        }
    }
    if (showCalendarConsent) MascotConfirmationSheet(
        title = "Подключить Google Calendar?",
        message = "Персонаж сможет учитывать ближайшие события, когда режим взаимодействия разрешает работу с календарём.",
        confirmLabel = "Подключить",
        onConfirm = { showCalendarConsent = false; calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
        onDismiss = { showCalendarConsent = false },
    )
}

@Composable
internal fun MascotSettingsSwitch(label: String, checked: Boolean, onChange: ((Boolean) -> Unit)?) {
    val toggle = if (onChange == null) Modifier else Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange)
    Box(Modifier.size(46.dp, 24.dp).semantics { contentDescription = label }.then(toggle),
        contentAlignment = Alignment.Center) {
        MascotSwitchTrack(checked)
    }
}

@Composable
internal fun MascotSwitchTrack(checked: Boolean) {
    Box(Modifier.size(46.dp, 20.dp).clip(CircleShape)
        .background(if (checked) FigmaInk else Color(0xFFB3B3B6)).padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(28.dp, 16.dp).background(Color.White, CircleShape))
    }
}

@Composable
private fun SettingsCard(title: String, action: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(.82f)
        .background(Color.White.copy(alpha = .8f), RoundedCornerShape(32.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, Modifier.weight(1f), color = FigmaInk, fontFamily = SbSansDisplayMedium, fontSize = 16.sp)
            if (action != null) IconButton(onClick = action, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Edit, "Добавить описание стиля", Modifier.size(16.dp), tint = FigmaInk)
            }
        }
        content()
    }
}
