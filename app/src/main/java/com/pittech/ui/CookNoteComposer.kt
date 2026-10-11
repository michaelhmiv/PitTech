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
    var localError by remember { mutableStateOf<String?>(null) }
    var showDetails by rememberSaveable(draft.id) { mutableStateOf(editing) }
    var showPhotoSource by rememberSaveable(draft.id) { mutableStateOf(photoFirst) }
    var staging by remember { mutableStateOf(false) }
    val valid = runCatching { draft.validate() }.isSuccess
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
            } catch (failure: Exception) { localError = failure.message ?: "The photo could not be opened." }
            finally { staging = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { stage(it) }
    val camera = rememberCameraPhotoCapture(onPhotoCaptured = { stage(listOf(it)) })
    fun keepDraft() { scope.launch { viewModel.persistNoteDraft(draft); onClose() } }
    AlertDialog(
        onDismissRequest = { if (!busy && !staging) keepDraft() },
        title = { Text(if (editing && draft.category == "temperature") "Edit temperature" else if (editing) "Edit timeline entry" else "Add to cook log") },
        text = {
            Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showDetails) OutlinedTextField(draft.title, { onChange(draft.copy(title = it)) }, label = { Text("Entry title") }, modifier = Modifier.fillMaxWidth().testTag("timeline-entry-title"))
                OutlinedTextField(draft.description, { onChange(draft.copy(description = it)) }, label = { Text("Notes or details") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("timeline-entry-details"))
                draft.temperatures.forEachIndexed { index, t ->
                    fun change(next: NoteTemperature) { onChange(draft.copy(temperatures = draft.temperatures.map { if (it.id == t.id) next else it })) }
                    HorizontalDivider()
                    Text("Temperature ${index + 1}", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(t.probeName, { change(t.copy(probeName = it)) }, label = { Text("Probe or location") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(if (index == 0) "temperature-probe" else "temperature-probe-${t.id}"))
                    OutlinedTextField(t.valueText, { change(t.copy(valueText = it)) }, label = { Text("Temperature") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag(if (index == 0) "temperature-value" else "temperature-value-${t.id}"))
                    SimpleDropdownField("Measurement", if (t.measurementType.startsWith("pit")) "Pit" else "Food", listOf("Food", "Pit"), { change(t.copy(measurementType = if (it == "Pit") "pit" else "food")) })
                    SimpleDropdownField("Unit", t.unit, listOf("°F", "°C"), { change(t.copy(unit = it)) })
                    SimpleDropdownField("Temperature for", dishes.firstOrNull { it.id == t.dishId }?.name ?: "Whole cook", listOf("Whole cook") + dishes.map { it.name }, { name -> change(t.copy(dishId = dishes.firstOrNull { it.name == name }?.id)) })
                    DateTimePickerField("Measured at", t.measuredAt, { change(t.copy(measuredAt = it)) }, "temperature-time-${t.id}")
                    TextButton(onClick = { onChange(draft.copy(temperatures = draft.temperatures.filterNot { it.id == t.id })) }) { Text("Remove temperature") }
                }
                OutlinedButton(onClick = { onChange(draft.copy(temperatures = draft.temperatures + NoteTemperature(unit = preferredUnit, measuredAt = draft.occurredAt, dishId = draft.dishId))) }, modifier = Modifier.fillMaxWidth().testTag("note-add-temperature")) { Text("Add temperature") }
                OutlinedButton(onClick = { showPhotoSource = true }, enabled = !staging, modifier = Modifier.fillMaxWidth().testTag("note-add-photo")) { Text("Attach photos") }
                draft.photos.forEachIndexed { index, photo ->
                    NotePhotoPreview(photo)
                    OutlinedTextField(photo.caption, { caption -> onChange(draft.copy(photos = draft.photos.map { if (it.id == photo.id) it.copy(caption = caption) else it })) },
                        label = { Text("Photo caption") }, modifier = Modifier.fillMaxWidth().testTag(if (index == 0) "timeline-photo-caption" else "timeline-photo-caption-${photo.id}"))
                    TextButton(onClick = { onChange(draft.copy(photos = draft.photos.filterNot { it.id == photo.id })) }) { Text("Remove photo") }
                }
                if (staging) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.testTag("timeline-entry-more-details")) { Text(if (showDetails) "Hide entry details" else "Add date, dish, or action") }
                if (showDetails) {
                    val actions = linkedMapOf("None" to null) + PlaybookCodec.actions.entries.associate { it.value to it.key } + mapOf("Preheat" to "preheat_start", "Season" to "season", "Trim" to "trim", "Marinate" to "marinate", "Add fuel" to "add_fuel")
                    SimpleDropdownField("Action", actions.entries.firstOrNull { it.value == draft.action }?.key ?: "None", actions.keys.toList(), { label -> onChange(draft.copy(action = actions[label], category = if (actions[label] != null) "action" else draft.category)) }, testTag = "timeline-entry-type")
                    Text("For", style = MaterialTheme.typography.labelLarge)
                    FilterChip(selected = draft.dishId == null && draft.dishIds.isEmpty(), onClick = { onChange(draft.copy(dishId = null, dishIds = emptyList())) }, label = { Text("Whole cook") })
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
        },
        confirmButton = { TextButton(onClick = { viewModel.saveNote(draft, editing, onSaved) }, enabled = valid && !busy && !staging,
            modifier = Modifier.testTag(if (draft.category == "temperature") "temperature-save" else "timeline-entry-save")) { Text("Save entry") } },
        dismissButton = {
            Row {
                TextButton(onClick = { viewModel.discardNoteDraft(draft.id); onClose() }, enabled = !busy && !staging) { Text("Discard") }
                TextButton(onClick = { keepDraft() }, enabled = !busy && !staging) { Text("Keep draft") }
            }
        },
    )
    if (showPhotoSource) PhotoSourceDialog(onDismiss = { showPhotoSource = false; if (photoFirst && draft.photos.isEmpty()) onClose() },
        onTakePhoto = { showPhotoSource = false; camera() }, onChooseFromLibrary = { showPhotoSource = false; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
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
