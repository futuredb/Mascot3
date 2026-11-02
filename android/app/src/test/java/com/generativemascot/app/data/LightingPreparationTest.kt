package com.generativemascot.app.data

import android.app.Application
import com.generativemascot.app.physics.PhysicsStore
import com.generativemascot.app.ui.loadVideoLighting
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import java.net.URI
import java.util.UUID
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LightingPreparationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val assets = File("src/main/assets").let { if (it.isDirectory) it else File("app/src/main/assets") }
    private val hero = "9f226b89-df35-4d3a-9b36-96c28ea1bb62"
    private fun api(files: Map<String, ByteArray>, corrupt: Boolean = false): MascotApi =
        Proxy.newProxyInstance(MascotApi::class.java.classLoader, arrayOf(MascotApi::class.java)) { _, method, args ->
            // A test fails immediately if any paid-generation method is called.
            require(method.name == "fetchBytes") { "Unexpected network call: ${method.name}" }
            val name = URI(args[0] as String).path.substringAfterLast('/')
            (if (corrupt && name.endsWith(".png")) byteArrayOf(0) else files.getValue(name)).toResponseBody()
        } as MascotApi
    private fun job(kind: String, source: File, files: Map<String, ByteArray>): LightingJob {
        val id = "b".repeat(64)
        return LightingJob(id, kind, "ready", LightingPreparation.hashFile(source),
            PhysicsStore.hash(files.getValue("manifest.json")), files.map { (name, bytes) ->
                LightingFile(name, PhysicsStore.hash(bytes), bytes.size.toLong(), "/v1/lighting/jobs/$id/assets/$name")
            })
    }

    @Test fun newVideoDownloadsItsOwnAtlasAndLoadsThroughTheProductionPlayerPath() = runBlocking<Unit> {
        val id = "lighting-test-${UUID.randomUUID()}"
        val root = File(context.filesDir, "hero/$id").apply { mkdirs() }
        // Different file bytes ensure this cannot accidentally load the bundled pack.
        val original = File(assets, "bundled-heroes/$hero/videos/idle.mp4")
        val source = File(root, "idle.mp4").apply { writeBytes(original.readBytes() + byteArrayOf(0, 0)) }
        val folder = File(assets, "video-lighting/${LightingPreparation.hashFile(original)}")
        val manifest = JSONObject(File(folder, "manifest.json").readText())
            .put("source_sha256", LightingPreparation.hashFile(source)).put("source_bytes", source.length())
        val files = folder.listFiles()!!.filter { it.extension == "png" }.associate { it.name to it.readBytes() }.toMutableMap()
        files["manifest.json"] = manifest.toString().toByteArray()
        val job = job("video", source, files)
        val destination = File(context.filesDir, "lighting/video/${job.sourceSha256}")
        try {
            assertNull(loadVideoLighting(context, source.toURI().toString()))
            val store = LightingPreparation(context)
            store.install(api(files), id, source, job)
            val loaded = requireNotNull(loadVideoLighting(context, source.toURI().toString()))
            assertEquals(job.sourceSha256, loaded.manifest.sourceSha256)
            assertEquals(loaded.manifest.pages.size, loaded.pages.size)
            loaded.pages.forEach { it.recycle() }
            store.install(api(files), id, source, job) // retry after successful installation
            assertTrue(File(destination, "manifest.json").isFile)
        } finally { root.deleteRecursively(); destination.deleteRecursively() }
    }

    @Test fun interruptedDownloadOrChangedSourceNeverActivatesAnIncompletePack() = runBlocking<Unit> {
        val id = "lighting-test-${UUID.randomUUID()}"; val root = File(context.filesDir, "hero/$id").apply { mkdirs() }
        val original = File(assets, "bundled-heroes/$hero/videos/idle.mp4")
        val source = File(root, "idle.mp4").apply { writeBytes(original.readBytes() + byteArrayOf(1)) }
        val folder = File(assets, "video-lighting/${LightingPreparation.hashFile(original)}")
        val files = folder.listFiles()!!.associate { it.name to it.readBytes() }.toMutableMap()
        files["manifest.json"] = JSONObject(files.getValue("manifest.json").decodeToString())
            .put("source_sha256", LightingPreparation.hashFile(source)).put("source_bytes", source.length()).toString().toByteArray()
        val job = job("video", source, files); val store = LightingPreparation(context)
        val destination = File(context.filesDir, "lighting/video/${job.sourceSha256}")
        try {
            assertTrue(runCatching { store.install(api(files, corrupt = true), id, source, job) }.isFailure)
            assertFalse(destination.exists())
            source.appendBytes(byteArrayOf(2))
            assertTrue(runCatching { store.install(api(files), id, source, job) }.isFailure)
            assertFalse(destination.exists())
        } finally { root.deleteRecursively(); destination.deleteRecursively() }
    }

    @Test fun newlyPreparedImageBecomesTheActiveVerifiedReliefWithoutChangingTheSource() = runBlocking<Unit> {
        val id = "lighting-test-${UUID.randomUUID()}"; val root = File(context.filesDir, "hero/$id").apply { mkdirs() }
        val source = File(root, "base.png"); File(assets, "bundled-heroes/$hero/base.png").copyTo(source)
        val revision = File(assets, "bundled-heroes/$hero/physics/active").readText().trim()
        val folder = File(assets, "bundled-heroes/$hero/physics/$revision")
        val files = folder.listFiles()!!.filter { it.extension == "png" || it.name == "manifest.json" }.associate { it.name to it.readBytes() }
        val job = job("image", source, files)
        try {
            LightingPreparation(context).install(api(files), id, source, job)
            val loaded = requireNotNull(PhysicsStore(context).load(id))
            assertEquals(job.sourceSha256, loaded.pack.sourceSha256)
            assertEquals(job.manifestSha256, PhysicsStore(context).revision(id))
            assertEquals(job.sourceSha256, LightingPreparation.hashFile(source))
        } finally { root.deleteRecursively() }
    }

    @Test fun assetLinksCannotEscapeTheConfiguredServerOrJob() {
        val id = "a".repeat(64); val expected = "/v1/lighting/jobs/$id/assets/manifest.json"
        val file = LightingFile("manifest.json", "b".repeat(64), 123, expected)
        assertEquals("https://example.com$expected", LightingPreparation.assetUrl("https://example.com", id, file))
        listOf("https://other.com$expected", "$expected?redirect=evil", "/v1/physics/manifest.json", "//$id.example.com$expected").forEach { url ->
            assertTrue(runCatching { LightingPreparation.assetUrl("https://example.com", id, file.copy(url = url)) }.isFailure)
        }
    }

    @Test fun allCharacterProfilesAndAllActionsCarryTheLightingContractWithinProviderLimits() {
        repeat(100) { seed ->
            assertTrue(buildCanonicalCharacterPrompt(seed).contains(relightableImageContract))
            HeroLocalStore.VIDEO_ACTIONS.forEach { action ->
                val prompt = buildCharacterAnimationPrompt(action, seed)
                validateResolvedVideoPrompt(prompt, "#00FF00")
                assertTrue(prompt.contains(relightableAnimationContract))
            }
        }
    }

    @Test fun recoveryIncludesEverySavedActionAndSkipsUnoptedLegacyHeroesAndPartialFiles() {
        val id = "lighting-test-${UUID.randomUUID()}"; val root = File(context.filesDir, "hero/$id").apply { mkdirs() }
        try {
            File(root, "base.png").writeBytes(byteArrayOf(1))
            val videos = File(root, "videos").apply { mkdirs() }
            HeroLocalStore.VIDEO_ACTIONS.forEach { File(videos, "$it.mp4").writeBytes(byteArrayOf(1)) }
            File(videos, "idle.pending.mp4").writeBytes(byteArrayOf(1))
            File(videos, "rejected/idle.mp4").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
            val store = LightingPreparation(context)
            assertTrue(store.sourcesFor(id).isEmpty())
            store.trackHero(id)
            assertEquals(HeroLocalStore.VIDEO_ACTIONS.size + 1, store.sourcesFor(id).size)
            assertEquals(HeroLocalStore.VIDEO_ACTIONS.toSet(), store.sourcesFor(id).filter { it.extension == "mp4" }.map { it.nameWithoutExtension }.toSet())
        } finally { root.deleteRecursively() }
    }
}
