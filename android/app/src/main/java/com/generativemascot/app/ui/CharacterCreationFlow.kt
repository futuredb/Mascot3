package com.generativemascot.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.generativemascot.app.R
import com.generativemascot.app.data.CharacterCreationChoice
import com.generativemascot.app.data.CharacterCommunication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class CreationPage { Overview, Type, Appearance, Personality, Communication }
private val types = listOf("person", "creature", "animal")
private val typeNames = listOf("Человек", "Существо", "Животное")
private val typeArt = listOf(R.drawable.type_person_figma, R.drawable.type_creature_figma, R.drawable.type_animal_figma)
private val ink = Color(0xff222222)
private val paper = Color(0xffeaebec)
private val semibold = FontFamily(Font(R.font.sb_sans_display_bold))
private val regular = FontFamily(Font(R.font.sb_sans_display_regular))
private data class CreationPreset(val label: String, val prompt: String, val art: Int)

private val appearance = listOf(
    CreationPreset("Модный", "Любит модно одеваться", R.drawable.creation_fashion),
    CreationPreset("Хищный", "Внешность хищника, с острыми чертами", R.drawable.creation_appearance_grid),
    CreationPreset("Милый", "Милая внешность и мягкие округлые формы", R.drawable.creation_appearance_grid),
    CreationPreset("Мистический", "Мистическая внешность с загадочными деталями", R.drawable.creation_appearance_grid),
    CreationPreset("Воин", "Внешность воина с элементами брони", R.drawable.creation_appearance_grid),
    CreationPreset("Разрушенный", "Внешность немного разрушена, с трещинами и сколами", R.drawable.creation_appearance_grid),
)
private val appearanceHeadings = listOf(
    "люблю модную одежду", "выгляжу как хищник", "у меня милая внешность",
    "выгляжу загадочно", "выгляжу как воин", "моя внешность немного разрушена",
)
private val personality = listOf(
    CreationPreset("Спокойный", "Спокойный и уравновешенный", R.drawable.creation_calm),
    CreationPreset("Дерзкий", "Дерзкий и уверенный в себе", R.drawable.creation_bold),
    CreationPreset("Любопытный", "Любопытный, любит исследовать новое", R.drawable.creation_curious),
    CreationPreset("Застенчивый", "Застенчивый и деликатный", R.drawable.creation_shy),
    CreationPreset("Мечтательный", "Мечтательный, с живым воображением", R.drawable.creation_dreamy),
    CreationPreset("Жизнерадостный", "Жизнерадостный и энергичный", R.drawable.creation_joyful),
)

private data class CreationDraft(
    val type: Int,
    val looks: Int,
    val traits: Int,
    val appearanceText: String,
    val personalityText: String,
)

private val creationDraftSaver = listSaver<CreationDraft?, Any>(
    save = { draft ->
        if (draft == null) emptyList()
        else listOf(draft.type, draft.looks, draft.traits, draft.appearanceText, draft.personalityText)
    },
    restore = { CreationDraft(it[0] as Int, it[1] as Int, it[2] as Int, it[3] as String, it[4] as String) },
)

