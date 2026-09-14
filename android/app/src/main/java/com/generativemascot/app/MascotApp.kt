package com.generativemascot.app

import android.app.Application
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.data.SessionStore
import com.generativemascot.app.data.createApi
import com.generativemascot.app.worker.ContextRefreshWorker
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

class MascotApp : Application(), ImageLoaderFactory {
    lateinit var session: SessionStore
        private set
    val api by lazy { createApi { runBlocking { session.deviceId() } } }
    lateinit var heroStore: HeroLocalStore
        private set

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        seedBundledHeroes()
        session = SessionStore(this)
        heroStore = HeroLocalStore(this)
        HeroLocalStore.attach(heroStore)
        if (!BuildConfig.DIRECT_OPENAI_GENERATION) {
            val work = PeriodicWorkRequestBuilder<ContextRefreshWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "mascot-context-refresh",
                ExistingPeriodicWorkPolicy.UPDATE,
                work,
            )
        } else {
            WorkManager.getInstance(this).cancelUniqueWork("mascot-context-refresh")
        }
    }

    /**
     * Friend/demo builds may ship with a snapshot of an existing on-device
     * library under assets/bundled-heroes. Copy missing files only so app
     * updates never overwrite characters or animations created on the device.
     */
    private fun seedBundledHeroes() {
        val assetRoot = "bundled-heroes"
        val heroIds = assets.list(assetRoot).orEmpty()
        if (heroIds.isEmpty()) return
        val destinationRoot = File(filesDir, "hero").apply { mkdirs() }
        heroIds.forEach { heroId ->
            copyAssetTree("$assetRoot/$heroId", File(destinationRoot, heroId))
        }
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            destination.mkdirs()
            children.forEach { child ->
                copyAssetTree("$assetPath/$child", File(destination, child))
            }
            return
        }
        if (destination.isFile) return
        destination.parentFile?.mkdirs()
        assets.open(assetPath).use { input ->
            destination.outputStream().use(input::copyTo)
        }
    }
}
