package com.generativemascot.app.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.generativemascot.app.MascotApp
import com.generativemascot.app.data.ManifestDto
import com.generativemascot.app.widget.updateMascotWidgets
import java.util.concurrent.TimeUnit

/**
 * Waits for an explicitly requested media pack, then refreshes the files used
 * by the widget.  The manifest is the source of truth: a state is considered
 * ready only after its still or extracted video frames are published.
 */
class MediaRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val mascotId = inputData.getString(KEY_MASCOT_ID) ?: return Result.failure()
        val kind = inputData.getString(KEY_KIND) ?: return Result.failure()
        val requested = inputData.getStringArray(KEY_STATES).orEmpty().toSet()
        val app = applicationContext as MascotApp

        val manifest = runCatching { app.api.manifest(mascotId) }.getOrElse {
            return Result.retry()
        }
        runCatching { app.heroStore.sync(app.api, mascotId) }.getOrElse {
            return Result.retry()
        }
        runCatching {
            val currentState = app.api.context(interact = false).stateKey
            app.session.saveCurrentState(currentState)
            updateMascotWidgets(applicationContext)
        }.getOrElse {
            return Result.retry()
        }

        val complete = requested.all { stateKey -> manifest.hasPublishedMedia(stateKey, kind) }
        return if (complete || runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
    }

    private fun ManifestDto.hasPublishedMedia(stateKey: String, kind: String): Boolean {
        if (stateKey == "base") {
            return if (kind == "video") !baseSequence?.frames.isNullOrEmpty() else base != null
        }
        val state = states[stateKey] ?: return false
        return if (kind == "video") {
            state.animation != null || !state.sequence?.frames.isNullOrEmpty()
        } else {
            state.status == "ready" && state.still != null
        }
    }

    companion object {
        private const val KEY_MASCOT_ID = "mascot_id"
        private const val KEY_KIND = "kind"
        private const val KEY_STATES = "states"
        private const val MAX_ATTEMPTS = 30

        fun enqueue(context: Context, mascotId: String, kind: String, states: List<String>) {
            if (states.isEmpty()) return
            val data = Data.Builder()
                .putString(KEY_MASCOT_ID, mascotId)
                .putString(KEY_KIND, kind)
                .putStringArray(KEY_STATES, states.toTypedArray())
                .build()
            val request = OneTimeWorkRequestBuilder<MediaRefreshWorker>()
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "mascot-media-$mascotId-$kind",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
