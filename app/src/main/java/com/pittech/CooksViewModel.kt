package com.pittech

import android.content.Context
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
    init {
        viewModelScope.launch(Dispatchers.IO) { runCatching { pruneStaleShareArchives() } }
    }

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
        val cookId = repository.startCook(draft)
        _selectedCookId.value = cookId
        _savedCookId.value = cookId
        _notice.value = "Cook saved on this phone."
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
        onSaved: (eventId: String, photoAttached: Boolean) -> Unit,
    ) = performWithLogResult(onSaved) {
        repository.addTimelineEventWithPhoto(cookId, dishId, type, title, details, occurredAt, photoUri, photoCaption)
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

    fun attachPhotoToTimelineEvent(eventId: String, cookId: String, dishId: String?, photoUri: String, caption: String?) = perform {
        repository.attachPhotoToTimelineEvent(eventId, cookId, dishId, photoUri, caption)
        _notice.value = "Photo attached to the cook log."
    }

    fun saveResults(cookId: String, dishId: String?, finalTemp: String, unit: String, restMinutes: String, ratings: Map<String, String>, notes: String, finish: Boolean) = perform {
        repository.saveCookResults(cookId, dishId, finalTemp, unit, restMinutes, ratings, notes, finish)
        if (finish) repository.cancelPendingCookReminders(cookId).forEach { CookReminderNotifications.cancel(context, it.id, it.cookId) }
        _notice.value = if (finish) "Cook finished and results saved." else "Results saved."
    }

    fun completeCook(cookId: String) = perform {
        repository.completeCook(cookId)
        repository.cancelPendingCookReminders(cookId).forEach { CookReminderNotifications.cancel(context, it.id, it.cookId) }
        _notice.value = "Cook marked finished."
    }

    fun pauseCook(cookId: String) = perform {
        repository.pauseCook(cookId)
        _notice.value = "Cook paused."
    }

    fun resumeCook(cookId: String) = perform {
        repository.resumeCook(cookId)
        _notice.value = "Cook resumed."
    }

    fun deleteCook(cook: com.pittech.data.CookEntity) = perform {
        repository.deleteCook(cook)
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
