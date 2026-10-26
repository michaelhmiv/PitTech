package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel
import com.pittech.PitTechApplication
import com.pittech.data.*
import com.pittech.domain.*
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun PrepComboTools(items: List<IngredientDraft>, onChange: (List<IngredientDraft>) -> Unit) {
    val app = LocalContext.current.applicationContext as PitTechApplication
    val records by remember(app) { app.database.companionDao().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val combos = records.filter { it.kind == "prep_combo" }
    val scope = rememberCoroutineScope()
    var selecting by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<PrepCombo?>(null) }
    var name by remember { mutableStateOf("") }
    var yield by remember { mutableStateOf("") }
    var yieldUnit by remember { mutableStateOf("servings") }
    var scaleTo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { selecting = true; error = null }, modifier = Modifier.testTag("prep-use-combo")) { Text("Use saved ingredient combo") }
    if (items.any { it.name.isNotBlank() }) TextButton(onClick = { saving = true; error = null }, modifier = Modifier.testTag("prep-save-combo")) { Text("Save this ingredient combo") }
    // PL-22 (DD-6): combo list, combo detail, and the save-combo form are
    // list / short-editor surfaces — sheets, not dialogs.
    if (selecting) SheetSurface(onDismiss = { selecting = false }, title = "Ingredient combos", actions = { TextButton(onClick = { selecting = false }) { Text("Close") } }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (combos.isEmpty()) Text("Save ingredients from a dish to reuse your rub, brine, or marinade here.")
            combos.forEach { record -> OutlinedButton(onClick = { selected = CookPreparation.decodeCombo(record.payload); scaleTo = ""; selecting = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(record.title) } }
        }
    }
    selected?.let { combo -> SheetSurface(onDismiss = { selected = null }, title = combo.name, actions = {
        TextButton(onClick = { selected = null }) { Text("Cancel") }
        TextButton(onClick = {
            runCatching {
                val copied = if (scaleTo.isBlank()) combo.items else CookPreparation.scale(combo, scaleTo.toDoubleOrNull() ?: throw IllegalArgumentException("Enter a positive yield."), combo.yieldUnit.orEmpty())
                onChange(items.filter { it.name.isNotBlank() } + copied); selected = null
            }.onFailure { error = ErrorCopy.failureText(it, "That combo couldn't be added. Your dish is unchanged — check the yield and try again.") }
        }) { Text("Add to dish") }
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            combo.items.forEach { Text("${it.name} · ${it.amountText} ${it.amountUnit}") }
            Text("Adds a copy to this dish. You can edit the quantities before saving.")
            if (combo.yieldAmount != null) {
                Text("Recipe yield: ${combo.yieldAmount} ${combo.yieldUnit}")
                OutlinedTextField(scaleTo, { scaleTo = it }, label = { Text("New yield in ${combo.yieldUnit} (optional)") }, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    } }
    if (saving) SheetSurface(onDismiss = { if (!busy) saving = false }, title = "Save ingredient combo", dismissEnabled = !busy, actions = {
        TextButton(enabled = !busy, onClick = { saving = false }) { Text("Cancel") }
        TextButton(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                try {
                    require(yield.isBlank() || yield.toDoubleOrNull() != null) { "Enter a positive recipe yield." }
                    app.preparationRepository.saveCombo(PrepCombo(name.trim(), items.filter { it.name.isNotBlank() }, yield.toDoubleOrNull(), yieldUnit.takeIf { yield.isNotBlank() })); saving = false
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { error = ErrorCopy.failureText(failure, "The combo couldn't be saved. Your ingredients are unchanged — try again.") }
                finally { busy = false }
            }
        }) { Text("Save combo") }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Combo name") }, modifier = Modifier.fillMaxWidth().testTag("prep-combo-name"))
            OutlinedTextField(yield, { yield = it }, label = { Text("Recipe yield (optional)") }, modifier = Modifier.fillMaxWidth())
            if (yield.isNotBlank()) OutlinedTextField(yieldUnit, { yieldUnit = it }, label = { Text("Yield unit") }, modifier = Modifier.fillMaxWidth())
            Text("An explicit yield allows scaling ingredient quantities when you reuse the combo.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun CookChecklistTools(data: CookDetailData, viewModel: CooksViewModel) {
    val records by viewModel.companionRecords.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val record = records.firstOrNull { it.kind == "checklist" && it.cookId == data.cook.id }
    val items = record?.let { CookPreparation.decodeChecklist(it.payload) }.orEmpty()
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<List<ChecklistItem>>(emptyList()) }
    var addition by remember { mutableStateOf("") }
    OutlinedButton(onClick = { if (record == null) viewModel.createCookChecklist(data.cook.id); open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("prep-checklist-open")) {
        Text(if (record == null) "Make prep checklist" else "Prep checklist · ${items.count { it.done }}/${items.size}")
    }
    // PL-22 (DD-6): the checklist and its editor are list surfaces — sheets.
    if (open) SheetSurface(onDismiss = { open = false }, title = "Prep checklist", actions = { TextButton(onClick = { open = false }) { Text("Close") } }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ingredients, supplies, and preparation notes for this cook.")
            items.forEach { item -> Row {
                Checkbox(item.done, enabled = !busy, onCheckedChange = { viewModel.checkPrepItem(data.cook.id, item.id, it) }, modifier = Modifier.semantics { contentDescription = item.text })
                Text(item.text, Modifier.weight(1f).padding(top = 12.dp))
            } }
            TextButton(enabled = !busy, onClick = { draft = items; editing = true; addition = "" }, modifier = Modifier.testTag("prep-checklist-edit")) { Text("Edit list") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (editing) SheetSurface(onDismiss = { editing = false }, title = "Edit prep checklist", actions = {
        TextButton(onClick = { editing = false }) { Text("Cancel") }
        TextButton(enabled = !busy && draft.all { it.text.isNotBlank() }, onClick = { viewModel.saveCookChecklist(data.cook.id, draft + if (addition.isNotBlank()) listOf(ChecklistItem(text = addition.trim())) else emptyList()) { if (it) editing = false } }) { Text("Save list") }
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            draft.forEach { item -> Row {
                OutlinedTextField(item.text, { text -> draft = draft.map { if (it.id == item.id) it.copy(text = text) else it } }, modifier = Modifier.weight(1f))
                TextButton(onClick = { draft = draft.filterNot { it.id == item.id } }) { Text("Remove") }
            } }
            OutlinedTextField(addition, { addition = it }, label = { Text("Add supply or task") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { if (addition.isNotBlank()) { draft = draft + ChecklistItem(text = addition.trim()); addition = "" } }) { Text("Add item") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun EquipmentTools(viewModel: CooksViewModel) {
    val summaries by viewModel.equipmentSummaries.collectAsStateWithLifecycle()
    val cooks by viewModel.cooks.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Pair<String?, EquipmentProfile>?>(null) }
    var fuel by remember { mutableStateOf<EquipmentSummary?>(null) }
    val records by viewModel.companionRecords.collectAsStateWithLifecycle()
    LaunchedEffect(records, cooks) { if (open) viewModel.refreshEquipment() }
    OutlinedButton(onClick = { viewModel.refreshEquipment(); open = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp).testTag("equipment-open")) { Text("My equipment") }
    // PL-22 (DD-6): the equipment list is a management surface — a sheet;
    // its profile editor below is multi-field and goes full screen.
    if (open) SheetSurface(onDismiss = { open = false }, title = "My equipment", actions = { TextButton(onClick = { open = false }) { Text("Close") } }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Save smoker details for setup, fuel notes, and cleaning reminders based on recorded cooking hours.")
            summaries.forEach { s ->
                Text(s.profile.name, style = MaterialTheme.typography.titleMedium)
                Text(listOf(s.profile.fuelType, s.profile.hopperBlend).filter { it.isNotBlank() }.joinToString(" · "))
                Text("${decimal(s.cookingHours)} recorded cooking hours" + if (s.maintenanceDue) " · cleaning due" else "")
                s.profile.cleanEveryHours?.let { Text("${decimal((s.cookingHours - s.profile.maintainedAtCookingHours).coerceAtLeast(0.0))} hours since maintenance · interval ${decimal(it)} hours") }
                s.burnRangeKgPerHour?.let { Text("Recorded fuel use: ${decimal(it.first)}–${decimal(it.second)} kg/hour across ${s.recordedFuelCooks} cooks. Conditions can change consumption.") }
                val entries = records.filter { it.kind == "fuel" }.map { CookPreparation.decodeFuel(it.payload) }.filter { it.equipmentName.equals(s.profile.name, true) }
                entries.sortedByDescending { it.at }.take(3).forEach { Text("${it.type.replaceFirstChar { c -> c.uppercase() }} ${decimal(it.kilograms)} kg · ${java.text.DateFormat.getDateInstance().format(java.util.Date(it.at))}", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { edit = s.id to s.profile }) { Text("Edit profile") }
                TextButton(onClick = { fuel = s }) { Text("Record fuel") }
                TextButton(enabled = !busy, onClick = { viewModel.completeMaintenance(s.id) }) { Text("Mark maintained") }
                HorizontalDivider()
            }
            OutlinedButton(onClick = { edit = null to EquipmentProfile("") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("equipment-add")) { Text("Add equipment") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    edit?.let { (id, initial) -> EquipmentEditor(initial, busy, error, { edit = null }) { profile -> viewModel.saveEquipment(profile, id) { if (it) edit = null } } }
    fuel?.let { s -> FuelEditor(s.profile.name, cooks.filter { it.cook.smokerName.equals(s.profile.name, true) }.map { it.cook }, busy, error, { fuel = null }) { e -> viewModel.recordEquipmentFuel(e) { if (it) fuel = null } } }
}

@Composable
private fun EquipmentEditor(initial: EquipmentProfile, busy: Boolean, error: String?, onClose: () -> Unit, onSave: (EquipmentProfile) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var fuel by remember { mutableStateOf(initial.fuelType) }
    var blend by remember { mutableStateOf(initial.hopperBlend) }
    var interval by remember { mutableStateOf(initial.cleanEveryHours?.toString().orEmpty()) }
    var localError by remember { mutableStateOf<String?>(null) }
    FullScreenSurface(onDismiss = onClose, title = "Equipment profile", actions = {
        TextButton(onClick = onClose) { Text("Cancel") }
        TextButton(enabled = !busy, onClick = {
            runCatching {
                require(interval.isBlank() || interval.toDoubleOrNull() != null) { "Enter a positive number of hours." }
                val p = initial.copy(name = name.trim(), fuelType = fuel.trim(), hopperBlend = blend.trim(), cleanEveryHours = interval.toDoubleOrNull()); CookPreparation.encodeEquipment(p); onSave(p)
            }.onFailure { localError = ErrorCopy.failureText(it, "That profile couldn't be saved. The saved profile is unchanged — check the details and try again.") }
        }, modifier = Modifier.testTag("equipment-save")) { Text("Save profile") }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Smoker name") }, enabled = initial.name.isBlank(), supportingText = { if (initial.name.isNotBlank()) Text("This name connects your recorded cooks to this profile.") }, modifier = Modifier.fillMaxWidth().testTag("equipment-name"))
            OutlinedTextField(fuel, { fuel = it }, label = { Text("Fuel type") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(blend, { blend = it }, label = { Text("Wood or pellet blend") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(interval, { interval = it }, label = { Text("Clean every cooking hours (optional)") }, modifier = Modifier.fillMaxWidth())
            (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun FuelEditor(equipment: String, cooks: List<CookEntity>, busy: Boolean, error: String?, onClose: () -> Unit, onSave: (FuelEntry) -> Unit) {
    var type by remember { mutableStateOf("Added") }
    var amount by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("lb") }
    var cookId by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val labels = cooks.map { it.title + " · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it.startedAtUtcMillis)) }
    SheetSurface(onDismiss = onClose, title = "Record fuel · $equipment", actions = {
        TextButton(onClick = onClose) { Text("Cancel") }
        TextButton(enabled = !busy, onClick = {
            runCatching {
                require(type != "Used" || cookId != null) { "Choose the cook that used this fuel." }
                val kg = ServeTimePlanner.weightKg(amount.toDoubleOrNull(), unit) ?: throw IllegalArgumentException("Enter a positive fuel quantity.")
                val entry = FuelEntry(equipment, kg, cookId, System.currentTimeMillis(), type.lowercase()); CookPreparation.encodeFuel(entry); onSave(entry)
            }.onFailure { localError = ErrorCopy.failureText(it, "That fuel entry couldn't be saved. Nothing was recorded — check the details and try again.") }
        }) { Text("Save fuel") }
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceField("Fuel", type, listOf("Added", "Used")) { type = it }
            OutlinedTextField(amount, { amount = it }, label = { Text("Quantity") }, modifier = Modifier.fillMaxWidth())
            ChoiceField("Unit", unit, listOf("lb", "kg")) { unit = it }
            ChoiceField("Cook", cooks.firstOrNull { it.id == cookId }?.let { labels[cooks.indexOf(it)] } ?: "Choose cook", listOf("Choose cook") + labels) { label -> cookId = labels.indexOf(label).takeIf { it >= 0 }?.let { cooks[it].id } }
            Text("Added fuel records refills. Used fuel records a measured amount consumed by a cook. Two completed cooks with measured use are needed for an estimate.")
            (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun decimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
