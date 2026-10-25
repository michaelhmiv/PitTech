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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import com.pittech.data.AlignedGuide
import com.pittech.data.CookReminderEntity
import com.pittech.data.CookStatus
import com.pittech.data.DishEntity
import com.pittech.data.SensorReadingEntity
import com.pittech.data.TimelineEventEntity
import com.pittech.domain.CookEntryValidation
import com.pittech.domain.DishDraft
import com.pittech.domain.IngredientDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale

private enum class CookTab(val label: String) { LIVE("Live"), TIMELINE("Timeline"), CHARTS("Charts") }
private data class PhotoRetry(val eventId: String, val cookId: String, val dishId: String?, val uri: String, val caption: String?, val useCaptureTime: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookDetailScreen(
    data: CookDetailData,
    viewModel: CooksViewModel,
    preferredTemperatureUnit: String,
    preferredWeightUnit: String,
    onBack: () -> Unit,
) {
    val grillState by viewModel.grillState.collectAsStateWithLifecycle()
    var showConnections by rememberSaveable { mutableStateOf(false) }
    var sharingDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    var resultsDishId by rememberSaveable { mutableStateOf<String?>(null) }
    val recordingRunning by viewModel.recordingServiceRunning.collectAsStateWithLifecycle()
    var noteDraftJson by rememberSaveable(data.cook.id) { mutableStateOf<String?>(null) }
    var noteEditing by rememberSaveable(data.cook.id) { mutableStateOf(false) }
    var notePhotoFirst by rememberSaveable(data.cook.id) { mutableStateOf(false) }
    var focusedTimelineId by rememberSaveable(data.cook.id) { mutableStateOf<String?>(null) }
    var quickEventType by rememberSaveable { mutableStateOf("note") }
    var viewingMarker by remember { mutableStateOf<TimelineEventEntity?>(null) }
    var selectedTab by rememberSaveable(data.cook.id) { mutableStateOf(CookTab.LIVE.name) }
    var editCook by remember { mutableStateOf(false) }
    var addDish by remember { mutableStateOf(false) }
    var editDish by remember { mutableStateOf<DishEntity?>(null) }
    var ingredientDish by remember { mutableStateOf<DishEntity?>(null) }
    var dishToDelete by remember { mutableStateOf<DishEntity?>(null) }
    var editEvent by remember { mutableStateOf<TimelineEventEntity?>(null) }
    var showNewEvent by remember { mutableStateOf(false) }
    var showTarget by remember { mutableStateOf(false) }
    var showResults by rememberSaveable(data.cook.id) { mutableStateOf(false) }
    val reminderRules by viewModel.reminders.observeRules().collectAsStateWithLifecycle(emptyList())
    val guideFlow = remember(data.cook.id) { viewModel.guide.observe(data.cook.id) }
    val guideRecords by guideFlow.collectAsStateWithLifecycle(emptyList())
    val reminderOccurrences by viewModel.reminders.observeOccurrences().collectAsStateWithLifecycle(emptyList())
    val liveCompanionRecords by viewModel.companionRecords.collectAsStateWithLifecycle(emptyList())
    val liveActivityTimers by remember(data.cook.id) { viewModel.activities.observe(data.cook.id) }.collectAsStateWithLifecycle(emptyList())
    val liveSummaries = remember(data.cook.id, liveCompanionRecords, liveActivityTimers) {
        val plan = liveCompanionRecords.firstOrNull { it.kind == "plan" && it.cookId == data.cook.id }
        val alertRecords = liveCompanionRecords.filter { it.kind == "alert" && it.cookId == data.cook.id }
        val alertSummary = if (alertRecords.isEmpty()) "Not set up" else {
            val enabled = alertRecords.count { com.pittech.domain.CookAlertEngine.decode(it.payload).first.enabled }
            val attention = alertRecords.count { com.pittech.domain.CookAlertEngine.decode(it.payload).second.active }
            "$enabled enabled" + if (attention > 0) " · $attention need attention" else ""
        }
        val checklistRecord = liveCompanionRecords.firstOrNull { it.kind == "checklist" && it.cookId == data.cook.id }
        val checklistSummary = checklistRecord?.let { record ->
            val items = com.pittech.domain.CookPreparation.decodeChecklist(record.payload)
            "${items.count { it.done }}/${items.size} done"
        } ?: "Not started"
        val runningTimers = liveActivityTimers.count { it.status == "running" }
        LivePanelSummaries(
            guidance = plan?.let { "Playbook · ${it.title}" } ?: "No playbook",
            alerts = alertSummary,
            checklist = checklistSummary,
            timers = if (runningTimers > 0) "$runningTimers running" else "None running",
        )
    }
    // PL-03: the alert line shown on the Live Zone strip — the needs-attention
    // count plus named acknowledgments, readable without opening anything.
    val alertStrip = remember(data.cook.id, liveCompanionRecords) {
        val alertRecords = liveCompanionRecords.filter { it.kind == "alert" && it.cookId == data.cook.id }
        com.pittech.domain.CookAlertEngine.stripSummary(alertRecords.map { record ->
            record.title to com.pittech.domain.CookAlertEngine.decode(record.payload).second
        })
    }
    var selectedFood by rememberSaveable(data.cook.id) { mutableStateOf<String?>(null) }
    var showProbeMove by remember { mutableStateOf(false) }
    var showFinishConfirm by remember { mutableStateOf(false) }
    var showCompletionChoices by rememberSaveable(data.cook.id) { mutableStateOf(false) }
    var confirmDeleteCook by remember { mutableStateOf(false) }
    var showExportCook by remember { mutableStateOf(false) }
    var showPhotoSource by rememberSaveable { mutableStateOf(false) }
    var preparingPhoto by remember { mutableStateOf(false) }
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
    var completionReminder by remember { mutableStateOf<CookReminderEntity?>(null) }
    var showReminderCheckIn by remember { mutableStateOf(false) }
    var pendingPhotoRetry by remember { mutableStateOf<PhotoRetry?>(null) }
    var viewingPhoto by remember { mutableStateOf<com.pittech.data.PhotoEntity?>(null) }
    var showPhotoGallery by rememberSaveable(data.cook.id) { mutableStateOf(false) }
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
    val pendingReminderRuleId by viewModel.pendingReminderRuleId.collectAsStateWithLifecycle()
    val pendingAlertId by viewModel.pendingAlertId.collectAsStateWithLifecycle()
    var alertFocusId by rememberSaveable(data.cook.id) { mutableStateOf<String?>(null) }
    var ruleCheckIn by remember { mutableStateOf<com.pittech.data.ReminderRule?>(null) }
    var ruleCheckInAnnounced by remember { mutableStateOf<String?>(null) }
    var ruleRecord by remember { mutableStateOf<com.pittech.data.ReminderRule?>(null) }
    var ruleRecordAnnounced by remember { mutableStateOf<String?>(null) }
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
        val reminder = data.reminders.firstOrNull { it.id == id && it.status == CookReminderEntity.STATUS_PENDING }
        if (reminder != null) {
            completionReminder = reminder
            showReminderCheckIn = true
        }
    }

    // PL-02: a unified-reminder notification tap opens THAT rule's check-in
    // here — hoisted above the (collapsible) Reminders panel so the route
    // cannot be hidden by a collapsed panel.
    LaunchedEffect(pendingReminderRuleId, data.cook.id) {
        val id = pendingReminderRuleId ?: return@LaunchedEffect
        val rule = viewModel.reminders.rule(id)
        if (rule != null && rule.cookId == data.cook.id) {
            ruleCheckInAnnounced = viewModel.reminderAnnouncedAction(id)
            ruleCheckIn = rule
        }
        viewModel.consumeReminderRuleCheckIn()
    }

    // PL-03: an alert notification tap opens the Alerts sheet at THAT rule —
    // hoisted above the (collapsible) Alerts panel so the route cannot be
    // hidden by a collapsed panel.
    LaunchedEffect(pendingAlertId, data.cook.id) {
        val id = pendingAlertId ?: return@LaunchedEffect
        alertFocusId = id
        viewModel.consumeAlertFocus()
    }
    ruleCheckIn?.let { rule ->
        ReminderRuleCheckInDialog(rule, ruleCheckInAnnounced, busy,
            onLogIt = { action -> coroutineScope.launch { viewModel.confirmReminderAction(rule.id, action) }; ruleCheckIn = null },
            onLogOther = { ruleRecordAnnounced = ruleCheckInAnnounced; ruleRecord = rule; ruleCheckIn = null },
            onSnooze = { coroutineScope.launch { viewModel.changeReminderRule(rule.id, "snooze") }; ruleCheckIn = null },
            onDismiss = { coroutineScope.launch { viewModel.changeReminderRule(rule.id, "complete") }; ruleCheckIn = null },
            onClose = { ruleCheckIn = null })
    }
    ruleRecord?.let { rule ->
        ReminderRecordActionDialog(rule, ruleRecordAnnounced, busy,
            onConfirm = { action -> coroutineScope.launch { viewModel.confirmReminderAction(rule.id, action) }; ruleRecord = null },
            onClose = { ruleRecord = null })
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
                duration = if (retryAvailable || notice!!.contains("deleted", ignoreCase = true)) {
                    androidx.compose.material3.SnackbarDuration.Indefinite
                } else {
                    androidx.compose.material3.SnackbarDuration.Short
                },
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                if (retryAvailable && retry != null) {
                    pendingPhotoRetry = null
                    viewModel.attachPhotoToTimelineEvent(retry.eventId, retry.cookId, retry.dishId, retry.uri, retry.caption, retry.useCaptureTime)
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
    ingredientDish?.let { dish -> FoodIngredientsDialog(data, dish, viewModel) { ingredientDish = null; viewModel.clearSaveError() } }
    if (showProbeMove) ProbeMoveDialog(data, selectedFood, viewModel) { showProbeMove = false; viewModel.clearSaveError() }
    LaunchedEffect(data.cook.status) { if (data.cook.status == CookStatus.COMPLETED) { if (showFinishConfirm) showCompletionChoices = true; showFinishConfirm = false } }
    if (showFinishConfirm) AlertDialog(
        onDismissRequest = { if (!busy) showFinishConfirm = false },
        title = { Text("Finish meal", modifier = Modifier.testTag("finish-cook-dialog")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("End this meal and stop temperature recording? You can add notes, results and photos afterward.")
            val unfinished = FoodLivePresentation.phases(data, System.currentTimeMillis()).filter { it.phase != "done" }.mapNotNull { phase -> data.dishes.firstOrNull { it.id == phase.dishId }?.name }
            if (unfinished.isNotEmpty()) Text("No completion action recorded for ${unfinished.joinToString()}. You can still finish; their physical actions will stay as recorded.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = { viewModel.clearSaveError(); viewModel.completeCook(data.cook.id) },
            enabled = !busy, modifier = Modifier.testTag("finish-cook-save")) { Text(if (busy) "Finishing…" else "Finish cook") } },
        dismissButton = { TextButton(onClick = { showFinishConfirm = false }, enabled = !busy) { Text("Cancel") } })
    if (showCompletionChoices) ModalBottomSheet(onDismissRequest = { showCompletionChoices = false }) {
        Column(Modifier.testTag("meal-completion-choices").heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Meal finished", style = MaterialTheme.typography.titleLarge)
            // PL-08 / DD-5: the sheet finish creates LEADS with the quick
            // verdict — overall + per-dish chips, all visible — then the
            // recipe saves, then Leave. One finish path, one results model.
            CompletionVerdictSection(data, viewModel, busy)
            var combinationSaved by remember(data.cook.id) { mutableStateOf<String?>(null) }
            MealRecipeActions(data, viewModel, initiallyExpanded = true, onCombinationSaved = { name -> combinationSaved = name })
            combinationSaved?.let { name -> Text("Meal combination “$name” saved. Find it in Recipes to repeat this meal.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("completion-combination-saved")) }
            TextButton({ selectedTab = CookTab.TIMELINE.name; showCompletionChoices = false }, modifier = Modifier.testTag("meal-view-summary")) { Text("View meal summary") }
            TextButton({ showCompletionChoices = false }, modifier = Modifier.testTag("leave-without-saving")) { Text("Leave without saving") }
            Spacer(Modifier.height(24.dp))
        }
    }
    fun openNote(category: String = "general", action: String? = null, photoFirst: Boolean = false, foodId: String? = selectedFood?.takeIf { id -> data.dishes.any { it.id == id } } ?: data.dishes.singleOrNull()?.id) {
        noteEditing = false
        notePhotoFirst = photoFirst
        val dishId = foodId
        noteDraftJson = com.pittech.domain.CookNoteDraft(cookId = data.cook.id, dishId = dishId, category = category, action = action,
            title = action?.let { com.pittech.domain.PlaybookCodec.actions[it] }.orEmpty(),
            temperatures = if (category == "temperature") listOf(com.pittech.domain.NoteTemperature(unit = preferredTemperatureUnit, dishId = dishId)) else emptyList()).encode()
    }
    // PL-07 (issue #172): the standalone Temperature Entry dialog is retired.
    // Editing a standalone manual reading opens the same note composer as
    // every other temperature job, carrying the reading's own identity; on
    // save the composer adopts the reading into the note it creates, so a
    // manual temp is edited the same way everywhere.
    fun openReadingEdit(reading: SensorReadingEntity) {
        noteEditing = true
        notePhotoFirst = false
        noteDraftJson = com.pittech.domain.CookNoteDraft(cookId = data.cook.id, dishId = reading.dishId, category = "temperature",
            occurredAt = reading.measuredAtUtcMillis,
            temperatures = listOf(com.pittech.domain.NoteTemperature(id = reading.id, probeName = reading.probeName,
                measurementType = reading.measurementType, valueText = reading.value.toString(), unit = reading.unit,
                measuredAt = reading.measuredAtUtcMillis, dishId = reading.dishId, probeId = reading.probeId))).encode()
    }
    noteDraftJson?.let { raw ->
        val draft = com.pittech.domain.CookNoteDraft.decode(raw)
        CookNoteComposer(draft, data.dishes, preferredTemperatureUnit, viewModel, noteEditing, notePhotoFirst,
            onChange = { noteDraftJson = it.encode() },
            onClose = { noteDraftJson = null; notePhotoFirst = false },
            onSaved = { attached -> noteDraftJson = null; notePhotoFirst = false })
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
        CookResultsSheet(
            data = data,
            preferredUnit = preferredTemperatureUnit,
            initialDishId = resultsDishId,
            busy = busy,
            saveError = error,
            onDismiss = { showResults = false; resultsDishId = null; viewModel.clearSaveError() },
            onSave = { dishId, finalTemp, unit, rest, ratings, notes, keep, change, cookAgain ->
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                viewModel.saveResults(data.cook.id, dishId, finalTemp, unit, rest, ratings, notes, false, keep, change, cookAgain) { saved ->
                    if (saved) { showResults = false; resultsDishId = null }
                }
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
                if (!preparingPhoto) coroutineScope.launch {
                    preparingPhoto = true
                    try { viewModel.snapshotForCookLog(data.cook.id); addCameraPhoto() }
                    finally { preparingPhoto = false }
                }
            },
            onChooseFromLibrary = {
                showPhotoSource = false
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }
    if (preparingPhoto) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Reading grill before photo…") },
            text = { Text("The photo will open even if a temperature reading is unavailable.") },
            confirmButton = {},
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
    val checkInReminder = completionReminder
    if (showReminderCheckIn && checkInReminder != null) {
        ReminderCheckInDialog(
            reminder = checkInReminder,
            photoUri = reminderPhotoUri,
            photoCaption = reminderPhotoCaption,
            onPhotoCaptionChange = { reminderPhotoCaption = it },
            // PL-02 (issue #172): scrim/Back only closes the check-in — the
            // hidden 1-hour snooze is gone. The reminder stays pending and
            // visible; nothing changes silently.
            onClose = {
                reminderPhotoUri?.let { CameraPhotoFiles.delete(context, it) }
                reminderPhotoUri = null
                reminderPhotoCaption = ""
                showReminderCheckIn = false
                completionReminder = null
                viewModel.consumeReminderCheckIn()
            },
            onDismiss = {
                viewModel.dismissCookReminder(checkInReminder)
                reminderPhotoUri?.let { CameraPhotoFiles.delete(context, it) }
                reminderPhotoUri = null
                reminderPhotoCaption = ""
                showReminderCheckIn = false
                completionReminder = null
            },
            onSnooze = {
                viewModel.snoozeCookReminder(checkInReminder, com.pittech.domain.ReminderVocabulary.SNOOZE_MILLIS)
                reminderPhotoUri?.let { CameraPhotoFiles.delete(context, it) }
                reminderPhotoUri = null
                reminderPhotoCaption = ""
                showReminderCheckIn = false
                completionReminder = null
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
                completionReminder = null
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
    if (showPhotoGallery) {
        PhotoGalleryDialog(
            photos = data.photos.sortedByDescending { it.capturedAtUtcMillis ?: it.addedAtUtcMillis },
            onDismiss = { showPhotoGallery = false },
            onPhotoClick = { photo ->
                showPhotoGallery = false
                viewingPhoto = photo
            },
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

    viewingMarker?.let { event ->
        AlertDialog(onDismissRequest = { viewingMarker = null }, title = { Text(event.title) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatTimestamp(event.occurredAtUtcMillis))
                event.details?.let { Text(it) }
                val snapshot = com.pittech.data.TemperatureContext.summary(event.temperatureContextJson)
                if (snapshot.isNotBlank()) Text(snapshot)
                data.photos.filter { it.eventId == event.id }.forEach { photo -> PhotoThumbnail(photo, Modifier.size(90.dp)) { viewingMarker = null; viewingPhoto = photo } }
            } }, confirmButton = { TextButton(onClick = { focusedTimelineId = event.id; viewingMarker = null; selectedTab = CookTab.TIMELINE.name }) { Text("Open timeline") } }, dismissButton = { TextButton(onClick = { viewingMarker = null }) { Text("Close") } })
    }
    sharingDraftId?.let { id -> SharingDraftEditor(id, viewModel) { sharingDraftId = null } }
    if (showConnections) ModalBottomSheet(onDismissRequest = { showConnections = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Devices & connections", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { showConnections = false }) { Text("Done") }
            }
            TelemetryConnectionsScreen(viewModel, Modifier.weight(1f))
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(data.cook.title, maxLines = 1); Text(statusLabel(data.cook.status), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("live-cook-status")) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to cooks") } },
                actions = {
                    IconButton(onClick = { showConnections = true }, modifier = Modifier.testTag("meal-devices")) { Icon(Icons.Filled.Thermostat, contentDescription = "Devices and connections") }
                    IconButton(onClick = { editCook = true }) { Icon(Icons.Filled.Edit, contentDescription = "Edit cook") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Box(Modifier.navigationBarsPadding()) {
                CookQuickActionsBar(
                    onNote = { openNote() },
                    onTemperature = { openNote("temperature") },
                    onPhoto = { openNote("photo", photoFirst = true) },
                    onReminder = { showReminderComposer = true },
                    onQuickEvent = { type -> openNote("action", com.pittech.domain.PlaybookCodec.action(type)) },
                )
            }
        },
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
            if (data.logDrafts.isNotEmpty() && noteDraftJson == null) {
                TextButton(onClick = {
                    val saved = data.logDrafts.first()
                    noteEditing = data.events.any { it.id == saved.id }
                    notePhotoFirst = false
                    noteDraftJson = saved.payload
                }, modifier = Modifier.testTag("cook-resume-note")) { Text("Resume saved note (${data.logDrafts.size})") }
            }
            RecipeGuideControl(data, guideRecords, viewModel, reminderRules.any { it.cookId == data.cook.id && it.enabled })
            Box(Modifier.weight(1f)) {
            when (CookTab.valueOf(selectedTab)) {
                CookTab.LIVE -> LiveCookTab(
                    data = data,
                    dishesContent = {
                        MealRecipeActions(data, viewModel)
                        RecipeGuideMilestones(data, guideRecords, compact = true) { selectedTab = CookTab.TIMELINE.name }
                        FoodGrillLayout(data, selectedFood, preferredTemperatureUnit,
                        onSelect = { selectedFood = it },
                        onLog = { food, category, action -> selectedFood = food; openNote(category, action, category == "photo", food) },
                        onRecordAction = { dish, action ->
                            // PL-01: record now; offer the note as a follow-up
                            // on the recorded entry, never as a gate before it.
                            selectedFood = dish.id
                            viewModel.recordDishActionNow(data.cook.id, dish.id, action) { eventId ->
                                coroutineScope.launch {
                                    val result = snackbar.showSnackbar(
                                        message = "${com.pittech.domain.PlaybookCodec.actions[action] ?: action} recorded for ${dish.name}.",
                                        actionLabel = "Add note",
                                        withDismissAction = true,
                                        duration = androidx.compose.material3.SnackbarDuration.Long,
                                    )
                                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                        noteEditing = true
                                        notePhotoFirst = false
                                        noteDraftJson = viewModel.loadNote(eventId).encode()
                                    }
                                }
                            }
                        },
                        onAdd = { addDish = true }, onEdit = { editDish = it },
                        onIngredients = { ingredientDish = it },
                        onProbe = { food -> selectedFood = food; viewModel.clearSaveError(); showProbeMove = true }, reminders = reminderRules, occurrences = reminderOccurrences,
                        selectedTools = { dish ->
                            TextButton(onClick = { resultsDishId = dish.id; viewModel.clearSaveError(); showResults = true }, modifier = Modifier.testTag("dish-verdict-${dish.id}")) { Text("Results & cook again") }
                            UnifiedReminderTools(data, viewModel, dish.id); RecipeReferenceTools(data, viewModel, dish.id)
                        }) },
                    guidancePanel = {
                        CookPhaseCard(data,
                            onFoodOn = { dish -> openNote("action", "food_on"); noteDraftJson = noteDraftJson?.let { com.pittech.domain.CookNoteDraft.decode(it).copy(dishId = dish).encode() } },
                            onPreheat = { openNote("action", "preheat_start") },
                            onAdopt = { viewModel.adoptFoodOnTiming(data.cook.id, it) })
                        CookPlaybookTools(data, viewModel, configuredOnly = null) },
                    remindersPanel = { UnifiedReminderTools(data, viewModel) },
                    alertsPanel = { CookAlertTools(data, viewModel, configuredOnly = null, focusedAlertId = alertFocusId) },
                    checklistPanel = { CookChecklistTools(data, viewModel) },
                    timersPanel = { MealActivityTimers(data.cook.id, data.dishes, data.cook.status != CookStatus.COMPLETED, viewModel) },
                    grillPanel = {
                        if (com.pittech.BuildConfig.TELEMETRY_BETA_ENABLED) CookTelemetryPanel(data, viewModel)
                        if (com.pittech.BuildConfig.CONTROLLER_TESTING_ENABLED || data.recording != null) CookGrillPanel(
                            data, grillState, recordingRunning, busy,
                            { viewModel.attachGrill(data.cook.id, preferredTemperatureUnit) },
                            { viewModel.resumeGrillRecording(data.cook.id) },
                            { viewModel.pauseGrillRecording(data.cook.id) },
                            { viewModel.stopGrillRecording(data.cook.id) },
                            { probe, dish -> viewModel.assignGrillProbe(data.cook.id, probe, dish) },
                            { policy -> if (data.recording == null) viewModel.configureGrillSampling(policy) else viewModel.configureCookSampling(data.cook.id, policy) },
                            { viewModel.readGrillNow(data.cook.id) },
                        )
                    },
                    grillAvailable = com.pittech.BuildConfig.TELEMETRY_BETA_ENABLED || com.pittech.BuildConfig.CONTROLLER_TESTING_ENABLED || data.recording != null,
                    learningTools = { CookLearningTools(data, viewModel) },
                    shareMealAction = {
                        if (data.dishes.isNotEmpty()) TextButton(onClick = { coroutineScope.launch { runCatching { viewModel.sharing.fromMeal(data.cook.id, data.dishes.map { it.id }.toSet()) }.onSuccess { sharingDraftId = it }.onFailure { android.widget.Toast.makeText(context, it.message ?: "Draft could not be created.", android.widget.Toast.LENGTH_LONG).show() } } }, modifier = Modifier.testTag("meal-sharing-draft")) { Text("Share meal") }
                    },
                    summaries = liveSummaries,
                    alertStrip = alertStrip,
                    alertFocused = alertFocusId != null,
                    displayUnit = preferredTemperatureUnit,
                    busy = busy,
                    onTogglePause = { if (data.cook.status == CookStatus.PAUSED) viewModel.resumeCook(data.cook.id) else viewModel.pauseCook(data.cook.id) },
                    onAddTarget = { showTarget = true },
                    onDeleteTarget = viewModel::deleteTarget,
                    onAddDish = { addDish = true },
                    onEditDish = { editDish = it },
                    onResults = {
                        viewModel.clearSaveError()
                        resultsDishId = null
                        showResults = true
                    },
                    onUndoFinish = { viewModel.reopenCook(data.cook.id) },
                    onExport = { showExportCook = true },
                    onFinish = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        viewModel.clearSaveError()
                        showFinishConfirm = true
                    },
                    onDelete = { confirmDeleteCook = true },
                    onCopySetup = { viewModel.previewCookAgain(data.cook.id) },
                    onError = error,
                    onCancelReminder = viewModel::dismissCookReminder,
                    onLogReminder = { reminder ->
                        completionReminder = reminder
                        showReminderCheckIn = true
                    },
                    onPhotoClick = { viewingPhoto = it },
                    onShowPhotoGallery = { showPhotoGallery = true },
                    onViewTimeline = { selectedTab = CookTab.TIMELINE.name },
                )
                CookTab.TIMELINE -> TimelineTab(
                    data = data,
                    focusEventId = focusedTimelineId,
                    onFocusHandled = { focusedTimelineId = null },
                    viewModel = viewModel,
                    onEditEvent = { event -> coroutineScope.launch { noteEditing = true; notePhotoFirst = false; noteDraftJson = viewModel.loadNote(event.id).encode() } },
                    onEditReading = { openReadingEdit(it) },
                    onError = error,
                    onPhotoClick = { viewingPhoto = it },
                    onShowPhotoGallery = { showPhotoGallery = true },
                    onFoodFilter = { selectedFood = it },
                    guide = { RecipeGuideMilestones(data, guideRecords) },
                )
                CookTab.CHARTS -> ChartsTab(data, preferredTemperatureUnit, guide = remember(data, guideRecords) { alignedGuide(data, guideRecords) }, guideVisible = guideState(guideRecords).visible, comparison = { ReferenceComparisonTools(data, viewModel, preferredTemperatureUnit) }) { viewingMarker = it }
            }
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
    onQuickEvent: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onNote,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("timeline-add"),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Log", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
                FilledTonalButton(
                    onClick = onTemperature,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("timeline-add-temperature"),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.Thermostat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Temp", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
                FilledTonalButton(
                    onClick = onPhoto,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("timeline-add-photo"),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Photo", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
                FilledTonalButton(
                    onClick = onReminder,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("cook-add-reminder"),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Remind", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Panel summaries computed at the call site, where the companion-record and
 * activity-timer flows are in scope (PL-04 / DD-2: a collapsed panel header
 * must still carry the panel's live state).
 */
internal data class LivePanelSummaries(
    val guidance: String,
    val alerts: String,
    val checklist: String,
    val timers: String,
)

/**
 * One Live-tab panel (PL-04): a titled card whose header always shows the
 * panel's live state summary, expanded or collapsed. Open/closed state is
 * remembered per cook and panel.
 */
@Composable
private fun LivePanel(
    cookId: String,
    id: String,
    title: String,
    summary: String?,
    initiallyExpanded: Boolean = true,
    forceExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(cookId, id) { mutableStateOf(initiallyExpanded) }
    // PL-03: a notification route (alert tap) opens its panel even when the
    // user last left it collapsed.
    LaunchedEffect(forceExpanded) { if (forceExpanded) expanded = true }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.testTag("live-panel-$id"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    summary?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                )
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun LiveCookTab(
    data: CookDetailData,
    dishesContent: @Composable () -> Unit,
    guidancePanel: @Composable () -> Unit,
    remindersPanel: @Composable () -> Unit,
    alertsPanel: @Composable () -> Unit,
    checklistPanel: @Composable () -> Unit,
    timersPanel: @Composable () -> Unit,
    grillPanel: @Composable () -> Unit,
    grillAvailable: Boolean,
    learningTools: @Composable () -> Unit,
    shareMealAction: @Composable () -> Unit,
    summaries: LivePanelSummaries,
    alertStrip: String?,
    alertFocused: Boolean,
    displayUnit: String,
    busy: Boolean,
    onTogglePause: () -> Unit,
    onAddTarget: () -> Unit,
    onDeleteTarget: (com.pittech.data.TargetEntity) -> Unit,
    onAddDish: () -> Unit,
    onEditDish: (DishEntity) -> Unit,
    onResults: () -> Unit,
    onExport: () -> Unit,
    onFinish: () -> Unit,
    onUndoFinish: () -> Unit,
    onDelete: () -> Unit,
    onCopySetup: () -> Unit,
    onError: String?,
    onCancelReminder: (CookReminderEntity) -> Unit,
    onLogReminder: (CookReminderEntity) -> Unit,
    onPhotoClick: (com.pittech.data.PhotoEntity) -> Unit,
    onShowPhotoGallery: () -> Unit,
    onViewTimeline: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(data.cook.id) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val elapsed = calculateElapsed(data).coerceAtLeast(0)
    val recent = data.events.filter(CookFeedPresentation::isStory).maxByOrNull { it.occurredAtUtcMillis }
    val latestPhoto = data.photos.maxByOrNull { it.capturedAtUtcMillis ?: it.addedAtUtcMillis }
    val pending = data.reminders.filter { it.status == CookReminderEntity.STATUS_PENDING }.sortedBy { it.dueAtUtcMillis }
    val pit = data.readings.filter { it.measurementType in setOf("pit", "pit_ambient", "ambient_temperature") }.maxByOrNull { it.measuredAtUtcMillis }
    val setpoint = data.readings.filter { it.measurementType == "setpoint" }.maxByOrNull { it.measuredAtUtcMillis }
    val cookTargets = data.targets.filter { it.dishId == null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Live Zone poster (PL-04): the cook at a glance — elapsed, pit and
        // setpoint, the latest temperature for every dish, and cook targets.
        Card(
            modifier = Modifier.fillMaxWidth().testTag("live-zone"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Elapsed " + formatCookDuration(elapsed), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("live-cook-elapsed"))
                }
                if (pit != null || setpoint != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        listOf("Pit" to pit, "Setpoint" to setpoint).forEach { (label, reading) ->
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.labelLarge)
                                Text(if (reading != null && FoodLivePresentation.accepted(reading))
                                    String.format(Locale.getDefault(), "%.0f", com.pittech.devices.CookTelemetryPolicy.convert(reading.value, reading.unit, displayUnit)) + " " + displayUnit
                                    else "—", style = MaterialTheme.typography.headlineSmall)
                                reading?.let { Text((if (data.recording?.status == com.pittech.data.CookRecordingEntity.RECORDING && it.sourceDeviceId == data.recording.deviceId && now - it.measuredAtUtcMillis in 0..com.pittech.devices.GrillSamplingPolicy.receiptWindow(it.samplingIntervalMillis)) "Received " else "Last received ") +
                                    formatTimeOfDay(it.measuredAtUtcMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
                data.dishes.forEach { dish ->
                    val latest = data.readings.filter { it.dishId == dish.id && FoodLivePresentation.accepted(it) }.maxByOrNull { it.measuredAtUtcMillis }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(dish.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(if (latest != null)
                            String.format(Locale.getDefault(), "%.0f", com.pittech.devices.CookTelemetryPolicy.convert(latest.value, latest.unit, displayUnit)) + " " + displayUnit
                            else "No reading yet", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                cookTargets.forEach { target ->
                    Text("Target · " + target.targetType.replace('_', ' ') + ": " + target.value + " " + target.unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // PL-03: alert state on the strip — the needs-attention
                // count and named acknowledgments, readable without opening
                // anything. Acknowledging (notification action or Alerts
                // sheet) is confirmed right here on Live.
                alertStrip?.let { line ->
                    Row(Modifier.fillMaxWidth().testTag("live-alerts-strip"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        // Next-action strip: the pending reminders, earliest first, with
        // their one-tap record path — never inside a collapsed panel (DD-2).
        pending.forEach { reminder ->
            Card { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(reminder.title); Text(formatTimeOfDay(reminder.dueAtUtcMillis), style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { onLogReminder(reminder) }) { Text("Log now") }
                TextButton(onClick = { onCancelReminder(reminder) }) { Text(com.pittech.domain.ReminderVocabulary.DISMISS) }
            } }
        }
        onError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        dishesContent()
        if (recent != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Recent activity", style = MaterialTheme.typography.labelLarge)
                Text(CookFeedPresentation.title(recent), maxLines = 2)
                Text(formatTimeOfDay(recent.occurredAtUtcMillis), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onViewTimeline) { Text("Timeline") }
        }
        latestPhoto?.let { photo ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhotoThumbnail(photo, Modifier.size(72.dp).testTag("live-recent-photo"), onClick = { onPhotoClick(photo) })
                Column(Modifier.weight(1f)) {
                    photo.caption?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2) }
                    TextButton(onClick = onShowPhotoGallery, modifier = Modifier.testTag("live-photo-gallery-open")) { Text("All photos (" + data.photos.size + ")") }
                }
            }
        }
        // Panels in lifecycle order (PL-04), each with a state-summary
        // header (DD-2). The alert and playbook tools render ONCE here —
        // the old configured-only/full split instances are unified.
        LivePanel(data.cook.id, "guidance", "Guidance", summaries.guidance) { guidancePanel() }
        LivePanel(data.cook.id, "reminders", "Reminders",
            if (pending.isEmpty()) "None pending" else "${pending.size} pending · next ${formatTimeOfDay(pending.first().dueAtUtcMillis)}") { remindersPanel() }
        LivePanel(data.cook.id, "alerts", "Alerts", summaries.alerts, forceExpanded = alertFocused) { alertsPanel() }
        LivePanel(data.cook.id, "checklist", "Checklist", summaries.checklist) { checklistPanel() }
        LivePanel(data.cook.id, "timers", "Timers", summaries.timers) { timersPanel() }
        if (grillAvailable) LivePanel(data.cook.id, "grill", "Grill & recording",
            if (data.recording?.status == com.pittech.data.CookRecordingEntity.RECORDING) "Recording" else "Not recording") { grillPanel() }
        LivePanel(data.cook.id, "targets", "Targets",
            if (data.targets.isEmpty()) "No targets" else "${data.targets.size} set") {
            data.targets.forEach { target ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(target.targetType.replace('_', ' ') + ": " + target.value + " " + target.unit, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onDeleteTarget(target) }) { Text("Delete") }
                }
            }
            OutlinedButton(onClick = onAddTarget, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Add target") }
        }
        LivePanel(data.cook.id, "results", "Results",
            if (data.results.isEmpty()) "Not recorded yet" else "Recorded") {
            OutlinedButton(onClick = onResults, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("live-record-results")) {
                Text(if (data.cook.status == CookStatus.COMPLETED) "Edit cook results" else "Record results")
            }
            learningTools()
        }
        LivePanel(data.cook.id, "export", "Export & share", null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Export") }
                OutlinedButton(onClick = onCopySetup, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Reuse setup") }
            }
            shareMealAction()
        }
        LivePanel(data.cook.id, "preparation", "Preparation details",
            "${data.dishes.size} dish${if (data.dishes.size == 1) "" else "es"}", initiallyExpanded = false) {
            data.dishes.forEach { dish ->
                Text(dish.name, style = MaterialTheme.typography.titleMedium)
                dish.startingCondition?.let { Text("Starting condition: " + it) }
                dish.prepNotes?.let { Text(it) }
                data.ingredients.filter { it.dishId == dish.id }.forEach { item ->
                    Text(item.name + (item.brand?.let { " · " + it } ?: "") +
                        (item.amountValue?.let { " · " + it + " " + item.amountUnit.orEmpty() } ?: ""))
                }
            }
        }
        LivePanel(data.cook.id, "details", "Cook details",
            "Started " + formatTimeOfDay(data.cook.startedAtUtcMillis), initiallyExpanded = false) {
            Text("Started " + formatTimestamp(data.cook.startedAtUtcMillis), style = MaterialTheme.typography.bodySmall)
            data.cook.smokerName?.let { Text("Smoker: " + it) }
            data.cook.notes?.let { Text(it) }
            data.cook.fuelType?.let { Text("Fuel: " + it) }
            data.cook.woodOrPelletBlend?.let { Text("Wood or pellets: " + it) }
            data.cook.outdoorTemperatureValue?.let { Text("Outdoor temperature: " + it + " " + data.cook.outdoorTemperatureUnit.orEmpty()) }
            data.cook.weatherNotes?.let { Text("Weather: " + it) }
            data.cook.windNotes?.let { Text("Wind: " + it) }
        }
        // Meal-level end actions stay visible — Results and Pause left the
        // collapsed "Cook tools" bucket with this re-rank (PL-04).
        if (data.cook.status != CookStatus.COMPLETED) OutlinedButton(onClick = onFinish, enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("live-finish-cook")) { Text("Finish cook") }
        // PL-08: Undo finish lives on the finished cook itself, not only in
        // the moment after finishing.
        if (data.cook.status == CookStatus.COMPLETED) OutlinedButton(onClick = onUndoFinish, enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("live-undo-finish")) { Text("Undo finish") }
        if (data.cook.status != CookStatus.COMPLETED) OutlinedButton(onClick = onTogglePause, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (data.cook.status == CookStatus.PAUSED) "Resume recording" else "Pause recording")
        }
        TextButton(onClick = onDelete, enabled = !busy) { Text("Delete cook") }
    }
}
@Composable
private fun DashboardSectionPair(
    compact: Boolean,
    testTag: String,
    first: (@Composable (Modifier) -> Unit)?,
    second: (@Composable (Modifier) -> Unit)?,
) {
    if (first == null && second == null) return
    if (compact && first != null && second != null) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag(testTag),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            first?.invoke(Modifier.fillMaxWidth())
            second?.invoke(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DashboardSectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
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
    onShowPhotoGallery: () -> Unit,
    focusEventId: String? = null,
    onFoodFilter: (String?) -> Unit = {},
    onFocusHandled: () -> Unit = {},
    guide: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val filterPreferences = remember(context) { context.getSharedPreferences("pittech-feed", 0) }
    var selectedFoodFilter by rememberSaveable(data.cook.id) { mutableStateOf(filterPreferences.getString(data.cook.id + ":food", null)) }
    val foodFilter = selectedFoodFilter?.takeIf { id -> data.dishes.any { it.id == id } }
    var selectedFilterName by rememberSaveable(data.cook.id) { mutableStateOf(filterPreferences.getString(data.cook.id + ":type", null)?.takeIf { name -> TimelineFilter.entries.any { it.name == name } } ?: TimelineFilter.STORY.name) }
    var newestFirst by rememberSaveable(data.cook.id) { mutableStateOf(true) }
    var showSortMenu by rememberSaveable(data.cook.id) { mutableStateOf(false) }
    val selectedFilter = TimelineFilter.valueOf(selectedFilterName)
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val rows = buildTimelineLines(data, selectedFilter, newestFirst, foodFilter)
    val days = rows.groupBy { timelineDayKey(it.time) }
    // Header items above the day groups: guide + summary, plus the results
    // card when the cook has saved results (PL-08).
    val showResultsCard = data.results.any { it.resultType in RESULT_DISPLAY_TYPES }
    val headerItems = if (showResultsCard) 3 else 2
    val latestIndex = rows.size + days.size + headerItems - 1
    LaunchedEffect(selectedFilterName, foodFilter) {
        filterPreferences.edit().putString(data.cook.id + ":type", selectedFilterName).putString(data.cook.id + ":food", foodFilter).apply()
        onFoodFilter(foodFilter)
    }
    LaunchedEffect(data.dishes.map { it.id }) { if (selectedFoodFilter != null && foodFilter == null) selectedFoodFilter = null }
    LaunchedEffect(selectedFilterName, foodFilter, newestFirst) { if (focusEventId == null) listState.scrollToItem(0) }
    LaunchedEffect(focusEventId) {
        if (focusEventId != null && rows.none { it is TimelineLine.Event && it.event.id == focusEventId }) {
            selectedFilterName = TimelineFilter.ALL.name
            selectedFoodFilter = null
        }
    }
    LaunchedEffect(focusEventId, rows.map { it.stableKey }) {
        if (focusEventId != null) {
            var position = headerItems
            var target: Int? = null
            days.values.forEach { group -> position++; group.forEach { row -> if (row is TimelineLine.Event && row.event.id == focusEventId) target = position; position++ } }
            target?.let { listState.scrollToItem(it); onFocusHandled() }
        }
    }
    val showJumpToLatest by remember(listState, newestFirst, latestIndex) {
        derivedStateOf {
            if (rows.isEmpty()) false
            else if (newestFirst) listState.firstVisibleItemIndex > 2
            else listState.firstVisibleItemIndex < (latestIndex - 1).coerceAtLeast(0)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Cook timeline", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onShowPhotoGallery, modifier = Modifier.testTag("timeline-gallery")) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = "Cook photos")
            }
            Box {
                TextButton(
                    onClick = { showSortMenu = true },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("timeline-sort"),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Text(if (newestFirst) "Newest first" else "Oldest first", maxLines = 1)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Newest first") },
                        onClick = { newestFirst = true; showSortMenu = false },
                        modifier = Modifier.testTag("timeline-sort-newest"),
                    )
                    DropdownMenuItem(
                        text = { Text("Oldest first") },
                        onClick = { newestFirst = false; showSortMenu = false },
                        modifier = Modifier.testTag("timeline-sort-oldest"),
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                var open by remember { mutableStateOf(false) }
                Column {
                    Text("Entries", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("timeline-filter-menu")) {
                        Text(selectedFilter.label, modifier = Modifier.weight(1f)); Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    TimelineFilter.entries.forEach { filter ->
                        DropdownMenuItem(text = { Text(filter.label) }, onClick = { selectedFilterName = filter.name; open = false },
                            modifier = Modifier.testTag("timeline-filter-" + filter.name.lowercase(Locale.ROOT)))
                    }
                }
            }
            val choices = foodChoices(data.dishes)
            SimpleDropdownField("Food", choices.firstOrNull { it.first == foodFilter }?.second ?: "All foods",
                listOf("All foods") + choices.map { it.second }, { name -> selectedFoodFilter = choices.firstOrNull { it.second == name }?.first },
                modifier = Modifier.weight(1f), testTag = "timeline-food-filter")
        }

        if (rows.isEmpty()) {
            val emptyTitle = if (selectedFilter == TimelineFilter.STORY || selectedFilter == TimelineFilter.ALL) "No cook history yet" else "No matching entries"
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                guide()
                Text(emptyTitle, style = MaterialTheme.typography.titleLarge)
                Text(
                    if (selectedFilter == TimelineFilter.ALL) {
                        "Notes, temperature checks, and photos will show up here as the cook progresses."
                    } else {
                        "Choose another filter or add an entry."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("timeline-list"),
                    state = listState,
                    contentPadding = PaddingValues(top = 6.dp, bottom = 76.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item(key = "timeline-guide") { guide() }
                    item(key = "timeline-summary") {
                        Text(
                            "Started ${formatTimeOfDay(data.cook.startedAtUtcMillis)} · ${formatCookDuration(calculateElapsed(data))} elapsed",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp).testTag("timeline-summary"),
                        )
                    }
                    if (showResultsCard) {
                        item(key = "timeline-results") { CookResultsCard(data) }
                    }
                    days.forEach { (dayKey, dayRows) ->
                        item(key = "timeline-day-$dayKey") {
                            TimelineDayHeading(dayRows.first().time)
                        }
                        itemsIndexed(dayRows, key = { _, row -> row.stableKey }) { index, row ->
                            TimelineEntryRow(
                                row = row,
                                isLastInDay = index == dayRows.lastIndex,
                                data = data,
                                onEditEvent = { event -> onEditEvent(event) },
                                onDeleteEvent = { event -> viewModel.deleteTimelineEvent(event) },
                                onEditReading = onEditReading,
                                onDeleteReading = { reading -> viewModel.deleteSensorReading(reading) },
                                onPhotoClick = onPhotoClick,
                                onShowPhotoGallery = onShowPhotoGallery,
                            )
                        }
                    }
                    onError?.let { error ->
                        item(key = "timeline-error") {
                            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (showJumpToLatest) {
                    FilledTonalButton(
                        onClick = {
                            coroutineScope.launch {
                                listState.animateScrollToItem(if (newestFirst) 0 else latestIndex)
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("timeline-jump-latest"),
                    ) {
                        Icon(if (newestFirst) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Jump to latest")
                    }
                }
            }
        }
    }
}

internal sealed class TimelineLine {
    abstract val time: Long
    abstract val stableKey: String

    data class Event(
        val event: TimelineEventEntity,
        val photos: List<com.pittech.data.PhotoEntity> = emptyList(),
    ) : TimelineLine() {
        override val time: Long = event.occurredAtUtcMillis
        override val stableKey: String = "event:${event.id}"
    }

    data class Photo(val photo: com.pittech.data.PhotoEntity) : TimelineLine() {
        override val time: Long = photo.capturedAtUtcMillis ?: photo.addedAtUtcMillis
        override val stableKey: String = "photo:" + photo.id
    }

    data class Reading(val reading: SensorReadingEntity) : TimelineLine() {
        override val time: Long = reading.measuredAtUtcMillis
        override val stableKey: String = "reading:${reading.id}"
    }

    data class ReadingGroup(val readings: List<SensorReadingEntity>) : TimelineLine() {
        override val time: Long = readings.maxOf { it.measuredAtUtcMillis }
        override val stableKey: String = "reading-group:${readings.first().id}"
    }
}

private const val TIMELINE_READING_GROUP_GAP_MILLIS = 5 * 60_000L
private const val TIMELINE_READING_GROUP_SPAN_MILLIS = 15 * 60_000L
private const val TIMELINE_READING_GROUP_MIN_SIZE = 4

internal fun buildTimelineLines(data: CookDetailData, filter: TimelineFilter, newestFirst: Boolean, foodId: String? = null): List<TimelineLine> {
    val photosByEvent = data.photos.filter { it.eventId != null }.groupBy { it.eventId.orEmpty() }
    val rows = mutableListOf<TimelineLine>()
    val linkedByEvent = data.logReadingLinks.groupBy { it.eventId }
    val linkedIds = data.logReadingLinks.map { it.readingId }.toSet()
    val readingsById = data.readings.associateBy { it.id }
    data.events.filter { it.eventType != "temperature" && !it.eventType.startsWith("telemetry_") }.forEach { event ->
        val photos = photosByEvent[event.id].orEmpty()
        val links = linkedByEvent[event.id].orEmpty()
        val foodMatches = CookFeedPresentation.matchesFood(event, foodId) || foodId != null &&
            (photos.any { it.dishId == foodId } || links.any { readingsById[it.readingId]?.dishId == foodId })
        if (foodMatches && CookFeedPresentation.matches(event, filter, photos.isNotEmpty(), links.isNotEmpty()))
            rows += TimelineLine.Event(event, photos)
    }
    if (filter in setOf(TimelineFilter.STORY, TimelineFilter.ALL, TimelineFilter.PHOTOS)) {
        data.photos.filter { it.eventId == null && (foodId == null || it.dishId == null || it.dishId == foodId) }
            .forEach { rows += TimelineLine.Photo(it) }
    }
    if (filter in setOf(TimelineFilter.STORY, TimelineFilter.ALL, TimelineFilter.TEMPERATURES)) {
        val pendingDeviceReadings = mutableListOf<SensorReadingEntity>()
        fun flushDeviceReadings() {
            if (pendingDeviceReadings.size >= TIMELINE_READING_GROUP_MIN_SIZE) {
                rows += TimelineLine.ReadingGroup(pendingDeviceReadings.toList())
            } else {
                pendingDeviceReadings.forEach { rows += TimelineLine.Reading(it) }
            }
            pendingDeviceReadings.clear()
        }

        val orderedReadings = data.readings.filter { it.id !in linkedIds }
            .filter { foodId == null || it.dishId == foodId || it.dishId == null }
            .filter { filter != TimelineFilter.STORY || it.source == "manual" }.sortedBy { it.measuredAtUtcMillis }
        orderedReadings.forEach { reading ->
            if (reading.source == "manual") {
                flushDeviceReadings()
                rows += TimelineLine.Reading(reading)
            } else {
                val previous = pendingDeviceReadings.lastOrNull()
                val crossesEvent = previous != null && data.events.any { event ->
                    event.occurredAtUtcMillis > previous.measuredAtUtcMillis &&
                        event.occurredAtUtcMillis <= reading.measuredAtUtcMillis
                }
                val breaksGroup = previous != null && (
                    reading.measuredAtUtcMillis - previous.measuredAtUtcMillis > TIMELINE_READING_GROUP_GAP_MILLIS ||
                        reading.measuredAtUtcMillis - pendingDeviceReadings.first().measuredAtUtcMillis > TIMELINE_READING_GROUP_SPAN_MILLIS ||
                        reading.source != previous.source ||
                        reading.sourceDeviceId != previous.sourceDeviceId ||
                        crossesEvent
                    )
                if (breaksGroup) flushDeviceReadings()
                pendingDeviceReadings += reading
            }
        }
        flushDeviceReadings()
    }
    val comparator = if (newestFirst) {
        compareByDescending<TimelineLine> { it.time }.thenBy { it.stableKey }
    } else {
        compareBy<TimelineLine> { it.time }.thenBy { it.stableKey }
    }
    return rows.sortedWith(comparator)
}

@Composable
private fun TimelineDayHeading(timestamp: Long) {
    Text(
        formatTimelineDay(timestamp).uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun TimelineEntryRow(
    row: TimelineLine,
    isLastInDay: Boolean,
    data: CookDetailData,
    onEditEvent: (TimelineEventEntity) -> Unit,
    onDeleteEvent: (TimelineEventEntity) -> Unit,
    onEditReading: (SensorReadingEntity) -> Unit,
    onDeleteReading: (SensorReadingEntity) -> Unit,
    onPhotoClick: (com.pittech.data.PhotoEntity) -> Unit,
    onShowPhotoGallery: () -> Unit,
) {
    val kind = when (row) {
        is TimelineLine.Event -> eventKindLabel(row.event.eventType)
        is TimelineLine.Photo -> "PHOTO"
        is TimelineLine.Reading, is TimelineLine.ReadingGroup -> "TEMPERATURE"
    }
    val markerColor = when (kind) {
        "PHOTO" -> MaterialTheme.colorScheme.secondary
        "START", "FINISH", "SETPOINT" -> MaterialTheme.colorScheme.primary
        "TEMPERATURE" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.width(18.dp).fillMaxHeight().padding(top = 15.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(12.dp).background(markerColor, CircleShape))
            if (!isLastInDay) {
                Spacer(Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
        when (row) {
            is TimelineLine.Event -> TimelineEventCard(
                modifier = Modifier.weight(1f),
                line = row,
                data = data,
                onEdit = { onEditEvent(row.event) },
                onDelete = { onDeleteEvent(row.event) },
                onPhotoClick = onPhotoClick,
                onShowPhotoGallery = onShowPhotoGallery,
            )
            is TimelineLine.Reading -> TemperatureTimelineCard(
                modifier = Modifier.weight(1f),
                reading = row.reading,
                cookStartedAt = data.cook.startedAtUtcMillis,
                foodName = data.dishes.firstOrNull { it.id == row.reading.dishId }?.name,
                onEdit = { onEditReading(row.reading) },
                onDelete = { onDeleteReading(row.reading) },
            )
            is TimelineLine.ReadingGroup -> TemperatureReadingGroupCard(Modifier.weight(1f), row, data.dishes.associate { it.id to it.name })
            is TimelineLine.Photo -> StandalonePhotoTimelineCard(Modifier.weight(1f), row.photo, data, onPhotoClick)
        }
    }
}

@Composable
private fun TimelineEventCard(
    modifier: Modifier, line: TimelineLine.Event, data: CookDetailData,
    onEdit: () -> Unit, onDelete: () -> Unit,
    onPhotoClick: (com.pittech.data.PhotoEntity) -> Unit, onShowPhotoGallery: () -> Unit,
) {
    val event = line.event
    val meta = com.pittech.domain.CookNoteMetadata.decode(event.noteMetadataJson)
    val effectiveType = com.pittech.domain.PlaybookCodec.action(event.eventType, event.noteMetadataJson)
    val scopes = (listOfNotNull(event.dishId) + meta?.dishIds.orEmpty()).distinct()
    val foodLabel = data.dishes.filter { it.id in scopes }.joinToString { it.name }.ifBlank { "Whole cook" }
    val title = CookFeedPresentation.title(event)
    val compact = line.photos.isEmpty() && (event.source != "manual" || CookFeedPresentation.isSetpoint(event))
    var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(if (compact) 4.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 4)
                    Text(formatTimeOfDay(event.occurredAtUtcMillis) + (if (!compact) " · " + foodLabel else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (event.source == "manual") TimelineEntryMenu(event.id, event.title,
                    "timeline-event-actions-" + effectiveType, "timeline-event-edit-" + effectiveType, "timeline-event-delete-" + effectiveType,
                    onEdit, onDelete)
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("timeline-details-toggle-" + event.id)) {
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "Hide entry details" else "Show entry details")
                }
            }
            if (!compact && event.source == "manual" && !event.details.isNullOrBlank() && event.details != title) {
                Text(event.details!!, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 4)
            }
            val linkedIds = data.logReadingLinks.filter { it.eventId == event.id }.map { it.readingId }.toSet()
            data.readings.filter { it.id in linkedIds }.forEach { reading ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(CookFeedPresentation.readingName(reading, data.dishes, scopes) + ": " + reading.value + " " + reading.unit, modifier = Modifier.weight(1f))
                    if (event.source == "manual") TimelineEntryMenu(reading.id, reading.probeName,
                        "temperature-actions-" + reading.probeName, "temperature-edit-" + reading.probeName, "temperature-delete-" + reading.probeName,
                        onEdit, onDelete)
                }
            }
            line.photos.forEach { photo ->
                PhotoThumbnail(photo, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)).testTag("timeline-photo-" + photo.id),
                    onClick = { onPhotoClick(photo) })
                photo.caption?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            if (expanded) {
                HorizontalDivider()
                Text(formatTimestamp(event.occurredAtUtcMillis), style = MaterialTheme.typography.bodySmall)
                Text(formatSinceCookStart(event.occurredAtUtcMillis, data.cook.startedAtUtcMillis) + " · " +
                    (if (event.source == "controller_cloud") "Grill" else "Manual entry"), style = MaterialTheme.typography.bodySmall)
                if (event.recordedAtUtcMillis != event.occurredAtUtcMillis)
                    Text("Entered " + formatTimestamp(event.recordedAtUtcMillis), style = MaterialTheme.typography.bodySmall)
                val context = com.pittech.data.TemperatureContext.summary(event.temperatureContextJson)
                if (context.isNotBlank()) {
                    Text(context, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("event-temperature-context"))
                    Text(contextTimeLabel(event.temperatureContextJson), style = MaterialTheme.typography.bodySmall)
                }
                if (compact) event.details?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                line.photos.forEach { Text(it.originalFileName, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (compact) Surface(modifier.testTag("timeline-entry-" + event.id)) { content() }
    else Card(modifier.testTag("timeline-entry-" + event.id), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) { content() }
}

@Composable private fun StandalonePhotoTimelineCard(modifier: Modifier, photo: com.pittech.data.PhotoEntity,
    data: CookDetailData, onPhotoClick: (com.pittech.data.PhotoEntity) -> Unit) {
    Column(modifier.testTag("timeline-standalone-photo-" + photo.id), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text((data.dishes.firstOrNull { it.id == photo.dishId }?.name ?: "Cook") + " · " +
            formatTimeOfDay(photo.capturedAtUtcMillis ?: photo.addedAtUtcMillis), style = MaterialTheme.typography.bodySmall)
        PhotoThumbnail(photo, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)).testTag("timeline-photo-" + photo.id),
            onClick = { onPhotoClick(photo) })
        photo.caption?.takeIf { it.isNotBlank() }?.let { Text(it) }
    }
}

internal fun timelineTemperatureValue(reading: SensorReadingEntity): String =
    if (FoodLivePresentation.accepted(reading)) formatReadingValue(reading.value) + " " + reading.unit else "Unavailable"

@Composable
private fun TemperatureTimelineCard(
    modifier: Modifier,
    reading: SensorReadingEntity,
    cookStartedAt: Long,
    foodName: String? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by rememberSaveable(reading.id) { mutableStateOf(false) }
    Card(
        modifier = modifier.testTag("timeline-entry-${reading.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(reading.probeName + ": " + timelineTemperatureValue(reading), style = MaterialTheme.typography.titleMedium)
                    Text(formatTimeOfDay(reading.measuredAtUtcMillis) + (foodName?.let { " · " + it } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("timeline-reading-details-" + reading.id)) {
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "Hide reading details" else "Reading details")
                }
                if (reading.source == "manual") {
                    TimelineEntryMenu(
                        stateKey = reading.id,
                        label = reading.probeName,
                        actionTag = "temperature-actions-${reading.probeName}",
                        editTag = "temperature-edit-${reading.probeName}",
                        deleteTag = "temperature-delete-${reading.probeName}",
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
            }
            if (expanded) {
                Text(formatTimestamp(reading.measuredAtUtcMillis) + " · " + formatSinceCookStart(reading.measuredAtUtcMillis, cookStartedAt), style = MaterialTheme.typography.bodySmall)
                Text(reading.measurementType.replace('_', ' ') + " · " + reading.source + " · " + reading.qualityStatus, style = MaterialTheme.typography.bodySmall)
                Text("Recorded " + formatTimestamp(reading.recordedAtUtcMillis), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TemperatureReadingGroupCard(modifier: Modifier, line: TimelineLine.ReadingGroup, foodNames: Map<String, String>) {
    var expanded by rememberSaveable(line.stableKey) { mutableStateOf(false) }
    val readings = line.readings.sortedBy { it.measuredAtUtcMillis }
    val probeSummaries = readings.groupBy { TemperaturePlot.identity(it) to it.unit }.values
    Card(
        modifier = modifier.testTag("timeline-entry-${line.stableKey}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimelineKindChip("TEMPERATURES")
                Text("${readings.size} readings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            Text(
                "${formatTimeOfDay(readings.first().measuredAtUtcMillis)}–${formatTimeOfDay(readings.last().measuredAtUtcMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            probeSummaries.forEach { samples ->
                val ordered = samples.sortedBy { it.measuredAtUtcMillis }
                val valid = ordered.filter(FoodLivePresentation::accepted)
                val range = if (valid.isEmpty()) "Unavailable" else
                    formatReadingValue(valid.minOf { it.value }) + "–" + formatReadingValue(valid.maxOf { it.value }) + " " + valid.first().unit
                Text(samples.first().probeName + (foodNames[samples.first().dishId]?.let { " · " + it } ?: "") + ": " + range +
                    " · latest " + timelineTemperatureValue(ordered.last()), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp).testTag("timeline-group-expand-${line.readings.first().id}"),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (expanded) "Hide readings" else "Show ${readings.size} readings")
            }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("timeline-group-readings-${line.readings.first().id}")) {
                    Text(readings.first().source, style = MaterialTheme.typography.bodySmall)
                    readings.forEach { reading ->
                        Text(
                            "${formatTimeOfDay(reading.measuredAtUtcMillis)} · ${reading.probeName}: ${timelineTemperatureValue(reading)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineKindChip(label: String) {
    val container = when (label) {
        "PHOTO" -> MaterialTheme.colorScheme.secondaryContainer
        "TEMPERATURE", "TEMPERATURES" -> MaterialTheme.colorScheme.tertiaryContainer
        "START", "FINISH", "SETPOINT" -> MaterialTheme.colorScheme.primaryContainer
        "CHECK-IN" -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (label) {
        "PHOTO" -> MaterialTheme.colorScheme.onSecondaryContainer
        "TEMPERATURE", "TEMPERATURES" -> MaterialTheme.colorScheme.onTertiaryContainer
        "START", "FINISH", "SETPOINT" -> MaterialTheme.colorScheme.onPrimaryContainer
        "CHECK-IN" -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = CircleShape) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

@Composable
private fun TimelineEntryMenu(
    stateKey: String,
    label: String,
    actionTag: String,
    editTag: String,
    deleteTag: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    Box {
        androidx.compose.material3.IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(48.dp).testTag(actionTag),
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options for $label")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Edit, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Edit") } },
                onClick = { expanded = false; onEdit() },
                modifier = Modifier.testTag(editTag),
            )
            DropdownMenuItem(
                text = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Delete, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Delete") } },
                onClick = { expanded = false; onDelete() },
                modifier = Modifier.testTag(deleteTag),
            )
        }
    }
}

private fun eventKindLabel(eventType: String): String = when (eventType) {
    "cook_started" -> "START"
    "cook_finished" -> "FINISH"
    "setpoint_recorded" -> "SETPOINT"
    "reminder_completed" -> "CHECK-IN"
    "photo" -> "PHOTO"
    "temperature" -> "TEMPERATURE"
    else -> displayEventType(eventType).uppercase(Locale.getDefault())
}

private fun formatTimeOfDay(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private fun formatReadingValue(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

private fun formatSinceCookStart(timestamp: Long, startedAt: Long): String {
    val elapsed = (timestamp - startedAt).coerceAtLeast(0L)
    return "+${formatCookDuration(elapsed)}"
}

private fun formatCookDuration(millis: Long): String {
    val minutes = millis.coerceAtLeast(0L) / 60_000L
    val days = minutes / (24 * 60)
    val hours = (minutes / 60) % 24
    val remainderMinutes = minutes % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${remainderMinutes}m"
        else -> "${remainderMinutes}m"
    }
}

private fun timelineDayKey(millis: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    return "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH)}-${calendar.get(Calendar.DAY_OF_MONTH)}"
}

private fun formatTimelineDay(millis: Long): String {
    val date = Date(millis)
    val calendar = Calendar.getInstance().apply { time = date }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val label = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
    return when {
        calendar.get(Calendar.YEAR) == today.get(Calendar.YEAR) && calendar.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "Today · $label"
        calendar.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) && calendar.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) -> "Yesterday · $label"
        else -> label
    }
}

@Composable
private fun ChartsTab(data: CookDetailData, displayUnit: String, guide: AlignedGuide = AlignedGuide(), guideVisible: Boolean = false, comparison: @Composable () -> Unit = {}, onMarker: (TimelineEventEntity) -> Unit) {
    val valid = data.readings.filter { it.qualityStatus == "valid" }
    val grouped = valid.groupBy { TemperaturePlot.identity(it) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        comparison()
        SectionCard("Temperature chart") {
            if (guideVisible && guide.readings.isEmpty()) Text("Guide curves wait for actual Food on. Sources without temperatures show instructions only.")
            TemperatureChart(data.readings + guide.readings, cookStartTimes = mapOf(data.cook.id to data.cook.startedAtUtcMillis), dishNames = data.dishes.associate { it.id to it.name }, markers = data.events.filter { it.eventType != "temperature" && !it.eventType.startsWith("telemetry_") }, connectionGaps = guide.gaps, onMarker = onMarker, displayUnit = displayUnit, targets = data.targets, targetHistory = data.targetHistory, photos = data.photos, timingMode = data.lifecycle?.timingMode ?: "legacy_preserved", referenceTimes = guide.originalTimes, referenceMatches = guide.matches)
            Text("Valid recorded points only. Manual samples remain individual points and missing periods stay empty.", style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard("Reading summary") {
            if (grouped.isEmpty()) Text("No readings to summarize yet.", style = MaterialTheme.typography.bodyLarge)
            grouped.forEach { (_, readings) ->
                val ordered = readings.sortedBy { it.measuredAtUtcMillis }
                val last = ordered.last()
                val values = ordered.map { com.pittech.devices.CookTelemetryPolicy.convert(it.value, it.unit, displayUnit) }
                Text(last.probeName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${readings.size} readings · low ${"%.1f".format(values.min())} · high ${"%.1f".format(values.max())} · last ${"%.1f".format(values.last())} $displayUnit", style = MaterialTheme.typography.bodyLarge)
            }
        }
        val duration = calculateElapsed(data)
        SectionCard("Cook timing") { Text("${duration / 3_600_000}h ${(duration / 60_000) % 60}m recorded", style = MaterialTheme.typography.bodyLarge) }
    }
}

/** The result types the results card displays — search metadata that shares
 * the results table (favorite, method, tags) is not a cook result. */
private val RESULT_DISPLAY_TYPES = setOf(
    "overall_rating", "cook_again", "final_temperature", "rest_minutes",
    "bark", "tenderness", "juiciness", "smoke", "seasoning",
    "result_notes", "keep_doing", "change_next_time",
)

private fun formatResultRating(value: Double): String =
    if (value % 1.0 == 0.0) "${value.toInt()} / 5" else "$value / 5"

private fun formatResultNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

/**
 * PL-08: saved results finally render — read-only, on the cook's summary
 * (Timeline). This resurrects the formerly dead CookResultsCard as the one
 * display of the one results model: the overall verdict, then one block per
 * dish with its verdict, detail ratings, and facts. Recorded facts are shown
 * exactly as saved; editing happens in the results editor, never here.
 */
@Composable
private fun CookResultsCard(data: CookDetailData) {
    fun rowsFor(dishId: String?) = data.results.filter { it.dishId == dishId }
    fun numeric(rows: List<com.pittech.data.CookResultEntity>, type: String) = rows.firstOrNull { it.resultType == type }?.numericValue
    fun text(rows: List<com.pittech.data.CookResultEntity>, type: String) = rows.firstOrNull { it.resultType == type }?.textValue?.takeIf { it.isNotBlank() }
    @Composable
    fun ResultFacts(rows: List<com.pittech.data.CookResultEntity>) {
        val details = listOf("Bark" to "bark", "Tenderness" to "tenderness", "Juiciness" to "juiciness", "Smoke" to "smoke", "Seasoning" to "seasoning")
            .mapNotNull { (label, type) -> numeric(rows, type)?.let { "$label ${formatResultRating(it)}" } }
        if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
        rows.firstOrNull { it.resultType == "final_temperature" }?.let { row ->
            row.numericValue?.let { Text("Final temperature: ${formatResultNumber(it)} ${row.unit.orEmpty()}".trim(), style = MaterialTheme.typography.bodyLarge) }
        }
        numeric(rows, "rest_minutes")?.let { Text("Rest: ${formatResultNumber(it)} min", style = MaterialTheme.typography.bodyLarge) }
        numeric(rows, "cook_again")?.let { Text("Cook again: ${if (it == 1.0) "Yes" else "No"}", style = MaterialTheme.typography.bodyLarge) }
        text(rows, "result_notes")?.let { Text("Notes: $it", style = MaterialTheme.typography.bodyLarge) }
        text(rows, "keep_doing")?.let { Text("Keep doing: $it", style = MaterialTheme.typography.bodyLarge) }
        text(rows, "change_next_time")?.let { Text("Change next time: $it", style = MaterialTheme.typography.bodyLarge) }
    }
    SectionCard("Cook results") {
        Column(Modifier.testTag("cook-results-card"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val cookRows = rowsFor(null)
            Text(
                "Overall verdict: " + (numeric(cookRows, "overall_rating")?.let { formatResultRating(it) } ?: "Not recorded"),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            ResultFacts(cookRows)
            data.dishes.forEach { dish ->
                val rows = rowsFor(dish.id)
                if (rows.any { it.resultType in RESULT_DISPLAY_TYPES }) {
                    Column(Modifier.testTag("cook-results-dish-${dish.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(dish.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Verdict: " + (numeric(rows, "overall_rating")?.let { formatResultRating(it) } ?: "Not recorded"),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        ResultFacts(rows)
                    }
                }
            }
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
private fun PhotoGalleryDialog(
    photos: List<com.pittech.data.PhotoEntity>,
    onDismiss: () -> Unit,
    onPhotoClick: (com.pittech.data.PhotoEntity) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Photos (${photos.size})", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).testTag("photo-gallery-title"), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 44.dp).testTag("photo-gallery-close")) { Text("Done") }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    photos.forEach { photo ->
                        Row(
                            Modifier.fillMaxWidth().testTag("photo-gallery-row-${photo.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                PhotoThumbnail(
                                    photo,
                                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).testTag("photo-gallery-item-${photo.id}"),
                                    onClick = { onPhotoClick(photo) },
                                )
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(photo.caption?.takeIf { it.isNotBlank() } ?: photo.originalFileName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(formatTimestamp(photo.capturedAtUtcMillis ?: photo.addedAtUtcMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
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
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("photo-viewer-close")) { Text("×", color = Color.White, style = MaterialTheme.typography.headlineMedium) }
                    Text(photo.caption ?: photo.originalFileName, color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).testTag("photo-viewer-title"), maxLines = 1)
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
                photo.capturedAtUtcMillis?.let { Text("Captured ${formatTimestamp(it)}", color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp)) }
                val temperatureContext = com.pittech.data.TemperatureContext.summary(photo.temperatureContextJson)
                Text(if (temperatureContext.isBlank()) "Temperature context unavailable for this photo's capture time" else temperatureContext + " · " + contextTimeLabel(photo.temperatureContextJson), color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp).testTag("photo-temperature-context"))
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
    onClose: () -> Unit,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onAddPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onComplete: (String) -> Unit,
) {
    var note by rememberSaveable(reminder.id) { mutableStateOf("") }
    AlertDialog(
        // PL-02: closing (scrim/Back) never changes the reminder.
        onDismissRequest = onClose,
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
                // The unified vocabulary, offered explicitly: Log it (confirm),
                // Snooze 10 min, Dismiss.
                TextButton(onClick = onSnooze, modifier = Modifier.testTag("reminder-checkin-snooze")) { Text(com.pittech.domain.ReminderVocabulary.SNOOZE) }
            }
        },
        confirmButton = { TextButton(onClick = { onComplete(note) }, modifier = Modifier.testTag("reminder-checkin-save")) { Text(com.pittech.domain.ReminderVocabulary.LOG_IT) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("reminder-checkin-dismiss")) { Text(com.pittech.domain.ReminderVocabulary.DISMISS) } },
    )
}

@Composable
private fun CookBasicsDialog(data: CookDetailData, onDismiss: () -> Unit, onSave: (String, String, String, String, String, String, String, String, String, String, String) -> Unit) {
    var title by rememberSaveable(data.cook.id) { mutableStateOf(data.cook.title) }
    var smoker by rememberSaveable(data.cook.id) { mutableStateOf(data.cook.smokerName.orEmpty()) }
    var setpoint by rememberSaveable(data.cook.id) { mutableStateOf((data.targetHistory.lastOrNull { it.targetType == "setpoint" }?.value ?: data.cook.initialSetpointValue)?.toString().orEmpty()) }
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
    var moreDetails by rememberSaveable(dish.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${dish.name}") },
        text = {
            Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Dish name") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { moreDetails = !moreDetails }, modifier = Modifier.testTag("food-details-toggle")) { Text(if (moreDetails) "Hide food details" else "More food details") }
                if (moreDetails) {
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
    initialType: String,
    photoUri: String?,
    photoCaption: String,
    onChoosePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onPhotoCaptionChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, String?, Long, String?, Boolean) -> Unit,
) {
    val dialogFocusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var type by rememberSaveable(draftKey, event?.id) { mutableStateOf(event?.eventType ?: if (photoEntryMode) "photo" else initialType) }
    var title by rememberSaveable(draftKey, event?.id) { mutableStateOf(event?.title ?: if (photoEntryMode) "Photo added" else if (initialType == "note") "Cook note" else initialType.replace('_', ' ').replaceFirstChar { it.uppercase() }) }
    var details by rememberSaveable(draftKey, event?.id) { mutableStateOf(event?.details.orEmpty()) }
    var time by rememberSaveable(draftKey, event?.id) { mutableStateOf(event?.occurredAtUtcMillis ?: System.currentTimeMillis()) }
    var timeWasChosen by rememberSaveable(draftKey, event?.id) { mutableStateOf(event != null) }
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
                    DateTimePickerField("Occurred at", time, { time = it; timeWasChosen = true }, "timeline-entry-time")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isNotBlank()) {
                    dialogFocusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onSave(type, title.trim(), details.trim().ifBlank { null }, time, dishId.takeUnless { it == "whole" }, timeWasChosen)
                }
            }, enabled = event != null || type != "note" || details.isNotBlank() || photoUri != null, modifier = Modifier.testTag("timeline-entry-save")) { Text("Save entry") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun DateTimePickerField(label: String, millis: Long, onChange: (Long) -> Unit, testTag: String) {
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
/**
 * One rating chip row (PL-08 / DD-5): chips 1–5, tapping the selected chip
 * clears back to "Not recorded" — skipping a rating is a first-class state,
 * different from a poor rating. Imported fractional ratings (e.g. 4.5) have
 * no chip; they are named as saved until the user picks a chip or clears.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerdictChipRow(value: Double?, onChange: (Double?) -> Unit, tagPrefix: String, enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..5).forEach { n ->
            FilterChip(
                selected = value == n.toDouble(),
                onClick = { onChange(if (value == n.toDouble()) null else n.toDouble()) },
                enabled = enabled,
                label = { Text("$n / 5") },
                modifier = Modifier.testTag("$tagPrefix-$n"),
            )
        }
    }
    when {
        value == null -> Text("Not recorded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        value % 1.0 != 0.0 -> Text("Saved rating: ${formatResultRating(value)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatingChipRow(label: String, valueText: String, onChange: (String) -> Unit, tagPrefix: String, enabled: Boolean) {
    Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    VerdictChipRow(valueText.toDoubleOrNull(), { onChange(it?.let { n -> if (n % 1.0 == 0.0) n.toInt().toString() else n.toString() } ?: "") }, tagPrefix, enabled)
}

/**
 * PL-08 / DD-5: the completion sheet's first section — the quick verdict.
 * The overall verdict plus one verdict per dish (rating chips + "Cook
 * again?"), every group visible before any other choice on the sheet.
 * Saving writes only verdict rows through the one results model, so
 * anything recorded elsewhere is untouched and anything skipped stays
 * "Not recorded".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompletionVerdictSection(data: CookDetailData, viewModel: CooksViewModel, busy: Boolean) {
    fun savedRating(dishId: String?) = data.results.firstOrNull { it.dishId == dishId && it.resultType == "overall_rating" }?.numericValue
    fun savedAgain(dishId: String) = data.results.firstOrNull { it.dishId == dishId && it.resultType == "cook_again" }?.numericValue?.let { it == 1.0 }
    var overall by remember(data.cook.id, data.results) { mutableStateOf(savedRating(null)) }
    var dishRatings by remember(data.cook.id, data.results) { mutableStateOf(data.dishes.associate { dish -> dish.id to savedRating(dish.id) }) }
    var dishAgain by remember(data.cook.id, data.results) { mutableStateOf(data.dishes.associate { dish -> dish.id to savedAgain(dish.id) }) }
    var saved by remember(data.cook.id) { mutableStateOf(false) }
    Column(Modifier.testTag("completion-verdict"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Quick verdict", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("How did it turn out? Anything you skip stays Not recorded — results can be edited any time from the cook.", style = MaterialTheme.typography.bodyMedium)
        Text("Overall", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        VerdictChipRow(overall, { overall = it; saved = false }, "completion-verdict-cook", !busy)
        data.dishes.forEach { dish ->
            Text(dish.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            VerdictChipRow(dishRatings[dish.id], { value -> dishRatings = dishRatings + (dish.id to value); saved = false }, "completion-verdict-${dish.id}", !busy)
            Text("Cook again?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("completion-again-${dish.id}")) {
                FilterChip(dishAgain[dish.id] == null, { dishAgain = dishAgain + (dish.id to null); saved = false }, enabled = !busy, label = { Text("Not decided") })
                FilterChip(dishAgain[dish.id] == true, { dishAgain = dishAgain + (dish.id to true); saved = false }, enabled = !busy, label = { Text("Yes") })
                FilterChip(dishAgain[dish.id] == false, { dishAgain = dishAgain + (dish.id to false); saved = false }, enabled = !busy, label = { Text("No") })
            }
        }
        Button(
            onClick = { viewModel.saveFinishVerdicts(data.cook.id, overall, data.dishes.associate { dish -> dish.id to (dishRatings[dish.id] to dishAgain[dish.id]) }) { ok -> if (ok) saved = true } },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("completion-verdict-save"),
        ) { Text("Save verdict") }
        if (saved) Text("Verdict saved", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("completion-verdict-saved"))
    }
}

/**
 * PL-08: THE results editor — one system for the whole cook and every dish,
 * replacing both the old ResultsDialog and the per-dish DishVerdictEditor.
 * Every scope edits the same fields of the same results rows, including the
 * per-dish "cook again?" the light editor used to own. Per DD-5 the verdict
 * and every rating chip group are visible without opening anything; only
 * prose (notes, keep/change) sits behind a reveal. Per DD-6 this is a sheet,
 * not a dialog. There is deliberately no finish toggle: finishing has
 * exactly one path — the Finish button and its verdict-first completion
 * sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CookResultsSheet(
    data: CookDetailData,
    preferredUnit: String,
    initialDishId: String?,
    busy: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (String?, String, String, String, Map<String, String>, String, String, String, Boolean?) -> Unit,
) {
    val sheetFocusManager = LocalFocusManager.current
    val sheetKeyboardController = LocalSoftwareKeyboardController.current
    val firstDishId = data.dishes.firstOrNull()?.id ?: "whole"
    var selectedDishId by rememberSaveable(data.cook.id, initialDishId) { mutableStateOf(initialDishId?.takeIf { id -> data.dishes.any { it.id == id } } ?: firstDishId) }
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
    var cookAgain by remember(data.cook.id, selectedDishId) { mutableStateOf(existing.firstOrNull { it.resultType == "cook_again" }?.numericValue?.let { it == 1.0 }) }
    var notes by rememberSaveable(data.cook.id, selectedDishId) { mutableStateOf(existing.firstOrNull { it.resultType == "result_notes" }?.textValue.orEmpty()) }
    var keep by rememberSaveable(data.cook.id, selectedDishId) { mutableStateOf(existing.firstOrNull { it.resultType == "keep_doing" }?.textValue.orEmpty()) }
    var change by rememberSaveable(data.cook.id, selectedDishId) { mutableStateOf(existing.firstOrNull { it.resultType == "change_next_time" }?.textValue.orEmpty()) }
    var showLearning by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(if (data.cook.status != CookStatus.COMPLETED) "Record results" else "Edit results", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("results-dialog-title"))
            Text("All result fields are optional.", style = MaterialTheme.typography.bodyMedium)
            (error ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("results-save-error")) }
            if (data.dishes.isNotEmpty()) SimpleDropdownField("Results for", selectedDishKey?.let { key -> data.dishes.firstOrNull { it.id == key }?.name } ?: "Whole cook", listOf("Whole cook") + data.dishes.map { it.name }, { selectedName -> selectedDishId = data.dishes.firstOrNull { dish -> dish.name == selectedName }?.id ?: "whole" }, enabled = !busy)
            RatingChipRow("Verdict", rating, { rating = it; error = null }, "result-verdict", !busy)
            if (selectedDishKey != null) {
                Text("Cook again?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("result-cook-again")) {
                    FilterChip(cookAgain == null, { cookAgain = null }, enabled = !busy, label = { Text("Not decided") })
                    FilterChip(cookAgain == true, { cookAgain = true }, enabled = !busy, label = { Text("Yes") })
                    FilterChip(cookAgain == false, { cookAgain = false }, enabled = !busy, label = { Text("No") })
                }
            }
            OutlinedTextField(finalTemp, { finalTemp = it; error = null }, enabled = !busy, label = { Text("Final temperature (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("result-final-temperature"))
            SimpleDropdownField("Temperature unit", unit, listOf("°F", "°C"), { unit = it }, enabled = !busy)
            OutlinedTextField(rest, { rest = it; error = null }, enabled = !busy, label = { Text("Rest time in minutes") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            RatingChipRow("Bark", bark, { bark = it; error = null }, "result-bark", !busy)
            RatingChipRow("Tenderness", tenderness, { tenderness = it; error = null }, "result-tenderness", !busy)
            RatingChipRow("Juiciness", juiciness, { juiciness = it; error = null }, "result-juiciness", !busy)
            RatingChipRow("Smoke", smoke, { smoke = it; error = null }, "result-smoke", !busy)
            RatingChipRow("Seasoning", seasoning, { seasoning = it; error = null }, "result-seasoning", !busy)
            OutlinedTextField(notes, { notes = it }, enabled = !busy, label = { Text("Cook notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { showLearning = !showLearning }, enabled = !busy) { Text(if (showLearning) "Hide next-cook lessons" else "Keep doing / change next time") }
            if (showLearning) {
                OutlinedTextField(keep, { keep = it }, enabled = !busy, label = { Text("Keep doing") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(change, { change = it }, enabled = !busy, label = { Text("Change next time") }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    val valid = CookEntryValidation.isOptionalPositiveNumberValid(finalTemp) && CookEntryValidation.isOptionalPositiveNumberValid(rest)
                    if (!valid) error = "Use positive values; ratings must be from 1 to 5."
                    else {
                        sheetFocusManager.clearFocus(force = true)
                        sheetKeyboardController?.hide()
                        onSave(selectedDishKey, finalTemp, unit, rest, mapOf("overall_rating" to rating, "bark" to bark, "tenderness" to tenderness, "juiciness" to juiciness, "smoke" to smoke, "seasoning" to seasoning), notes, keep, change, cookAgain)
                    }
                }, enabled = !busy, modifier = Modifier.testTag("results-save")) { Text(if (busy) "Saving…" else "Save results") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun displayEventType(type: String): String = type.replace('_', ' ').replaceFirstChar { it.titlecase() }

private fun formatTimestamp(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private fun safeFileName(value: String): String = value.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(Regex("\\s+"), "_").ifBlank { "PitTech_Cook" }

private fun calculateElapsed(data: CookDetailData): Long {
    if (data.lifecycle?.timingMode == "explicit_phases") return com.pittech.domain.CookPhases.heatingMillis(
        com.pittech.domain.CookPhases.all(com.pittech.data.notePlanEvents(data.events), data.dishes.map { it.id }, data.cook.startedAtUtcMillis, data.cook.endedAtUtcMillis ?: System.currentTimeMillis()))
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

private fun contextTimeLabel(json: String?): String = com.pittech.data.TemperatureContext.decode(json)
    .groupBy { it.source }.entries.joinToString(" · ") { (source, values) ->
        val label = if (source == "controller_cloud") "Cloud received" else "Measured"
        label + " " + values.map { it.observedAtUtcMillis }.distinct().sorted().joinToString { formatTimestamp(it) }
    }
