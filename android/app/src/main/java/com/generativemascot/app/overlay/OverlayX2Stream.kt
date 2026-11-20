package com.generativemascot.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.generativemascot.app.MascotApp
import com.generativemascot.app.data.HeroLocalStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Owns one model connection while the overlay window is attached. */
@SuppressLint("SetJavaScriptEnabled")
internal class OverlayX2Stream(context: Context, private val heroId: String, private val onLive: (Boolean) -> Unit) : FrameLayout(context) {
    private var web: WebView? = null
    private var disposed = false
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        val base = HeroLocalStore.current?.baseFile(heroId)
        if (base != null) {
            val image = "data:image/png;base64," + Base64.encodeToString(base.readBytes(), Base64.NO_WRAP)
            val w = WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                alpha = .01f
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val u = request.url
                        if (u.scheme != "https" || u.host != "appassets.androidplatform.net") return null
                        if (u.path == "/helios/index.html") {
                            val html = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;overflow:hidden;background:transparent}video{position:absolute;width:1px;height:1px;opacity:0}canvas{position:fixed;inset:0;object-fit:contain;opacity:0}</style><video id="video" autoplay playsinline muted></video><video id="replay" playsinline muted></video><canvas id="frame"></canvas><script>window.MascotOverlayMode=true</script><script type="module" src="/helios/overlay.js"></script>"""
                            return WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
                        }
                        if (u.path == "/helios/overlay.js") {
                            val asset = if (com.generativemascot.app.BuildConfig.USE_X2) "helios/android-x2.js" else "helios/android-helios.js"
                            return WebResourceResponse("text/javascript", "UTF-8", context.assets.open(asset))
                        }
                        return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArray(0).inputStream())
                    }
                }
                addJavascriptInterface(object {
                    @JavascriptInterface fun reactorToken(): String = try {
                        val key = (context.applicationContext as MascotApp).reactorSettings.apiKey().orEmpty()
                        check(key.isNotBlank())
                        val auth = JSONObject().put("type", "session")
                            .put("resources", JSONObject().put("models", JSONObject().put("match", JSONArray().put(if (com.generativemascot.app.BuildConfig.USE_X2) "xmax/x2" else "reactor/helios"))))
                            .put("constraints", JSONObject().put("max_sessions", 1))
                        val body = JSONObject().put("authorization_details", JSONArray().put(auth))
                        val req = Request.Builder().url("https://api.reactor.inc/tokens").header("Reactor-API-Key", key)
                            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                        http.newCall(req).execute().use { r ->
                            check(r.isSuccessful)
                            JSONObject().put("jwt", JSONObject(r.body?.string() ?: "{}").getString("jwt")).toString()
                        }
                    } catch (_: Exception) { JSONObject().put("error", "Не удалось подключить анимацию.").toString() }
                    @JavascriptInterface fun event(type: String, detail: String) {
                        main.post {
                            if (disposed || web !== this@apply) return@post
                            if (type in setOf("ready", "frame", "restart-frame", "replay", "error", "stopped")) {
                                android.util.Log.i("MascotOverlayStream", "event=$type")
                            }
                            when (type) {
                                "ready" -> evaluateJavascript("window.reactMascot(${JSONObject.quote(image)},${JSONObject.quote("The character blinks and breathes gently.")})", null)
                                "frame", "restart-frame", "replay" -> { alpha = 1f; onLive(true) }
                                "error", "stopped" -> { alpha = .01f; onLive(false) }
                            }
                        }
                    }
                }, "MascotBridge")
            }
            web = w
            addView(w, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            w.loadUrl("https://appassets.androidplatform.net/helios/index.html")
        }
    }
    fun dispose() {
        if (disposed) return
        disposed = true
        onLive(false)
        val old = web ?: return
        web = null
        old.evaluateJavascript("window.closeMascot?.()", null)
        removeView(old)
        main.postDelayed({ old.stopLoading(); old.removeJavascriptInterface("MascotBridge"); old.destroy() }, 1000)
    }
}
