package com.generativemascot.app.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.generativemascot.app.MascotApp
import com.generativemascot.app.data.MascotApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine

/** Exercise the actual ViewModel on cached user data; no requests reach any provider. */
class AppStartupInstrumentedTest {
    @Test fun cachedHeroOpensWhileAllServerRequestsAreStalled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MascotApp
        assumeFalse(app.openRouterSettings.isDirectModeEnabled())
        runBlocking { app.ensureBundledHeroes() }
        val originalAccepted = runBlocking { app.session.acceptedMascotId() }
        val originalCurrent = runBlocking { app.session.mascotId() }
        val paidCalls = AtomicInteger()
        val requests = AtomicInteger()
        val api = Proxy.newProxyInstance(MascotApi::class.java.classLoader, arrayOf(MascotApi::class.java)) { _, method, args ->
            if (method.name in setOf("createMascot", "generateGreeting", "generateStills", "generateVideos", "replace", "preparePhysics")) {
                paidCalls.incrementAndGet(); error("Startup must never submit paid work")
            }
            @Suppress("UNCHECKED_CAST")
            val continuation = args!!.last() as Continuation<Any?>
            requests.incrementAndGet()
            val stalled: suspend () -> Any? = { delay(60_000); error("Request should be cancelled") }
            stalled.startCoroutine(continuation)
            COROUTINE_SUSPENDED
        } as MascotApi
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        lateinit var model: AppViewModel
        val started = SystemClock.elapsedRealtime()
        try {
            instrumentation.runOnMainSync {
                model = ViewModelProvider(owner, AppViewModel.factory(api, app.session, app.heroStore, app.openRouterSettings))[AppViewModel::class.java]
            }
            while (!model.state.value.ready && SystemClock.elapsedRealtime() - started < 5000) SystemClock.sleep(20)
            val state = model.state.value
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue("Cached hero remained behind the startup spinner", state.ready)
            assertEquals("READY", state.mascot?.status)
            assertNotNull(state.context?.stillUrl)
            assertTrue(state.context!!.stillUrl!!.startsWith("file:"))
            assertEquals(originalAccepted, runBlocking { app.session.acceptedMascotId() })
            assertEquals(originalCurrent, runBlocking { app.session.mascotId() })
            assertEquals(0, paidCalls.get())
            val output = File(app.getExternalFilesDir(null), "startup-acceptance").apply { mkdirs() }
            File(output, "offline-startup.json").writeText("""{"ready_ms":$elapsed,"hero":"${state.mascot?.id}","local_context":true,"sessions_unchanged":true,"paid_calls":0,"all_server_calls_stalled":true}""")
        } finally {
            instrumentation.runOnMainSync { owner.viewModelStore.clear() }
        }
    }
}
