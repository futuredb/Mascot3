package com.generativemascot.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.generativemascot.app.MascotApp
import com.generativemascot.app.data.CityBody
import com.generativemascot.app.data.ContextDto
import com.generativemascot.app.data.MascotApi
import com.generativemascot.app.data.MascotDto
import com.generativemascot.app.data.NameBody
import com.generativemascot.app.data.SessionStore
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.data.GenerationRoute
import com.generativemascot.app.data.OpenRouterKeyVerifier
import com.generativemascot.app.data.OpenRouterSettingsStore
import com.generativemascot.app.data.planAnimationBatch
import com.generativemascot.app.data.validateAnimationBatch
import com.generativemascot.app.data.AcceptanceGreetingStore
import com.generativemascot.app.data.AcceptanceGreetingRequest
import com.generativemascot.app.worker.AcceptanceGreetingWorker
import com.generativemascot.app.widget.updateMascotWidgets
import com.generativemascot.app.worker.MascotGenerationWorker
import com.generativemascot.app.worker.MediaRefreshWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.UUID

private const val MOSCOW_NAME = "Москва"
private const val GENERATE_TIMEOUT_MS = 360_000L
private val WAITING_STATUSES = setOf("DRAFT", "BASE_GENERATING", "BASE_QA", "UNIQUE_CHECK")

fun waitingForHero(mascot: MascotDto?): Boolean {
    if (mascot == null || !mascot.previewUrl.isNullOrBlank()) return false
    return mascot.status in WAITING_STATUSES
}

private fun generationStageLabel(stage: String?): String {
    return when (stage) {
        MascotGenerationWorker.STAGE_BASE -> "Создаём внешность героя…"
        MascotGenerationWorker.STAGE_VIDEO -> "Создаём проверяемый набор цельных анимаций — это займёт несколько минут…"
        else -> "Готовим генерацию…"
    }
}

private val MOSCOW = CityBody(
    cityId = "524901",
    name = MOSCOW_NAME,
    country = "Россия",
    timezone = "Europe/Moscow",
    lat = 55.7558,
    lon = 37.6173,
)

data class AppUiState(
    val ready: Boolean = false,
    val welcomeRequestId: Long? = null,
    val route: String = "knock",
    val cityName: String = MOSCOW_NAME,
    val creating: Boolean = false,
    val generationLabel: String? = null,
    val greetingPending: Boolean = false,
    val error: String? = null,
    val mascot: MascotDto? = null,
    val name: String = "",
    val context: ContextDto? = null,
    val busy: Boolean = false,
    val packBusy: Boolean = false,
    val packMessage: String? = null,
    val animationBatchActions: List<String> = emptyList(),
    val animationBatchId: String? = null,
    val animationLibrary: Map<String, List<String>> = emptyMap(),
    val animationPackReady: Boolean = false,
    val heroLibrary: List<HeroLibraryItem> = emptyList(),
    val generationRoute: GenerationRoute = GenerationRoute.TEAM_SERVER,
    val openRouterKeyPresent: Boolean = false,
    val openRouterKeyDraft: String = "",
    val openRouterChecking: Boolean = false,
    val openRouterMessage: String? = null,
)

data class HeroLibraryItem(
    val id: String,
    val name: String? = null,
    val baseFrames: List<String> = emptyList(),
    val baseStill: String? = null,
    val active: Boolean = false,
)

