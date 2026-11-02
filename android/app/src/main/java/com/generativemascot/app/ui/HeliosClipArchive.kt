package com.generativemascot.app.ui

import android.content.Context
import android.media.MediaExtractor
import android.util.AtomicFile
import android.util.Base64
import com.generativemascot.app.data.HeroLocalStore
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONObject

/** Receives one complete MediaRecorder container in ordered chunks, without a network request. */
internal class HeliosClipArchive(context: Context, private val heroId: String) {
    private val store = HeroLocalStore(context.applicationContext)
    private data class Transfer(val partial: File, val destination: File, val title: String, var bytes: Long = 0)
    private val transfers = mutableMapOf<String, Transfer>()

    @Synchronized fun begin(title: String, mime: String): String {
        val cleanTitle = title.trim().replace(Regex("[\\r\\n]+"), " ").take(100)
        require(cleanTitle.isNotBlank())
        val extension = when {
            mime.startsWith("video/webm", ignoreCase = true) -> "webm"
            mime.startsWith("video/mp4", ignoreCase = true) -> "mp4"
            else -> error("Unsupported recording format")
        }
        val folder = store.chatAnimationDirectory(heroId).apply { mkdirs() }
        // Interrupted files are never shown in the library; remove only old archive staging files.
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        folder.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{32}\\.helios-pending")) && it.lastModified() < cutoff }
            .forEach { it.delete() }
        val id = UUID.randomUUID().toString().replace("-", "")
        val partial = File(folder, "$id.helios-pending")
        check(partial.createNewFile())
        transfers[id] = Transfer(partial, File(folder, "$id.$extension"), cleanTitle)
        return id
    }

    @Synchronized fun append(id: String, encoded: String): Boolean {
        val transfer = transfers[id] ?: return false
        return try {
            require(encoded.length <= 200_000)
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.isNotEmpty() && transfer.bytes + bytes.size <= 50_000_000L)
            FileOutputStream(transfer.partial, true).use { it.write(bytes) }
            transfer.bytes += bytes.size
            true
        } catch (_: Exception) { abort(id); false }
    }

    @Synchronized fun finish(id: String): Boolean {
        val transfer = transfers.remove(id) ?: return false
        val metadata = AtomicFile(File(transfer.destination.parentFile, "$id.json"))
        return try {
            require(transfer.bytes > 1024)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(transfer.partial.absolutePath)
                require((0 until extractor.trackCount).any { index ->
                    extractor.getTrackFormat(index).getString("mime")?.startsWith("video/") == true
                })
            } finally { extractor.release() }
            FileOutputStream(transfer.partial, true).use { it.fd.sync() }
            val output = metadata.startWrite()
            try {
                output.write(JSONObject().put("title", transfer.title).put("createdAt", System.currentTimeMillis())
                    .put("source", "helios").toString().toByteArray(Charsets.UTF_8))
                metadata.finishWrite(output)
            } catch (error: Exception) { metadata.failWrite(output); throw error }
            check(transfer.partial.renameTo(transfer.destination))
            true
        } catch (_: Exception) {
            transfer.partial.delete()
            metadata.delete()
            false
        }
    }

    @Synchronized fun abort(id: String) { transfers.remove(id)?.partial?.delete() }
}
