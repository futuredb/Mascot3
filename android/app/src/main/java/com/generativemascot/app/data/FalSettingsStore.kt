package com.generativemascot.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.generativemascot.app.BuildConfig
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun normalizeFalApiKey(raw: String): String {
    val value = raw.trim()
    require(value.isNotEmpty()) { "Введите ключ fal.ai" }
    require(value.length <= 256) { "Ключ fal.ai слишком длинный" }
    require(value.none(Char::isWhitespace)) { "В ключе fal.ai не должно быть пробелов" }
    return value
}

/** A device-local fal.ai override encrypted by Android Keystore. */
class FalSettingsStore(context: Context, bundledKey: String = BuildConfig.FAL_H3_API_KEY) {
    private val secretFile = java.io.File(context.applicationContext.noBackupFilesDir, SECRET_FILE)
    private val bundledApiKey = bundledKey.trim().takeIf { it.isNotBlank() }

    fun apiKey(): String? {
        if (!secretFile.isFile) return bundledApiKey
        return runCatching {
            val buffer = ByteBuffer.wrap(Base64.decode(secretFile.readText(), Base64.NO_WRAP))
            require(buffer.get() == FORMAT_VERSION)
            val ivSize = buffer.get().toInt() and 0xff
            require(ivSize in 12..32 && buffer.remaining() > ivSize)
            val iv = ByteArray(ivSize).also(buffer::get)
            val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(encrypted)
            try { normalizeFalApiKey(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
        }.getOrElse {
            secretFile.delete()
            bundledApiKey
        }
    }

    fun hasUserApiKey(): Boolean = secretFile.isFile && apiKey() != null

    fun saveApiKey(raw: String) {
        val plain = normalizeFalApiKey(raw).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(plain)
        plain.fill(0)
        val payload = ByteBuffer.allocate(2 + cipher.iv.size + encrypted.size)
            .put(FORMAT_VERSION).put(cipher.iv.size.toByte()).put(cipher.iv).put(encrypted).array()
        val staging = java.io.File(secretFile.parentFile, "${secretFile.name}.pending")
        staging.writeText(Base64.encodeToString(payload, Base64.NO_WRAP))
        runCatching {
            Files.move(staging.toPath(), secretFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(staging.toPath(), secretFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun removeApiKey() {
        secretFile.delete()
        java.io.File(secretFile.parentFile, "${secretFile.name}.pending").delete()
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    private companion object {
        const val SECRET_FILE = "fal-key-v1.enc"
        const val KEY_ALIAS = "mascots.fal.user-key.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
    }
}
