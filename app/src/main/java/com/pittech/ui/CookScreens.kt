package com.pittech.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.BuildConfig
import com.pittech.CooksViewModel
import com.pittech.ads.AdEntitlement
import com.pittech.ads.cookListNativeAdInsertIndex
import com.pittech.ads.shouldShowCookListNativeAd
import com.pittech.R
import com.pittech.data.CameraPhotoFiles
import com.pittech.data.CookStatus
import com.pittech.data.CookWithDishes
import com.pittech.domain.CookEntryValidation
import com.pittech.domain.DishDraft
import com.pittech.domain.DishDraftCodec
import com.pittech.domain.EditorDraftKeys
import com.pittech.domain.IngredientDraft
import com.pittech.domain.NewCookDraft
import java.text.DateFormat
import java.time.Instant
import java.util.Date

private enum class MainSection(val title: String, val icon: ImageVector) {
    COOKS("Meals", Icons.Filled.Home),
    RECIPES("Recipes", Icons.Filled.MenuBook),
    DISCOVER("Discover", Icons.Filled.Explore),
    INSIGHTS("Insights", Icons.Filled.Insights),
    MORE("More", Icons.Filled.MoreHoriz),
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PitTechApp(
    viewModel: CooksViewModel,
    themeMode: PitTechThemeMode,
    onThemeModeChange: (PitTechThemeMode) -> Unit,
    adsEnabled: Boolean = false,
    entitlement: AdEntitlement = AdEntitlement.UNKNOWN,
    previouslyEntitled: Boolean = false,
    privacyOptionsRequired: Boolean = false,
    onShowPrivacyOptions: () -> Unit = {},
    // First-run welcome entries (#187): the welcome's secondaries land on the
    // exact destinations the empty-Meals entries use.
    startWithRestoreBackup: Boolean = false,
    startAtDevices: Boolean = false,
    // PL-20 (#172): crash recovery returns to the cook that was open when
    // the crash hit, and its attach action lands on Settings with the
    // feedback form open.
    startCookId: String? = null,
    startAtFeedback: Boolean = false,
) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            viewModel.setAppForeground(true)
            try { kotlinx.coroutines.awaitCancellation() } finally { viewModel.setAppForeground(false) }
        }
    }
    val grillState by viewModel.grillState.collectAsStateWithLifecycle()
    val cooks by viewModel.cooks.collectAsStateWithLifecycle()
    val setupPreview by viewModel.setupPreview.collectAsStateWithLifecycle()
    val previewPlaybook by viewModel.previewPlaybook.collectAsStateWithLifecycle()
    val keptSetupDraft by viewModel.setupDraft.collectAsStateWithLifecycle()
    // PL-14 (#172): the More → Account row carries the private-sync status.
    val accountPresentation by viewModel.account.presentation.collectAsStateWithLifecycle()
    val syncPresentation by viewModel.sync.presentation.collectAsStateWithLifecycle()
    val companionRecords by viewModel.companionRecords.collectAsStateWithLifecycle()
    val saving by viewModel.busy.collectAsStateWithLifecycle()
    val saveError by viewModel.error.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val savedCookId by viewModel.savedCookId.collectAsStateWithLifecycle()
    val selectedCookId by viewModel.selectedCookId.collectAsStateWithLifecycle()
    val selectedCook by viewModel.selectedCook.collectAsStateWithLifecycle()
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
    val importHistoryAdded by viewModel.importHistoryAdded.collectAsStateWithLifecycle()
    val restoredPreferences by viewModel.restoredPreferences.collectAsStateWithLifecycle()
    val shareArchiveUri by viewModel.shareArchiveUri.collectAsStateWithLifecycle()
    var selectedSection by rememberSaveable { mutableStateOf(MainSection.COOKS.name) }
    LaunchedEffect(selectedSection) { if (selectedSection == MainSection.MORE.name) viewModel.sync.refresh() }
    val publicLinkSequence by viewModel.publicDiscovery.linkSequence.collectAsStateWithLifecycle()
    LaunchedEffect(publicLinkSequence) { if(publicLinkSequence>0)selectedSection=MainSection.DISCOVER.name }
    var moreDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var sharedCodes by rememberSaveable { mutableStateOf(false) }
    var communitySettings by rememberSaveable { mutableStateOf(false) }
    if(communitySettings)CommunityControlsDialog(viewModel.communityControls,viewModel.account,viewModel.publicDiscovery){communitySettings=false}
    var myCookbook by rememberSaveable { mutableStateOf(false) }
    if(myCookbook)MyCookbookDialog(viewModel) { myCookbook=false }
    // PL-18: a completed publication asks to land in the public cookbook.
    val cookbookOpenRequests by viewModel.cookbookOpenRequests.collectAsStateWithLifecycle()
    LaunchedEffect(cookbookOpenRequests) { if (cookbookOpenRequests > 0) myCookbook = true }
    RelayImportDialog(viewModel)
    AccountSheet(viewModel.account, viewModel.sync, viewModel.mcpConnections, viewModel.mcpDeviceActions, viewModel.subscription)
    // Slice 8 (PL-15 / DD-10): when the account surface closes after a
    // paywall sign-in detour — sign-in completed or cancelled — the gate
    // reopens on the destination the user was headed to.
    val accountShowing by viewModel.account.showing.collectAsStateWithLifecycle()
    LaunchedEffect(accountShowing) { if (!accountShowing) viewModel.subscription.resumeAfterSignInDetour() }
    ProGateDialog(
        viewModel.subscription,
        previouslyEntitled,
        onSignIn = { if (viewModel.account.enabled) viewModel.account.open() },
        benchmarks = viewModel.communityBenchmarks,
        personal = insights.proPersonalLibrary(System.currentTimeMillis()),
        customization = viewModel.proCustomization,
    )
    if (sharedCodes) SharedRelayCodesDialog(viewModel) { sharedCodes = false }
    var isStartingCook by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("pittech-preferences", 0) }
    var temperatureUnit by rememberSaveable { mutableStateOf(preferences.getString("temperature-unit", "°F") ?: "°F") }
    var weightUnit by rememberSaveable { mutableStateOf(preferences.getString("weight-unit", "lb") ?: "lb") }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.previewImport(uri)
    }
    LaunchedEffect(startWithRestoreBackup) {
        if (startWithRestoreBackup) restoreBackup.launch(arrayOf("application/zip", "application/x-zip-compressed"))
    }
    LaunchedEffect(startAtDevices) {
        if (startAtDevices) {
            selectedSection = MainSection.MORE.name
            moreDestination = "Devices"
        }
    }
    LaunchedEffect(startCookId) {
        if (startCookId != null) viewModel.openCook(startCookId)
    }
    // The crash-recovery attach request is a one-shot: it opens the feedback
    // form exactly once, on the first Settings composition after the attach.
    // MainActivity's startAtFeedback stays true for the process lifetime, so
    // the request is consumed into this pending flag and cleared by
    // SettingsScreen the moment the form opens — re-entering Settings later
    // must never reopen the form or hijack the More tab.
    var feedbackStartPending by remember { mutableStateOf(false) }
    LaunchedEffect(startAtFeedback) {
        if (startAtFeedback) {
            selectedSection = MainSection.MORE.name
            moreDestination = "Settings"
            feedbackStartPending = true
        }
    }

    LaunchedEffect(restoredPreferences) {
        restoredPreferences?.let { restored ->
            temperatureUnit = restored.temperatureUnit
            weightUnit = restored.weightUnit
            onThemeModeChange(PitTechThemeMode.valueOf(restored.themeMode))
            viewModel.consumeRestoredPreferences()
        }
    }

    LaunchedEffect(shareArchiveUri) {
        val uri = shareArchiveUri ?: return@LaunchedEffect
        runCatching {
            context.startActivity(Intent.createChooser(ZipShareIntent.create(uri), if (uri.lastPathSegment?.endsWith(".pittech") == true) "Share Cook Pack" else "Share PitTech backup"))
        }.onFailure { failure ->
            val message = if (failure is ActivityNotFoundException) "No app can share this ZIP. Use Save backup instead." else "The backup could not be shared."
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
        viewModel.consumeShareArchive()
    }

    if (importPreview != null) {
        val draft = importPreview!!
        if (draft.cookPack != null) CookPackImportDialog(draft, saving, saveError, viewModel::cancelImport, viewModel::confirmCookPackImport, viewModel::confirmCookPackHistoryImport, importHistoryAdded)
        else {
            // TURN-IN (area-06 `e04`→`e05`): the restore review is two steps
            // inside the same guard dialog — contents preview (counts as
            // status cells), then the separate confirmed step. The restore
            // itself is unchanged: one confirmImport, duplicates skipped,
            // preferences ride along when the backup carries them.
            var restoreStep by remember(draft) { mutableStateOf(0) }
            AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Review backup contents") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TurnInMilestoneRail(listOf(
                        TurnInRailStep("Preview", null, if (restoreStep == 0) TurnInRailState.CURRENT else TurnInRailState.DONE),
                        TurnInRailStep("Confirm", null, if (restoreStep == 1) TurnInRailState.CURRENT else TurnInRailState.UPCOMING),
                    ))
                    if (restoreStep == 0) {
                        Text("Exported ${draft.exportedAtUtc?.let(::formatBackupDate) ?: "date unavailable"} · archive v${draft.archiveVersion}", style = PitTechTextStyles.monoMeta, color = pitTechTokens().inkMuted)
                        // Counts as a grid of score cells (slice 5, frame
                        // s18) — kept as the preview step's body here.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TurnInScoreCell("Cooks", "${draft.cookCount}", Modifier.weight(1f))
                            TurnInScoreCell("Dishes", "${draft.dishCount}", Modifier.weight(1f))
                            TurnInScoreCell("Log entries", "${draft.eventCount}", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TurnInScoreCell("Readings", "${draft.readingCount}", Modifier.weight(1f))
                            TurnInScoreCell("Results", "${draft.resultCount}", Modifier.weight(1f))
                            TurnInScoreCell("Photos", "${draft.photoCount}", Modifier.weight(1f))
                        }
                        if (draft.savedToolsCount > 0) Text("${draft.savedToolsCount} saved playbooks, plans, alerts, checklists or equipment records")
                        if (draft.preferences != null) {
                            Text("This full backup also restores temperature, weight, and appearance preferences.")
                        } else {
                            Text("This cook-only or older backup does not include app preferences.")
                        }
                    } else {
                        Text("PitTech adds cooks that are not already on this phone and skips duplicate cook IDs. Existing cooks are not overwritten.")
                        if (draft.preferences != null) {
                            Text("This full backup also restores temperature, weight, and appearance preferences.")
                        } else {
                            Text("This cook-only or older backup does not include app preferences.")
                        }
                    }
                }
            },
            confirmButton = {
                if (restoreStep == 0) TextButton(onClick = { restoreStep = 1 }) { Text("Continue") }
                else TextButton(onClick = viewModel::confirmImport, enabled = !saving) { Text("Restore backup") }
            },
            dismissButton = {
                if (restoreStep == 0) TextButton(onClick = viewModel::cancelImport) { Text("Cancel") }
                else TextButton(onClick = { restoreStep = 0 }) { Text("Back") }
            },
            )
        }
    }

    LaunchedEffect(savedCookId) {
        val newCookId = savedCookId
        if (newCookId != null) {
            isStartingCook = false
            selectedSection = MainSection.COOKS.name
            viewModel.clearSavedCookSignal()
            viewModel.openCook(newCookId)
        }
    }

    PlaybookDialogs(viewModel)
    ServePlanDialogs(viewModel)
    LaunchedEffect(setupPreview) { if (setupPreview != null) { isStartingCook = true; selectedSection = MainSection.COOKS.name } }

    if (selectedCookId != null) {
        // System back (gesture or button) closes the cook, same as the
        // detail's own back arrow — it must never exit the app from here.
        BackHandler { viewModel.closeCook() }
        val detail = selectedCook
        if (detail == null || detail.cook.id != selectedCookId) {
            // PL-20 (#172): a selection whose detail never arrives is a
            // cook that no longer exists (the crash-recovery record can
            // outlive its cook — deleted, or replaced by a restore).
            // Fall back home after a grace period instead of hanging on
            // the interstitial forever; a real local cook loads in well
            // under the grace period, and the effect is cancelled the
            // moment the detail lands.
            LaunchedEffect(selectedCookId) {
                kotlinx.coroutines.delay(5_000)
                viewModel.closeCook()
            }
            Scaffold { padding ->
                // TURN-IN labelled loader (§4, `d07-loading-opening`): the
                // wait is named, never a bare spinner.
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator(color = pitTechTokens().ember, modifier = Modifier.size(28.dp))
                        TurnInBannerText("Opening cook…", style = PitTechTextStyles.bannerTitle)
                    }
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
        // Back leaves the start-a-cook flow the same way its own back
        // affordance does; the setup draft handling inside is unchanged.
        BackHandler {
            viewModel.clearSaveError()
            isStartingCook = false
            viewModel.consumeSetupPreview()
        }
        key(setupPreview) {
        StartCookScreen(
            saving = saving,
            saveError = saveError,
            initialDraft = setupPreview,
            equipmentProfiles = companionRecords.filter { it.kind == "equipment" }.map { com.pittech.domain.CookPreparation.decodeEquipment(it.payload) },
            initialPlaybook = companionRecords.firstOrNull { it.id == setupPreview?.playbookId }?.let { com.pittech.domain.PlaybookCodec.decode(it.payload) } ?: companionRecords.firstOrNull { it.id == setupPreview?.scheduledPlanId }?.let { com.pittech.domain.ServeTimePlanner.decode(it.payload).book } ?: previewPlaybook,
            onEditPlaybook = { if (previewPlaybook != null) viewModel.editPreviewPlaybook() else if (setupPreview?.scheduledPlanId != null) viewModel.planServeTime(scheduledId = setupPreview?.scheduledPlanId) else setupPreview?.playbookId?.let(viewModel::editPlaybook) },
            preferredTemperatureUnit = temperatureUnit,
            preferredWeightUnit = weightUnit,
            onBack = {
                viewModel.clearSaveError()
                isStartingCook = false
                viewModel.consumeSetupPreview()
            },
            onStartCook = viewModel::startCook,
            grillState = grillState,
            onGrill = { (context.applicationContext as com.pittech.PitTechApplication).grillMonitor.selectDevice(it) },
            onSampling = viewModel::configureGrillSampling,
            onDraftChange = viewModel::keepSetupDraft,
            onDiscardDraft = viewModel::discardSetupDraft,
        )
        }
        return
    }

    val section = MainSection.valueOf(selectedSection)
    BackHandler(enabled = section == MainSection.MORE && moreDestination != null) { moreDestination = null }
    // From any other tab root, back returns to the start tab (Cooks) instead
    // of exiting; only the Cooks root lets back leave the app. The More
    // sub-destination handler above keeps priority there via its condition.
    BackHandler(enabled = section != MainSection.COOKS && moreDestination == null) { selectedSection = MainSection.COOKS.name }
    Scaffold(
        topBar = {
            // TURN-IN tab-bar treatment (§4): tab roots carry NO title bar —
            // the selected bottom tab says where you are. Only a More
            // sub-destination keeps a slim back row, so its way back is
            // never lost. The offline banner rides above every tab (Q1).
            Column {
                if (section == MainSection.MORE && moreDestination != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { moreDestination = null }) { Icon(Icons.Filled.ArrowBack, "Back to More") }
                        TurnInBannerText(moreDestination ?: "", style = PitTechTextStyles.bannerTitle)
                    }
                } else {
                    // Brand strip on every tab root: brand only, never a
                    // page title — the bottom tab still says where you are.
                    PitTechBrandStrip()
                }
                OfflineBanner()
            }
        },
        bottomBar = {
            // TURN-IN shell (§4, `d01`): the bar is a flat surface tray; the
            // selected tab is ember with a small ember tick above it — no
            // pill background behind the icon.
            val tokens = pitTechTokens()
            Surface(
                color = tokens.surface,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                border = BorderStroke(1.dp, tokens.ruling),
            ) {
                NavigationBar(containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                    MainSection.entries.forEach { item ->
                        NavigationBarItem(
                            selected = section == item,
                            onClick = {
                                if (item == MainSection.MORE && section == item) moreDestination = null
                                selectedSection = item.name
                            },
                            icon = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Box(
                                        Modifier.size(width = 18.dp, height = 3.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(if (section == item) tokens.ember else androidx.compose.ui.graphics.Color.Transparent),
                                    )
                                    Icon(item.icon, contentDescription = null)
                                }
                            },
                            label = { Text(item.title) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = tokens.emberText,
                                selectedTextColor = tokens.emberText,
                                unselectedIconColor = tokens.inkMuted,
                                unselectedTextColor = tokens.inkMuted,
                                indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            modifier = Modifier.testTag("nav-${item.name.lowercase()}"),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            // "Start a cook" as the board's primary floating action
            // (Michael's direction from hands-on testing). The Meals board's
            // own start button stays — the FAB is an added entry point to
            // the same flow, not a replacement.
            if (section == MainSection.COOKS) {
                val tokens = pitTechTokens()
                ExtendedFloatingActionButton(
                    onClick = {
                        viewModel.clearSaveError()
                        viewModel.consumeSetupPreview()
                        isStartingCook = true
                    },
                    containerColor = tokens.ember,
                    contentColor = tokens.onAccent,
                    modifier = Modifier.testTag("start-cook-fab"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start a cook")
                }
            }
        },
    ) { padding ->
        when (section) {
            MainSection.COOKS -> CooksHome(
                cooks = cooks,
                playbooks = { UpcomingCooks(viewModel); PlaybookLibrary(viewModel) },
                adsEnabled = adsEnabled,
                entitlement = entitlement,
                previouslyEntitled = previouslyEntitled,
                error = saveError,
                notice = notice,
                modifier = Modifier.padding(padding),
                onOpenCook = viewModel::openCook,
                onRestoreBackup = { restoreBackup.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                onStartCook = {
                    viewModel.clearSaveError()
                    viewModel.consumeSetupPreview()
                    isStartingCook = true
                },
                setupDraft = keptSetupDraft,
                onResumeSetup = viewModel::resumeSetupDraft,
                onDiscardSetup = viewModel::discardSetupDraft,
            )
            MainSection.RECIPES -> Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SavedRecipesLibrary(viewModel, standalone = true)
            }
            MainSection.DISCOVER -> if (viewModel.publicDiscovery.enabled) PublicDiscoveryScreen(viewModel.publicDiscovery, Modifier.padding(padding), temperatureUnit, viewModel.account, viewModel.publicationWriter, viewModel.communityControls) { selectedSection = MainSection.RECIPES.name } else DiscoveryWelcome(Modifier.padding(padding)) { selectedSection = MainSection.RECIPES.name }
            MainSection.INSIGHTS -> InsightsScreen(
                data = insights,
                onOpenCook = viewModel::openCook,
                onStartCook = {
                    viewModel.clearSaveError()
                    viewModel.consumeSetupPreview()
                    isStartingCook = true
                },
                modifier = Modifier.padding(padding),
                onProEntry = viewModel.subscription::openPro,
            )
            MainSection.MORE -> when (moreDestination) {
            "Devices" -> Column(Modifier.padding(padding).fillMaxSize()) {
                EquipmentTools(viewModel)
                if (BuildConfig.TELEMETRY_BETA_ENABLED) {
                    TelemetryConnectionsScreen(viewModel, Modifier.weight(1f))
                } else {
                    // PL-30 (#172): the shared "not available yet" pattern,
                    // with a route onward to Meals (where readings are
                    // added by hand) instead of a dead-end text block.
                    NotAvailablePlaceholder(
                        icon = Icons.Filled.Thermostat,
                        title = NotAvailableCopy.DEVICES_TITLE,
                        message = NotAvailableCopy.DEVICES_MESSAGE,
                        note = NotAvailableCopy.DEVICES_NOTE,
                        onwardLabel = NotAvailableCopy.DEVICES_ONWARD,
                        onOnward = { selectedSection = MainSection.COOKS.name },
                        modifier = Modifier.weight(1f),
                    )
                }

            }
            "Settings" -> SettingsScreen(
                viewModel = viewModel,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
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
                openFeedbackOnStart = feedbackStartPending,
                onFeedbackStartConsumed = { feedbackStartPending = false },
                modifier = Modifier.padding(padding),
            )
            else -> MoreHome(Modifier.padding(padding), onDevices = { moreDestination = "Devices" }, onSettings = { moreDestination = "Settings" }, onImportCode = { viewModel.relay.openImport() }, onSharedCodes = { sharedCodes = true }, onAccount = if (viewModel.account.enabled) ({ viewModel.account.open() }) else null, onCookbook = if (viewModel.account.enabled && viewModel.publicDiscovery.enabled) ({ myCookbook=true }) else null, onCommunity = if(viewModel.publicDiscovery.enabled)({communitySettings=true})else null, onPro = { viewModel.subscription.openPro(com.pittech.billing.ProPlacement.MORE) }, accountSubtitle = if (accountPresentation.signedIn) "Private sync — ${SyncStatusCopy.row(syncPresentation.status, syncPresentation.detail, syncPresentation.lastSyncedAt, syncPresentation.conflicts.size, true, viewModel.sync.enabled).headline}" else "Optional email sign-in and this device's session")
            }
        }
    }
}

@Composable
private fun CooksHome(
    playbooks: @Composable () -> Unit,
    cooks: List<CookWithDishes>,
    adsEnabled: Boolean,
    entitlement: AdEntitlement,
    previouslyEntitled: Boolean,
    error: String?,
    notice: String?,
    modifier: Modifier = Modifier,
    onOpenCook: (String) -> Unit,
    onRestoreBackup: () -> Unit,
    onStartCook: () -> Unit,
    // PL-13 (#172): a kept Start Cook setup draft offers Resume right here.
    setupDraft: NewCookDraft? = null,
    onResumeSetup: () -> Unit = {},
    onDiscardSetup: () -> Unit = {},
) {
    val activeCooks = cooks.filter { it.cook.status != CookStatus.COMPLETED }
    val finishedCooks = cooks.filter { it.cook.status == CookStatus.COMPLETED }
    val showCookListAd = shouldShowCookListNativeAd(adsEnabled, entitlement, previouslyEntitled)
    var showPlanning by rememberSaveable { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowMillis = System.currentTimeMillis()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (cooks.isEmpty()) {
            // First-run / empty Meals (#172, DD-1): the mascot is a small header
            // lockup only — max 56 dp tall / 168 dp wide, never a hero.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(
                        painter = painterResource(R.drawable.pittech_smoker),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.height(56.dp).widthIn(max = 168.dp).testTag("meals-lockup"),
                    )
                    Text("PitTech", style = MaterialTheme.typography.titleLarge)
                }
                TurnInBannerText("Your cook log is ready", style = PitTechTextStyles.bannerTitle)
                Text(
                    "Add the meat, preparation, photos, and notes you want to remember. You can log a cook with or without a connected grill.",
                    style = PitTechTextStyles.body,
                )
                Text(
                    "Your cook records stay on this phone. No account is needed.",
                    style = PitTechTextStyles.body,
                    color = pitTechTokens().inkMuted,
                )
            }
        }

        if (activeCooks.isNotEmpty()) {
            // TURN-IN lane (`d01`): the first active cook leads the tab as
            // the On-the-fire lane; the rest stay one tap away below it.
            LiveZoneCard(activeCooks.first(), nowMillis = nowMillis, onClick = { onOpenCook(activeCooks.first().cook.id) })
            if (activeCooks.size > 1) {
                SectionHeading("Also cooking")
                activeCooks.drop(1).forEach { cook -> CookSummaryCard(cook, onClick = { onOpenCook(cook.cook.id) }) }
            }
        }

        Button(
            onClick = onStartCook,
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("start-cook"),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Start meal")
        }

        if (setupDraft != null) {
            // PL-13: the kept setup is one tap from Meals, never a surprise.
            Card(
                colors = CardDefaults.cardColors(containerColor = pitTechTokens().surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, pitTechTokens().ruling),
                modifier = Modifier.fillMaxWidth().testTag("setup-draft-card"),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TurnInBannerText("Cook setup draft")
                    Text(
                        setupDraft.title.ifBlank { "Untitled setup" } + setupDraft.sourceLabel?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onResumeSetup, modifier = Modifier.heightIn(min = 48.dp).testTag("resume-setup")) { Text("Resume setup") }
                        TextButton(onClick = onDiscardSetup, modifier = Modifier.heightIn(min = 48.dp).testTag("discard-setup")) { Text("Discard setup") }
                    }
                }
            }
        }

        if (cooks.isEmpty()) {
            OutlinedButton(
                onClick = onRestoreBackup,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("empty-restore-backup"),
            ) {
                Text("Restore from backup")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium) }

        // Planning becomes a panel whose header carries its state (DD-2):
        // collapsed never means invisible.
        Card(
            colors = CardDefaults.cardColors(containerColor = pitTechTokens().surface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, pitTechTokens().ruling),
            modifier = Modifier.fillMaxWidth().clickable { showPlanning = !showPlanning }.testTag("home-planning-toggle"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TurnInBannerText("Planning and playbooks")
                    if (!showPlanning) {
                        Text(
                            "Playbooks, serve plans and upcoming cooks",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = if (showPlanning) "Hide planning" else "Show planning")
            }
        }
        if (showPlanning) playbooks()

        if (finishedCooks.isNotEmpty()) {
            SectionHeading("Completed meals")
            val adInsertIndex = if (showCookListAd) cookListNativeAdInsertIndex(finishedCooks.size) else null
            finishedCooks.forEachIndexed { index, cook ->
                CookSummaryCard(cook, onClick = { onOpenCook(cook.cook.id) })
                if (adInsertIndex != null && index == adInsertIndex - 1) {
                    PitTechNativeAdPlacement(adsEnabled = adsEnabled, entitlement = entitlement, previouslyEntitled = previouslyEntitled)
                }
            }
        }
    }
}

