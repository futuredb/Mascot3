package com.generativemascot.app.ui

import android.util.Base64
import com.generativemascot.app.data.CharacterAppearance
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reuse encoded image data until either the image or its appearance record changes. */
internal class ChatHeroReferenceCache {
    private data class Stamp(val path: String, val bytes: Long, val modified: Long,
        val appearanceBytes: Long, val appearanceModified: Long)
    internal data class Reference(val image: String, val appearance: String)
    private val mutex = Mutex()
    private var cachedStamp: Stamp? = null
    private var cached: Reference? = null

    suspend fun read(file: File): Reference = withContext(Dispatchers.IO) {
        mutex.withLock {
            val appearanceFile = File(file.parentFile, "creative/appearance.json")
            val stamp = Stamp(file.absolutePath, file.length(), file.lastModified(),
                appearanceFile.length(), appearanceFile.lastModified())
            if (stamp == cachedStamp) cached?.let { return@withLock it }
            val value = Reference("data:image/png;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP),
                CharacterAppearance.read(file).orEmpty())
            cachedStamp = stamp
            cached = value
            value
        }
    }
}
