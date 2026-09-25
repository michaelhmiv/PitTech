package com.pittech.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel
import com.pittech.ads.shouldShowCookHistoryNativeAd
import com.pittech.R
import com.pittech.data.CookStatus
import com.pittech.data.CookWithDishes
import com.pittech.domain.CookEntryValidation
import com.pittech.domain.DishDraft
import com.pittech.domain.IngredientDraft
import com.pittech.domain.NewCookDraft
import java.text.DateFormat
import java.util.Date

private enum class MainSection(val title: String, val icon: ImageVector) {
    COOKS("Cooks", Icons.Filled.Home),
    INSIGHTS("Insights", Icons.Filled.List),
    DEVICES("Devices", Icons.Filled.Info),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PitTechApp(
    viewModel: CooksViewModel,
    adsEnabled: Boolean = false,
    privacyOptionsRequired: Boolean = false,
    onShowPrivacyOptions: () -> Unit = {},
) {
    val cooks by viewModel.cooks.collectAsStateWithLifecycle()
    val saving by viewModel.busy.collectAsStateWithLifecycle()
    val saveError by viewModel.error.collectAsStateWithLifecycle()
    val savedCookId by viewModel.savedCookId.collectAsStateWithLifecycle()
    val selectedCookId by viewModel.selectedCookId.collectAsStateWithLifecycle()
    val selectedCook by viewModel.selectedCook.collectAsStateWithLifecycle()
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    var selectedSection by rememberSaveable { mutableStateOf(MainSection.COOKS.name) }
    var isStartingCook by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("pittech-preferences", 0) }
    var temperatureUnit by rememberSaveable { mutableStateOf(preferences.getString("temperature-unit", "°F") ?: "°F") }
    var weightUnit by rememberSaveable { mutableStateOf(preferences.getString("weight-unit", "lb") ?: "lb") }

    LaunchedEffect(savedCookId) {
        if (savedCookId != null) {
            isStartingCook = false
            selectedSection = MainSection.COOKS.name
            viewModel.clearSavedCookSignal()
        }
    }

