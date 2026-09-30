package com.generativemascot.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.generativemascot.app.MascotApp
import com.generativemascot.app.data.AcceptanceGreetingStore
import com.generativemascot.app.data.GenerationRoute
import com.generativemascot.app.data.OnDeviceMascotGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Approval + exactly greeting. Resumption uses the durable original token and provider job id. */
class AcceptanceGreetingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MascotApp
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val workflow = AcceptanceGreetingStore(app)
        val request = workflow.request(id) ?: return Result.failure()
        try {
            if (request.route == GenerationRoute.TEAM_SERVER) {
                app.api.accept(id)
                app.heroStore.sync(app.api, id)
            }
            check(app.heroStore.baseFile(id) != null) { "Не найдена сохранённая картинка героя" }
            app.session.saveAcceptedMascot(id)
            workflow.clearDraft(id)
            if (app.heroStore.actionVideoFile(id, "greeting") == null) {
                check(!request.completed) { "Приветствие уже создавалось; автоматический повтор отключён" }
                if (request.route == GenerationRoute.OPENROUTER_DIRECT) {
                    val key = app.openRouterSettings.apiKey() ?: error("Сохраните ключ OpenRouter в настройках")
                    OnDeviceMascotGenerator(app.heroStore, key).generatePerformanceVideo(id, request.token, listOf("greeting"))
                } else {
                    // An old server returns 404 here, not an unrelated default animation.
                    app.api.generateGreeting(id, request.token)
                    while (app.heroStore.actionVideoFile(id, "greeting") == null) {
                        app.heroStore.sync(app.api, id)
                        if (app.heroStore.actionVideoFile(id, "greeting") != null) break
                        val current = app.api.getMascot(id)
                        check(current.stages["animations"] !in setOf("failed", "partial", "ready")) {
                            "Приветствие не готово. Герой и промежуточные результаты сохранены"
                        }
                        delay(5_000)
                    }
                }
            }
            return Result.success(workDataOf(KEY_ID to id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = if (error is retrofit2.HttpException && error.code() == 404) {
                "Командный сервер нужно обновить для автоприветствия. Другие платные анимации не запускались"
            } else error.message ?: "Не удалось закончить приветствие"
            return Result.failure(workDataOf(KEY_ERROR to message.take(500)))
        }
    }
    companion object {
        const val KEY_ID = "greeting_hero_id"
        const val KEY_ERROR = "greeting_error"
        fun uniqueName(id: String) = "acceptance-greeting-$id"
    }
}
