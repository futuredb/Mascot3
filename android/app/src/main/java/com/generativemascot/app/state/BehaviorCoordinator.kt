package com.generativemascot.app.state

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.generativemascot.app.MascotApp
import com.generativemascot.app.widget.updateMascotWidgets
import com.generativemascot.app.worker.ContextRefreshWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** One process-wide owner for state transitions, regardless of generation route. */
class BehaviorCoordinator(private val app: MascotApp) {
    val store = StateStore(app)
    val config = StateConfigStore(app)
    private val analytics = StateAnalytics(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolver = StateResolver()
    private val refreshLock = Mutex()
    private val externalReadLock = Mutex()
    private var foregroundJob: Job? = null
    private var debounceJob: Job? = null
    @Volatile private var foreground = false
    @Volatile private var lastWeatherRead = 0L
    @Volatile private var lastHealthRead = 0L
    @Volatile private var pendingEntry = false
    private var armedDue: Long? = null
    private val durationCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()
    private val reported = mutableSetOf<String>()

    fun start() {
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) { entered() }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { left() }
        })
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = deviceSignalChanged()
            override fun onLost(network: Network) = deviceSignalChanged()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = deviceSignalChanged()
        }) }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { deviceSignalChanged() }
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { refresh() }
    }

    private fun deviceSignalChanged() {
        scope.launch { refresh(fetchExternal = false) }
    }

    private fun armPendingSignals() {
            val r = store.current
            val c = config.get()
            val now = System.currentTimeMillis()
            val due = listOfNotNull(r.networkChangedAt?.plus(c.networkDebounceSeconds * 1000L),
                r.currentChargingSession?.plus(c.chargingDebounceMinutes * MINUTE)?.takeIf { !r.chargingStateShown })
                .filter { it > now }.minOrNull() ?: run { armedDue = null; return }
            if (armedDue == due) return
            armedDue = due
            debounceJob?.cancel()
            debounceJob = scope.launch { delay((due - System.currentTimeMillis()).coerceAtLeast(1)); refresh(fetchExternal = false) }
            WorkManager.getInstance(app).enqueueUniqueWork("mascot-device-debounce:$due", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ContextRefreshWorker>().setInitialDelay((due - now).coerceAtLeast(1), TimeUnit.MILLISECONDS).build())
    }

    fun entered() {
        if (foreground) return
        foreground = true
        pendingEntry = true
        foregroundJob?.cancel()
        foregroundJob = scope.launch {
            refresh(fetchExternal = false)
            scope.launch { refresh() }
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                if (store.current.stateExpiresAt <= now) refresh(fetchExternal = false)
                val rules = config.get()
                if (now - lastHealthRead >= rules.healthPollMinutes * MINUTE || now - lastWeatherRead >= rules.weatherPollMinutes * MINUTE)
                    scope.launch { refresh() }
            }
        }
    }

    fun left() {
        foreground = false
        foregroundJob?.cancel()
        scope.launch { store.update { it.copy(lastAppLeftAt = System.currentTimeMillis()) } }
    }
    suspend fun configurationChanged() {
        lastHealthRead = 0
        store.update { it.copy(stateExpiresAt = 0,
            healthSnapshot = if (config.get().healthTriggersEnabled) it.healthSnapshot else null) }
        WorkManager.getInstance(app).enqueueUniquePeriodicWork("mascot-context-refresh",
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            androidx.work.PeriodicWorkRequestBuilder<ContextRefreshWorker>(config.get().refreshMinutes.toLong(), TimeUnit.MINUTES).build())
        refresh()
    }

    fun gesture(event: StateEvent) { scope.launch { refresh(event, fetchExternal = false) } }
    fun created() { scope.launch { refresh(StateEvent.CREATED, fetchExternal = false) } }
    fun clipFinished(action: String) {
        if (action == store.current.currentStateId.action && action in setOf("greeting", "signature_move"))
            gesture(StateEvent.CLIP_FINISHED)
    }

    suspend fun refresh(event: StateEvent = StateEvent.AUTO, fetchExternal: Boolean = true): StateRecord {
        var weather: WeatherObservation? = null
        var health: HealthSnapshot? = null
        // Slow providers must never block a tap, swipe, sleep or local state publication.
        if (fetchExternal && externalReadLock.tryLock()) {
            try {
                val readAt = System.currentTimeMillis()
                val c = config.get()
                if (readAt - lastWeatherRead >= c.weatherPollMinutes * MINUTE) {
                    lastWeatherRead = readAt
                    weather = runCatching { readWeather(readAt) }.getOrNull()
                }
                val mayReadHealth = c.healthTriggersEnabled && (foreground || c.healthBackgroundReadEnabled && HealthSignals.backgroundGranted(app))
                if (mayReadHealth && c.healthTriggersEnabled && readAt - lastHealthRead >= c.healthPollMinutes * MINUTE) {
                    lastHealthRead = readAt
                    health = try { withTimeout(20_000) { HealthSignals.read(app, c, readAt) } }
                        catch (_: TimeoutCancellationException) { null }
                } else if (!mayReadHealth || !c.healthTriggersEnabled) lastHealthRead = readAt
            } finally { externalReadLock.unlock() }
        }
        return refreshLock.withLock {
        val now = System.currentTimeMillis()
        val c = config.get()
        val hero = app.session.acceptedMascotId()
        val available = hero?.let { app.heroStore.actionVideoUrls(it).keys }.orEmpty()
        val signals = StateSignals(networkType(), charging(), weather,
            if (c.healthTriggersEnabled) health else HealthSnapshot())
        val old = store.current
        val actualEvent = if (event == StateEvent.AUTO && pendingEntry && hero != null) StateEvent.ENTER else event
        if (actualEvent == StateEvent.ENTER) pendingEntry = false
        val next = store.update { saved ->
            val activeHero = if (hero != saved.heroId) saved.copy(heroId = hero, manualSleep = false,
                stateExpiresAt = 0, lastRenderedAction = null, previousBaseState = null, consecutiveBaseCount = 0,
                lastShownAt = emptyMap(), lastTriggerAt = emptyMap(), priority = 15) else saved
            val resolved = resolver.resolve(activeHero, now, c, signals, actualEvent, available, clipDurations(hero, available))
            resolved.copy(lastRenderedAction = renderedAction(resolved, available))
        }
        // Compatibility mirror only: SessionStore is no longer a selector.
        app.session.saveCurrentState(next.lastRenderedAction ?: next.currentStateId.action)
        if (next.revision != old.revision || next.heroId != old.heroId || next.lastRenderedAction != old.lastRenderedAction) {
            if (old.stateStartedAt > 0) analytics.log("state_finished", old, null, now)
            analytics.log("state_selected", next, old.currentStateId, now)
            scope.launch { runCatching { updateMascotWidgets(app) } }
        }
        armPendingSignals()
        next
        }
    }

    fun observed(surface: String, action: String) {
        scope.launch {
            val r = store.current
            if (action != r.lastRenderedAction && !(action == "sleep_loop" && r.lastRenderedAction == "sleeping")) return@launch
            val key = "${r.heroId}:${r.revision}:$surface:$action"
            if (synchronized(reported) { if (reported.size > 256) reported.clear(); reported.add(key) }) {
                if (surface == "app" && r.currentStateId in setOf(PetState.GREET, PetState.SIGNATURE) && action == r.currentStateId.action) {
                    val started = System.currentTimeMillis()
                    val duration = clipDurations(r.heroId, setOf(action))[action] ?: 6000L
                    store.update { current -> if (current.revision == r.revision)
                        current.copy(stateStartedAt = started, stateExpiresAt = started + duration) else current }
                }
                analytics.log("state_started", r, null, System.currentTimeMillis(), surface, action)
            }
        }
    }

    private fun clipDurations(hero: String?, available: Set<String>): Map<String, Long> {
        if (hero == null) return emptyMap()
        return listOf("greeting", "signature_move").filter { it in available }.associateWith { action ->
            val file = app.heroStore.actionVideoFile(hero, action) ?: return@associateWith 6000L
            val key = "$hero:$action"
            val cached = durationCache[key]
            if (cached?.first == file.lastModified()) cached.second else {
                val duration = runCatching {
                    MediaMetadataRetriever().let { reader ->
                        try { reader.setDataSource(file.absolutePath); reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() }
                        finally { reader.release() }
                    }
                }.getOrNull() ?: 6000L
                durationCache[key] = file.lastModified() to duration
                duration
            }
        }
    }

    private fun networkType(): String? = runCatching {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return@runCatching "NONE"
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return@runCatching "NONE"
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "OTHER"
        }
    }.getOrNull()
    private fun charging(): Boolean? = runCatching {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return@runCatching null
        battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in setOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
    }.getOrNull()

    private fun readWeather(now: Long): WeatherObservation {
        val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).build()
        val url = "https://api.open-meteo.com/v1/forecast?latitude=55.7558&longitude=37.6173" +
            "&current=temperature_2m,cloud_cover,weather_code,precipitation,wind_speed_10m,wind_gusts_10m&timezone=UTC"
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful)
            val current = JSONObject(response.body!!.string()).getJSONObject("current")
            val code = current.getInt("weather_code")
            return WeatherObservation(now, current.getDouble("temperature_2m"), current.getInt("cloud_cover"),
                code.toString(), current.optDouble("precipitation", 0.0), current.optDouble("wind_speed_10m", 0.0),
                current.optDouble("wind_gusts_10m", 0.0), code in 45..48, code >= 95)
        }
    }
}
