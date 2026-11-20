package com.generativemascot.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Local recovery snapshot. It contains private chat and image data, but never API credentials. */
internal class HeroRecoveryExporter(private val context: Context) {
    data class Result(val file: File, val heroes: Int, val files: Int, val missingImages: Int)
    private val heroPattern = Regex("[A-Za-z0-9-]{1,80}")

    fun export(): Result {
        val external = requireNotNull(context.getExternalFilesDir(null)) { "Память для архива недоступна" }
        val destination = File(external, "mascot-recovery-${UUID.randomUUID()}.zip")
        val heroRoot = File(context.filesDir, "hero")
        val store = HeroLocalStore(context)
        val heroes = heroRoot.listFiles().orEmpty().filter {
            it.isDirectory && it.name.matches(heroPattern) && !store.isDeleted(it.name) && File(it, "base.png").isFile
        }.sortedBy { it.name }
        require(heroes.isNotEmpty()) { "Сохранённых героев нет" }
        val checksums = linkedMapOf<String, String>()
        val settings = JSONObject()
        val paths = JSONObject()
        val missing = JSONArray()
        val observedTrees = mutableListOf<Pair<File, Map<String, Pair<Long, Long>>>>()
        val imageRoot = File(context.filesDir, "chat-images").canonicalFile
        val privateRoot = context.filesDir.canonicalFile
        try {
            ZipOutputStream(FileOutputStream(destination)).use { zip ->
                fun add(file: File, entry: String) {
                    require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "Файл архива недоступен: ${file.name}" }
                    if (checksums.containsKey(entry)) return
                    val originalSize = file.length(); val originalTime = file.lastModified()
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.putNextEntry(ZipEntry(entry))
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { val count = input.read(buffer); if (count < 0) break
                            digest.update(buffer, 0, count); zip.write(buffer, 0, count) }
                    }
                    zip.closeEntry()
                    check(file.isFile && file.length() == originalSize && file.lastModified() == originalTime) {
                        "Файл ${file.name} изменился во время экспорта. Повтори после завершения генерации"
                    }
                    checksums[entry] = digest.digest().joinToString("") { "%02x".format(it) }
                }
                fun treeState(folder: File): Map<String, Pair<Long, Long>> {
                    if (!folder.exists()) return emptyMap()
                    require(folder.isDirectory && !Files.isSymbolicLink(folder.toPath()))
                    return folder.walkTopDown().onEnter { dir ->
                        require(!Files.isSymbolicLink(dir.toPath())); true
                    }.filter { it.isFile }.associate { file ->
                        require(!Files.isSymbolicLink(file.toPath()))
                        file.relativeTo(folder).invariantSeparatorsPath to (file.length() to file.lastModified())
                    }
                }
                fun addTree(folder: File, prefix: String) {
                    val before = treeState(folder)
                    before.keys.sorted().forEach { relative ->
                        val file = File(folder, relative)
                        require(relative.split('/').none { it == ".." || it.isEmpty() })
                        add(file, "$prefix/$relative")
                    }
                    check(treeState(folder) == before) { "Файлы изменились во время экспорта. Повтори позже" }
                    observedTrees.add(folder to before)
                }
                fun addAsset(path: String, entry: String) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.putNextEntry(ZipEntry(entry))
                    context.assets.open(path).buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { val count = input.read(buffer); if (count < 0) break
                            digest.update(buffer, 0, count); zip.write(buffer, 0, count) }
                    }
                    zip.closeEntry()
                    checksums[entry] = digest.digest().joinToString("") { "%02x".format(it) }
                }
                fun attach(raw: String?) {
                    if (raw.isNullOrBlank() || paths.has(raw)) return
                    val candidate = runCatching { File(raw).canonicalFile }.getOrNull()
                    if (candidate == null || !candidate.path.startsWith(privateRoot.path + File.separator) ||
                        !candidate.isFile || candidate.extension.lowercase() !in setOf("jpg", "jpeg", "png", "webp") ||
                        Files.isSymbolicLink(File(raw).toPath())) { missing.put(raw); return }
                    val relative = candidate.relativeTo(privateRoot).invariantSeparatorsPath
                    val entry = if (candidate.parentFile == imageRoot) "recovery/chat-images/${candidate.name}"
                        else "recovery/files/$relative"
                    add(candidate, entry)
                    paths.put(raw, entry)
                }
                val choices = context.getSharedPreferences("hero_character_choices", Context.MODE_PRIVATE)
                val histories = context.getSharedPreferences("mascot_chat_history", Context.MODE_PRIVATE)
                val interactions = context.getSharedPreferences("mascot_chat_interaction", Context.MODE_PRIVATE)
                val batches = context.getSharedPreferences("animation_batches", Context.MODE_PRIVATE)
                heroes.forEach { hero ->
                    addTree(hero, "recovery/hero/${hero.name}")
                    val choice = JSONObject()
                    listOf("type", "style", "reference", "x", "y").forEach { key ->
                        val value = choices.all["${hero.name}:$key"] ?: return@forEach
                        choice.put(key, value)
                        if (key == "reference") attach(value as? String)
                    }
                    val data = JSONObject().put("choice", choice)
                    histories.getString(hero.name, null)?.let { raw ->
                        data.put("chatHistory", raw)
                        runCatching { JSONArray(raw) }.getOrNull()?.let { rows ->
                            for (index in 0 until rows.length()) attach(rows.optJSONObject(index)?.optString("imagePath"))
                        }
                    }
                    interactions.getString(hero.name, null)?.let { data.put("interaction", it) }
                    batches.getString("latest:${hero.name}", null)?.let { batch ->
                        data.put("batchId", batch)
                        batches.getString("actions:$batch", null)?.let { data.put("batchActions", it) }
                    }
                    settings.put(hero.name, data)
                }
                // Preserve both downloaded maps and maps supplied by the installed
                // APK, so the backup does not depend on retaining that APK later.
                addTree(File(context.filesDir, "lighting/video"), "recovery/lighting/video")
                context.assets.list("video-lighting").orEmpty()
                    .filter { it.matches(Regex("[a-f0-9]{64}")) }.sorted().forEach { hash ->
                        context.assets.list("video-lighting/$hash").orEmpty().filter {
                            it == "manifest.json" || it.matches(Regex("normals-\\d{3}\\.png"))
                        }.sorted().forEach { name ->
                            addAsset("video-lighting/$hash/$name", "recovery/bundled-video-lighting/$hash/$name")
                        }
                    }
                check(observedTrees.all { (folder, before) -> treeState(folder) == before }) {
                    "Файлы изменились во время экспорта. Повтори позже"
                }
                val manifest = JSONObject().put("format", "mascot-recovery-v1")
                    .put("heroes", JSONArray(heroes.map { it.name }))
                    .put("settings", settings).put("imagePaths", paths).put("missingImages", missing)
                    .put("sha256", JSONObject(checksums as Map<*, *>))
                zip.putNextEntry(ZipEntry("recovery/manifest.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            // Verify the actual closed archive before it can be reported as ready.
            ZipFile(destination).use { zip ->
                checksums.forEach { (name, expected) ->
                    val entry = requireNotNull(zip.getEntry(name))
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(entry).buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                    }
                    check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "Архив не прошёл проверку" }
                }
                requireNotNull(zip.getEntry("recovery/manifest.json"))
            }
            return Result(destination, heroes.size, checksums.size, missing.length())
        } catch (error: Throwable) { destination.delete(); throw error }
    }
}
