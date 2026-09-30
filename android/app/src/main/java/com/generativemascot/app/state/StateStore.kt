package com.generativemascot.app.state

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val stateJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

internal fun AtomicFile.readTextOrNull(): String? = runCatching {
    openRead().bufferedReader().use { it.readText() }
}.getOrNull()

internal fun AtomicFile.writeTextSafely(text: String) {
    baseFile.parentFile?.mkdirs()
    val stream = startWrite()
    try { stream.write(text.toByteArray(Charsets.UTF_8)); finishWrite(stream) }
    catch (error: Exception) { failWrite(stream); throw error }
}

class StateStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "behavior/state-v3.json"))
    private val lock = Mutex()
    private val mutable = MutableStateFlow(file.readTextOrNull()?.let {
        runCatching { stateJson.decodeFromString<StateRecord>(it) }.getOrNull()
    } ?: StateRecord())
    val states = mutable.asStateFlow()
    val current get() = mutable.value
    suspend fun update(change: (StateRecord) -> StateRecord): StateRecord = withContext(Dispatchers.IO) {
        lock.withLock {
            val next = change(current)
            if (next != current) {
                file.writeTextSafely(stateJson.encodeToString(next))
                mutable.value = next
            }
            next
        }
    }
}

class StateConfigStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "behavior/config-v3.json"))
    @Synchronized fun get(): StateConfig = file.readTextOrNull()?.let {
        runCatching { stateJson.decodeFromString<StateConfig>(it).validate() }.getOrNull()
    } ?: StateConfig()
    @Synchronized fun save(raw: String): StateConfig {
        require(raw.length <= 100_000) { "Конфиг слишком большой" }
        val config = stateJson.decodeFromString<StateConfig>(raw).validate()
        file.writeTextSafely(stateJson.encodeToString(config))
        return config
    }
    fun text(): String = stateJson.encodeToString(get())
}

/** Bounded local analytics only. No keys, network identifiers or raw health values. */
class StateAnalytics(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "behavior/events.jsonl"))
    @Synchronized fun log(event: String, record: StateRecord, previous: PetState?, now: Long,
        surface: String? = null, action: String? = null) {
        val weather = record.weatherHistory.lastOrNull().takeIf { record.triggerReason.startsWith("weather_") }
        val payload = org.json.JSONObject().put("event", event).put("state_id", record.currentStateId.name)
            .put("trigger_reason", record.triggerReason).put("timestamp", now)
            .put("duration", (now - record.stateStartedAt).coerceAtLeast(0))
            .put("previous_state", previous?.name).put("revision", record.revision)
        surface?.let { payload.put("surface", it) }
        action?.let { payload.put("animation_id", it) }
        weather?.let {
            payload.put("weather_condition", it.condition).put("cloud_cover", it.cloudCover)
                .put("temperature", it.temperature)
        }
        runCatching {
            val lines = file.readTextOrNull().orEmpty().lineSequence().filter { it.isNotBlank() }.toList().takeLast(499)
            file.writeTextSafely((lines + payload.toString()).joinToString("\n", postfix = "\n"))
        }
    }
}

/** Rendering fallback does not silently select another semantic state. */
fun renderedAction(record: StateRecord, available: Set<String>): String {
    if (record.currentStateId.action in available) return record.currentStateId.action
    if ("idle" in available) return "idle"
    if (record.lastRenderedAction in available) return record.lastRenderedAction!!
    if ("greeting" in available) return "greeting"
    return available.firstOrNull() ?: "idle"
}
