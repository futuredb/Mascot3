package com.generativemascot.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import java.io.IOException
import javax.net.ssl.SSLException

internal enum class ServerIssue(val message: String) {
    UNREACHABLE("Сервер недоступен в этой сети. Сохранённые герои работают офлайн."),
    AUTHORIZATION("Сервер отклонил доступ. Нужно обновить настройки подключения."),
    TLS("Не удалось установить защищённое соединение с сервером."),
    SERVER("Сервер временно не отвечает. Сохранённые герои доступны."),
    RESPONSE("Не удалось прочитать ответ сервера. Сохранённые герои доступны."),
}

internal fun serverIssue(error: Exception): ServerIssue = when (error) {
    is HttpException -> if (error.code() in setOf(401, 403)) ServerIssue.AUTHORIZATION else ServerIssue.SERVER
    is SSLException -> ServerIssue.TLS
    is IOException -> ServerIssue.UNREACHABLE
    else -> ServerIssue.RESPONSE
}

/** Read-only status: 404 means no active server hero, not a connection failure. */
internal suspend fun readServerIssue(readStatus: suspend () -> Int, timeoutMs: Long = STARTUP_REMOTE_TIMEOUT_MS): ServerIssue? {
    return try {
        val status = withTimeoutOrNull(timeoutMs) { readStatus() } ?: return ServerIssue.UNREACHABLE
        when (status) {
            in 200..299, 404 -> null
            401, 403 -> ServerIssue.AUTHORIZATION
            else -> ServerIssue.SERVER
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        serverIssue(error)
    }
}
