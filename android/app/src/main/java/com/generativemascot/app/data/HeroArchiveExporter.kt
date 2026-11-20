package com.generativemascot.app.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exports only character artwork and matching prepared lighting. Never includes private app preferences. */
internal class HeroArchiveExporter(private val context: Context) {
    internal data class Result(val file: File, val heroes: Int, val imageMaps: Int, val videoMaps: Int)
    private val hashPattern = Regex("[a-f0-9]{64}")
    private val heroPattern = Regex("[A-Za-z0-9-]{1,80}")

    fun export(): Result {
        val external = requireNotNull(context.getExternalFilesDir(null)) { "Память для экспорта недоступна" }
        val destination = File(external, "mascot-heroes-${java.util.UUID.randomUUID()}.zip")
        val store = HeroLocalStore(context)
        val heroes = File(context.filesDir, "hero").listFiles().orEmpty().filter {
            it.isDirectory && it.name.matches(heroPattern) && !store.isDeleted(it.name) && File(it, "base.png").isFile
        }.sortedBy { it.name }
        require(heroes.isNotEmpty()) { "Сохранённых героев нет" }
        var imageMaps = 0; var videoMaps = 0
        val videoHashes = mutableSetOf<String>()
        try {
            ZipOutputStream(FileOutputStream(destination)).use { zip ->
                fun add(file: File, entry: String) {
                    if (!file.isFile || file.isDirectory) return
                    zip.putNextEntry(ZipEntry(entry))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                fun addFolder(folder: File, prefix: String, allowed: (File) -> Boolean) {
                    folder.listFiles().orEmpty().filter { it.isFile && allowed(it) }.sortedBy { it.name }
                        .forEach { add(it, "$prefix/${it.name}") }
                }
                heroes.forEach { hero ->
                    val prefix = "bundled-heroes/${hero.name}"
                    listOf("base.png", "name.txt", "performance.mp4", "lighting.enabled").forEach {
                        add(File(hero, it), "$prefix/$it")
                    }
                    listOf("states", "widget", "puppet", "chat-activity").forEach { dir ->
                        addFolder(File(hero, dir), "$prefix/$dir") { it.extension in setOf("png", "mp4", "json") }
                    }
                    addFolder(File(hero, "videos"), "$prefix/videos") {
                        it.extension == "mp4" || it.name in setOf("neutral.png", "sleep-loop-anchor.png", "matte-color.txt", "manifest.json")
                    }
                    listOf("appearance.json", "character-contract.json").forEach {
                        add(File(hero, "creative/$it"), "$prefix/creative/$it")
                    }
                    val activeFile = File(hero, "physics/active")
                    val revision = runCatching { activeFile.readText().trim() }.getOrNull()
                    if (revision != null && revision.matches(hashPattern)) {
                        val physics = File(hero, "physics/$revision")
                        if (File(physics, "manifest.json").isFile) {
                            add(activeFile, "$prefix/physics/active")
                            addFolder(physics, "$prefix/physics/$revision") {
                                it.name == "manifest.json" || it.extension == "png"
                            }
                            imageMaps++
                        }
                    }
                    val videos = listOf(File(hero, "performance.mp4")) +
                        File(hero, "videos").listFiles().orEmpty().filter { it.isFile && it.extension == "mp4" }
                    videos.filter(File::isFile).forEach { video ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        video.inputStream().buffered().use { input ->
                            val bytes = ByteArray(64 * 1024)
                            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
                        }
                        val hash = digest.digest().joinToString("") { "%02x".format(it) }
                        if (videoHashes.add(hash)) {
                            val map = File(context.filesDir, "lighting/video/$hash")
                            if (File(map, "manifest.json").isFile) {
                                addFolder(map, "video-lighting/$hash") {
                                    it.name == "manifest.json" || it.name.matches(Regex("normals-\\d{3}\\.png"))
                                }
                                videoMaps++
                            }
                        }
                    }
                }
            }
            return Result(destination, heroes.size, imageMaps, videoMaps)
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }
}
