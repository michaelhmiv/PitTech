package com.pittech

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pittech.data.CookRepository
import com.pittech.data.CookWithDishes
import com.pittech.data.CookReminderEntity
import com.pittech.data.BackupPreferences
import com.pittech.data.InsightsSnapshot
import com.pittech.data.PitTechDataTransfer
import com.pittech.data.selectCookPackRecipes
import com.pittech.data.recordedCookOnly
import com.pittech.data.TimelineEventEntity
import com.pittech.data.SensorReadingEntity
import com.pittech.data.TargetEntity
import com.pittech.data.DishEntity
import com.pittech.domain.CookInputs
import com.pittech.domain.DishDraft
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CooksViewModel(
    private val repository: CookRepository,
    private val dataTransfer: PitTechDataTransfer,
    private val context: Context,
) : ViewModel() {
    private suspend fun previewPublicCookPack(file: File, cook: Boolean, id: String, revision: String) {
        val prepared = withContext(Dispatchers.IO) { file.inputStream().buffered().use(dataTransfer::previewCookPack) }
        val source = prepared.cookPack?.optJSONObject("source")
        require(source != null && source.optString("itemId") == id && source.optString("revisionId") == revision) { "This download doesn't match the recipe preview." }
        previewPublicItemId = id
        _importHistoryAdded.value = false
        _importPreview.value = prepared.copy(publicCookOnSave = cook)
    }
    internal var publicDiscovery = com.pittech.data.PublicManager(context, viewModelScope, onPreview = ::previewPublicCookPack)
        private set
    internal fun installPublicTestAPI(api: com.pittech.data.PublicAPI) {
        check(BuildConfig.DEBUG)
        publicDiscovery = com.pittech.data.PublicManager(context, viewModelScope, onPreview = ::previewPublicCookPack, api = api, enabled = true,
            cache = com.pittech.data.PublicCache(context, "test-" + java.util.UUID.randomUUID().toString()))
    }
    internal val communityControls = com.pittech.data.CommunityControls(com.pittech.data.CommunityVault(context),viewModelScope)
    internal val account: com.pittech.data.AccountManager = com.pittech.data.AccountManager(com.pittech.data.AccountVault(context), viewModelScope,cleanup={ user ->
        com.pittech.data.PublicationOperationsStore(context).purgeAccount(user)
        com.pittech.data.CommunityVault(context).purgeAccount(user)
        com.pittech.data.PrivateSyncStore(context).purgeAccount(user)
        communityControls.reload();publicDiscovery.controlsChanged()
    },removeJournal={
        com.pittech.data.LocalJournalRemoval(app.database,com.pittech.data.PhotoStorage(context)).remove { detachSyncForJournal() }
        // The open detail would otherwise stick on "Opening cook…" for a deleted row.
        closeCook()
    })
    internal val mcpConnections = com.pittech.data.McpConnectionsManager(account, viewModelScope)
    internal val mcpDeviceActions = com.pittech.data.McpDeviceActionsManager(
        account, viewModelScope,
        com.pittech.data.McpDeviceStore(context),
        com.pittech.data.DeviceActionDispatcher(com.pittech.data.NativeDeviceActionPorts(context, app.database)),
    )
    internal val subscription = com.pittech.billing.SubscriptionManager(
        com.pittech.billing.PlayProBillingClient(context,com.pittech.billing.ProProductConfiguration.productId),
        viewModelScope,
        session = { if(account.presentation.value.signedIn) runCatching { account.access() }.getOrNull() else null },
        accountsEnabled = account.enabled,
        ownerId = { if(account.enabled) account.publicationUserId() else null },
        cacheStore = com.pittech.billing.SharedPrefsSubscriptionCacheStore(context))
    internal val communityBenchmarks = com.pittech.billing.CommunityBenchmarksLoader(
        session = { if(account.presentation.value.signedIn) runCatching { account.access() }.getOrNull() else null },
        onAuthMismatch = { subscription.refresh() })
    internal val proCustomization = com.pittech.domain.ProCustomizationStore(
        com.pittech.domain.SharedPrefsCustomizationBacking(context),
        com.pittech.domain.PackageManagerProIconController(context))
    internal val sync: com.pittech.data.PrivateSyncCoordinator = com.pittech.data.PrivateSyncCoordinator(com.pittech.data.PrivateSyncStore(context), com.pittech.data.AccountPublicationSession(account), viewModelScope,
        library = com.pittech.data.PrivateSyncRoomLibrary(app.database, repository, com.pittech.data.PhotoStorage(context), context),
        projection = com.pittech.data.PrivateSyncProjection(context.assets.open("private-sync-projection-v1.json").bufferedReader().use { it.readText() }))
    private suspend fun detachSyncForJournal() { sync.detachLibrary() }
    internal val publicationWriter = com.pittech.data.PublicationWriter(com.pittech.data.AccountPublicationSession(account), com.pittech.data.PublicationOperationsStore(context))
    internal val recipes get() = repository.recipes
    internal val sharing get() = repository.sharing

    /**
     * Flowing §4.3: the provenance payload for a recipe revision, assembled
     * from the source cook's scoped archive. Null when the revision has no
     * source cook or the cook has left the library — the honest-absence
     * case the recipe screen renders around.
     */
    internal suspend fun recipeProvenance(sourceCookId: String?, sourceDishId: String?): com.pittech.domain.RecipeProvenance? {
        if (sourceCookId == null || sourceDishId == null) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val archive = com.pittech.data.provenanceReadyArchive(com.pittech.data.archiveSnapshotJson(repository.exportSnapshot(sourceCookId), null))
                com.pittech.domain.RecipeProvenance.fromArchive(archive, sourceCookId, sourceDishId)
            }.getOrNull()
        }
    }

    /**
     * Flowing 3.4: the library's recipes as plan-seed options — each
     * recipe's current snapshot plus its §4.3 provenance payload when it
     * has a source cook. Recipes without recorded durations stay on the
     * list so the picker can say why they can't seed a lane.
     */
    internal suspend fun planSeedOptions(): List<com.pittech.domain.PlanSeedOption> = withContext(Dispatchers.IO) {
        val recipes = repository.recipes.observeRecipes().first().filter { it.archivedAtUtcMillis == null }
        val revisions = repository.recipes.observeRevisions().first().associateBy { it.id }
        recipes.mapNotNull { recipe ->
            val revision = revisions[recipe.currentRevisionId] ?: return@mapNotNull null
            val snapshot = runCatching { com.pittech.domain.RecipeSnapshotCodec.decode(revision.snapshotJson) }.getOrNull() ?: return@mapNotNull null
            val provenance = recipeProvenance(revision.sourceCookId, revision.sourceDishId)
            com.pittech.domain.PlanSeedOption(
                recipeId = recipe.id,
                recipeName = recipe.name,
                snapshot = snapshot,
                provenance = provenance,
                seeded = com.pittech.domain.PlanSeeding.fromRecipe(snapshot, provenance),
            )
        }
    }

    /** One-shot cook detail for surfaces that visit a cook without opening it (the recipe story's full chart and photos). */
    internal suspend fun cookDetailOnce(cookId: String): com.pittech.data.CookDetailData? =
        repository.observeCookDetail(cookId).first()
    internal val relay = com.pittech.data.RelayManager(context, viewModelScope, prepare = { id, history, file ->
        val before = sharing.get(id) ?: error("Draft unavailable.")
        file.outputStream().buffered().use { dataTransfer.writeCookPack(it, id, history) }
        check(sharing.get(id)?.updatedAtUtcMillis == before.updatedAtUtcMillis) { "This draft changed while preparing. Review it and try again." }
        before.updatedAtUtcMillis to before.title
    }, preview = { file ->
        val prepared = file.inputStream().buffered().use(dataTransfer::previewImport)
        require(prepared.cookPack != null) { "This download is not a supported Cook Pack." }
        previewPublicItemId = null
        _importHistoryAdded.value = false
        _importPreview.value = prepared
    })
    internal val guide get() = repository.guide
    internal fun saveDishVerdict(cookId: String, dishId: String, rating: Double?, again: Boolean?, notes: String, onSaved: (Boolean) -> Unit) = perform({ repository.saveDishVerdict(cookId, dishId, rating, again, notes) }, onSaved)
    internal suspend fun recipeExtraction(cookId: String, dishId: String, contextIds: Set<String> = emptySet()): com.pittech.data.RecipeExtractionDraft {
        val recorded = com.pittech.data.RecipeExtraction.fromMeal(repository.observeCookDetail(cookId).first() ?: error("Meal unavailable."), dishId, contextIds)
        val working = repository.recipeAttempt(dishId)
        return if (working == null) recorded else recorded.copy(snapshot = com.pittech.data.RecipeExtraction.mergeAttempt(working, recorded.snapshot))
    }
    internal suspend fun addRecipeToMeal(cookId: String, revisionId: String) = repository.addRecipeToMeal(cookId, revisionId)
    internal suspend fun updateRecipeAttempt(dishId: String, snapshot: com.pittech.domain.RecipeSnapshot) = repository.updateRecipeAttempt(dishId, snapshot)
    internal suspend fun recipeAttempt(dishId: String) = repository.recipeAttempt(dishId)
    internal suspend fun saveMealPreset(cookId: String, name: String) = repository.saveMealPreset(cookId, name)
    internal fun refreshReminderRules() { viewModelScope.launch { app.reminderRepository.reconcileAll(context) } }
    internal val reminders get() = app.reminderRepository
    internal suspend fun saveReminderRule(cookId: String, dishId: String?, title: String, trigger: com.pittech.domain.ReminderTrigger, id: String? = null, revisionId: String? = null, milestoneId: String? = null) = app.reminderRepository.save(cookId, dishId, title, trigger, id ?: java.util.UUID.randomUUID().toString(), revisionId, milestoneId).also { app.reminderRepository.cancelDisplayed(context, it); app.reminderRepository.reconcile(context, cookId) }
    internal suspend fun changeReminderRule(id: String, action: String) { app.reminderRepository.change(context, id, action); app.reminderRepository.reconcileAll(context) }
    internal suspend fun confirmReminderAction(id: String, action: String) = app.reminderRepository.confirmAction(context, id, action, repository)
    internal val activities get() = repository.activities
    private val app get() = context.applicationContext as PitTechApplication
    internal val telemetry get() = app.telemetryRuntime
    internal val grillState get() = app.grillMonitor.state
    internal val recordingServiceRunning get() = app.recordingServiceRunning

    internal fun configureGrillSampling(policy: com.pittech.devices.GrillSamplingPolicy) = app.grillMonitor.configureSampling(policy)

    internal fun configureCookSampling(cookId: String, policy: com.pittech.devices.GrillSamplingPolicy) = perform {
        app.recordingRepository.configureSampling(cookId, policy)
        reconcileRecording()
    }

    private suspend fun reconcileRecording() {
        if (BuildConfig.TELEMETRY_BETA_ENABLED) app.telemetryRuntime.requestBackgroundFromVisibleApp()
        CookRecordingRecovery.reconcile(context)
    }

    /** Log creation may fail independently of the optional grill snapshot. Never prevent saving the log. */
    internal suspend fun snapshotForCookLog(cookId: String, force: Boolean = false) {
        if (BuildConfig.TELEMETRY_BETA_ENABLED) viewModelScope.launch(Dispatchers.IO) { app.telemetryRuntime.onLog(cookId, force) }
        if (!BuildConfig.CONTROLLER_TESTING_ENABLED) return
        val recording = app.database.recordingDao().getRecording(cookId) ?: return
        if (recording.status != com.pittech.data.CookRecordingEntity.RECORDING ||
            (!force && recording.samplingMode != com.pittech.devices.GrillSamplingMode.ON_LOG.key)) return
        val state = app.grillMonitor.state.value
        if (state.phase == com.pittech.devices.PolarisPhase.RESTORING || state.phase == com.pittech.devices.PolarisPhase.DISCOVERING) {
            app.recordingRepository.markGap(cookId, "The grill connection is opening. This entry can still be saved.")
            return
        }
        if (!state.authenticated || state.selectedDeviceId?.let(com.pittech.devices.CookTelemetryPolicy::deviceKey) != recording.controllerKey) {
            app.recordingRepository.pause(cookId, "Reconnect the attached grill in Devices, then resume recording to include temperatures with new logs.")
            reconcileRecording()
            return
        }
        val recent = state.latest?.takeIf { !force && !state.readingsAreOld(System.currentTimeMillis()) && System.currentTimeMillis() - it.fetchedAtMillis <= 15_000L && it.fetchedAtMillis >= recording.resumedAtUtcMillis }
        try {
            val sample = recent ?: app.grillMonitor.snapshot()
            if (!app.grillMonitor.state.value.authenticated) {
                app.recordingRepository.pause(cookId, "Reconnect the attached grill in Devices, then resume recording.")
                reconcileRecording()
            } else if (sample == null) app.recordingRepository.markGap(cookId, "This log's grill reading was unavailable. Your entry can still be saved.")
            else app.recordingRepository.ingest(cookId, recording.controllerKey, sample, app.grillMonitor.state.value)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { app.recordingRepository.markGap(cookId, "This log's grill reading was unavailable. Your entry can still be saved.") }
    }

    internal fun readGrillNow(cookId: String) = perform { snapshotForCookLog(cookId, force = true) }

    internal fun setAppForeground(active: Boolean) {
        if (BuildConfig.TELEMETRY_BETA_ENABLED) { app.telemetryRuntime.foreground.value = active; if (active) app.telemetryRuntime.requestBackgroundFromVisibleApp() }
        if (!BuildConfig.CONTROLLER_TESTING_ENABLED) return
        app.grillMonitor.setForeground(active)
        if (active) viewModelScope.launch {
            if (app.database.recordingDao().getActiveRecording() != null) {
                try { reconcileRecording() }
                catch (_: Exception) { app.database.recordingDao().getActiveRecording()?.let { app.recordingRepository.pause(it.cookId, "Android could not resume recording. Tap Resume recording.") } }
            }
        }
    }

    fun attachGrill(cookId: String, unit: String) = perform { attachSelectedGrill(cookId, unit) }

    private suspend fun attachSelectedGrill(cookId: String, unit: String, probeDishes: Map<String, String?> = emptyMap()) {
        val state = app.grillMonitor.state.value
        require(state.authenticated && state.sessionSaved) { "Connect your grill in Devices before attaching a grill." }
        val device = state.selectedDevice ?: error("Choose a grill in Devices first.")
        app.recordingRepository.attach(cookId, device, unit, probeDishes, sampling = state.sampling)
        app.grillMonitor.lockDevice(device.id)
        try { reconcileRecording() }
        catch (_: Exception) {
            app.grillMonitor.setRecording(false)
            app.recordingRepository.pause(cookId, "Android could not start recording. Open PitTech and tap Resume recording.")
            error("Cook saved, but Android could not start recording. Tap Resume recording to try again.")
        }
        _notice.value = "Grill attached. Temperatures will be saved with this cook."
    }

    fun resumeGrillRecording(cookId: String) = perform {
        require(app.grillMonitor.state.value.authenticated && app.grillMonitor.state.value.sessionSaved) { "Connect your grill in Devices before resuming recording." }
        val saved = app.database.recordingDao().getRecording(cookId) ?: error("Attach a grill first.")
        val device = app.grillMonitor.state.value.devices.firstOrNull { com.pittech.devices.CookTelemetryPolicy.deviceKey(it.id) == saved.controllerKey }
            ?: error("Sign in and find the attached grill in Devices first.")
        app.grillMonitor.selectDevice(device.id)
        app.recordingRepository.attach(cookId, device, saved.unit)
        app.grillMonitor.lockDevice(device.id)
        try { reconcileRecording() } catch (_: Exception) {
            app.grillMonitor.setRecording(false)
            app.recordingRepository.pause(cookId, "Android could not resume recording.")
            error("Android could not resume recording. Keep PitTech open and try again.")
        }
    }

    fun pauseGrillRecording(cookId: String) = perform { app.recordingRepository.pause(cookId); reconcileRecording() }
    fun stopGrillRecording(cookId: String) = perform { app.recordingRepository.stop(cookId); reconcileRecording() }
    fun assignGrillProbe(cookId: String, probeId: String, dishId: String?, movedAt: Long? = null, onFinished: ((Boolean) -> Unit)? = null) = perform({
        val now = System.currentTimeMillis()
        app.recordingRepository.assignProbe(probeId, cookId, dishId, now, movedAt ?: now)
    }, onFinished)
    private val syncChanges = object : androidx.room.InvalidationTracker.Observer("cooks", "dishes", "ingredients", "timeline_events", "devices", "probes", "sensor_readings", "targets", "cook_results", "photos", "saved_recipes", "recipe_revisions", "recipe_assets", "meal_presets", "dish_recipe_sources", "companion_records", "companion_photos", "log_reading_links", "cook_lifecycle", "target_history", "reminder_rules") {
        override fun onInvalidated(tables: Set<String>) { viewModelScope.launch { sync.requestAutomaticSync() } }
    }
    init {
        app.database.invalidationTracker.addObserver(syncChanges)
        viewModelScope.launch(Dispatchers.IO) { runCatching { pruneStaleShareArchives() } }
    }

    override fun onCleared() {
        app.database.invalidationTracker.removeObserver(syncChanges)
        sync.pauseAutomaticSync()
        super.onCleared()
    }

    private val _equipmentSummaries = MutableStateFlow<List<com.pittech.data.EquipmentSummary>>(emptyList())
    val equipmentSummaries = _equipmentSummaries.asStateFlow()
    fun refreshEquipment() = perform { _equipmentSummaries.value = app.preparationRepository.summaries() }
    fun saveEquipment(profile: com.pittech.domain.EquipmentProfile, id: String?, onFinished: (Boolean) -> Unit) = perform({ app.preparationRepository.saveEquipment(profile, id); _equipmentSummaries.value = app.preparationRepository.summaries() }, onFinished)
    fun recordEquipmentFuel(entry: com.pittech.domain.FuelEntry, onFinished: (Boolean) -> Unit) = perform({ app.preparationRepository.addFuel(entry); _equipmentSummaries.value = app.preparationRepository.summaries() }, onFinished)
    fun completeMaintenance(id: String) = perform { app.preparationRepository.maintained(id); _equipmentSummaries.value = app.preparationRepository.summaries() }
    fun createCookChecklist(cookId: String) = perform { app.preparationRepository.createChecklist(cookId) }
    fun saveCookChecklist(cookId: String, items: List<com.pittech.domain.ChecklistItem>, onFinished: (Boolean) -> Unit) = perform({ app.preparationRepository.saveChecklist(cookId, items) }, onFinished)
    fun checkPrepItem(cookId: String, itemId: String, done: Boolean) = perform { app.preparationRepository.check(cookId, itemId, done) }

    fun toggleCookFavorite(cookId: String, favorite: Boolean) = perform { repository.setFavorite(cookId, favorite) }
    fun saveCookLearning(cookId: String, dishId: String?, keep: String, change: String) = perform { repository.saveLearning(cookId, dishId, keep, change) }

    /** Flowing §4.2: the composer's one-tap capture writes the action's
     * inputs through the same results model as the editor. */
    fun saveCookInputs(cookId: String, dishId: String?, inputs: CookInputs) = perform { repository.saveCookInputs(cookId, dishId, inputs) }
    fun saveCookTags(cookId: String, method: String, tags: String) = perform { repository.saveCookTags(cookId, method, tags) }
    fun saveCookReference(cookId: String, reference: com.pittech.domain.CookReference) = perform { app.companionRepository.saveReference(cookId, reference) }

    private val _servePlanEditor = MutableStateFlow<com.pittech.domain.ServePlan?>(null)
    val servePlanEditor = _servePlanEditor.asStateFlow()
    private val _durationEvidence = MutableStateFlow<List<com.pittech.domain.DurationEvidence>>(emptyList())
    val durationEvidence = _durationEvidence.asStateFlow()
    private var editingServePlanId: String? = null
    fun planServeTime(playbookId: String? = null, scheduledId: String? = null) = perform {
        _durationEvidence.value = app.servePlanRepository.evidence()
        editingServePlanId = scheduledId
        val saved = scheduledId?.let { app.companionRepository.get(it) }
        if (saved != null) _servePlanEditor.value = com.pittech.domain.ServeTimePlanner.decode(saved.payload)
        else {
            val book = playbookId?.let { app.companionRepository.get(it)?.let { r -> com.pittech.domain.PlaybookCodec.decode(r.payload) } }
                ?: com.pittech.domain.CookPlaybook("Planned cook", NewCookDraft("Planned cook", dishes = listOf(DishDraft("Main dish", "Beef"))), emptyList())
            _servePlanEditor.value = com.pittech.domain.ServePlan(book, System.currentTimeMillis() + 86_400_000, java.time.ZoneId.systemDefault().id,
                schedules = book.draft.dishes.mapIndexed { i, _ -> com.pittech.domain.DishSchedule(i, evidenceNote = "Enter your duration range", pitF = book.draft.setpointText.toDoubleOrNull()?.let { com.pittech.domain.CookPlanEngine.fahrenheit(it, book.draft.setpointUnit) }) })
        }
    }
    fun closeServePlan() { _servePlanEditor.value = null }
    fun saveServePlan(plan: com.pittech.domain.ServePlan) = perform {
        val id = app.servePlanRepository.save(plan, editingServePlanId)
        if (_setupPreview.value?.scheduledPlanId == id) _setupPreview.value = plan.book.draft.copy(scheduledPlanId = id)
        _servePlanEditor.value = null; _notice.value = "Plan saved — not started. Recording starts only when you start the cook."
    }
    fun previewScheduledCook(id: String) = perform {
        val plan = com.pittech.domain.ServeTimePlanner.decode(app.companionRepository.get(id)?.payload ?: error("Scheduled cook not found."))
        _previewPlaybook.value = null
        _setupPreview.value = plan.book.draft.copy(scheduledPlanId = id)
        _selectedCookId.value = null
    }
    fun deleteScheduledCook(id: String) = perform { app.companionRepository.delete(id) }

    fun saveCookAlert(cookId: String, title: String, rule: com.pittech.domain.CookAlertRule, useTimedMonitoring: Boolean = false, onFinished: (Boolean) -> Unit = {}) = perform({
        if (useTimedMonitoring) {
            val current = app.database.recordingDao().getRecording(cookId) ?: error("Attach a grill before enabling background monitoring.")
            app.recordingRepository.configureSampling(cookId, com.pittech.devices.GrillSamplingPolicy(com.pittech.devices.GrillSamplingMode.PERIODIC, current.samplingIntervalMillis))
            reconcileRecording()
        }
        app.alertRepository.save(cookId, title, rule)
        _notice.value = "Alert saved. Manual entries are evaluated when you log them."
    }, onFinished)
    fun changeCookAlert(id: String, action: String) = perform {
        app.alertRepository.change(id, action)
        com.pittech.CookAlertNotifications.cancel(context, id)
    }

    /** Flowing Slice 2 (1.5): the board's pit-target stepper — app-side target history only. */
    fun setPitTarget(cookId: String, value: Double, unit: String) = perform {
        repository.setPitTarget(cookId, value, unit)
        _notice.value = "Pit target set to ${value.toInt()} $unit."
    }

    /** Flowing Slice 2 (1.5): one-tap "conditions changed" — a plain note event on the timeline. */
    fun logConditionsChanged(cookId: String) = saveNote(
        com.pittech.domain.CookNoteDraft(cookId = cookId, title = "Conditions changed"),
        editing = false,
    ) { }

    /** Flowing Slice 2 (E3): the overnight panel's test alert — posted on the real alert channel. */
    fun fireOvernightTestAlert(cookId: String, cookTitle: String) {
        com.pittech.CookAlertNotifications.showTest(context, cookId, cookTitle)
        _notice.value = "Test alert sent — check your notifications."
    }

    fun beginGuidance(cookId: String) = perform { app.companionRepository.beginPlan(cookId) }
    fun setPreciseCookTimers(cookId: String, enabled: Boolean) = perform {
        context.getSharedPreferences("pittech-preferences", 0).edit().putBoolean("precise-cook-timers", enabled).apply()
        app.companionRepository.reconcile(context, cookId)
    }
    fun updateGuidanceStep(cookId: String, step: com.pittech.domain.PlaybookStep) = perform { app.companionRepository.updateStep(cookId, step) }
    fun pauseGuidance(cookId: String, paused: Boolean) = perform { app.companionRepository.setPaused(cookId, paused) }
    fun completeGuidanceStep(cookId: String, step: com.pittech.domain.EvaluatedStep, rest: Boolean = false) = perform {
        app.companionRepository.completeStep(cookId, step.step.id, step.progress.occurrence)
        if (rest && step.dishId != null) app.companionRepository.stageAction(cookId, step.dishId, "rest_start")
    }
    fun snoozeGuidance(cookId: String, step: com.pittech.domain.EvaluatedStep) = perform { app.companionRepository.snooze(cookId, step.step.id, step.progress.occurrence, System.currentTimeMillis() + 600_000) }
    fun skipGuidance(cookId: String, stepId: String, anchor: Boolean) = perform { app.companionRepository.skip(cookId, stepId, anchor) }
    fun changeDishStage(cookId: String, dishId: String, action: String) = perform { app.companionRepository.stageAction(cookId, dishId, action) }

    val companionRecords = app.companionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _playbookEditor = MutableStateFlow<com.pittech.domain.CookPlaybook?>(null)
    val playbookEditor = _playbookEditor.asStateFlow()
    private val _setupPreview = MutableStateFlow<NewCookDraft?>(null)
    val setupPreview = _setupPreview.asStateFlow()
    private var editingPlaybookId: String? = null
    fun preparePlaybook(cookId: String) = perform {
        editingPlaybookId = null
        _playbookEditor.value = app.companionRepository.suggest(cookId)
    }
    fun newPlaybook() {
        editingPlaybookId = null
        _playbookEditor.value = com.pittech.domain.CookPlaybook("My playbook", NewCookDraft("My cook", dishes = listOf(DishDraft("Brisket", "Beef", "Brisket"))), emptyList())
    }
    fun editPlaybook(id: String) = perform {
        editingPlaybookId = id
        _playbookEditor.value = app.companionRepository.get(id)?.let { com.pittech.domain.PlaybookCodec.decode(it.payload) }
    }
    fun closePlaybookEditor() { _playbookEditor.value = null; adoptPreviewPlaybook = false }
    fun savePlaybook(book: com.pittech.domain.CookPlaybook, photos: Boolean) = perform {
        val newId = app.companionRepository.save(book, editingPlaybookId, photos)
        if (adoptPreviewPlaybook) {
            // PL-10: the user explicitly saved the cook-again preview's steps
            // from the editor — the reviewed setup now follows the saved book.
            adoptPreviewPlaybook = false
            _previewPlaybook.value = null
            _setupPreview.value = book.draft.copy(playbookId = newId)
        } else if (_setupPreview.value?.playbookId == editingPlaybookId && editingPlaybookId != null) {
            _setupPreview.value = book.draft.copy(playbookId = newId)
        }
        _playbookEditor.value = null
        _notice.value = "Playbook saved on this phone."
    }
    fun previewPlaybook(id: String) = perform {
        val book = app.companionRepository.get(id)?.let { com.pittech.domain.PlaybookCodec.decode(it.payload) } ?: error("Playbook not found.")
        _previewPlaybook.value = null
        _setupPreview.value = book.draft.copy(title = book.name, playbookId = id)
        _selectedCookId.value = null
    }

    /** PL-10 (#172): the playbook behind the open Start Cook review when it
     * exists only as a preview (cook-again). It is deliberately NOT saved to
     * the library: starting the cook attaches its steps as a snapshot, and
     * it is saved only if the user edits the steps and saves from the
     * playbook editor. */
    private val _previewPlaybook = MutableStateFlow<com.pittech.domain.CookPlaybook?>(null)
    val previewPlaybook: StateFlow<com.pittech.domain.CookPlaybook?> = _previewPlaybook.asStateFlow()
    private var adoptPreviewPlaybook = false
    fun previewCookAgain(cookId: String) = perform {
        val book = app.companionRepository.suggest(cookId)
        _previewPlaybook.value = book
        _setupPreview.value = book.draft.copy(playbookId = null, sourceLabel = "Cook again · copied from ${book.name}")
        _selectedCookId.value = null
    }
    /** PL-10: "Adjust playbook steps" on a cook-again review opens the editor
     * on the transient book; saving there is the explicit save action. */
    fun editPreviewPlaybook() {
        val book = _previewPlaybook.value ?: return
        editingPlaybookId = null
        adoptPreviewPlaybook = true
        _playbookEditor.value = book
    }

    /** PL-10 (#172): the Recipes library start lands on the same Start Cook
     * review as every other start path — no cook exists until the review's
     * "Begin preparation" is tapped. */
    fun previewRecipeMeal(title: String, revisionIds: List<String>) = perform {
        _previewPlaybook.value = null
        _setupPreview.value = repository.recipeStartDraft(
            title, revisionIds,
            sourceLabel = "From your Recipes · saved recipes stay attached to their dishes",
        )
        _selectedCookId.value = null
    }

    /** PL-10 (#172): repeating a saved meal combination lands on the same
     * Start Cook review; the preset's working copies ride along in the
     * draft and are applied when the cook starts. */
    fun previewMealPreset(id: String) = perform {
        _previewPlaybook.value = null
        _setupPreview.value = repository.presetStartDraft(id)
        _selectedCookId.value = null
    }
    fun consumeSetupPreview() {
        _setupPreview.value = null
        // The transient cook-again book belongs to the open review only.
        _previewPlaybook.value = null
    }

    // PL-13 (#172): the Start Cook setup kept on this device when a setup is
    // left mid-edit. Meals offers "Resume setup" until the draft is discarded
    // or a cook is started from it. Device-local only — never synced.
    private val setupDraftKey = "start-cook-setup-draft"
    private val setupDraftPlaybookKey = "start-cook-setup-draft-playbook"
    private val _setupDraft = MutableStateFlow<NewCookDraft?>(loadSetupDraft())
    val setupDraft: StateFlow<NewCookDraft?> = _setupDraft.asStateFlow()
    private fun loadSetupDraft(): NewCookDraft? = runCatching {
        context.getSharedPreferences("pittech-preferences", 0).getString(setupDraftKey, null)
            ?.let { com.pittech.domain.SetupDraftCodec.decode(it) }
    }.getOrNull()
    fun keepSetupDraft(draft: NewCookDraft) {
        _setupDraft.value = draft
        val editor = context.getSharedPreferences("pittech-preferences", 0).edit()
            .putString(setupDraftKey, com.pittech.domain.SetupDraftCodec.encode(draft))
        // A cook-again preview's unsaved steps are part of the setup; keep
        // them beside the draft so Resume restores the same review.
        val book = _previewPlaybook.value
        if (book != null) editor.putString(setupDraftPlaybookKey, com.pittech.domain.PlaybookCodec.encode(book))
        else editor.remove(setupDraftPlaybookKey)
        editor.apply()
    }
    fun discardSetupDraft() {
        _setupDraft.value = null
        context.getSharedPreferences("pittech-preferences", 0).edit()
            .remove(setupDraftKey).remove(setupDraftPlaybookKey).apply()
    }
    fun resumeSetupDraft() {
        val draft = _setupDraft.value ?: return
        _previewPlaybook.value = runCatching {
            context.getSharedPreferences("pittech-preferences", 0).getString(setupDraftPlaybookKey, null)
                ?.let { com.pittech.domain.PlaybookCodec.decode(it) }
        }.getOrNull()
        _setupPreview.value = draft
        _selectedCookId.value = null
    }
    fun deletePlaybook(id: String) = perform { app.companionRepository.delete(id) }

    val cooks: StateFlow<List<CookWithDishes>> = repository.observeCooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedCookId = MutableStateFlow<String?>(null)
    val selectedCookId: StateFlow<String?> = _selectedCookId.asStateFlow()

    init {
        // PL-20 (#172): keep the diagnostics record of the open cook
        // current so crash recovery can return to it. The initial value
        // is dropped: at process start nothing has been selected yet,
        // and the record from before a crash must survive until
        // recovery reads it.
        viewModelScope.launch {
            _selectedCookId.drop(1).collect { CrashDiagnostics.recordOpenCook(context, it) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedCook = _selectedCookId.filterNotNull()
        .flatMapLatest(repository::observeCookDetail)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val insights: StateFlow<InsightsSnapshot> = repository.observeInsights()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsSnapshot(emptyList(), emptyList(), emptyList()))

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _savedCookId = MutableStateFlow<String?>(null)
    val savedCookId: StateFlow<String?> = _savedCookId.asStateFlow()

    private val _deletedEvent = MutableStateFlow<TimelineEventEntity?>(null)
    val deletedEvent: StateFlow<TimelineEventEntity?> = _deletedEvent.asStateFlow()

    private var deletedReading: SensorReadingEntity? = null

    private val _pendingReminderId = MutableStateFlow<String?>(null)
    val pendingReminderId: StateFlow<String?> = _pendingReminderId.asStateFlow()

    private val _importPreview = MutableStateFlow<PitTechDataTransfer.ImportDraft?>(null)
    val importPreview: StateFlow<PitTechDataTransfer.ImportDraft?> = _importPreview.asStateFlow()

    /** PL-19: the recorded meal in the open Cook Pack preview was added to
     * history. The preview itself stays open — the pending recipe save
     * choice is not dropped — so this is tracked beside the draft rather
     * than inside it (rewriting the draft would reset the selection). */
    private val _importHistoryAdded = MutableStateFlow(false)
    val importHistoryAdded: StateFlow<Boolean> = _importHistoryAdded.asStateFlow()

    /** The public item the open Cook Pack preview came from, so a completed
     * save can mark that Discover detail as saved (PL-19). */
    private var previewPublicItemId: String? = null

    /** PL-18: a completed publication asks the shell to open My Cookbook —
     * the user lands where the thing went. */
    private val _cookbookOpenRequests = MutableStateFlow(0)
    val cookbookOpenRequests: StateFlow<Int> = _cookbookOpenRequests.asStateFlow()
    fun requestCookbookOpen() { _cookbookOpenRequests.value += 1 }

    private val _restoredPreferences = MutableStateFlow<BackupPreferences?>(null)
    val restoredPreferences: StateFlow<BackupPreferences?> = _restoredPreferences.asStateFlow()

    private val _shareArchiveUri = MutableStateFlow<Uri?>(null)
    val shareArchiveUri: StateFlow<Uri?> = _shareArchiveUri.asStateFlow()

    fun openCook(cookId: String) {
        clearMessages()
        _selectedCookId.value = cookId
    }

    fun closeCook() {
        _selectedCookId.value = null
        clearMessages()
    }

    fun requestReminderCheckIn(reminderId: String) { _pendingReminderId.value = reminderId }
    fun consumeReminderCheckIn() { _pendingReminderId.value = null }

    // PL-02 (issue #172): a unified-reminder notification tap routes to THAT
    // rule's check-in, not just the cook.
    private val _pendingReminderRuleId = MutableStateFlow<String?>(null)
    val pendingReminderRuleId: StateFlow<String?> = _pendingReminderRuleId.asStateFlow()
    fun requestReminderRuleCheckIn(ruleId: String) { _pendingReminderRuleId.value = ruleId }
    fun consumeReminderRuleCheckIn() { _pendingReminderRuleId.value = null }

    // PL-03 (issue #172): a cook-alert notification tap routes to THAT rule —
    // Cook Detail opens with the Alerts sheet up at the firing rule.
    private val _pendingAlertId = MutableStateFlow<String?>(null)
    val pendingAlertId: StateFlow<String?> = _pendingAlertId.asStateFlow()
    fun requestAlertFocus(alertId: String) { _pendingAlertId.value = alertId }
    fun consumeAlertFocus() { _pendingAlertId.value = null }

    /** In-app feedback through the app's notice snackbar (PL-29 rider: test alerts land here, not in a real notification). */
    fun showNotice(message: String) { _notice.value = message }

    /** PL-01: the action a unified rule announced, for its check-in/record default. */
    suspend fun reminderAnnouncedAction(ruleId: String): String? =
        app.reminderRepository.rule(ruleId)?.let { app.reminderRepository.announcedAction(it) }

    /**
     * PL-01: the food-card phase actions record immediately in one tap (the
     * guidance Done pattern), instead of opening a composer that records
     * nothing until a second Save. The recorded event stays editable, so a
     * note or photo can be added to it afterwards.
     */
    fun recordDishActionNow(cookId: String, dishId: String?, action: String, onRecorded: (String) -> Unit) = perform {
        snapshotForCookLog(cookId)
        val result = repository.notes.save(
            com.pittech.domain.CookNoteDraft(
                cookId = cookId, dishId = dishId, category = "action", action = action,
                title = com.pittech.domain.PlaybookCodec.actions[action].orEmpty(),
            ),
        )
        // No _notice here: the caller confirms with its own snackbar, which
        // carries the "Add note" follow-up for the recorded entry.
        onRecorded(result.event.id)
    }

    fun startCook(draft: NewCookDraft) = perform {
        if (draft.recordGrill) require(app.grillMonitor.state.value.authenticated && app.grillMonitor.state.value.selectedDevice != null) { "Choose a connected grill in Devices before starting automatic recording." }
        val selected = app.grillMonitor.state.value.selectedDevice
        val resolved = if (draft.recordGrill && draft.smokerName.isBlank()) draft.copy(smokerName = selected?.name.orEmpty()) else draft
        val cookId = repository.startCook(resolved, transientPlaybook = _previewPlaybook.value)
        _previewPlaybook.value = null
        discardSetupDraft()
        _selectedCookId.value = cookId
        _savedCookId.value = cookId
        _notice.value = "Cook saved on this phone."
        if (draft.recordGrill) {
            val dishes = app.database.cookDao().getDishesForCook(cookId)
            attachSelectedGrill(cookId, draft.setpointUnit, mapOf("probe1" to draft.probe1DishIndex?.let { dishes.getOrNull(it)?.id }, "probe2" to draft.probe2DishIndex?.let { dishes.getOrNull(it)?.id },
                "probe3" to draft.probe3DishIndex?.let { dishes.getOrNull(it)?.id }, "probe4" to draft.probe4DishIndex?.let { dishes.getOrNull(it)?.id }))
        }
    }

    fun updateCookDetails(cookId: String, title: String, smoker: String, setpoint: String, unit: String, notes: String, fuel: String, wood: String, outdoorTemp: String, outdoorUnit: String, weather: String, wind: String) = perform {
        repository.updateCookDetails(cookId, title, smoker, setpoint, unit, notes, fuel, wood, outdoorTemp, outdoorUnit, weather, wind)
        _notice.value = "Cook details saved."
    }

    fun addDish(cookId: String, draft: DishDraft) = perform {
        repository.addDish(cookId, draft)
        _notice.value = "Dish added."
    }

    fun saveIngredient(ingredient: com.pittech.data.IngredientEntity, onFinished: (Boolean) -> Unit) = perform({
        repository.saveIngredient(ingredient)
        _notice.value = "Ingredient saved."
    }, onFinished)

    fun updateDish(dish: DishEntity) = perform {
        repository.updateDishDetails(dish)
        _notice.value = "Dish details saved."
    }

    fun deleteDish(dish: DishEntity) = perform {
        cancelCompanionNotifications(dish.cookId, dish.id)
        repository.deleteDish(dish)
        _notice.value = "Dish deleted. Cook-level notes and timeline entries remain."
    }

    fun addTimelineEvent(cookId: String, dishId: String?, type: String, title: String, details: String?, occurredAt: Long) = perform {
        snapshotForCookLog(cookId)
        repository.addTimelineEvent(cookId, dishId, type, title, details, occurredAt)
        _notice.value = "Entry added to the timeline."
    }

    fun addCookLogEntry(
        cookId: String,
        dishId: String?,
        type: String,
        title: String,
        details: String?,
        occurredAt: Long,
        photoUri: String?,
        photoCaption: String?,
        usePhotoCaptureTime: Boolean = false,
        useCurrentTime: Boolean = false,
        onSaved: (eventId: String, photoAttached: Boolean) -> Unit,
    ) = performWithLogResult(onSaved) {
        snapshotForCookLog(cookId)
        val time = if (useCurrentTime && app.database.recordingDao().getRecording(cookId)?.samplingMode == com.pittech.devices.GrillSamplingMode.ON_LOG.key) System.currentTimeMillis() else occurredAt
        repository.addTimelineEventWithPhoto(cookId, dishId, type, title, details, time, photoUri, photoCaption, usePhotoCaptureTime)
    }

    fun adoptFoodOnTiming(id: String, at: Long?) = perform {
        repository.adoptFoodOnTiming(id, at)
        _notice.value = "Timing now uses actual Food on actions."
    }
    suspend fun loadNote(eventId: String) = repository.notes.load(eventId)
    fun flushNoteDraft(draft: com.pittech.domain.CookNoteDraft) { viewModelScope.launch { persistNoteDraft(draft) } }
    suspend fun persistNoteDraft(draft: com.pittech.domain.CookNoteDraft) {
        try { repository.notes.persistDraft(draft) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { _error.value = com.pittech.ui.ErrorCopy.failureText(failure, com.pittech.ui.ErrorCopy.SAVE_NOTE_DRAFT_FAILED) }
    }
    suspend fun stageNotePhoto(id: String, uri: String) = repository.notes.stagePhoto(id, uri)
    fun discardNoteDraft(id: String) = perform { repository.notes.discardDraft(id) }
    fun saveNote(draft: com.pittech.domain.CookNoteDraft, editing: Boolean, capture: CookInputs? = null, onSaved: (Boolean) -> Unit) = performWithLogResult({ _, attached ->
        _notice.value = when {
            !attached -> "Entry saved; its photo could not be attached. Reopen the saved draft to retry."
            draft.category == "temperature" -> if (editing) "Temperature entry updated." else "Temperature saved."
            editing -> "Timeline entry updated."
            else -> "Entry added to the timeline."
        }
        onSaved(attached)
    }) {
        if (!editing) snapshotForCookLog(draft.cookId)
        val saved = repository.notes.save(draft)
        // Flowing §4.2 capture (wrap material / spritz) is written inside
        // the note's own save operation: a separate ViewModel call from
        // the saved-callback would race the busy guard and be dropped.
        if (capture != null) repository.saveCookInputs(draft.cookId, draft.dishId, capture)
        saved
    }
    private var deletedNote: com.pittech.data.DeletedCookNote? = null

    fun updateTimelineEvent(event: TimelineEventEntity) = perform {
        repository.updateTimelineEvent(event)
        _notice.value = "Timeline entry updated."
    }

    fun deleteTimelineEvent(event: TimelineEventEntity) = perform {
        deletedNote = repository.notes.delete(event.id)
        _deletedEvent.value = event
        _notice.value = "Entry deleted."
    }

    fun undoDeleteTimelineEvent() {
        val event = _deletedEvent.value ?: return
        perform {
            deletedNote?.let { repository.notes.restore(it) } ?: repository.restoreTimelineEvent(event)
            deletedNote = null
            _deletedEvent.value = null
            _notice.value = "Entry restored."
        }
    }

    fun clearDeletedEvent() { _deletedEvent.value = null; deletedReading = null; deletedNote = null }

    fun updateSensorReading(reading: SensorReadingEntity) = perform {
        repository.updateSensorReading(reading)
        _notice.value = "Temperature entry updated."
    }

    fun deleteSensorReading(reading: SensorReadingEntity) = perform {
        repository.deleteSensorReading(reading)
        deletedReading = reading
        _notice.value = "Temperature entry deleted."
    }

    fun undoDeleteSensorReading() {
        val reading = deletedReading ?: return
        perform {
            repository.restoreSensorReading(reading)
            deletedReading = null
            _notice.value = "Temperature entry restored."
        }
    }

    fun undoLastDelete() {
        when {
            _deletedEvent.value != null -> undoDeleteTimelineEvent()
            deletedReading != null -> undoDeleteSensorReading()
        }
    }

    suspend fun readPhotoBytes(relativePath: String): ByteArray? = repository.readPhoto(relativePath)

    fun addManualTemperature(cookId: String, dishId: String?, probe: String, type: String, value: Double, unit: String, measuredAt: Long) = perform {
        snapshotForCookLog(cookId)
        repository.addManualTemperature(cookId, dishId, probe, type, value, unit, measuredAt)
        _notice.value = "Temperature saved."
    }

    fun addTarget(cookId: String, dishId: String?, type: String, value: Double, unit: String, explanation: String?) = perform {
        repository.addTarget(cookId, dishId, type, value, unit, explanation)
        _notice.value = "Target saved."
    }

    fun deleteTarget(target: TargetEntity) = perform {
        repository.deleteTarget(target)
        _notice.value = "Target deleted."
    }

    fun addCookPhoto(cookId: String, uri: String, caption: String?, onFinished: ((Boolean) -> Unit)? = null) = perform({
        snapshotForCookLog(cookId)
        repository.addCookPhoto(cookId, uri, caption)
        _notice.value = "Photo added to the cook."
    }, onFinished)

    fun createCookReminder(cookId: String, title: String, dueAtUtcMillis: Long, notificationsEnabled: Boolean) = perform {
        val reminder = repository.createCookReminder(cookId, title, dueAtUtcMillis)
        CookReminderNotifications.schedule(context, reminder)
        _notice.value = if (notificationsEnabled) "Reminder set for ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(dueAtUtcMillis))}." else "Reminder saved. Turn on PitTech notifications to see the alert when it is due."
    }

    fun cancelCookReminder(reminder: CookReminderEntity) = perform {
        repository.cancelCookReminder(reminder.id)?.let { CookReminderNotifications.cancel(context, it.id, it.cookId) }
        _notice.value = "Reminder cancelled."
    }

    /** PL-02: Dismiss — the legacy check-in's third explicit option; ends the
     * reminder with nothing recorded (and replaces the hidden 1-hour snooze). */
    fun dismissCookReminder(reminder: CookReminderEntity) = perform {
        repository.dismissCookReminder(reminder.id)?.let { CookReminderNotifications.cancel(context, it.id, it.cookId) }
        consumeReminderCheckIn()
        _notice.value = "Reminder dismissed."
    }

    fun snoozeCookReminder(reminder: CookReminderEntity, delayMillis: Long) = perform {
        val dueAt = System.currentTimeMillis() + delayMillis
        repository.snoozeCookReminder(reminder.id, dueAt)?.let { updated ->
            CookReminderNotifications.cancel(context, reminder.id, reminder.cookId)
            CookReminderNotifications.schedule(context, updated)
        }
        consumeReminderCheckIn()
        _notice.value = "Reminder moved to ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(dueAt))}."
    }

    fun completeCookReminder(
        reminder: CookReminderEntity,
        note: String,
        photoUri: String?,
        photoCaption: String?,
        onSaved: (eventId: String, photoAttached: Boolean) -> Unit,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            _notice.value = null
            try {
                snapshotForCookLog(reminder.cookId)
                val checkIn = repository.completeCookReminder(reminder.id, note)
                    ?: error("This reminder has already been completed or is no longer available.")
                CookReminderNotifications.cancel(context, checkIn.reminder.id, checkIn.reminder.cookId)
                val photoAttached = if (photoUri == null) true else try {
                    repository.addReminderCheckInPhoto(checkIn.event.id, checkIn.reminder.cookId, photoUri, photoCaption)
                    true
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                _notice.value = if (photoAttached) "Check-in added to the cook timeline." else "Check-in saved; its photo could not be attached."
                consumeReminderCheckIn()
                onSaved(checkIn.event.id, photoAttached)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _error.value = com.pittech.ui.ErrorCopy.failureText(failure, com.pittech.ui.ErrorCopy.SAVE_CHECK_IN_FAILED)
            } finally {
                _busy.value = false
            }
        }
    }

    fun attachPhotoToTimelineEvent(eventId: String, cookId: String, dishId: String?, photoUri: String, caption: String?, usePhotoCaptureTime: Boolean = false) = perform {
        snapshotForCookLog(cookId)
        repository.attachPhotoToTimelineEvent(eventId, cookId, dishId, photoUri, caption, usePhotoCaptureTime)
        _notice.value = "Photo attached to the cook log."
    }

    fun saveResults(
        cookId: String,
        dishId: String?,
        finalTemp: String,
        unit: String,
        restMinutes: String,
        ratings: Map<String, String>,
        notes: String,
        finish: Boolean,
        keepDoing: String = "",
        changeNextTime: String = "",
        cookAgain: Boolean? = null,
        cookInputs: CookInputs? = null,
        onFinished: ((Boolean) -> Unit)? = null,
    ) = perform({
        repository.saveLearning(cookId, dishId, keepDoing, changeNextTime)
        if (cookInputs != null) repository.saveCookInputs(cookId, dishId, cookInputs)
        repository.saveCookResults(cookId, dishId, finalTemp, unit, restMinutes, ratings, notes, finish, cookAgain = cookAgain, updateCookAgain = dishId != null)
        if (finish) reconcileRecording()
        if (finish) repository.cancelPendingCookReminders(cookId).forEach { CookReminderNotifications.cancel(context, it.id, it.cookId) }
        if (finish) cancelCompanionNotifications(cookId)
        _notice.value = if (finish) "Cook finished and results saved." else "Results saved."
    }, onFinished)

    /**
     * PL-08: the completion sheet's quick-verdict step — the whole-cook
     * overall verdict plus one verdict (rating + cook again?) per dish,
     * written through the one results model without touching other fields.
     */
    fun saveFinishVerdicts(
        cookId: String,
        overallRating: Double?,
        dishVerdicts: Map<String, Pair<Double?, Boolean?>>,
        onFinished: ((Boolean) -> Unit)? = null,
    ) = perform({
        repository.saveVerdict(cookId, null, overallRating, null)
        dishVerdicts.forEach { (dishId, verdict) -> repository.saveVerdict(cookId, dishId, verdict.first, verdict.second) }
        _notice.value = "Verdict saved."
    }, onFinished)

    fun reopenCook(cookId: String) = perform {
        repository.reopenCook(cookId)
        reconcileRecording()
        _notice.value = "Cook reopened."
    }

    fun completeCook(cookId: String) = perform {
        repository.completeCook(cookId)
        reconcileRecording()
        repository.cancelPendingCookReminders(cookId).forEach { CookReminderNotifications.cancel(context, it.id, it.cookId) }
        cancelCompanionNotifications(cookId)
        _notice.value = "Cook marked finished."
    }

    fun pauseCook(cookId: String) = perform {
        repository.pauseCook(cookId)
        reconcileRecording()
        _notice.value = "Cook paused."
    }

    fun resumeCook(cookId: String) = perform {
        repository.resumeCook(cookId)
        _notice.value = "Cook resumed."
    }

    fun deleteCook(cook: com.pittech.data.CookEntity) = perform {
        cancelCompanionNotifications(cook.id)
        app.database.cookDao().getAllReminders().filter { it.cookId == cook.id }.forEach { CookReminderNotifications.cancel(context, it.id, cook.id) }
        repository.deleteCook(cook)
        reconcileRecording()
        closeCook()
        _notice.value = "Cook deleted."
    }

    private suspend fun cancelCompanionNotifications(cookId: String, dishId: String? = null) {
        app.database.companionDao().forCook(cookId).forEach { r -> when (r.kind) {
            "plan" -> com.pittech.domain.CookPlanEngine.decode(r.payload).let { plan -> plan.book.steps.filter { dishId == null || it.dishIndex?.let { i -> plan.dishIds[i] } == dishId }.forEach { CookGuidanceNotifications.cancel(context, cookId, it.id) } }
            "alert" -> if (dishId == null || com.pittech.domain.CookAlertEngine.decode(r.payload).first.dishId == dishId) { CookGuidanceNotifications.cancelAlarm(context, cookId, r.id); CookAlertNotifications.cancel(context, r.id) }
        } }
    }

    fun duplicateCookSetup(cookId: String) = perform {
        val newId = repository.duplicateCookSetup(cookId)
        _selectedCookId.value = newId
        _notice.value = "A new cook was started from this setup."
    }

    fun export(uri: Uri, format: String, cookId: String? = null) = perform {
        withContext(Dispatchers.IO) {
            val output = context.contentResolver.openOutputStream(uri) ?: error("The selected file could not be opened for saving.")
            output.use {
                when (format) {
                    FORMAT_XLSX -> dataTransfer.writeWorkbook(it, cookId)
                    FORMAT_CSV -> dataTransfer.writeCsv(it, cookId)
                    else -> dataTransfer.writeZip(it, cookId)
                }
            }
        }
        _notice.value = "Export saved."
    }

    fun createShareArchive(cookId: String? = null) = perform {
        _shareArchiveUri.value = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "exports")
            check(directory.exists() || directory.mkdirs()) { "PitTech could not prepare a temporary backup file." }
            pruneStaleShareArchives(directory)
            val archive = File.createTempFile("pittech_export_", ".zip", directory)
            try {
                archive.outputStream().buffered().use { dataTransfer.writeZip(it, cookId) }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive)
            } catch (failure: Throwable) {
                archive.delete()
                throw failure
            }
        }
        _notice.value = "Backup ready to share. Choose where to send it."
    }

    fun createShareCookPack(documentId: String, includeRecordedCook: Boolean = false) = perform {
        _shareArchiveUri.value = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "exports").apply { mkdirs() }
            pruneStaleShareArchives(directory)
            val pack = File.createTempFile("pittech_recipe_", ".pittech", directory)
            try {
                pack.outputStream().buffered().use { dataTransfer.writeCookPack(it, documentId, includeRecordedCook) }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pack)
            } catch (failure: Throwable) { pack.delete(); throw failure }
        }
        _notice.value = "Cook Pack ready to share."
    }

    fun consumeShareArchive() { _shareArchiveUri.value = null }

    private fun pruneStaleShareArchives(directory: File = File(context.cacheDir, "exports")) {
        runCatching {
            val expiry = System.currentTimeMillis() - SHARE_ARCHIVE_MAX_AGE_MILLIS
            directory.listFiles().orEmpty()
                .filter { it.isFile && it.extension.lowercase() in setOf("zip", "pittech") && it.lastModified() < expiry }
                .forEach(File::delete)
        }
    }

    fun previewImport(uri: Uri) = perform {
        previewPublicItemId = null
        _importHistoryAdded.value = false
        _importPreview.value = withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri) ?: error("The selected backup could not be opened.")
            input.use(dataTransfer::previewImport)
        }
        _notice.value = null
    }

    fun confirmImport() {
        val draft = _importPreview.value ?: return
        perform {
            val result = dataTransfer.import(draft)
            draft.preferences?.let { restored ->
                context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE).edit()
                    .putString(TEMPERATURE_UNIT_KEY, restored.temperatureUnit)
                    .putString(WEIGHT_UNIT_KEY, restored.weightUnit)
                    .putString(THEME_MODE_KEY, restored.themeMode)
                    .apply()
                _restoredPreferences.value = restored
            }
            val pendingReminders = repository.getPendingCookReminders()
            pendingReminders.forEach { CookReminderNotifications.schedule(context, it) }
            _importPreview.value = null
            _importHistoryAdded.value = false
            previewPublicItemId = null
            val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            val reminderStatus = if (pendingReminders.isNotEmpty() && !notificationsEnabled) " Reminders are saved, but notifications are off so they may not alert." else ""
            _notice.value = "Restored ${result.importedCooks} cooks and ${result.importedPhotos} photos. ${result.skippedCooks} duplicate cooks skipped.$reminderStatus"
        }
    }

    fun confirmCookPackImport(revisions: Set<String>) {
        val source = _importPreview.value ?: return
        perform {
            val draft = source.selectCookPackRecipes(revisions)
            withContext(Dispatchers.IO) { dataTransfer.import(draft) }
            _importPreview.value = null
            _importHistoryAdded.value = false
            if (source.publicCookOnSave) {
                // PL-19: "Cook this" lands on the Start Cook review instead
                // of teleporting into a live cook from a save dialog — the
                // meal starts only when the user confirms the setup.
                _previewPlaybook.value = null
                _setupPreview.value = repository.recipeStartDraft(source.snapshot.companionRecords.firstOrNull()?.title ?: "New meal", revisions.sorted())
                _notice.value = "Saved ${revisions.size} ${if (revisions.size == 1) "recipe" else "recipes"}. Review the meal setup to start cooking."
            } else {
                previewPublicItemId?.let { publicDiscovery.markSaved(it) }
                _notice.value = "Saved ${revisions.size} ${if (revisions.size == 1) "recipe" else "recipes"}. Find them in Recipes."
            }
            previewPublicItemId = null
        }
    }

    fun confirmCookPackHistoryImport() {
        val source = _importPreview.value ?: return
        perform {
            val result = withContext(Dispatchers.IO) { dataTransfer.import(source.recordedCookOnly()) }
            // PL-19: adding the recorded meal no longer closes the save
            // dialog — the pending recipe selection survives, so the user
            // can still save the recipes they picked.
            _importHistoryAdded.value = true
            _notice.value = if (result.importedCooks == 0) "This recorded meal is already in your history. You can still save the recipes below." else "Added the recorded meal to history. You can still save the recipes below."
        }
    }

    fun cancelImport() {
        _importPreview.value = null
        _importHistoryAdded.value = false
        previewPublicItemId = null
    }
    fun consumeRestoredPreferences() { _restoredPreferences.value = null }

    fun clearSavedCookSignal() { _savedCookId.value = null }
    fun clearMessages() { _error.value = null; _notice.value = null }
    fun clearSaveError() { _error.value = null }

    private fun perform(block: suspend () -> Unit) = perform(block, null)

    private fun perform(block: suspend () -> Unit, onFinished: ((Boolean) -> Unit)?) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            _notice.value = null
            var succeeded = false
            try {
                block()
                succeeded = true
                sync.requestAutomaticSync()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _error.value = com.pittech.ui.ErrorCopy.failureText(failure, com.pittech.ui.ErrorCopy.SAVE_CHANGE_FAILED)
            } finally {
                _busy.value = false
                onFinished?.invoke(succeeded)
            }
        }
    }

    private fun performWithLogResult(
        onSaved: (eventId: String, photoAttached: Boolean) -> Unit,
        block: suspend () -> com.pittech.data.CookLogSaveResult,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            _notice.value = null
            try {
                val result = block()
                _notice.value = if (result.photoAttached) "Entry added to the timeline." else "Entry saved; its photo could not be attached."
                onSaved(result.event.id, result.photoAttached)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _error.value = com.pittech.ui.ErrorCopy.failureText(failure, com.pittech.ui.ErrorCopy.SAVE_TIMELINE_ENTRY_FAILED)
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        const val FORMAT_XLSX = "xlsx"
        const val FORMAT_CSV = "csv"
        const val FORMAT_ZIP = "zip"
        private const val PREFERENCES_FILE = "pittech-preferences"
        private const val TEMPERATURE_UNIT_KEY = "temperature-unit"
        private const val WEIGHT_UNIT_KEY = "weight-unit"
        private const val THEME_MODE_KEY = "theme-mode"
        private const val SHARE_ARCHIVE_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
    }

    class Factory(
        private val repository: CookRepository,
        private val dataTransfer: PitTechDataTransfer,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CooksViewModel::class.java))
            return CooksViewModel(repository, dataTransfer, context.applicationContext) as T
        }
    }
}
