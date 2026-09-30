package com.generativemascot.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.generativemascot.app.MascotApp
import com.generativemascot.app.widget.updateMascotWidgets

/**
 * Extracting transparent widget frames from a local MP4 can take several seconds. It must never run
 * inside AppWidgetProvider.onUpdate because Android gives that broadcast a short execution window.
 */
class WidgetFrameWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val mascotId = inputData.getString(KEY_MASCOT_ID) ?: return Result.failure()
        val stateKey = inputData.getString(KEY_STATE_KEY) ?: "idle"
        val app = applicationContext as MascotApp
        return runCatching {
            app.heroStore.widgetAnimation(mascotId, stateKey)
            updateMascotWidgets(
                applicationContext,
                prepareAnimation = false,
                stateOverride = stateKey,
                preparedMascotId = mascotId,
            )
            Result.success()
        }.getOrElse {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_MASCOT_ID = "mascot_id"
        private const val KEY_STATE_KEY = "state_key"
        private const val MAX_ATTEMPTS = 2

        fun enqueue(context: Context, mascotId: String, stateKey: String) {
            val app = context.applicationContext as MascotApp
            if (!app.heroStore.needsWidgetPreparation(mascotId, stateKey)) return
            val request = OneTimeWorkRequestBuilder<WidgetFrameWorker>()
                .setInputData(workDataOf(KEY_MASCOT_ID to mascotId, KEY_STATE_KEY to stateKey))
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "mascot-widget-frames-$mascotId-$stateKey",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
