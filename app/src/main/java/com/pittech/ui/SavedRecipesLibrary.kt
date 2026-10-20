package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveable
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

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun SavedRecipesLibrary(viewModel: CooksViewModel, standalone: Boolean = false) {
    val recipes by remember { viewModel.recipes.observeRecipes() }.collectAsStateWithLifecycle(emptyList())
    val revisions by remember { viewModel.recipes.observeRevisions() }.collectAsStateWithLifecycle(emptyList())
    val presets by remember { viewModel.recipes.observePresets() }.collectAsStateWithLifecycle(emptyList())
    val cooks by viewModel.cooks.collectAsStateWithLifecycle()
    var expanded by remember(standalone) { mutableStateOf(standalone) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("All categories") }
    var tag by rememberSaveable { mutableStateOf("All tags") }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var archived by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var composing by rememberSaveable { mutableStateOf(false) }
    var sharingDraftId by remember { mutableStateOf<String?>(null) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var mealTitle by rememberSaveable { mutableStateOf("") }
    var existingMealId by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<SavedRecipe?>(null) }
    var editor by remember { mutableStateOf<RecipeEditorRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun act(block: suspend () -> Unit) { if (busy) return; busy = true; scope.launch { try { block(); error = null } catch (e: Exception) { error = e.message } finally { busy = false } } }
    val snapshots = remember(revisions) { revisions.associate { it.id to RecipeSnapshotCodec.decode(it.snapshotJson) } }
    if (standalone) Text("Your cookbook, ready for another cook.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("saved-recipes-toggle")) { Text("Saved Recipes · ${recipes.count { it.archivedAtUtcMillis == null }}") }
    if (expanded) {
        SavedSharingDocuments(viewModel)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Find a recipe") }, modifier = Modifier.weight(1f).testTag("recipe-search"))
            OutlinedIconButton(onClick = { filters = true }, modifier = Modifier.size(48.dp).testTag("recipe-filters")) { Icon(Icons.Filled.FilterList, "Filter recipes") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(favorites, { favorites = !favorites }, label = { Text("Favorites") }, modifier = Modifier.heightIn(min = 48.dp))
            FilterChip(composing, { composing = !composing; if (!composing) selected = emptyList() }, label = { Text(if (composing) "Cancel selection" else "Build a meal") }, modifier = Modifier.heightIn(min = 48.dp).testTag("recipe-compose"))
        }
        if (category != "All categories" || tag != "All tags" || archived) {
            Text(listOfNotNull(category.takeUnless { it == "All categories" }, tag.takeUnless { it == "All tags" }, "Archived".takeIf { archived }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        if (filters) {
            var pendingCategory by remember { mutableStateOf(category) }; var pendingTag by remember { mutableStateOf(tag) }; var pendingArchived by remember { mutableStateOf(archived) }
            AlertDialog(onDismissRequest = { filters = false }, title = { Text("Filter recipes") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SimpleDropdownField("Category", pendingCategory, listOf("All categories") + snapshots.values.map { it.dish.foodType }.distinct().sorted(), { pendingCategory = it })
                    SimpleDropdownField("Tag", pendingTag, listOf("All tags") + snapshots.values.flatMap { it.tags }.distinct().sorted(), { pendingTag = it })
                    FilterChip(pendingArchived, { pendingArchived = !pendingArchived }, label = { Text("Show archived recipes") })
                }
            }, confirmButton = { TextButton(onClick = { category = pendingCategory; tag = pendingTag; archived = pendingArchived; filters = false }) { Text("Apply filters") } }, dismissButton = { TextButton(onClick = { filters = false }) { Text("Cancel") } })
        }
        val shown = recipes.filter { r -> val s = snapshots[r.currentRevisionId]
            (r.archivedAtUtcMillis != null) == archived && r.name.contains(query, true) && (!favorites || r.favorite) &&
                (category == "All categories" || s?.dish?.foodType == category) && (tag == "All tags" || tag in s?.tags.orEmpty()) }
        if (shown.isEmpty()) Text(if (recipes.isEmpty()) "Save selected dishes from a finished meal here. Your meal history stays available." else "No recipes match these filters.")
        shown.forEach { recipe ->
            var assets by remember(recipe.currentRevisionId) { mutableStateOf(emptyList<RecipeAsset>()) }
            LaunchedEffect(recipe.currentRevisionId) { assets = viewModel.recipes.assets(recipe.currentRevisionId) }
            var menu by remember { mutableStateOf(false) }
            val snapshot = snapshots[recipe.currentRevisionId]
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    assets.firstOrNull()?.let { RecipePicture(viewModel.recipes.photoUri(it.relativeFilePath)) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (composing) Checkbox(recipe.currentRevisionId in selected, { checked -> selected = if (checked) selected + recipe.currentRevisionId else selected - recipe.currentRevisionId }, enabled = !archived, modifier = Modifier.testTag("recipe-select-${recipe.id}"))
                        TextButton(onClick = { detail = recipe }, modifier = Modifier.weight(1f).testTag("recipe-open-" + recipe.id)) { Text(recipe.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions for ${recipe.name}") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(if (archived) "Restore recipe" else if (recipe.favorite) "Remove favorite" else "Favorite") }, onClick = { menu = false; act { if (archived) viewModel.recipes.unarchive(recipe.id) else viewModel.recipes.setFavorite(recipe.id, !recipe.favorite) } })
                            }
                        }
                    }
                    snapshot?.let { s ->
                        Text(listOf(s.dish.foodType, s.dish.cut, if (s.dish.weightText.isNotBlank()) "${s.dish.weightText} ${s.dish.weightUnit}" else "").filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s.description.isNotBlank()) Text(s.description, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                    }
                    Text("Saved recipe · version ${revisions.firstOrNull { it.id == recipe.currentRevisionId }?.revisionNumber ?: 1}${if (recipe.favorite) " · favorite" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        if (selected.isNotEmpty()) {
            val temperatures = selected.mapNotNull { snapshots[it]?.preferredPitTemperatureF }.distinct()
            if (temperatures.size > 1) Text("These recipes use different smoker settings. Choose the setting for this meal when ready.")
            OutlinedTextField(mealTitle, { mealTitle = it }, label = { Text("Meal name (optional)") }, modifier = Modifier.fillMaxWidth().testTag("recipe-meal-title"))
            Button(onClick = { act { viewModel.startRecipeMeal(mealTitle, selected); selected = emptyList() } }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recipe-start-meal")) { Text("Start meal with ${selected.size} recipes") }
            val active = cooks.filter { it.cook.status != CookStatus.COMPLETED }
            if (active.isNotEmpty()) {
                val choices = active.mapIndexed { index, c -> c.cook.id to "${c.cook.title} (${index + 1})" }
                SimpleDropdownField("Existing meal", choices.firstOrNull { it.first == existingMealId }?.second ?: "Choose a meal", listOf("Choose a meal") + choices.map { it.second }, { value -> existingMealId = choices.firstOrNull { it.second == value }?.first })
                OutlinedButton(onClick = { act { val id = existingMealId ?: return@act; selected.forEach { viewModel.addRecipeToMeal(id, it) }; selected = emptyList(); viewModel.openCook(id) } }, enabled = existingMealId != null && !busy) { Text("Add selected recipes") }
            }
        }
        presets.forEach { preset -> TextButton(onClick = { act { viewModel.startMealPreset(preset.id) } }, enabled = !busy) { Text("Repeat ${preset.name}") } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    detail?.let { recipe ->
        RecipeDetailsDialog(viewModel, recipe, revisions.filter { it.recipeId == recipe.id }, snapshots, cooks.map { it.cook.id }.toSet(), busy,
            onClose = { detail = null }, onUse = { id -> act { viewModel.startRecipeMeal(recipe.name, listOf(id)); detail = null } },
            onEdit = { revision -> act { editor = RecipeEditorRequest(snapshots.getValue(revision.id), recipe.id, recipe.currentRevisionId, revision.id, revision.sourceCookId, revision.sourceDishId, viewModel.recipes.assets(revision.id)); detail = null } },
            onDuplicate = { name -> act { viewModel.recipes.duplicate(recipe.id, name); detail = null } },
            onArchive = { act { viewModel.recipes.archive(recipe.id); detail = null } },
            onShare = { id -> act { sharingDraftId = viewModel.sharing.fromRecipe(id); detail = null } })
    }
    editor?.let { request -> RecipeEditorDialog(request, viewModel, busy, error, onClose = { editor = null }, onSave = { edit -> act { saveRecipeEdit(request, edit, viewModel); editor = null } }) }
    sharingDraftId?.let { id -> SharingDraftEditor(id, viewModel) { sharingDraftId = null } }
}

@Composable private fun RecipeDetailsDialog(viewModel: CooksViewModel, recipe: SavedRecipe, history: List<RecipeRevision>, snapshots: Map<String, RecipeSnapshot>, cookIds: Set<String>, busy: Boolean,
    onClose: () -> Unit, onUse: (String) -> Unit, onEdit: (RecipeRevision) -> Unit, onDuplicate: (String) -> Unit, onArchive: () -> Unit, onShare: (String) -> Unit) {
    var selected by remember(recipe.id) { mutableStateOf(recipe.currentRevisionId) }
    var duplicateName by remember(recipe.id) { mutableStateOf(recipe.name + " copy") }
    val revision = history.firstOrNull { it.id == selected }; val snapshot = snapshots[selected]
    var assets by remember(selected) { mutableStateOf(emptyList<RecipeAsset>()) }
    LaunchedEffect(selected) { assets = viewModel.recipes.assets(selected) }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(recipe.name) }, text = {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SimpleDropdownField("Version", history.firstOrNull { it.id == selected }?.let { recipeVersionLabel(it, history) }.orEmpty(), history.sortedWith(compareByDescending<RecipeRevision> { it.revisionNumber }.thenBy { it.id }).map { recipeVersionLabel(it, history) }, { label -> selected = history.first { recipeVersionLabel(it, history) == label }.id })
            Text(if (selected == recipe.currentRevisionId) "Preferred version" else "Previous version")
            if (revision?.sourceCookId != null) Text(if (revision.sourceCookId in cookIds) "Saved from your meal history." else "Source meal is unavailable. Saved instructions and reference photos remain available.")
            snapshot?.publicSource?.let { source ->
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Text(if (source.removed) "Saved from a removed recipe" else "Saved from " + source.displayName)
                if (!source.removed) TextButton(onClick = { uriHandler.openUri(source.url) }) { Text("View shared source") }
                Text("This saved version is yours. Changes to the public recipe don't replace it.", style = MaterialTheme.typography.bodySmall)
            }
            if(viewModel.publicDiscovery.enabled)snapshot?.takeIf { it.publicSource?.removed==false }?.let { PublicSourceReviewButton(it,viewModel.publicDiscovery) }
            snapshot?.let { s -> Text(s.description); Text(s.dish.prepNotes); s.dish.preparationItems.forEach { Text("${it.name} · ${it.amountText} ${it.amountUnit}") }; s.instructions.forEach { Text(it.title + it.details.takeIf { x -> x.isNotBlank() }?.let { x -> " · $x" }.orEmpty()) }; if (s.resultNotes.isNotBlank()) Text(s.resultNotes) }
            assets.forEach { asset -> RecipePicture(viewModel.recipes.photoUri(asset.relativeFilePath)); if (asset.caption.isNotBlank()) Text(asset.caption) }
            revision?.let { r -> TextButton(onClick = { onEdit(r) }, enabled = !busy) { Text("Edit as a new version") } }
            TextButton(onClick = { onShare(selected) }, enabled = revision != null && !busy, modifier = Modifier.testTag("recipe-share")) { Text("Share recipe") }
            OutlinedTextField(duplicateName, { duplicateName = it }, label = { Text("Variation name") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { onDuplicate(duplicateName) }, enabled = !busy && duplicateName.isNotBlank()) { Text("Duplicate recipe") }
            TextButton(onClick = onArchive, enabled = !busy) { Text("Archive recipe") }
        }
    }, confirmButton = { TextButton(onClick = { onUse(selected) }, enabled = revision != null && !busy) { Text("Use this version") } }, dismissButton = { TextButton(onClick = onClose, enabled = !busy, modifier=Modifier.testTag("recipe-details-close")) { Text("Close") } })
}

@Composable internal fun MealRecipeActions(data: CookDetailData, viewModel: CooksViewModel, initiallyExpanded: Boolean = false) {
    if (data.dishes.isEmpty()) return
    var editor by remember(data.cook.id) { mutableStateOf<RecipeEditorRequest?>(null) }
    var expanded by remember(data.cook.id, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
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
                OutlinedButton(onClick = { act { val draft = viewModel.recipeExtraction(data.cook.id, dish.id); editor = RecipeEditorRequest(draft.snapshot, sourceCookId = data.cook.id, sourceDishId = dish.id, sourcePhotos = data.photos, selectedPhotoIds = draft.photoIds, context = draft.contextEvents) } }, enabled = !busy, modifier = Modifier.testTag("dish-save-recipe-" + dish.id)) { Text("Save as new recipe") }
                if (source != null) TextButton(onClick = { act {
                    val recipe = viewModel.recipes.get(source.recipeId) ?: error("This saved recipe is unavailable.")
                    val revision = viewModel.recipes.revision(recipe.currentRevisionId) ?: error("This version is unavailable.")
                    val draft = viewModel.recipeExtraction(data.cook.id, dish.id)
                    editor = RecipeEditorRequest(RecipeExtraction.mergeAttempt(RecipeSnapshotCodec.decode(revision.snapshotJson), draft.snapshot), recipe.id, revision.id, revision.id, data.cook.id, dish.id,
                        viewModel.recipes.assets(revision.id), data.photos, draft.photoIds, draft.contextEvents)
                } }, enabled = !busy) { Text("Review update to saved recipe") }
            } else if (source != null) TextButton(onClick = { act {
                val working = viewModel.recipeAttempt(dish.id) ?: RecipeSnapshotCodec.decode(viewModel.recipes.revision(source.recipeRevisionId)!!.snapshotJson)
                val actual = viewModel.recipeExtraction(data.cook.id, dish.id).snapshot
                editor = RecipeEditorRequest(working.copy(dish = actual.dish, targets = actual.targets), attemptDishId = dish.id)
            } }, enabled = !busy, modifier = Modifier.testTag("recipe-adjust-${dish.id}")) { Text("Adjust for this meal") }
        }
        if (expanded && data.cook.status == CookStatus.COMPLETED) TextButton(onClick = { preset = true }) { Text("Save meal combination") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (preset) AlertDialog(onDismissRequest = { if (!busy) preset = false }, title = { Text("Save meal combination") }, text = { OutlinedTextField(presetName, { presetName = it }, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { act { viewModel.saveMealPreset(data.cook.id, presetName); preset = false } }, enabled = !busy && presetName.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = { preset = false }, enabled = !busy) { Text("Cancel") } })
    editor?.let { request -> RecipeEditorDialog(request, viewModel, busy, error, onClose = { editor = null }, onSave = { edit -> act {
        if (request.attemptDishId != null) viewModel.updateRecipeAttempt(request.attemptDishId, edit.snapshot)
        else {
            val selected = viewModel.recipeExtraction(data.cook.id, request.sourceDishId!!, edit.contextIds)
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
