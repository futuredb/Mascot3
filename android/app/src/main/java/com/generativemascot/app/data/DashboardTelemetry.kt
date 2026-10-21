package com.generativemascot.app.data

import android.content.Context
import androidx.work.*
import com.generativemascot.app.BuildConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Metadata only. No API key, prompt, generated content or file enters the outbox. */
object DashboardTelemetry {
    private var context: Context? = null
    private val lock = Any()
    private val allowed = setOf("attemptId","provider","model","credentialId","billingScope","outcome","statusCode","durationMs","providerRequestId","inputTokens","outputTokens","cachedTokens","reasoningTokens","images","videoSeconds","cost","currency","costSource","action","errorCode")
    private const val RESOURCE = "prod01:application:9ronyeoqd35ij22pt12uyhdc"
    private const val ENDPOINT = "https://dashboard.future-db.ru/api/v1/events/client"
    private var sessionId = UUID.randomUUID().toString()
    private var lastVisit = 0L
    private val sender = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    fun initialize(value: Context) {
        if (BuildConfig.BUILD_TYPE == "flowdemo" || BuildConfig.DEBUG) return
        context = value.applicationContext
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(value).enqueueUniquePeriodicWork("dashboard-telemetry-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DashboardTelemetryWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
    }

    fun visit() {
        synchronized(lock) {
            if (System.currentTimeMillis() - lastVisit > 30 * 60_000) sessionId = UUID.randomUUID().toString()
            lastVisit = System.currentTimeMillis()
        }
        emit("client_session", JSONObject())
    }

    fun emit(kind: String, fields: JSONObject) {
        val app = context ?: return
        runCatching {
            synchronized(lock) {
                val prefs = app.getSharedPreferences("dashboard-telemetry", Context.MODE_PRIVATE)
                val visitor = prefs.getString("installation", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("installation", it).commit() }
                val directory = File(app.filesDir, "dashboard-outbox").apply { mkdirs() }
                val files = directory.listFiles { f -> f.extension == "json" }.orEmpty()
                var dropped = prefs.getInt("dropped", 0)
                if (files.size >= 5000) { dropped++; prefs.edit().putInt("dropped", dropped).apply(); return }
                val event = JSONObject().put("version", 1).put("eventId", UUID.randomUUID().toString()).put("resourceId", RESOURCE)
                    .put("occurredAt", Instant.now().toString()).put("kind", kind).put("sdkVersion", "1.0.0")
                    .put("visitorId", visitor).put("sessionId", sessionId).put("identityKind", "installation").put("device", "android").put("dropped", dropped)
                fields.keys().forEach { key -> if (key in allowed && !fields.isNull(key)) event.put(key, fields.get(key)) }
                val target = File(directory, "${System.currentTimeMillis()}_${event.getString("eventId")}.json")
                val temporary = File(directory, target.name + ".pending")
                temporary.writeText(event.toString())
                check(temporary.renameTo(target))
            }
            WorkManager.getInstance(app).enqueueUniqueWork("dashboard-telemetry-flush", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DashboardTelemetryWorker>().setInitialDelay(10, TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }

    internal fun flush(): Boolean {
        val app = context ?: return true
        return runCatching {
            repeat(10) {
                val files = synchronized(lock) {
                    File(app.filesDir, "dashboard-outbox").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.take(50)
                }
                if (files.isEmpty()) return true
                val valid = files.filter { file ->
                    if (System.currentTimeMillis()-file.lastModified()>89L*86400000) { file.delete(); false } else true
                }
                if (valid.isEmpty()) return@repeat
                val events = JSONArray().apply { valid.forEach { put(JSONObject(it.readText())) } }
                val request = Request.Builder().url(ENDPOINT).header("X-Dashboard-Client", "android")
                    .post(JSONObject().put("events", events).toString().toRequestBody("application/json".toMediaType())).build()
                sender.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return false
                    val ack = JSONObject(response.body?.string().orEmpty())
                    if (ack.optInt("accepted") + ack.optInt("duplicates") != valid.size) return false
                }
                synchronized(lock) { valid.forEach { it.delete() } }
            }
            true
        }.getOrDefault(false)
    }

    // The bounded image-response suffix may begin inside base64 data. Balance the
    // usage object instead of stopping at its first nested token-details object.
    private fun usageFromSuffix(text: String): JSONObject? {
        val marker = Regex("\"usage\"\\s*:\\s*\\{").find(text) ?: return null
        val start = marker.range.last
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '\"') quoted = false
            } else when (char) {
                '\"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return runCatching { JSONObject(text.substring(start, index + 1)) }.getOrNull()
            }
        }
        return null
    }

    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath
        val post = request.method == "POST" && Regex(".*/(chat/completions|images(?:/generations)?|videos(?:/generations)?)$").matches(path)
        val jobId = if (request.method == "GET" && Regex(".*/videos/[^/]+$").matches(path)) path.substringAfterLast('/') else null
        val app = context
        if (app == null || request.url.host != "openrouter.ai" || (!post && jobId == null)) return@Interceptor chain.proceed(request)
        val prefs = app.getSharedPreferences("dashboard-jobs", Context.MODE_PRIVATE)
        val prior = jobId?.let { prefs.getString(it, null) }?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (!post && prior == null) return@Interceptor chain.proceed(request)
        val contextFields = prior ?: run {
            val head = Buffer()
            val sink = object: ForwardingSink(blackholeSink()) {
                override fun write(source: Buffer, byteCount: Long) {
                    if (head.size < 8192) source.copyTo(head, 0, minOf(byteCount, 8192-head.size))
                    super.write(source, byteCount)
                }
            }.buffer()
            runCatching { if (request.body?.isOneShot() != true && request.body?.isDuplex() != true) { request.body?.writeTo(sink); sink.flush() } }
            val prefix = head.readUtf8()
            val model = Regex("\"model\"\\s*:\\s*\"([A-Za-z0-9_.:/@~-]+)\"").find(prefix)?.groupValues?.get(1) ?: "unknown"
            val team = BuildConfig.OPENROUTER_API_KEY.isNotBlank() && request.header("Authorization") == "Bearer ${BuildConfig.OPENROUTER_API_KEY}"
            JSONObject().put("attemptId", UUID.randomUUID().toString()).put("provider", "openrouter").put("model", model)
                .put("startedAt",System.currentTimeMillis()).put("credentialId", if (team) "mascot3-team" else "personal").put("billingScope", if (team) "team" else "personal")
        }
        val started = contextFields.optLong("startedAt",System.currentTimeMillis())
        if (post) emit("ai_attempt", JSONObject(contextFields.toString()).put("outcome", "started").put("costSource", "unknown"))
        try {
            val response = chain.proceed(request)
            val body = response.body ?: return@Interceptor response
            val capture = Buffer()
            var completed = false
            fun finish(eof: Boolean) {
                if (completed) return
                completed = true
                runCatching {
                    val tail = capture.readUtf8()
                    val raw = runCatching { JSONObject(tail) }.getOrElse {
                        // Images may be tens of MB. Only retain the bounded suffix containing usage.
                        JSONObject().apply { usageFromSuffix(tail)?.let { put("usage", it) } }
                    }
                    val status = raw.optString("status")
                    if (post && (path.endsWith("/videos") || path.endsWith("/videos/generations")) && response.isSuccessful && raw.optString("id").isNotBlank() && status != "completed" && status != "failed") {
                        prefs.edit().putString(raw.getString("id"), contextFields.toString()).apply()
                        return@runCatching
                    }
                    if (!post && status !in setOf("completed", "failed")) return@runCatching
                    val event = JSONObject(contextFields.toString()).put("statusCode", response.code)
                        .put("durationMs", (System.currentTimeMillis()-started).coerceIn(0L,86400000L)).put("costSource", "unknown")
                        .put("outcome", if (!response.isSuccessful || status == "failed") "error" else if (eof) "success" else "unknown")
                    val usage = raw.optJSONObject("usage") ?: JSONObject()
                    for ((target, keys) in mapOf("inputTokens" to listOf("prompt_tokens","input_tokens"), "outputTokens" to listOf("completion_tokens","output_tokens"))) {
                        keys.firstOrNull { usage.has(it) && !usage.isNull(it) }?.let { event.put(target, usage.getLong(it)) }
                    }
                    val cost = runCatching { BigDecimal(usage.get("cost").toString()) }.getOrNull()
                    if (cost != null && cost.signum() >= 0) event.put("cost", cost.setScale(minOf(cost.scale(),15),java.math.RoundingMode.HALF_UP).toPlainString()).put("currency", "USD").put("costSource", "client_reported")
                    if (raw.optString("id").isNotBlank()) event.put("providerRequestId", raw.getString("id"))
                    emit("ai_attempt", event)
                    if (jobId != null) prefs.edit().remove(jobId).apply()
                }
            }
            val source = object: ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val read = super.read(sink, byteCount)
                    if (read > 0) { sink.copyTo(capture, sink.size-read, read); if (capture.size>262144) capture.skip(capture.size-262144) }
                    if (read == -1L) finish(true)
                    return read
                }
                override fun close() { try { super.close() } finally { finish(false) } }
            }.buffer()
            response.newBuilder().body(object: ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        } catch (error: Exception) {
            if (post) emit("ai_attempt", JSONObject(contextFields.toString()).put("outcome", "error").put("costSource", "unknown").put("errorCode", "PROVIDER_UNAVAILABLE"))
            throw error
        }
    }
}

class DashboardTelemetryWorker(context: Context, parameters: WorkerParameters): Worker(context, parameters) {
    override fun doWork(): Result {
        DashboardTelemetry.initialize(applicationContext)
        return if (DashboardTelemetry.flush()) Result.success() else Result.retry()
    }
}
