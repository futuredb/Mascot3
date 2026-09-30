package com.generativemascot.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.generativemascot.app.widget.updateMascotWidgets
import com.generativemascot.app.MascotApp

class ContextRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MascotApp
        app.behavior.refresh()
        val mascotId = app.session.acceptedMascotId() ?: app.session.mascotId()
        if (!app.openRouterSettings.isDirectModeEnabled() && mascotId != null) {
            runCatching { app.heroStore.sync(app.api, mascotId) }
        }
        updateMascotWidgets(applicationContext)
        return Result.success()
    }
}
