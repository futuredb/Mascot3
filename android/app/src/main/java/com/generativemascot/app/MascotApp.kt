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
import com.generativemascot.app.data.BundledHeroImporter
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.data.OpenRouterSettingsStore
import com.generativemascot.app.data.SessionStore
import com.generativemascot.app.data.createApi
import com.generativemascot.app.worker.ContextRefreshWorker
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MascotApp : Application(), ImageLoaderFactory {
    lateinit var session: SessionStore
        private set
    val api by lazy { createApi { runBlocking { session.deviceId() } } }
    lateinit var heroStore: HeroLocalStore
        private set
    lateinit var openRouterSettings: OpenRouterSettingsStore
        private set
    private val bundledHeroesMutex = Mutex()
    private var bundledHeroesImported = false
    lateinit var behavior: com.generativemascot.app.state.BehaviorCoordinator
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
        session = SessionStore(this)
        heroStore = HeroLocalStore(this)
        openRouterSettings = OpenRouterSettingsStore(this)
        HeroLocalStore.attach(heroStore)
        behavior = com.generativemascot.app.state.BehaviorCoordinator(this)
        behavior.start()
        val work = PeriodicWorkRequestBuilder<ContextRefreshWorker>(behavior.config.get().refreshMinutes.toLong(), TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "mascot-context-refresh",
            ExistingPeriodicWorkPolicy.UPDATE,
            work,
        )
    }

    /**
     * Friend/demo builds may ship with a snapshot of an existing on-device
     * library under assets/bundled-heroes. Copy missing files only so app
     * updates never overwrite characters or animations created on the device.
     */
    internal suspend fun ensureBundledHeroes() = withContext(Dispatchers.IO) {
        bundledHeroesMutex.withLock {
            if (!bundledHeroesImported) {
                BundledHeroImporter(
                    listAssets = { path -> assets.list(path).orEmpty() },
                    openAsset = { path -> assets.open(path) },
                    destinationRoot = File(filesDir, "hero"),
                ).importAll()
                bundledHeroesImported = true
            }
        }
    }
}