@Composable
internal fun CharacterCreationFlow(
    onClose: () -> Unit,
    onFinish: (CharacterCreationChoice) -> Boolean,
    creationInProgress: Boolean = false,
    onCreationFailure: () -> String = { "Не удалось начать создание персонажа. Попробуй ещё раз." },
) {
    var page by rememberSaveable { mutableStateOf(CreationPage.Overview) }
    var guided by rememberSaveable { mutableStateOf(false) }
    var type by rememberSaveable { mutableIntStateOf(types.indices.random()) }
    var looks by rememberSaveable { mutableIntStateOf(1 shl appearance.indices.random()) }
    var traits by rememberSaveable {
        val count = (1..2).random()
        mutableIntStateOf(personality.indices.shuffled().take(count).sumOf { 1 shl it })
    }
    val communicationVariationSeed by rememberSaveable { mutableIntStateOf(kotlin.random.Random.nextInt()) }
    var communicationWasEdited by rememberSaveable { mutableStateOf(false) }
    var communication by rememberSaveable(stateSaver = communicationSaver) {
        mutableStateOf(CharacterCommunication.fromPersonality(presetDescription(personality, traits), communicationVariationSeed))
    }
    var communicationBeforeEdit by rememberSaveable(stateSaver = communicationSaver) { mutableStateOf(CharacterCommunication()) }
    var appearanceText by rememberSaveable { mutableStateOf("") }
    var personalityText by rememberSaveable { mutableStateOf("") }
    var overviewDraft by rememberSaveable(stateSaver = creationDraftSaver) { mutableStateOf<CreationDraft?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var referencePath by rememberSaveable { mutableStateOf<String?>(null) }
    var referenceLoading by remember { mutableStateOf(false) }
    var referenceError by remember { mutableStateOf<String?>(null) }
    var showReferenceNotice by remember { mutableStateOf(false) }
    val referencePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            referenceLoading = true
            scope.launch {
                try { referencePath = withContext(Dispatchers.IO) { saveChatImage(context, uri) }; referenceError = null }
                catch (_: Exception) { referenceError = "Не удалось открыть изображение" }
                finally { referenceLoading = false }
            }
        }
    }
    if (showReferenceNotice) MascotConfirmationSheet(
        title = "Фото для персонажа",
        message = "Если загружаешь фотографию человека, используй только своё фото. " +
            "Изображение будет передано сервису генерации. Видеомодель может отклонить фото, если распознает реального человека.",
        confirmLabel = "Выбрать изображение",
        onConfirm = { showReferenceNotice = false; referencePicker.launch("image/*") },
        onDismiss = { showReferenceNotice = false },
    )
    fun navigate(to: CreationPage) {
        if (to != CreationPage.Overview && page == CreationPage.Overview) {
            overviewDraft = CreationDraft(type, looks, traits, appearanceText, personalityText)
            if (guided) {
                looks = 0
                traits = 0
                appearanceText = ""
                personalityText = ""
                if (!communicationWasEdited) communication = CharacterCommunication()
            }
        }
        if (to == CreationPage.Overview) overviewDraft = null
        editing = false
        error = null
        page = to
    }
    fun back() {
        if (page == CreationPage.Communication) communication = communicationBeforeEdit
        if (editing) {
            editing = false
            return
        }
        if (!guided || page == CreationPage.Type) {
            overviewDraft?.let {
                type = it.type
                looks = it.looks
                traits = it.traits
                appearanceText = it.appearanceText
                personalityText = it.personalityText
                if (!communicationWasEdited) communication = CharacterCommunication.fromPersonality(
                    listOf(presetDescription(personality, traits), personalityText.trim()).filter(String::isNotBlank).joinToString(". "),
                    communicationVariationSeed,
                )
            }
            navigate(CreationPage.Overview)
        } else {
            navigate(when (page) {
                CreationPage.Appearance -> CreationPage.Type
                CreationPage.Communication -> CreationPage.Personality
                else -> CreationPage.Appearance
            })
        }
    }
    fun finish() {
        if (creationInProgress || referenceLoading) return
        val choice = CharacterCreationChoice(
            type = types[type], style = "neutral", referencePath = referencePath,
            communication = communication,
            appearancePrompt = listOf(presetDescription(appearance, looks), appearanceText.trim()).filter { it.isNotBlank() }.joinToString(". "),
            personalityPrompt = listOf(presetDescription(personality, traits), personalityText.trim()).filter { it.isNotBlank() }.joinToString(". "),
        )
        error = if (onFinish(choice)) null else onCreationFailure()
    }

    BackHandler(enabled = !creationInProgress) { if (page == CreationPage.Overview) onClose() else back() }
    if (page == CreationPage.Type) {
        CharacterTypeScreen(type, referencePath, { showReferenceNotice = true }, { referencePath = null }, ::back) {
            type = it
            navigate(if (guided) CreationPage.Appearance else CreationPage.Overview)
        }
        return
    }

    if (page == CreationPage.Communication) {
        CreationCommunicationPage(
            if (type == 1) R.drawable.creation_communication_creature else typeArt[type],
            communication, { communication = it; communicationWasEdited = true }, ::back,
        ) {
            navigate(CreationPage.Overview)
        }
        return
    }

    MascotSystemBars(paper)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            if (page == CreationPage.Overview) Brush.verticalGradient(listOf(paper, paper))
            else Brush.verticalGradient(0f to Color(0xfff2f2f2), .2f to paper, .8f to paper, 1f to Color(0xfff2f2f2)),
        ).clipToBounds(),
    ) {
        val sx = maxWidth / 393
        val sy = maxHeight / 852
        fun slot(x: Int, y: Int, width: Int, height: Int) =
            Modifier.offset(sx * x, sy * y).size(sx * width, sy * height)
        val overview = page == CreationPage.Overview
        val isAppearance = page == CreationPage.Appearance
        val preview = if (!overview && type == 1) R.drawable.creation_creature_blurred else typeArt[type]
        Image(painterResource(preview), null,
            if (overview) {
                val (left, top) = when (type) {
                    1 -> -61 to 124
                    2 -> -75 to 137
                    else -> -35 to 123
                }
                Modifier.offset(sx * left, sy * top)
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .size(sx * 463, sy * 408).blur(6.dp)
            }
            else if (type == 1) slot(60, 123, 273, 273).drawWithContent {
                val canvas = drawContext.canvas
                canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint().apply { blendMode = BlendMode.Darken })
                drawContent()
                canvas.restore()
            }.then(if (isAppearance) Modifier else Modifier.blur(6.dp))
            else slot(42, 123, 309, 273).then(if (isAppearance) Modifier else Modifier.blur(6.dp)),
            contentScale = ContentScale.FillBounds)

        if (overview && referencePath == null) {
            Row(slot(26, 62, 160, 48).mascotClickable(onClickLabel = "Загрузить референс") { showReferenceNotice = true },
                verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.creation_attachment), null, Modifier.size(24.dp), tint = ink.copy(alpha = .4f))
                Spacer(Modifier.width(8.dp))
                Text("Загрузить реф", color = ink.copy(alpha = .55f), fontFamily = regular, fontSize = 14.sp)
            }
        }
        referencePath?.takeIf { overview || guided }?.let { path ->
            CreationReferenceThumbnail(path, slot(if (overview) 25 else 180, if (overview) 69 else 55, 38, 34),
                onReplace = { showReferenceNotice = true }, onRemove = { referencePath = null })
        }
        if (!overview) Box(slot(13, 48, 48, 48).mascotClickable(onClickLabel = "Назад", onClick = ::back), contentAlignment = Alignment.Center) {
            HomeFigmaIcon("creation-back", null, size = 24.dp)
        }
        if (overview || guided) Box(slot(if (overview) 328 else 336, if (overview) 63 else 47, 48, 48)
            .mascotClickable(onClickLabel = "Закрыть", onClick = onClose), contentAlignment = Alignment.Center) {
            HomeFigmaIcon("creation-close", null, size = 15.dp)
        }

        if (overview) {
            val offsets = rememberInfiniteTransition(label = "Карточки персонажа")
            val wave by offsets.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
                durationMillis = 6000
                0f at 0 using EaseInOut
                -1f at 1500 using EaseInOut
                1f at 4500 using EaseInOut
                0f at 6000
            }), label = "Покачивание карточек")
            val motion = if (type == 0) wave else 0f
            val lookIndex = appearance.indices.firstOrNull { looks and (1 shl it) != 0 }
            val traitIndex = personality.indices.firstOrNull { traits and (1 shl it) != 0 }
            DatingCircle(slot(20, 506, 134, 134).offset(y = sy * (motion * 7)), if (looks == 1) "Любит модно одеваться" else presetLabels(appearance, looks).ifEmpty { "Внешность" },
                lookIndex?.let { appearance[it] }, lookIndex) { guided = false; navigate(CreationPage.Appearance) }
            DatingCircle(slot(154, 542, 134, 134).offset(y = sy * (motion * 6)), typeNames[type], null, null) {
                guided = false
                navigate(CreationPage.Type)
            }
            DatingCircle(slot(239, 426, 134, 134).offset(y = sy * (motion * 8)),
                presetLabels(personality, traits).ifEmpty { "Характер" }, traitIndex?.let { personality[it] }, null) {
                guided = false
                navigate(CreationPage.Personality)
            }
            Box(slot(42, 356, 133, 134).offset(y = sy * (motion * 8)).clip(RoundedCornerShape(42.dp))
                .background(Color.White).mascotClickable(onClickLabel = "Стиль общения") {
                    guided = false
                    communicationBeforeEdit = communication
                    navigate(CreationPage.Communication)
                }, contentAlignment = Alignment.Center) {
                Row(Modifier.align(Alignment.TopCenter).padding(top = 21.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    for (index in 1..4) HomeFigmaIcon("creation-quote-$index", null, size = 5.dp)
                }
                Text("Стиль\nобщения", Modifier.align(Alignment.BottomCenter).padding(bottom = 29.dp),
                    textAlign = TextAlign.Center, color = Color.Black, fontFamily = SbSansDisplayMedium, fontSize = 13.sp)
            }
            HomeFigmaIcon("creation-quote-tail", null,
                Modifier.offset(sx * 146, sy * 391.5f).graphicsLayer { scaleY = -1f }, size = 36.dp)
            Box(slot(146, 329, 205, 86).clip(RoundedCornerShape(20.dp)).background(Color.White)
                .mascotClickable(onClickLabel = "Настроить стиль общения") {
                    guided = false
                    communicationBeforeEdit = communication
                    navigate(CreationPage.Communication)
                }.padding(16.dp), contentAlignment = Alignment.CenterStart) {
                Text(communication.notes.ifBlank { communication.summary() }.ifBlank { "Как с тобой общаться" },
                    fontFamily = regular, fontSize = 13.sp, lineHeight = 16.sp, color = Color.Black,
                    maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            CreationPill("СОЗДАТЬ", slot(136, 721, 122, 50), !creationInProgress && !referenceLoading, ::finish)
            Box(slot(75, 781, 243, 48).mascotClickable(onClickLabel = "Настроить по-своему") {
                guided = true
                navigate(CreationPage.Type)
            }, contentAlignment = Alignment.Center) {
                Text("Настроить по-своему", fontFamily = regular, fontSize = 14.sp, color = ink.copy(alpha = .55f))
            }
        } else {
            val presets = if (isAppearance) appearance else personality
            val mask = if (isAppearance) looks else traits
            val custom = if (isAppearance) appearanceText else personalityText
            val heading = when {
                custom.isNotBlank() -> custom
                isAppearance -> appearanceHeadings.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                    .joinToString(" и ").replaceFirstChar { it.uppercase() }
                else -> presetLabels(presets, mask)
            }
            val defaultHeading = if (isAppearance) "Выбери как я буду выглядеть" else "Выбери с каким я буду характером"
            val headingStyle = TextStyle(color = Color.Black, fontFamily = SbSansDisplayMedium, fontSize = 20.sp,
                lineHeight = 24.sp, letterSpacing = (-.5).sp, textAlign = TextAlign.Center)
            Box(slot(27, 415, 339, 54), contentAlignment = Alignment.BottomCenter) {
                if (editing) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    BasicTextField(custom, { value -> if (value.length <= 500) {
                        if (isAppearance) appearanceText = value else {
                            personalityText = value
                            if (!communicationWasEdited) communication = CharacterCommunication.fromPersonality(
                                listOf(presetDescription(personality, traits), value.trim()).filter(String::isNotBlank).joinToString(". "),
                                communicationVariationSeed,
                            )
                        }
                    } },
                        Modifier.fillMaxWidth().focusRequester(focus), textStyle = headingStyle,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { editing = false }), maxLines = 2)
                } else Text(heading.ifEmpty { defaultHeading }, style = headingStyle,
                    color = if (heading.isEmpty()) ink.copy(alpha = .55f) else Color.Black, maxLines = 2)
            }
            Box(slot(27, 473, 339, 22), contentAlignment = Alignment.Center) {
                Text(if (isAppearance) "добавь детали внешности" else "создай характер", fontFamily = regular,
                    fontSize = 12.sp, color = ink.copy(alpha = .6f))
            }
            for ((index, preset) in presets.withIndex()) {
                val bit = 1 shl index
                val selected = mask and bit != 0
                val full = Integer.bitCount(mask) >= 2
                Box(slot(56 + index % 3 * 100, 519 + index / 3 * 100, 80, 80).clip(CircleShape)
                    .semantics { this.selected = selected }
                    .mascotClickable(enabled = selected || !full, onClickLabel = preset.label) {
                        if (isAppearance) looks = mask xor bit else {
                            traits = mask xor bit
                            if (!communicationWasEdited) communication = CharacterCommunication.fromPersonality(
                                listOf(presetDescription(personality, traits), personalityText.trim()).filter(String::isNotBlank).joinToString(". "),
                                communicationVariationSeed,
                            )
                        }
                    }, contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().alpha(if (full && !selected) .18f else 1f)) {
                        PresetArt(preset, index.takeIf { isAppearance })
                        CurvedPresetLabel(preset.label)
                    }
                    if (selected) Box(Modifier.size(42.dp).clip(CircleShape).background(ink), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, "Выбрано", tint = Color.White)
                    }
                }
            }
            val label = when {
                !guided -> "СОХРАНИТЬ"
                else -> "ДАЛЕЕ"
            }
            val width = when (label) { "ДАЛЕЕ" -> 105; "СОХРАНИТЬ" -> 144; else -> 122 }
            val x = (393 - width - 58) / 2
            Box(slot(x, 745, 50, 50).clip(CircleShape).background(ink)
                .mascotClickable(onClickLabel = "Добавить описание") { editing = !editing }, contentAlignment = Alignment.Center) {
                HomeFigmaIcon("creation-pencil", null, size = 19.dp)
            }
            CreationPill(label, slot(x + 58, 745, width, 50), !creationInProgress && !referenceLoading) {
                when {
                    !guided -> navigate(CreationPage.Overview)
                    isAppearance -> navigate(CreationPage.Personality)
                    else -> {
                        communicationBeforeEdit = communication
                        navigate(CreationPage.Communication)
                    }
                }
            }
        }
        (error ?: referenceError)?.let {
            Text(it, slot(32, 100, 329, 44), color = Color(0xff9e3030), fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun CreationReferenceThumbnail(path: String, modifier: Modifier, onReplace: () -> Unit, onRemove: () -> Unit) {
    Box(modifier) {
        AsyncImage(File(path), "Референс", Modifier.size(32.dp).clip(CircleShape)
            .mascotClickable(onClickLabel = "Заменить референс", onClick = onReplace), contentScale = ContentScale.Crop)
        Box(Modifier.align(Alignment.TopEnd).size(14.dp).clip(CircleShape).background(Color.Black)
            .mascotClickable(onClickLabel = "Удалить референс", onClick = onRemove), contentAlignment = Alignment.Center) {
            Text("×", color = Color.White, fontSize = 12.sp)
        }
    }
}

private fun presetLabels(presets: List<CreationPreset>, mask: Int) =
    presets.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.joinToString(" и ") { it.label }

private fun presetDescription(presets: List<CreationPreset>, mask: Int) =
    presets.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.joinToString(". ") { it.prompt }

@Composable
private fun CreationPill(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Box(modifier.clip(CircleShape).background(ink.copy(alpha = if (enabled) 1f else .55f))
        .mascotClickable(enabled = enabled, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontFamily = semibold, fontSize = 14.sp)
    }
}

@Composable
private fun DatingCircle(modifier: Modifier, label: String, preset: CreationPreset?, index: Int?, onClick: () -> Unit) {
    Box(modifier.clip(CircleShape).background(Color.White).mascotClickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        if (preset != null) PresetArt(preset, index)
        val textColor = if (preset == null) Color.Black else Color.White
        HomeFigmaIcon(if (preset == null) "creation-dark-arrow" else "creation-light-arrow",
            null, Modifier.align(Alignment.TopCenter).padding(top = 16.dp), size = 24.dp)
        Text(label, Modifier.padding(horizontal = 13.dp), color = textColor, fontFamily = SbSansDisplayMedium,
            fontSize = 14.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CurvedPresetLabel(label: String) {
    val context = LocalContext.current
    val typeface = remember(context) { ResourcesCompat.getFont(context, R.font.sb_sans_display_regular) }
    Canvas(Modifier.fillMaxSize()) {
        val scale = size.minDimension / 80f
        val inset = 11f * scale
        val arc = android.graphics.Path().apply {
            addArc(inset, inset, size.width - inset, size.height - inset, -95f, 145f)
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 10f * scale
            this.typeface = typeface
        }
        drawContext.canvas.nativeCanvas.drawTextOnPath(label, arc, 0f, 0f, paint)
    }
}

@Composable
private fun PresetArt(preset: CreationPreset, index: Int?) {
    if (index == null) {
        Image(painterResource(preset.art), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
        val transforms = listOf(
            floatArrayOf(-.0499f, 0f, 1.0879f, 1f),
            floatArrayOf(-1.1908f, -.273f, 4.4447f, 4.0855f),
            floatArrayOf(-2.2601f, -.273f, 4.4447f, 4.0855f),
            floatArrayOf(-.0499f, -1.3399f, 4.17f, 3.833f),
            floatArrayOf(-3.3771f, -.273f, 4.4447f, 4.0855f),
            floatArrayOf(-3.3771f, -1.4241f, 4.4447f, 4.0855f),
        )
        val (left, top, width, height) = transforms[index]
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
            Image(painterResource(preset.art), null,
                Modifier.offset(maxWidth * left, maxHeight * top).wrapContentSize(Alignment.TopStart, unbounded = true).size(maxWidth * width, maxHeight * height),
                contentScale = ContentScale.FillBounds)
        }
    }
}
