package com.generativemascot.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.data.OnDeviceMascotGenerator
import com.generativemascot.app.data.SessionStore
import java.io.File

/**
 * Runs the paid image request outside the Activity so navigating away from the
 * app does not cancel it. A durable marker gives every WorkRequest at-most-once
 * request semantics: if Android restarts an interrupted worker, it reports the
 * interruption instead of silently spending credits on a second POST.
 */
class MascotGenerationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val requestToken = inputData.getString(KEY_REQUEST_TOKEN)
            ?: return Result.failure(errorData("Не найден номер запроса"))
        val mascotId = inputData.getString(KEY_MASCOT_ID)
            ?: return Result.failure(errorData("Не найден номер героя"))
        val animationsOnly = inputData.getBoolean(KEY_ANIMATIONS_ONLY, false)
        val store = HeroLocalStore(applicationContext)
        val generator = OnDeviceMascotGenerator(store)

        return runCatching {
            if (!animationsOnly && store.baseFile(mascotId) == null) {
                setProgress(Data.Builder().putString(KEY_STAGE, STAGE_BASE).build())
                markPaidStageOnce(requestToken, STAGE_BASE)
                generator.generateBase(mascotId)
            }
            // A fresh character stops after the base image so the user can reject
            // a weak design before spending the larger video budget. Animation is
            // a separate, explicit "Оживить" confirmation.
            if (animationsOnly && !store.animationPackReady(mascotId)) {
                setProgress(Data.Builder().putString(KEY_STAGE, STAGE_VIDEO).build())
                // Stored action clips/job ids mean a previous worker already
                // started this pack and must resume rather than pay twice.
                if (!store.hasOpenRouterVideoProgress(mascotId)) {
                    markPaidStageOnce(requestToken, STAGE_VIDEO)
                }
                generator.generatePerformanceVideo(mascotId)
            }
        }.fold(
            onSuccess = {
                SessionStore(applicationContext).saveAcceptedMascot(mascotId)
                Result.success(Data.Builder().putString(KEY_MASCOT_ID, mascotId).build())
            },
            onFailure = { error ->
                if (store.baseFile(mascotId) != null) {
                    SessionStore(applicationContext).saveAcceptedMascot(mascotId)
                }
                Result.failure(
                    Data.Builder()
                        .putString(KEY_ERROR, (error.message ?: "Генерация не удалась").take(500))
                        .putString(KEY_PARTIAL_MASCOT_ID, mascotId.takeIf { store.baseFile(it) != null })
                        .build(),
                )
            },
        )
    }

    private fun markPaidStageOnce(requestToken: String, stage: String) {
        val marker = File(applicationContext.filesDir, "generation-requests/$requestToken.$stage.started")
        marker.parentFile?.mkdirs()
        check(marker.createNewFile()) {
            "Этап генерации был прерван. Повторный платный запрос не запускался."
        }
    }

    private fun errorData(message: String): Data = Data.Builder()
        .putString(KEY_ERROR, message.take(500))
        .build()

    companion object {
        const val UNIQUE_WORK_NAME = "on-device-mascot-generation"
        const val KEY_REQUEST_TOKEN = "request_token"
        const val KEY_MASCOT_ID = "mascot_id"
        const val KEY_PARTIAL_MASCOT_ID = "partial_mascot_id"
        const val KEY_ANIMATIONS_ONLY = "animations_only"
        const val KEY_STAGE = "stage"
        const val KEY_ERROR = "error"
        const val STAGE_BASE = "base"
        const val STAGE_VIDEO = "video"
    }
}
