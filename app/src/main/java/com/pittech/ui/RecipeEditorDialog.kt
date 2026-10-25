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

@Composable internal fun RecipeEditorDialog(request: RecipeEditorRequest, viewModel: CooksViewModel, busy: Boolean, error: String?,
    onClose: () -> Unit, onSave: (RecipeEdit) -> Unit) {
    var snapshot by remember(request) { mutableStateOf(request.snapshot) }
    var tags by remember(request) { mutableStateOf(snapshot.tags.joinToString(", ")) }
    var pit by remember(request) { mutableStateOf(snapshot.preferredPitTemperatureF?.toString().orEmpty()) }
    var instructions by remember(request) { mutableStateOf(snapshot.instructions.map { InstructionEdit(it, it.offsetMillis?.let { m -> (m / 60000.0).toString() }.orEmpty(), it.durationMillis?.let { m -> (m / 60000.0).toString() }.orEmpty()) }) }
    var targets by remember(request) { mutableStateOf(snapshot.targets.map { TargetEdit(it.type, it.value.toString(), it.unit, it.explanation.orEmpty()) }) }
    var keptAssets by remember(request) { mutableStateOf(request.assets.map { it.id }.toSet()) }
    var keptPhotos by remember(request) { mutableStateOf(request.selectedPhotoIds) }
    var captions by remember(request) { mutableStateOf(request.assets.associate { it.id to it.caption }) }
    var addedPhotos by remember(request) { mutableStateOf(emptyList<String>()) }
    var contextIds by remember(request) { mutableStateOf(emptySet<String>()) }
    var keptFacts by remember(request) { mutableStateOf(snapshot.observations.map { it.sourceEventId }.toSet()) }
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
        TextButton(onClick = { onSave(RecipeEdit(prepared(), keptAssets, keptPhotos, captions, addedPhotos, contextIds)) }, enabled = valid && !busy, modifier = Modifier.testTag("recipe-save")) { Text(if (request.attemptDishId != null) "Apply to this meal" else "Save recipe") }
    }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(snapshot.name, { value -> snapshot = snapshot.copy(name = value, dish = if (snapshot.dish.name == snapshot.name) snapshot.dish.copy(name = value) else snapshot.dish) }, label = { Text("Recipe name") }, modifier = Modifier.fillMaxWidth().testTag("recipe-name"))
                OutlinedTextField(snapshot.description, { snapshot = snapshot.copy(description = it) }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth().testTag("recipe-description"))
                OutlinedTextField(tags, { tags = it }, label = { Text("Tags, separated by commas") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { showSetup = true }) { Text("Edit dish, ingredients & preparation") }
                OutlinedTextField(snapshot.servings, { snapshot = snapshot.copy(servings = it) }, label = { Text("Serving amount (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pit, { pit = it }, label = { Text("Preferred smoker temperature °F (optional)") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { showInstructions = !showInstructions }) { Text("Instructions, timings & targets") }
                if (showInstructions) {
                    instructions.forEachIndexed { index, edit ->
                        fun change(next: InstructionEdit) { instructions = instructions.mapIndexed { i, old -> if (i == index) next else old } }
                        OutlinedTextField(edit.original.title, { change(edit.copy(original = edit.original.copy(title = it))) }, label = { Text("Instruction") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(edit.original.details, { change(edit.copy(original = edit.original.copy(details = it))) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
                        SimpleDropdownField("Method", CookingMethods.labels[edit.original.method] ?: "Not set", listOf("Not set") + CookingMethods.labels.values,
                            { label -> change(edit.copy(original = edit.original.copy(method = CookingMethods.labels.entries.firstOrNull { it.value == label }?.key))) })
                        OutlinedTextField(edit.offset, { change(edit.copy(offset = it)) }, label = { Text("Minutes from On smoker (optional)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(edit.duration, { change(edit.copy(duration = it)) }, label = { Text("Expected duration, minutes (optional)") }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { instructions = instructions.filterIndexed { i, _ -> i != index } }) { Text("Remove instruction") }
                    }
                    TextButton(onClick = { instructions = instructions + InstructionEdit(RecipeInstruction(UUID.randomUUID().toString(), ""), "", "") }) { Text("+ Instruction") }
                    targets.forEachIndexed { index, target ->
                        fun change(next: TargetEdit) { targets = targets.mapIndexed { i, old -> if (i == index) next else old } }
                        OutlinedTextField(target.explanation, { change(target.copy(explanation = it)) }, label = { Text("Target description") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(target.value, { change(target.copy(value = it)) }, label = { Text("Target temperature") }, modifier = Modifier.fillMaxWidth())
                        SimpleDropdownField("Unit", target.unit, listOf("°F", "°C"), { change(target.copy(unit = it)) })
                        TextButton(onClick = { targets = targets.filterIndexed { i, _ -> i != index } }) { Text("Remove target") }
                    }
                    TextButton(onClick = { targets = targets + TargetEdit("personal_finish", "", "°F", "") }) { Text("+ Temperature target") }
                    OutlinedTextField(snapshot.resultNotes, { snapshot = snapshot.copy(resultNotes = it) }, label = { Text("Recipe notes & lessons") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
                if (request.attemptDishId == null) {
                    TextButton(onClick = { showPhotos = !showPhotos }) { Text("Reference photos") }
                    if (showPhotos) {
                        request.assets.forEach { asset ->
                            Row { Checkbox(asset.id in keptAssets, { selected -> keptAssets = if (selected) keptAssets + asset.id else keptAssets - asset.id }); Text("Keep reference photo") }
                            RecipePicture(viewModel.recipes.photoUri(asset.relativeFilePath))
                            OutlinedTextField(captions[asset.id] ?: asset.caption, { captions = captions + (asset.id to it) }, label = { Text("Caption") }, modifier = Modifier.fillMaxWidth())
                        }
                        request.sourcePhotos.forEach { photo ->
                            Row { Checkbox(photo.id in keptPhotos, { selected -> keptPhotos = if (selected) keptPhotos + photo.id else keptPhotos - photo.id }); Text(photo.caption ?: "Meal photo") }
                            if (photo.id in keptPhotos) { RecipePicture(viewModel.recipes.photoUri(photo.relativePath)); OutlinedTextField(captions[photo.id] ?: photo.caption.orEmpty(), { captions = captions + (photo.id to it) }, label = { Text("Caption") }, modifier = Modifier.fillMaxWidth()) }
                        }
                        addedPhotos.forEach { uri -> RecipePicture(uri); OutlinedTextField(captions[uri].orEmpty(), { captions = captions + (uri to it) }, label = { Text("Caption") }, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { addedPhotos = addedPhotos - uri }) { Text("Remove photo") } }
                        TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("+ Reference photos") }
                    }
                }
                TextButton(onClick = { showFacts = !showFacts }) { Text("Recorded history") }
                if (showFacts) {
                    Text("Recorded observations stay separate from editable instructions.", style = MaterialTheme.typography.bodySmall)
                    snapshot.observations.forEach { fact -> Row {
                        Checkbox(fact.sourceEventId in keptFacts, { selected -> keptFacts = if (selected) keptFacts + fact.sourceEventId else keptFacts - fact.sourceEventId })
                        Column { Text(fact.title); if (fact.details.isNotBlank()) Text(fact.details); fact.offsetMillis?.let { Text("${it / 60000.0} minutes from recorded On smoker") } }
                    } }
                    request.context.forEach { event -> Row { Checkbox(event.id in contextIds, { selected -> contextIds = if (selected) contextIds + event.id else contextIds - event.id }); Column { Text("Shared meal context: ${event.title}"); event.details?.let { Text(it) } } } }
                }
                if (!valid) Text("Review the recipe name, instruction titles and numeric quantities.", color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
    }
    if (showSetup) DishEditorDialog(snapshot.dish, snapshot.dish.weightUnit, { showSetup = false }, { dish -> addedPhotos = addedPhotos + dish.photoUris; snapshot = snapshot.copy(dish = dish.copy(photoUris = emptyList())); showSetup = false })
}

@Composable internal fun RecipePicture(uri: String) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }) }
    }.getOrNull() } }
    bitmap?.let { Image(it.asImageBitmap(), "Recipe reference photo", Modifier.fillMaxWidth().heightIn(max = 200.dp)) }
}
