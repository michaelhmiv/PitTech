package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
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
    // Recipes view mode (hands-on direction, 2026-10-27): a list of rows
    // with an inline photo, or a photo-forward grid — the cook's choice,
    // persisted on the device.
    val libraryContext = LocalContext.current
    val libraryPreferences = remember { libraryContext.getSharedPreferences("pittech-preferences", Context.MODE_PRIVATE) }
    var gridView by remember { mutableStateOf(libraryPreferences.getBoolean("recipes-grid-view", false)) }
    var existingMealId by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<SavedRecipe?>(null) }
    var editor by remember { mutableStateOf<RecipeEditorRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun act(block: suspend () -> Unit) { if (busy) return; busy = true; scope.launch { try { block(); error = null } catch (e: Exception) { error = ErrorCopy.failureText(e, "That change couldn't be saved. Your cookbook is unchanged — try again.") } finally { busy = false } } }
    val snapshots = remember(revisions) { revisions.associate { it.id to RecipeSnapshotCodec.decode(it.snapshotJson) } }
    // TURN-IN (Slice 3, Area 03 `dark-01-library`): the library is composed
    // as the frame's — a boxed search with the filter entry riding beside
    // it, the Favorites / Compose chips, meal-preset lanes, and recipe
    // cards with banner names and mono meta lines. Every control, handler,
    // and test tag keeps its existing home; the copy is the app's own.
    val tokens = pitTechTokens()
    if (standalone) Text("Your cookbook, ready for another cook.", style = PitTechTextStyles.body, color = tokens.inkMuted)
    else TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("saved-recipes-toggle")) { Text("Saved Recipes · ${recipes.count { it.archivedAtUtcMillis == null }}", style = PitTechTextStyles.bodyEmphasis) }
    if (expanded) {
        SavedSharingDocuments(viewModel)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Find a recipe") }, singleLine = true, modifier = Modifier.weight(1f).testTag("recipe-search"))
            OutlinedIconButton(onClick = { filters = true }, modifier = Modifier.size(48.dp).testTag("recipe-filters")) { Icon(Icons.Filled.FilterList, "Filter recipes") }
        }
        TurnInSegmentedControl(
            options = listOf("List", "Grid"),
            selectedIndex = if (gridView) 1 else 0,
            onSelect = { index ->
                gridView = index == 1
                libraryPreferences.edit().putBoolean("recipes-grid-view", gridView).apply()
            },
            modifier = Modifier.fillMaxWidth(),
            tags = listOf("recipes-view-list", "recipes-view-grid"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(favorites, { favorites = !favorites }, label = { Text("Favorites") }, modifier = Modifier.heightIn(min = 48.dp))
            FilterChip(composing, { composing = !composing; if (!composing) selected = emptyList() }, label = { Text(if (composing) "Cancel selection" else "Build a meal") }, modifier = Modifier.heightIn(min = 48.dp).testTag("recipe-compose"))
        }
        if (category != "All categories" || tag != "All tags" || archived) {
            Text(listOfNotNull(category.takeUnless { it == "All categories" }, tag.takeUnless { it == "All tags" }, "Archived".takeIf { archived }).joinToString(" · "), style = PitTechTextStyles.monoMeta, color = tokens.attentionText)
        }
        if (filters) {
            var pendingCategory by remember { mutableStateOf(category) }; var pendingTag by remember { mutableStateOf(tag) }; var pendingArchived by remember { mutableStateOf(archived) }
            // PL-22 (DD-6): filters are contextual controls — a sheet.
            SheetSurface(onDismiss = { filters = false }, title = "Filter recipes", actions = {
                TextButton(onClick = { filters = false }) { Text("Cancel") }
                TextButton(onClick = { category = pendingCategory; tag = pendingTag; archived = pendingArchived; filters = false }) { Text("Apply filters") }
            }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SimpleDropdownField("Category", pendingCategory, listOf("All categories") + snapshots.values.map { it.dish.foodType }.distinct().sorted(), { pendingCategory = it })
                    SimpleDropdownField("Tag", pendingTag, listOf("All tags") + snapshots.values.flatMap { it.tags }.distinct().sorted(), { pendingTag = it })
                    FilterChip(pendingArchived, { pendingArchived = !pendingArchived }, label = { Text("Show archived recipes") })
                }
            }
        }
        // Newest-added first, so a plainly named recipe ("Boston Butt")
        // stays findable as the library grows.
        val shown = recipes.filter { r -> val s = snapshots[r.currentRevisionId]
            (r.archivedAtUtcMillis != null) == archived && r.name.contains(query, true) && (!favorites || r.favorite) &&
                (category == "All categories" || s?.dish?.foodType == category) && (tag == "All tags" || tag in s?.tags.orEmpty()) }
            .sortedByDescending { it.createdAtUtcMillis }
        // PL-25: the Recipes empties are the shared StateCopy wording.
        // (A3 `dark-02-library-empty`: the filtered empty is a quiet card;
        // the brand empty keeps the same words in the same card shape.)
        if (shown.isEmpty()) {
            TurnInCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TurnInBannerText(if (recipes.isEmpty()) EmptyStateCopy.RECIPES_TITLE else EmptyStateCopy.RECIPES_FILTERED_TITLE)
                    Text(if (recipes.isEmpty()) EmptyStateCopy.RECIPES_BODY else EmptyStateCopy.RECIPES_FILTERED_BODY, style = PitTechTextStyles.body)
                }
            }
        }
        if (gridView) {
            shown.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { recipe ->
                        RecipeGridTile(
                            viewModel = viewModel,
                            recipe = recipe,
                            snapshot = snapshots[recipe.currentRevisionId],
                            composing = composing,
                            archived = archived,
                            isSelected = recipe.currentRevisionId in selected,
                            onToggleSelected = { checked -> selected = if (checked) selected + recipe.currentRevisionId else selected - recipe.currentRevisionId },
                            onOpen = { detail = recipe },
                            onFavoriteOrRestore = { act { if (archived) viewModel.recipes.unarchive(recipe.id) else viewModel.recipes.setFavorite(recipe.id, !recipe.favorite) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else shown.forEach { recipe ->
            var assets by remember(recipe.currentRevisionId) { mutableStateOf(emptyList<RecipeAsset>()) }
            LaunchedEffect(recipe.currentRevisionId) { assets = viewModel.recipes.assets(recipe.currentRevisionId) }
            var menu by remember { mutableStateOf(false) }
            val snapshot = snapshots[recipe.currentRevisionId]
            // The whole card frame is the tap target (button semantics via
            // TurnInCard's onClick): previously only the name text opened the
            // recipe, so taps on the photo or meta lines did nothing. In the
            // list view the photo rides inline as a small thumbnail.
            TurnInCard(Modifier.fillMaxWidth().testTag("recipe-open-" + recipe.id), onClick = { detail = recipe }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (composing) Checkbox(recipe.currentRevisionId in selected, { checked -> selected = if (checked) selected + recipe.currentRevisionId else selected - recipe.currentRevisionId }, enabled = !archived, modifier = Modifier.testTag("recipe-select-${recipe.id}"))
                        assets.firstOrNull()?.let { RecipeThumbnail(viewModel.recipes.photoUri(it.relativeFilePath), Modifier.size(56.dp)) }
                        TurnInBannerText(recipe.name, style = PitTechTextStyles.bannerTitle, modifier = Modifier.weight(1f))
                        if (recipe.favorite) Icon(Icons.Filled.Favorite, null, tint = tokens.flag, modifier = Modifier.size(20.dp))
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions for ${recipe.name}") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(if (archived) "Restore recipe" else if (recipe.favorite) "Remove favorite" else "Favorite") }, onClick = { menu = false; act { if (archived) viewModel.recipes.unarchive(recipe.id) else viewModel.recipes.setFavorite(recipe.id, !recipe.favorite) } })
                            }
                        }
                    }
                    snapshot?.let { s ->
                        Text(listOf(s.dish.foodType, s.dish.cut, if (s.dish.weightText.isNotBlank()) "${s.dish.weightText} ${s.dish.weightUnit}" else "").filter(String::isNotBlank).joinToString(" · "), style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                        if (s.description.isNotBlank()) Text(s.description, style = PitTechTextStyles.body, maxLines = 3)
                    }
                    Text("Saved recipe · version ${revisions.firstOrNull { it.id == recipe.currentRevisionId }?.revisionNumber ?: 1}${if (recipe.favorite) " · favorite" else ""} · Added ${formatRecipeDate(recipe.createdAtUtcMillis)}", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                }
            }
        }
        if (selected.isNotEmpty()) {
            TurnInCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TurnInMetaLabel("Build a meal")
                    val temperatures = selected.mapNotNull { snapshots[it]?.preferredPitTemperatureF }.distinct()
                    if (temperatures.size > 1) TurnInStatusStrip("These recipes use different smoker settings. Choose the setting for this meal when ready.", tokens.cobalt)
                    TurnInBoxedField("Meal name (optional)") {
                        OutlinedTextField(mealTitle, { mealTitle = it }, modifier = Modifier.fillMaxWidth().testTag("recipe-meal-title"))
                    }
                    Button(onClick = { viewModel.previewRecipeMeal(mealTitle, selected); selected = emptyList() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recipe-start-meal")) { Text("Start meal with ${selected.size} recipes") }
                    val active = cooks.filter { it.cook.status != CookStatus.COMPLETED }
                    if (active.isNotEmpty()) {
                        val choices = active.mapIndexed { index, c -> c.cook.id to "${c.cook.title} (${index + 1})" }
                        SimpleDropdownField("Existing meal", choices.firstOrNull { it.first == existingMealId }?.second ?: "Choose a meal", listOf("Choose a meal") + choices.map { it.second }, { value -> existingMealId = choices.firstOrNull { it.second == value }?.first })
                        OutlinedButton(onClick = { act { val id = existingMealId ?: return@act; selected.forEach { viewModel.addRecipeToMeal(id, it) }; selected = emptyList(); viewModel.openCook(id) } }, enabled = existingMealId != null && !busy) { Text("Add selected recipes") }
                    }
                }
            }
        }
        presets.forEach { preset ->
            TurnInCard(Modifier.fillMaxWidth(), onClick = { if (!busy) viewModel.previewMealPreset(preset.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TurnInMetaLabel("Meal preset")
                    Text("Repeat ${preset.name}", style = PitTechTextStyles.bannerCard, color = tokens.ink, modifier = Modifier.weight(1f))
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    detail?.let { recipe ->
        RecipeDetailsDialog(viewModel, recipe, revisions.filter { it.recipeId == recipe.id }, snapshots, cooks.map { it.cook.id }.toSet(), busy,
            onClose = { detail = null }, onUse = { id -> viewModel.previewRecipeMeal(recipe.name, listOf(id)); detail = null },
            onEdit = { revision -> act { editor = RecipeEditorRequest(snapshots.getValue(revision.id), recipe.id, recipe.currentRevisionId, revision.id, revision.sourceCookId, revision.sourceDishId, viewModel.recipes.assets(revision.id)); detail = null } },
            onDuplicate = { name -> act { viewModel.recipes.duplicate(recipe.id, name); detail = null } },
            onArchive = { act { viewModel.recipes.archive(recipe.id); detail = null } },
            onShare = { id -> act { sharingDraftId = viewModel.sharing.fromRecipe(id); detail = null } },
            onOpenCook = { id -> viewModel.openCook(id); detail = null })
    }
    editor?.let { request -> RecipeEditorDialog(request, viewModel, busy, error, onClose = { editor = null }, onSave = { edit -> act { saveRecipeEdit(request, edit, viewModel); editor = null } }) }
    sharingDraftId?.let { id -> SharingDraftEditor(id, viewModel) { sharingDraftId = null } }
}

private fun formatRecipeDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

private fun formatRecipeTime(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private fun formatRecipeDuration(millis: Long): String {
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours h $minutes min"
        hours > 0 -> "$hours h"
        else -> "$minutes min"
    }
}

/** The grid view's photo-forward tile: same destination and actions as the
 * list card — photo, name, meta, added date, favorite/restore menu, and
 * the meal-building checkbox when composing. */
@Composable
private fun RecipeGridTile(
    viewModel: CooksViewModel,
    recipe: SavedRecipe,
    snapshot: RecipeSnapshot?,
    composing: Boolean,
    archived: Boolean,
    isSelected: Boolean,
    onToggleSelected: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onFavoriteOrRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var assets by remember(recipe.currentRevisionId) { mutableStateOf(emptyList<RecipeAsset>()) }
    LaunchedEffect(recipe.currentRevisionId) { assets = viewModel.recipes.assets(recipe.currentRevisionId) }
    var menu by remember { mutableStateOf(false) }
    val tokens = pitTechTokens()
    TurnInCard(modifier.testTag("recipe-open-" + recipe.id), onClick = onOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            assets.firstOrNull()?.let { RecipeThumbnail(viewModel.recipes.photoUri(it.relativeFilePath), Modifier.fillMaxWidth().height(104.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (composing) Checkbox(isSelected, onToggleSelected, enabled = !archived, modifier = Modifier.testTag("recipe-select-${recipe.id}"))
                TurnInBannerText(recipe.name, style = PitTechTextStyles.bannerCard, modifier = Modifier.weight(1f), maxLines = 2)
                if (recipe.favorite) Icon(Icons.Filled.Favorite, null, tint = tokens.flag, modifier = Modifier.size(18.dp))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions for ${recipe.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(if (archived) "Restore recipe" else if (recipe.favorite) "Remove favorite" else "Favorite") }, onClick = { menu = false; onFavoriteOrRestore() })
                    }
                }
            }
            snapshot?.let { s ->
                Text(listOf(s.dish.foodType, s.dish.cut).filter(String::isNotBlank).joinToString(" · "), style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
            }
            Text("Added ${formatRecipeDate(recipe.createdAtUtcMillis)}", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
        }
    }
}

@Composable private fun RecipeDetailsDialog(viewModel: CooksViewModel, recipe: SavedRecipe, history: List<RecipeRevision>, snapshots: Map<String, RecipeSnapshot>, cookIds: Set<String>, busy: Boolean,
    onClose: () -> Unit, onUse: (String) -> Unit, onEdit: (RecipeRevision) -> Unit, onDuplicate: (String) -> Unit, onArchive: () -> Unit, onShare: (String) -> Unit, onOpenCook: ((String) -> Unit)? = null) {
    val tokens = pitTechTokens()
    var selected by remember(recipe.id) { mutableStateOf(recipe.currentRevisionId) }
    var duplicateName by remember(recipe.id) { mutableStateOf(recipe.name + " copy") }
    val revision = history.firstOrNull { it.id == selected }; val snapshot = snapshots[selected]
    var assets by remember(selected) { mutableStateOf(emptyList<RecipeAsset>()) }
    LaunchedEffect(selected) { assets = viewModel.recipes.assets(selected) }
    // PL-22 (DD-6): recipe detail is a reading surface — a tall sheet.
    SheetSurface(onDismiss = { if (!busy) onClose() }, title = recipe.name, dismissEnabled = !busy, fillHeightFraction = 0.92f, actions = {
        TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.testTag("recipe-details-close")) { Text("Close") }
        TextButton(onClick = { onUse(selected) }, enabled = revision != null && !busy) { Text("Use this version") }
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SimpleDropdownField("Version", history.firstOrNull { it.id == selected }?.let { recipeVersionLabel(it, history) }.orEmpty(), history.sortedWith(compareByDescending<RecipeRevision> { it.revisionNumber }.thenBy { it.id }).map { recipeVersionLabel(it, history) }, { label -> selected = history.first { recipeVersionLabel(it, history) == label }.id })
            // A3 `dark-04-recipe-detail`: the preferred version wears the
            // marigold pill; a previous version stays a quiet mono line.
            if (selected == recipe.currentRevisionId) {
                Surface(shape = RoundedCornerShape(8.dp), color = tokens.marigold, contentColor = tokens.onAccent) {
                    Text("Preferred version", style = PitTechTextStyles.monoMeta, color = tokens.onAccent, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            } else Text("Previous version", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
            Text("Added " + formatRecipeDate(recipe.createdAtUtcMillis), style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
            if (revision?.sourceCookId != null) {
                if (revision.sourceCookId in cookIds) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Saved from your meal history.", style = PitTechTextStyles.body, modifier = Modifier.weight(1f))
                        if (onOpenCook != null) TextButton(onClick = { onOpenCook(revision.sourceCookId) }) { Text("Open the cook") }
                    }
                }
                else TurnInStatusStrip("Source meal is unavailable. Saved instructions and reference photos remain available.", tokens.cobalt)
            }
            snapshot?.publicSource?.let { source ->
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Text(if (source.removed) "Saved from a removed recipe" else "Saved from " + source.displayName, style = PitTechTextStyles.body)
                if (!source.removed) TextButton(onClick = { uriHandler.openUri(source.url) }) { Text("View shared source") }
                Text("This saved version is yours. Changes to the public recipe don't replace it.", style = PitTechTextStyles.body, color = tokens.inkMuted)
            }
            if(viewModel.publicDiscovery.enabled)snapshot?.takeIf { it.publicSource?.removed==false }?.let { PublicSourceReviewButton(it,viewModel.publicDiscovery) }
            snapshot?.let { s ->
                // The frame's score cells, from the values the snapshot
                // actually carries: pit, serves, and the first target.
                val cells = listOfNotNull(
                    s.preferredPitTemperatureF?.let { "Pit" to "${it.toInt()}°" },
                    s.servings.takeIf { it.isNotBlank() }?.let { "Serves" to it },
                    s.targets.firstOrNull()?.let { "Target" to "${it.value.toInt()}${it.unit}" },
                )
                if (cells.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cells.forEach { (label, value) -> TurnInScoreCell(label, value, Modifier.weight(1f)) }
                }
                if (s.description.isNotBlank()) Text(s.description, style = PitTechTextStyles.body)
                // The source cook's story, from the facts frozen into the
                // snapshot when the recipe was saved: when it was cooked,
                // how long it ran, how it rated, and the recorded timeline
                // with the temperatures measured at each step. Authored
                // fields alone made saved-from-a-cook recipes read as
                // boilerplate; the story was always in the data.
                val story = s.observations.sortedBy { it.occurredAtUtcMillis }
                if (story.isNotEmpty() || s.rating != null || s.smokingAnchorUtcMillis != null) {
                    TurnInCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TurnInMetaLabel("From the cook")
                            val facts = listOfNotNull(
                                s.smokingAnchorUtcMillis?.let { "Cooked " + formatRecipeDate(it) },
                                story.mapNotNull { it.offsetMillis }.maxOrNull()?.let { "Ran " + formatRecipeDuration(it) },
                                s.rating?.let { "Rated " + String.format(java.util.Locale.getDefault(), "%.1f", it) + " / 5" },
                            )
                            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                            story.forEach { observation ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(formatRecipeTime(observation.occurredAtUtcMillis), style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                                        Text(observation.title, style = PitTechTextStyles.bodyEmphasis)
                                    }
                                    if (observation.details.isNotBlank()) Text(observation.details, style = PitTechTextStyles.body)
                                    observation.recordedTemperatures.forEach { reading ->
                                        Text(reading.probeName + " · " + String.format(java.util.Locale.getDefault(), "%.0f", reading.value) + "°", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                                    }
                                }
                            }
                        }
                    }
                }
                if (s.dish.preparationItems.isNotEmpty()) TurnInCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TurnInMetaLabel("Ingredients")
                        s.dish.preparationItems.forEach { item ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(item.name, style = PitTechTextStyles.bodyEmphasis)
                                Text("${item.amountText} ${item.amountUnit}", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                            }
                        }
                    }
                }
                if (s.dish.prepNotes.isNotBlank()) Text(s.dish.prepNotes, style = PitTechTextStyles.body)
                if (s.instructions.isNotEmpty()) TurnInCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TurnInMetaLabel("Instructions")
                        s.instructions.forEach { Text(it.title + it.details.takeIf { x -> x.isNotBlank() }?.let { x -> " · $x" }.orEmpty(), style = PitTechTextStyles.body) }
                    }
                }
                if (s.resultNotes.isNotBlank()) TurnInCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TurnInMetaLabel("Results note")
                        Text(s.resultNotes, style = PitTechTextStyles.body)
                    }
                }
            }
            assets.forEach { asset -> RecipePicture(viewModel.recipes.photoUri(asset.relativeFilePath)); if (asset.caption.isNotBlank()) Text(asset.caption, style = PitTechTextStyles.monoMeta, color = tokens.inkMuted) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                revision?.let { r -> Button(onClick = { onEdit(r) }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Edit as a new version") } }
                OutlinedButton(onClick = { onShare(selected) }, enabled = revision != null && !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("recipe-share")) { Text("Share recipe") }
            }
            TurnInBoxedField("Variation name") {
                OutlinedTextField(duplicateName, { duplicateName = it }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = { onDuplicate(duplicateName) }, enabled = !busy && duplicateName.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Duplicate recipe") }
            TextButton(onClick = onArchive, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Archive recipe", color = tokens.flag) }
        }
    }
}

@Composable internal fun MealRecipeActions(data: CookDetailData, viewModel: CooksViewModel, initiallyExpanded: Boolean = false, onCombinationSaved: ((String) -> Unit)? = null) {
    if (data.dishes.isEmpty()) return
    var editor by remember(data.cook.id) { mutableStateOf<RecipeEditorRequest?>(null) }
    var expanded by remember(data.cook.id, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    var preset by remember(data.cook.id) { mutableStateOf(false) }
    var presetName by remember(data.cook.id) { mutableStateOf(data.cook.title) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val sources by remember { viewModel.recipes.observeSources() }.collectAsStateWithLifecycle(emptyList())
    val libraryRecipes by remember { viewModel.recipes.observeRecipes() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    fun act(block: suspend () -> Unit) { if (busy) return; busy = true; scope.launch { try { block(); error = null } catch (e: Exception) { error = ErrorCopy.failureText(e, "That change couldn't be saved. Your cookbook is unchanged — try again.") } finally { busy = false } } }
    if (data.cook.status == CookStatus.COMPLETED || sources.any { source -> data.dishes.any { it.id == source.dishId } }) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("meal-recipe-actions")) { Text(if (data.cook.status == CookStatus.COMPLETED) "Save selected dishes as recipes" else "Recipes in this meal", style = PitTechTextStyles.bodyEmphasis) }
        if (expanded) data.dishes.forEach { dish ->
            val source = sources.firstOrNull { it.dishId == dish.id }
            TurnInBannerText(dish.name)
            if (data.cook.status == CookStatus.COMPLETED) {
                OutlinedButton(onClick = { act { val draft = viewModel.recipeExtraction(data.cook.id, dish.id); editor = RecipeEditorRequest(draft.snapshot.copy(name = differentiatedRecipeName(draft.snapshot.name, libraryRecipes.map { it.name })), sourceCookId = data.cook.id, sourceDishId = dish.id, sourcePhotos = data.photos, selectedPhotoIds = draft.photoIds, context = draft.contextEvents) } }, enabled = !busy, modifier = Modifier.testTag("dish-save-recipe-" + dish.id)) { Text("Save as new recipe") }
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
        confirmButton = { TextButton(onClick = { act { viewModel.saveMealPreset(data.cook.id, presetName); preset = false; onCombinationSaved?.invoke(presetName) } }, enabled = !busy && presetName.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = { preset = false }, enabled = !busy) { Text("Cancel") } })
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