/**
 * The TURN-IN On-the-fire lane on Meals (`d01`): the lead active cook as a
 * lane row — ember edge bar, banner name, a LIVE (or PAUSED) pill, the
 * setpoint/smoker/dishes in mono, and the elapsed clock. Nothing (ads
 * included) ever renders inside or above it. Same card contract as every
 * cook card ("cook-card-<id>" opens the cook); the lane is presentation,
 * not a new destination.
 */
@Composable
private fun LiveZoneCard(cook: CookWithDishes, nowMillis: Long, onClick: () -> Unit) {
    val tokens = pitTechTokens()
    val paused = cook.cook.status == CookStatus.PAUSED
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("cook-card-${cook.cook.id}"),
        colors = CardDefaults.cardColors(containerColor = tokens.surface, contentColor = tokens.ink),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, tokens.ruling),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(tokens.ember))
            Column(
                modifier = Modifier.padding(16.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TurnInBannerText(cook.cook.title, modifier = Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (paused) tokens.marigold else tokens.ember,
                        contentColor = tokens.onAccent,
                    ) {
                        Text(
                            if (paused) "PAUSED" else "LIVE",
                            style = PitTechTextStyles.monoMeta,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
                val setpoint = cook.cook.initialSetpointValue
                val detail = buildList {
                    if (setpoint != null) add("Pit set ${formatPosterValue(setpoint)}${cook.cook.initialSetpointUnit ?: ""}")
                    cook.cook.smokerName?.takeIf { it.isNotBlank() }?.let { add(it) }
                    if (cook.dishes.isNotEmpty()) add(cook.dishes.joinToString { dish -> dish.cut?.takeIf(String::isNotBlank) ?: dish.name })
                }
                if (detail.isNotEmpty()) {
                    Text(detail.joinToString(" · "), style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        formatElapsed(nowMillis - cook.cook.startedAtUtcMillis),
                        style = PitTechTextStyles.monoReadout,
                        color = tokens.ink,
                    )
                    Text(cookStatusLine(cook), style = PitTechTextStyles.body, color = tokens.inkMuted)
                }
            }
        }
    }
}

