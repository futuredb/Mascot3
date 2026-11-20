package com.generativemascot.app.data

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/** Imports APK assets only, never provider jobs or generation requests. Call under the app mutex. */
internal class BundledHeroImporter(
    private val listAssets: (String) -> Array<out String>,
    private val openAsset: (String) -> InputStream,
    private val destinationRoot: File,
    private val isDeleted: (String) -> Boolean = { false },
    private val onImported: (String, File) -> Unit = { _, _ -> },
) {
    fun importAll() {
        val assetRoot = "bundled-heroes"
        childrenOf(assetRoot).sorted().forEach { heroId ->
            require(heroId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid bundled hero ID: $heroId" }
            if (isDeleted(heroId)) return@forEach
            val source = "$assetRoot/$heroId"
            check("base.png" in childrenOf(source)) { "Bundled hero has no base image: $heroId" }
            val destination = File(destinationRoot, heroId)
            copyTree(source, destination)
            // A bundled retirement applies only to the exact previously shipped
            // clip; keep a recoverable copy and never touch a new generation.
            val retiredHash = File(destination, "retire-idle.sha256").takeIf(File::isFile)?.readText()?.trim()
            val idle = File(destination, "videos/idle.mp4")
            if (retiredHash != null && retiredHash.matches(Regex("[a-f0-9]{64}")) && idle.isFile &&
                java.security.MessageDigest.getInstance("SHA-256").digest(idle.readBytes()).joinToString("") { "%02x".format(it) } == retiredHash) {
                val archived = File(destination, "retired-videos/idle-$retiredHash-${java.util.UUID.randomUUID()}.mp4")
                check(archived.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                check(!archived.exists() && idle.renameTo(archived)) { "Не удалось убрать старую анимацию" }
            }
            onImported(heroId, destination)
        }
    }

    private fun childrenOf(assetPath: String): List<String> = listAssets(assetPath)
        // APK/test asset providers may enumerate descendants, rather than immediate children.
        .map { it.substringBefore('/') }
        .filterNot { it.startsWith(".") }
        .distinct()

    private fun copyTree(assetPath: String, destination: File) {
        val children = childrenOf(assetPath)
        if (children.isNotEmpty()) {
            check(destination.isDirectory || destination.mkdirs()) { "Cannot create hero directory" }
            // base.png is the library's visibility marker: publish it only after all other assets.
            children.sortedWith(compareBy<String> { it == "base.png" }.thenBy { it }).forEach { child ->
                require(child.isNotEmpty() && child != "." && child != ".." && '/' !in child && '\\' !in child)
                copyTree("$assetPath/$child", File(destination, child))
            }
            return
        }
        if (destination.exists()) {
            check(destination.isFile) { "Bundled file conflicts with an existing directory" }
            return // User-created/renamed/modified files always win.
        }
        val parent = requireNotNull(destination.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create hero directory" }
        // A fixed, importer-owned name lets a later launch safely retry an interrupted copy.
        val pending = File(parent, "${destination.name}.bundled-import.pending")
        try {
            openAsset(assetPath).use { input ->
                FileOutputStream(pending).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            try {
                // No REPLACE_EXISTING: even a file created during the copy must be preserved.
                Files.move(pending.toPath(), destination.toPath())
            } catch (exists: FileAlreadyExistsException) {
                if (!destination.isFile) throw exists
            }
        } finally {
            pending.delete()
        }
    }
}
