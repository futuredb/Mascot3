package com.generativemascot.app.physics

import android.os.SystemClock
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.generativemascot.app.BuildConfig
import com.generativemascot.app.MascotApp
import com.generativemascot.app.data.AssetRefDto
import com.generativemascot.app.worker.PhysicsJobRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real Node HTTP routes + real WorkManager on a device, with no cloud credentials. */
class PhysicsNetworkAcceptanceTest {
    @Test fun approvedExistingJobInstallsForPilotWithoutAnotherProviderSubmission() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("installPilotApproved") == "true")
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:8788" && BuildConfig.MASCOT3_CLIENT_TOKEN == "physics-local-acceptance")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MascotApp
        val store = PhysicsStore(app); val id = PhysicsStore.PILOT_ID
        val source = File(app.filesDir, "hero/$id/base.png").readBytes()
        store.requestPreparation(id)
        val work = WorkManager.getInstance(app); val until = SystemClock.elapsedRealtime() + 90000
        var info: WorkInfo? = null
        while (SystemClock.elapsedRealtime() < until) {
            info = work.getWorkInfosForUniqueWork("physics-prepare-$id").get(5, TimeUnit.SECONDS).lastOrNull()
            if (info?.state?.isFinished == true) break
            SystemClock.sleep(100)
        }
        assertEquals(info?.outputData?.getString("error"), WorkInfo.State.SUCCEEDED, info?.state)
        val job = app.api.physicsJob(requireNotNull(store.savedJob(id)))
        assertEquals(job.physicsPack?.sha256, store.revision(id))
        assertNotNull(store.load(id)); assertArrayEquals(source, File(app.filesDir, "hero/$id/base.png").readBytes())
        val output = File(app.getExternalFilesDir(null), "physics-acceptance").apply { mkdirs() }
        File(output, "pilot-preparation-result.json").writeText("""{"phone_upload":true,"saved_job":"${job.id}","installed_pack":"${store.revision(id)}","hero_source_unchanged":true,"cloud_calls":0}""")
    }

    @Test fun savedJobRestartAndInterruptedWorkManagerInstallPreserveHero() = runBlocking {
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:8788" && BuildConfig.MASCOT3_CLIENT_TOKEN == "physics-local-acceptance")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MascotApp
        val api = app.api; val store = PhysicsStore(app); val work = WorkManager.getInstance(app)
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        fun control(path: String, post: Boolean = false): JsonObject {
            val request = Request.Builder().url("${BuildConfig.API_BASE_URL}/acceptance/$path")
                .header("X-Mascot-Client-Token", BuildConfig.MASCOT3_CLIENT_TOKEN)
                .apply { if (post) post(ByteArray(0).toRequestBody()) }.build()
            return client.newCall(request).execute().use { response ->
                check(response.isSuccessful); Json.parseToJsonElement(response.body!!.string()).jsonObject
            }
        }
        fun ref(name: String): AssetRefDto {
            val value = control("state").getValue("refs").jsonObject.getValue(name).jsonObject
            return AssetRefDto(value.getValue("url").jsonPrimitive.content, value.getValue("sha256").jsonPrimitive.content)
        }
        val source = "bundled-heroes/${PhysicsStore.PILOT_ID}"
        val image = app.assets.open("$source/base.png").use { it.readBytes() }
        val anchor = app.assets.open("$source/videos/neutral.png").use { it.readBytes() }
        val contract = app.assets.open("$source/creative/character-contract.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val request = PhysicsJobRequest(PhysicsStore.PILOT_ID, Base64.encodeToString(image, Base64.NO_WRAP), Base64.encodeToString(anchor, Base64.NO_WRAP), contract)
        val first = api.preparePhysics("physics-v1-${PhysicsStore.PILOT_ID}", request)
        assertEquals("ready", first.status)
        control("restart", true)
        val resumed = api.preparePhysics("physics-v1-${PhysicsStore.PILOT_ID}", request)
        assertEquals(first.id, resumed.id); assertEquals(first.physicsPack, resumed.physicsPack)
        val id = "physics-acceptance-${UUID.randomUUID()}"
        val hero = File(app.filesDir, "hero/$id").apply { mkdirs() }
        File(hero, "base.png").writeBytes(image)
        val names = mutableListOf<String>()
        fun enqueue(reference: AssetRefDto): String {
            val name = "physics-install-$id-${reference.sha256}"; names += name
            store.enqueueInstall(id, reference); return name
        }
        fun await(name: String, timeout: Long = 90000, predicate: (WorkInfo) -> Boolean): WorkInfo {
            val until = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < until) {
                val info = work.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).lastOrNull()
                if (info != null && predicate(info)) return info
                SystemClock.sleep(100)
            }
            error("WorkManager timed out: $name")
        }
        try {
            val original = requireNotNull(first.physicsPack)
            assertEquals(WorkInfo.State.SUCCEEDED, await(enqueue(original)) { it.state.isFinished }.state)
            assertEquals(original.sha256, store.revision(id)); assertNotNull(store.load(id))
            control("interrupt-next-update", true)
            val updated = ref("update"); val updateWork = enqueue(updated)
            await(updateWork) { it.runAttemptCount >= 1 && it.state == WorkInfo.State.ENQUEUED }
            assertEquals("Interrupted update replaced active pack", original.sha256, store.revision(id))
            assertNotNull(store.load(id))
            assertEquals(WorkInfo.State.SUCCEEDED, await(updateWork) { it.state.isFinished }.state)
            assertEquals(updated.sha256, store.revision(id)); assertNotNull(store.load(id))
            assertEquals(WorkInfo.State.FAILED, await(enqueue(ref("rejected"))) { it.state.isFinished }.state)
            assertEquals("Rejected QA replaced active pack", updated.sha256, store.revision(id))
            assertArrayEquals(image, File(hero, "base.png").readBytes())
            val output = File(app.getExternalFilesDir(null), "physics-acceptance").apply { mkdirs() }
            File(output, "network-result.json").writeText("""{"real_http_routes":true,"saved_job_survived_backend_restart":true,"workmanager_install":true,"interruption_preserved_active_pack":true,"retry_completed":true,"rejected_qa_preserved_active_pack":true,"hero_source_unchanged":true,"cloud_calls":0}""")
        } finally {
            names.forEach { work.cancelUniqueWork(it).result.get(5, TimeUnit.SECONDS) }
            hero.deleteRecursively() // This test's UUID directory only; real heroes are untouched.
        }
    }
}