private fun formatElapsed(elapsedMillis: Long): String {
    val totalMinutes = (elapsedMillis.coerceAtLeast(0) / 60_000).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "%d:%02d".format(hours, minutes) else "0:%02d".format(minutes)
}

private fun formatPosterValue(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

private fun cookStatusLine(cook: CookWithDishes): String {
    val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(cook.cook.startedAtUtcMillis))
    return when (cook.cook.status) {
        CookStatus.PAUSED -> "Recording and guidance paused · $time"
        CookStatus.COMPLETED -> "Finished · $time"
        else -> if (cook.lifecycles.firstOrNull()?.timingMode == "explicit_phases")
            com.pittech.domain.CookPhases.summary(com.pittech.domain.CookPhases.all(com.pittech.data.notePlanEvents(cook.events), cook.dishes.map { it.id }, cook.cook.startedAtUtcMillis, System.currentTimeMillis())) + " · $time"
        else "Active session · legacy timer · $time"
    }
}

@Composable
private fun CookSummaryCard(cook: CookWithDishes, onClick: () -> Unit) {
    // TURN-IN lane row (§4, `d01`): banner name over a quiet status line.
    val tokens = pitTechTokens()
    val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(cook.cook.startedAtUtcMillis))
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("cook-card-${cook.cook.id}"),
        colors = CardDefaults.cardColors(containerColor = tokens.surface, contentColor = tokens.ink),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, tokens.ruling),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TurnInBannerText(cook.cook.title)
            Text(
                when (cook.cook.status) {
                    CookStatus.PAUSED -> "Recording and guidance paused · $time"
                    CookStatus.COMPLETED -> "Finished · $time"
                    else -> if (cook.lifecycles.firstOrNull()?.timingMode == "explicit_phases")
                        com.pittech.domain.CookPhases.summary(com.pittech.domain.CookPhases.all(com.pittech.data.notePlanEvents(cook.events), cook.dishes.map { it.id }, cook.cook.startedAtUtcMillis, System.currentTimeMillis())) + " · $time"
                    else "Active session · legacy timer · $time"
                },
                style = PitTechTextStyles.body,
                color = tokens.inkMuted,
            )
            if (cook.dishes.isNotEmpty()) {
                Text(
                    cook.dishes.joinToString { dish -> dish.cut?.takeIf(String::isNotBlank) ?: dish.name },
                    style = PitTechTextStyles.body,
                )
            }
        }
    }
}