class AppViewModel(
    private val api: MascotApi,
    private val session: SessionStore,
    private val heroStore: HeroLocalStore,
    private val openRouterSettings: OpenRouterSettingsStore,
    private val openRouterVerifier: OpenRouterKeyVerifier = OpenRouterKeyVerifier(),
) : ViewModel() {
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state
    private var generateStartedAt = 0L
    private var generateSeq = 0
    private var heroSelectionSeq = 0
    private var generationGaveUp = false
    private val workManager = WorkManager.getInstance(session.appContext)
    private val foregroundWelcome = ForegroundWelcomeGate()
    private val acceptanceWorkflow = AcceptanceGreetingStore(session.appContext)
    private var greetingObserver: kotlinx.coroutines.Job? = null
    private var creationPrevious: MascotDto? = null
    private val behavior get() = (session.appContext as? com.generativemascot.app.MascotApp)?.behavior
    private var behaviorObserved = false

    fun observeBehavior() {
        if (behaviorObserved) return
        behaviorObserved = true
        viewModelScope.launch {
            behavior?.store?.states?.collectLatest { record ->
                val hero = _state.value.mascot ?: return@collectLatest
                if (hero.id != record.heroId || hero.status != "READY" || _state.value.greetingPending) return@collectLatest
                val ctx = contextForRecord(hero.id, record)
                _state.update { if (it.mascot?.id == hero.id) it.copy(context = ctx) else it }
            }
        }
    }

    fun onAppForegrounded() {
        if (behavior != null) return // ProcessLifecycleOwner handles real app foreground, not menu/rotation.
        val request = foregroundWelcome.enter()
        _state.update { it.copy(welcomeRequestId = request) }
    }

    fun onAppBackgrounded() {
        if (behavior != null) return
        foregroundWelcome.leave()
        _state.update { it.copy(welcomeRequestId = null) }
    }

    fun consumeWelcome(requestId: Long): Boolean {
        if (!foregroundWelcome.consume(requestId)) return false
        _state.update { if (it.welcomeRequestId == requestId) it.copy(welcomeRequestId = null) else it }
        return true
    }

    private fun directMode(): Boolean = openRouterSettings.isDirectModeEnabled()

    private fun savedHeroName(mascotId: String, previous: MascotDto? = null): String? {
        val current = _state.value
        return resolveHeroName(
            mascotId = mascotId,
            persistedName = heroStore.mascotName(mascotId),
            currentMascot = current.mascot,
            libraryName = current.heroLibrary.firstOrNull { it.id == mascotId }?.name,
            previousMascot = previous,
        )
    }

    init {
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun applyMoscow() {
        if (!directMode()) runCatching { api.saveCity(MOSCOW) }
        session.saveCityName(MOSCOW_NAME)
        _state.update { it.copy(cityName = MOSCOW_NAME) }
    }

    private suspend fun bootstrap() {
        _state.update {
            it.copy(
                generationRoute = if (directMode()) GenerationRoute.OPENROUTER_DIRECT else GenerationRoute.TEAM_SERVER,
                openRouterKeyPresent = openRouterSettings.hasApiKey(),
            )
        }
        val bundledImportError = try {
            // Await the IO import before reading the library; never block Application/widget startup.
            (session.appContext as? MascotApp)?.ensureBundledHeroes()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("BundledHeroes", "Cannot import bundled heroes; existing library is retained", error)
            "Не удалось добавить встроенных героев. Проверьте свободное место и откройте приложение снова."
        }
        runCatching { applyMoscow() }
        acceptanceWorkflow.pending()?.let { request ->
            restoreGreeting(request)
            return
        }
        if (directMode()) {
            val draftId = acceptanceWorkflow.draftId()
            if (draftId != null) {
                val active = withContext(Dispatchers.IO) {
                    workManager.getWorkInfosByTag("draft-base:$draftId").get().firstOrNull { !it.state.isFinished }
                }
                val base = heroStore.baseFile(draftId)
                if (active != null || base != null) {
                    val draft = MascotDto(id = draftId, name = savedHeroName(draftId),
                        status = if (base == null) "BASE_GENERATING" else "AWAITING_ACCEPTANCE",
                        previewUrl = base?.toURI()?.toString())
                    _state.update { it.copy(ready = true, mascot = draft, creating = active != null,
                        busy = active != null, route = "knock", context = null,
                        generationLabel = if (active != null) "Создаём внешность героя…" else null) }
                    if (active != null) observeGeneration(active.id, null, animationsOnly = false)
                    return
                }
                acceptanceWorkflow.clearDraft(draftId)
            }
            val currentMascotId = session.mascotId()
            val savedId = session.acceptedMascotId() ?: currentMascotId
            val localId = savedId?.takeIf { heroStore.baseFile(it) != null }
                ?: heroStore.localMascotIds().firstOrNull()
            val shown = localId?.let { id ->
                session.saveAcceptedMascot(id)
                MascotDto(
                    id = id,
                    name = heroStore.mascotName(id),
                    status = "READY",
                    promptVersion = "local-library",
                    previewUrl = heroStore.baseFile(id)?.toURI()?.toString(),
                )
            }
            var activeWork = withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork(MascotGenerationWorker.UNIQUE_WORK_NAME)
                    .get()
                    .firstOrNull { !it.state.isFinished }
            }
            val localPackReady = localId?.let(heroStore::animationPackReady) == true
            // A provider job can finish while Android has the worker in retry
            // backoff. If every durable asset is already present for that same
            // mascot, the files are the source of truth: clear the stale work so
            // the UI cannot remain forever on the generation screen.
            if (
                localPackReady &&
                currentMascotId == localId &&
                activeWork?.progress?.getString(MascotGenerationWorker.KEY_STAGE) != MascotGenerationWorker.STAGE_BASE
            ) {
                activeWork?.let { workManager.cancelWorkById(it.id) }
                activeWork = null
            }
            val activeInfo = activeWork
            val batchId = activeInfo?.id?.toString() ?: localId?.let(heroStore::latestAnimationBatchId)
            val batchActions = batchId?.let(heroStore::animationBatchActions)
                ?: if (activeInfo != null) HeroLocalStore.LEGACY_LIBRARY_VIDEO_ACTIONS else emptyList()
            val activeAnimations = activeInfo != null && localId != null && heroStore.baseFile(localId) != null &&
                activeInfo.progress.getString(MascotGenerationWorker.KEY_STAGE) != MascotGenerationWorker.STAGE_BASE
            _state.update {
                it.copy(
                    ready = true,
                    route = if (activeAnimations) "animations" else "knock",
                    cityName = MOSCOW_NAME,
                    mascot = if (activeInfo == null || activeAnimations) shown else shown?.copy(
                        previewUrl = null,
                        previewAnimationUrl = null,
                        status = "BASE_GENERATING",
                    ),
                    creating = activeInfo != null,
                    packBusy = activeAnimations,
                    packMessage = if (activeAnimations) {
                        "Создаём: ${activeInfo!!.progress.getInt(MascotGenerationWorker.KEY_COMPLETED, 0)} из " +
                            "${activeInfo.progress.getInt(MascotGenerationWorker.KEY_TOTAL, ANIMATION_PACK_SIZE)} готовы"
                    } else {
                        null
                    },
                    generationLabel = activeInfo?.progress?.getString(MascotGenerationWorker.KEY_STAGE)
                        ?.let(::generationStageLabel),
                    busy = false,
                    error = bundledImportError,
                    context = null,
                    name = shown?.name.orEmpty(),
                    animationPackReady = localPackReady,
                    animationBatchId = batchId,
                    animationBatchActions = batchActions,
                )
            }
            if (activeInfo != null) {
                observeGeneration(
                    activeInfo.id,
                    shown,
                    animationsOnly = activeAnimations,
                )
            }
            else if (shown != null) loadContext(interact = false)
            return
        }
        val savedId = session.mascotId()
        val acceptedId = session.acceptedMascotId() ?: savedId
        val restored = savedId?.let { runCatching { api.getMascot(it) }.getOrNull() }
        val accepted = acceptedId?.let { runCatching { api.getMascot(it) }.getOrNull() }
        val active = runCatching { api.activeMascot() }.getOrNull()?.takeIf { it.isSuccessful }?.body()
        val localAccepted = acceptedId
            ?.takeIf { heroStore.baseFile(it) != null }
            ?.let { MascotDto(id = it, name = heroStore.mascotName(it), status = "READY") }
        val localBundled = heroStore.localMascotIds().firstOrNull()?.let { id ->
            MascotDto(
                id = id,
                name = heroStore.mascotName(id),
                status = "READY",
                promptVersion = "bundled-library",
                previewUrl = heroStore.baseFile(id)?.toURI()?.toString(),
            )
        }
        val shown = when {
            waitingForHero(restored) || restored?.status == "AWAITING_ACCEPTANCE" -> restored
            restored?.status == "READY" -> restored
            accepted?.status == "READY" -> accepted
            active != null -> active
            localAccepted != null -> localAccepted
            localBundled != null -> localBundled
            else -> restored
        }
        val waiting = waitingForHero(shown)
        if (waiting) generateStartedAt = System.currentTimeMillis()
        generationGaveUp = false
        _state.update {
            it.copy(
                ready = true,
                route = "knock",
                cityName = MOSCOW_NAME,
                mascot = if (waiting || shown?.status == "READY" || shown?.status == "AWAITING_ACCEPTANCE") shown else null,
                creating = waiting,
                busy = false,
                error = bundledImportError ?: if (shown?.status == "FAILED_FINAL") "Не удалось сгенерировать нового героя" else null,
                context = null,
                name = shown?.name.orEmpty(),
            )
        }
        if (shown?.status == "READY") {
            session.saveAcceptedMascot(shown.id)
            cacheHero(shown.id)
            loadContext(interact = false)
        }
    }

    fun continueOnboarding() {
        _state.update { it.copy(route = "knock", cityName = MOSCOW_NAME) }
        viewModelScope.launch {
            session.markOnboarded()
            applyMoscow()
        }
    }

    fun createHero() = summonHero()

    fun replaceHero() = summonHero()

    /** Opening the creation screen is free; the three knocks are the explicit request. */
    fun startHeroCreation() {
        if (_state.value.creating || _state.value.busy || _state.value.greetingPending) return
        creationPrevious = _state.value.mascot
        _state.update { it.copy(route = "create", mascot = null, context = null, error = null, name = "") }
    }

    fun cancelHeroCreation() {
        if (_state.value.creating || _state.value.greetingPending) return
        viewModelScope.launch {
            val id = session.acceptedMascotId() ?: return@launch
            val previous = creationPrevious?.takeIf { it.id == id }
                ?: MascotDto(id = id, name = savedHeroName(id), status = "READY",
                    previewUrl = heroStore.baseFile(id)?.toURI()?.toString())
            val ctx = localContext(id)
            _state.update { it.copy(mascot = previous, route = "knock", context = ctx, error = null) }
        }
    }

    fun summonHero() {
        if (_state.value.creating || _state.value.busy || _state.value.greetingPending) return
        val existing = _state.value.mascot ?: creationPrevious
        val token = ++generateSeq
        generateStartedAt = System.currentTimeMillis()
        generationGaveUp = false
        _state.update {
            it.copy(
                creating = true,
                busy = true,
                route = "knock",
                error = null,
                name = "",
                context = null,
                mascot = existing?.copy(
                    previewUrl = null,
                    previewAnimationUrl = null,
                    status = "BASE_GENERATING",
                ),
            )
        }
        if (directMode()) {
            enqueueOnDeviceGeneration(existing, animationsOnly = false)
            return
        }
        viewModelScope.launch {
            session.markOnboarded()
            val result = if (existing != null) {
                runCatching { api.replace(existing.id, UUID.randomUUID().toString()) }
            } else {
                runCatching { api.createMascot(UUID.randomUUID().toString()) }
            }
            result
                .onSuccess { mascot ->
                    if (token != generateSeq) return@onSuccess
                    session.saveMascot(mascot.id)
                    val waiting = waitingForHero(mascot)
                    _state.update {
                        it.copy(
                            creating = waiting,
                            busy = false,
                            mascot = mascot,
                            name = "",
                            context = null,
                            route = "knock",
                            error = if (mascot.status == "FAILED_FINAL") "Не удалось сгенерировать нового героя" else null,
                        )
                    }
                }
                .onFailure { error ->
                    if (token != generateSeq) return@onFailure
                    val network = error is java.net.ConnectException ||
                        error is java.net.SocketTimeoutException ||
                        error is java.net.UnknownHostException ||
                        error.cause is java.net.ConnectException ||
                        (error.message?.contains("Failed to connect", ignoreCase = true) == true)
                    _state.update {
                        it.copy(
                            creating = false,
                            busy = false,
                            route = "knock",
                            mascot = existing,
                            error = if (!directMode() && network && existing?.status == "READY") {
                                null
                            } else if (network) {
                                "Нового героя можно создать только при доступном сервисе."
                            } else {
                                error.message ?: "Генерация не удалась, постучите ещё раз"
                            },
                        )
                    }
                }
        }
    }

    private fun enqueueOnDeviceGeneration(previous: MascotDto?, animationsOnly: Boolean, actions: List<String>? = null) {
        val mascotId = if (animationsOnly) previous?.id ?: return else UUID.randomUUID().toString()
        val selected = if (animationsOnly) validateAnimationBatch(actions) else null
        val input = androidx.work.Data.Builder()
            .putString(MascotGenerationWorker.KEY_REQUEST_TOKEN, UUID.randomUUID().toString())
            .putString(MascotGenerationWorker.KEY_MASCOT_ID, mascotId)
            .putBoolean(MascotGenerationWorker.KEY_ANIMATIONS_ONLY, animationsOnly)
        selected?.let { input.putStringArray(MascotGenerationWorker.KEY_ACTIONS, it.toTypedArray()) }
        val request = OneTimeWorkRequestBuilder<MascotGenerationWorker>()
            .addTag(if (animationsOnly) "animation-pack:$mascotId" else "draft-base:$mascotId")
            .setInputData(input.build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        if (!animationsOnly) acceptanceWorkflow.saveDraft(mascotId)
        selected?.let {
            // Persist before enqueueing, so process death cannot expand a chosen paid batch.
            heroStore.saveAnimationBatch(mascotId, request.id.toString(), it)
            _state.update { state -> state.copy(animationBatchActions = it, animationBatchId = request.id.toString()) }
        }
        workManager.enqueueUniqueWork(
            MascotGenerationWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        observeGeneration(request.id, previous, animationsOnly)
    }

    private fun observeGeneration(workId: UUID, previous: MascotDto?, animationsOnly: Boolean) {
        viewModelScope.launch {
            val batchActions = if (animationsOnly) {
                heroStore.animationBatchActions(workId.toString()) ?: HeroLocalStore.LEGACY_LIBRARY_VIDEO_ACTIONS
            } else emptyList()
            workManager.getWorkInfoByIdFlow(workId).filterNotNull().collectLatest { info ->
                when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.RUNNING -> _state.update {
                        it.copy(
                            creating = true,
                            busy = !animationsOnly,
                            packBusy = animationsOnly,
                            animationBatchActions = batchActions,
                            animationBatchId = if (animationsOnly) workId.toString() else null,
                            packMessage = if (animationsOnly) {
                                "Создаём: ${info.progress.getInt(MascotGenerationWorker.KEY_COMPLETED, 0)} из " +
                                    "${info.progress.getInt(MascotGenerationWorker.KEY_TOTAL, batchActions.size)} готовы"
                            } else {
                                it.packMessage
                            },
                            generationLabel = generationStageLabel(
                                info.progress.getString(MascotGenerationWorker.KEY_STAGE),
                            ),
                            error = null,
                            route = if (animationsOnly) "animations" else "knock",
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val mascotId = info.outputData.getString(MascotGenerationWorker.KEY_MASCOT_ID)
                        val file = mascotId?.let(heroStore::baseFile)
                        if (mascotId == null || file == null) {
                            restoreAfterGenerationFailure(previous, "Герой сгенерирован, но файл не сохранился")
                            return@collectLatest
                        }
                        if (animationsOnly) session.saveAcceptedMascot(mascotId)
                        else {
                            acceptanceWorkflow.saveDraft(mascotId)
                            session.saveMascot(mascotId)
                        }
                        val mascot = MascotDto(
                            id = mascotId,
                            name = savedHeroName(mascotId, previous),
                            status = if (animationsOnly) "READY" else "AWAITING_ACCEPTANCE",
                            promptVersion = if (heroStore.animationPackReady(mascotId)) {
                                "pet-generation-v2.5-seedance-2.0-mini-budget"
                            } else {
                                "on-device-gpt-image-2-character-contract-v5"
                            },
                            previewUrl = file.toURI().toString(),
                            stages = if (animationsOnly) mapOf("animations" to
                                if (heroStore.animationPackReady(mascotId)) "ready" else "partial") else emptyMap(),
                        )
                        val ctx = localContext(mascotId)
                        _state.update {
                            it.copy(
                                creating = false,
                                busy = false,
                                generationLabel = null,
                                mascot = mascot,
                                name = mascot.name.orEmpty(),
                                context = if (animationsOnly) ctx else null,
                                route = if (animationsOnly) "animations" else "knock",
                                error = null,
                                animationPackReady = heroStore.animationPackReady(mascotId),
                                packBusy = false,
                                packMessage = if (animationsOnly) "Выбранные ${batchActions.size} анимаций готовы" else it.packMessage,
                                animationBatchActions = batchActions,
                                animationBatchId = if (animationsOnly) workId.toString() else null,
                            )
                        }
                        publishAnimationLibrary(mascotId)
                        if (animationsOnly) pushWidget(ctx)
                        return@collectLatest
                    }
                    WorkInfo.State.FAILED,
                    WorkInfo.State.CANCELLED -> {
                        val partialId = info.outputData.getString(MascotGenerationWorker.KEY_PARTIAL_MASCOT_ID)
                        val partial = partialId?.let { id ->
                            heroStore.baseFile(id)?.let { file ->
                                MascotDto(
                                    id = id,
                                    name = savedHeroName(id, previous),
                                    status = if (animationsOnly) "READY" else "AWAITING_ACCEPTANCE",
                                    promptVersion = "on-device-gpt-image-2-character-contract-v5",
                                    previewUrl = file.toURI().toString(),
                                )
                            }
                        }
                        if (partial != null) {
                            if (animationsOnly) session.saveAcceptedMascot(partial.id)
                            else acceptanceWorkflow.saveDraft(partial.id)
                        }
                        restoreAfterGenerationFailure(
                            partial ?: previous,
                            info.outputData.getString(MascotGenerationWorker.KEY_ERROR)
                                ?: "Генерация была прервана",
                        )
                        if (animationsOnly) {
                            _state.update {
                                it.copy(
                                    route = "animations",
                                    packBusy = false,
                                    packMessage = "Генерация остановилась. Готовое сохранено — можно продолжить.",
                                    mascot = it.mascot?.let { hero ->
                                        hero.copy(stages = hero.stages + ("animations" to "failed"))
                                    },
                                )
                            }
                        }
                        return@collectLatest
                    }
                }
            }
        }
    }

    private fun restoreAfterGenerationFailure(previous: MascotDto?, message: String) {
        val restored = previous?.copy(name = savedHeroName(previous.id, previous))
        _state.update {
            it.copy(
                creating = false,
                busy = false,
                generationLabel = null,
                mascot = restored,
                name = restored?.name.orEmpty(),
                animationPackReady = previous?.id?.let(heroStore::animationPackReady) == true,
                route = "knock",
                error = message,
            )
        }
    }

    fun onName(value: String) = _state.update { it.copy(name = value) }

    fun generateStillPack(pack: Int) = requestPack("still", pack)

    fun generateVideoPack(pack: Int) = requestPack("video", pack)

    private fun requestPack(kind: String, pack: Int) {
        if (directMode()) return
        val mascot = _state.value.mascot ?: return
        if (mascot.status != "READY" || _state.value.packBusy) return
        viewModelScope.launch {
            _state.update { it.copy(packBusy = true, packMessage = null) }
            runCatching {
                if (kind == "still") api.generateStills(mascot.id, pack)
                else api.generateVideos(mascot.id, pack)
            }.onSuccess { result ->
                MediaRefreshWorker.enqueue(session.appContext, mascot.id, kind, result.queued)
                val prefix = if (kind == "still") "Статика" else "Видео"
                val blocked = if (result.blocked.isNotEmpty()) ", недоступно ${result.blocked.size}" else ""
                _state.update {
                    it.copy(
                        packBusy = false,
                        packMessage = "$prefix: готово ${result.ready.size}, в очереди ${result.queued.size}$blocked",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(packBusy = false, packMessage = error.message ?: "Запрос не выполнен") }
            }
        }
    }

    fun accept() {
        val current = _state.value
        val mascot = current.mascot ?: return
        if (current.busy || current.creating || current.packBusy) return
        if (!current.greetingPending && mascot.status != "AWAITING_ACCEPTANCE") return
        // Lock before starting a coroutine: two rapid taps cannot authorize two jobs.
        _state.update { it.withGreetingStarted() }
        viewModelScope.launch {
            runCatching {
                val pending = acceptanceWorkflow.pending() ?: acceptanceWorkflow.request(mascot.id)?.takeUnless { it.completed }
                val request = if (pending != null) {
                    val work = withContext(Dispatchers.IO) { workManager.getWorkInfoById(pending.workId).get() }
                    if (work != null && work.state.isFinished && work.state != WorkInfo.State.SUCCEEDED) {
                        acceptanceWorkflow.resume(mascot.id)
                    } else pending
                } else acceptanceWorkflow.begin(mascot.id, current.generationRoute)
                if (current.name.isNotBlank()) heroStore.saveMascotName(mascot.id, current.name)
                restoreGreeting(request)
            }.onFailure { error ->
                val persisted = acceptanceWorkflow.pending() != null
                _state.update { it.copy(busy = false, creating = false,
                    mascot = if (persisted) it.mascot else current.mascot,
                    greetingPending = persisted, route = "knock", error = error.message) }
            }
        }
    }

    private suspend fun restoreGreeting(request: AcceptanceGreetingRequest) {
        val id = request.mascotId
        val base = heroStore.baseFile(id)
        val previousPreview = _state.value.mascot?.takeIf { it.id == id }?.previewUrl
        val preview = base?.toURI()?.toString() ?: previousPreview ?: if (request.route == GenerationRoute.TEAM_SERVER) {
            runCatching { api.getMascot(id).previewUrl }.getOrNull()
        } else null
        val mascot = MascotDto(id = id, name = savedHeroName(id), status = "READY",
            previewUrl = preview)
        _state.update { it.copy(ready = true, route = "knock", mascot = mascot,
            name = mascot.name.orEmpty(), context = null, greetingPending = true,
            creating = true, busy = true, packBusy = false, error = null,
            animationBatchActions = listOf("greeting"), animationBatchId = request.workId.toString(),
            animationPackReady = heroStore.animationPackReady(id),
            generationLabel = "Готовим приветствие — одну анимацию…") }
        if (heroStore.actionVideoFile(id, "greeting") != null) {
            finishGreeting(mascot)
            return
        }
        val existing = withContext(Dispatchers.IO) { workManager.getWorkInfoById(request.workId).get() }
        if (existing == null) {
            heroStore.saveAnimationBatch(id, request.workId.toString(), listOf("greeting"))
            val work = OneTimeWorkRequestBuilder<AcceptanceGreetingWorker>()
                .setId(request.workId)
                .setInputData(workDataOf(AcceptanceGreetingWorker.KEY_ID to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            workManager.enqueueUniqueWork(AcceptanceGreetingWorker.uniqueName(id), ExistingWorkPolicy.KEEP, work)
        }
        greetingObserver?.cancel()
        greetingObserver = viewModelScope.launch {
            val info = workManager.getWorkInfoByIdFlow(request.workId).filterNotNull().first { it.state.isFinished }
            val failure = if (info.state == WorkInfo.State.SUCCEEDED) {
                runCatching { finishGreeting(mascot) }.exceptionOrNull()?.message
            } else info.outputData.getString(AcceptanceGreetingWorker.KEY_ERROR)
                ?: "Приветствие прервалось. Герой сохранён; можно продолжить прежнее задание."
            if (failure != null) _state.update {
                it.copy(creating = false, busy = false, greetingPending = true, error = failure)
            }
        }
    }

    fun deferGreeting() {
        if (_state.value.creating || _state.value.busy || !_state.value.greetingPending) return
        viewModelScope.launch {
            val pending = acceptanceWorkflow.pending() ?: return@launch
            val id = pending.mascotId
            val file = heroStore.baseFile(id) ?: return@launch
            acceptanceWorkflow.defer(pending.mascotId)
            session.saveAcceptedMascot(id)
            acceptanceWorkflow.clearDraft(id)
            greetingObserver?.cancel()
            val ctx = localContext(id)
            _state.update { it.copy(mascot = MascotDto(id = id, name = savedHeroName(id), status = "READY",
                previewUrl = file.toURI().toString()), context = ctx, name = savedHeroName(id).orEmpty(),
                greetingPending = false, creating = false, busy = false, error = null,
                generationLabel = null, route = "knock") }
        }
    }

    private suspend fun finishGreeting(mascot: MascotDto) {
        check(heroStore.actionVideoFile(mascot.id, "greeting") != null) { "Приветствие ещё не сохранено" }
        session.saveAcceptedMascot(mascot.id)
        acceptanceWorkflow.complete(mascot.id)
        val record = behavior?.refresh(com.generativemascot.app.state.StateEvent.CREATED, fetchExternal = false)
        val ctx = if (record != null) contextForRecord(mascot.id, record) else localContext(mascot.id)
        val welcome = if (behavior == null) foregroundWelcome.requestGreeting() else null
        val name = savedHeroName(mascot.id, mascot)
        _state.update { it.copy(mascot = mascot.copy(status = "READY", name = name), name = name.orEmpty(), context = ctx,
            creating = false, busy = false, greetingPending = false, generationLabel = null,
            error = null, route = if (it.route == "animations" || it.route == "settings") it.route else "knock",
            welcomeRequestId = welcome,
            animationBatchActions = listOf("greeting"), animationPackReady = heroStore.animationPackReady(mascot.id)) }
        publishAnimationLibrary(mascot.id)
        pushWidget(ctx)
    }

    fun onOpenedFromWidget() {
        if (_state.value.greetingPending) return
        viewModelScope.launch { loadContext(interact = true) }
    }

    fun pollProgress() {
        if (directMode() || _state.value.greetingPending) return
        val mascot = _state.value.mascot ?: return
        val token = generateSeq
        val mascotId = mascot.id
        if (_state.value.route != "knock" && _state.value.route != "progress") return
        viewModelScope.launch {
            runCatching { api.getMascot(mascotId) }
                .onSuccess { current ->
                    if (token != generateSeq) return@onSuccess
                    if (_state.value.mascot?.id != mascotId) return@onSuccess
                    if (current.status == "RETIRED") return@onSuccess
                    if (current.status == "FAILED_FINAL") {
                        val acceptedId = session.acceptedMascotId()
                        if (acceptedId != null && acceptedId != current.id) {
                            val restored = runCatching { api.getMascot(acceptedId) }.getOrNull()
                                ?.copy(status = "READY")
                                ?: heroStore.baseFile(acceptedId)?.let {
                                    MascotDto(id = acceptedId, status = "READY")
                                }
                            if (restored != null) {
                                session.saveMascot(restored.id)
                                val restoredContext = loadContext(interact = false)
                                _state.update {
                                    it.copy(
                                        mascot = restored,
                                        context = restoredContext,
                                        creating = false,
                                        busy = false,
                                        route = "knock",
                                        error = "Новый герой не сгенерировался. Вернули предыдущего героя.",
                                    )
                                }
                                return@onSuccess
                            }
                        }
                    }
                    val waiting = waitingForHero(current)
                    val timedOut = waiting &&
                        generateStartedAt > 0L &&
                        System.currentTimeMillis() - generateStartedAt > GENERATE_TIMEOUT_MS
                    if (timedOut || current.status == "FAILED_FINAL") generationGaveUp = true
                    val stuck = generationGaveUp && waiting
                    val failed = current.status == "FAILED_FINAL" || timedOut || stuck
                    val route = routeFor(current.status)
                    val ctx = if (current.status == "READY") loadContext(interact = true) else _state.value.context
                    _state.update {
                        it.copy(
                            mascot = current,
                            route = route,
                            busy = false,
                            creating = waiting && !generationGaveUp,
                            context = ctx,
                            name = current.name.orEmpty(),
                            error = when {
                                current.status == "FAILED_FINAL" ->
                                    "Не удалось сгенерировать нового героя"
                                timedOut || stuck -> "Генератор сейчас не отвечает. Постучите ещё раз."
                                else -> null
                            },
                        )
                    }
                }
        }
    }

    private var lastWidgetPush: String? = null

    private suspend fun persistAcceptedHero(ready: MascotDto) {
        cacheHero(ready.id)
        val ctx = loadContext(interact = true)
        _state.update { it.copy(busy = false, mascot = ready, route = "knock", context = ctx) }
        if (ctx != null) pushWidget(ctx)
    }

    private fun cacheHero(mascotId: String) {
        viewModelScope.launch {
            if (!directMode()) runCatching { heroStore.sync(api, mascotId) }
            runCatching { updateMascotWidgets(session.appContext) }
        }
    }

    fun refreshQuietly() {
        if (_state.value.greetingPending) return
        viewModelScope.launch { loadContext(interact = false) }
    }

    fun openAnimationLibrary() {
        _state.update { it.copy(route = "animations") }
        refreshAnimationLibrary()
    }

    fun completeAnimations(requestedCount: Int) {
        val mascot = _state.value.mascot ?: return
        val animationStage = mascot.stages["animations"]
        if (
            _state.value.creating ||
            _state.value.greetingPending ||
            _state.value.packBusy ||
            animationStage == "running" ||
            heroStore.animationPackReady(mascot.id)
        ) return
        val actions = runCatching {
            planAnimationBatch(
                heroStore.actionVideoUrls(mascot.id).keys,
                HeroLocalStore.LIBRARY_VIDEO_ACTIONS.filter {
                    heroStore.openRouterVideoJobId(mascot.id, it) != null
                }.toSet(),
                requestedCount,
            )
        }.getOrElse { error ->
            _state.update { it.copy(error = error.message) }
            return
        }
        if (actions.isEmpty()) { refreshAnimationLibrary(); return }
        if (!directMode()) {
            val batchId = UUID.randomUUID().toString()
            _state.update {
                it.copy(
                    route = "animations",
                    packBusy = true,
                    packMessage = "Запускаем ${actions.size} анимаций…",
                    animationBatchActions = actions,
                    animationBatchId = batchId,
                    error = null,
                    mascot = mascot.copy(
                        stages = mascot.stages + ("animations" to "running"),
                    ),
                )
            }
            viewModelScope.launch {
                runCatching { api.generateVideos(mascot.id, actions.size) }
                    .onSuccess { result ->
                        val selected = result.requested.ifEmpty { actions }
                        MediaRefreshWorker.enqueue(
                            session.appContext,
                            mascot.id,
                            "video",
                            (result.requested + result.queued).distinct(),
                        )
                        val ready = result.ready.distinct().size
                        val stage = when {
                            result.blocked.isNotEmpty() -> "failed"
                            result.queued.isNotEmpty() -> "running"
                            ready >= ANIMATION_PACK_SIZE -> "ready"
                            else -> "partial"
                        }
                        _state.update {
                            it.copy(
                                route = "animations",
                                packBusy = false,
                                animationBatchActions = selected,
                                packMessage = if (stage == "ready" || stage == "partial") {
                                    "Выбранные ${selected.size} анимаций готовы"
                                } else {
                                    "Создаём партию из ${selected.size} · всего готово $ready из $ANIMATION_PACK_SIZE"
                                },
                                mascot = mascot.copy(
                                    status = "READY",
                                    stages = mascot.stages + ("animations" to stage),
                                ),
                                error = null,
                            )
                        }
                        refreshAnimationLibrary()
                    }
                    .onFailure { error ->
                        val rejected = error is retrofit2.HttpException && error.code() in listOf(400, 401, 403, 404, 409, 429)
                        _state.update {
                            it.copy(
                                route = "animations",
                                packBusy = false,
                                mascot = if (rejected) mascot else it.mascot,
                                error = if (error is retrofit2.HttpException && error.code() == 400) {
                                    "Сервер ещё не поддерживает эту партию — нужен его апдейт. Другие анимации вместо выбранных не запускались."
                                } else "Не удалось проверить запуск: ${error.message ?: "нет связи с сервером"}",
                                animationBatchActions = if (rejected) emptyList() else it.animationBatchActions,
                                packMessage = if (error is retrofit2.HttpException && error.code() == 400) {
                                    "Сервер ещё не поддерживает эту партию — нужен его апдейт. Другие анимации вместо выбранных не запускались."
                                } else "Не удалось проверить запуск: ${error.message ?: "нет связи с сервером"}",
                            )
                        }
                        // The request may have reached the server even if the response was lost.
                        // Refresh first; never offer a second paid start while the server says
                        // the original batch is still running.
                        refreshAnimationLibrary()
                    }
            }
            return
        }
        _state.update {
            it.copy(
                route = "animations",
                creating = true,
                busy = false,
                packBusy = true,
                packMessage = "Создаём ${actions.size} анимаций; готовое сохраняется после каждого ролика",
                generationLabel = "Seedance Mini создаёт ${actions.size} анимаций…",
                error = null,
                mascot = mascot.copy(
                    status = "READY",
                    stages = mascot.stages + ("animations" to "running"),
                ),
            )
        }
        if (directMode()) {
            runCatching { enqueueOnDeviceGeneration(mascot, animationsOnly = true, actions = actions) }
                .onFailure { error ->
                    restoreAfterGenerationFailure(mascot, error.message ?: "Не удалось сохранить партию")
                    _state.update { it.copy(packBusy = false) }
                }
            return
        }
    }

    fun refreshHeroLibrary() {
        viewModelScope.launch {
            val activeId = session.acceptedMascotId() ?: session.mascotId()
            fun localItems(ids: List<String>, names: Map<String, MascotDto>): List<HeroLibraryItem> =
                ids.distinct().filter { it !in acceptanceWorkflow.unacceptedIds() }.mapNotNull { id ->
                    val base = heroStore.baseFile(id)
                    val frames = heroStore.sequenceFiles(id, "base")
                    if (base == null && frames.isEmpty()) return@mapNotNull null
                    HeroLibraryItem(
                        id = id,
                        name = resolveHeroName(id, heroStore.mascotName(id),
                            currentMascot = _state.value.mascot, libraryName = names[id]?.name),
                        baseFrames = frames.map { it.toURI().toString() },
                        baseStill = base?.toURI()?.toString(),
                        active = id == activeId,
                    )
                }

            // Show cached heroes instantly; remote refresh must never leave the
            // picker on an empty loading screen.
            _state.update { it.copy(heroLibrary = localItems(heroStore.localMascotIds(), emptyMap())) }
            if (directMode()) return@launch
            val remote = runCatching { api.mascotLibrary() }.getOrDefault(emptyList())
            val remoteById = remote.associateBy { it.id }
            val ids = (remote.map { it.id } + heroStore.localMascotIds()).distinct()
            _state.update { it.copy(heroLibrary = localItems(ids, remoteById)) }
            remote.forEach { hero ->
                runCatching { heroStore.sync(api, hero.id) }
                _state.update { it.copy(heroLibrary = localItems(ids, remoteById)) }
            }
        }
    }

    fun selectLibraryHero(mascotId: String) {
        if (_state.value.greetingPending) return
        if (_state.value.mascot?.id == mascotId) return
        val selectionToken = ++heroSelectionSeq
        val libraryItem = _state.value.heroLibrary.firstOrNull { it.id == mascotId }
        viewModelScope.launch {
            session.saveAcceptedMascot(mascotId)
            val ctx = localContext(mascotId)
            if (selectionToken != heroSelectionSeq) return@launch
            val localSelected = MascotDto(
                id = mascotId,
                name = savedHeroName(mascotId),
                status = "READY",
                promptVersion = "local-library",
                previewUrl = libraryItem?.baseStill
                    ?: heroStore.baseFile(mascotId)?.toURI()?.toString(),
            )
            // Local files are the source of truth for display. Switch in one
            // atomic state update before waiting for the server, so selection
            // feels immediate and the previous hero cannot flash back in.
            _state.update {
                it.copy(
                    mascot = localSelected,
                    name = localSelected.name.orEmpty(),
                    packMessage = null,
                    animationBatchId = heroStore.latestAnimationBatchId(mascotId),
                    animationBatchActions = heroStore.latestAnimationBatchId(mascotId)
                        ?.let(heroStore::animationBatchActions).orEmpty(),
                    context = ctx,
                    animationPackReady = heroStore.animationPackReady(mascotId),
                    route = "knock",
                    heroLibrary = it.heroLibrary.map { hero -> hero.copy(active = hero.id == mascotId) },
                )
            }
            publishAnimationLibrary(mascotId)
            if (directMode()) return@launch

            val remoteSelected = runCatching { api.activate(mascotId) }.getOrNull()
                ?: runCatching { api.getMascot(mascotId) }.getOrNull()
            if (selectionToken != heroSelectionSeq) return@launch
            remoteSelected?.let { selected ->
                val resolvedName = savedHeroName(mascotId, selected)
                _state.update {
                    it.copy(
                        mascot = selected.copy(status = "READY", name = resolvedName),
                        name = resolvedName.orEmpty(),
                    )
                }
            }
            runCatching { heroStore.sync(api, mascotId) }
        }
    }

    fun closeAnimationLibrary() = _state.update { it.copy(route = "knock") }

    fun refreshAnimationLibrary() {
        viewModelScope.launch {
            val pendingId = _state.value.mascot?.id?.takeIf { _state.value.greetingPending }
            val mascotId = pendingId ?: session.acceptedMascotId() ?: session.mascotId() ?: return@launch
            publishAnimationLibrary(mascotId)
            if (pendingId != null) return@launch // The greeting worker owns this job's sync and acceptance.
            if (!directMode()) {
                runCatching { api.getMascot(mascotId) }.getOrNull()?.let { remote ->
                    val resolvedName = savedHeroName(mascotId, remote)
                    _state.update { current ->
                        if (current.mascot?.id == mascotId) current.copy(mascot = remote.copy(name = resolvedName)) else current
                    }
                }
                runCatching { heroStore.sync(api, mascotId) }
            }
            publishAnimationLibrary(mascotId)
        }
    }

    private fun publishAnimationLibrary(mascotId: String) {
        val resolvedName = savedHeroName(mascotId)
        val library = ANIMATION_STATE_KEYS.associateWith { stateKey ->
            heroStore.librarySequenceFiles(mascotId, stateKey).map { it.toURI().toString() }
        }
        _state.update {
            it.copy(
                mascot = it.mascot?.let { hero ->
                    if (hero.id == mascotId) hero.copy(name = resolvedName) else hero
                },
                animationLibrary = library,
                animationPackReady = heroStore.animationPackReady(mascotId),
            )
        }
    }

    private suspend fun loadContext(interact: Boolean): ContextDto? {
        val selectedId = session.acceptedMascotId() ?: return null
        val rawContext = localContext(selectedId)
        val mascotId = session.acceptedMascotId() ?: session.mascotId()
        if (!directMode() && mascotId != null) {
            runCatching { heroStore.sync(api, mascotId) }
        }
        val frames = mascotId?.let { heroStore.playbackSequenceFiles(it, rawContext.stateKey) }.orEmpty()
        val still = mascotId?.let { heroStore.stateFile(it, rawContext.stateKey) }
            ?: mascotId?.let { heroStore.baseFile(it) }
        val ctx = rawContext.copy(
            stillUrl = still?.toURI()?.toString() ?: rawContext.stillUrl,
            // PngSequence intentionally reads local files.  Prefer the synced
            // sequence even when the backend also returns public frame URLs.
            animationFrames = if (frames.isEmpty()) rawContext.animationFrames else frames.map { it.toURI().toString() },
            animationFps = if (frames.isEmpty()) rawContext.animationFps else HeroLocalStore.FULL_FRAME_FPS,
        )
        return ctx.also {
        _state.update { it.copy(context = ctx) }
        pushWidget(ctx)
    }
    }

    private suspend fun localContext(mascotId: String): ContextDto {
        behavior?.let { coordinator ->
            return contextForRecord(mascotId, coordinator.refresh(fetchExternal = false))
        }
        val stateKey = "idle" // Test/preview contexts without MascotApp do not choose states.
        val still = heroStore.stateFile(mascotId, stateKey) ?: heroStore.baseFile(mascotId)
        val frames = heroStore.playbackSequenceFiles(mascotId, stateKey)
        return ContextDto(
            stateKey = stateKey,
            reasonCode = "local_context",
            reasonText = "Состояние выбрано на телефоне",
            cityName = MOSCOW_NAME,
            stillUrl = still?.toURI()?.toString(),
            animationUrl = heroStore.actionVideoFile(mascotId, "idle")?.toURI()?.toString()
                ?: heroStore.performanceVideoFile(mascotId)?.toURI()?.toString(),
            animationFrames = frames.map { it.toURI().toString() },
            animationFps = HeroLocalStore.FULL_FRAME_FPS,
        )
    }

    private fun contextForRecord(mascotId: String, record: com.generativemascot.app.state.StateRecord): ContextDto {
        val action = com.generativemascot.app.state.renderedAction(record, heroStore.actionVideoUrls(mascotId).keys)
        return ContextDto(stateKey = action, reasonCode = record.triggerReason,
            reasonText = "${record.currentStateId.name}: ${record.triggerReason}", cityName = MOSCOW_NAME,
            stillUrl = (heroStore.stateFile(mascotId, action) ?: heroStore.baseFile(mascotId))?.toURI()?.toString(),
            animationUrl = heroStore.actionVideoFile(mascotId, action)?.toURI()?.toString()
                ?: heroStore.performanceVideoFile(mascotId)?.toURI()?.toString(),
            animationFrames = heroStore.playbackSequenceFiles(mascotId, action).map { it.toURI().toString() },
            animationFps = HeroLocalStore.FULL_FRAME_FPS)
    }

    private suspend fun pushWidget(ctx: ContextDto) {
        val mascotId = session.acceptedMascotId() ?: session.mascotId()
        if (!directMode() && mascotId != null) {
            runCatching { heroStore.sync(api, mascotId) }
        }
        val key = "${behavior?.store?.current?.revision}|${ctx.stateKey}|${ctx.stillUrl}|${ctx.animationFrames.size}"
        if (key == lastWidgetPush) return
        lastWidgetPush = key
        runCatching { updateMascotWidgets(session.appContext) }
    }
    fun openSettings() = _state.update {
        it.copy(
            route = "settings",
            openRouterKeyDraft = "",
            openRouterMessage = null,
            generationRoute = if (directMode()) GenerationRoute.OPENROUTER_DIRECT else GenerationRoute.TEAM_SERVER,
            openRouterKeyPresent = openRouterSettings.hasApiKey(),
        )
    }
    fun backToHero() = _state.update { it.copy(route = "knock") }

    fun onOpenRouterKey(value: String) = _state.update {
        it.copy(openRouterKeyDraft = value.trim().take(256), openRouterMessage = null)
    }

    fun useTeamServer() {
        if (_state.value.creating || _state.value.packBusy) return
        openRouterSettings.setRoute(GenerationRoute.TEAM_SERVER)
        _state.update {
            it.copy(
                generationRoute = GenerationRoute.TEAM_SERVER,
                openRouterMessage = "Новые генерации пойдут через командный сервер",
            )
        }
    }

    fun saveAndUseOpenRouterKey() {
        if (_state.value.creating || _state.value.packBusy || _state.value.openRouterChecking) return
        val draft = _state.value.openRouterKeyDraft
        val candidate = draft.takeIf { it.isNotBlank() } ?: openRouterSettings.apiKey()
        if (candidate == null) {
            _state.update { it.copy(openRouterMessage = "Вставьте ключ OpenRouter") }
            return
        }
        _state.update { it.copy(openRouterChecking = true, openRouterMessage = "Проверяем ключ без платной генерации…") }
        viewModelScope.launch {
            runCatching { openRouterVerifier.verify(candidate) }
                .onSuccess { check ->
                    if (draft.isNotBlank()) openRouterSettings.saveApiKey(candidate)
                    openRouterSettings.setRoute(GenerationRoute.OPENROUTER_DIRECT)
                    val balance = check.remainingUsd?.let { " · доступно $%.2f".format(it) }.orEmpty()
                    _state.update {
                        it.copy(
                            generationRoute = GenerationRoute.OPENROUTER_DIRECT,
                            openRouterKeyPresent = true,
                            openRouterKeyDraft = "",
                            openRouterChecking = false,
                            openRouterMessage = "Ключ подключён$balance. Новые генерации идут напрямую в OpenRouter.",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            openRouterChecking = false,
                            openRouterMessage = error.message ?: "Не удалось проверить ключ",
                        )
                    }
                }
        }
    }

    fun removeOpenRouterKey() {
        if (_state.value.creating || _state.value.packBusy) return
        openRouterSettings.removeApiKey()
        _state.update {
            it.copy(
                generationRoute = GenerationRoute.TEAM_SERVER,
                openRouterKeyPresent = false,
                openRouterKeyDraft = "",
                openRouterMessage = "Ключ удалён с телефона",
            )
        }
    }

    fun saveName() {
        saveName(returnHome = true)
    }

    fun saveNameInPlace() {
        saveName(returnHome = false)
    }

    private fun saveName(returnHome: Boolean) {
        val mascot = _state.value.mascot ?: return
        val requestedName = _state.value.name
        viewModelScope.launch {
            if (directMode()) {
                runCatching { heroStore.saveMascotName(mascot.id, requestedName) }
                    .onSuccess { savedName ->
                        _state.update {
                            it.withSavedHeroName(mascot.id, savedName).copy(
                                route = if (returnHome && it.mascot?.id == mascot.id) "knock" else it.route,
                            )
                        }
                    }
                    .onFailure { error ->
                        _state.update { it.copy(error = error.message ?: "Не удалось сохранить имя") }
                    }
                return@launch
            }
            runCatching { api.rename(mascot.id, NameBody(requestedName)) }
                .onSuccess { updated ->
                    val savedName = updated.name?.takeIf { it.isNotBlank() } ?: requestedName.trim()
                    runCatching { heroStore.saveMascotName(mascot.id, savedName) }
                    _state.update {
                        it.withSavedHeroName(mascot.id, savedName).copy(
                            route = if (returnHome && it.mascot?.id == mascot.id) "knock" else it.route,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "Не удалось сохранить имя") }
                }
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            if (!directMode()) runCatching { api.deleteAccount() }
            session.clear()
            _state.update {
                AppUiState(
                    ready = true,
                    route = "knock",
                    generationRoute = if (directMode()) GenerationRoute.OPENROUTER_DIRECT else GenerationRoute.TEAM_SERVER,
                    openRouterKeyPresent = openRouterSettings.hasApiKey(),
                )
            }
        }
    }

    companion object {
        fun routeFor(status: String) = when (status) {
            "AWAITING_ACCEPTANCE" -> "knock"
            "READY" -> "knock"
            "FAILED_FINAL" -> "knock"
            else -> "knock"
        }

        fun factory(
            api: MascotApi,
            session: SessionStore,
            heroStore: HeroLocalStore,
            openRouterSettings: OpenRouterSettingsStore,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(api, session, heroStore, openRouterSettings) as T
        }
    }
}
