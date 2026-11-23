package com.generativemascot.app.ui

import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.generativemascot.app.MascotApp
import com.generativemascot.app.BuildConfig
import com.generativemascot.app.data.CharacterGlitchPalette
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
import java.io.File
import java.security.MessageDigest

private val reactorHttp = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

internal class HeliosPlayerController {
    var view: WebView? = null
    var ready = false
    var generation = 0
        private set
    private var pendingReaction: String? = null
    private var pendingReply: String? = null
    private var portraitVisible = false
    private var glitchEnabled = false
    fun invalidate() {
        generation++
        pendingReaction = null
        pendingReply = null
    }
    fun markReady(target: WebView) {
        if (view !== target) return
        ready = true
        target.evaluateJavascript("window.setMascotVisibility?.($portraitVisible)", null)
        target.evaluateJavascript("window.setMascotGlitchEnabled?.($glitchEnabled)", null)
        pendingReaction?.let { target.evaluateJavascript(it, null) }
        pendingReply?.let { target.evaluateJavascript(it, null) }
        pendingReaction = null
        pendingReply = null
    }
    fun react(image: String, reaction: String, context: String = "", appearance: String = "",
        reply: String = "", prewarm: Boolean = false): Boolean {
        val command = "window.reactMascot(${JSONObject.quote(image)},${JSONObject.quote(reaction)},${JSONObject.quote(context)},${JSONObject.quote(appearance)},${JSONObject.quote(reply)},$prewarm)"
        val target = view
        if (target == null || !ready) pendingReaction = command else target.evaluateJavascript(command, null)
        return true
    }
    fun restart(image: String, reaction: String, context: String, appearance: String, reply: String): Boolean {
        val command = "window.restartMascot(${JSONObject.quote(image)},${JSONObject.quote(reaction)},${JSONObject.quote(context)},${JSONObject.quote(appearance)},${JSONObject.quote(reply)})"
        val target = view
        if (target == null || !ready) {
            pendingReaction = command
            pendingReply = null
        } else target.evaluateJavascript(command, null)
        return true
    }
    fun reply(image: String, reaction: String, context: String, appearance: String, reply: String) {
        val command = "window.replyMascot(${JSONObject.quote(image)},${JSONObject.quote(reaction)},${JSONObject.quote(context)},${JSONObject.quote(appearance)},${JSONObject.quote(reply)})"
        val target = view
        if (target == null || !ready) pendingReply = command else target.evaluateJavascript(command, null)
    }
    fun setPortraitVisible(visible: Boolean) {
        portraitVisible = visible
        if (ready) view?.evaluateJavascript("window.setMascotVisibility?.($visible)", null)
    }
    fun setGlitchEnabled(enabled: Boolean) {
        if (glitchEnabled == enabled) return
        glitchEnabled = enabled
        if (ready) view?.evaluateJavascript("window.setMascotGlitchEnabled?.($enabled)", null)
    }
    fun stop() { invalidate(); view?.evaluateJavascript("window.stopMascot?.()", null) }
    fun shutdown() { invalidate(); view?.evaluateJavascript("window.closeMascot?.()", null) }
}

@Composable
internal fun HeliosPlayer(
    controller: HeliosPlayerController,
    mascotId: String,
    glitchPalette: CharacterGlitchPalette,
    glitchEnabled: Boolean,
    onEvent: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reactorApiKey = (context.applicationContext as MascotApp).reactorSettings.apiKey().orEmpty()
    val archive = remember(controller, mascotId) { HeliosClipArchive(context, mascotId) }
    val currentEvent = rememberUpdatedState(onEvent)
    DisposableEffect(controller) {
        onDispose {
            controller.invalidate()
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
    AndroidView(modifier = modifier, update = { controller.setGlitchEnabled(glitchEnabled) }, factory = { context ->
        WebView(context).apply {
            val managedView = this
            val heroHash = MessageDigest.getInstance("SHA-256").digest(mascotId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val idleFile = File(context.filesDir, "helios-idle/$heroHash.webm")
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
                    if (url.path == "/helios/idle.webm") {
                        return if (idleFile.isFile && idleFile.length() > 1024) WebResourceResponse("video/webm", null, idleFile.inputStream())
                        else WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    val asset = when (url.path) {
                        "/helios/index.html" -> "helios/index.html"
                        "/helios/android-helios.js" -> if (BuildConfig.USE_OMNI) "helios/android-omni.js" else if (BuildConfig.USE_X2) "helios/android-x2.js" else "helios/android-helios.js"
                        else -> return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    val mime = if (asset.endsWith(".js")) "text/javascript" else "text/html"
                    return WebResourceResponse(mime, "UTF-8", context.assets.open(asset))
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun saveIdleClip(encoded: String): Boolean {
                    if (encoded.length > 12_000_000) return false
                    return try {
                        val bytes = Base64.decode(encoded, Base64.DEFAULT)
                        if (bytes.size !in 1025..8_000_000) return false
                        check(idleFile.parentFile?.mkdirs() == true || idleFile.parentFile?.isDirectory == true)
                        val atomic = AtomicFile(idleFile)
                        val stream = atomic.startWrite()
                        try { stream.write(bytes); atomic.finishWrite(stream) }
                        catch (e: Exception) { atomic.failWrite(stream); throw e }
                        true
                    } catch (_: Exception) { false }
                }
                @JavascriptInterface fun beginClip(title: String, mime: String): String =
                    runCatching { archive.begin(title, mime) }.getOrDefault("")
                @JavascriptInterface fun appendClip(id: String, data: String): Boolean = archive.append(id, data)
                @JavascriptInterface fun finishClip(id: String): Boolean = archive.finish(id)
                @JavascriptInterface fun abortClip(id: String) = archive.abort(id)
                @JavascriptInterface fun reactorToken(): String {
                    return try {
                        check(reactorApiKey.isNotBlank()) { "Не настроен ключ Reactor." }
                        val models = JSONObject().put("match", JSONArray().put(if (BuildConfig.USE_OMNI) "reactor/fast-h3-omni" else if (BuildConfig.USE_X2) "xmax/x2" else "reactor/helios"))
                        val authorization = JSONObject().put("type", "session")
                            .put("resources", JSONObject().put("models", models))
                            .put("constraints", JSONObject().put("max_sessions", 1))
                        val body = JSONObject().put("authorization_details", JSONArray().put(authorization))
                        val req = Request.Builder().url("https://api.reactor.inc/tokens")
                            .header("Reactor-API-Key", reactorApiKey)
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
                    if (BuildConfig.USE_X2 && type in setOf("diagnostic", "error", "frame", "restart-frame", "status")) Log.i("MascotX2", "$type $detail")
                    if (type == "replay") Log.i("MascotHelios", "clip-replay")
                    if (type == "restart-frame") Log.i("MascotHeliosTiming", "reaction-frame")
                    if (type == "performance") Log.i("MascotHeliosPerformance", detail.take(200))
                    if (type == "diagnostic" && detail in setOf("identity-refreshed", "identity-refresh-failed", "warm-paused", "warm-reset")) Log.i("MascotHelios", detail)
                    Handler(Looper.getMainLooper()).post {
                        if (controller.view !== managedView) return@post
                        if (type == "ready") controller.markReady(managedView)
                        currentEvent.value(type, detail.take(300))
                    }
                }
            }, "MascotBridge")
            controller.view = this
            loadUrl("https://appassets.androidplatform.net/helios/index.html?finite=1&warm=${android.net.Uri.encode(glitchPalette.warmHex)}&cool=${android.net.Uri.encode(glitchPalette.coolHex)}")
        }
    })
}
