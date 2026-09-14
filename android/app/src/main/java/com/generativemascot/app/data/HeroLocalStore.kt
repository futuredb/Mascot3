package com.generativemascot.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

class HeroLocalStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "hero")
    private val json = Json { ignoreUnknownKeys = true }

    fun resolve(url: String?): String? {
        val remote = rewriteHost(url) ?: return null
        val relative = assetKey(remote) ?: return remote
        val local = File(root, relative)
        if (!local.isFile) return remote
        return "${local.toURI()}?v=${local.lastModified()}"
    }

    fun fileFor(url: String?): File? {
        val remote = rewriteHost(url) ?: return null
        val relative = assetKey(remote) ?: return null
        val local = File(root, relative)
        return local.takeIf { it.isFile }
    }

    fun stateFile(mascotId: String, stateKey: String): File? {
        val widget = File(root, "$mascotId/widget/$stateKey.png")
        if (widget.isFile) return widget
        val still = File(root, "$mascotId/states/$stateKey.png")
        return still.takeIf { it.isFile }
    }

    fun baseFile(mascotId: String): File? =
        File(root, "$mascotId/base.png").takeIf { it.isFile }

    fun mascotName(mascotId: String): String? =
        File(root, "$mascotId/name.txt")
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun expressionPackReady(mascotId: String): Boolean = EXPRESSION_STATES.all { state ->
        File(root, "$mascotId/states/$state.png").isFile
    }

    fun puppetPackReady(mascotId: String): Boolean =
        puppetPartFiles(mascotId).keys.containsAll(PUPPET_ROLES)

    fun animationPackReady(mascotId: String): Boolean = VIDEO_ACTIONS.all { action ->
        actionVideoFile(mascotId, action) != null
    }

    fun actionVideoFile(mascotId: String, action: String): File? =
        File(root, "$mascotId/videos/${normalizeVideoAction(action)}.mp4")
            .takeIf { it.isFile && it.length() > 1024 }

    fun actionVideoUrls(mascotId: String): Map<String, String> = VIDEO_ACTIONS.mapNotNull { action ->
        actionVideoFile(mascotId, action)?.let { action to it.toURI().toString() }
    }.toMap()

    fun neutralVideoFrameFile(mascotId: String): File? =
        File(root, "$mascotId/videos/neutral.png").takeIf { it.isFile && it.length() > 1024 }

    fun sleepLoopAnchorFile(mascotId: String): File? =
        File(root, "$mascotId/videos/sleep-loop-anchor.png")
            .takeIf { it.isFile && it.length() > 1024 }

    fun videoMatteColor(mascotId: String): Int? =
        File(root, "$mascotId/videos/matte-color.txt")
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.removePrefix("#")
            ?.toLongOrNull(16)
            ?.let { Color.rgb(((it shr 16) and 0xff).toInt(), ((it shr 8) and 0xff).toInt(), (it and 0xff).toInt()) }

    fun saveVideoMatteColor(mascotId: String, color: Int) {
        val file = File(root, "$mascotId/videos/matte-color.txt")
        file.parentFile?.mkdirs()
        file.writeText("#%02X%02X%02X".format(Color.red(color), Color.green(color), Color.blue(color)))
    }

    fun performanceVideoFile(mascotId: String): File? =
        File(root, "$mascotId/performance.mp4").takeIf { it.isFile && it.length() > 1024 }

    fun videoJobId(mascotId: String): String? =
        File(root, "$mascotId/sora-job-id.txt").takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun saveVideoJobId(mascotId: String, jobId: String) {
        val file = File(root, "$mascotId/sora-job-id.txt")
        file.parentFile?.mkdirs()
        file.writeText(jobId)
    }

    fun clearVideoJobId(mascotId: String) {
        File(root, "$mascotId/sora-job-id.txt").delete()
    }

    fun openRouterVideoJobId(mascotId: String, action: String): String? =
        File(root, "$mascotId/videos/${normalizeVideoAction(action)}.job-id.txt")
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun saveOpenRouterVideoJobId(mascotId: String, action: String, jobId: String) {
        val file = File(root, "$mascotId/videos/${normalizeVideoAction(action)}.job-id.txt")
        file.parentFile?.mkdirs()
        file.writeText(jobId)
    }

    fun clearOpenRouterVideoJobId(mascotId: String, action: String) {
        File(root, "$mascotId/videos/${normalizeVideoAction(action)}.job-id.txt").delete()
    }

    fun hasOpenRouterVideoProgress(mascotId: String): Boolean = VIDEO_ACTIONS.any { action ->
        actionVideoFile(mascotId, action) != null || openRouterVideoJobId(mascotId, action) != null
    }

    suspend fun saveCreativeContract(mascotId: String, json: String): File = withContext(Dispatchers.IO) {
        val destination = File(root, "$mascotId/creative/character-contract.json")
        destination.parentFile?.mkdirs()
        val staging = File(destination.parentFile, "character-contract.pending.json")
        staging.writeText(json)
        if (destination.exists()) destination.delete()
        require(staging.renameTo(destination)) { "Не удалось сохранить контракт героя" }
        destination
    }

    suspend fun saveAnimationContract(mascotId: String, action: String, json: String): File =
        withContext(Dispatchers.IO) {
            val normalized = normalizeVideoAction(action)
            val destination = File(root, "$mascotId/creative/animations/$normalized.json")
            destination.parentFile?.mkdirs()
            val staging = File(destination.parentFile, "$normalized.pending.json")
            staging.writeText(json)
            if (destination.exists()) destination.delete()
            require(staging.renameTo(destination)) { "Не удалось сохранить контракт анимации $normalized" }
            destination
        }

    internal suspend fun saveAnimationPromptRecord(
        mascotId: String,
        action: String,
        record: AnimationPromptRecord,
    ): File = withContext(Dispatchers.IO) {
        val normalized = normalizeVideoAction(action)
        val destination = File(root, "$mascotId/creative/animations/$normalized.prompt.json")
        destination.parentFile?.mkdirs()
        val staging = File(destination.parentFile, "$normalized.prompt.pending.json")
        staging.writeText(json.encodeToString(record))
        if (destination.exists()) destination.delete()
        require(staging.renameTo(destination)) { "Не удалось сохранить версию промпта $normalized" }
        destination
    }

    suspend fun saveActionVideo(
        mascotId: String,
        action: String,
        mp4: ByteArray,
        costUsd: Double?,
    ): File = withContext(Dispatchers.IO) {
        require(mp4.size > 1024) { "OpenRouter вернул пустое видео" }
        val normalized = normalizeVideoAction(action)
        val destination = File(root, "$mascotId/videos/$normalized.mp4")
        destination.parentFile?.mkdirs()
        val staging = File(destination.parentFile, "$normalized.pending.mp4")
        staging.writeBytes(mp4)
        if (destination.exists()) destination.delete()
        require(staging.renameTo(destination)) { "Не удалось сохранить анимацию $normalized" }
        costUsd?.let { File(destination.parentFile, "$normalized.cost-usd.txt").writeText(it.toString()) }
        destination
    }

    internal suspend fun saveVideoQa(mascotId: String, action: String, qa: VideoClipQa): File =
        withContext(Dispatchers.IO) {
            val normalized = normalizeVideoAction(action)
            val destination = File(root, "$mascotId/videos/$normalized.qa.json")
            destination.parentFile?.mkdirs()
            destination.writeText(json.encodeToString(qa))
            destination
        }

    suspend fun quarantineActionVideo(
        mascotId: String,
        action: String,
        jobId: String,
        mp4: ByteArray,
        costUsd: Double?,
    ): File = withContext(Dispatchers.IO) {
        val normalized = normalizeVideoAction(action)
        val safeJob = jobId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)
        val destination = File(root, "$mascotId/videos/rejected/$normalized-$safeJob.mp4")
        destination.parentFile?.mkdirs()
        destination.writeBytes(mp4)
        costUsd?.let {
            File(destination.parentFile, "$normalized-$safeJob.cost-usd.txt").writeText(it.toString())
        }
        destination
    }

    suspend fun saveVideoPackManifest(mascotId: String): File = withContext(Dispatchers.IO) {
        val clips = VIDEO_ACTIONS.map { action ->
            val video = actionVideoFile(mascotId, action)
                ?: error("Нет принятой анимации $action")
            val qaFile = File(root, "$mascotId/videos/$action.qa.json")
            val qa = qaFile.takeIf { it.isFile }
                ?.readText()
                ?.let { json.decodeFromString<VideoClipQa>(it) }
            VideoClipManifest(
                id = action,
                file = video.name,
                durationMs = qa?.durationMs ?: 0L,
                costUsd = File(root, "$mascotId/videos/$action.cost-usd.txt")
                    .takeIf { it.isFile }?.readText()?.trim()?.toDoubleOrNull(),
                status = if (qa != null) "approved" else "legacy_unverified",
                qaFile = qaFile.takeIf { it.isFile }?.name,
            )
        }
        val manifest = VideoPackManifest(
            mascotId = mascotId,
            clips = clips,
            transitions = defaultVideoTransitions(),
        )
        val destination = File(root, "$mascotId/videos/manifest.json")
        destination.writeText(json.encodeToString(manifest))
        destination
    }

    suspend fun saveNeutralVideoFrame(mascotId: String, png: ByteArray): File = withContext(Dispatchers.IO) {
        require(png.size > 1024) { "Не удалось подготовить нулевую позу" }
        val destination = File(root, "$mascotId/videos/neutral.png")
        destination.parentFile?.mkdirs()
        val staging = File(destination.parentFile, "neutral.pending.png")
        staging.writeBytes(png)
        if (destination.exists()) destination.delete()
        require(staging.renameTo(destination)) { "Не удалось сохранить нулевую позу" }
        destination
    }

    suspend fun saveSleepLoopAnchor(mascotId: String, png: ByteArray): File = withContext(Dispatchers.IO) {
        require(png.size > 1024) { "Не удалось подготовить позу для сна" }
        val destination = File(root, "$mascotId/videos/sleep-loop-anchor.png")
        destination.parentFile?.mkdirs()
        val staging = File(destination.parentFile, "sleep-loop-anchor.pending.png")
        staging.writeBytes(png)
        if (destination.exists()) destination.delete()
        require(staging.renameTo(destination)) { "Не удалось сохранить позу для сна" }
        destination
    }

    fun animationPackCostUsd(mascotId: String): Double = VIDEO_ACTIONS.sumOf { action ->
        File(root, "$mascotId/videos/${normalizeVideoAction(action)}.cost-usd.txt")
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.toDoubleOrNull()
            ?: 0.0
    }

    internal fun creativeContracts(): List<MobileCharacterContract> = root.listFiles { file -> file.isDirectory }
        .orEmpty()
        .mapNotNull { hero ->
            File(hero, "creative/character-contract.json")
                .takeIf { it.isFile }
                ?.readText()
                ?.let { raw -> runCatching { json.decodeFromString<MobileCharacterContract>(raw) }.getOrNull() }
        }

    internal fun creativeContract(mascotId: String): MobileCharacterContract? =
        File(root, "$mascotId/creative/character-contract.json")
            .takeIf { it.isFile }
            ?.readText()
            ?.let { raw -> runCatching { json.decodeFromString<MobileCharacterContract>(raw) }.getOrNull() }

    suspend fun savePerformanceVideo(mascotId: String, mp4: ByteArray): File = withContext(Dispatchers.IO) {
        require(mp4.size > 1024) { "Sora вернула пустое видео" }
        val destination = File(root, "$mascotId/performance.mp4")
        destination.parentFile?.mkdirs()
        val staging = File(destination.parentFile, "performance.pending.mp4")
        staging.writeBytes(mp4)
        if (destination.exists()) destination.delete()
        require(staging.renameTo(destination)) { "Не удалось сохранить видео героя" }
        destination
    }

    fun localMascotIds(): List<String> = root.listFiles { file ->
        file.isDirectory && File(file, "base.png").isFile
    }?.sortedByDescending { File(it, "base.png").lastModified() }?.map { it.name }.orEmpty()

    suspend fun saveGeneratedBase(mascotId: String, png: ByteArray): File = withContext(Dispatchers.IO) {
        require(png.size > 8 && png[0] == 0x89.toByte() && png[1] == 0x50.toByte()) {
            "Генератор вернул повреждённый PNG"
        }
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: error("Генератор вернул нечитаемое изображение")
        val step = 2
        var samples = 0
        var transparent = 0
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) {
            val alpha = Color.alpha(bitmap.getPixel(x, y))
            samples += 1
            if (alpha <= 16) transparent += 1
            if (alpha > 32) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        val hasSubject = right >= left && bottom >= top
        val subjectWidthRatio = if (hasSubject) (right - left + 1).toDouble() / bitmap.width else 0.0
        val subjectHeightRatio = if (hasSubject) (bottom - top + 1).toDouble() / bitmap.height else 0.0
        val minimumMarginRatio = if (hasSubject) minOf(
            left.toDouble() / bitmap.width,
            top.toDouble() / bitmap.height,
            (bitmap.width - 1 - right).toDouble() / bitmap.width,
            (bitmap.height - 1 - bottom).toDouble() / bitmap.height,
        ) else 0.0
        val qa = evaluateCanonicalImageQa(
            width = bitmap.width,
            height = bitmap.height,
            transparentRatio = transparent.toDouble() / samples.coerceAtLeast(1),
            subjectWidthRatio = subjectWidthRatio,
            subjectHeightRatio = subjectHeightRatio,
            minimumMarginRatio = minimumMarginRatio,
        )
        bitmap.recycle()
        val qaFile = File(root, "$mascotId/creative/base.qa.json")
        qaFile.parentFile?.mkdirs()
        qaFile.writeText(json.encodeToString(qa))
        if (!qa.hardPass) {
            val rejected = File(root, "$mascotId/rejected/base.png")
            rejected.parentFile?.mkdirs()
            rejected.writeBytes(png)
            error(
                "Герой создан, но не прошёл проверку: ${qa.blockingIssues.joinToString("; ")}. " +
                    "Повторная платная генерация не запускалась.",
            )
        }
        val destination = File(root, "$mascotId/base.png")
        destination.parentFile?.mkdirs()
        val staging = File(destination.parentFile, "base.pending.png")
        staging.writeBytes(png)
        if (destination.exists()) destination.delete()
        require(staging.renameTo(destination)) { "Не удалось сохранить героя" }
        destination
    }

    suspend fun saveExpressionSheet(mascotId: String, png: ByteArray) = withContext(Dispatchers.IO) {
        val sheet = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: error("Генератор вернул нечитаемый лист эмоций")
        require(sheet.width >= 1200 && sheet.height >= 800) {
            "Лист эмоций имеет неверный размер"
        }
        val heroRoot = File(root, mascotId).apply { mkdirs() }
        val staging = File(heroRoot, "states-pending").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            EXPRESSION_STATES.forEachIndexed { index, state ->
                val column = index % 3
                val row = index / 3
                val left = column * sheet.width / 3
                val top = row * sheet.height / 2
                val right = (column + 1) * sheet.width / 3
                val bottom = (row + 1) * sheet.height / 2
                val tile = Bitmap.createBitmap(sheet, left, top, right - left, bottom - top)
                File(staging, "$state.png").outputStream().use { output ->
                    require(tile.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Не удалось сохранить эмоцию $state"
                    }
                }
                tile.recycle()
            }
            val states = File(heroRoot, "states").apply { mkdirs() }
            EXPRESSION_STATES.forEach { state ->
                val source = File(staging, "$state.png")
                val destination = File(states, "$state.png")
                if (destination.exists()) destination.delete()
                require(source.renameTo(destination)) { "Не удалось установить эмоцию $state" }
            }
            File(heroRoot, "expression-sheet.png").writeBytes(png)
        } finally {
            sheet.recycle()
            staging.deleteRecursively()
        }
    }

    /**
     * Turns one paid 3x5 model output into a local layered puppet. The green
     * screen is removed deterministically on the phone; no background-removal
     * request or server is involved.
     */
    suspend fun savePuppetSheet(mascotId: String, png: ByteArray) = withContext(Dispatchers.IO) {
        val sheet = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: error("Генератор вернул нечитаемый лист рига")
        require(sheet.width >= 900 && sheet.height >= 1300) {
            "Лист рига имеет неверный размер"
        }
        val heroRoot = File(root, mascotId).apply { mkdirs() }
        val staging = File(heroRoot, "puppet-pending").apply {
            deleteRecursively()
            File(this, "parts").mkdirs()
        }
        try {
            PUPPET_ROLES.forEachIndexed { index, role ->
                val column = index % 3
                val row = index / 3
                val left = column * sheet.width / 3
                val top = row * sheet.height / 5
                val right = (column + 1) * sheet.width / 3
                val bottom = (row + 1) * sheet.height / 5
                val tile = Bitmap.createBitmap(sheet, left, top, right - left, bottom - top)
                val cutout = removeGreenScreen(tile)
                tile.recycle()
                val bounds = alphaBounds(cutout)
                    ?: error("Не найдена деталь рига: $role")
                require(bounds.width() < cutout.width * .96f && bounds.height() < cutout.height * .96f) {
                    "Деталь рига $role касается края ячейки"
                }
                val cropped = Bitmap.createBitmap(cutout, bounds.left, bounds.top, bounds.width(), bounds.height())
                cutout.recycle()
                val padding = 16
                val padded = Bitmap.createBitmap(
                    cropped.width + padding * 2,
                    cropped.height + padding * 2,
                    Bitmap.Config.ARGB_8888,
                )
                android.graphics.Canvas(padded).drawBitmap(cropped, padding.toFloat(), padding.toFloat(), null)
                cropped.recycle()
                File(staging, "parts/$role.png").outputStream().use { output ->
                    require(padded.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Не удалось сохранить деталь рига $role"
                    }
                }
                padded.recycle()
            }
            val assets = PUPPET_ROLES.associateWith { role ->
                val part = File(staging, "parts/$role.png")
                AssetRefDto(
                    url = "http://local/assets/$mascotId/puppet/parts/$role.png",
                    sha256 = sha256(part),
                    mimeType = "image/png",
                )
            }
            val pack = PuppetPackDto(
                version = 1,
                format = "layered-2d-puppet",
                rigPreset = "friendly_blob_v1",
                parts = assets,
                actions = listOf(
                    "idle", "greeting", "happy", "curious", "surprised", "sleepy", "sad",
                    "dragged", "petted", "dancing", "spinning",
                ),
            )
            File(staging, "manifest-client.json").writeText(json.encodeToString(pack))
            val destination = File(heroRoot, "puppet")
            val previous = File(heroRoot, "puppet-previous").apply { deleteRecursively() }
            if (destination.exists()) require(destination.renameTo(previous)) { "Не удалось обновить риг" }
            if (!staging.renameTo(destination)) {
                if (previous.exists()) previous.renameTo(destination)
                error("Не удалось установить риг")
            }
            previous.deleteRecursively()
            File(heroRoot, "puppet-sheet.png").writeBytes(png)
        } finally {
            sheet.recycle()
            staging.deleteRecursively()
        }
    }

    /**
     * Splits one paid GPT Image atlas into complete transparent character
     * frames. Registration remains fixed to the original cells: no limb is
     * extracted, warped or composited separately.
     */
    suspend fun saveAnimationSheet(mascotId: String, action: String, png: ByteArray) =
        withContext(Dispatchers.IO) {
            require(action in FULL_FRAME_ACTIONS) { "Неизвестная анимация: $action" }
            val sheet = BitmapFactory.decodeByteArray(png, 0, png.size)
                ?: error("Генератор вернул нечитаемый лист анимации")
            require(sheet.width >= 900 && sheet.height >= 1300) {
                "Лист анимации имеет неверный размер"
            }
            val heroRoot = File(root, mascotId).apply { mkdirs() }
            val sheets = File(heroRoot, "animation-sheets").apply { mkdirs() }
            val pendingSheet = File(sheets, "$action.pending.png")
            pendingSheet.writeBytes(png)
            val staging = File(heroRoot, "sequences/$action-pending").apply {
                deleteRecursively()
                mkdirs()
            }
            val fingerprints = mutableSetOf<Long>()
            try {
                repeat(FULL_FRAME_COUNT) { index ->
                    val column = index % SHEET_COLUMNS
                    val row = index / SHEET_COLUMNS
                    val left = column * sheet.width / SHEET_COLUMNS
                    val top = row * sheet.height / SHEET_ROWS
                    val right = (column + 1) * sheet.width / SHEET_COLUMNS
                    val bottom = (row + 1) * sheet.height / SHEET_ROWS
                    val tile = Bitmap.createBitmap(sheet, left, top, right - left, bottom - top)
                    val transparent = isolateMainCharacter(removeGreenScreen(tile))
                    tile.recycle()
                    val bounds = alphaBounds(transparent)
                        ?: error("В кадре ${index + 1} пропал герой")
                    require(bounds.left > transparent.width * .015f &&
                        bounds.top > transparent.height * .015f &&
                        bounds.right < transparent.width * .985f &&
                        bounds.bottom < transparent.height * .985f
                    ) { "Герой обрезан в кадре ${index + 1}" }
                    require(transparentFraction(transparent) >= .22f) {
                        "В кадре ${index + 1} остался фон"
                    }
                    fingerprints += frameFingerprint(transparent)
                    File(staging, "frame_%03d.png".format(index)).outputStream().use { output ->
                        require(transparent.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            "Не удалось сохранить кадр ${index + 1}"
                        }
                    }
                    transparent.recycle()
                }
                require(fingerprints.size >= MIN_DISTINCT_FRAME_COUNT) {
                    "В листе слишком много одинаковых кадров; повторный платный запрос не запускался"
                }
                val destination = File(heroRoot, "sequences/$action")
                val previous = File(heroRoot, "sequences/$action-previous").apply { deleteRecursively() }
                if (destination.exists()) require(destination.renameTo(previous)) {
                    "Не удалось обновить анимацию $action"
                }
                if (!staging.renameTo(destination)) {
                    if (previous.exists()) previous.renameTo(destination)
                    error("Не удалось установить анимацию $action")
                }
                previous.deleteRecursively()
                val installedSheet = File(sheets, "$action.png")
                if (installedSheet.exists()) installedSheet.delete()
                require(pendingSheet.renameTo(installedSheet)) {
                    "Не удалось сохранить исходный лист $action"
                }
            } finally {
                sheet.recycle()
                staging.deleteRecursively()
                if (pendingSheet.exists()) {
                    val failed = File(sheets, "$action.failed-${System.currentTimeMillis()}.png")
                    pendingSheet.renameTo(failed)
                }
            }
        }

    private fun removeGreenScreen(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (index in pixels.indices) {
            val color = pixels[index]
            val sourceAlpha = Color.alpha(color)
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            val dominance = minOf(green - red, green - blue)
            val alpha = when {
                sourceAlpha < 8 -> 0
                green > 105 && dominance >= 78 -> 0
                green > 85 && dominance > 28 ->
                    (sourceAlpha * (78 - dominance).coerceIn(0, 50) / 50f).toInt()
                else -> sourceAlpha
            }
            if (alpha == 0) {
                pixels[index] = Color.TRANSPARENT
            } else {
                val cleanGreen = if (dominance > 12) minOf(green, maxOf(red, blue) + 12) else green
                pixels[index] = Color.argb(alpha, red, cleanGreen, blue)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Keeps the complete main silhouette and discards tiny/grid-edge artifacts. */
    private fun isolateMainCharacter(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val componentIds = IntArray(pixels.size) { -1 }
        val queue = IntArray(pixels.size)
        val components = mutableListOf<Pair<Int, Boolean>>()
        var componentId = 0
        for (start in pixels.indices) {
            if (componentIds[start] >= 0 || Color.alpha(pixels[start]) <= 20) continue
            var head = 0
            var tail = 0
            var area = 0
            var touchesEdge = false
            queue[tail++] = start
            componentIds[start] = componentId
            while (head < tail) {
                val index = queue[head++]
                area += 1
                val x = index % width
                val y = index / width
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) touchesEdge = true
                fun visit(next: Int) {
                    if (componentIds[next] < 0 && Color.alpha(pixels[next]) > 20) {
                        componentIds[next] = componentId
                        queue[tail++] = next
                    }
                }
                if (x > 0) visit(index - 1)
                if (x + 1 < width) visit(index + 1)
                if (y > 0) visit(index - width)
                if (y + 1 < height) visit(index + width)
            }
            components += area to touchesEdge
            componentId += 1
        }
        if (components.isEmpty()) return source
        val minimumArea = pixels.size / 500
        val selected = components.indices
            .filter { index ->
                val (area, touchesEdge) = components[index]
                area >= minimumArea && !touchesEdge
            }
            .maxByOrNull { components[it].first }
            ?: components.indices.maxBy { components[it].first }
        for (index in pixels.indices) {
            if (componentIds[index] != selected) pixels[index] = Color.TRANSPARENT
        }
        source.recycle()
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun alphaBounds(bitmap: Bitmap): android.graphics.Rect? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var minX = bitmap.width
        var minY = bitmap.height
        var maxX = -1
        var maxY = -1
        pixels.forEachIndexed { index, color ->
            if (Color.alpha(color) <= 20) return@forEachIndexed
            val x = index % bitmap.width
            val y = index / bitmap.width
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }
        if (maxX < minX || maxY < minY) return null
        return android.graphics.Rect(minX, minY, maxX + 1, maxY + 1)
    }

    private fun transparentFraction(bitmap: Bitmap): Float {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { Color.alpha(it) <= 8 }.toFloat() / pixels.size
    }

    private fun frameFingerprint(bitmap: Bitmap): Long {
        var hash = -0x340d631b7bdddcdbL
        repeat(16) { sampleY ->
            val y = ((sampleY + .5f) * bitmap.height / 16f).toInt().coerceIn(0, bitmap.height - 1)
            repeat(16) { sampleX ->
                val x = ((sampleX + .5f) * bitmap.width / 16f).toInt().coerceIn(0, bitmap.width - 1)
                val color = bitmap.getPixel(x, y)
                val quantized = ((Color.alpha(color) shr 4) shl 12) or
                    ((Color.red(color) shr 4) shl 8) or
                    ((Color.green(color) shr 4) shl 4) or
                    (Color.blue(color) shr 4)
                hash = (hash xor quantized.toLong()) * 0x100000001b3L
            }
        }
        return hash
    }

    fun sequenceFiles(mascotId: String, stateKey: String): List<File> =
        File(root, "$mascotId/sequences/$stateKey")
            .listFiles { file -> file.isFile && file.name.startsWith("frame_") && file.extension == "png" }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun spriteSequenceFiles(mascotId: String, stateKey: String): List<File> {
        val metadata = File(root, "$mascotId/sprites/manifest-client.json")
        if (!metadata.isFile) return emptyList()
        val pack = runCatching { json.decodeFromString<SpritePackDto>(metadata.readText()) }.getOrNull()
            ?: return emptyList()
        if (!pack.productionEligible || pack.approval != "approved") return emptyList()
        val family = pack.stateToFamily[stateKey] ?: return emptyList()
        return File(root, "$mascotId/sprites/frames/$family")
            .listFiles { file -> file.isFile && file.name.startsWith("frame_") && file.extension == "png" }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    /** Prefer complete generated animation frames, then approved legacy assets. */
    fun playbackSequenceFiles(mascotId: String, stateKey: String): List<File> =
        sequenceFiles(mascotId, normalizeFullFrameAction(stateKey))
            .ifEmpty { spriteSequenceFiles(mascotId, stateKey) }
            .ifEmpty { sequenceFiles(mascotId, stateKey) }
            .ifEmpty { sequenceFiles(mascotId, "base") }

    fun puppetPartFiles(mascotId: String): Map<String, File> {
        val metadata = File(root, "$mascotId/puppet/manifest-client.json")
        if (!metadata.isFile) return emptyMap()
        val pack = runCatching { json.decodeFromString<PuppetPackDto>(metadata.readText()) }.getOrNull()
            ?: return emptyMap()
        return pack.parts.mapNotNull { (role, asset) -> fileFor(asset.url)?.let { role to it } }.toMap()
    }

    suspend fun sync(api: MascotApi, mascotId: String) = withContext(Dispatchers.IO) {
        val manifest = runCatching { api.manifest(mascotId) }.getOrNull() ?: return@withContext
        val jobs = buildList {
            manifest.base?.let { add(it.url to it.sha256) }
            manifest.baseSequence?.frames?.forEach { add(it.url to it.sha256) }
            for (state in manifest.states.values) {
                state.still?.let { add(it.url to it.sha256) }
                state.animation?.let { add(it.url to it.sha256) }
                state.sequence?.frames?.forEach { add(it.url to it.sha256) }
            }
            manifest.spritePack?.atlas?.let { add(it.url to it.sha256) }
            manifest.puppetPack?.parts?.values?.forEach { add(it.url to it.sha256) }
        }
        for ((rawUrl, sha) in jobs) {
            val remote = rewriteHost(rawUrl) ?: continue
            val relative = assetKey(remote) ?: continue
            val dest = File(root, relative)
            if (dest.isFile && sha.isNotBlank() && sha256(dest) == sha) continue
            dest.parentFile?.mkdirs()
            val bytes = runCatching { api.fetchBytes(remote).bytes() }.getOrNull() ?: continue
            if (bytes.isEmpty()) continue
            dest.writeBytes(bytes)
        }
        manifest.spritePack?.let { unpackSpriteAtlas(mascotId, it) }
        manifest.puppetPack?.let { pack ->
            val metadata = File(root, "$mascotId/puppet/manifest-client.json")
            metadata.parentFile?.mkdirs()
            metadata.writeText(json.encodeToString(pack))
        }
    }

    private fun unpackSpriteAtlas(mascotId: String, pack: SpritePackDto) {
        if (!pack.productionEligible || pack.approval != "approved") return
        val geometry = pack.geometry
        if (geometry.cellWidth <= 0 || geometry.cellHeight <= 0 || geometry.framesPerRow <= 0) return
        val atlasFile = fileFor(pack.atlas?.url) ?: return
        val atlas = BitmapFactory.decodeFile(atlasFile.absolutePath) ?: return
        if (atlas.width != geometry.atlasWidth || atlas.height != geometry.atlasHeight) {
            atlas.recycle()
            return
        }
        val spritesRoot = File(root, "$mascotId/sprites")
        val staging = File(spritesRoot, "frames-staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            for (row in pack.rows) {
                if (row.row !in 0 until geometry.rows) continue
                val family = File(staging, row.family).apply { mkdirs() }
                for (frameIndex in 0 until geometry.framesPerRow) {
                    val x = frameIndex * geometry.cellWidth
                    val y = row.row * geometry.cellHeight
                    val frame = Bitmap.createBitmap(atlas, x, y, geometry.cellWidth, geometry.cellHeight)
                    File(family, "frame_%02d.png".format(frameIndex)).outputStream().use { output ->
                        frame.compress(Bitmap.CompressFormat.PNG, 100, output)
                    }
                    frame.recycle()
                }
            }
            val frames = File(spritesRoot, "frames")
            val previous = File(spritesRoot, "frames-previous")
            previous.deleteRecursively()
            if (frames.exists()) frames.renameTo(previous)
            if (!staging.renameTo(frames)) {
                if (previous.exists()) previous.renameTo(frames)
                return
            }
            previous.deleteRecursively()
            File(spritesRoot, "manifest-client.json").writeText(json.encodeToString(pack))
        } finally {
            atlas.recycle()
            staging.deleteRecursively()
        }
    }

    companion object {
        val EXPRESSION_STATES = listOf(
            "acquaintance", "working", "sleeping", "thinking", "bored", "rainy",
        )
        val PUPPET_ROLES = setOf(
            "head", "torso", "tail", "arm", "foot_left", "foot_right", "gill_left", "gill_right",
            "eye_forward", "eye_left", "eye_right", "eye_closed", "mouth_happy", "mouth_o", "mouth_sad",
        )
        val FULL_FRAME_ACTIONS = listOf("idle", "sleeping", "eating", "sad", "working", "dancing")
        // `sleeping` is the authored transition (stand -> lie down -> wake -> stand).
        // `sleep_loop` is generated separately from a frame inside that transition,
        // so it can remain asleep indefinitely without replaying the lie-down motion.
        val CORE_VIDEO_ACTIONS = listOf("idle", "joyful", "sleeping", "dancing")
        val VIDEO_ACTIONS = CORE_VIDEO_ACTIONS + "sleep_loop"
        const val FULL_FRAME_FPS = 12
        const val FULL_FRAME_COUNT = 12
        private const val MIN_FULL_FRAME_COUNT = FULL_FRAME_COUNT
        private const val MIN_DISTINCT_FRAME_COUNT = 8
        private const val SHEET_COLUMNS = 3
        private const val SHEET_ROWS = 4

        fun normalizeFullFrameAction(stateKey: String): String = when (stateKey) {
            "sleeping" -> "sleeping"
            "working", "thinking" -> "working"
            "bored", "rainy", "sad" -> "sad"
            "eating" -> "eating"
            "dancing" -> "dancing"
            else -> "idle"
        }

        fun normalizeVideoAction(stateKey: String): String = when (stateKey) {
            "sleep_loop" -> "sleep_loop"
            "joyful", "content", "eating" -> "joyful"
            "sleeping" -> "sleeping"
            "dancing", "working" -> "dancing"
            else -> "idle"
        }
        @Volatile
        var current: HeroLocalStore? = null
            private set

        fun attach(store: HeroLocalStore) {
            current = store
        }

        fun assetKey(url: String): String? {
            val path = url.substringAfter("/assets/", missingDelimiterValue = "").substringBefore('?')
            if (path.isBlank() || '/' !in path) return null
            return path
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
