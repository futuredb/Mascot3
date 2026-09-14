package com.generativemascot.app.ui

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
import com.generativemascot.app.BuildConfig
import com.generativemascot.app.data.CityBody
import com.generativemascot.app.data.ContextDto
import com.generativemascot.app.data.MascotApi
import com.generativemascot.app.data.MascotDto
import com.generativemascot.app.data.NameBody
import com.generativemascot.app.data.SessionStore
import com.generativemascot.app.data.LocalContextResolver
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.widget.updateMascotWidgets
import com.generativemascot.app.worker.MascotGenerationWorker
import com.generativemascot.app.worker.MediaRefreshWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
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
    val route: String = "knock",
    val cityName: String = MOSCOW_NAME,
    val creating: Boolean = false,
    val generationLabel: String? = null,
    val error: String? = null,
    val mascot: MascotDto? = null,
    val name: String = "",
    val context: ContextDto? = null,
    val busy: Boolean = false,
    val packBusy: Boolean = false,
    val packMessage: String? = null,
    val animationLibrary: Map<String, List<String>> = emptyMap(),
    val animationPackReady: Boolean = false,
    val heroLibrary: List<HeroLibraryItem> = emptyList(),
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
) : ViewModel() {
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state
    private var generateStartedAt = 0L
    private var generateSeq = 0
    private var heroSelectionSeq = 0
    private var generationGaveUp = false
    private val workManager = WorkManager.getInstance(session.appContext)

    init {
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun applyMoscow() {
        if (!BuildConfig.DIRECT_OPENAI_GENERATION) runCatching { api.saveCity(MOSCOW) }
        session.saveCityName(MOSCOW_NAME)
        _state.update { it.copy(cityName = MOSCOW_NAME) }
    }

    private suspend fun bootstrap() {
        runCatching { applyMoscow() }
        if (BuildConfig.DIRECT_OPENAI_GENERATION) {
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
            _state.update {
                it.copy(
                    ready = true,
                    route = "knock",
                    cityName = MOSCOW_NAME,
                    mascot = if (activeWork == null) shown else shown?.copy(
                        previewUrl = null,
                        previewAnimationUrl = null,
                        status = "BASE_GENERATING",
                    ),
                    creating = activeWork != null,
                    generationLabel = activeWork?.progress?.getString(MascotGenerationWorker.KEY_STAGE)
                        ?.let(::generationStageLabel),
                    busy = false,
                    error = null,
                    context = null,
                    animationPackReady = localPackReady,
                )
            }
            if (activeWork != null) observeGeneration(activeWork.id, shown)
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
                error = if (shown?.status == "FAILED_FINAL") "Не удалось сгенерировать нового героя" else null,
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

    fun summonHero() {
        if (_state.value.creating) return
        val existing = _state.value.mascot
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
        if (BuildConfig.DIRECT_OPENAI_GENERATION) {
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
                            error = if (!BuildConfig.DIRECT_OPENAI_GENERATION && network && existing?.status == "READY") {
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

    private fun enqueueOnDeviceGeneration(previous: MascotDto?, animationsOnly: Boolean) {
        val mascotId = if (animationsOnly) previous?.id ?: return else UUID.randomUUID().toString()
        val request = OneTimeWorkRequestBuilder<MascotGenerationWorker>()
            .setInputData(
                workDataOf(
                    MascotGenerationWorker.KEY_REQUEST_TOKEN to UUID.randomUUID().toString(),
                    MascotGenerationWorker.KEY_MASCOT_ID to mascotId,
                    MascotGenerationWorker.KEY_ANIMATIONS_ONLY to animationsOnly,
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(
            MascotGenerationWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        observeGeneration(request.id, previous)
    }

    private fun observeGeneration(workId: UUID, previous: MascotDto?) {
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).filterNotNull().collectLatest { info ->
                when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.RUNNING -> _state.update {
                        it.copy(
                            creating = true,
                            busy = true,
                            generationLabel = generationStageLabel(
                                info.progress.getString(MascotGenerationWorker.KEY_STAGE),
                            ),
                            error = null,
                            route = "knock",
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val mascotId = info.outputData.getString(MascotGenerationWorker.KEY_MASCOT_ID)
                        val file = mascotId?.let(heroStore::baseFile)
                        if (mascotId == null || file == null) {
                            restoreAfterGenerationFailure(previous, "Герой сгенерирован, но файл не сохранился")
                            return@collectLatest
                        }
                        session.saveAcceptedMascot(mascotId)
                        val mascot = MascotDto(
                            id = mascotId,
                            status = "READY",
                            promptVersion = if (heroStore.animationPackReady(mascotId)) {
                                "pet-generation-v2.5-seedance-2.0-mini-budget"
                            } else {
                                "on-device-gpt-image-2-character-contract-v5"
                            },
                            previewUrl = file.toURI().toString(),
                        )
                        val ctx = localContext(mascotId)
                        _state.update {
                            it.copy(
                                creating = false,
                                busy = false,
                                generationLabel = null,
                                mascot = mascot,
                                name = "",
                                context = ctx,
                                route = "knock",
                                error = null,
                                animationPackReady = heroStore.animationPackReady(mascotId),
                            )
                        }
                        publishAnimationLibrary(mascotId)
                        pushWidget(ctx)
                        return@collectLatest
                    }
                    WorkInfo.State.FAILED,
                    WorkInfo.State.CANCELLED -> {
                        val partialId = info.outputData.getString(MascotGenerationWorker.KEY_PARTIAL_MASCOT_ID)
                        val partial = partialId?.let { id ->
                            heroStore.baseFile(id)?.let { file ->
                                MascotDto(
                                    id = id,
                                    status = "READY",
                                    promptVersion = "on-device-gpt-image-2-character-contract-v5",
                                    previewUrl = file.toURI().toString(),
                                )
                            }
                        }
                        if (partial != null) session.saveAcceptedMascot(partial.id)
                        restoreAfterGenerationFailure(
                            partial ?: previous,
                            info.outputData.getString(MascotGenerationWorker.KEY_ERROR)
                                ?: "Генерация была прервана",
                        )
                        return@collectLatest
                    }
                }
            }
        }
    }

    private fun restoreAfterGenerationFailure(previous: MascotDto?, message: String) {
        _state.update {
            it.copy(
                creating = false,
                busy = false,
                generationLabel = null,
                mascot = previous,
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
        val mascot = _state.value.mascot ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching {
                if (_state.value.name.isNotBlank()) api.rename(mascot.id, NameBody(_state.value.name))
                api.accept(mascot.id)
            }.onSuccess { ready ->
                session.saveAcceptedMascot(ready.id)
                persistAcceptedHero(ready)
            }.onFailure {
                val current = runCatching { api.getMascot(mascot.id) }.getOrNull()
                if (current?.status == "READY") {
                    session.saveAcceptedMascot(current.id)
                    persistAcceptedHero(current)
                } else {
                    _state.update { it.copy(busy = false, route = "knock", error = "Не удалось собрать пакет") }
                }
            }
        }
    }

    fun onOpenedFromWidget() {
        viewModelScope.launch { loadContext(interact = true) }
    }

    fun pollProgress() {
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
            if (!BuildConfig.DIRECT_OPENAI_GENERATION) runCatching { heroStore.sync(api, mascotId) }
            runCatching { updateMascotWidgets(session.appContext) }
        }
    }

    fun refreshQuietly() {
        viewModelScope.launch { loadContext(interact = false) }
    }

    fun openAnimationLibrary() {
        _state.update { it.copy(route = "animations") }
        refreshAnimationLibrary()
    }

    fun completeAnimations() {
        val mascot = _state.value.mascot ?: return
        val animationStage = mascot.stages["animations"]
        if (
            _state.value.creating ||
            _state.value.packBusy ||
            animationStage == "running" ||
            heroStore.animationPackReady(mascot.id)
        ) return
        if (!BuildConfig.DIRECT_OPENAI_GENERATION) {
            _state.update {
                it.copy(
                    route = "animations",
                    packBusy = true,
                    packMessage = "Запускаем 17 анимаций…",
                    error = null,
                    mascot = mascot.copy(
                        stages = mascot.stages + ("animations" to "running"),
                    ),
                )
            }
            viewModelScope.launch {
                runCatching { api.generateVideos(mascot.id, ANIMATION_PACK_SIZE) }
                    .onSuccess { result ->
                        MediaRefreshWorker.enqueue(
                            session.appContext,
                            mascot.id,
                            "video",
                            (result.requested + result.queued).distinct(),
                        )
                        val ready = result.ready.distinct().size
                        val stage = if (result.queued.isEmpty() && result.blocked.isEmpty()) "ready" else "running"
                        _state.update {
                            it.copy(
                                route = "animations",
                                packBusy = false,
                                packMessage = if (stage == "ready") {
                                    "Все $ANIMATION_PACK_SIZE анимаций готовы"
                                } else {
                                    "Генерация идёт: готово $ready из $ANIMATION_PACK_SIZE"
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
                        _state.update {
                            it.copy(
                                route = "animations",
                                packBusy = false,
                                packMessage = "Не удалось проверить запуск: ${error.message ?: "нет связи с сервером"}",
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
                route = "knock",
                creating = true,
                busy = true,
                generationLabel = "Seedance Mini создаёт 17 живых анимаций…",
                error = null,
                mascot = mascot.copy(
                    previewUrl = null,
                    previewAnimationUrl = null,
                    status = "BASE_GENERATING",
                ),
            )
        }
        if (BuildConfig.DIRECT_OPENAI_GENERATION) {
            enqueueOnDeviceGeneration(mascot, animationsOnly = true)
            return
        }
    }

    fun refreshHeroLibrary() {
        viewModelScope.launch {
            val activeId = session.acceptedMascotId() ?: session.mascotId()
            fun localItems(ids: List<String>, names: Map<String, MascotDto>): List<HeroLibraryItem> =
                ids.distinct().mapNotNull { id ->
                    val base = heroStore.baseFile(id)
                    val frames = heroStore.sequenceFiles(id, "base")
                    if (base == null && frames.isEmpty()) return@mapNotNull null
                    HeroLibraryItem(
                        id = id,
                        name = names[id]?.name ?: heroStore.mascotName(id),
                        baseFrames = frames.map { it.toURI().toString() },
                        baseStill = base?.toURI()?.toString(),
                        active = id == activeId,
                    )
                }

            // Show cached heroes instantly; remote refresh must never leave the
            // picker on an empty loading screen.
            _state.update { it.copy(heroLibrary = localItems(heroStore.localMascotIds(), emptyMap())) }
            if (BuildConfig.DIRECT_OPENAI_GENERATION) return@launch
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
        if (_state.value.mascot?.id == mascotId) return
        val selectionToken = ++heroSelectionSeq
        val libraryItem = _state.value.heroLibrary.firstOrNull { it.id == mascotId }
        viewModelScope.launch {
            session.saveAcceptedMascot(mascotId)
            val ctx = localContext(mascotId)
            if (selectionToken != heroSelectionSeq) return@launch
            val localSelected = MascotDto(
                id = mascotId,
                name = libraryItem?.name,
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
                    context = ctx,
                    animationPackReady = heroStore.animationPackReady(mascotId),
                    route = "knock",
                    heroLibrary = it.heroLibrary.map { hero -> hero.copy(active = hero.id == mascotId) },
                )
            }
            publishAnimationLibrary(mascotId)
            if (BuildConfig.DIRECT_OPENAI_GENERATION) return@launch

            val remoteSelected = runCatching { api.activate(mascotId) }.getOrNull()
                ?: runCatching { api.getMascot(mascotId) }.getOrNull()
            if (selectionToken != heroSelectionSeq) return@launch
            remoteSelected?.let { selected ->
                _state.update {
                    it.copy(
                        mascot = selected.copy(status = "READY"),
                        name = selected.name ?: it.name,
                    )
                }
            }
            runCatching { heroStore.sync(api, mascotId) }
        }
    }

    fun closeAnimationLibrary() = _state.update { it.copy(route = "knock") }

    fun refreshAnimationLibrary() {
        viewModelScope.launch {
            val mascotId = session.acceptedMascotId() ?: session.mascotId() ?: return@launch
            publishAnimationLibrary(mascotId)
            if (!BuildConfig.DIRECT_OPENAI_GENERATION) {
                runCatching { api.getMascot(mascotId) }.getOrNull()?.let { remote ->
                    _state.update { current ->
                        if (current.mascot?.id == mascotId) current.copy(mascot = remote) else current
                    }
                }
                runCatching { heroStore.sync(api, mascotId) }
            }
            publishAnimationLibrary(mascotId)
        }
    }

    private fun publishAnimationLibrary(mascotId: String) {
        val library = ANIMATION_STATE_KEYS.associateWith { stateKey ->
            heroStore.playbackSequenceFiles(mascotId, stateKey).map { it.toURI().toString() }
        }
        _state.update {
            it.copy(
                animationLibrary = library,
                animationPackReady = heroStore.animationPackReady(mascotId),
            )
        }
    }

    private suspend fun loadContext(interact: Boolean): ContextDto? {
        val remote = if (BuildConfig.DIRECT_OPENAI_GENERATION) null
            else runCatching { api.context(interact) }.getOrNull()
        val rawContext = remote ?: run {
            val mascotId = session.acceptedMascotId() ?: return null
            localContext(mascotId)
        }
        val mascotId = session.acceptedMascotId() ?: session.mascotId()
        if (!BuildConfig.DIRECT_OPENAI_GENERATION && mascotId != null) {
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
        session.saveCurrentState(ctx.stateKey)
        return ctx.also {
        _state.update { it.copy(context = ctx) }
        pushWidget(ctx)
        if (!BuildConfig.DIRECT_OPENAI_GENERATION && ctx.playbackMode == "clip") {
            viewModelScope.launch {
                delay(ctx.motionMs.toLong() + 400)
                runCatching { api.context(false) }.getOrNull()?.let { next ->
                    _state.update { it.copy(context = next) }
                    pushWidget(next)
                }
            }
        }
    }
    }

    private suspend fun localContext(mascotId: String): ContextDto {
        val stateKey = LocalContextResolver().resolve()
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

    private suspend fun pushWidget(ctx: ContextDto) {
        session.saveCurrentState(ctx.stateKey)
        val mascotId = session.acceptedMascotId() ?: session.mascotId()
        if (!BuildConfig.DIRECT_OPENAI_GENERATION && mascotId != null) {
            runCatching { heroStore.sync(api, mascotId) }
        }
        val key = "${ctx.stateKey}|${ctx.stillUrl}|${ctx.animationFrames.size}"
        if (key == lastWidgetPush) return
        lastWidgetPush = key
        runCatching { updateMascotWidgets(session.appContext) }
    }
    fun openSettings() = _state.update { it.copy(route = "settings") }
    fun backToHero() = _state.update { it.copy(route = "knock") }

    fun saveName() {
        saveName(returnHome = true)
    }

    fun saveNameInPlace() {
        saveName(returnHome = false)
    }

    private fun saveName(returnHome: Boolean) {
        val mascot = _state.value.mascot ?: return
        viewModelScope.launch {
            runCatching { api.rename(mascot.id, NameBody(_state.value.name)) }
                .onSuccess { updated ->
                    _state.update {
                        it.copy(
                            mascot = updated,
                            name = updated.name.orEmpty(),
                            route = if (returnHome) "knock" else it.route,
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
            runCatching { api.deleteAccount() }
            session.clear()
            _state.update { AppUiState(ready = true, route = "knock") }
        }
    }

    companion object {
        fun routeFor(status: String) = when (status) {
            "AWAITING_ACCEPTANCE" -> "knock"
            "READY" -> "knock"
            "FAILED_FINAL" -> "knock"
            else -> "knock"
        }

        fun factory(api: MascotApi, session: SessionStore, heroStore: HeroLocalStore) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(api, session, heroStore) as T
        }
    }
}
