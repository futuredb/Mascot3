package com.generativemascot.app.worker

import android.content.Context
import androidx.work.*
import com.generativemascot.app.MascotApp
import com.generativemascot.app.widget.updateMascotWidgets

/** Legacy alarm entry point. Selection is delegated to the common StateResolver. */
class WidgetMoodWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MascotApp
        app.behavior.refresh()
        updateMascotWidgets(applicationContext)
        return Result.success()
    }
    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("mascot-widget-mood", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WidgetMoodWorker>().build())
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("mascot-widget-mood") }
    }
}