@Composable
private fun PitTechBrandStrip() {
    // The brand strip: a small footprint that is unmistakably PitTech —
    // the Ember mascot at a clearly readable size beside the wordmark in
    // the display face, with the ember tick the tab bar uses. It carries
    // no page title; the selected bottom tab still says where you are.
    val tokens = pitTechTokens()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("pittech-brand-strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.pittech_smoker),
            contentDescription = null,
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TurnInBannerText("PitTech", style = PitTechTextStyles.bannerTitle)
            Box(
                Modifier.size(width = 26.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tokens.ember),
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    // TURN-IN: section heads are tracked-caps meta labels (§3.2), not
    // title bars — the tab already says where you are.
    TurnInMetaLabel(text)
}

private fun formatBackupDate(value: String): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(Instant.parse(value).toEpochMilli()))
}.getOrDefault(value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartCookScreen(
    initialDraft: NewCookDraft? = null,
    equipmentProfiles: List<com.pittech.domain.EquipmentProfile> = emptyList(),
    initialPlaybook: com.pittech.domain.CookPlaybook? = null,
    onEditPlaybook: () -> Unit = {},
    saving: Boolean,
    saveError: String?,
    preferredTemperatureUnit: String,
    preferredWeightUnit: String,
    onBack: () -> Unit,
    onStartCook: (NewCookDraft) -> Unit,
    grillState: com.pittech.devices.PolarisMonitorState,
    onGrill: (String) -> Unit,
    onSampling: (com.pittech.devices.GrillSamplingPolicy) -> Unit,
    // PL-13 (#172): every edit to the setup is offered to the kept-draft
    // store; leaving keeps the draft instead of discarding it.
    onDraftChange: (NewCookDraft) -> Unit = {},
    onDiscardDraft: () -> Unit = {},
) {
    val defaultCookName = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date()) }
    var title by rememberSaveable {
        mutableStateOf(initialDraft?.title ?: defaultCookName)
    }
    var recordGrill by rememberSaveable { mutableStateOf(false) }
    var alreadyCooking by rememberSaveable { mutableStateOf(initialDraft?.alreadyCooking ?: false) }
    var foodOnTime by rememberSaveable { mutableLongStateOf(initialDraft?.foodOnAtUtcMillis ?: System.currentTimeMillis()) }
    var probe1 by rememberSaveable { mutableStateOf<String?>(null) }
    var probe2 by rememberSaveable { mutableStateOf<String?>(null) }
    var probe3 by rememberSaveable { mutableStateOf<String?>(null) }
    var probe4 by rememberSaveable { mutableStateOf<String?>(null) }
    var smoker by rememberSaveable { mutableStateOf(initialDraft?.smokerName ?: grillState.selectedDevice?.name.orEmpty()) }
    val prepApp = LocalContext.current.applicationContext as com.pittech.PitTechApplication
    var maintenanceNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(smoker, equipmentProfiles) {
        maintenanceNote = null
        if (equipmentProfiles.any { it.name.equals(smoker.trim(), true) }) {
            val summary = prepApp.preparationRepository.summaries().firstOrNull { it.profile.name.equals(smoker.trim(), true) }
            if (summary?.maintenanceDue == true) maintenanceNote = "Maintenance is due for ${summary.profile.name}. Review My equipment in Devices after cleaning."
        }
    }
    var setpoint by rememberSaveable { mutableStateOf(initialDraft?.setpointText.orEmpty()) }
    var setpointUnit by rememberSaveable { mutableStateOf(initialDraft?.setpointUnit ?: preferredTemperatureUnit) }
    var notes by rememberSaveable { mutableStateOf(initialDraft?.notes.orEmpty()) }
    var fuelType by rememberSaveable { mutableStateOf(initialDraft?.fuelType.orEmpty()) }
    var woodBlend by rememberSaveable { mutableStateOf(initialDraft?.woodOrPelletBlend.orEmpty()) }
    var outdoorTemperature by rememberSaveable { mutableStateOf(initialDraft?.outdoorTemperatureText.orEmpty()) }
    var outdoorTemperatureUnit by rememberSaveable { mutableStateOf(initialDraft?.outdoorTemperatureUnit ?: preferredTemperatureUnit) }
    var weather by rememberSaveable { mutableStateOf(initialDraft?.weatherNotes.orEmpty()) }
    var wind by rememberSaveable { mutableStateOf(initialDraft?.windNotes.orEmpty()) }
    var moreCookDetails by rememberSaveable { mutableStateOf(false) }
    val dishes = remember { mutableStateListOf<DishDraft>().apply { addAll(initialDraft?.dishes.orEmpty()) } }
    // PL-19: saved-recipe sources stay parallel to the dish list while it
    // is reviewed/edited here, so removing or adding a dish never attaches
    // a recipe to the wrong dish when preparation begins.
    val dishRevisions = remember { mutableStateListOf<String?>().apply { addAll(initialDraft?.recipeRevisionIds.orEmpty()) } }
    // PL-10: preset working copies ride parallel to the sources the same way.
    val dishAttemptSnapshots = remember { mutableStateListOf<String?>().apply { addAll(initialDraft?.recipeAttemptSnapshots.orEmpty()) } }
    var followPlaybook by rememberSaveable { mutableStateOf(initialDraft?.followPlaybook ?: true) }
    var showSteps by rememberSaveable { mutableStateOf(false) }
    var editPreviewDish by remember { mutableStateOf<Int?>(null) }
    editPreviewDish?.let { index -> DishEditorDialog(preferredWeightUnit = preferredWeightUnit, initial = dishes[index], draftKey = "startcook", onDismiss = { editPreviewDish = null }, onSave = { dishes[index] = it; editPreviewDish = null }) }
    var showDishDialog by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    // The setup exactly as it would start right now. The same value feeds
    // "Begin preparation", the dirty check, and the PL-13 kept draft, so
    // the three can never disagree about what the setup is.
    fun currentDraft(): NewCookDraft = NewCookDraft(
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
        recipeRevisionIds = if (dishRevisions.size == dishes.size) dishRevisions.toList() else emptyList(),
        recipeAttemptSnapshots = if (dishAttemptSnapshots.size == dishes.size) dishAttemptSnapshots.toList() else emptyList(),
        sourceLabel = initialDraft?.sourceLabel,
        scheduledPlanId = initialDraft?.scheduledPlanId,
        playbookId = initialDraft?.playbookId,
        followPlaybook = followPlaybook,
        recordGrill = recordGrill && grillState.selectedDevice != null,
        alreadyCooking = alreadyCooking, foodOnAtUtcMillis = if (alreadyCooking) foodOnTime else null,
        probe1DishIndex = probe1?.toIntOrNull(),
        probe2DishIndex = probe2?.toIntOrNull(),
        probe3DishIndex = probe3?.toIntOrNull(),
        probe4DishIndex = probe4?.toIntOrNull(),
    )
    val initialSnapshot = remember { currentDraft() }
    // PL-13: edits are kept as a draft on this phone as they happen, so
    // leaving — by any route — never loses the setup.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { currentDraft() }.collect { draft ->
            if (draft != initialSnapshot) onDraftChange(draft)
        }
    }
    val requestBack = {
        if (currentDraft() != initialSnapshot) confirmLeave = true else onBack()
    }

    if (showDishDialog) {
        DishEditorDialog(
            preferredWeightUnit = preferredWeightUnit,
            draftKey = "startcook",
            onDismiss = { showDishDialog = false },
            onSave = { draft ->
                dishes.add(draft)
                if (dishRevisions.size == dishes.size - 1) dishRevisions.add(null)
                if (dishAttemptSnapshots.size == dishes.size - 1) dishAttemptSnapshots.add(null)
                showDishDialog = false
            },
        )
    }

    if (confirmLeave) {
        // PL-13: leaving a changed setup keeps it as a draft — the same
        // words and choices as iOS. Discarding is the explicit third option.
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave this cook setup?") },
            text = { Text("Your setup is kept as a draft on this phone. Resume it any time from Meals.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onBack()
                }, modifier = Modifier.testTag("setup-keep-draft")) { Text("Keep draft") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onDiscardDraft()
                        confirmLeave = false
                        onBack()
                    }, modifier = Modifier.testTag("setup-discard")) { Text("Discard setup") }
                    TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TurnInBannerText("Start meal", style = PitTechTextStyles.bannerTitle) },
                navigationIcon = {
                    IconButton(onClick = requestBack, enabled = !saving) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Go back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = pitTechTokens().ground),
            )
        },
        bottomBar = {
            Surface(shadowElevation = 6.dp, color = pitTechTokens().surface) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (saveError != null) {
                        Text(saveError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Button(
                        onClick = { onStartCook(currentDraft()) },
                        enabled = !saving && CookEntryValidation.isOptionalPositiveNumberValid(setpoint),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("cook-save"),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(if (saving) "Saving cook…" else if (alreadyCooking) "Save active cook" else "Begin preparation")
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
            initialDraft?.sourceLabel?.let { label ->
                // The one review's source card (`d10`): where this setup
                // came from, stated before any field.
                Card(
                    Modifier.fillMaxWidth().testTag("cook-source"),
                    colors = CardDefaults.cardColors(containerColor = pitTechTokens().surface),
                    border = BorderStroke(1.dp, pitTechTokens().ruling),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TurnInMetaLabel("Source")
                        Text(label, style = PitTechTextStyles.bodyEmphasis)
                        Text("Nothing is cooking yet — review the setup, then begin preparation when you're ready.", style = PitTechTextStyles.body, color = pitTechTokens().inkMuted)
                    }
                }
            }
            if (initialPlaybook != null) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = pitTechTokens().surface),
                    border = BorderStroke(1.dp, pitTechTokens().ruling),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TurnInBannerText("${initialPlaybook.name} · revision ${initialPlaybook.revision}")
                        ChoiceField("Reuse", if (followPlaybook) "Setup and guided steps" else "Setup only", listOf("Setup and guided steps", "Setup only")) { followPlaybook = it == "Setup and guided steps" }
                        TextButton(onClick = { showSteps = !showSteps }) { Text(if (showSteps) "Hide steps" else "Review ${initialPlaybook.steps.size} steps") }
                        if (showSteps) initialPlaybook.steps.forEach { Text("${it.title} · ${stepDescription(it)}\n${it.instructions}") }
                        if (initialPlaybook.keepDoing.isNotBlank()) Text("Keep doing: ${initialPlaybook.keepDoing}")
                        if (initialPlaybook.changeNextTime.isNotBlank()) Text("Change next time: ${initialPlaybook.changeNextTime}")
                        TextButton(onClick = onEditPlaybook) { Text(if (initialDraft?.scheduledPlanId != null) "Adjust schedule and steps" else "Adjust playbook steps") }
                    }
                }
                dishes.forEachIndexed { index, dish -> OutlinedButton(onClick = { editPreviewDish = index }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Adjust ${dish.name}") } }
            }
            // `d08`: the stage is a segmented control, not a dropdown.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TurnInMetaLabel("Current stage")
                TurnInSegmentedControl(
                    options = listOf("Preparing", "Already cooking"),
                    selectedIndex = if (alreadyCooking) 1 else 0,
                    onSelect = { alreadyCooking = it == 1 },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (alreadyCooking) DateTimePickerField("Actual Food on", foodOnTime, { foodOnTime = it }, "cook-initial-food-on-time")
            Text("Add the basics now. You can fill in the rest during or after the cook.", style = PitTechTextStyles.body, color = pitTechTokens().inkMuted)
            TurnInBoxedField("Cook name") {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("Saturday brisket") },
                    supportingText = { Text("Defaults to today. You can rename this cook any time.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cook-title"),
                )
            }

            if (BuildConfig.CONTROLLER_TESTING_ENABLED) StartGrillRecordingPanel(
                grillState, recordGrill, { recordGrill = it }, dishes.mapIndexed { index, dish -> index.toString() to dish.name },
                probe1, probe2, { probe1 = it }, { probe2 = it }, onGrill,
                probe3, probe4, { probe3 = it }, { probe4 = it }, onSampling,
            )
            SectionHeading("Dishes")

            if (dishes.isEmpty()) {
                OutlinedButton(
                    onClick = { showDishDialog = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("cook-add-dish"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add a dish")
                }
                Text(
                    "No dish added yet. You can start now and add one later.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                dishes.forEachIndexed { index, dish ->
                    DishDraftCard(dish = dish, onRemove = { dishes.removeAt(index); if (dishRevisions.size == dishes.size + 1) dishRevisions.removeAt(index); if (dishAttemptSnapshots.size == dishes.size + 1) dishAttemptSnapshots.removeAt(index); probe1 = null; probe2 = null })
                }
                OutlinedButton(
                    onClick = { showDishDialog = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("cook-add-another-dish"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add another dish")
                }
            }

            if (equipmentProfiles.isNotEmpty()) ChoiceField("Saved equipment", "Choose equipment", equipmentProfiles.map { it.name }) { name ->
                equipmentProfiles.firstOrNull { it.name == name }?.let { profile -> smoker = profile.name; fuelType = profile.fuelType; woodBlend = profile.hopperBlend }
            }
            TurnInBoxedField("Smoker or grill (optional)") {
                OutlinedTextField(
                    value = smoker,
                    onValueChange = { smoker = it },
                    placeholder = { Text("Pit Boss Austin XL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cook-smoker"),
                )
            }
            maintenanceNote?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                TurnInBoxedField("Starting setpoint (optional)", modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = setpoint,
                        onValueChange = { setpoint = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = !CookEntryValidation.isOptionalPositiveNumberValid(setpoint),
                        supportingText = {
                            if (!CookEntryValidation.isOptionalPositiveNumberValid(setpoint)) {
                                Text("Enter a positive number, or leave it blank.")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("cook-setpoint"),
                    )
                }
                SimpleDropdownField(
                    label = "Unit",
                    value = setpointUnit,
                    options = listOf("°F", "°C"),
                    onSelect = { setpointUnit = it },
                    testTag = "cook-setpoint-unit",
                    modifier = Modifier.width(96.dp),
                )
            }
            TurnInBoxedField("Cook notes (optional)") {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = { Text("Weather, timing, changes, or anything to remember") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("cook-notes"),
                )
            }
            TextButton(onClick = { moreCookDetails = !moreCookDetails }, modifier = Modifier.testTag("cook-more-details")) {
                Text(if (moreCookDetails) "Hide more cook details" else "More cook details (optional)")
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
                TurnInBannerText(dish.name)
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
    initial: DishDraft? = null,
    preferredWeightUnit: String = "lb",
    // PL-24 (#172): the draft scope identifies WHICH dish edit this is
    // (start cook, a cook, a recipe, a playbook, a serve plan) so an
    // interrupted edit restores in its own context only.
    draftKey: String,
    onDismiss: () -> Unit,
    onSave: (DishDraft) -> Unit,
) {
    val draftStore = rememberEditorDraftStore()
    val fullDraftKey = EditorDraftKeys.dish(draftKey)
    // The dish as opened: the caller's values, or the kept draft when one
    // exists. Autosave below only writes once the fields move away from
    // this baseline, so merely opening the editor manufactures no draft.
    val initialDish = remember(fullDraftKey) {
        DishDraft(
            name = initial?.name.orEmpty(), foodType = initial?.foodType ?: "Other",
            cut = initial?.cut.orEmpty(), weightText = initial?.weightText.orEmpty(),
            weightUnit = initial?.weightUnit ?: preferredWeightUnit,
            startingCondition = initial?.startingCondition, boneIn = initial?.boneIn,
            placement = initial?.placement.orEmpty(), gradeOrSource = initial?.gradeOrSource.orEmpty(),
            thicknessNotes = initial?.thicknessNotes.orEmpty(), prepNotes = initial?.prepNotes.orEmpty(),
            rub = initial?.rub?.takeIf { it.isNotBlank() }
                ?: com.pittech.domain.CookInputVocabulary.rubFromIngredients(initial?.preparationItems ?: emptyList()).orEmpty(),
            preparationItems = initial?.preparationItems?.takeIf { it.isNotEmpty() } ?: listOf(IngredientDraft(name = "")),
            photoUris = emptyList(),
        )
    }
    val restored = remember(fullDraftKey) {
        draftStore.load(fullDraftKey)?.let(DishDraftCodec::decode)?.takeIf { it != initialDish }
    }
    var openedDish by remember(fullDraftKey) { mutableStateOf(restored ?: initialDish) }
    var draftRestored by rememberSaveable(fullDraftKey) { mutableStateOf(restored != null) }
    var name by rememberSaveable { mutableStateOf(openedDish.name) }
    var foodType by rememberSaveable { mutableStateOf(openedDish.foodType) }
    var cut by rememberSaveable { mutableStateOf(openedDish.cut) }
    var weight by rememberSaveable { mutableStateOf(openedDish.weightText) }
    var weightUnit by rememberSaveable { mutableStateOf(openedDish.weightUnit) }
    var detailsExpanded by rememberSaveable { mutableStateOf(restored != null) }
    var startingCondition by rememberSaveable { mutableStateOf(openedDish.startingCondition) }
    var boneIn by rememberSaveable { mutableStateOf(openedDish.boneIn) }
    var placement by rememberSaveable { mutableStateOf(openedDish.placement) }
    var gradeOrSource by rememberSaveable { mutableStateOf(openedDish.gradeOrSource) }
    var thicknessNotes by rememberSaveable { mutableStateOf(openedDish.thicknessNotes) }
    var prepNotes by rememberSaveable { mutableStateOf(openedDish.prepNotes) }
    var rub by rememberSaveable { mutableStateOf(openedDish.rub) }
    var preparationItems by remember { mutableStateOf(openedDish.preparationItems.takeIf { it.isNotEmpty() } ?: listOf(IngredientDraft(name = ""))) }
    var photoUris by rememberSaveable { mutableStateOf(openedDish.photoUris) }
    var nameError by rememberSaveable { mutableStateOf(false) }
    var weightError by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var showPhotoSource by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    fun currentDish() = DishDraft(
        name = name, foodType = foodType, cut = cut, weightText = weight, weightUnit = weightUnit,
        startingCondition = startingCondition, boneIn = boneIn, placement = placement,
        gradeOrSource = gradeOrSource, thicknessNotes = thicknessNotes, prepNotes = prepNotes,
        rub = rub,
        preparationItems = preparationItems, photoUris = photoUris,
    )
    // PL-24: autosave the working dish once it diverges from how the
    // editor opened; reverting to the opened state clears the draft,
    // and saving the dish clears it too.
    LaunchedEffect(name, foodType, cut, weight, weightUnit, startingCondition, boneIn, placement, gradeOrSource, thicknessNotes, prepNotes, rub, preparationItems, photoUris) {
        val current = currentDish()
        if (current != openedDish) draftStore.save(fullDraftKey, DishDraftCodec.encode(current))
    }
    fun resetToInitial() {
        openedDish = initialDish
        name = initialDish.name; foodType = initialDish.foodType; cut = initialDish.cut
        weight = initialDish.weightText; weightUnit = initialDish.weightUnit
        detailsExpanded = false; startingCondition = initialDish.startingCondition; boneIn = initialDish.boneIn
        placement = initialDish.placement; gradeOrSource = initialDish.gradeOrSource
        thicknessNotes = initialDish.thicknessNotes; prepNotes = initialDish.prepNotes
        rub = initialDish.rub
        preparationItems = initialDish.preparationItems
        photoUris = initialDish.photoUris
    }
    val requestDismiss = {
        val hasInput = name.isNotBlank() || foodType != "Other" || cut.isNotBlank() || weight.isNotBlank() ||
            startingCondition != null || boneIn != null || placement.isNotBlank() || gradeOrSource.isNotBlank() || thicknessNotes.isNotBlank() || prepNotes.isNotBlank() || rub.isNotBlank() ||
            preparationItems.any { it.name.isNotBlank() || it.amountText.isNotBlank() } || photoUris.isNotEmpty()
        if (hasInput) confirmDiscard = true else onDismiss()
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10),
    ) { selected ->
        photoUris = (photoUris + selected.map { it.toString() }).distinct().take(10)
    }
    val addCameraPhoto = rememberCameraPhotoCapture(onPhotoCaptured = { uri ->
        photoUris = (photoUris + uri.toString()).distinct().take(10)
    })

    // PL-22 (DD-6): the dish editor is a multi-field editor — full screen.
    FullScreenSurface(
        onDismiss = requestDismiss,
        title = "Add a dish",
        actions = {
            TextButton(onClick = requestDismiss) { Text("Cancel") }
            TextButton(onClick = {
                nameError = name.isBlank()
                weightError = !CookEntryValidation.isOptionalPositiveNumberValid(weight)
                val invalidAmounts = preparationItems.any { !CookEntryValidation.isOptionalPositiveNumberValid(it.amountText) }
                if (!nameError && !weightError && !invalidAmounts) {
                    draftStore.clear(fullDraftKey)
                    onSave(
                        currentDish().copy(
                            name = name.trim(),
                            preparationItems = preparationItems.filter { it.name.isNotBlank() },
                        ),
                    )
                }
            }, modifier = Modifier.testTag("dish-save")) { Text("Add dish") }
        },
    ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (draftRestored) {
                    ResumeDraftBanner(onDiscard = {
                        draftStore.clear(fullDraftKey)
                        draftRestored = false
                        resetToInitial()
                    })
                }
                TurnInBoxedField("Dish name") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; nameError = false },
                        placeholder = { Text("Brisket, ribs, pork loin") },
                        singleLine = true,
                        isError = nameError,
                        supportingText = { if (nameError) Text("Enter a dish name or cut.") },
                        modifier = Modifier.fillMaxWidth().testTag("dish-name"),
                    )
                }
                TextButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.testTag("dish-details-toggle")) {
                    Text(if (detailsExpanded) "Hide food details" else "More food details")
                }

                if (detailsExpanded) {
                SimpleDropdownField(
                    label = "Food type",
                    value = foodType,
                    options = listOf("Meat", "Beef", "Pork", "Poultry", "Seafood", "Wild game", "Vegetables", "Pasta", "Sides", "Dairy", "Baked dishes", "Desserts", "Other"),
                    onSelect = { foodType = it },
                    testTag = "dish-food-type",
                )
                if (foodType in listOf("Meat", "Beef", "Pork", "Poultry", "Seafood", "Wild game")) {
                OutlinedTextField(
                    value = cut,
                    onValueChange = { cut = it },
                    label = { Text("Specific cut (optional)") },
                    placeholder = { Text("St. Louis ribs, pork shoulder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("dish-cut"),
                )
                }
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


                    Text("Starting condition", style = MaterialTheme.typography.titleMedium)
                    SimpleDropdownField(
                        label = "Optional",
                        value = startingCondition ?: "Not set",
                        options = listOf("Not set", "Refrigerated", "Thawed", "Frozen", "Other"),
                        onSelect = { startingCondition = it.takeUnless { value -> value == "Not set" } },
                        testTag = "dish-starting-condition",
                    )
                    if (foodType in listOf("Meat", "Beef", "Pork", "Poultry", "Wild game")) {
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
                    }
                    OutlinedTextField(placement, { placement = it }, label = { Text("Smoker position (optional)") }, placeholder = { Text("Upper rack, left side") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("dish-placement"))
                    OutlinedTextField(gradeOrSource, { gradeOrSource = it }, label = { Text("Grade, brand, or source (optional)") }, modifier = Modifier.fillMaxWidth().testTag("dish-source"))
                    OutlinedTextField(rub, { rub = it }, label = { Text("Rub (optional)") }, placeholder = { Text("From the recipe, or name your own") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("dish-rub"))
                    OutlinedTextField(thicknessNotes, { thicknessNotes = it }, label = { Text("Size or thickness notes (optional)") }, modifier = Modifier.fillMaxWidth().testTag("dish-thickness"))

                    Text("Preparation and ingredients", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Record ingredients, sauces, seasonings, or preparation details when useful.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrepComboTools(preparationItems) { preparationItems = it }
                    preparationItems.forEachIndexed { index, item ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = item.name,
                                onValueChange = { value ->
                                    preparationItems = preparationItems.mapIndexed { i, current -> if (i == index) current.copy(name = value) else current }
                                },
                                label = { Text("Ingredient or preparation") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("preparation-name-$index"),
                            )
                            var itemDetails by rememberSaveable(index) { mutableStateOf(false) }
                            TextButton(onClick = { itemDetails = !itemDetails }, modifier = Modifier.testTag("preparation-item-details-" + index)) { Text(if (itemDetails) "Hide item details" else "Item details") }
                            if (itemDetails) {
                            SimpleDropdownField(
                                label = "Type",
                                value = item.stage.replace('_', ' ').replaceFirstChar { it.titlecase() },
                                options = listOf("Main ingredient", "Seasoning", "Sauce", "Marinade", "Binder", "Brine", "Glaze", "Topping", "Injection", "Spritz", "Wrap addition", "Other"),
                                onSelect = { value ->
                                    preparationItems = preparationItems.mapIndexed { i, current ->
                                        if (i == index) current.copy(stage = value.lowercase().replace(' ', '_')) else current
                                    }
                                },
                                testTag = "preparation-type-$index",
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
                                    options = listOf("cup", "tbsp", "tsp", "fl oz", "ml", "L", "g", "oz", "lb", "kg", "count"),
                                    onSelect = { value ->
                                        preparationItems = preparationItems.mapIndexed { i, current -> if (i == index) current.copy(amountUnit = value) else current }
                                    },
                                    testTag = "preparation-unit-$index",
                                    modifier = Modifier.width(106.dp),
                                )
                            }
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
                    onClick = { showPhotoSource = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("dish-add-photos"),
                ) {
                    Text(if (photoUris.isEmpty()) "Add photos" else "Add more photos (${photoUris.size})")
                }
                photoUris.forEachIndexed { index, _ ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Photo ${index + 1}", modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            CameraPhotoFiles.delete(context, photoUris[index])
                            photoUris = photoUris.filterIndexed { i, _ -> i != index }
                        }) {
                            Text("Remove")
                        }
                    }
                }
            }
    }

    if (showPhotoSource) {
        PhotoSourceDialog(
            onDismiss = { showPhotoSource = false },
            onTakePhoto = {
                showPhotoSource = false
                addCameraPhoto()
            },
            onChooseFromLibrary = {
                showPhotoSource = false
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }

    if (confirmDiscard) {
        // PL-24 (#172): leaving keeps the dish as a draft — the same
        // words and choices as the Start Cook leave dialog (PL-13).
        // Discarding is the explicit third option.
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Leave this dish?") },
            text = { Text("Your dish is kept as a draft on this phone. It comes back next time you open this editor.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDismiss()
                }, modifier = Modifier.testTag("dish-keep-draft")) { Text("Keep draft") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        photoUris.forEach { CameraPhotoFiles.delete(context, it) }
                        draftStore.clear(fullDraftKey)
                        confirmDiscard = false
                        onDismiss()
                    }, modifier = Modifier.testTag("dish-discard")) { Text("Discard dish") }
                    TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
                }
            },
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
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
            ) {
                Text(value, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose $label")
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        modifier = if (testTag == null) Modifier else Modifier.testTag("$testTag-option-${option.lowercase(java.util.Locale.ROOT).replace(' ', '-')}"),
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
