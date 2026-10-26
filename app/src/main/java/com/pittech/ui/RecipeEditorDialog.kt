package com.pittech.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pittech.CooksViewModel
import com.pittech.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class RecipeEdit(val snapshot: RecipeSnapshot, val assetIds: Set<String>, val photoIds: Set<String>,
    val captions: Map<String, String>, val addedPhotoUris: List<String>, val contextIds: Set<String>)
private data class InstructionEdit(val original: RecipeInstruction, val offset: String, val duration: String)
private data class TargetEdit(val type: String, val value: String, val unit: String, val explanation: String)

/** PL-24 (#172): the recipe editor's opened state as a storable draft. */
private fun RecipeEditorRequest.initialDraft(): RecipeEditorDraft = RecipeEditorDraft(
    name = snapshot.name, description = snapshot.description, servings = snapshot.servings,
    resultNotes = snapshot.resultNotes, dish = snapshot.dish,
    tags = snapshot.tags.joinToString(", "),
    pit = snapshot.preferredPitTemperatureF?.toString().orEmpty(),
    instructions = snapshot.instructions.map {
        RecipeInstructionDraft(it.id, it.title, it.details, it.method, it.sourceEventId, it.action,
            it.offsetMillis?.let { m -> (m / 60000.0).toString() }.orEmpty(),
            it.durationMillis?.let { m -> (m / 60000.0).toString() }.orEmpty())
    },
    targets = snapshot.targets.map { RecipeTargetDraft(it.type, it.value.toString(), it.unit, it.explanation.orEmpty()) },
    keptAssets = assets.map { it.id }.toSet(),
    keptPhotos = selectedPhotoIds,
    captions = assets.associate { it.id to it.caption },
    addedPhotos = emptyList(),
    contextIds = emptySet(),
    keptFacts = snapshot.observations.map { it.sourceEventId }.toSet(),
)