    if (selectedCookId != null) {
        val detail = selectedCook
        if (detail == null || detail.cook.id != selectedCookId) {
            Scaffold { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("Opening cook…", style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            CookDetailScreen(
                data = detail,
                viewModel = viewModel,
                preferredTemperatureUnit = temperatureUnit,
                preferredWeightUnit = weightUnit,
                onBack = viewModel::closeCook,
            )
        }
        return
    }

    if (isStartingCook) {
        StartCookScreen(
            saving = saving,
            saveError = saveError,
            preferredTemperatureUnit = temperatureUnit,
            preferredWeightUnit = weightUnit,
            onBack = {
                viewModel.clearSaveError()
                isStartingCook = false
            },
            onStartCook = viewModel::startCook,
        )
        return
    }

    val section = MainSection.valueOf(selectedSection)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(
                            painter = painterResource(R.drawable.pittech_smoker),
                            contentDescription = "PitTech smoker logo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(42.dp),
                        )
                        Column {
                            Text("PitTech", fontWeight = FontWeight.Bold)
                            Text(section.title, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                MainSection.entries.forEach { item ->
                    NavigationBarItem(
                        selected = section == item,
                        onClick = { selectedSection = item.name },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.title) },
                        modifier = Modifier.testTag("nav-${item.name.lowercase()}"),
                    )
                }
            }
        },
    ) { padding ->
        when (section) {
            MainSection.COOKS -> CooksHome(
                cooks = cooks,
                adsEnabled = adsEnabled,
                modifier = Modifier.padding(padding),
                onOpenCook = viewModel::openCook,
                onStartCook = {
                    viewModel.clearSaveError()
                    isStartingCook = true
                },
            )
            MainSection.INSIGHTS -> InsightsScreen(
                data = insights,
                onOpenCook = viewModel::openCook,
                modifier = Modifier.padding(padding),
            )
            MainSection.DEVICES -> FeaturePlaceholder(
                title = "Devices",
                message = "Controller setup is paused until your grill arrives.",
                note = "You can log temperatures by hand from any cook. Your cook history stays available offline.",
                modifier = Modifier.padding(padding),
            )
            MainSection.SETTINGS -> SettingsScreen(
                viewModel = viewModel,
                temperatureUnit = temperatureUnit,
                weightUnit = weightUnit,
                onTemperatureUnitChange = {
                    temperatureUnit = it
                    preferences.edit().putString("temperature-unit", it).apply()
                },
                onWeightUnitChange = {
                    weightUnit = it
                    preferences.edit().putString("weight-unit", it).apply()
                },
                privacyOptionsRequired = privacyOptionsRequired,
                onShowPrivacyOptions = onShowPrivacyOptions,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun CooksHome(
    cooks: List<CookWithDishes>,
    adsEnabled: Boolean,
    modifier: Modifier = Modifier,
    onOpenCook: (String) -> Unit,
    onStartCook: () -> Unit,
) {
    val activeCooks = cooks.filter { it.cook.status != CookStatus.COMPLETED }
    val finishedCooks = cooks.filter { it.cook.status == CookStatus.COMPLETED }
    val showHistoryAd = shouldShowCookHistoryNativeAd(
        adsEnabled = adsEnabled,
        activeCookCount = activeCooks.size,
        completedCookCount = finishedCooks.size,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(
            onClick = onStartCook,
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("start-cook"),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Start a cook")
        }

        Text(
            "Your cook records stay on this phone. No account is needed.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (activeCooks.isNotEmpty()) {
            SectionHeading("Active cook")
            activeCooks.forEach { cook -> CookSummaryCard(cook, onClick = { onOpenCook(cook.cook.id) }) }
        }

        if (finishedCooks.isNotEmpty()) {
            SectionHeading("Recent cooks")
            finishedCooks.forEachIndexed { index, cook ->
                CookSummaryCard(cook, onClick = { onOpenCook(cook.cook.id) })
                if (showHistoryAd && index == 3) {
                    PitTechNativeAdPlacement(adsEnabled = true)
                }
            }
        }

        if (cooks.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Your cook log is ready", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Add the meat, preparation, photos, and notes you want to remember. You can log a cook with or without a connected grill.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun CookSummaryCard(cook: CookWithDishes, onClick: () -> Unit) {
    val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(cook.cook.startedAtUtcMillis))
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("cook-card-${cook.cook.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(cook.cook.title, style = MaterialTheme.typography.titleLarge)
            Text(
                when (cook.cook.status) {
                    CookStatus.PAUSED -> "Paused · $time"
                    CookStatus.COMPLETED -> "Finished · $time"
                    else -> "In progress · $time"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cook.dishes.isNotEmpty()) {
                Text(
                    cook.dishes.joinToString { dish -> dish.cut?.takeIf(String::isNotBlank) ?: dish.name },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun FeaturePlaceholder(
    title: String,
    message: String,
    note: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Text(note, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartCookScreen(
    saving: Boolean,
    saveError: String?,
    preferredTemperatureUnit: String,
    preferredWeightUnit: String,
    onBack: () -> Unit,
    onStartCook: (NewCookDraft) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var smoker by rememberSaveable { mutableStateOf("") }
    var setpoint by rememberSaveable { mutableStateOf("") }
    var setpointUnit by rememberSaveable { mutableStateOf(preferredTemperatureUnit) }
    var notes by rememberSaveable { mutableStateOf("") }
    var fuelType by rememberSaveable { mutableStateOf("") }
    var woodBlend by rememberSaveable { mutableStateOf("") }
    var outdoorTemperature by rememberSaveable { mutableStateOf("") }
    var outdoorTemperatureUnit by rememberSaveable { mutableStateOf(preferredTemperatureUnit) }
    var weather by rememberSaveable { mutableStateOf("") }
    var wind by rememberSaveable { mutableStateOf("") }
    var moreCookDetails by rememberSaveable { mutableStateOf(false) }
    val dishes = remember { mutableStateListOf<DishDraft>() }
    var showDishDialog by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestBack = {
        val hasInput = title.isNotBlank() || smoker.isNotBlank() || setpoint.isNotBlank() || notes.isNotBlank() || fuelType.isNotBlank() || woodBlend.isNotBlank() || outdoorTemperature.isNotBlank() || weather.isNotBlank() || wind.isNotBlank() || dishes.isNotEmpty()
        if (hasInput) confirmDiscard = true else onBack()
    }

    if (showDishDialog) {
        DishEditorDialog(
            preferredWeightUnit = preferredWeightUnit,
            onDismiss = { showDishDialog = false },
            onSave = { draft ->
                dishes.add(draft)
                showDishDialog = false
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this cook setup?") },
            text = { Text("The details you entered here have not been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onBack()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Start a cook") },
                navigationIcon = {
                    IconButton(onClick = requestBack, enabled = !saving) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Go back")
                    }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (saveError != null) {
                        Text(saveError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Button(
                        onClick = {
                            onStartCook(
                                NewCookDraft(
                                    title = title,
                                    smokerName = smoker,
                                    setpointText = setpoint,
                                    setpointUnit = setpointUnit,
                                    notes = notes,
                                    fuelType = fuelType,
                                    woodOrPelletBlend = woodBlend,
                                    outdoorTemperatureText = outdoorTemperature,
                                    outdoorTemperatureUnit = outdoorTemperatureUnit,
                                    weatherNotes = weather,
                                    windNotes = wind,
                                    dishes = dishes.toList(),
                                ),
                            )
                        },
                        enabled = !saving && CookEntryValidation.isOptionalPositiveNumberValid(setpoint),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("cook-save"),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(if (saving) "Saving cook…" else "Start cook")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add the basics now. You can fill in the rest during or after the cook.", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Cook name (optional)") },
                placeholder = { Text("Saturday brisket") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("cook-title"),
            )
            OutlinedTextField(
                value = smoker,
                onValueChange = { smoker = it },
                label = { Text("Smoker or grill (optional)") },
                placeholder = { Text("Pit Boss Austin XL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("cook-smoker"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = setpoint,
                    onValueChange = { setpoint = it },
                    label = { Text("Starting setpoint (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = !CookEntryValidation.isOptionalPositiveNumberValid(setpoint),
                    supportingText = {
                        if (!CookEntryValidation.isOptionalPositiveNumberValid(setpoint)) {
                            Text("Enter a positive number, or leave it blank.")
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("cook-setpoint"),
                )
                SimpleDropdownField(
                    label = "Unit",
                    value = setpointUnit,
                    options = listOf("°F", "°C"),
                    onSelect = { setpointUnit = it },
                    testTag = "cook-setpoint-unit",
                    modifier = Modifier.width(96.dp),
                )
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Cook notes (optional)") },
                placeholder = { Text("Weather, timing, changes, or anything to remember") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("cook-notes"),
            )
            TextButton(onClick = { moreCookDetails = !moreCookDetails }, modifier = Modifier.testTag("cook-more-details")) {
                Text(if (moreCookDetails) "Hide equipment and conditions" else "Add fuel and outdoor conditions (optional)")
            }
            if (moreCookDetails) {
                OutlinedTextField(fuelType, { fuelType = it }, label = { Text("Fuel type") }, placeholder = { Text("Wood pellets, charcoal, splits") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("cook-fuel"))
                OutlinedTextField(woodBlend, { woodBlend = it }, label = { Text("Wood or pellet blend") }, placeholder = { Text("Hickory and apple") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("cook-wood-blend"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(outdoorTemperature, { outdoorTemperature = it }, label = { Text("Outdoor temperature") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f).testTag("cook-outdoor-temperature"))
                    SimpleDropdownField("Unit", outdoorTemperatureUnit, listOf("°F", "°C"), { outdoorTemperatureUnit = it }, modifier = Modifier.width(96.dp))
                }
                OutlinedTextField(weather, { weather = it }, label = { Text("Weather") }, placeholder = { Text("Clear, cloudy, rain") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("cook-weather"))
                OutlinedTextField(wind, { wind = it }, label = { Text("Wind or exposure") }, placeholder = { Text("Breezy from the north") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("cook-wind"))
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                SectionHeading("Dishes")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showDishDialog = true }, modifier = Modifier.testTag("cook-add-dish")) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add dish")
                }
            }

            if (dishes.isEmpty()) {
                Text(
                    "No dish added yet. You can start now and add one later.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                dishes.forEachIndexed { index, dish ->
                    DishDraftCard(
                        dish = dish,
                        onRemove = { dishes.removeAt(index) },
                    )
                }
                OutlinedButton(onClick = { showDishDialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add another dish")
                }
            }

            Spacer(Modifier.height(68.dp))
        }
    }
}

@Composable
private fun DishDraftCard(dish: DishDraft, onRemove: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(dish.name, style = MaterialTheme.typography.titleLarge)
                val detail = buildList {
                    add(dish.foodType)
                    if (dish.weightText.isNotBlank()) add("${dish.weightText} ${dish.weightUnit}")
                    if (dish.preparationItems.any { it.name.isNotBlank() }) add("${dish.preparationItems.count { it.name.isNotBlank() }} prep items")
                    if (dish.photoUris.isNotEmpty()) add("${dish.photoUris.size} photos")
                }.joinToString(" · ")
                Text(detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove ${dish.name}")
            }
        }
    }
}

@Composable
internal fun DishEditorDialog(
    preferredWeightUnit: String = "lb",
    onDismiss: () -> Unit,
    onSave: (DishDraft) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var foodType by rememberSaveable { mutableStateOf("Pork") }
    var cut by rememberSaveable { mutableStateOf("") }
    var weight by rememberSaveable { mutableStateOf("") }
    var weightUnit by rememberSaveable { mutableStateOf(preferredWeightUnit) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    var startingCondition by rememberSaveable { mutableStateOf<String?>(null) }
    var boneIn by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var placement by rememberSaveable { mutableStateOf("") }
    var gradeOrSource by rememberSaveable { mutableStateOf("") }
    var thicknessNotes by rememberSaveable { mutableStateOf("") }
    var prepNotes by rememberSaveable { mutableStateOf("") }
    var preparationItems by remember { mutableStateOf(listOf(IngredientDraft(name = ""))) }
    var photoUris by remember { mutableStateOf(emptyList<String>()) }
    var nameError by rememberSaveable { mutableStateOf(false) }
    var weightError by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestDismiss = {
        val hasInput = name.isNotBlank() || foodType != "Pork" || cut.isNotBlank() || weight.isNotBlank() ||
            startingCondition != null || boneIn != null || placement.isNotBlank() || gradeOrSource.isNotBlank() || thicknessNotes.isNotBlank() || prepNotes.isNotBlank() ||
            preparationItems.any { it.name.isNotBlank() || it.amountText.isNotBlank() } || photoUris.isNotEmpty()
        if (hasInput) confirmDiscard = true else onDismiss()
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10),
    ) { selected ->
        photoUris = (photoUris + selected.map { it.toString() }).distinct().take(10)
    }

    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text("Add a dish") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = false },
                    label = { Text("Dish name") },
                    placeholder = { Text("Brisket, ribs, pork loin") },
                    singleLine = true,
                    isError = nameError,
                    supportingText = { if (nameError) Text("Enter a dish name or cut.") },
                    modifier = Modifier.fillMaxWidth().testTag("dish-name"),
                )
                SimpleDropdownField(
                    label = "Food type",
                    value = foodType,
                    options = listOf("Beef", "Pork", "Poultry", "Seafood", "Wild game", "Vegetables", "Other"),
                    onSelect = { foodType = it },
                    testTag = "dish-food-type",
                )
                OutlinedTextField(
                    value = cut,
                    onValueChange = { cut = it },
                    label = { Text("Specific cut (optional)") },
                    placeholder = { Text("St. Louis ribs, pork shoulder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("dish-cut"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = weight,
                        onValueChange = { weight = it; weightError = false },
                        label = { Text("Weight (optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = weightError,
                        supportingText = { if (weightError) Text("Use a positive number.") },
                        modifier = Modifier.weight(1f).testTag("dish-weight"),
                    )
                    SimpleDropdownField(
                        label = "Unit",
                        value = weightUnit,
                        options = listOf("lb", "oz", "kg", "g"),
                        onSelect = { weightUnit = it },
                        testTag = "dish-weight-unit",
                        modifier = Modifier.width(96.dp),
                    )
                }

                TextButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.testTag("dish-details-toggle")) {
                    Text(if (detailsExpanded) "Hide preparation details" else "Add preparation details")
                }

                if (detailsExpanded) {
                    Text("Starting condition", style = MaterialTheme.typography.titleMedium)
                    SimpleDropdownField(
                        label = "Optional",
                        value = startingCondition ?: "Not set",
                        options = listOf("Not set", "Refrigerated", "Thawed", "Frozen", "Other"),
                        onSelect = { startingCondition = it.takeUnless { value -> value == "Not set" } },
                        testTag = "dish-starting-condition",
                    )
                    Text("Bone-in or boneless", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = boneIn == true,
                            onClick = { boneIn = if (boneIn == true) null else true },
                            label = { Text("Bone-in") },
                            modifier = Modifier.testTag("dish-bone-in"),
                        )
                        FilterChip(
                            selected = boneIn == false,
                            onClick = { boneIn = if (boneIn == false) null else false },
                            label = { Text("Boneless") },
                            modifier = Modifier.testTag("dish-boneless"),
                        )
                    }
                    OutlinedTextField(placement, { placement = it }, label = { Text("Smoker position (optional)") }, placeholder = { Text("Upper rack, left side") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("dish-placement"))
                    OutlinedTextField(gradeOrSource, { gradeOrSource = it }, label = { Text("Grade, brand, or source (optional)") }, modifier = Modifier.fillMaxWidth().testTag("dish-source"))
                    OutlinedTextField(thicknessNotes, { thicknessNotes = it }, label = { Text("Size or thickness notes (optional)") }, modifier = Modifier.fillMaxWidth().testTag("dish-thickness"))

                    Text("Preparation and ingredients", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add the rub, binder, brine, marinade, or other details you want to remember.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    preparationItems.forEachIndexed { index, item ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SimpleDropdownField(
                                label = "Type",
                                value = item.stage.replace('_', ' ').replaceFirstChar { it.titlecase() },
                                options = listOf("Seasoning", "Binder", "Brine", "Marinade", "Injection", "Spritz", "Wrap addition", "Glaze", "Other"),
                                onSelect = { value ->
                                    preparationItems = preparationItems.mapIndexed { i, current ->
                                        if (i == index) current.copy(stage = value.lowercase().replace(' ', '_')) else current
                                    }
                                },
                                testTag = "preparation-type-$index",
                            )
                            OutlinedTextField(
                                value = item.name,
                                onValueChange = { value ->
                                    preparationItems = preparationItems.mapIndexed { i, current -> if (i == index) current.copy(name = value) else current }
                                },
                                label = { Text("Ingredient or preparation") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("preparation-name-$index"),
                            )
                            OutlinedTextField(
                                value = item.brand,
                                onValueChange = { value ->
                                    preparationItems = preparationItems.mapIndexed { i, current -> if (i == index) current.copy(brand = value) else current }
                                },
                                label = { Text("Brand (optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("preparation-brand-$index"),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = item.amountText,
                                    onValueChange = { value ->
                                        preparationItems = preparationItems.mapIndexed { i, current -> if (i == index) current.copy(amountText = value) else current }
                                    },
                                    label = { Text("Amount (optional)") },
                                    placeholder = { Text("2") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    isError = !CookEntryValidation.isOptionalPositiveNumberValid(item.amountText),
                                    supportingText = {
                                        if (!CookEntryValidation.isOptionalPositiveNumberValid(item.amountText)) {
                                            Text("Enter a positive number, or leave the amount blank.")
                                        }
                                    },
                                    modifier = Modifier.weight(1f).testTag("preparation-amount-$index"),
                                )
                                SimpleDropdownField(
                                    label = "Unit",
                                    value = item.amountUnit,
                                    options = listOf("tsp", "tbsp", "cup", "g", "oz"),
                                    onSelect = { value ->
                                        preparationItems = preparationItems.mapIndexed { i, current -> if (i == index) current.copy(amountUnit = value) else current }
                                    },
                                    testTag = "preparation-unit-$index",
                                    modifier = Modifier.width(106.dp),
                                )
                            }
                        }
                    }
                    TextButton(onClick = { preparationItems = preparationItems + IngredientDraft(name = "") }, modifier = Modifier.testTag("preparation-add-item")) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add preparation item")
                    }
                    OutlinedTextField(
                        value = prepNotes,
                        onValueChange = { prepNotes = it },
                        label = { Text("Preparation notes (optional)") },
                        placeholder = { Text("Trimmed the fat cap; used mustard as binder") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth().testTag("preparation-notes"),
                    )
                }

                OutlinedButton(
                    onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("dish-add-photos"),
                ) {
                    Text(if (photoUris.isEmpty()) "Add photos" else "Add more photos (${photoUris.size})")
                }
                photoUris.forEachIndexed { index, _ ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Photo ${index + 1}", modifier = Modifier.weight(1f))
                        TextButton(onClick = { photoUris = photoUris.filterIndexed { i, _ -> i != index } }) {
                            Text("Remove")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                nameError = name.isBlank()
                weightError = !CookEntryValidation.isOptionalPositiveNumberValid(weight)
                val invalidAmounts = preparationItems.any { !CookEntryValidation.isOptionalPositiveNumberValid(it.amountText) }
                if (!nameError && !weightError && !invalidAmounts) {
                    onSave(
                        DishDraft(
                            name = name.trim(),
                            foodType = foodType,
                            cut = cut,
                            weightText = weight,
                            weightUnit = weightUnit,
                            startingCondition = startingCondition,
                            boneIn = boneIn,
                            placement = placement,
                            gradeOrSource = gradeOrSource,
                            thicknessNotes = thicknessNotes,
                            prepNotes = prepNotes,
                            preparationItems = preparationItems.filter { it.name.isNotBlank() },
                            photoUris = photoUris,
                        ),
                    )
                }
            }, modifier = Modifier.testTag("dish-save")) { Text("Add dish") }
        },
        dismissButton = { TextButton(onClick = requestDismiss) { Text("Cancel") } },
    )

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this dish?") },
            text = { Text("The dish details and selected photos have not been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDismiss()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
internal fun SimpleDropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    testTag: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
            ) {
                Text(value, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose $label")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}
