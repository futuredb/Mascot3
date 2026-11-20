package com.generativemascot.app.physics

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.generativemascot.app.BuildConfig
import com.generativemascot.app.data.AssetRefDto
import com.generativemascot.app.data.MascotApi
import com.generativemascot.app.worker.PhysicsWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.security.MessageDigest

data class InstalledPhysics(val pack: PhysicsPack, val directory: File, val framing: PhysicsFraming = PhysicsFraming(), val bounds: SubjectBounds? = null)

class PhysicsStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun directory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        return File(context.filesDir, "hero/$id/physics")
    }
    private fun prefs(id: String) = context.getSharedPreferences("physics-$id", Context.MODE_PRIVATE)
    fun status(id: String): String? = prefs(id).getString("status", null)
    fun setStatus(id: String, message: String) { prefs(id).edit().putString("status", message).commit() }
    fun savedJob(id: String): String? = prefs(id).getString("job", null)
    fun saveJob(id: String, job: String) { prefs(id).edit().putString("job", job).commit() }
    fun requestPreparation(id: String) {
        require(id == PILOT_ID) { "Пока доступна только подготовка пилотного героя" }
        require(BuildConfig.MASCOT3_CLIENT_TOKEN.isNotBlank()) { "Не настроено подключение к серверу подготовки" }
        setStatus(id, "Готовим физику…")
        WorkManager.getInstance(context).enqueueUniqueWork("physics-prepare-$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PhysicsWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("hero" to id)).build())
    }
    fun enqueueInstall(id: String, ref: AssetRefDto) {
        WorkManager.getInstance(context).enqueueUniqueWork("physics-install-$id-${ref.sha256}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PhysicsWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("hero" to id, "url" to ref.url, "sha256" to ref.sha256)).build())
    }
    fun revision(id: String): String? = runCatching {
        AtomicFile(File(directory(id), "active")).openRead().bufferedReader().use { it.readText() }.trim()
    }.getOrNull()
    suspend fun load(id: String): InstalledPhysics? = mutex.withLock { withContext(Dispatchers.IO) {
        refreshBundled(id)
        val revision = revision(id) ?: return@withContext null
        require(revision.matches(Regex("[a-f0-9]{64}")))
        verify(id, File(directory(id), revision), revision)
    } }
    /** Upgrade an older bundled pack after APK import, without replacing an
     * unrelated downloaded revision or pointing at partially copied files. */
    private fun refreshBundled(id: String) {
        val source="bundled-heroes/$id/physics"
        val bundled=runCatching { context.assets.open("$source/active").bufferedReader().use { it.readText() }.trim() }.getOrNull() ?: return
        require(bundled.matches(Regex("[a-f0-9]{64}")))
        val current=revision(id)
        val previous=prefs(id).getString("bundledRevision",null)
        val knownBundled=context.assets.list(source).orEmpty().any { it.substringBefore('/')==current }
        if (current!=bundled && (current==null || current==previous || knownBundled)) {
            verify(id,File(directory(id),bundled),bundled)
            activate(directory(id),bundled)
        }
        prefs(id).edit().putString("bundledRevision",bundled).commit()
    }
    private fun activate(root: File, revision: String) {
        val atomic = AtomicFile(File(root, "active")); val out = atomic.startWrite()
        try { out.write(revision.toByteArray()); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    internal suspend fun installPrepared(id: String, staging: File, expected: String) = mutex.withLock { withContext(Dispatchers.IO) {
        require(expected.matches(Regex("[a-f0-9]{64}")))
        verify(id, staging, expected)
        val root = directory(id).apply { mkdirs() }
        val destination = File(root, expected)
        if (destination.exists() && runCatching { verify(id, destination, expected) }.isFailure) {
            // Keep the damaged revision for recovery; never overwrite a good pack.
            val previous = File(root, "corrupt-$expected-${java.util.UUID.randomUUID()}")
            check(destination.renameTo(previous)) { "Не удалось восстановить карты героя" }
        }
        if (!destination.exists()) check(staging.renameTo(destination)) { "Не удалось сохранить карты героя" }
        verify(id, destination, expected)
        activate(root, expected)
    } }
    private fun verify(id: String, root: File, expected: String): InstalledPhysics {
        val bytes = File(root, "manifest.json").readBytes()
        require(hash(bytes) == expected) { "Повреждён манифест физики" }
        val pack = json.decodeFromString<PhysicsPack>(bytes.decodeToString()); pack.validate()
        val hero = File(context.filesDir, "hero/$id")
        require(hash(File(hero, "base.png").readBytes()) == pack.sourceSha256) { "Физика подготовлена для другой картинки" }
        if (pack.videoAnchorSha256.isNotEmpty() && File(hero, "videos/neutral.png").exists()) {
            require(hash(File(hero, "videos/neutral.png").readBytes()) == pack.videoAnchorSha256) { "Изменилась нейтральная поза видео" }
        }
        pack.assets.values.forEach { asset ->
            val file = File(root, asset.file)
            require(file.length() == asset.size && hash(file.readBytes()) == asset.sha256) { "Повреждены слои физики" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth == pack.width && bounds.outHeight == pack.height) { "Неверные размеры слоя физики" }
        }
        require(pack.width.toLong() * pack.height * pack.layers.size * 4 <= 128L * 1024 * 1024) { "Пакет превышает лимит памяти" }
        val bounds = bounds(pack, root)
        return InstalledPhysics(pack, root, PhysicsFraming.fit(bounds.width, bounds.height, bounds.left, bounds.top, bounds.right, bounds.bottom), bounds)
    }
    private fun bounds(pack: PhysicsPack, root: File): SubjectBounds {
        var left=pack.width; var top=pack.height; var right=0; var bottom=0
        // Once per loaded revision, off the UI thread. Transparent canvas
        // margins must not decide a character's apparent size.
        pack.layers.map { it.texture }.distinct().forEach { texture ->
            val bitmap = requireNotNull(BitmapFactory.decodeFile(File(root,pack.assets.getValue(texture).file).path))
            try {
                val row = IntArray(pack.width)
                for (y in 0 until pack.height) {
                    bitmap.getPixels(row,0,pack.width,0,y,pack.width,1)
                    for (x in row.indices) if ((row[x] ushr 24) >= 16) {
                        left=minOf(left,x); right=maxOf(right,x+1); top=minOf(top,y); bottom=maxOf(bottom,y+1)
                    }
                }
            } finally { bitmap.recycle() }
        }
        return SubjectBounds(pack.width, pack.height, left, top, right, bottom)
    }
    private fun url(raw: String): String {
        val base = URI(BuildConfig.API_BASE_URL + "/"); val resolved = base.resolve(raw)
        require(resolved.scheme == base.scheme && resolved.host == base.host && resolved.port == base.port && resolved.userInfo == null && resolved.path.startsWith("/v1/physics/jobs/")) { "Недопустимый адрес физического пакета" }
        return resolved.toString()
    }
    private suspend fun download(api: MascotApi, raw: String, maximum: Long): ByteArray = api.fetchBytes(url(raw)).use { response ->
        require(response.contentLength() <= maximum) { "Слишком большой файл физики" }
        response.byteStream().use { stream ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = stream.read(buffer); if (count < 0) break; require(output.size().toLong() + count <= maximum); output.write(buffer, 0, count) }
            output.toByteArray()
        }
    }
    suspend fun install(api: MascotApi, id: String, ref: AssetRefDto) = mutex.withLock { withContext(Dispatchers.IO) {
        require(ref.sha256.matches(Regex("[a-f0-9]{64}")))
        if (revision(id) == ref.sha256 && runCatching { verify(id, File(directory(id), ref.sha256), ref.sha256) }.isSuccess) return@withContext
        val root = directory(id); root.mkdirs()
        val staging = File(root, "pending-${ref.sha256}").apply { mkdirs() }
        val bytes = download(api, ref.url, 8L * 1024 * 1024)
        require(hash(bytes) == ref.sha256) { "Не совпал хеш манифеста" }
        val pack = json.decodeFromString<PhysicsPack>(bytes.decodeToString()); pack.validate()
        var total = 0L
        pack.assets.values.forEach { asset ->
            total += asset.size; require(total <= 96L * 1024 * 1024) { "Слишком большой пакет" }
            val target = File(staging, asset.file)
            if (!target.isFile || hash(target.readBytes()) != asset.sha256) {
                val contents = download(api, asset.url, 30L * 1024 * 1024)
                require(contents.size.toLong() == asset.size && hash(contents) == asset.sha256) { "Не совпал хеш слоя" }
                target.writeBytes(contents)
            }
        }
        File(staging, "manifest.json").writeBytes(bytes)
        verify(id, staging, ref.sha256)
        val destination = File(root, ref.sha256)
        if (destination.exists() && runCatching { verify(id, destination, ref.sha256) }.isFailure) {
            check(destination.renameTo(File(root,"corrupt-${ref.sha256}-${System.currentTimeMillis()}")))
        }
        if (!destination.exists()) check(staging.renameTo(destination)) { "Не удалось сохранить пакет" }
        verify(id, destination, ref.sha256)
        activate(root,ref.sha256)
        setStatus(id, "Физика готова")
    } }
    companion object {
        const val PILOT_ID = "716cdb43-703b-4d7e-8a79-27f03d92b99c"
        private val mutex = Mutex()
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
