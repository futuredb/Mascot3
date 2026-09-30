package com.generativemascot.app.data

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** Written before enqueueing: only an explicit acceptance authorizes this one paid clip. */
internal data class AcceptanceGreetingRequest(
    val mascotId: String,
    val token: String,
    val workId: UUID,
    val route: GenerationRoute,
    val completed: Boolean = false,
)

internal class AcceptanceGreetingStore(context: Context) {
    private val preferences = context.getSharedPreferences("acceptance_greeting", Context.MODE_PRIVATE)
    fun draftId(): String? = preferences.getString("draft", null)
    fun unacceptedIds(): Set<String> = preferences.getStringSet("unaccepted", emptySet()).orEmpty().toSet()
    fun saveDraft(id: String) {
        check(preferences.edit().putString("draft", id).putStringSet("unaccepted", unacceptedIds() + id).commit())
    }
    fun clearDraft(id: String) {
        val edit = preferences.edit().putStringSet("unaccepted", unacceptedIds() - id)
        if (draftId() == id) edit.remove("draft")
        check(edit.commit())
    }
    fun pending(): AcceptanceGreetingRequest? = preferences.getString("pending", null)?.let(::request)
    fun request(id: String): AcceptanceGreetingRequest? = preferences.getString("request:$id", null)?.let {
        val json = JSONObject(it)
        AcceptanceGreetingRequest(id, json.getString("token"), UUID.fromString(json.getString("work")),
            GenerationRoute.valueOf(json.getString("route")), json.optBoolean("completed"))
    }

    @Synchronized fun begin(id: String, route: GenerationRoute): AcceptanceGreetingRequest {
        check(pending()?.mascotId.let { it == null || it == id }) { "Сначала дождитесь приветствия другого героя" }
        request(id)?.let {
            if (!it.completed) save(it)
            return it
        }
        return AcceptanceGreetingRequest(id, UUID.randomUUID().toString(), UUID.randomUUID(), route).also(::save)
    }

    /** User-confirmed resumption changes the WorkManager id, never the provider submission token. */
    @Synchronized fun resume(id: String): AcceptanceGreetingRequest {
        val previous = requireNotNull(request(id))
        check(!previous.completed)
        return previous.copy(workId = UUID.randomUUID()).also(::save)
    }

    @Synchronized fun complete(id: String) {
        val previous = requireNotNull(request(id))
        save(previous.copy(completed = true))
        clearDraft(id)
    }

    /** Stop blocking navigation, but retain the original paid token/job history for reconciliation. */
    @Synchronized fun defer(id: String) {
        if (pending()?.mascotId == id) check(preferences.edit().remove("pending").commit())
    }

    private fun save(request: AcceptanceGreetingRequest) {
        val value = JSONObject().put("token", request.token).put("work", request.workId.toString())
            .put("route", request.route.name).put("completed", request.completed).toString()
        val edit = preferences.edit().putString("request:${request.mascotId}", value)
        if (!request.completed) edit.putString("pending", request.mascotId)
        else if (preferences.getString("pending", null) == request.mascotId) edit.remove("pending")
        check(edit.commit()) { "Не удалось сохранить задание приветствия; платный запрос не запускался" }
    }
}
