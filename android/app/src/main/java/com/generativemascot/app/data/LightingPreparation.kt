package com.generativemascot.app.data

import android.content.Context
import android.util.AtomicFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.generativemascot.app.BuildConfig
import com.generativemascot.app.physics.PhysicsStore
import com.generativemascot.app.ui.VideoLightingManifest
import com.generativemascot.app.worker.LightingPreparationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Serializable data class LightingFile(val file: String, val sha256: String, val size: Long, val url: String)
@Serializable data class LightingJob(
    val id: String, val kind: String, val status: String,
    @SerialName("source_sha256") val sourceSha256: String,
    @SerialName("manifest_sha256") val manifestSha256: String? = null,
    val files: List<LightingFile> = emptyList(), val error: String? = null,
)

/** Recovery schedules depth from saved images; it never creates new paid media. */
internal class LightingPreparation(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val root = File(context.filesDir, "lighting")
    private val recoveryPrefs = context.getSharedPreferences("lighting_recovery", Context.MODE_PRIVATE)
    internal fun isExcluded(id: String): Boolean = id in recoveryPrefs.getStringSet("excluded_existing", emptySet()).orEmpty()

    /** Pause only previously existing unwanted heroes; newly created IDs stay eligible. */
    internal suspend fun limitExistingTo(allowed: Set<String>) = withContext(Dispatchers.IO) {
        val folders = File(context.filesDir, "hero").listFiles().orEmpty().filter(File::isDirectory)
        require(allowed.all { id -> folders.any { it.name == id && File(it, "base.png").isFile } }) {
            "Не найдены все три выбранных персонажа; настройки не изменены"
        }
        val excluded = folders.map { it.name }.filter { it.matches(Regex("[A-Za-z0-9-]{1,80}")) && it !in allowed }.toSet()
        check(recoveryPrefs.edit().putStringSet("excluded_existing", excluded).commit())
        val work = WorkManager.getInstance(context)
        for (id in excluded) for (source in recoverySourcesFor(id)) {
            if (!source.isFile) continue
            val hash = hashFile(source)
            val kind = if (source.extension == "png") "image" else "video"
            for (prefix in listOf("lighting", "lighting-v2")) {
                work.cancelUniqueWork("$prefix-$id-$kind-$hash").result.get()
            }
        }
    }
    internal suspend fun clearExistingLimit() = withContext(Dispatchers.IO) {
        check(recoveryPrefs.edit().remove("excluded_existing").commit())
        resume()
    }
    fun trackHero(id: String) {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        File(context.filesDir, "hero/$id/lighting.enabled").apply { parentFile?.mkdirs(); createNewFile() }
    }
    suspend fun enqueueHero(id: String) = withContext(Dispatchers.IO) {
        sourcesFor(id).forEach { enqueue(id, it) }
    }
    internal fun sourcesFor(id: String): List<File> {
        val hero = File(context.filesDir, "hero/$id")
        if (!File(hero, "lighting.enabled").isFile) return emptyList()
        val sources = listOf(File(hero, "base.png"), File(hero, "performance.mp4")) +
            File(hero, "videos").listFiles().orEmpty().filter { it.extension == "mp4" && !it.name.contains("pending") }
        return sources.filter(File::isFile)
    }
    internal fun recoverySourcesFor(id: String): List<File> {
        if (!id.matches(Regex("[A-Za-z0-9-]{1,80}"))) return emptyList()
        // Recover older heroes as well: their active animation needs its own
        // pose-aligned normals, not just the static base image's depth pack.
        val hero = File(context.filesDir, "hero/$id")
        return (listOf(File(hero, "base.png"), File(hero, "performance.mp4")) +
            File(hero, "videos").listFiles().orEmpty().filter {
                it.extension == "mp4" && !it.name.contains("pending")
            }).filter(File::isFile)
    }
    suspend fun resume() = withContext(Dispatchers.IO) {
        val migration = context.getSharedPreferences("lighting_recovery", Context.MODE_PRIVATE)
        val needsMigration = !migration.getBoolean("queue_v2", false)
        var allScheduled = true
        File(context.filesDir, "hero").listFiles().orEmpty().filter(File::isDirectory).forEach { folder ->
            if (isExcluded(folder.name)) return@forEach
            recoverySourcesFor(folder.name).forEach { source ->
                try { enqueue(folder.name, source, recovery = true, migratedQueue = needsMigration) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    allScheduled = false
                    android.util.Log.w("MascotDepth", "Could not schedule saved depth source", error)
                }
            }
        }
        // Record only after all unique enqueue operations have reached the DB.
        // If the process dies halfway through, KEEP deduplicates the next pass.
        if (needsMigration && allScheduled) migration.edit().putBoolean("queue_v2", true).commit()
    }
    suspend fun enqueue(id: String, source: File, recovery: Boolean = false,
        migratedQueue: Boolean = false) = withContext(Dispatchers.IO) {
        if (isExcluded(id)) return@withContext
        if (!source.isFile) return@withContext
        val kind = if (source.extension == "png") "image" else "video"
        val hash = hashFile(source)
        if (kind == "video" && (File(root, "video/$hash/manifest.json").isFile ||
                runCatching { context.assets.open("video-lighting/$hash/manifest.json").close() }.isSuccess)) return@withContext
        if (kind == "image") {
            val physics = PhysicsStore(context)
            // A matching manifest alone is not enough: missing or damaged layer
            // files must be downloaded again instead of silently disabling depth.
            val valid = try { physics.load(id)?.pack?.sourceSha256 == hash }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            if (valid) return@withContext
        }
        val request = OneTimeWorkRequestBuilder<LightingPreparationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // Avoid exponential growth in retries when older clips contend.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf("hero" to id, "kind" to kind, "sha256" to hash,
                "source" to source.relativeTo(context.filesDir).path))
        // Fresh saves start immediately; legacy recovery yields to them.
        if (recovery) request.setInitialDelay(2, TimeUnit.MINUTES)
        // A parallel v2 name bypasses legacy hours-long backoffs without
        // cancelling work that might have begun between a check and replace.
        // Both workers still share the single preparation slot and server job.
        val prefix = if (migratedQueue) "lighting-v2" else "lighting"
        WorkManager.getInstance(context).enqueueUniqueWork("$prefix-$id-$kind-$hash", ExistingWorkPolicy.KEEP,
            request.build()).result.get()
    }
    fun status(id: String, hash: String, value: String) {
        context.getSharedPreferences("lighting-$id", Context.MODE_PRIVATE).edit().putString(hash, value).apply()
    }
    suspend fun install(api: MascotApi, id: String, source: File, job: LightingJob) = withContext(Dispatchers.IO) {
        require(job.status == "ready" && job.id.matches(HASH) && job.sourceSha256 == hashFile(source))
        require(job.manifestSha256?.matches(HASH) == true && job.files.size in 2..5)
        require(job.files.map { it.file }.toSet().size == job.files.size)
        require(job.files.sumOf { it.size } <= 96L * 1024 * 1024)
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val staging = File(root, "pending-${job.id}-$id").apply { mkdirs() }
        for (asset in job.files) {
            require(asset.file.matches(Regex("manifest\\.json|body_(color|depth)\\.png|normals-\\d{3}\\.png")))
            require(asset.sha256.matches(HASH) && asset.size in 1..(30L * 1024 * 1024))
            val url = assetUrl(BuildConfig.API_BASE_URL, job.id, asset)
            val target = File(staging, asset.file)
            if (target.isFile && target.length() == asset.size && hashFile(target) == asset.sha256) continue
            api.fetchBytes(url).use { response ->
                require(response.contentLength() <= asset.size)
                val atomic = AtomicFile(target); val output = atomic.startWrite()
                try {
                    var count = 0L
                    response.byteStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) { val read = input.read(buffer); if (read < 0) break
                            count += read; require(count <= asset.size); output.write(buffer, 0, read) }
                    }
                    require(count == asset.size)
                    atomic.finishWrite(output)
                } catch (error: Exception) { atomic.failWrite(output); throw error }
            }
            require(hashFile(target) == asset.sha256) { "Повреждена карта света" }
        }
        require(hashFile(File(staging, "manifest.json")) == job.manifestSha256)
        require(hashFile(source) == job.sourceSha256) // Source may have changed during the download.
        if (job.kind == "image") {
            PhysicsStore(context).installPrepared(id, staging, job.manifestSha256!!)
        } else {
            require(job.kind == "video")
            val manifest = json.decodeFromString<VideoLightingManifest>(File(staging, "manifest.json").readText())
            manifest.validate()
            require(manifest.sourceSha256 == job.sourceSha256 && manifest.sourceBytes == source.length())
            manifest.pages.forEachIndexed { index, name ->
                val file = File(staging, name)
                require(hashFile(file) == manifest.pageSha256[index])
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                val count = minOf(manifest.pageFrames, manifest.timestampsUs.size - index * manifest.pageFrames)
                require(bounds.outWidth == manifest.columns * manifest.tileSize &&
                    bounds.outHeight == (count + manifest.columns - 1) / manifest.columns * manifest.tileSize)
            }
            val destination = File(root, "video/${job.sourceSha256}").apply { parentFile?.mkdirs() }
            // Immutable content-addressed pack; never expose a partial atlas.
            if (!destination.exists()) check(staging.renameTo(destination) || destination.exists())
            require(hashFile(File(destination, "manifest.json")) == job.manifestSha256)
            if (staging.exists()) staging.deleteRecursively()
        }
        status(id, job.sourceSha256, "ready")
    }
    companion object {
        private val HASH = Regex("[a-f0-9]{64}")
        fun hashFile(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun assetUrl(base: String, jobId: String, asset: LightingFile): String {
            val origin = URI(base + "/"); val resolved = origin.resolve(asset.url)
            require(resolved.scheme == origin.scheme && resolved.host == origin.host && resolved.port == origin.port &&
                resolved.userInfo == null && resolved.query == null && resolved.fragment == null &&
                resolved.path == "/v1/lighting/jobs/$jobId/assets/${asset.file}")
            return resolved.toString()
        }
    }
}
