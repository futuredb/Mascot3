package com.generativemascot.app.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.generativemascot.app.BuildConfig
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Explicit read-only probe under the application's UID, including its VPN policy. */
class BackendConnectivityDiagnosticTest {
    @Test fun recordBackendReachability() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("diagnoseBackend") == "true")
        val traces = JSONArray()
        for (path in listOf("/health", "/v1/physics/jobs/7c2caaca1b682e57ad76e0eb53085b46")) {
            val record = JSONObject().put("path", path)
            val events = JSONArray()
            val client = OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false)
                .eventListener(object : EventListener() {
                    override fun connectStart(call: Call, address: InetSocketAddress, proxy: Proxy) {
                        events.put("connect:${address.address?.hostAddress}:${address.port}")
                    }
                    override fun secureConnectStart(call: Call) { events.put("tls_start") }
                    override fun connectionAcquired(call: Call, connection: okhttp3.Connection) {
                        events.put("connected:${connection.protocol()}")
                    }
                }).build()
            try {
                val request = Request.Builder().url(BuildConfig.API_BASE_URL + path)
                    .header("X-Mascot-Client-Token", BuildConfig.MASCOT3_CLIENT_TOKEN).build()
                client.newCall(request).execute().use { record.put("http", it.code) }
            } catch (error: Exception) {
                val causes = JSONArray()
                var cause: Throwable? = error
                while (cause != null && causes.length() < 5) {
                    causes.put("${cause.javaClass.simpleName}: ${cause.message}"); cause = cause.cause
                }
                record.put("failure", causes)
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            record.put("events", events); traces.put(record)
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(app.getExternalFilesDir(null), "startup-acceptance").apply { mkdirs() }
        File(output, "backend-connectivity.json").writeText(JSONObject().put("requests", traces).toString(2))
    }
}
