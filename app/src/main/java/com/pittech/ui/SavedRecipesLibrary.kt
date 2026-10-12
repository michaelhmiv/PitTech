package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel
import com.pittech.data.*
import com.pittech.domain.*
import kotlinx.coroutines.launch

internal data class RecipeEditorRequest(val snapshot: RecipeSnapshot, val recipeId: String? = null,
    val expectedRevisionId: String? = null, val assetRevisionId: String? = expectedRevisionId,
    val sourceCookId: String? = null, val sourceDishId: String? = null, val assets: List<RecipeAsset> = emptyList(),
    val sourcePhotos: List<PhotoEntity> = emptyList(), val selectedPhotoIds: Set<String> = emptySet(),
    val context: List<TimelineEventEntity> = emptyList(), val attemptDishId: String? = null)

@Composable internal fun SavedRecipesLibrary(viewModel: CooksViewModel) {
    val recipes by remember { viewModel.recipes.observeRecipes() }.collectAsStateWithLifecycle(emptyList())
    val revisions by remember { viewModel.recipes.observeRevisions() }.collectAsStateWithLifecycle(emptyList())
    val presets by remember { viewModel.recipes.observePresets() }.collectAsStateWithLifecycle(emptyList())
    val cooks by viewModel.cooks.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All categories") }
    var tag by remember { mutableStateOf("All tags") }
    var favorites by remember { mutableStateOf(false) }
    var archived by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var mealTitle by remember { mutableStateOf("") }
    var existingMealId by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<SavedRecipe?>(null) }
    var editor by remember { mutableStateOf<RecipeEditorRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun act(block: suspend () -> Unit) { if (busy) return; busy = true; scope.launch { try { block(); error = null } catch (e: Exception) { error = e.message } finally { busy = false } } }
    val snapshots = remember(revisions) { revisions.associate { it.id to RecipeSnapshotCodec.decode(it.snapshotJson) } }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("saved-recipes-toggle")) { Text("Saved Recipes · ${recipes.count { it.archivedAtUtcMillis == null }}") }
    if (expanded) {
        OutlinedTextField(query, { query = it }, label = { Text("Find a recipe") }, modifier = Modifier.fillMaxWidth().testTag("recipe-search"))
        SimpleDropdownField("Category", category, listOf("All categories") + snapshots.values.map { it.dish.foodType }.distinct().sorted(), { category = it })
        SimpleDropdownField("Tag", tag, listOf("All tags") + snapshots.values.flatMap { it.tags }.distinct().sorted(), { tag = it })
        Row { FilterChip(favorites, { favorites = !favorites }, label = { Text("Favorites") }); Spacer(Modifier.width(8.dp)); FilterChip(archived, { archived = !archived }, label = { Text("Archived") }) }
        val shown = recipes.filter { r -> val s = snapshots[r.currentRevisionId]
            (r.archivedAtUtcMillis != null) == archived && r.name.contains(query, true) && (!favorites || r.favorite) &&
                (category == "All categories" || s?.dish?.foodType == category) && (tag == "All tags" || tag in s?.tags.orEmpty()) }
        if (shown.isEmpty()) Text(if (recipes.isEmpty()) "Save selected dishes from a finished meal here. Your meal history stays available." else "No recipes match these filters.")
        shown.forEach { recipe ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { Checkbox(recipe.currentRevisionId in selected, { checked -> selected = if (checked) selected + recipe.currentRevisionId else selected - recipe.currentRevisionId }, enabled = !archived)
                    TextButton(onClick = { detail = recipe }, modifier = Modifier.weight(1f).testTag("recipe-open-" + recipe.id)) { Text(recipe.name) } }
                Text("Preferred version ${revisions.firstOrNull { it.id == recipe.currentRevisionId }?.revisionNumber ?: ""} · ${snapshots[recipe.currentRevisionId]?.dish?.foodType.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { act { if (archived) viewModel.recipes.unarchive(recipe.id) else viewModel.recipes.setFavorite(recipe.id, !recipe.favorite) } }) { Text(if (archived) "Restore recipe" else if (recipe.favorite) "Remove favorite" else "Favorite") }
            } }
        }
        if (selected.isNotEmpty()) {
            val temperatures = selected.mapNotNull { snapshots[it]?.preferredPitTemperatureF }.distinct()
            if (temperatures.size > 1) Text("These recipes use different smoker settings. Choose the setting for this meal when ready.")
            OutlinedTextField(mealTitle, { mealTitle = it }, label = { Text("Meal name (optional)") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { act { viewModel.startRecipeMeal(mealTitle, selected.toList()); selected = emptySet() } }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("recipe-start-meal")) { Text("Start meal with ${selected.size} recipes") }
            val active = cooks.filter { it.cook.status != CookStatus.COMPLETED }
            if (active.isNotEmpty()) {
                val choices = active.mapIndexed { index, c -> c.cook.id to "${c.cook.title} (${index + 1})" }
                SimpleDropdownField("Existing meal", choices.firstOrNull { it.first == existingMealId }?.second ?: "Choose a meal", listOf("Choose a meal") + choices.map { it.second }, { value -> existingMealId = choices.firstOrNull { it.second == value }?.first })
                OutlinedButton(onClick = { act { val id = existingMealId ?: return@act; selected.forEach { viewModel.addRecipeToMeal(id, it) }; selected = emptySet(); viewModel.openCook(id) } }, enabled = existingMealId != null && !busy) { Text("Add selected recipes") }
            }
        }
        presets.forEach { preset -> TextButton(onClick = { act { viewModel.startMealPreset(preset.id) } }, enabled = !busy) { Text("Repeat ${preset.name}") } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    detail?.let { recipe ->
        RecipeDetailsDialog(recipe, revisions.filter { it.recipeId == recipe.id }, snapshots, cooks.map { it.cook.id }.toSet(), busy,
            onClose = { detail = null }, onUse = { id -> act { viewModel.startRecipeMeal(recipe.name, listOf(id)); detail = null } },
            onEdit = { revision -> act { editor = RecipeEditorRequest(snapshots.getValue(revision.id), recipe.id, recipe.currentRevisionId, revision.id, revision.sourceCookId, revision.sourceDishId, viewModel.recipes.assets(revision.id)); detail = null } },
            onDuplicate = { name -> act { viewModel.recipes.duplicate(recipe.id, name); detail = null } },
            onArchive = { act { viewModel.recipes.archive(recipe.id); detail = null } })
    }
    editor?.let { request -> RecipeEditorDialog(request, viewModel, busy, error, onClose = { editor = null }, onSave = { edit -> act { saveRecipeEdit(request, edit, viewModel); editor = null } }) }
}

@Composable private fun RecipeDetailsDialog(recipe: SavedRecipe, history: List<RecipeRevision>, snapshots: Map<String, RecipeSnapshot>, cookIds: Set<String>, busy: Boolean,
    onClose: () -> Unit, onUse: (String) -> Unit, onEdit: (RecipeRevision) -> Unit, onDuplicate: (String) -> Unit, onArchive: () -> Unit) {
    var selected by remember(recipe.id) { mutableStateOf(recipe.currentRevisionId) }
    var duplicateName by remember(recipe.id) { mutableStateOf(recipe.name + " copy") }
    val revision = history.firstOrNull { it.id == selected }; val snapshot = snapshots[selected]
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(recipe.name) }, text = {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SimpleDropdownField("Version", history.firstOrNull { it.id == selected }?.let { "Version ${it.revisionNumber}" }.orEmpty(), history.sortedByDescending { it.revisionNumber }.map { "Version ${it.revisionNumber}" }, { label -> selected = history.first { "Version ${it.revisionNumber}" == label }.id })
            Text(if (selected == recipe.currentRevisionId) "Preferred version" else "Previous version")
            if (revision?.sourceCookId != null) Text(if (revision.sourceCookId in cookIds) "Saved from your meal history." else "Source meal is unavailable. Saved instructions and reference photos remain available.")
            snapshot?.let { s -> Text(s.description); Text(s.dish.prepNotes); s.dish.preparationItems.forEach { Text("${it.name} · ${it.amountText} ${it.amountUnit}") }; s.instructions.forEach { Text(it.title + it.details.takeIf { x -> x.isNotBlank() }?.let { x -> " · $x" }.orEmpty()) }; if (s.resultNotes.isNotBlank()) Text(s.resultNotes) }
            revision?.let { r -> TextButton(onClick = { onEdit(r) }, enabled = !busy) { Text("Edit as a new version") } }
            OutlinedTextField(duplicateName, { duplicateName = it }, label = { Text("Variation name") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { onDuplicate(duplicateName) }, enabled = !busy && duplicateName.isNotBlank()) { Text("Duplicate recipe") }
            TextButton(onClick = onArchive, enabled = !busy) { Text("Archive recipe") }
        }
    }, confirmButton = { TextButton(onClick = { onUse(selected) }, enabled = revision != null && !busy) { Text("Use this version") } }, dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Close") } })
}

