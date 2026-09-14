package com.generativemascot.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.generativemascot.app.widget.updateMascotWidgets
import com.generativemascot.app.MascotApp
import com.generativemascot.app.data.LocalContextResolver

class ContextRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MascotApp
        val remoteState = runCatching { app.api.context(interact = false).stateKey }.getOrNull()
        val state = remoteState ?: LocalContextResolver().resolve()
        app.session.saveCurrentState(state)
        val mascotId = app.session.acceptedMascotId() ?: app.session.mascotId()
        if (mascotId != null) {
            runCatching { app.heroStore.sync(app.api, mascotId) }
        }
        updateMascotWidgets(applicationContext)
        return Result.success()
    }
}
