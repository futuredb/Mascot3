package com.generativemascot.app.worker

import android.content.Context
import android.util.Base64
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.generativemascot.app.MascotApp
import com.generativemascot.app.data.AssetRefDto
import com.generativemascot.app.physics.PhysicsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

@Serializable data class PhysicsJobRequest(
    @SerialName("mascot_id") val mascotId: String,
    @SerialName("image_base64") val imageBase64: String,
    @SerialName("anchor_base64") val anchorBase64: String,
    @SerialName("character_contract") val characterContract: JsonObject,
)
@Serializable data class PhysicsJobDto(
    val id: String, val status: String, val stage: String,
    val error: String? = null,
    @SerialName("physics_pack") val physicsPack: AssetRefDto? = null,
)

class PhysicsWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("hero") ?: return Result.failure()
        val app = applicationContext as MascotApp; val store = PhysicsStore(app)
        try {
            inputData.getString("url")?.let { url ->
                store.install(app.api, id, AssetRefDto(url, inputData.getString("sha256") ?: error("Нет хеша пакета")))
                return Result.success()
            }
            val root = File(app.filesDir, "hero/$id")
            val source = File(root, "base.png").readBytes()
            val anchor = File(root, "videos/neutral.png").takeIf(File::isFile)?.readBytes() ?: source
            val contract = File(root, "creative/character-contract.json").takeIf(File::isFile)?.readText() ?: "{}"
            val request = PhysicsJobRequest(id, Base64.encodeToString(source, Base64.NO_WRAP), Base64.encodeToString(anchor, Base64.NO_WRAP), Json.parseToJsonElement(contract) as JsonObject)
            // The stable pilot token survives process death even before the response is saved.
            val token = "physics-v1-$id"
            var job = app.api.preparePhysics(token, request)
            store.saveJob(id, job.id)
            repeat(240) {
                if (job.status == "ready") {
                    store.install(app.api, id, job.physicsPack ?: error("Сервер не вернул пакет"))
                    return Result.success()
                }
                if (job.status in setOf("blocked", "failed")) error("Физика не подготовлена: ${job.error ?: job.stage}")
                store.setStatus(id, "Готовим физику…")
                delay(2000)
                job = app.api.physicsJob(job.id)
            }
            store.setStatus(id, "Подготовка продолжается на сервере")
            return Result.retry() // Server idempotency resumes the existing paid job.
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            if (e is java.io.IOException) {
                store.setStatus(id, "Нет связи. Подготовка продолжится после подключения")
                return Result.retry()
            }
            val message = when (e) {
                is retrofit2.HttpException -> if (e.code() == 404) "Сервер ещё не поддерживает физику" else "Ошибка сервера подготовки (${e.code()})"
                else -> e.message ?: "Не удалось подготовить физику"
            }
            store.setStatus(id, message.take(180))
            return Result.failure(workDataOf("error" to message.take(500)))
        }
    }
}