@Composable internal fun MealRecipeActions(data: CookDetailData, viewModel: CooksViewModel) {
    var editor by remember(data.cook.id) { mutableStateOf<RecipeEditorRequest?>(null) }
    var expanded by remember(data.cook.id) { mutableStateOf(false) }
    var preset by remember(data.cook.id) { mutableStateOf(false) }
    var presetName by remember(data.cook.id) { mutableStateOf(data.cook.title) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val sources by remember { viewModel.recipes.observeSources() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    fun act(block: suspend () -> Unit) { if (busy) return; busy = true; scope.launch { try { block(); error = null } catch (e: Exception) { error = e.message } finally { busy = false } } }
    if (data.cook.status == CookStatus.COMPLETED || sources.any { source -> data.dishes.any { it.id == source.dishId } }) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("meal-recipe-actions")) { Text(if (data.cook.status == CookStatus.COMPLETED) "Save selected dishes as recipes" else "Recipes in this meal") }
        if (expanded) data.dishes.forEach { dish ->
            val source = sources.firstOrNull { it.dishId == dish.id }
            Text(dish.name, style = MaterialTheme.typography.titleSmall)
            if (data.cook.status == CookStatus.COMPLETED) {
                OutlinedButton(onClick = { act { val draft = RecipeExtraction.fromMeal(data, dish.id); editor = RecipeEditorRequest(draft.snapshot, sourceCookId = data.cook.id, sourceDishId = dish.id, sourcePhotos = data.photos, selectedPhotoIds = draft.photoIds, context = draft.contextEvents) } }, enabled = !busy, modifier = Modifier.testTag("dish-save-recipe-" + dish.id)) { Text("Save as new recipe") }
                if (source != null) TextButton(onClick = { act {
                    val recipe = viewModel.recipes.get(source.recipeId) ?: error("This saved recipe is unavailable.")
                    val revision = viewModel.recipes.revision(recipe.currentRevisionId) ?: error("This version is unavailable.")
                    val draft = RecipeExtraction.fromMeal(data, dish.id)
                    editor = RecipeEditorRequest(RecipeExtraction.mergeAttempt(RecipeSnapshotCodec.decode(revision.snapshotJson), draft.snapshot), recipe.id, revision.id, revision.id, data.cook.id, dish.id,
                        viewModel.recipes.assets(revision.id), data.photos, draft.photoIds, draft.contextEvents)
                } }, enabled = !busy) { Text("Review update to saved recipe") }
            } else if (source != null) TextButton(onClick = { act {
                val working = viewModel.recipeAttempt(dish.id) ?: RecipeSnapshotCodec.decode(viewModel.recipes.revision(source.recipeRevisionId)!!.snapshotJson)
                val actual = RecipeExtraction.fromMeal(data, dish.id).snapshot
                editor = RecipeEditorRequest(working.copy(dish = actual.dish, targets = actual.targets), attemptDishId = dish.id)
            } }, enabled = !busy) { Text("Adjust for this meal") }
        }
        if (expanded && data.cook.status == CookStatus.COMPLETED) TextButton(onClick = { preset = true }) { Text("Save meal combination") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (preset) AlertDialog(onDismissRequest = { if (!busy) preset = false }, title = { Text("Save meal combination") }, text = { OutlinedTextField(presetName, { presetName = it }, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { act { viewModel.saveMealPreset(data.cook.id, presetName); preset = false } }, enabled = !busy && presetName.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = { preset = false }, enabled = !busy) { Text("Cancel") } })
    editor?.let { request -> RecipeEditorDialog(request, viewModel, busy, error, onClose = { editor = null }, onSave = { edit -> act {
        if (request.attemptDishId != null) viewModel.updateRecipeAttempt(request.attemptDishId, edit.snapshot)
        else {
            val selected = RecipeExtraction.fromMeal(data, request.sourceDishId!!, edit.contextIds)
            val additions = selected.snapshot.observations.filter { it.sourceEventId in edit.contextIds }
            val instructions = selected.snapshot.instructions.filter { it.sourceEventId in edit.contextIds && edit.snapshot.instructions.none { original -> original.sourceEventId == it.sourceEventId } }
            val prepared = edit.copy(snapshot = edit.snapshot.copy(observations = (edit.snapshot.observations + additions).distinctBy { it.sourceEventId }.sortedBy { it.occurredAtUtcMillis }, instructions = edit.snapshot.instructions + instructions))
            saveRecipeEdit(request, prepared, viewModel)
        }
        editor = null
    } }) }
}

internal suspend fun saveRecipeEdit(request: RecipeEditorRequest, edit: RecipeEdit, viewModel: CooksViewModel) = viewModel.recipes.save(
    edit.snapshot, request.recipeId, request.expectedRevisionId, request.sourceCookId, request.sourceDishId,
    photoIds = edit.photoIds, retainAssetsFromRevisionId = request.assetRevisionId, keptAssetIds = edit.assetIds,
    assetCaptions = edit.captions, addedPhotoUris = edit.addedPhotoUris)
