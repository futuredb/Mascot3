package com.generativemascot.app.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundledHeroImporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "hero")
    private val id = "friend-hero"
    private val files = mapOf(
        "bundled-heroes/$id/base.png" to byteArrayOf(1, 2, 3),
        "bundled-heroes/$id/name.txt" to "Уна".toByteArray(),
        "bundled-heroes/$id/videos/idle.mp4" to ByteArray(4096) { 4 },
        "bundled-heroes/$id/creative/character-contract.json" to "{}".toByteArray(),
    )

    private fun importer(open: (String) -> InputStream = { ByteArrayInputStream(files.getValue(it)) }) =
        BundledHeroImporter(
            listAssets = { path ->
                val children = files.keys.filter { it.startsWith("$path/") }
                    .map { it.removePrefix("$path/").substringBefore('/') }.distinct()
                // Finder metadata is not a hero or asset; it must never reach openAsset.
                (if (children.isEmpty()) children else children + ".DS_Store").toTypedArray()
            },
            openAsset = open,
            destinationRoot = root,
        )

    private fun destination(relative: String) = File(root, "$id/$relative")

    @Test fun publishesHeroOnlyAfterVideosAndMetadataAreCopied() {
        var baseOpened = false
        importer { path ->
            if (path.endsWith("/base.png")) {
                assertFalse(destination("base.png").exists())
                assertTrue(destination("videos/idle.mp4").isFile)
                assertEquals("Уна", destination("name.txt").readText())
                assertTrue(destination("creative/character-contract.json").isFile)
                baseOpened = true
            }
            ByteArrayInputStream(files.getValue(path))
        }.importAll()
        assertTrue(baseOpened)
        assertFalse(root.walkTopDown().any { it.name == ".DS_Store" })
        files.forEach { (source, bytes) ->
            assertArrayEquals(bytes, File(root, source.removePrefix("bundled-heroes/")).readBytes())
        }
    }

    @Test fun neverReplacesExistingHeroesNamesOrAnimationsAndDoesNotDuplicateOnRetry() {
        destination("name.txt").apply { parentFile!!.mkdirs(); writeText("Другое имя") }
        destination("base.png").writeBytes(byteArrayOf(9))
        destination("videos/idle.mp4").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(8)) }
        repeat(2) { importer().importAll() }
        assertEquals("Другое имя", destination("name.txt").readText())
        assertArrayEquals(byteArrayOf(9), destination("base.png").readBytes())
        assertArrayEquals(byteArrayOf(8), destination("videos/idle.mp4").readBytes())
        assertTrue(destination("creative/character-contract.json").isFile)
        assertEquals(listOf(id), root.listFiles()!!.map { it.name })
    }

    @Test fun interruptedFileIsNotPublishedAndRetryCompletesWithoutRecopyingApprovedFiles() {
        try {
            importer { path ->
                if (path.endsWith(".mp4")) {
                    object : InputStream() {
                        private var count = 0
                        override fun read(): Int = if (count++ < 64) 4 else throw IOException("Interrupted copy")
                    }
                } else ByteArrayInputStream(files.getValue(path))
            }.importAll()
            fail("Copy failure should be reported")
        } catch (_: IOException) { }
        assertFalse(destination("videos/idle.mp4").exists())
        assertFalse(destination("base.png").exists())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".pending") })
        val nameTime = destination("name.txt").lastModified()
        importer().importAll()
        assertArrayEquals(files.getValue("bundled-heroes/$id/videos/idle.mp4"), destination("videos/idle.mp4").readBytes())
        assertTrue(destination("base.png").isFile)
        assertEquals(nameTime, destination("name.txt").lastModified())
    }

    @Test fun retriesOnlyItsOwnPendingFileLeftByAKilledProcess() {
        val pending = destination("videos/idle.mp4.bundled-import.pending")
        pending.apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(7)) }
        val unrelated = destination("videos/user-export.pending")
        unrelated.writeBytes(byteArrayOf(6))
        importer().importAll()
        assertFalse(pending.exists())
        assertArrayEquals(files.getValue("bundled-heroes/$id/videos/idle.mp4"), destination("videos/idle.mp4").readBytes())
        assertArrayEquals(byteArrayOf(6), unrelated.readBytes())
    }

    @Test fun fileCreatedDuringCopyStillWins() {
        importer { path ->
            if (path.endsWith("name.txt")) destination("name.txt").writeText("Имя пользователя")
            ByteArrayInputStream(files.getValue(path))
        }.importAll()
        assertEquals("Имя пользователя", destination("name.txt").readText())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".pending") })
    }

    @Test fun acceptsRecursiveAssetListingsWithoutDuplicatingOrFlatteningHeroFiles() {
        BundledHeroImporter(
            listAssets = { path -> files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/") }.toTypedArray() },
            openAsset = { ByteArrayInputStream(files.getValue(it)) },
            destinationRoot = root,
        ).importAll()
        assertEquals(listOf(id), root.listFiles()!!.map { it.name })
        files.forEach { (source, bytes) ->
            assertArrayEquals(bytes, File(root, source.removePrefix("bundled-heroes/")).readBytes())
        }
    }
}
