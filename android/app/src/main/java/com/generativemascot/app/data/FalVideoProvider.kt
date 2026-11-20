package com.generativemascot.app.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI

/** Queue requests are durable: submission is never automatically retried. */
internal class FalVideoProvider(
    private val client: OkHttpClient,
    private val key: String,
    private val baseUrl: String = "https://queue.fal.run",
) {
    private val falClient = client.newBuilder().followRedirects(false).dns(
        ProviderDnsFallback(client.dns) { host ->
            host == "queue.fal.run" || validFalVideoUrl("https://$host/")
        },
    ).build()

    suspend fun submit(reference: File, prompt: String, durationSeconds: Int): String = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "Ключ fal недоступен" }
        val image = "data:image/png;base64," + Base64.encodeToString(reference.readBytes(), Base64.NO_WRAP)
        val body = JSONObject().put("prompt", prompt).put("image_url", image).put("end_image_url", image)
            .put("duration", durationSeconds).put("resolution", "768P").put("prompt_expansion_mode", "disabled")
            .toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/minimax/h3-max-turbo/image-to-video")
            .header("Authorization", "Key $key").post(body).build()
        val result = executeJson(request)
        val id = result.optString("request_id")
        if (!validFalRequestId(id)) throw IOException("fal не вернул номер видеозадачи; повторная отправка заблокирована")
        FAL_JOB_PREFIX + id
    }

    suspend fun poll(jobId: String, action: String): JSONObject {
        val id = requireFalRequestId(jobId)
        val deadline = System.currentTimeMillis() + 30 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            val status = withContext(Dispatchers.IO) {
                executeJson(authorizedGet("$baseUrl/minimax/h3-max-turbo/requests/$id/status"))
            }
            when (status.optString("status")) {
                "COMPLETED" -> return status
                "FAILED", "CANCELLED" -> throw IOException("fal не создал анимацию $action. Повторный запрос не запускался.")
            }
            delay(3_000L)
        }
        throw IOException("fal ещё создаёт анимацию $action. Задача сохранена — повторная отправка не потребуется.")
    }

    suspend fun download(jobId: String): ByteArray = withContext(Dispatchers.IO) {
        val id = requireFalRequestId(jobId)
        val result = executeJson(authorizedGet("$baseUrl/minimax/h3-max-turbo/requests/$id"))
        val url = result.optJSONObject("video")?.optString("url").orEmpty()
        if (!validFalVideoUrl(url)) throw IOException("fal вернул некорректную ссылку на видео")
        // Never send the fal credential to the video CDN.
        falClient.newCall(Request.Builder().url(url).get().build())
            .execute().use { response ->
                if (!response.isSuccessful) throw IOException("Не удалось скачать видео fal (HTTP ${response.code})")
                response.body?.bytes()?.takeIf { it.size > 1024 } ?: throw IOException("fal вернул пустое видео")
            }
    }

    private fun authorizedGet(url: String): Request = Request.Builder().url(url)
        .header("Authorization", "Key $key").get().build()

    private fun executeJson(request: Request): JSONObject = falClient
        .newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401, 403 -> "Ключ fal не разрешает H3 Max Turbo"
                    402 -> "На балансе fal недостаточно средств"
                    429 -> "Достигнут лимит fal"
                    else -> "fal вернул ошибку HTTP ${response.code}"
                }
                throw IOException("$message. Автоматический повтор не запускался.")
            }
            try { JSONObject(raw) } catch (_: Exception) { throw IOException("fal вернул некорректный ответ") }
        }
}

internal const val FAL_JOB_PREFIX = "fal-h3-"
/** The greeting direction used for the successful H3 comparison. */
internal fun buildFalGreetingPrompt(matteHex: String, personality: String = "natural to the character"): String = """
    One continuous silent 6-second virtual pet animation. The same character in the supplied first image
    notices the viewer and gives one greeting natural to its body and established personality: $personality.
    Express this personality through posture, gesture and facial expression. Avoid generic cuteness or forced
    smiles when they contradict the character. Hold the starting pose
    briefly, then give one readable gesture of greeting and gently settle back to the exact original pose
    and expression before the end. The first and final images are identical anchors: match position,
    orientation, scale, silhouette, facial expression and breathing phase for a seamless loop. Smooth natural
    motion, easing, weight, soft follow-through, no sudden jumps or cuts. Keep the complete character inside
    the frame and preserve its exact identity, anatomy, colors and proportions as one complete indivisible body.
    Static camera, no camera movement, static scale, no extra objects or characters, no subtitles, no audio.
    Preserve the exact plain $matteHex background uniformly in every frame without shadows, texture,
    gradients or scenery.
""".trimIndent()
internal fun validFalRequestId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9-]{1,80}"))
internal fun requireFalRequestId(jobId: String): String = jobId.removePrefix(FAL_JOB_PREFIX)
    .also { require(jobId.startsWith(FAL_JOB_PREFIX) && validFalRequestId(it)) { "Некорректный номер задачи fal" } }
internal fun validFalVideoUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    val host = uri.host?.lowercase().orEmpty()
    uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && uri.port in listOf(-1, 443) &&
        (host == "fal.media" || host.endsWith(".fal.media") || host == "fal.ai" || host.endsWith(".fal.ai"))
}.getOrDefault(false)
