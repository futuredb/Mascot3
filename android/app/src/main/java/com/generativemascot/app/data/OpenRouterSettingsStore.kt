package com.generativemascot.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.generativemascot.app.BuildConfig
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class GenerationRoute {
    TEAM_SERVER,
    OPENROUTER_DIRECT,
}

data class OpenRouterKeyCheck(
    val remainingUsd: Double?,
    val imageModel: String,
    val videoModel: String,
)

internal fun normalizeOpenRouterApiKey(raw: String): String {
    val value = raw.trim()
    require(value.startsWith("sk-or-")) { "Ключ OpenRouter должен начинаться с sk-or-" }
    require(value.length >= 24) { "Ключ OpenRouter выглядит слишком коротким" }
    require(value.none(Char::isWhitespace)) { "В ключе OpenRouter не должно быть пробелов" }
    return value
}

/**
 * A user-entered override stays encrypted with Android Keystore. Team builds
 * can additionally provide a bundled fallback through BuildConfig.
 */
class OpenRouterSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val secretFile = java.io.File(appContext.noBackupFilesDir, SECRET_FILE)
    private val bundledApiKey = runCatching {
        BuildConfig.OPENROUTER_API_KEY
            .takeIf { it.isNotBlank() }
            ?.let(::normalizeOpenRouterApiKey)
    }.getOrNull()

    init {
        // This team build intentionally switches existing installations once to
        // the bundled direct route. A later explicit choice remains respected.
        if (bundledApiKey != null && preferences.getInt(BUNDLED_ROUTE_MIGRATION, 0) < 1) {
            preferences.edit()
                .putString(ROUTE, GenerationRoute.OPENROUTER_DIRECT.name)
                .putInt(BUNDLED_ROUTE_MIGRATION, 1)
                .apply()
        }
    }

    fun route(): GenerationRoute = preferences.getString(ROUTE, null)
        ?.let { saved -> GenerationRoute.entries.firstOrNull { it.name == saved } }
        ?: if (bundledApiKey != null) GenerationRoute.OPENROUTER_DIRECT else GenerationRoute.TEAM_SERVER

    fun setRoute(route: GenerationRoute) {
        require(route != GenerationRoute.OPENROUTER_DIRECT || hasApiKey()) {
            "Сначала сохраните ключ OpenRouter"
        }
        preferences.edit().putString(ROUTE, route.name).apply()
    }

    fun isDirectModeEnabled(): Boolean =
        route() == GenerationRoute.OPENROUTER_DIRECT && hasApiKey()

    fun hasApiKey(): Boolean = apiKey() != null

    fun saveApiKey(raw: String) {
        val plain = normalizeOpenRouterApiKey(raw).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(plain)
        plain.fill(0)
        val iv = cipher.iv
        val payload = ByteBuffer.allocate(2 + iv.size + encrypted.size)
            .put(FORMAT_VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(encrypted)
            .array()
        val staging = java.io.File(secretFile.parentFile, "${secretFile.name}.pending")
        staging.writeText(Base64.encodeToString(payload, Base64.NO_WRAP))
        runCatching {
            Files.move(
                staging.toPath(),
                secretFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(staging.toPath(), secretFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun apiKey(): String? {
        if (!secretFile.isFile) return bundledApiKey
        return runCatching {
            val payload = Base64.decode(secretFile.readText(), Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(payload)
            require(buffer.get() == FORMAT_VERSION) { "Unsupported key format" }
            val ivSize = buffer.get().toInt() and 0xff
            require(ivSize in 12..32 && buffer.remaining() > ivSize) { "Invalid encrypted key" }
            val iv = ByteArray(ivSize).also(buffer::get)
            val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(encrypted)
            try {
                normalizeOpenRouterApiKey(plain.toString(Charsets.UTF_8))
            } finally {
                plain.fill(0)
            }
        }.getOrElse {
            // A restored backup has no matching Keystore entry. Fail closed:
            // discard that override and fall back to the team build route.
            secretFile.delete()
            preferences.edit()
                .putString(
                    ROUTE,
                    if (bundledApiKey != null) GenerationRoute.OPENROUTER_DIRECT.name else GenerationRoute.TEAM_SERVER.name,
                )
                .apply()
            bundledApiKey
        }
    }

    fun removeApiKey() {
        secretFile.delete()
        java.io.File(secretFile.parentFile, "${secretFile.name}.pending").delete()
        preferences.edit()
            .putString(
                ROUTE,
                if (bundledApiKey != null) GenerationRoute.OPENROUTER_DIRECT.name else GenerationRoute.TEAM_SERVER.name,
            )
            .apply()
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "openrouter_settings"
        const val ROUTE = "generation_route"
        const val BUNDLED_ROUTE_MIGRATION = "bundled_route_migration_v1"
        const val SECRET_FILE = "openrouter-key-v1.enc"
        const val KEY_ALIAS = "mascots.openrouter.user-key.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
    }
}

/** Performs only read-only OpenRouter calls; checking a key cannot spend credits. */
class OpenRouterKeyVerifier {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun verify(raw: String): OpenRouterKeyCheck = withContext(Dispatchers.IO) {
        val key = normalizeOpenRouterApiKey(raw)
        val keyInfo = get("$OPENROUTER_BASE_URL/key", key)
        val imageModels = get("$OPENROUTER_BASE_URL/images/models", key)
        val videoModels = get("$OPENROUTER_BASE_URL/videos/models", key)
        requireModel(imageModels, IMAGE_MODEL, "GPT Image 2")
        requireModel(videoModels, VIDEO_MODEL, "Seedance 2.0 Mini")
        val data = JSONObject(keyInfo).optJSONObject("data")
        val remaining = data?.takeIf { it.has("limit_remaining") && !it.isNull("limit_remaining") }
            ?.optDouble("limit_remaining")
            ?.takeIf { !it.isNaN() }
        OpenRouterKeyCheck(
            remainingUsd = remaining,
            imageModel = IMAGE_MODEL,
            videoModel = VIDEO_MODEL,
        )
    }

    private fun get(url: String, key: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("X-Title", "Mascots Android")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw readableError(response.code, raw)
            raw
        }
    }

    private fun requireModel(raw: String, modelId: String, label: String) {
        val entries = JSONObject(raw).optJSONArray("data") ?: JSONArray()
        val found = (0 until entries.length()).any { entries.optJSONObject(it)?.optString("id") == modelId }
        if (!found) throw IOException("$label сейчас недоступна. Платный запрос не запускался.")
    }

    private fun readableError(code: Int, raw: String): IOException {
        val apiMessage = runCatching {
            val body = JSONObject(raw)
            body.optJSONObject("error")?.optString("message")
                ?: body.optString("message")
        }.getOrNull().orEmpty()
        val readable = when (code) {
            401, 403 -> "OpenRouter не принял этот ключ"
            402 -> "На балансе OpenRouter недостаточно средств"
            429 -> "OpenRouter временно ограничил количество запросов"
            in 500..599 -> "OpenRouter временно недоступен"
            else -> apiMessage.take(180).ifBlank { "HTTP $code" }
        }
        return IOException("Не удалось подключить ключ: $readable")
    }

    private companion object {
        const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
        const val IMAGE_MODEL = "openai/gpt-image-2"
        const val VIDEO_MODEL = "bytedance/seedance-2.0-mini"
    }
}
