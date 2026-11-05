package com.generativemascot.app.ui

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.generativemascot.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

private val reactorHttp = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

internal class HeliosPlayerController {
    var view: WebView? = null
    var ready = false
    fun react(image: String, reaction: String, context: String = "", appearance: String = ""): Boolean {
        val target = view ?: return false
        if (!ready) return false
        target.evaluateJavascript("window.reactMascot(${JSONObject.quote(image)},${JSONObject.quote(reaction)},${JSONObject.quote(context)},${JSONObject.quote(appearance)})", null)
        return true
    }
    fun stop() { view?.evaluateJavascript("window.stopMascot?.()", null) }
    fun shutdown() { view?.evaluateJavascript("window.closeMascot?.()", null) }
}

@Composable
internal fun HeliosPlayer(controller: HeliosPlayerController, mascotId: String, onEvent: (String, String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val archive = remember(controller, mascotId) { HeliosClipArchive(context, mascotId) }
    val currentEvent = rememberUpdatedState(onEvent)
    DisposableEffect(controller) {
        onDispose {
            val oldView = controller.view
            controller.view = null
            controller.ready = false
            if (oldView != null) {
                val handler = Handler(Looper.getMainLooper())
                oldView.evaluateJavascript("window.closeMascot?.()", null)
                // Let the final MediaRecorder chunk reach private storage after chat closes.
                handler.postDelayed({ oldView.stopLoading(); oldView.removeJavascriptInterface("MascotBridge"); oldView.destroy() }, 15000)
            }
        }
    }
    AndroidView(modifier = modifier, factory = { context ->
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.scheme != "https" || url.host != "appassets.androidplatform.net") return null
                    val asset = when (url.path) {
                        "/helios/index.html" -> "helios/index.html"
                        "/helios/android-helios.js" -> "helios/android-helios.js"
                        else -> return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    val mime = if (asset.endsWith(".js")) "text/javascript" else "text/html"
                    return WebResourceResponse(mime, "UTF-8", context.assets.open(asset))
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun beginClip(title: String, mime: String): String =
                    runCatching { archive.begin(title, mime) }.getOrDefault("")
                @JavascriptInterface fun appendClip(id: String, data: String): Boolean = archive.append(id, data)
                @JavascriptInterface fun finishClip(id: String): Boolean = archive.finish(id)
                @JavascriptInterface fun abortClip(id: String) = archive.abort(id)
                @JavascriptInterface fun reactorToken(): String {
                    return try {
                        check(BuildConfig.REACTOR_API_KEY.isNotBlank()) { "В сборке отсутствует ключ Reactor." }
                        val models = JSONObject().put("match", JSONArray().put("reactor/helios"))
                        val authorization = JSONObject().put("type", "session")
                            .put("resources", JSONObject().put("models", models))
                            .put("constraints", JSONObject().put("max_sessions", 1))
                        val body = JSONObject().put("authorization_details", JSONArray().put(authorization))
                        val req = Request.Builder().url("https://api.reactor.inc/tokens")
                            .header("Reactor-API-Key", BuildConfig.REACTOR_API_KEY)
                            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                        reactorHttp.newCall(req).execute().use { response ->
                            check(response.isSuccessful) { "Reactor не выдал доступ (HTTP ${response.code})." }
                            val jwt = JSONObject(response.body?.string() ?: "{}").getString("jwt")
                            JSONObject().put("jwt", jwt).toString()
                        }
                    } catch (e: Exception) {
                        JSONObject().put("error", e.message?.takeIf { it.startsWith("Reactor ") || it.startsWith("В сборке") } ?: "Не удалось подключиться к Reactor.").toString()
                    }
                }
                @JavascriptInterface fun event(type: String, detail: String) {
                    Handler(Looper.getMainLooper()).post {
                        if (type == "ready") controller.ready = true
                        currentEvent.value(type, detail.take(300))
                    }
                }
            }, "MascotBridge")
            controller.view = this
            loadUrl("https://appassets.androidplatform.net/helios/index.html")
        }
    })
}
