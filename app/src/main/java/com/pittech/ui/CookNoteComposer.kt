package com.pittech.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel
import com.pittech.data.DishEntity
import com.pittech.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** All shortcut entry points edit this same durable draft and save one timeline identity. */
@Composable
internal fun CookNoteComposer(draft: CookNoteDraft, dishes: List<DishEntity>, preferredUnit: String,
    viewModel: CooksViewModel, editing: Boolean, photoFirst: Boolean,
    onChange: (CookNoteDraft) -> Unit, onClose: () -> Unit, onSaved: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val saveError by viewModel.error.collectAsStateWithLifecycle()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var localError by remember { mutableStateOf<String?>(null) }
    var showDetails by rememberSaveable(draft.id) { mutableStateOf(false) }
    var showPhotoSource by rememberSaveable(draft.id) { mutableStateOf(photoFirst) }
    var staging by remember { mutableStateOf(false) }
    var captionIds by rememberSaveable(draft.id) { mutableStateOf(emptyList<String>()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val latestDraft by rememberUpdatedState(draft)
    val latestBusy by rememberUpdatedState(busy)
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE && !latestBusy) viewModel.flushNoteDraft(latestDraft)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var durationText by rememberSaveable(draft.id) { mutableStateOf(draft.durationMillis?.let { (it / 60_000.0).toString() }.orEmpty()) }
    val valid = runCatching { draft.validate() }.isSuccess && (durationText.isBlank() || durationText.toDoubleOrNull()?.let { it.isFinite() && it >= 0 && it <= 43200 } == true)
    LaunchedEffect(draft, busy) {
        if (!busy) { delay(300); viewModel.persistNoteDraft(draft) }
    }
    fun stage(uris: List<Uri>) {
        if (uris.isEmpty()) return
        staging = true
        scope.launch {
            try {
                val photos = uris.map { viewModel.stageNotePhoto(draft.id, it.toString()) }
                val next = draft.copy(photos = draft.photos + photos)
                viewModel.persistNoteDraft(next)
                onChange(next)
            } catch (failure: Exception) { localError = ErrorCopy.failureText(failure, "The photo couldn't be opened. Your note draft is kept on this device — try again.") }
            finally { staging = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { stage(it) }
    val camera = rememberCameraPhotoCapture(onPhotoCaptured = { stage(listOf(it)) })
    fun keepDraft() { scope.launch { viewModel.persistNoteDraft(draft); onClose() } }
    // PL-22 (DD-6): the composer is the note/temp contextual capture — a
    // bottom sheet, not a dialog. Same draft, fields, and actions; the Save
    // row stays pinned under the scrolling fields (results-sheet pattern).
    SheetSurface(
        onDismiss = { if (!busy && !staging) keepDraft() },
        title = if (editing && draft.category == "temperature") "Edit temperature" else if (editing) "Edit timeline entry" else "Add to cook log",
        dismissEnabled = !busy && !staging,
        fillHeightFraction = 0.95f,
        actions = {
            TextButton(onClick = { viewModel.discardNoteDraft(draft.id); onClose() }, enabled = !busy && !staging) { Text("Discard") }
            TextButton(onClick = { keepDraft() }, enabled = !busy && !staging) { Text("Keep draft") }
            TextButton(onClick = { viewModel.saveNote(draft, editing, onSaved) }, enabled = valid && !busy && !staging,
                modifier = Modifier.testTag(if (draft.category == "temperature") "temperature-save" else "timeline-entry-save")) { Text("Save entry") }
        },
    ) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val choices = foodChoices(dishes)
                val scopeLabel = if (draft.dishIds.isNotEmpty()) draft.dishIds.size.toString() + " foods" else
                    choices.firstOrNull { it.first == draft.dishId }?.second ?: "Whole cook"
                SimpleDropdownField("For", scopeLabel, listOf("Whole cook") + choices.map { it.second } + listOf("Select multiple foods…"), { label ->
                    if (label == "Select multiple foods…") showDetails = true
                    else {
                        val food = choices.firstOrNull { it.second == label }?.first
                        val followScope = draft.dishIds.isEmpty()
                        onChange(draft.copy(dishId = food, dishIds = emptyList(), temperatures = draft.temperatures.map {
                            if (followScope && it.dishId == draft.dishId && it.measurementType.startsWith("food")) it.copy(dishId = food) else it
                        }))
                    }
                }, testTag = "note-food-scope")
                OutlinedTextField(draft.description, { onChange(draft.copy(description = it)) }, label = { Text("Observation") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("timeline-entry-details"))
                draft.temperatures.forEachIndexed { index, t ->
                    fun change(next: NoteTemperature) { onChange(draft.copy(temperatures = draft.temperatures.map { if (it.id == t.id) next else it })) }
                    OutlinedTextField(t.valueText, { change(t.copy(valueText = it)) }, label = { Text("Temperature (" + t.unit + ")") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag(if (index == 0) "temperature-value" else "temperature-value-" + t.id))
                    // PL-07 (issue #172): probe / measurement / unit /
                    // measured-at live on the temperature row itself, not
                    // behind "More details" — a quick temp must not silently
                    // record the wrong probe, type, unit or time.
                    OutlinedTextField(t.probeName, { change(t.copy(probeName = it)) }, label = { Text("Probe or location") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(if (index == 0) "temperature-probe" else "temperature-probe-" + t.id))
                    // Legacy readings from the retired Temperature Entry
                    // dialog can carry "setpoint"/"other"; keep that type
                    // selectable so editing never silently rewrites it.
                    val legacyMeasurement = when (t.measurementType) { "setpoint" -> "Setpoint"; "other" -> "Other"; else -> null }
                    SimpleDropdownField("Measurement", legacyMeasurement ?: if (t.measurementType.startsWith("pit")) "Pit" else "Food",
                        listOf("Food", "Pit") + listOfNotNull(legacyMeasurement), { label ->
                        when (label) {
                            "Pit" -> change(t.copy(measurementType = "pit", dishId = null))
                            "Food" -> change(t.copy(measurementType = "food", dishId = draft.dishId))
                            "Setpoint" -> change(t.copy(measurementType = "setpoint"))
                            else -> change(t.copy(measurementType = "other"))
                        }
                    })
                    SimpleDropdownField("Unit", t.unit, listOf("°F", "°C"), { change(t.copy(unit = it)) })
                    SimpleDropdownField("Temperature for", choices.firstOrNull { it.first == t.dishId }?.second ?: "Whole cook",
                        listOf("Whole cook") + choices.map { it.second }, { label -> change(t.copy(dishId = choices.firstOrNull { it.second == label }?.first)) })
                    DateTimePickerField("Measured at", t.measuredAt, { change(t.copy(measuredAt = it)) }, "temperature-time-" + t.id)
                    TextButton(onClick = { onChange(draft.copy(temperatures = draft.temperatures.filterNot { it.id == t.id })) }) { Text("Remove temperature") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showPhotoSource = true }, enabled = !staging, modifier = Modifier.weight(1f).testTag("note-add-photo")) { Text("+ Photo") }
                    OutlinedButton(onClick = { onChange(draft.copy(temperatures = draft.temperatures + NoteTemperature(unit = preferredUnit, measuredAt = draft.occurredAt, dishId = draft.dishId))) },
                        modifier = Modifier.weight(1f).testTag("note-add-temperature")) { Text("+ Temp") }
                }
                draft.photos.forEachIndexed { index, photo ->
                    NotePhotoPreview(photo)
                    if (photo.caption.isNotBlank() || photo.id in captionIds || showDetails)
                        OutlinedTextField(photo.caption, { caption -> onChange(draft.copy(photos = draft.photos.map { if (it.id == photo.id) it.copy(caption = caption) else it })) },
                            label = { Text("Photo caption") }, modifier = Modifier.fillMaxWidth().testTag(if (index == 0) "timeline-photo-caption" else "timeline-photo-caption-" + photo.id))
                    Row {
                        if (photo.caption.isBlank() && photo.id !in captionIds && !showDetails)
                            TextButton(onClick = { captionIds = captionIds + photo.id }, modifier = Modifier.testTag("note-caption-" + photo.id)) { Text("Add caption") }
                        TextButton(onClick = { onChange(draft.copy(photos = draft.photos.filterNot { it.id == photo.id })) }) { Text("Remove photo") }
                    }
                }
                if (staging) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { focusManager.clearFocus(); keyboard?.hide(); showDetails = !showDetails }, modifier = Modifier.testTag("timeline-entry-more-details")) { Text(if (showDetails) "Hide details" else "More details") }
                if (showDetails) {
                    SimpleDropdownField("Cooking method", CookingMethods.labels[draft.method] ?: "Not set", listOf("Not set") + CookingMethods.labels.values,
                        { label -> onChange(draft.copy(method = CookingMethods.labels.entries.firstOrNull { it.value == label }?.key)) }, testTag = "note-method")
                    if (draft.startedAtUtcMillis != null && draft.endedAtUtcMillis != null) {
                        Text("Measured duration: ${draft.durationMillis?.div(60_000.0)} minutes")
                    } else OutlinedTextField(durationText, { raw ->
                        durationText = raw
                        val duration = raw.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..43200.0 }?.let { (it * 60_000).toLong() }
                        onChange(draft.copy(durationMillis = duration))
                    }, label = { Text("Duration in minutes (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag("note-duration"))
                    IngredientFields(draft.ingredients) { onChange(draft.copy(ingredients = it)) }
                    OutlinedTextField(draft.title, { onChange(draft.copy(title = it)) }, label = { Text("Entry title") }, modifier = Modifier.fillMaxWidth().testTag("timeline-entry-title"))
                    val actions = linkedMapOf("None" to null) + PlaybookCodec.actions.entries.associate { it.value to it.key } +
                        mapOf("Preheat" to "preheat_start", "Season" to "season", "Trim" to "trim", "Marinate" to "marinate", "Add fuel" to "add_fuel")
                    SimpleDropdownField("Action", actions.entries.firstOrNull { it.value == draft.action }?.key ?: "None", actions.keys.toList(),
                        { label -> onChange(draft.copy(action = actions[label], category = if (actions[label] != null) "action" else draft.category)) }, testTag = "timeline-entry-type")
                    Text("Foods in this note", style = MaterialTheme.typography.labelLarge)
                    dishes.forEach { dish ->
                        val selected = dish.id == draft.dishId || dish.id in draft.dishIds
                        FilterChip(selected, onClick = {
                            val ids = (draft.dishIds + listOfNotNull(draft.dishId)).distinct().toMutableList()
                            if (selected) ids.remove(dish.id) else ids.add(dish.id)
                            onChange(draft.copy(dishId = ids.singleOrNull(), dishIds = if (ids.size > 1) ids else emptyList()))
                        }, label = { Text(dish.name) })
                    }
                    DateTimePickerField("Occurred at", draft.occurredAt, { at -> onChange(draft.copy(occurredAt = at)) }, "timeline-entry-time")
                }
                (localError ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("note-save-error")) }
            }
    }
    // PL-07 (issue #172): cancelling the photo source returns to the
    // composer — including on a photo-first entry, where it used to close
    // the whole composer and dump the user back on Cook Detail.
    if (showPhotoSource) PhotoSourceDialog(onDismiss = { showPhotoSource = false },
        onTakePhoto = { scope.launch { viewModel.persistNoteDraft(draft); showPhotoSource = false; camera() } }, onChooseFromLibrary = { scope.launch { viewModel.persistNoteDraft(draft); showPhotoSource = false; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } })
}

@Composable private fun NotePhotoPreview(photo: NotePhoto) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, photo.uri) {
        value = withContext(Dispatchers.IO) { runCatching {
            context.contentResolver.openInputStream(Uri.parse(photo.uri))?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }) }
        }.getOrNull() }
    }
    bitmap?.let { Image(it.asImageBitmap(), photo.caption.ifBlank { "Attached cook photo" }, Modifier.fillMaxWidth().height(100.dp)) }
}
