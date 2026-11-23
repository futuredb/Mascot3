package com.generativemascot.app.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import com.generativemascot.app.MainActivity
import com.generativemascot.app.MascotApp
import com.generativemascot.app.R

internal const val CALENDAR_REMINDER_PREFERENCES = "mascot_calendar_reminders"

internal fun configureCalendarReminders(context: Context, enabled: Boolean, mode: String) {
    // The offline flow preview uses a plain Application with background work disabled.
    if (context.applicationContext !is MascotApp) return
    val work = WorkManager.getInstance(context.applicationContext)
    if (enabled && mode in setOf("proactive_guidance", "proactive_solutions")) {
        work.enqueueUniquePeriodicWork("mascot-calendar-reminders", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CalendarReminderWorker>(15, java.util.concurrent.TimeUnit.MINUTES).build())
    } else work.cancelUniqueWork("mascot-calendar-reminders")
}

/** Local, best-effort reminders for proactive modes; no calendar data is sent to a model. */
class CalendarReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MascotApp
        val context = applicationContext
        if (!context.getSharedPreferences("mascot_calendar", Context.MODE_PRIVATE).getBoolean("enabled", false) ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return Result.success()
        val heroId = app.session.acceptedMascotId() ?: app.session.mascotId() ?: return Result.success()
        val seen = context.getSharedPreferences(CALENDAR_REMINDER_PREFERENCES, Context.MODE_PRIVATE)
        if (!seen.getBoolean("$heroId:push", true)) return Result.success()
        val mode = context.getSharedPreferences("mascot_chat_interaction", Context.MODE_PRIVATE).getString(heroId, "request_guidance")
        if (mode != "proactive_guidance" && mode != "proactive_solutions") return Result.success()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now); ContentUris.appendId(it, now + 30 * 60_000L)
        }.build()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("mascot_calendar", "Напоминания персонажа", NotificationManager.IMPORTANCE_DEFAULT))
        val pending = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            val googleCalendars = mutableListOf<Long>()
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.VISIBLE} = ?",
                arrayOf("com.google", "1"), null)?.use { cursor ->
                while (cursor.moveToNext()) googleCalendars += cursor.getLong(0)
            }
            if (googleCalendars.isEmpty()) return Result.success()
            context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                "${CalendarContract.Instances.CALENDAR_ID} IN (${googleCalendars.joinToString(",") { "?" }})",
                googleCalendars.map { it.toString() }.toTypedArray(), "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getInt(3) != 0) continue
                    val start = cursor.getLong(2)
                    val id = "${cursor.getLong(0)}:$start"
                    if (seen.getBoolean(id, false)) continue
                    val title = cursor.getString(1)?.trim()?.take(120).orEmpty().ifBlank { "Событие" }
                    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(start))
                    manager.notify(id.hashCode(), NotificationCompat.Builder(context, "mascot_calendar")
                        .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Скоро событие")
                        .setContentText("$title — в $time").setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setContentIntent(pending).setAutoCancel(true).build())
                    seen.edit().putBoolean(id, true).apply()
                }
            }
            Result.success()
        } catch (_: SecurityException) { Result.success() }
    }
}
