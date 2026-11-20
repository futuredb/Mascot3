package com.generativemascot.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.generativemascot.app.MascotApp
import com.generativemascot.app.BuildConfig
import com.generativemascot.app.data.LightingPreparation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/** Upload/poll/download only. This worker has no paid generator dependency. */
class LightingPreparationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("hero") ?: return@withContext Result.failure()
        val kind = inputData.getString("kind") ?: return@withContext Result.failure()
        val hash = inputData.getString("sha256") ?: return@withContext Result.failure()
        val relative = inputData.getString("source") ?: return@withContext Result.failure()
        if (!id.matches(Regex("[A-Za-z0-9-]{1,80}")) || kind !in setOf("image", "video") || !hash.matches(Regex("[a-f0-9]{64}"))) return@withContext Result.failure()
        val app = applicationContext as MascotApp
        val store = LightingPreparation(app)
        // Older orphaned tasks may refer to files that no longer exist, and
        // therefore could not be found by unique-name queue cleanup.
        if (store.isExcluded(id)) return@withContext Result.success()
        if (BuildConfig.MASCOT3_CLIENT_TOKEN.isBlank()) {
            store.status(id, hash, "waiting_for_server_configuration")
            return@withContext Result.retry()
        }
        // WorkManager may start many recovered clips at once. Do not block its
        // workers in memory or upload several large videos concurrently: an
        // occupied slot retries later and Android can run newly saved work.
        if (!preparationSlot.tryLock()) return@withContext Result.retry()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        try {
            val source = File(app.filesDir, relative).canonicalFile
            require(source.path.startsWith(File(app.filesDir, "hero/$id").canonicalPath + File.separator))
            if (!source.isFile || LightingPreparation.hashFile(source) != hash) return@withContext Result.success()
            val existing = app.api.lightingSource(kind, hash)
            var job = if (existing.isSuccessful) requireNotNull(existing.body()) else {
                if (existing.code() != 404) throw retrofit2.HttpException(existing)
                app.api.prepareLighting(kind, hash, source.asRequestBody("application/octet-stream".toMediaType()))
            }
            repeat(12) {
                require(job.kind == kind && job.sourceSha256 == hash)
                store.status(id, hash, job.status)
                if (job.status == "ready") {
                    store.install(app.api, id, source, job)
                    android.util.Log.i("MascotDepth", "$kind ready in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
                    return@withContext Result.success()
                }
                if (job.status == "failed") return@withContext Result.failure()
                delay(5_000)
                job = app.api.lightingJob(job.id)
            }
            Result.retry()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val retry = error is java.io.IOException || error is retrofit2.HttpException && error.code() in setOf(404, 408, 429, 500, 502, 503, 504)
            android.util.Log.w("MascotDepth", "$kind ${if (retry) "retry" else "failed"}: ${error.javaClass.simpleName}")
            store.status(id, hash, if (retry) "waiting_for_server" else "failed")
            if (retry) Result.retry() else Result.failure()
        } finally {
            preparationSlot.unlock()
        }
    }

    private companion object {
        val preparationSlot = Mutex()
    }
}
