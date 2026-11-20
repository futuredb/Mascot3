package com.generativemascot.app.ui

import android.content.ContentUris
import android.content.Context
import android.content.ContentProviderOperation
import android.provider.CalendarContract
import org.json.JSONObject
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class CalendarEventRequest(val title: String, val start: Long, val end: Long, val defaultDuration: Boolean)
internal data class WritableGoogleCalendar(val id: Long, val name: String, val account: String, val primary: Boolean)

internal fun parseCalendarEvent(json: JSONObject): CalendarEventRequest? = runCatching {
    val title = json.getString("title").trim().take(120)
    val start = OffsetDateTime.parse(json.getString("start")).toInstant().toEpochMilli()
    val endText = json.optString("end", "").takeUnless { it.isBlank() || it == "null" }
    val end = endText?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() } ?: (start + 30 * 60_000L)
    require(title.isNotBlank() && end > start && end - start <= 24L * 60 * 60 * 1000)
    require(start > System.currentTimeMillis() - 60_000)
    CalendarEventRequest(title, start, end, endText == null)
}.getOrNull()

internal suspend fun writableGoogleCalendars(context: Context): List<WritableGoogleCalendar> = withContext(Dispatchers.IO) {
    val calendars = mutableListOf<WritableGoogleCalendar>()
    context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME),
        "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.VISIBLE} = ? AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
        arrayOf("com.google", "1", CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { cursor ->
        while (cursor.moveToNext()) calendars += WritableGoogleCalendar(cursor.getLong(0),
            cursor.getString(2).orEmpty().ifBlank { "Google Calendar" }, cursor.getString(3).orEmpty(), cursor.getInt(1) == 1)
    }
    calendars
}

/** The selected calendar is revalidated before writing. */
internal suspend fun createGoogleCalendarEvent(context: Context, event: CalendarEventRequest, selectedCalendarId: Long? = null): String = withContext(Dispatchers.IO) {
    val calendars = writableGoogleCalendars(context)
    val calendarId = if (selectedCalendarId != null) calendars.singleOrNull { it.id == selectedCalendarId }?.id
        else calendars.singleOrNull { it.primary }?.id ?: calendars.singleOrNull()?.id
    calendarId ?: return@withContext "Не удалось однозначно выбрать доступный Google-календарь. Проверь подключение и настройки календарей."
    val operations = arrayListOf(
        ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
            .withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
            .withValue(CalendarContract.Events.TITLE, event.title)
            .withValue(CalendarContract.Events.DTSTART, event.start)
            .withValue(CalendarContract.Events.DTEND, event.end)
            .withValue(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            .withValue(CalendarContract.Events.HAS_ALARM, 1).build(),
        ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
            .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0)
            .withValue(CalendarContract.Reminders.MINUTES, 60)
            .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT).build(),
    )
    val result = context.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
    val eventUri = result.firstOrNull()?.uri ?: return@withContext "Не удалось создать событие в календаре."
    // The calendar itself will deliver the one-hour reminder; avoid a second app notification.
    runCatching {
        val eventId = ContentUris.parseId(eventUri)
        context.getSharedPreferences("mascot_calendar_reminders", Context.MODE_PRIVATE).edit()
            .putBoolean("$eventId:${event.start}", true).apply()
    }
    val whenText = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(event.start))
    "Создал событие «${event.title}» на $whenText в Google Calendar и добавил напоминание за час." +
        if (event.defaultDuration) " Длительность поставил 30 минут — её можно изменить в календаре." else ""
}

internal fun explicitlyRejectsCalendarAction(text: String): Boolean =
    Regex("(?iu)не\\s+(добавляй|создавай|ставь|записывай|заноси|вноси|нужно добавлять|надо добавлять)").containsMatchIn(text) ||
        text.trim().lowercase(Locale.ROOT).trim('.', '!', '?') in setOf("нет", "отмена", "передумал", "передумала", "не надо")

/** Conservative local gate: only mentions of dated commitments may trigger an automatic action. */
internal fun mentionsScheduledEvent(text: String): Boolean {
    val value = text.lowercase(Locale.ROOT)
    if (explicitlyRejectsCalendarAction(value)) return false
    val date = Regex("(?iu)(сегодня|завтра|послезавтра|понедельник|вторник|сред[ау]|четверг|пятниц[ау]|суббот[ау]|воскресенье|[0-3]?\\d[./-][01]?\\d)").containsMatchIn(value)
    val event = listOf("встреч", "врач", "доктор", "стомат", "зубн", "прием", "приём", "созвон", "собеседован", "экзамен", "трениров", "заняти", "лекци", "концерт", "рейс", "событи", "визит", "ужин", "обед", "бронь", "перелёт", "поездк", "день рожден", "мероприяти", "appointment", "meeting").any(value::contains)
    return date && event
}

internal fun calendarActionAllowed(interactionId: String, pendingRequest: Boolean): Boolean =
    interactionId == "proactive_solutions" && pendingRequest

internal fun asksToCreateCalendarEvent(text: String): Boolean {
    val value = text.lowercase(Locale.ROOT)
    return listOf("созда", "добав", "постав", "запиш", "назнач", "запланир", "сделай", "занеси", "напомни", "schedule", "create", "add").any(value::contains) &&
        listOf("встреч", "событи", "календар", "напоминан", "напомни", "calendar", "event", "meeting", "reminder").any(value::contains)
}

internal fun isCalendarQuestion(text: String): Boolean {
    val value = text.lowercase(Locale.ROOT)
    return listOf("календар", "расписан", "встреч", "событи", "calendar", "schedule", "event").any(value::contains)
}

/** Read only local Google calendars which Android has already synchronized. */
internal suspend fun readUpcomingGoogleCalendar(context: Context): String = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    val end = now + 14L * 24 * 60 * 60 * 1000
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, now)
        ContentUris.appendId(it, end)
    }.build()
    val calendarIds = mutableListOf<String>()
    context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.VISIBLE} = ?",
        arrayOf("com.google", "1"), null)?.use { cursor ->
        while (cursor.moveToNext()) calendarIds += cursor.getLong(0).toString()
    }
    if (calendarIds.isEmpty()) return@withContext "На телефоне нет видимых синхронизированных Google-календарей."
    val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY)
    val events = mutableListOf<String>()
    context.contentResolver.query(uri, projection,
        "${CalendarContract.Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",") { "?" }})",
        calendarIds.toTypedArray(), "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
        val dateTime = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        while (cursor.moveToNext() && events.size < 30) {
            val name = (cursor.getString(0) ?: "Без названия").replace(Regex("[\\r\\n]+"), " ").take(120)
            val allDay = cursor.getInt(3) != 0
            val start = if (allDay) date.format(Date(cursor.getLong(1))) + " (весь день)" else dateTime.format(Date(cursor.getLong(1)))
            events += "$start — $name"
        }
    }
    if (events.isEmpty()) "В синхронизированных Google-календарях нет видимых событий в ближайшие 14 дней."
    else "Ближайшие события из синхронизированных Google-календарей (максимум 30; ближайшие 14 дней):\n" + events.joinToString("\n")
}
