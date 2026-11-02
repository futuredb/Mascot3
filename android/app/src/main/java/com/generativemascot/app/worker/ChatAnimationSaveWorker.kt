package com.generativemascot.app.worker

import android.content.Context
import android.util.AtomicFile
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.generativemascot.app.data.HeroLocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private val client = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()

/** Downloads an already-created clip; this worker never submits an AI request. */
class ChatAnimationSaveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val hero = inputData.getString("hero") ?: return@withContext Result.failure()
        val id = inputData.getString("job")?.takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: return@withContext Result.failure()
        val title = inputData.getString("title").orEmpty().take(100)
        val folder = runCatching { HeroLocalStore(applicationContext).chatAnimationDirectory(hero) }.getOrNull()
            ?: return@withContext Result.failure()
        val destination = File(folder, "$id.mp4")
        if (destination.isFile && destination.length() > 1024) return@withContext Result.success()
        try {
            val request = Request.Builder().url("http://127.0.0.1:8811/api/turbo/animation/$id").get().build()
            val response = client.newCall(request).execute()
            val job = response.use {
                if (it.code == 404) return@withContext if (runAttemptCount < 12) Result.retry() else Result.failure()
                if (!it.isSuccessful) return@withContext Result.retry()
                JSONObject(it.body?.string() ?: "{}")
            }
            when (job.optString("status")) {
                "generating", "unknown" -> return@withContext Result.retry()
                "ready" -> Unit
                else -> return@withContext Result.failure()
            }
            val url = job.optString("video").toHttpUrlOrNull() ?: return@withContext Result.failure()
            if (url.scheme != "https" || !(url.host == "fal.media" || url.host.endsWith(".fal.media"))) return@withContext Result.failure()
            folder.mkdirs()
            val staging = File(folder, "$id.pending")
            try {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { result ->
                    if (!result.isSuccessful) return@withContext Result.retry()
                    val body = result.body ?: return@withContext Result.retry()
                    if (body.contentLength() > 100_000_000L) return@withContext Result.failure()
                    body.byteStream().use { source -> staging.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var total = 0L
                        while (true) { val n = source.read(buf); if (n < 0) break; total += n
                            if (total > 100_000_000L) throw IllegalStateException("Video too large")
                            out.write(buf, 0, n)
                        }
                        out.fd.sync()
                    } }
                }
                if (staging.length() <= 1024) return@withContext Result.retry()
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(staging.absolutePath)
                    if ((retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) <= 0)
                        return@withContext Result.retry()
                } finally { retriever.release() }
                val meta = AtomicFile(File(folder, "$id.json")); val out = meta.startWrite()
                try {
                    val json = JSONObject().put("title", title).put("createdAt", job.optLong("createdAt", System.currentTimeMillis()))
                    out.write(json.toString().toByteArray(Charsets.UTF_8)); meta.finishWrite(out)
                } catch (e: Exception) { meta.failWrite(out); throw e }
                if (!staging.renameTo(destination)) return@withContext Result.retry()
                Result.success()
            } finally { staging.delete() }
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { Result.retry() }
    }
}

suspend fun scheduleChatAnimationSave(context: Context, hero: String, session: String, request: String, title: String) = withContext(Dispatchers.IO) {
    val id = MessageDigest.getInstance("SHA-256").digest("$session:$request".toByteArray(Charsets.UTF_8))
        .take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
    val work = OneTimeWorkRequestBuilder<ChatAnimationSaveWorker>()
        .setInputData(workDataOf("hero" to hero, "job" to id, "title" to title.take(100)))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
    WorkManager.getInstance(context).enqueueUniqueWork("chat-animation-$id", ExistingWorkPolicy.KEEP, work).result.get(10, TimeUnit.SECONDS)
}
