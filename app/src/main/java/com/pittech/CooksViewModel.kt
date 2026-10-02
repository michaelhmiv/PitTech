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
import com.pittech.data.TimelineEventEntity
import com.pittech.data.SensorReadingEntity
import com.pittech.data.TargetEntity
import com.pittech.data.DishEntity
import com.pittech.domain.DishDraft
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val app get() = context.applicationContext as PitTechApplication
    internal val grillState get() = app.grillMonitor.state
    internal val recordingServiceRunning get() = app.recordingServiceRunning

    internal fun configureGrillSampling(policy: com.pittech.devices.GrillSamplingPolicy) = app.grillMonitor.configureSampling(policy)

    internal fun configureCookSampling(cookId: String, policy: com.pittech.devices.GrillSamplingPolicy) = perform {
        app.recordingRepository.configureSampling(cookId, policy)
        reconcileRecording()
    }

    private suspend fun reconcileRecording() {
        if (!BuildConfig.CONTROLLER_TESTING_ENABLED) return
        val recording = app.database.recordingDao().getActiveRecording()
        if (recording == null) {
            app.loggingRecordingActive = false
            app.grillMonitor.setRecording(false)
            context.stopService(Intent(context, CookRecordingService::class.java))
            return
        }
        val policy = com.pittech.devices.GrillSamplingPolicy.stored(recording.samplingMode, recording.samplingIntervalMillis)
        app.loggingRecordingActive = policy.mode == com.pittech.devices.GrillSamplingMode.ON_LOG
        app.grillMonitor.configureSampling(policy)
        app.grillMonitor.state.value.devices.firstOrNull { com.pittech.devices.CookTelemetryPolicy.deviceKey(it.id) == recording.controllerKey }?.let { app.grillMonitor.lockDevice(it.id) }
        app.grillMonitor.setRecording(true)
        if (policy.mode == com.pittech.devices.GrillSamplingMode.ON_LOG) {
            context.stopService(Intent(context, CookRecordingService::class.java))
            // The logging owner has no timers or background queries, but keeps the cook binding.
            app.grillMonitor.setRecording(true)
        } else if (!app.recordingServiceRunning.value) CookRecordingService.start(context)
    }

    /** Log creation may fail independently of the optional grill snapshot. Never prevent saving the log. */
    internal suspend fun snapshotForCookLog(cookId: String, force: Boolean = false) {
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
    fun assignGrillProbe(cookId: String, probeId: String, dishId: String?) = perform { app.recordingRepository.assignProbe(probeId, cookId, dishId) }
    init {
        viewModelScope.launch(Dispatchers.IO) { runCatching { pruneStaleShareArchives() } }
    }

    fun beginGuidance(cookId: String) = perform { app.companionRepository.beginPlan(cookId) }
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
    fun closePlaybookEditor() { _playbookEditor.value = null }
    fun savePlaybook(book: com.pittech.domain.CookPlaybook, photos: Boolean) = perform {
        val newId = app.companionRepository.save(book, editingPlaybookId, photos)
        if (_setupPreview.value?.playbookId == editingPlaybookId && editingPlaybookId != null) _setupPreview.value = book.draft.copy(playbookId = newId)
        _playbookEditor.value = null
        _notice.value = "Playbook saved on this phone."
    }
    fun previewPlaybook(id: String) = perform {
        val book = app.companionRepository.get(id)?.let { com.pittech.domain.PlaybookCodec.decode(it.payload) } ?: error("Playbook not found.")
        _setupPreview.value = book.draft.copy(title = book.name, playbookId = id)
        _selectedCookId.value = null
    }
    fun previewCookAgain(cookId: String) = perform {
        val book = app.companionRepository.suggest(cookId)
        val id = app.companionRepository.save(book)
        _setupPreview.value = book.draft.copy(playbookId = id)
        _selectedCookId.value = null
    }
    fun consumeSetupPreview() { _setupPreview.value = null }
    fun deletePlaybook(id: String) = perform { app.companionRepository.delete(id) }

    val cooks: StateFlow<List<CookWithDishes>> = repository.observeCooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedCookId = MutableStateFlow<String?>(null)
    val selectedCookId: StateFlow<String?> = _selectedCookId.asStateFlow()

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

    fun startCook(draft: NewCookDraft) = perform {
        if (draft.recordGrill) require(app.grillMonitor.state.value.authenticated && app.grillMonitor.state.value.selectedDevice != null) { "Choose a connected grill in Devices before starting automatic recording." }
        val selected = app.grillMonitor.state.value.selectedDevice
        val resolved = if (draft.recordGrill && draft.smokerName.isBlank()) draft.copy(smokerName = selected?.name.orEmpty()) else draft
        val cookId = repository.startCook(resolved)
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

    fun updateDish(dish: DishEntity) = perform {
        repository.updateDishDetails(dish)
        _notice.value = "Dish details saved."
    }

    fun deleteDish(dish: DishEntity) = perform {
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

    fun updateTimelineEvent(event: TimelineEventEntity) = perform {
        repository.updateTimelineEvent(event)
        _notice.value = "Timeline entry updated."
    }

    fun deleteTimelineEvent(event: TimelineEventEntity) = perform {
        repository.deleteTimelineEvent(event)
        _deletedEvent.value = event
        _notice.value = "Entry deleted."
    }

    fun undoDeleteTimelineEvent() {
        val event = _deletedEvent.value ?: return
        perform {
            repository.restoreTimelineEvent(event)
            _deletedEvent.value = null
            _notice.value = "Entry restored."
        }
    }

    fun clearDeletedEvent() { _deletedEvent.value = null; deletedReading = null }

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
                _error.value = failure.message ?: "That check-in could not be saved. Try again."
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

    fun saveResults(cookId: String, dishId: String?, finalTemp: String, unit: String, restMinutes: String, ratings: Map<String, String>, notes: String, finish: Boolean) = perform {
        repository.saveCookResults(cookId, dishId, finalTemp, unit, restMinutes, ratings, notes, finish)
        if (finish) reconcileRecording()
        if (finish) repository.cancelPendingCookReminders(cookId).forEach { CookReminderNotifications.cancel(context, it.id, it.cookId) }
        _notice.value = if (finish) "Cook finished and results saved." else "Results saved."
    }

    fun completeCook(cookId: String) = perform {
        repository.completeCook(cookId)
        reconcileRecording()
        repository.cancelPendingCookReminders(cookId).forEach { CookReminderNotifications.cancel(context, it.id, it.cookId) }
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
        repository.deleteCook(cook)
        reconcileRecording()
        closeCook()
        _notice.value = "Cook deleted."
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

    fun consumeShareArchive() { _shareArchiveUri.value = null }

    private fun pruneStaleShareArchives(directory: File = File(context.cacheDir, "exports")) {
        runCatching {
            val expiry = System.currentTimeMillis() - SHARE_ARCHIVE_MAX_AGE_MILLIS
            directory.listFiles().orEmpty()
                .filter { it.isFile && it.extension.equals("zip", ignoreCase = true) && it.lastModified() < expiry }
                .forEach(File::delete)
        }
    }

    fun previewImport(uri: Uri) = perform {
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
            val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            val reminderStatus = if (pendingReminders.isNotEmpty() && !notificationsEnabled) " Reminders are saved, but notifications are off so they may not alert." else ""
            _notice.value = "Restored ${result.importedCooks} cooks and ${result.importedPhotos} photos. ${result.skippedCooks} duplicate cooks skipped.$reminderStatus"
        }
    }

    fun cancelImport() { _importPreview.value = null }
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
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _error.value = failure.message ?: "That change could not be saved. Try again."
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
                _error.value = failure.message ?: "That timeline entry could not be saved. Try again."
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
