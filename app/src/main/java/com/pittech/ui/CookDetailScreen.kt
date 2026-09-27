package com.pittech.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel
import com.pittech.data.CameraPhotoFiles
import com.pittech.data.CookDetailData
import com.pittech.data.CookReminderEntity
import com.pittech.data.CookStatus
import com.pittech.data.DishEntity
import com.pittech.data.SensorReadingEntity
import com.pittech.data.TimelineEventEntity
import com.pittech.domain.CookEntryValidation
import com.pittech.domain.DishDraft
import com.pittech.domain.IngredientDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Calendar

private enum class CookTab(val label: String) { LIVE("Live"), TIMELINE("Timeline"), CHARTS("Charts") }
private data class PhotoRetry(val eventId: String, val cookId: String, val dishId: String?, val uri: String, val caption: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookDetailScreen(
    data: CookDetailData,
    viewModel: CooksViewModel,
    preferredTemperatureUnit: String,
    preferredWeightUnit: String,
    onBack: () -> Unit,
) {
    var selectedTab by rememberSaveable(data.cook.id) { mutableStateOf(CookTab.LIVE.name) }
    var editCook by remember { mutableStateOf(false) }
    var addDish by remember { mutableStateOf(false) }
    var editDish by remember { mutableStateOf<DishEntity?>(null) }
    var dishToDelete by remember { mutableStateOf<DishEntity?>(null) }
    var editEvent by remember { mutableStateOf<TimelineEventEntity?>(null) }
    var showNewEvent by remember { mutableStateOf(false) }
    var showTemperature by remember { mutableStateOf(false) }
    var showTarget by remember { mutableStateOf(false) }
    var editReading by remember { mutableStateOf<SensorReadingEntity?>(null) }
    var showResults by remember { mutableStateOf(false) }
    var confirmDeleteCook by remember { mutableStateOf(false) }
    var showExportCook by remember { mutableStateOf(false) }
    var showPhotoSource by rememberSaveable { mutableStateOf(false) }
    var photoSourceDestination by rememberSaveable { mutableStateOf("log") }
    var photoEntryMode by rememberSaveable(data.cook.id) { mutableStateOf(false) }
    var logComposerSession by rememberSaveable(data.cook.id) { mutableStateOf(0) }
    var logPhotoUri by rememberSaveable(data.cook.id) { mutableStateOf<String?>(null) }
    var logPhotoCaption by rememberSaveable(data.cook.id) { mutableStateOf("") }
    var reminderPhotoUri by rememberSaveable(data.cook.id) { mutableStateOf<String?>(null) }
    var reminderPhotoCaption by rememberSaveable(data.cook.id) { mutableStateOf("") }
    var showReminderComposer by rememberSaveable { mutableStateOf(false) }
    var reminderTitle by rememberSaveable { mutableStateOf("Check grill temperature") }
    var reminderDelayMillis by rememberSaveable { mutableStateOf(60L * 60 * 1000) }
    var completionReminderId by remember { mutableStateOf<String?>(null) }
    var showReminderCheckIn by remember { mutableStateOf(false) }
    var pendingPhotoRetry by remember { mutableStateOf<PhotoRetry?>(null) }
    var viewingPhoto by remember { mutableStateOf<com.pittech.data.PhotoEntity?>(null) }
    var photoToSave by remember { mutableStateOf<com.pittech.data.PhotoEntity?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val deletedEvent by viewModel.deletedEvent.collectAsStateWithLifecycle()
    val pendingReminderId by viewModel.pendingReminderId.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            if (photoSourceDestination == "reminder") {
                reminderPhotoUri = uri.toString()
                showReminderCheckIn = true
            } else {
                logPhotoUri = uri.toString()
                showNewEvent = true
            }
        } else if (photoSourceDestination == "reminder") {
            showReminderCheckIn = true
        }
    }
    val addCameraPhoto = rememberCameraPhotoCapture(
        onPhotoCaptured = { uri ->
            if (photoSourceDestination == "reminder") {
                reminderPhotoUri = uri.toString()
                showReminderCheckIn = true
            } else {
                logPhotoUri = uri.toString()
                showNewEvent = true
            }
        },
        onCaptureCancelled = {
            if (photoSourceDestination == "reminder") showReminderCheckIn = true
        },
    )
    val savePhoto = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val selected = photoToSave
        photoToSave = null
        if (uri != null && selected != null) coroutineScope.launch {
            runCatching {
                val bytes = viewModel.readPhotoBytes(selected.relativePath) ?: error("The photo file is missing.")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("The selected location could not be opened.")
                }
            }.onSuccess { android.widget.Toast.makeText(context, "Photo saved.", android.widget.Toast.LENGTH_SHORT).show() }
                .onFailure { android.widget.Toast.makeText(context, it.message ?: "Photo could not be saved.", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    val requestReminderPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.createCookReminder(data.cook.id, reminderTitle, System.currentTimeMillis() + reminderDelayMillis, granted)
        showReminderComposer = false
    }
    val exportWorkbook = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) viewModel.export(uri, CooksViewModel.FORMAT_XLSX, data.cook.id)
    }
    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.export(uri, CooksViewModel.FORMAT_ZIP, data.cook.id)
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.export(uri, CooksViewModel.FORMAT_CSV, data.cook.id)
    }

    fun sharePhoto(photo: com.pittech.data.PhotoEntity) {
        runCatching {
            val file = java.io.File(context.filesDir, photo.relativePath)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = photo.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(context.contentResolver, photo.caption ?: photo.originalFileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share cook photo"))
        }.onFailure { android.widget.Toast.makeText(context, it.message ?: "Photo could not be shared.", android.widget.Toast.LENGTH_LONG).show() }
    }

    fun saveReminder() {
        val notificationsEnabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsEnabled) {
            requestReminderPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.createCookReminder(data.cook.id, reminderTitle, System.currentTimeMillis() + reminderDelayMillis, notificationsEnabled)
            showReminderComposer = false
        }
    }

    LaunchedEffect(pendingReminderId, data.cook.id, data.reminders) {
        val id = pendingReminderId
        if (id != null && data.reminders.any { it.id == id && it.status == CookReminderEntity.STATUS_PENDING }) {
            completionReminderId = id
            showReminderCheckIn = true
        }
    }

    LaunchedEffect(notice, pendingPhotoRetry) {
        if (notice != null) {
            val retry = pendingPhotoRetry
            val retryAvailable = retry != null && notice!!.contains("photo could not be attached", ignoreCase = true)
            val result = snackbar.showSnackbar(
                message = notice!!,
                actionLabel = when {
                    retryAvailable -> "Retry photo"
                    notice!!.contains("deleted", ignoreCase = true) -> "Undo"
                    else -> null
                },
                withDismissAction = true,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                if (retryAvailable && retry != null) {
                    pendingPhotoRetry = null
                    viewModel.attachPhotoToTimelineEvent(retry.eventId, retry.cookId, retry.dishId, retry.uri, retry.caption)
                } else viewModel.undoLastDelete()
            }
            if (deletedEvent != null) viewModel.clearDeletedEvent()
        }
    }

    if (editCook) {
        CookBasicsDialog(data, onDismiss = { editCook = false }) { title, smoker, setpoint, unit, notes, fuel, wood, outdoor, outdoorUnit, weather, wind ->
            viewModel.updateCookDetails(data.cook.id, title, smoker, setpoint, unit, notes, fuel, wood, outdoor, outdoorUnit, weather, wind)
            editCook = false
        }
    }
    if (addDish) {
        DishEditorDialog(preferredWeightUnit = preferredWeightUnit, onDismiss = { addDish = false }) { draft ->
            viewModel.addDish(data.cook.id, draft)
            addDish = false
        }
    }
    editDish?.let { dish ->
        DishDetailsDialog(dish, onDismiss = { editDish = null }, onDelete = { dishToDelete = dish; editDish = null }) { updated ->
            viewModel.updateDish(updated)
            editDish = null
        }
    }
    dishToDelete?.let { dish ->
        AlertDialog(
            onDismissRequest = { dishToDelete = null },
            title = { Text("Delete ${dish.name}?") },
            text = { Text("This removes the dish and its preparation items. Cook timeline entries and photos stay in the cook record.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteDish(dish); dishToDelete = null }) { Text("Delete dish") } },
            dismissButton = { TextButton(onClick = { dishToDelete = null }) { Text("Cancel") } },
        )
    }
    if (showNewEvent || editEvent != null) {
        TimelineEventDialog(
            event = editEvent,
            dishes = data.dishes,
            draftKey = logComposerSession,
            photoEntryMode = photoEntryMode,
            photoUri = logPhotoUri,
            photoCaption = logPhotoCaption,
            onChoosePhoto = {
                photoSourceDestination = "log"
                showPhotoSource = true
            },
            onRemovePhoto = {
                logPhotoUri?.let { CameraPhotoFiles.delete(context, it) }
                logPhotoUri = null
                logPhotoCaption = ""
            },
            onPhotoCaptionChange = { logPhotoCaption = it },
            onDismiss = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                logPhotoUri?.let { CameraPhotoFiles.delete(context, it) }
                logPhotoUri = null
                logPhotoCaption = ""
                photoEntryMode = false
                showNewEvent = false
                editEvent = null
            },
            onSave = { type, title, details, occurred, dishId ->
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                if (editEvent == null) {
                    val uri = logPhotoUri
                    val caption = logPhotoCaption.takeIf { it.isNotBlank() }
                    val entryType = if (photoEntryMode && type == "note") "photo" else type
                    val entryTitle = if (photoEntryMode && title == "Cook note") "Photo added" else title
                    viewModel.addCookLogEntry(data.cook.id, dishId, entryType, entryTitle, details, occurred, uri, caption) { eventId, photoAttached ->
                        if (!photoAttached && uri != null) pendingPhotoRetry = PhotoRetry(eventId, data.cook.id, dishId, uri, caption)
                        else pendingPhotoRetry = null
                    }
                    if (uri == null) pendingPhotoRetry = null
                    logPhotoCaption = ""
                    logPhotoUri = null
                    photoEntryMode = false
                }
                else viewModel.updateTimelineEvent(editEvent!!.copy(dishId = dishId, eventType = type, title = title, details = details, occurredAtUtcMillis = occurred))
                showNewEvent = false
                editEvent = null
            },
        )
    }
    if (showTemperature || editReading != null) {
        TemperatureEntryDialog(
            reading = editReading,
            dishes = data.dishes,
            preferredUnit = preferredTemperatureUnit,
            onDismiss = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                showTemperature = false
                editReading = null
            },
            onSave = { probe, type, value, unit, measuredAt, dishId ->
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                if (editReading == null) viewModel.addManualTemperature(data.cook.id, dishId, probe, type, value, unit, measuredAt)
                else viewModel.updateSensorReading(editReading!!.copy(probeName = probe, measurementType = type, value = value, unit = unit, measuredAtUtcMillis = measuredAt, dishId = dishId))
                showTemperature = false
                editReading = null
            },
        )
    }
    if (showTarget) {
        TargetDialog(
            dishes = data.dishes,
            preferredUnit = preferredTemperatureUnit,
            onDismiss = { showTarget = false },
            onSave = { dishId, type, value, unit, explanation ->
                viewModel.addTarget(data.cook.id, dishId, type, value, unit, explanation)
                showTarget = false
            },
        )
    }
    if (showResults) {
        ResultsDialog(
            data = data,
            preferredUnit = preferredTemperatureUnit,
            onDismiss = { showResults = false },
            onSave = { dishId, finalTemp, unit, rest, ratings, notes, finish ->
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                viewModel.saveResults(data.cook.id, dishId, finalTemp, unit, rest, ratings, notes, finish)
                showResults = false
            },
        )
    }
    if (showExportCook) {
        AlertDialog(
            onDismissRequest = { showExportCook = false },
            title = { Text("Export this cook") },
            text = { Text("Save a file somewhere you control, or share a complete ZIP with another app.") },
            confirmButton = {
                Column {
                    TextButton(onClick = { showExportCook = false; exportWorkbook.launch("${safeFileName(data.cook.title)}.xlsx") }) { Text("Excel workbook") }
                    TextButton(onClick = { showExportCook = false; exportZip.launch("${safeFileName(data.cook.title)}_backup.zip") }) { Text("Save this cook as ZIP") }
                    TextButton(onClick = { showExportCook = false; viewModel.createShareArchive(data.cook.id) }) { Text("Share this cook") }
                    TextButton(onClick = { showExportCook = false; exportCsv.launch("${safeFileName(data.cook.title)}_readings.csv") }) { Text("Temperature readings CSV") }
                    TextButton(onClick = { showExportCook = false }) { Text("Cancel") }
                }
            },
        )
    }
    if (showPhotoSource) {
        PhotoSourceDialog(
            onDismiss = {
                showPhotoSource = false
                if (photoSourceDestination == "reminder") showReminderCheckIn = true
            },
            onTakePhoto = {
                showPhotoSource = false
                addCameraPhoto()
            },
            onChooseFromLibrary = {
                showPhotoSource = false
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }
    if (showReminderComposer) {
        ReminderComposerDialog(
            title = reminderTitle,
            delayMillis = reminderDelayMillis,
            onTitleChange = { reminderTitle = it },
            onDelayChange = { reminderDelayMillis = it },
            onDismiss = { showReminderComposer = false },
            onSave = ::saveReminder,
        )
    }
    val checkInReminder = data.reminders.firstOrNull { it.id == completionReminderId && it.status == CookReminderEntity.STATUS_PENDING }
    if (showReminderCheckIn && checkInReminder != null) {
        ReminderCheckInDialog(
            reminder = checkInReminder,
            photoUri = reminderPhotoUri,
            photoCaption = reminderPhotoCaption,
            onPhotoCaptionChange = { reminderPhotoCaption = it },
            onDismiss = {
                viewModel.snoozeCookReminder(checkInReminder, 60L * 60 * 1000)
                reminderPhotoUri?.let { CameraPhotoFiles.delete(context, it) }
                reminderPhotoUri = null
                reminderPhotoCaption = ""
                showReminderCheckIn = false
                completionReminderId = null
            },
            onAddPhoto = {
                showReminderCheckIn = false
                photoSourceDestination = "reminder"
                showPhotoSource = true
            },
            onRemovePhoto = {
                reminderPhotoUri?.let { CameraPhotoFiles.delete(context, it) }
                reminderPhotoUri = null
                reminderPhotoCaption = ""
            },
            onComplete = { note ->
                val uri = reminderPhotoUri
                val caption = reminderPhotoCaption.takeIf { it.isNotBlank() }
                viewModel.completeCookReminder(checkInReminder, note, uri, caption) { eventId, photoAttached ->
                    if (!photoAttached && uri != null) pendingPhotoRetry = PhotoRetry(eventId, data.cook.id, null, uri, caption)
                    else pendingPhotoRetry = null
                }
                reminderPhotoUri = null
                reminderPhotoCaption = ""
                showReminderCheckIn = false
                completionReminderId = null
            },
        )
    }
    viewingPhoto?.let { photo ->
        PhotoViewerDialog(
            photo = photo,
            onDismiss = { viewingPhoto = null },
            onSave = { selected ->
                photoToSave = selected
                savePhoto.launch(selected.originalFileName.substringAfterLast('/').ifBlank { "cook-photo.jpg" })
            },
            onShare = ::sharePhoto,
        )
    }
    if (confirmDeleteCook) {
        AlertDialog(
            onDismissRequest = { confirmDeleteCook = false },
            title = { Text("Delete this cook?") },
            text = { Text("This removes the cook, its timeline, readings, results, and photos from this phone. Export a backup first if you want to keep a copy.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteCook(data.cook); confirmDeleteCook = false }) { Text("Delete cook") } },
            dismissButton = { TextButton(onClick = { confirmDeleteCook = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(data.cook.title, maxLines = 1); Text(statusLabel(data.cook.status), style = MaterialTheme.typography.bodyMedium) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to cooks") } },
                actions = { IconButton(onClick = { editCook = true }) { Icon(Icons.Filled.Edit, contentDescription = "Edit cook") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TabRow(selectedTabIndex = CookTab.entries.indexOfFirst { it.name == selectedTab }) {
                CookTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab.name,
                        onClick = {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            snackbar.currentSnackbarData?.dismiss()
                            selectedTab = tab.name
                        },
                        text = { Text(tab.label) },
                        modifier = Modifier.testTag("cook-tab-${tab.name.lowercase()}"),
                    )
                }
            }
            CookQuickActionsBar(
                onNote = { logComposerSession++; photoEntryMode = false; logPhotoUri = null; logPhotoCaption = ""; editEvent = null; showNewEvent = true },
                onTemperature = { showTemperature = true },
                onPhoto = { logComposerSession++; photoEntryMode = true; photoSourceDestination = "log"; showPhotoSource = true },
                onReminder = { showReminderComposer = true },
            )
            when (CookTab.valueOf(selectedTab)) {
                CookTab.LIVE -> LiveCookTab(
                    data = data,
                    busy = busy,
                    onTogglePause = { if (data.cook.status == CookStatus.PAUSED) viewModel.resumeCook(data.cook.id) else viewModel.pauseCook(data.cook.id) },
                    onAddTarget = { showTarget = true },
                    onDeleteTarget = viewModel::deleteTarget,
                    onAddDish = { addDish = true },
                    onEditDish = { editDish = it },
                    onResults = { showResults = true },
                    onExport = { showExportCook = true },
                    onFinish = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        showResults = true
                    },
                    onDelete = { confirmDeleteCook = true },
                    onCopySetup = { viewModel.duplicateCookSetup(data.cook.id) },
                    onError = error,
                    onCancelReminder = viewModel::cancelCookReminder,
                    onLogReminder = { reminder ->
                        completionReminderId = reminder.id
                        showReminderCheckIn = true
                    },
                    onPhotoClick = { viewingPhoto = it },
                    onViewTimeline = { selectedTab = CookTab.TIMELINE.name },
                )
                CookTab.TIMELINE -> TimelineTab(
                    data = data,
                    viewModel = viewModel,
                    onEditEvent = { editEvent = it },
                    onEditReading = { editReading = it },
                    onError = error,
                    onPhotoClick = { viewingPhoto = it },
                )
                CookTab.CHARTS -> ChartsTab(data)
            }
        }
    }
}

@Composable
private fun CookQuickActionsBar(
    onNote: () -> Unit,
    onTemperature: () -> Unit,
    onPhoto: () -> Unit,
    onReminder: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Log this cook", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Capture what happened while it’s fresh.", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onNote, modifier = Modifier.weight(1.25f).heightIn(min = 52.dp).testTag("timeline-add")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Add to log")
                    }
                }
                FilledTonalButton(onClick = onTemperature, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("timeline-add-temperature")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Thermostat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Temperature")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = onPhoto, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("timeline-add-photo")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Add photo")
                    }
                }
                OutlinedButton(onClick = onReminder, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("cook-add-reminder")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Set reminder")
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveCookTab(
    data: CookDetailData,
    busy: Boolean,
    onTogglePause: () -> Unit,
    onAddTarget: () -> Unit,
    onDeleteTarget: (com.pittech.data.TargetEntity) -> Unit,
    onAddDish: () -> Unit,
    onEditDish: (DishEntity) -> Unit,
    onResults: () -> Unit,
    onExport: () -> Unit,
    onFinish: () -> Unit,
    onDelete: () -> Unit,
    onCopySetup: () -> Unit,
    onError: String?,
    onCancelReminder: (CookReminderEntity) -> Unit,
    onLogReminder: (CookReminderEntity) -> Unit,
    onPhotoClick: (com.pittech.data.PhotoEntity) -> Unit,
    onViewTimeline: () -> Unit,
) {
    val elapsed = calculateElapsed(data)
    val hours = (elapsed.coerceAtLeast(0) / 3_600_000)
    val minutes = (elapsed.coerceAtLeast(0) / 60_000) % 60
    var showCookDetails by rememberSaveable(data.cook.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionCard("Cook summary") {
            Text("${hours}h ${minutes}m ${if (data.cook.status == CookStatus.PAUSED) "paused" else "elapsed"}", style = MaterialTheme.typography.headlineMedium)
            Text("Started ${formatTimestamp(data.cook.startedAtUtcMillis)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showCookDetails = !showCookDetails }, modifier = Modifier.heightIn(min = 44.dp)) { Text(if (showCookDetails) "Hide cook details" else "Cook details") }
            if (showCookDetails) {
                data.cook.smokerName?.let { Text("Smoker: $it", style = MaterialTheme.typography.bodyLarge) }
                data.cook.initialSetpointValue?.let { Text("Starting setpoint: $it ${data.cook.initialSetpointUnit.orEmpty()}", style = MaterialTheme.typography.bodyLarge) }
                data.cook.fuelType?.let { Text("Fuel: $it", style = MaterialTheme.typography.bodyLarge) }
                data.cook.woodOrPelletBlend?.let { Text("Wood or pellets: $it", style = MaterialTheme.typography.bodyLarge) }
                data.cook.outdoorTemperatureValue?.let { Text("Outdoor temperature: $it ${data.cook.outdoorTemperatureUnit.orEmpty()}", style = MaterialTheme.typography.bodyLarge) }
                data.cook.weatherNotes?.let { Text("Weather: $it", style = MaterialTheme.typography.bodyLarge) }
                data.cook.windNotes?.let { Text("Wind: $it", style = MaterialTheme.typography.bodyLarge) }
                data.cook.notes?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
            if (data.cook.status != CookStatus.COMPLETED) {
                OutlinedButton(onClick = onTogglePause, enabled = !busy, modifier = Modifier.heightIn(min = 46.dp)) {
                    Text(if (data.cook.status == CookStatus.PAUSED) "Resume cook" else "Pause cook")
                }
            }
        }

        SectionCard("Dishes (${data.dishes.size})") {
            if (data.dishes.isEmpty()) Text("No dishes yet. Add one now or later.", style = MaterialTheme.typography.bodyLarge)
            data.dishes.forEach { dish ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(dish.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(buildList {
                                add(dish.foodType)
                                dish.cut?.let(::add)
                                dish.weightValue?.let { add("$it ${dish.weightUnit.orEmpty()}") }
                                dish.startingCondition?.let(::add)
                                dish.placement?.let { add("Rack: $it") }
                                dish.gradeOrSource?.let { add(it) }
                            }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { onEditDish(dish) }) { Text("Edit") }
                    }
                    data.ingredients.filter { it.dishId == dish.id }.forEach { item ->
                        Text(
                            "${item.stage.replace('_', ' ').replaceFirstChar { it.titlecase() }}: ${item.name}" +
                                (item.brand?.let { " · $it" } ?: "") +
                                (item.amountValue?.let { " · $it ${item.amountUnit.orEmpty()}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    dish.prepNotes?.let { Text("Preparation notes: $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            OutlinedButton(onClick = onAddDish, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Add dish")
            }
        }

        SectionCard("Temperatures") {
            if (data.readings.isEmpty()) {
                Text("No temperature readings yet. Record a manual check to start a temperature history and chart.", style = MaterialTheme.typography.bodyLarge)
            } else {
                data.readings.groupBy { it.probeName }.forEach { (probe, values) ->
                    val latest = values.maxBy { it.measuredAtUtcMillis }
                    Text(probe, style = MaterialTheme.typography.titleMedium)
                    Text("${latest.value} ${latest.unit}", style = MaterialTheme.typography.headlineMedium)
                    Text("Manual reading · ${formatTimestamp(latest.measuredAtUtcMillis)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        val pendingReminders = data.reminders.filter { it.status == CookReminderEntity.STATUS_PENDING }
        if (pendingReminders.isNotEmpty()) {
            SectionCard("Reminders (${pendingReminders.size})") {
                pendingReminders.forEach { reminder ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(reminder.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("Around ${formatTimestamp(reminder.dueAtUtcMillis)}", style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { onLogReminder(reminder) }, modifier = Modifier.heightIn(min = 44.dp)) { Text("Log now") }
                        TextButton(onClick = { onCancelReminder(reminder) }, modifier = Modifier.heightIn(min = 44.dp)) { Text("Cancel") }
                    }
                }
            }
        }

        val recentRows = buildList {
            data.events.filter { it.eventType != "temperature" }.forEach { add(TimelineLine.Event(it)) }
            data.readings.forEach { add(TimelineLine.Reading(it)) }
        }.sortedBy { it.time }.takeLast(3).asReversed()
        SectionCard("Latest in the cook log") {
            if (recentRows.isEmpty()) {
                Text("Your notes, photos, and temperature checks will show here in order.", style = MaterialTheme.typography.bodyMedium)
            } else {
                recentRows.forEach { row ->
                    when (row) {
                        is TimelineLine.Event -> {
                            Text(row.event.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(formatTimestamp(row.event.occurredAtUtcMillis), style = MaterialTheme.typography.bodySmall)
                            data.photos.filter { it.eventId == row.event.id }.forEach { photo -> PhotoThumbnail(photo, modifier = Modifier.fillMaxWidth().height(120.dp), onClick = { onPhotoClick(photo) }) }
                        }
                        is TimelineLine.Reading -> Text("${row.reading.probeName}: ${row.reading.value} ${row.reading.unit} · ${formatTimestamp(row.reading.measuredAtUtcMillis)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (row != recentRows.last()) androidx.compose.material3.HorizontalDivider()
                }
                TextButton(onClick = onViewTimeline, modifier = Modifier.heightIn(min = 44.dp)) { Text("View full cook timeline") }
            }
        }



        SectionCard("Targets") {
            if (data.targets.isEmpty()) Text("Optional: record a personal finish goal or a food-safety target you are following.", style = MaterialTheme.typography.bodyLarge)
            data.targets.forEach { target ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(target.targetType.replace('_', ' ').replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.titleMedium)
                        Text("${target.value} ${target.unit}" + (target.explanation?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyLarge)
                    }
                    TextButton(onClick = { onDeleteTarget(target) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                }
            }
            Text("A recorded food-safety target is not a safety guarantee or cooking recommendation.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onAddTarget, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add target") }
        }

        SectionCard("Wrap up") {
            OutlinedButton(onClick = onResults, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (data.cook.status == CookStatus.COMPLETED) "Edit cook results" else "Record results") }
            if (data.cook.status == CookStatus.ACTIVE) {
                Button(onClick = onFinish, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("live-finish-cook")) { Text("Finish cook") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onExport, enabled = !busy, modifier = Modifier.heightIn(min = 44.dp)) { Text("Export") }
                TextButton(onClick = onCopySetup, enabled = !busy, modifier = Modifier.heightIn(min = 44.dp)) { Text("Reuse setup") }
                TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.heightIn(min = 44.dp)) { Text("Delete cook") }
            }
            Text("Reusing a setup starts a fresh cook log.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            onError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
        }

        if (data.results.isNotEmpty()) CookResultsCard(data)
        if (data.photos.isNotEmpty()) PhotoGallery(data.photos, onPhotoClick)
    }
}

@Composable
private fun TimelineTab(
    data: CookDetailData,
    viewModel: CooksViewModel,
    onEditEvent: (TimelineEventEntity) -> Unit,
    onEditReading: (SensorReadingEntity) -> Unit,
    onError: String?,
    onPhotoClick: (com.pittech.data.PhotoEntity) -> Unit,
) {
    var eventsOnly by rememberSaveable(data.cook.id) { mutableStateOf(false) }
    val rows = buildList {
        data.events.filter { it.eventType != "temperature" }.forEach { add(TimelineLine.Event(it)) }
        if (!eventsOnly) data.readings.forEach { add(TimelineLine.Reading(it)) }
    }.sortedBy { it.time }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Cook history", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            FilterChip(selected = !eventsOnly, onClick = { eventsOnly = false }, label = { Text("All entries") })
            FilterChip(selected = eventsOnly, onClick = { eventsOnly = true }, label = { Text("Events only") })
        }
        if (rows.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.Center) {
                Text("No cook history yet", style = MaterialTheme.typography.titleLarge)
                Text("Use the quick log buttons above to add a note, temperature, photo, or reminder. Entries will appear here in time order.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { row ->
                    when (row) {
                        is TimelineLine.Event -> TimelineEventCard(row.event, data, onEdit = { onEditEvent(row.event) }, onDelete = { viewModel.deleteTimelineEvent(row.event) }, onPhotoClick = onPhotoClick)
                        is TimelineLine.Reading -> TemperatureTimelineCard(row.reading, onEdit = { onEditReading(row.reading) }, onDelete = { viewModel.deleteSensorReading(row.reading) })
                    }
                }
                onError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

private sealed class TimelineLine(val time: Long) {
    data class Event(val event: TimelineEventEntity) : TimelineLine(event.occurredAtUtcMillis)
    data class Reading(val reading: SensorReadingEntity) : TimelineLine(reading.measuredAtUtcMillis)
}

@Composable
private fun TimelineEventCard(event: TimelineEventEntity, data: CookDetailData, onEdit: () -> Unit, onDelete: () -> Unit, onPhotoClick: (com.pittech.data.PhotoEntity) -> Unit) {
    val dishName = data.dishes.firstOrNull { it.id == event.dishId }?.name
    val photos = data.photos.filter { it.eventId == event.id }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${formatTimestamp(event.occurredAtUtcMillis)} · ${event.source.replaceFirstChar { it.uppercase() }}" + (dishName?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            event.details?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            photos.forEach { photo -> PhotoThumbnail(photo, onClick = { onPhotoClick(photo) }) }
            if (event.source == "manual") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp).testTag("timeline-event-edit-${event.eventType}")) { Icon(Icons.Filled.Edit, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Edit") }
                    TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp).testTag("timeline-event-delete-${event.eventType}")) { Icon(Icons.Filled.Delete, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun TemperatureTimelineCard(reading: SensorReadingEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${reading.probeName}: ${reading.value} ${reading.unit}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${formatTimestamp(reading.measuredAtUtcMillis)} · ${reading.measurementType.replace('_', ' ')} · ${reading.source}", style = MaterialTheme.typography.bodyMedium)
            if (reading.source == "manual") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp).testTag("temperature-edit-${reading.probeName}")) { Text("Edit") }
                TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp).testTag("temperature-delete-${reading.probeName}")) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun ChartsTab(data: CookDetailData) {
    val valid = data.readings.filter { it.qualityStatus == "valid" }
    val grouped = valid.groupBy { it.probeName }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard("Temperature chart") {
            if (valid.isEmpty()) {
                Text("Temperature charts will appear after you record readings. Manual readings work without a grill controller.", style = MaterialTheme.typography.bodyLarge)
            } else {
                TemperatureChart(valid, cookStartTimes = mapOf(data.cook.id to data.cook.startedAtUtcMillis))
                Text("Only valid saved readings are plotted. Missing periods are left blank.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        SectionCard("Reading summary") {
            if (grouped.isEmpty()) Text("No readings to summarize yet.", style = MaterialTheme.typography.bodyLarge)
            grouped.forEach { (probe, readings) ->
                val ordered = readings.sortedBy { it.measuredAtUtcMillis }
                val first = ordered.first()
                val last = ordered.last()
                val min = ordered.minOf { it.value }
                val max = ordered.maxOf { it.value }
                val hours = ((last.measuredAtUtcMillis - first.measuredAtUtcMillis).coerceAtLeast(1L)) / 3_600_000.0
                val rise = (last.value - first.value) / hours
                Text(probe, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${readings.size} readings · low ${min} · high ${max} · last ${last.value} ${last.unit}", style = MaterialTheme.typography.bodyLarge)
                Text("Average change from first to last: ${"%.1f".format(rise)} ${last.unit}/hour over recorded span", style = MaterialTheme.typography.bodyMedium)
            }
        }
        val duration = calculateElapsed(data)
        SectionCard("Cook timing") { Text("${duration / 3_600_000}h ${(duration / 60_000) % 60}m recorded", style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun CookResultsCard(data: CookDetailData) {
    SectionCard("Cook results") {
        data.results.forEach { result ->
            val label = result.resultType.replace('_', ' ').replaceFirstChar { it.titlecase() }
            val value = result.numericValue?.let { "$it ${result.unit.orEmpty()}" } ?: result.textValue.orEmpty()
            Text("$label: $value", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun PhotoGallery(photos: List<com.pittech.data.PhotoEntity>, onPhotoClick: (com.pittech.data.PhotoEntity) -> Unit) {
    SectionCard("Photos (${photos.size})") {
        photos.forEach { photo ->
            PhotoThumbnail(photo, onClick = { onPhotoClick(photo) })
            Text(photo.caption ?: photo.originalFileName, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PhotoThumbnail(
    photo: com.pittech.data.PhotoEntity,
    modifier: Modifier = Modifier.fillMaxWidth().height(190.dp),
    onClick: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember(photo.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(photo.id) {
        val decoded = withContext(Dispatchers.IO) {
            val file = java.io.File(context.filesDir, photo.relativePath)
            if (!file.isFile) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > 900 || bounds.outHeight / sampleSize > 900) sampleSize *= 2
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                },
            )
        }
        bitmap = decoded?.asImageBitmap()
    }
    bitmap?.let { Image(it, contentDescription = photo.caption ?: photo.originalFileName, modifier = modifier.clickable(onClick = onClick), contentScale = ContentScale.Crop) }
        ?: Text("Photo saved · ${photo.originalFileName}", style = MaterialTheme.typography.bodyMedium, modifier = modifier.clickable(onClick = onClick))
}

@Composable
private fun PhotoViewerDialog(
    photo: com.pittech.data.PhotoEntity,
    onDismiss: () -> Unit,
    onSave: (com.pittech.data.PhotoEntity) -> Unit,
    onShare: (com.pittech.data.PhotoEntity) -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(photo.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var scale by remember(photo.id) { mutableStateOf(1f) }
    var offsetX by remember(photo.id) { mutableStateOf(0f) }
    var offsetY by remember(photo.id) { mutableStateOf(0f) }
    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        if (scale == 1f) {
            offsetX = 0f
            offsetY = 0f
        } else {
            offsetX += pan.x
            offsetY += pan.y
        }
    }
    LaunchedEffect(photo.id) {
        val decoded = withContext(Dispatchers.IO) {
            val file = java.io.File(context.filesDir, photo.relativePath)
            if (!file.isFile) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > 2000 || bounds.outHeight / sampleSize > 2000) sampleSize *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            })
        }
        bitmap = decoded?.asImageBitmap()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = onDismiss) { Text("×", color = Color.White, style = MaterialTheme.typography.headlineMedium) }
                    Text(photo.caption ?: photo.originalFileName, color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { onSave(photo) }) { Text("Save", color = Color.White) }
                    TextButton(onClick = { onShare(photo) }) { Text("Share", color = Color.White) }
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth().transformable(transformState),
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let {
                        Image(
                            it,
                            contentDescription = photo.caption ?: photo.originalFileName,
                            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
                            contentScale = ContentScale.Fit,
                        )
                    } ?: Text("Photo could not be loaded", color = Color.White)
                }
                Text("Pinch to zoom · Drag to move", color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp))
            }
        }
    }
}

@Composable
private fun ReminderComposerDialog(
    title: String,
    delayMillis: Long,
    onTitleChange: (String) -> Unit,
    onDelayChange: (Long) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val options = listOf(30L * 60 * 1000 to "30 min", 60L * 60 * 1000 to "1 hour", 2L * 60 * 60 * 1000 to "2 hours", 5L * 60 * 60 * 1000 to "5 hours")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a cook reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = onTitleChange, label = { Text("What should you check?") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("reminder-title"))
                Text("Remind me in", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.take(2).forEach { (millis, label) -> FilterChip(selected = delayMillis == millis, onClick = { onDelayChange(millis) }, label = { Text(label) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.drop(2).forEach { (millis, label) -> FilterChip(selected = delayMillis == millis, onClick = { onDelayChange(millis) }, label = { Text(label) }) }
                }
                Text("Notifications may arrive a little after the selected time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = title.isNotBlank(), modifier = Modifier.testTag("reminder-save")) { Text("Set reminder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReminderCheckInDialog(
    reminder: CookReminderEntity,
    photoUri: String?,
    photoCaption: String,
    onPhotoCaptionChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onAddPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onComplete: (String) -> Unit,
) {
    var note by rememberSaveable(reminder.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How did it go?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(reminder.title)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Add a note") },
                    placeholder = { Text("Checked the grill, added wood…") },
                    modifier = Modifier.fillMaxWidth().testTag("reminder-checkin-note"),
                )
                OutlinedButton(onClick = onAddPhoto, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (photoUri == null) "Add a photo to this check-in" else "Change attached photo")
                }
                if (photoUri != null) {
                    Text("Photo ready to save with this check-in.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    OutlinedTextField(
                        value = photoCaption,
                        onValueChange = onPhotoCaptionChange,
                        label = { Text("Photo caption (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("reminder-checkin-photo-caption"),
                    )
                    TextButton(onClick = onRemovePhoto) { Text("Remove photo") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onComplete(note) }, modifier = Modifier.testTag("reminder-checkin-save")) { Text("Log check-in") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Remind me in 1 hour") } },
    )
}

@Composable
private fun CookBasicsDialog(data: CookDetailData, onDismiss: () -> Unit, onSave: (String, String, String, String, String, String, String, String, String, String, String) -> Unit) {
    var title by rememberSaveable(data.cook.id) { mutableStateOf(data.cook.title) }
    var smoker by rememberSaveable(data.cook.id) { mutableStateOf(data.cook.smokerName.orEmpty()) }
    var setpoint by rememberSaveable(data.cook.id) { mutableStateOf(data.cook.initialSetpointValue?.toString().orEmpty()) }
    var unit by rememberSaveable(data.cook.id) { mutableStateOf(data.cook.initialSetpointUnit ?: "°F") }
    var notes by rememberSaveable(data.cook.id) { mutableStateOf(data.cook.notes.orEmpty()) }
    var fuel by rememberSaveable(data.cook.id) { mutableStateOf(data.cook.fuelType.orEmpty()) }
    var wood by rememberSaveable(data.cook.id) { mutableStateOf(data.cook.woodOrPelletBlend.orEmpty()) }
    var outdoor by rememberSaveable(data.cook.id) { mutableStateOf(data.cook.outdoorTemperatureValue?.toString().orEmpty()) }
    var outdoorUnit by rememberSaveable(data.cook.id) { mutableStateOf(data.cook.outdoorTemperatureUnit ?: "°F") }
    var weather by rememberSaveable(data.cook.id) { mutableStateOf(data.cook.weatherNotes.orEmpty()) }
    var wind by rememberSaveable(data.cook.id) { mutableStateOf(data.cook.windNotes.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit cook details") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Cook name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(smoker, { smoker = it }, label = { Text("Smoker or grill") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(setpoint, { setpoint = it }, label = { Text("Starting setpoint") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                SimpleDropdownField("Setpoint unit", unit, listOf("°F", "°C"), { unit = it })
                OutlinedTextField(notes, { notes = it }, label = { Text("Cook notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fuel, { fuel = it }, label = { Text("Fuel type") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(wood, { wood = it }, label = { Text("Wood or pellet blend") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(outdoor, { outdoor = it }, label = { Text("Outdoor temperature") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    SimpleDropdownField("Unit", outdoorUnit, listOf("°F", "°C"), { outdoorUnit = it }, modifier = Modifier.width(96.dp))
                }
                OutlinedTextField(weather, { weather = it }, label = { Text("Weather") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(wind, { wind = it }, label = { Text("Wind or exposure") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { if (CookEntryValidation.isOptionalPositiveNumberValid(setpoint) && CookEntryValidation.isOptionalFiniteNumberValid(outdoor)) onSave(title, smoker, setpoint, unit, notes, fuel, wood, outdoor, outdoorUnit, weather, wind) }) { Text("Save details") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DishDetailsDialog(dish: DishEntity, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (DishEntity) -> Unit) {
    var name by rememberSaveable(dish.id) { mutableStateOf(dish.name) }
    var foodType by rememberSaveable(dish.id) { mutableStateOf(dish.foodType) }
    var cut by rememberSaveable(dish.id) { mutableStateOf(dish.cut.orEmpty()) }
    var weight by rememberSaveable(dish.id) { mutableStateOf(dish.weightValue?.toString().orEmpty()) }
    var weightUnit by rememberSaveable(dish.id) { mutableStateOf(dish.weightUnit ?: "lb") }
    var condition by rememberSaveable(dish.id) { mutableStateOf(dish.startingCondition ?: "Not set") }
    var boneIn by rememberSaveable(dish.id) { mutableStateOf(dish.boneIn) }
    var placement by rememberSaveable(dish.id) { mutableStateOf(dish.placement.orEmpty()) }
    var gradeOrSource by rememberSaveable(dish.id) { mutableStateOf(dish.gradeOrSource.orEmpty()) }
    var thicknessNotes by rememberSaveable(dish.id) { mutableStateOf(dish.thicknessNotes.orEmpty()) }
    var notes by rememberSaveable(dish.id) { mutableStateOf(dish.prepNotes.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${dish.name}") },
        text = {
            Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Dish name") }, modifier = Modifier.fillMaxWidth())
                SimpleDropdownField("Food type", foodType, listOf("Beef", "Pork", "Poultry", "Seafood", "Wild game", "Vegetables", "Other"), { foodType = it })
                OutlinedTextField(cut, { cut = it }, label = { Text("Cut") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(weight, { weight = it }, label = { Text("Weight") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    SimpleDropdownField("Unit", weightUnit, listOf("lb", "oz", "kg", "g"), { weightUnit = it }, modifier = Modifier.width(100.dp))
                }
                SimpleDropdownField("Starting condition", condition, listOf("Not set", "Refrigerated", "Thawed", "Frozen", "Other"), { condition = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = boneIn == true, onClick = { boneIn = if (boneIn == true) null else true }, label = { Text("Bone-in") })
                    FilterChip(selected = boneIn == false, onClick = { boneIn = if (boneIn == false) null else false }, label = { Text("Boneless") })
                }
                OutlinedTextField(placement, { placement = it }, label = { Text("Smoker position") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(gradeOrSource, { gradeOrSource = it }, label = { Text("Grade, brand, or source") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(thicknessNotes, { thicknessNotes = it }, label = { Text("Size or thickness notes") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("Preparation notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && CookEntryValidation.isOptionalPositiveNumberValid(weight)) {
                    onSave(dish.copy(name=name.trim(), foodType=foodType, cut=cut.trim().ifBlank { null }, weightValue=CookEntryValidation.optionalPositiveNumber(weight), weightUnit=weightUnit.takeIf { weight.isNotBlank() }, startingCondition=condition.takeUnless { it == "Not set" }, boneIn=boneIn, placement=placement.trim().ifBlank { null }, gradeOrSource=gradeOrSource.trim().ifBlank { null }, thicknessNotes=thicknessNotes.trim().ifBlank { null }, prepNotes=notes.trim().ifBlank { null }))
                }
            }) { Text("Save dish") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete dish") }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun TimelineEventDialog(
    event: TimelineEventEntity?,
    dishes: List<DishEntity>,
    draftKey: Int,
    photoEntryMode: Boolean,
    photoUri: String?,
    photoCaption: String,
    onChoosePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onPhotoCaptionChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, String?, Long, String?) -> Unit,
) {
    val dialogFocusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var type by rememberSaveable(draftKey, event?.id) { mutableStateOf(event?.eventType ?: if (photoEntryMode) "photo" else "note") }
    var title by rememberSaveable(draftKey, event?.id) { mutableStateOf(event?.title ?: if (photoEntryMode) "Photo added" else "Cook note") }
    var details by rememberSaveable(draftKey, event?.id) { mutableStateOf(event?.details.orEmpty()) }
    var time by rememberSaveable(draftKey, event?.id) { mutableStateOf(event?.occurredAtUtcMillis ?: System.currentTimeMillis()) }
    val defaultDishId = dishes.singleOrNull()?.id ?: "whole"
    var dishId by rememberSaveable(draftKey, event?.id) { mutableStateOf(event?.dishId ?: defaultDishId) }
    var showMoreDetails by rememberSaveable(draftKey, event?.id) { mutableStateOf(event != null) }
    val choices = listOf("Meat on", "Spritz", "Wrap", "Flip", "Temperature check", "Fuel added", "Move", "Remove", "Rest", "Finish", "Photo", "Note", "Custom")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (event == null) "Add to cook log" else "Edit timeline entry") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (event != null || showMoreDetails) {
                    OutlinedTextField(title, { title = it }, label = { Text("Entry title") }, placeholder = { Text("Spritzed the meat, added fuel…") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("timeline-entry-title"))
                }
                OutlinedTextField(
                    details,
                    { details = it },
                    label = { Text(if (event == null) "Write a note (optional)" else "Notes or details") },
                    placeholder = { if (event == null) Text("What happened with the cook?") },
                    minLines = if (event == null) 3 else 2,
                    modifier = Modifier.fillMaxWidth().testTag("timeline-entry-details"),
                )
                if (event == null) {
                    OutlinedButton(onClick = onChoosePhoto, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (photoUri == null) "Attach a photo (optional)" else "Change attached photo")
                    }
                    if (photoUri != null) {
                        Text("Photo will be saved with this log entry.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                        OutlinedTextField(
                            value = photoCaption,
                            onValueChange = onPhotoCaptionChange,
                            label = { Text("Photo caption (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("timeline-photo-caption"),
                        )
                        TextButton(onClick = onRemovePhoto) { Text("Remove photo") }
                    }
                }
                TextButton(onClick = { showMoreDetails = !showMoreDetails }, modifier = Modifier.testTag("timeline-entry-more-details")) {
                    Text(if (showMoreDetails) "Hide entry details" else "Add date, dish, or entry type")
                }
                if (showMoreDetails) {
                    SimpleDropdownField("Entry type", displayEventType(type), choices, { selected ->
                        type = selected.lowercase().replace(' ', '_')
                        if (title.isBlank() || title == event?.title || title == "Cook note" || title == "Photo added") {
                            title = if (selected == "Photo") "Photo added" else selected
                        }
                    }, testTag = "timeline-entry-type")
                    SimpleDropdownField("For", dishId.takeUnless { it == "whole" }?.let { id -> dishes.firstOrNull { it.id == id }?.name } ?: "Whole cook", listOf("Whole cook") + dishes.map { it.name }, { selected -> dishId = dishes.firstOrNull { it.name == selected }?.id ?: "whole" })
                    DateTimePickerField("Occurred at", time, { time = it }, "timeline-entry-time")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isNotBlank()) {
                    dialogFocusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onSave(type, title.trim(), details.trim().ifBlank { null }, time, dishId.takeUnless { it == "whole" })
                }
            }, enabled = event != null || type != "note" || details.isNotBlank() || photoUri != null, modifier = Modifier.testTag("timeline-entry-save")) { Text("Save entry") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TemperatureEntryDialog(
    reading: SensorReadingEntity?,
    dishes: List<DishEntity>,
    preferredUnit: String,
    onDismiss: () -> Unit,
    onSave: (String, String, Double, String, Long, String?) -> Unit,
) {
    val dialogFocusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var probe by rememberSaveable(reading?.id) { mutableStateOf(reading?.probeName ?: "Food probe") }
    var value by rememberSaveable(reading?.id) { mutableStateOf(reading?.value?.toString().orEmpty()) }
    var type by rememberSaveable(reading?.id) { mutableStateOf(reading?.measurementType ?: "food_probe") }
    var unit by rememberSaveable(reading?.id) { mutableStateOf(reading?.unit ?: preferredUnit) }
    var time by rememberSaveable(reading?.id) { mutableStateOf(reading?.measuredAtUtcMillis ?: System.currentTimeMillis()) }
    val defaultDishId = dishes.singleOrNull()?.id ?: "whole"
    var dishId by rememberSaveable(reading?.id) { mutableStateOf(reading?.dishId ?: defaultDishId) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (reading == null) "Record temperature" else "Edit temperature") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(probe, { probe = it }, label = { Text("Probe or location") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("temperature-probe"))
                OutlinedTextField(value, { value = it; error = null }, label = { Text("Temperature") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, isError = error != null, supportingText = { error?.let { Text(it) } }, modifier = Modifier.fillMaxWidth().testTag("temperature-value"))
                SimpleDropdownField("Measurement", type.replace('_', ' ').replaceFirstChar { it.titlecase() }, listOf("Food probe", "Pit ambient", "Setpoint", "Other"), { type = it.lowercase().replace(' ', '_') })
                SimpleDropdownField("Unit", unit, listOf("°F", "°C"), { unit = it })
                SimpleDropdownField("For", dishId.takeUnless { it == "whole" }?.let { id -> dishes.firstOrNull { it.id == id }?.name } ?: "Whole cook", listOf("Whole cook") + dishes.map { it.name }, { selected -> dishId = dishes.firstOrNull { it.name == selected }?.id ?: "whole" })
                DateTimePickerField("Measured at", time, { time = it }, "temperature-time")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsedValue = value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
                if (probe.isBlank()) error = "Enter a probe or location name."
                else if (parsedValue == null) error = "Enter a valid temperature."
                else {
                    dialogFocusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onSave(probe.trim(), type, parsedValue, unit, time, dishId.takeUnless { it == "whole" })
                }
            }, modifier = Modifier.testTag("temperature-save")) { Text("Save temperature") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DateTimePickerField(label: String, millis: Long, onChange: (Long) -> Unit, testTag: String) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val current = Calendar.getInstance().apply { timeInMillis = millis }
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    val selectedDate = Calendar.getInstance().apply {
                        timeInMillis = millis
                        set(Calendar.YEAR, year)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, day)
                    }
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            selectedDate.set(Calendar.HOUR_OF_DAY, hour)
                            selectedDate.set(Calendar.MINUTE, minute)
                            selectedDate.set(Calendar.SECOND, 0)
                            selectedDate.set(Calendar.MILLISECOND, 0)
                            onChange(selectedDate.timeInMillis)
                        },
                        current.get(Calendar.HOUR_OF_DAY),
                        current.get(Calendar.MINUTE),
                        android.text.format.DateFormat.is24HourFormat(context),
                    ).show()
                },
                current.get(Calendar.YEAR),
                current.get(Calendar.MONTH),
                current.get(Calendar.DAY_OF_MONTH),
            ).show()
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(testTag),
    ) {
        Text("$label · ${formatTimestamp(millis)}")
    }
}

@Composable
private fun TargetDialog(
    dishes: List<DishEntity>,
    preferredUnit: String,
    onDismiss: () -> Unit,
    onSave: (String?, String, Double, String, String?) -> Unit,
) {
    var type by rememberSaveable { mutableStateOf("personal_finish") }
    var value by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(preferredUnit) }
    var explanation by rememberSaveable { mutableStateOf("") }
    var dishId by rememberSaveable { mutableStateOf("whole") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a cook target") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SimpleDropdownField("Target type", type.replace('_', ' ').replaceFirstChar { it.titlecase() }, listOf("Personal finish", "Food safety", "Serving goal"), { type = it.lowercase().replace(' ', '_') })
                OutlinedTextField(value, { value = it; error = null }, label = { Text("Target temperature") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = error != null, supportingText = { error?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
                SimpleDropdownField("Unit", unit, listOf("°F", "°C"), { unit = it })
                SimpleDropdownField("For", dishId.takeUnless { it == "whole" }?.let { id -> dishes.firstOrNull { it.id == id }?.name } ?: "Whole cook", listOf("Whole cook") + dishes.map { it.name }, { selected -> dishId = dishes.firstOrNull { it.name == selected }?.id ?: "whole" })
                OutlinedTextField(explanation, { explanation = it }, label = { Text("Source or reason (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Text("PitTech stores this as your note. It does not decide whether food is safe or done.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val number = CookEntryValidation.optionalPositiveNumber(value)
                if (number == null) error = "Enter a positive temperature."
                else onSave(dishId.takeUnless { it == "whole" }, type, number, unit, explanation.trim().ifBlank { null })
            }) { Text("Save target") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ResultsDialog(
    data: CookDetailData,
    preferredUnit: String,
    onDismiss: () -> Unit,
    onSave: (String?, String, String, String, Map<String, String>, String, Boolean) -> Unit,
) {
    val dialogFocusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val firstDishId = data.dishes.firstOrNull()?.id ?: "whole"
    var selectedDishId by rememberSaveable(data.cook.id) { mutableStateOf(firstDishId) }
    val selectedDishKey = selectedDishId.takeUnless { it == "whole" }
    val existing = data.results.filter { it.dishId == selectedDishKey }
    fun savedNumber(type: String) = existing.firstOrNull { it.resultType == type }?.numericValue?.toString().orEmpty()
    var finalTemp by rememberSaveable(data.cook.id, selectedDishId) { mutableStateOf(savedNumber("final_temperature")) }
    var unit by rememberSaveable(data.cook.id, selectedDishId) { mutableStateOf(existing.firstOrNull { it.resultType == "final_temperature" }?.unit ?: preferredUnit) }
    var rest by rememberSaveable(data.cook.id, selectedDishId) { mutableStateOf(savedNumber("rest_minutes")) }
    var rating by rememberSaveable(data.cook.id, selectedDishId) { mutableStateOf(savedNumber("overall_rating")) }
    var bark by rememberSaveable(data.cook.id, selectedDishId) { mutableStateOf(savedNumber("bark")) }
    var tenderness by rememberSaveable(data.cook.id, selectedDishId) { mutableStateOf(savedNumber("tenderness")) }
    var juiciness by rememberSaveable(data.cook.id, selectedDishId) { mutableStateOf(savedNumber("juiciness")) }
    var smoke by rememberSaveable(data.cook.id, selectedDishId) { mutableStateOf(savedNumber("smoke")) }
    var seasoning by rememberSaveable(data.cook.id, selectedDishId) { mutableStateOf(savedNumber("seasoning")) }
    var detailRatings by rememberSaveable(data.cook.id, selectedDishId) { mutableStateOf(false) }
    var notes by rememberSaveable(data.cook.id, selectedDishId) { mutableStateOf(existing.firstOrNull { it.resultType == "result_notes" }?.textValue.orEmpty()) }
    var finish by rememberSaveable(data.cook.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (data.cook.status == CookStatus.ACTIVE) "Record results" else "Edit results", modifier = Modifier.testTag("results-dialog-title")) },
        text = {
            Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("All result fields are optional.", style = MaterialTheme.typography.bodyMedium)
                if (data.dishes.isNotEmpty()) SimpleDropdownField("Results for", selectedDishKey?.let { key -> data.dishes.firstOrNull { it.id == key }?.name } ?: "Whole cook", listOf("Whole cook") + data.dishes.map { it.name }, { selectedName -> selectedDishId = data.dishes.firstOrNull { dish -> dish.name == selectedName }?.id ?: "whole" })
                OutlinedTextField(finalTemp, { finalTemp = it; error = null }, label = { Text("Final temperature (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("result-final-temperature"))
                SimpleDropdownField("Temperature unit", unit, listOf("°F", "°C"), { unit = it })
                OutlinedTextField(rest, { rest = it; error = null }, label = { Text("Rest time in minutes") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(rating, { rating = it; error = null }, label = { Text("Overall rating (1–5)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { detailRatings = !detailRatings }) { Text(if (detailRatings) "Hide detailed ratings" else "Rate bark, tenderness, and more") }
                if (detailRatings) {
                    OutlinedTextField(bark, { bark = it; error = null }, label = { Text("Bark (1–5)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(tenderness, { tenderness = it; error = null }, label = { Text("Tenderness (1–5)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(juiciness, { juiciness = it; error = null }, label = { Text("Juiciness (1–5)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(smoke, { smoke = it; error = null }, label = { Text("Smoke level (1–5)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(seasoning, { seasoning = it; error = null }, label = { Text("Seasoning (1–5)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(notes, { notes = it }, label = { Text("What would you change next time?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                if (data.cook.status != CookStatus.COMPLETED) FilterChip(
                    selected = finish,
                    onClick = { finish = !finish },
                    label = { Text(if (finish) "Finish cook when saved" else "Also finish this cook") },
                    modifier = Modifier.testTag("results-finish-toggle"),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val allRatings = listOf(rating, bark, tenderness, juiciness, smoke, seasoning)
                val valid = CookEntryValidation.isOptionalPositiveNumberValid(finalTemp) && CookEntryValidation.isOptionalPositiveNumberValid(rest) &&
                    allRatings.all { it.isBlank() || CookEntryValidation.optionalPositiveNumber(it)?.let { number -> number in 1.0..5.0 } == true }
                if (!valid) error = "Use positive values; ratings must be from 1 to 5."
                else {
                    dialogFocusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onSave(selectedDishKey, finalTemp, unit, rest, mapOf("overall_rating" to rating, "bark" to bark, "tenderness" to tenderness, "juiciness" to juiciness, "smoke" to smoke, "seasoning" to seasoning), notes, finish)
                }
            }, modifier = Modifier.testTag("results-save")) { Text("Save results") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun displayEventType(type: String): String = type.replace('_', ' ').replaceFirstChar { it.titlecase() }

private fun formatTimestamp(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private fun safeFileName(value: String): String = value.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(Regex("\\s+"), "_").ifBlank { "PitTech_Cook" }

private fun calculateElapsed(data: CookDetailData): Long {
    var total = 0L
    var runningFrom = data.cook.startedAtUtcMillis
    var isRunning = true
    val end = data.cook.endedAtUtcMillis ?: System.currentTimeMillis()
    data.events.sortedBy { it.occurredAtUtcMillis }.forEach { event ->
        when (event.eventType) {
            "cook_paused" -> if (isRunning) {
                total += (event.occurredAtUtcMillis - runningFrom).coerceAtLeast(0L)
                isRunning = false
            }
            "cook_resumed" -> if (!isRunning) {
                runningFrom = event.occurredAtUtcMillis
                isRunning = true
            }
        }
    }
    if (isRunning) total += (end - runningFrom).coerceAtLeast(0L)
    return total
}

private fun statusLabel(status: String): String = when (status) {
    CookStatus.PAUSED -> "Paused"
    CookStatus.COMPLETED -> "Finished"
    else -> "In progress"
}