@Composable internal fun RecipeEditorDialog(request: RecipeEditorRequest, viewModel: CooksViewModel, busy: Boolean, error: String?,
    onClose: () -> Unit, onSave: (RecipeEdit) -> Unit) {
    // PL-24 (#172): autosave + resume, like every multi-field editor.
    // The draft stores the working copies (instructions/targets with
    // their raw numeric text) without validating — an in-progress
    // recipe is allowed to be invalid; that is the point of the draft.
    val draftStore = rememberEditorDraftStore()
    val fullDraftKey = EditorDraftKeys.recipe(request.recipeId, request.attemptDishId)
    val initialDraft = remember(request) { request.initialDraft() }
    val storedDraft = remember(request) {
        draftStore.load(fullDraftKey)?.let(RecipeEditorDraftCodec::decode)?.takeIf { it != initialDraft }
    }
    var openedDraft by remember(request) { mutableStateOf(storedDraft ?: initialDraft) }
    var draftRestored by remember(request) { mutableStateOf(storedDraft != null) }
    var snapshot by remember(request) { mutableStateOf(request.snapshot.copy(name = openedDraft.name, description = openedDraft.description, servings = openedDraft.servings, resultNotes = openedDraft.resultNotes, dish = openedDraft.dish)) }
    var tags by remember(request) { mutableStateOf(openedDraft.tags) }
    var pit by remember(request) { mutableStateOf(openedDraft.pit) }
    var instructions by remember(request) { mutableStateOf(openedDraft.instructions.map { draft ->
        InstructionEdit(
            RecipeInstruction(draft.id, draft.title, draft.details, draft.method,
                draft.offsetText.toDoubleOrNull()?.let { (it * 60000).toLong() },
                draft.durationText.toDoubleOrNull()?.let { (it * 60000).toLong() },
                draft.sourceEventId, draft.action),
            draft.offsetText, draft.durationText,
        )
    }) }
    var targets by remember(request) { mutableStateOf(openedDraft.targets.map { TargetEdit(it.type, it.valueText, it.unit, it.explanation) }) }
    var keptAssets by remember(request) { mutableStateOf(openedDraft.keptAssets) }
    var keptPhotos by remember(request) { mutableStateOf(openedDraft.keptPhotos) }
    var captions by remember(request) { mutableStateOf(openedDraft.captions) }
    var addedPhotos by remember(request) { mutableStateOf(openedDraft.addedPhotos) }
    var contextIds by remember(request) { mutableStateOf(openedDraft.contextIds) }
    var keptFacts by remember(request) { mutableStateOf(openedDraft.keptFacts) }
    fun currentDraft() = RecipeEditorDraft(
        name = snapshot.name, description = snapshot.description, servings = snapshot.servings,
        resultNotes = snapshot.resultNotes, dish = snapshot.dish, tags = tags, pit = pit,
        instructions = instructions.map {
            RecipeInstructionDraft(it.original.id, it.original.title, it.original.details, it.original.method,
                it.original.sourceEventId, it.original.action, it.offset, it.duration)
        },
        targets = targets.map { RecipeTargetDraft(it.type, it.value, it.unit, it.explanation) },
        keptAssets = keptAssets, keptPhotos = keptPhotos, captions = captions,
        addedPhotos = addedPhotos, contextIds = contextIds, keptFacts = keptFacts,
    )
    LaunchedEffect(snapshot, tags, pit, instructions, targets, keptAssets, keptPhotos, captions, addedPhotos, contextIds, keptFacts) {
        val current = currentDraft()
        if (current != openedDraft) draftStore.save(fullDraftKey, RecipeEditorDraftCodec.encode(current))
        else draftStore.clear(fullDraftKey)
    }
    fun resetToInitialDraft() {
        openedDraft = initialDraft
        snapshot = request.snapshot.copy(name = initialDraft.name, description = initialDraft.description, servings = initialDraft.servings, resultNotes = initialDraft.resultNotes, dish = initialDraft.dish)
        tags = initialDraft.tags; pit = initialDraft.pit
        instructions = initialDraft.instructions.map { draft ->
            InstructionEdit(
                RecipeInstruction(draft.id, draft.title, draft.details, draft.method,
                    draft.offsetText.toDoubleOrNull()?.let { (it * 60000).toLong() },
                    draft.durationText.toDoubleOrNull()?.let { (it * 60000).toLong() },
                    draft.sourceEventId, draft.action),
                draft.offsetText, draft.durationText,
            )
        }
        targets = initialDraft.targets.map { TargetEdit(it.type, it.valueText, it.unit, it.explanation) }
        keptAssets = initialDraft.keptAssets; keptPhotos = initialDraft.keptPhotos
        captions = initialDraft.captions; addedPhotos = initialDraft.addedPhotos
        contextIds = initialDraft.contextIds; keptFacts = initialDraft.keptFacts
    }
    var showSetup by remember { mutableStateOf(false) }
    var showInstructions by remember { mutableStateOf(false) }
    var showPhotos by remember { mutableStateOf(false) }
    var showFacts by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> addedPhotos = addedPhotos + uris.map { it.toString() } }
    fun minutes(raw: String): Long? = raw.toDoubleOrNull()?.takeIf { it.isFinite() && it in -43200.0..43200.0 }?.let { (it * 60000).toLong() }
    fun prepared() = snapshot.copy(tags = tags.split(',').map(String::trim).filter(String::isNotBlank).distinct(),
        preferredPitTemperatureF = pit.toDoubleOrNull(), instructions = instructions.map { i -> i.original.copy(offsetMillis = minutes(i.offset), durationMillis = minutes(i.duration)) },
        targets = targets.map { t -> PlaybookTarget(0, t.type, t.value.toDoubleOrNull() ?: Double.NaN, t.unit, t.explanation.ifBlank { null }) },
        observations = snapshot.observations.filter { it.sourceEventId in keptFacts })
    val valid = (pit.isBlank() || pit.toDoubleOrNull()?.isFinite() == true) && instructions.all { i -> (i.offset.isBlank() || minutes(i.offset) != null) && (i.duration.isBlank() || minutes(i.duration)?.let { it >= 0 } == true) } && runCatching { RecipeSnapshotCodec.validate(prepared()) }.isSuccess
    // PL-22 (DD-6): the recipe editor is the app's largest editor — full screen.
    FullScreenSurface(onDismiss = { if (!busy) onClose() }, title = if (request.attemptDishId != null) "Adjust for this meal" else if (request.recipeId == null) "Save recipe" else "Edit a new version", dismissEnabled = !busy, actions = {
        TextButton(onClick = onClose, enabled = !busy) { Text("Cancel") }
        TextButton(onClick = { draftStore.clear(fullDraftKey); onSave(RecipeEdit(prepared(), keptAssets, keptPhotos, captions, addedPhotos, contextIds)) }, enabled = valid && !busy, modifier = Modifier.testTag("recipe-save")) { Text(if (request.attemptDishId != null) "Apply to this meal" else "Save recipe") }
    }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draftRestored) {
                    ResumeDraftBanner(onDiscard = {
                        draftStore.clear(fullDraftKey)
                        draftRestored = false
                        resetToInitialDraft()
                    })
                }
                // TURN-IN (Slice 3, Area 03 `dark-06-recipe-editor`): the
                // editor's fields are boxed fields — the label rides above
                // the box as a caps meta label (§4). Labels, tags, and
                // validation are the app's own, unchanged.
                TurnInBoxedField("Recipe name") {
                    OutlinedTextField(snapshot.name, { value -> snapshot = snapshot.copy(name = value, dish = if (snapshot.dish.name == snapshot.name) snapshot.dish.copy(name = value) else snapshot.dish) }, modifier = Modifier.fillMaxWidth().testTag("recipe-name"))
                }
                TurnInBoxedField("Description (optional)") {
                    OutlinedTextField(snapshot.description, { snapshot = snapshot.copy(description = it) }, modifier = Modifier.fillMaxWidth().testTag("recipe-description"))
                }
                TurnInBoxedField("Tags, separated by commas") {
                    OutlinedTextField(tags, { tags = it }, modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = { showSetup = true }) { Text("Edit dish, ingredients & preparation") }
                TurnInBoxedField("Serving amount (optional)") {
                    OutlinedTextField(snapshot.servings, { snapshot = snapshot.copy(servings = it) }, modifier = Modifier.fillMaxWidth())
                }
                TurnInBoxedField("Preferred smoker temperature °F (optional)") {
                    OutlinedTextField(pit, { pit = it }, modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = { showInstructions = !showInstructions }) { Text("Instructions, timings & targets") }
                if (showInstructions) {
                    instructions.forEachIndexed { index, edit ->
                        fun change(next: InstructionEdit) { instructions = instructions.mapIndexed { i, old -> if (i == index) next else old } }
                        TurnInCard(Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                TurnInBoxedField("Instruction") {
                                    OutlinedTextField(edit.original.title, { change(edit.copy(original = edit.original.copy(title = it))) }, modifier = Modifier.fillMaxWidth())
                                }
                                TurnInBoxedField("Notes") {
                                    OutlinedTextField(edit.original.details, { change(edit.copy(original = edit.original.copy(details = it))) }, modifier = Modifier.fillMaxWidth())
                                }
                                SimpleDropdownField("Method", CookingMethods.labels[edit.original.method] ?: "Not set", listOf("Not set") + CookingMethods.labels.values,
                                    { label -> change(edit.copy(original = edit.original.copy(method = CookingMethods.labels.entries.firstOrNull { it.value == label }?.key))) })
                                TurnInBoxedField("Minutes from On smoker (optional)") {
                                    OutlinedTextField(edit.offset, { change(edit.copy(offset = it)) }, modifier = Modifier.fillMaxWidth())
                                }
                                TurnInBoxedField("Expected duration, minutes (optional)") {
                                    OutlinedTextField(edit.duration, { change(edit.copy(duration = it)) }, modifier = Modifier.fillMaxWidth())
                                }
                                TextButton(onClick = { instructions = instructions.filterIndexed { i, _ -> i != index } }) { Text("Remove instruction") }
                            }
                        }
                    }
                    TextButton(onClick = { instructions = instructions + InstructionEdit(RecipeInstruction(UUID.randomUUID().toString(), ""), "", "") }) { Text("+ Instruction") }
                    targets.forEachIndexed { index, target ->
                        fun change(next: TargetEdit) { targets = targets.mapIndexed { i, old -> if (i == index) next else old } }
                        TurnInBoxedField("Target description") {
                            OutlinedTextField(target.explanation, { change(target.copy(explanation = it)) }, modifier = Modifier.fillMaxWidth())
                        }
                        TurnInBoxedField("Target temperature") {
                            OutlinedTextField(target.value, { change(target.copy(value = it)) }, modifier = Modifier.fillMaxWidth())
                        }
                        SimpleDropdownField("Unit", target.unit, listOf("°F", "°C"), { change(target.copy(unit = it)) })
                        TextButton(onClick = { targets = targets.filterIndexed { i, _ -> i != index } }) { Text("Remove target") }
                    }
                    TextButton(onClick = { targets = targets + TargetEdit("personal_finish", "", "°F", "") }) { Text("+ Temperature target") }
                    TurnInBoxedField("Recipe notes & lessons") {
                        OutlinedTextField(snapshot.resultNotes, { snapshot = snapshot.copy(resultNotes = it) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (request.attemptDishId == null) {
                    TextButton(onClick = { showPhotos = !showPhotos }) { Text("Reference photos") }
                    if (showPhotos) {
                        request.assets.forEach { asset ->
                            Row { Checkbox(asset.id in keptAssets, { selected -> keptAssets = if (selected) keptAssets + asset.id else keptAssets - asset.id }); Text("Keep reference photo") }
                            RecipePicture(viewModel.recipes.photoUri(asset.relativeFilePath))
                            TurnInBoxedField("Caption") {
                                OutlinedTextField(captions[asset.id] ?: asset.caption, { captions = captions + (asset.id to it) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        request.sourcePhotos.forEach { photo ->
                            Row { Checkbox(photo.id in keptPhotos, { selected -> keptPhotos = if (selected) keptPhotos + photo.id else keptPhotos - photo.id }); Text(photo.caption ?: "Meal photo") }
                            if (photo.id in keptPhotos) { RecipePicture(viewModel.recipes.photoUri(photo.relativePath)); TurnInBoxedField("Caption") { OutlinedTextField(captions[photo.id] ?: photo.caption.orEmpty(), { captions = captions + (photo.id to it) }, modifier = Modifier.fillMaxWidth()) } }
                        }
                        addedPhotos.forEach { uri -> RecipePicture(uri); TurnInBoxedField("Caption") { OutlinedTextField(captions[uri].orEmpty(), { captions = captions + (uri to it) }, modifier = Modifier.fillMaxWidth()) }; TextButton(onClick = { addedPhotos = addedPhotos - uri }) { Text("Remove photo") } }
                        TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("+ Reference photos") }
                    }
                }
                TextButton(onClick = { showFacts = !showFacts }) { Text("Recorded history") }
                if (showFacts) {
                    Text("Recorded observations stay separate from editable instructions.", style = PitTechTextStyles.body, color = pitTechTokens().inkMuted)
                    snapshot.observations.forEach { fact -> Row {
                        Checkbox(fact.sourceEventId in keptFacts, { selected -> keptFacts = if (selected) keptFacts + fact.sourceEventId else keptFacts - fact.sourceEventId })
                        Column { Text(fact.title, style = PitTechTextStyles.bodyEmphasis); if (fact.details.isNotBlank()) Text(fact.details, style = PitTechTextStyles.body); fact.offsetMillis?.let { Text("${it / 60000.0} minutes from recorded On smoker", style = PitTechTextStyles.monoMeta, color = pitTechTokens().inkMuted) } }
                    } }
                    request.context.forEach { event -> Row { Checkbox(event.id in contextIds, { selected -> contextIds = if (selected) contextIds + event.id else contextIds - event.id }); Column { Text("Shared meal context: ${event.title}", style = PitTechTextStyles.bodyEmphasis); event.details?.let { Text(it, style = PitTechTextStyles.body) } } } }
                }
                // A3 `dark-07-editor-error`: the failure is flagged in
                // place at the top of the form's end; the edits stay put.
                if (!valid) TurnInStatusStrip("Review the recipe name, instruction titles and numeric quantities.", pitTechTokens().flag)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
    }
    if (showSetup) DishEditorDialog(snapshot.dish, snapshot.dish.weightUnit, draftKey = "recipe", onDismiss = { showSetup = false }, onSave = { dish -> addedPhotos = addedPhotos + dish.photoUris; snapshot = snapshot.copy(dish = dish.copy(photoUris = emptyList())); showSetup = false })
}

@Composable internal fun RecipePicture(uri: String) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }) }
    }.getOrNull() } }
    bitmap?.let { Image(it.asImageBitmap(), "Recipe reference photo", Modifier.fillMaxWidth().heightIn(max = 200.dp)) }
}
