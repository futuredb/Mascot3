package com.generativemascot.app.data

import android.app.Application
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FalVideoProviderTest {
    @Test fun submitsMatchingAnchorsOnceAndKeepsProviderIdentity() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val request = chain.request()
            assertEquals("Key test-key", request.header("Authorization"))
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            assertEquals(body.getString("image_url"), body.getString("end_image_url"))
            assertEquals(6, body.getInt("duration"))
            assertEquals("1080P", body.getString("resolution"))
            assertEquals("disabled", body.getString("prompt_expansion_mode"))
            response(request, """{"request_id":"abc-123"}""")
        }.build()
        val reference = File.createTempFile("fal-reference", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            assertEquals("fal-h3-abc-123", FalVideoProvider(client, "test-key").submit(reference, "hello", 6))
            assertEquals(1, calls)
        } finally { reference.delete() }
    }

    @Test fun resumesSavedJobAndNeverSendsCredentialToVideoHost() = runBlocking {
        val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val host = request.url.host; calls += request.url.encodedPath
            if (host == "queue.fal.run") assertEquals("Key test-key", request.header("Authorization"))
            else assertNull(request.header("Authorization"))
            when {
                request.url.encodedPath.endsWith("/status") -> response(request, """{"status":"COMPLETED"}""")
                host == "queue.fal.run" -> response(request, """{"video":{"url":"https://v3b.fal.media/result.mp4"}}""")
                else -> response(request, "x".repeat(2048))
            }
        }.build()
        val provider = FalVideoProvider(client, "test-key")
        provider.poll("fal-h3-abc-123", "greeting")
        assertEquals(2048, provider.download("fal-h3-abc-123").size)
        assertEquals(listOf("/minimax/h3-max-turbo/requests/abc-123/status", "/minimax/h3-max-turbo/requests/abc-123", "/result.mp4"), calls)
    }

    @Test fun rejectsUntrustedVideoUrlsAndInvalidJobIds() {
        assertTrue(validFalVideoUrl("https://v3b.fal.media/files/video.mp4"))
        assertFalse(validFalVideoUrl("https://fal.media.attacker.example/video"))
        assertFalse(validFalVideoUrl("http://fal.media/video"))
        assertFalse(validFalVideoUrl("https://user@fal.media/video"))
        assertThrows(IllegalArgumentException::class.java) { requireFalRequestId("fal-h3-../../other") }
        assertThrows(IllegalArgumentException::class.java) { requireFalRequestId("legacy-job") }
    }

    @Test fun greetingPromptPreservesLoopAndTechnicalMatte() {
        val prompt = buildFalGreetingPrompt("#00FF00")
        validateResolvedVideoPrompt(prompt, "#00FF00")
        assertTrue(prompt.contains("first and final images are identical"))
        assertTrue(prompt.contains("greeting"))
    }

    private fun response(request: okhttp3.Request, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody("application/json".toMediaType())).build()
}
