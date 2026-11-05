package com.generativemascot.app.data

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** A description is valid only for the exact image the vision model observed. */
internal object CharacterAppearance {
    fun imageHash(image: File): String = image.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun read(image: File): String? = runCatching {
        val record = JSONObject(AtomicFile(File(image.parentFile, "creative/appearance.json"))
            .openRead().bufferedReader().use { it.readText() })
        record.optString("description").trim().takeIf {
            it.isNotEmpty() && record.optString("imageSha256") == imageHash(image)
        }
    }.getOrNull()

    fun save(image: File, description: String, expectedImageHash: String? = null) {
        val clean = description.trim().take(1200)
        if (clean.isEmpty()) return
        val hash = imageHash(image)
        if (expectedImageHash != null && expectedImageHash != hash) return
        val file = File(image.parentFile, "creative/appearance.json")
        check(file.parentFile?.mkdirs() != false || file.parentFile?.isDirectory == true)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            val record = JSONObject().put("imageSha256", hash).put("description", clean)
            stream.write(record.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
}
