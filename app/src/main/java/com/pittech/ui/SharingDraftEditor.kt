package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel
import com.pittech.data.CompanionRecord
import com.pittech.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable internal fun SavedSharingDocuments(viewModel: CooksViewModel) {
    val rows by remember { viewModel.sharing.observeAll() }.collectAsStateWithLifecycle(emptyList())
    val drafts = rows.filter { it.kind == SharingDraftCodec.DRAFT }
    val collections = rows.filter { it.kind == SharingDraftCodec.COLLECTION }.distinctBy { SharingDraftCodec.decode(it.payload).collectionId }
    var editing by remember { mutableStateOf<String?>(null) }; val scope = rememberCoroutineScope(); var error by remember { mutableStateOf<String?>(null) }
    if (drafts.isNotEmpty()) {
        Text("Sharing drafts", style = PitTechTextStyles.bannerCard)
        drafts.forEach { row -> OutlinedButton(onClick = { editing = row.id }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("resume-sharing-${row.id}")) { Text("Resume ${row.title}") } }
    }
    if (collections.isNotEmpty()) {
        Text("Meal collections", style = PitTechTextStyles.bannerCard)
        collections.forEach { row -> OutlinedButton(onClick = { scope.launch { try { editing = viewModel.sharing.duplicate(row.id) } catch (e: Exception) { error = ErrorCopy.failureText(e, "That sharing change couldn't be saved. Your drafts are unchanged — try again.") } } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Copy & customize ${row.title}") } }
    }
    error?.let { TurnInStatusStrip(it, pitTechTokens().flag) }
    editing?.let { SharingDraftEditor(it, viewModel) { editing = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SharingDraftEditor(id: String, viewModel: CooksViewModel, account: com.pittech.data.AccountManager = viewModel.account, publisher: com.pittech.data.PublicationWriter = viewModel.publicationWriter, publishingEnabled: Boolean = viewModel.publicDiscovery.enabled && account.enabled, onClose: () -> Unit) {
    var includeRecordedCook by remember(id) { mutableStateOf(false) }
    var record by remember(id) { mutableStateOf<CompanionRecord?>(null) }
    var document by remember(id) { mutableStateOf<SharingDocument?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }; var editId by remember { mutableStateOf<String?>(null) }
    var chooseRecipe by remember { mutableStateOf(false) }
    var cardSettings by remember { mutableStateOf(false) }; var cardPreview by remember { mutableStateOf(false) }
    var relayShare by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf(false) }
    // §4.5: when this draft is bound to a live publication, its posted
    // card (QR footer) is reachable from the same Publish panel.
    var postedBinding by remember(id) { mutableStateOf<com.pittech.data.PublicationReceipt?>(null) }
    var postedCard by remember { mutableStateOf(false) }
    LaunchedEffect(id, publishing) { if (!publishing) postedBinding = runCatching { publisher.binding(id) }.getOrNull() }
    val exporting by viewModel.busy.collectAsStateWithLifecycle()
    val exportError by viewModel.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope(); val lock = remember { Mutex() }
    val contentScroll = rememberScrollState()
    val recipes by remember { viewModel.recipes.observeRecipes() }.collectAsStateWithLifecycle(emptyList())
    suspend fun reload() { record = viewModel.sharing.get(id) ?: kotlin.error("Draft unavailable."); document = SharingDraftCodec.decode(record!!.payload); saved = true }
    suspend fun persist(value: SharingDocument = document!!) = lock.withLock {
        val previous = record ?: kotlin.error("Draft unavailable.")
        record = viewModel.sharing.save(id, value, previous.updatedAtUtcMillis); saved = true
    }
    fun act(block: suspend () -> Unit) { if (busy) return; busy = true; scope.launch { try { block(); error = null } catch (e: Exception) { error = ErrorCopy.failureText(e, "That sharing change couldn't be saved. Your drafts are unchanged — try again.") } finally { busy = false } } }
    LaunchedEffect(id) { try { reload() } catch (e: Exception) { error = ErrorCopy.failureText(e, "That sharing change couldn't be saved. Your drafts are unchanged — try again.") } }
    LaunchedEffect(document, editId, relayShare, publishing) {
        val value = document ?: return@LaunchedEffect
        if (editId != null || relayShare || publishing) return@LaunchedEffect
        saved = false; delay(500)
        withContext(NonCancellable) { try { persist(value); error = null } catch (e: Exception) { error = ErrorCopy.failureText(e, "That sharing change couldn't be saved. Your drafts are unchanged — try again.") } }
    }
    // Only the active editor sheet handles its dismissal callbacks.
    if (!publishing) ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { if (!publishing) act { persist(); onClose() } }) {
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Sharing draft", style = PitTechTextStyles.bannerTitle); Text(if (saved) "Saved on this device" else "Saving edits…", style = MaterialTheme.typography.bodySmall, color = pitTechTokens().inkMuted) }
                TextButton(onClick = { act { persist(); onClose() } }, enabled = !busy) { Text("Done") }
            }
            Column(Modifier.weight(1f).verticalScroll(contentScroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                document?.let { current ->
                    Text("Choose exactly what you want to share. Your original meal stays unchanged.", color = pitTechTokens().inkMuted)
                    TurnInLiteralBoxedField("Title", Modifier.fillMaxWidth()) {
                        OutlinedTextField(current.title, { document = current.copy(title = it.take(160)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("sharing-title"))
                    }
                    current.entries.forEachIndexed { index, entry ->
                        val assets by produceState(emptyList<com.pittech.data.CompanionPhoto>(), id, entry.id, record?.updatedAtUtcMillis) { value = viewModel.sharing.assets(id).filter { it.action == entry.id } }
                        TurnInCard(Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(entry.included, { included -> document = current.copy(entries = current.entries.map { if (it.id == entry.id) it.copy(included = included) else it }, heroPhotoId = current.heroPhotoId.takeUnless { !included && it in entry.photoIds }) }, modifier = Modifier.testTag("sharing-include-${entry.id}"))
                                    Text(entry.snapshot.name, style = PitTechTextStyles.bannerCard, modifier = Modifier.weight(1f))
                                }
                                TextButton(onClick = { act { persist(); editId = entry.id } }, enabled = !busy) { Text("Edit recipe, ingredients & instructions") }
                                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(entry.includeStory, { enabled -> document = current.copy(entries = current.entries.map { if (it.id == entry.id) it.copy(includeStory = enabled) else it }) }); Text("Include recorded cook story", modifier = Modifier.weight(1f)) }
                                if (entry.includeStory) Text("Recorded milestones, readings and results remain source facts.", style = MaterialTheme.typography.bodySmall, color = pitTechTokens().inkMuted)
                                assets.forEach { asset ->
                                    RecipePicture(viewModel.sharing.photoUri(asset.relativePath))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(asset.id in entry.photoIds, { selected -> document = current.copy(entries = current.entries.map { if (it.id == entry.id) it.copy(photoIds = if (selected) it.photoIds + asset.id else it.photoIds - asset.id) else it }, heroPhotoId = current.heroPhotoId.takeUnless { !selected && it == asset.id }) })
                                        Text(asset.caption.ifBlank { "Reference photo" }, modifier = Modifier.weight(1f))
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { val reordered = current.entries.toMutableList(); java.util.Collections.swap(reordered, index, index - 1); document = current.copy(entries = reordered) }, enabled = index > 0) { Text("Move up") }
                                    TextButton(onClick = { val reordered = current.entries.toMutableList(); java.util.Collections.swap(reordered, index, index + 1); document = current.copy(entries = reordered) }, enabled = index < current.entries.lastIndex) { Text("Move down") }
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { act { persist(); chooseRecipe = true } }, enabled = current.entries.size < 20 && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add a saved recipe") }
                    Text("Identity is chosen when you publish. File sharing and saved collections do not require an account.", style = MaterialTheme.typography.bodySmall, color = pitTechTokens().inkMuted)
                    if (current.sourceHistoryJson != null) {
                        val originalDishes = remember(current.sourceHistoryJson) { com.pittech.data.CookPackHistory.rows(org.json.JSONObject(current.sourceHistoryJson), "dishes").map { it.getString("id") }.toSet() }
                        val chosen = current.entries.filter { it.included }
                        val eligible = chosen.isNotEmpty() && chosen.all { it.includeStory && it.sourceDishId in originalDishes }
                        LaunchedEffect(eligible) { if (!eligible) includeRecordedCook = false }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(includeRecordedCook, { includeRecordedCook = it }, enabled = eligible && !exporting, modifier = Modifier.testTag("sharing-include-history"))
                            Text("Include this recorded meal", modifier = Modifier.weight(1f))
                        }
                        Text(if (eligible) "Recipients can choose to add this finished meal to history." else "Enable the recorded story for each selected original dish first.", style = MaterialTheme.typography.bodySmall, color = pitTechTokens().inkMuted)
                    }
                    TextButton({ cardSettings = !cardSettings }) { Text("Card appearance & note") }
                    if (cardSettings) ShareCardOptionsEditor(current) { document = it }
                    // PL-18: the exits have a hierarchy — the Cook Pack file
                    // is the primary exit, the share card and the code are
                    // secondary, and saving a collection is a local action,
                    // not a share. (Publish lives in its own panel below.)
                    Button(onClick = { act { persist(); viewModel.createShareCookPack(id, includeRecordedCook) } }, enabled = !busy && !exporting && current.entries.any { it.included }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sharing-export-pack")) { Text(if (exporting) "Preparing Cook Pack…" else "Share Cook Pack file") }
                    OutlinedButton({ act { persist(); cardPreview = true } }, enabled = !busy && current.entries.any { it.included }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sharing-preview-card")) { Text("Preview share card") }
                    OutlinedButton(onClick = { act { persist(); relayShare = true } }, enabled = !busy && !exporting && current.entries.any { it.included }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sharing-relay")) { Text("Share by code") }
                    exportError?.let { TurnInStatusStrip(it, pitTechTokens().flag) }
                    OutlinedButton(onClick = { act { persist(); viewModel.sharing.saveCollection(id); viewModel.sharing.discard(id); onClose() } }, enabled = current.entries.any { it.included } && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save meal collection") }
                    TextButton(onClick = { act { viewModel.sharing.discard(id); onClose() } }, enabled = !busy) { Text("Discard draft") }
                }
                error?.let { TurnInStatusStrip(it, pitTechTokens().flag) }
                Spacer(Modifier.height(24.dp))
            }
            // PL-18: Publish sits inside its own panel, below the file and
            // code exits in rank. The panel stays pinned: keep the next
            // action reachable while title editing owns the keyboard —
            // focus-driven bring-into-view scrolling must not move this
            // tap target (the publication flow tests guard exactly that).
            if(publishingEnabled && document!=null)TurnInCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Publish", style = PitTechTextStyles.bannerCard)
                    Text("Make this draft public on your cookbook. You preview exactly what posts first — nothing is published without that separate confirmation.", style = MaterialTheme.typography.bodySmall, color = pitTechTokens().inkMuted)
                    OutlinedButton(onClick={ focus.clearFocus(); keyboard?.hide(); act { persist(); publishing=true } },enabled=!busy && document!!.entries.any { it.included },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("sharing-publish")) { Text("Preview public publication") }
                    postedBinding?.takeIf { it.status != "pending_review" }?.let { receipt ->
                        OutlinedButton(onClick={ postedCard=true },enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("sharing-posted-card")) { Text("Share posted card") }
                    }
                }
            }
        }
    }
    if (cardPreview) ShareCardPreview(id,viewModel,onPack = { cardPreview = false; viewModel.createShareCookPack(id,includeRecordedCook) },onClose = { cardPreview = false },onProThemes = { viewModel.subscription.openPro(com.pittech.billing.ProPlacement.THEMES) })
    if (postedCard) postedBinding?.let { receipt -> ShareCardPreview(id,viewModel,onPack = { postedCard = false; viewModel.createShareCookPack(id,includeRecordedCook) },onClose = { postedCard = false },publication = com.pittech.data.ShareCardPublication(receipt.id)) }
    if (publishing) PublicationDialog(id,viewModel,account,publisher) { publishing=false; act { reload() } }
    if (relayShare) RelayShareDialog(id, includeRecordedCook, viewModel) { relayShare = false }
    if (chooseRecipe) SheetSurface(onDismiss = { chooseRecipe = false }, title = "Add a recipe", actions = { TextButton(onClick = { chooseRecipe = false }) { Text("Done") } }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            recipes.filter { it.archivedAtUtcMillis == null }.forEach { recipe -> TextButton(onClick = { act { viewModel.sharing.addRecipe(id, recipe.currentRevisionId, record!!.updatedAtUtcMillis); chooseRecipe = false; reload() } }, enabled = !busy) { Text(recipe.name) } }
            if (recipes.isEmpty()) Text("Save a dish as a recipe first.")
        }
    }
    editId?.let { key -> document?.entries?.firstOrNull { it.id == key }?.let { entry ->
        RecipeEditorDialog(RecipeEditorRequest(entry.snapshot), viewModel, busy, error, onClose = { editId = null }, onSave = { edit -> act {
            viewModel.sharing.editEntry(id, key, edit.snapshot, edit.addedPhotoUris, edit.captions, record!!.updatedAtUtcMillis); reload(); editId = null
        } })
    } }
}
