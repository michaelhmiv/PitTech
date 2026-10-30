package com.pittech.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.data.PublicManager
import com.pittech.domain.FoodCategories
import org.json.JSONObject

@Composable internal fun PublicDiscoveryScreen(manager: PublicManager,modifier: Modifier = Modifier,temperatureUnit: String = "°F", account: com.pittech.data.AccountManager? = null, publicationWriter: com.pittech.data.PublicationWriter? = null, controls:com.pittech.data.CommunityControls?=null, onOpenRecipes: () -> Unit = {}) {
    val state by manager.state.collectAsStateWithLifecycle()
    val accountState=account?.presentation?.collectAsStateWithLifecycle()?.value
    var previousSignedIn by remember{mutableStateOf(accountState?.signedIn)}
    LaunchedEffect(accountState?.signedIn){if(previousSignedIn!=accountState?.signedIn){previousSignedIn=accountState?.signedIn;manager.controlsChanged()}}
    var query by rememberSaveable { mutableStateOf(state.appliedQuery) }
    var food by rememberSaveable { mutableStateOf(state.appliedFood) }
    var managingControls by remember{mutableStateOf(false)}
    var showFork by remember{mutableStateOf(false)}
    var forkInFlight by remember{mutableStateOf(false)}
    // The tweak sheet closes when its fork lands; a failure leaves it
    // open with the error showing, tweaks intact.
    LaunchedEffect(state.forking) {
        if(forkInFlight && !state.forking) {
            forkInFlight=false
            if(state.savedDetailId!=null && state.savedDetailId==state.detailId)showFork=false
        }
    }
    LaunchedEffect(state.detailId) { if(state.detailId==null)showFork=false }
    var sort by rememberSaveable { mutableStateOf(state.appliedSort) }
    var category by rememberSaveable { mutableStateOf(state.appliedCategory) }
    LaunchedEffect(manager) { if(state.items.isEmpty())manager.refresh(state.appliedSort,state.appliedFood,state.appliedQuery,state.appliedCategory) }
    BackHandler(state.detailId != null || state.profileHandle != null) { manager.back() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        // PL-23 (DD-8): Discover is a network surface — offline is stated,
        // with the cached content below unchanged.
        if(!rememberOnlineState())TurnInStatusStrip(OfflineCopy.CHANGES_WAIT,pitTechTokens().cobalt,Modifier.testTag("discover-offline"))
        if(controls!=null && account!=null)OutlinedButton(onClick={managingControls=true},modifier=Modifier.heightIn(min=48.dp)) { Text("Community controls") }
        if(state.profileHandle != null) { PublicProfileContent(manager,state,temperatureUnit) } else if(state.detailId != null) {
            TextButton(onClick={ manager.back() }) { Text("Back to Discover") }
            if(state.detailLoading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(); Text(LoadingCopy.LOADING_RECIPE) }
            state.detail?.let { detail ->
                state.image?.let { bytes ->
                    val bitmap=remember(bytes) { BitmapFactory.decodeByteArray(bytes,0,bytes.size) }
                    if(bitmap != null)Image(bitmap.asImageBitmap(),"Selected photograph for ${detail.title}",Modifier.fillMaxWidth().heightIn(max=280.dp))
                    val caption=detail.recipes.flatMap { it.rows("assets") }.firstOrNull { it.getString("id")==detail.hero?.getString("id") }?.optString("caption").orEmpty()
                    if(caption.isNotEmpty())Text(caption,style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)
                }
                Text(detail.title,Modifier.testTag("public-title"),style=PitTechTextStyles.bannerTitle)
                Text("Shared by ${detail.credit}",style=PitTechTextStyles.monoMeta,color=pitTechTokens().inkMuted)
                // Slice 5 (Spread 04, step 2): the publication's own
                // numbers — stars and forks as the book draws them,
                // plus its category. Counts appear only when the
                // response carried them (§4.5's slot rule).
                Text(listOfNotNull(detail.likes?.let { "★ $it" },detail.forks?.let { "⑂ $it" },com.pittech.domain.FoodCategories.labelFor(com.pittech.domain.FoodCategories.normalizedId(detail.payload.optString("category")))).joinToString(" · "),style=PitTechTextStyles.monoMeta,color=pitTechTokens().inkMuted)
                detail.profileUrl?.let { url->TextButton(onClick={manager.openProfile(com.pittech.data.PublicLink.destination(url).substringAfter(':'))}) { Text("View public cookbook") } }
                if(account?.enabled==true && publicationWriter!=null)PublicStarActions(detail.id,account,publicationWriter)
                if(controls!=null && account!=null)CommunityActions(detail,controls,account,manager)
                Text("Your saved version stays yours. Publishing a variation is a separate choice.",style=MaterialTheme.typography.bodyMedium,color=pitTechTokens().inkMuted)
                if(state.savedDetailId == detail.id) {
                    // PL-19: after a save, the primary lands where the
                    // thing went — the saved recipes in Recipes.
                    Button(onClick=onOpenRecipes,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("public-open-recipes")) { Text("Open in Recipes") }
                }
                // Slice 5 (element 4.2/4.4): Star and Fork are the
                // actions on this page, by those names — Fork saves as
                // mine from the payload on device, no Cook Pack
                // download. "Plan this" forks as written if needed and
                // opens the planner on the fork (direct seeding).
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.fillMaxWidth()) {
                    Button(onClick={ showFork=true },enabled=!state.forking && !state.preparing,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("public-fork")) { Text("Fork") }
                    OutlinedButton(onClick={ manager.preview(true) },enabled=!state.preparing && !state.forking,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("public-cook")) { Text("Cook this") }
                }
                OutlinedButton(onClick={ manager.planThis() },enabled=!state.forking && !state.preparing,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("public-plan-this")) { Text("Plan this") }
                if(state.forking) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(); Text("Saving your version…") } }
                if(state.preparing) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(); Text(LoadingCopy.DOWNLOADING_COOK_PACK) }; TextButton(onClick={ manager.cancel() }) { Text("Cancel download") } }
                detail.recipes.forEach { recipe ->
                    TurnInCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("Recipe",style=PitTechTextStyles.monoMeta,color=pitTechTokens().inkMuted)
                            Text(recipe.getString("title"),style=PitTechTextStyles.bannerCard)
                            recipe.optJSONObject("source")?.let { source->
                                if(source.optBoolean("removed"))Text("Based on a removed recipe",style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)
                                else TextButton(onClick={manager.open(source.getString("itemId"))}) { Text("Based on a recipe shared by "+source.optString("displayName","a PitTech cook")) }
                            }
                            Text(recipe.optString("description"),style=MaterialTheme.typography.bodyMedium)
                            if(!recipe.isNull("weightGrams"))Text("Recorded weight · ${recipe.getDouble("weightGrams")/1000} kg",style=PitTechTextStyles.monoMeta,color=pitTechTokens().inkMuted)
                            recipe.rows("ingredients").forEach { Text(it.optString("name")+" · "+it.optString("amount"),style=MaterialTheme.typography.bodyMedium) }
                            recipe.rows("instructions").forEach { step->
                                Text(step.optString("title"),style=MaterialTheme.typography.titleMedium)
                                if(!step.isNull("method") && step.getString("method").isNotEmpty())Text("Method · "+step.getString("method"),style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)
                                Text(step.optString("details"),style=MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if(!recipe.isNull("story"))PublicStoryView(recipe.getJSONObject("story"),temperatureUnit)
                    recipe.rows("assets").forEach { photo->detail.media.firstOrNull { it.getString("id")==photo.getString("id") }?.let { asset->
                        if(asset.getString("id")!=detail.hero?.getString("id"))PublicAdditionalPhoto(manager,detail,asset,photo.optString("caption"))
                    } }
                }
            }
            state.message?.let { Text(it,Modifier.testTag("public-message")) }
            OutlinedButton(onClick={ manager.open(state.detailId!!) },enabled=!state.detailLoading && !state.preparing,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("Refresh preview") }
        } else {
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                // Slice 5 (element 4.5): Discover is the community
                // shelf — "Public cookbook" names the storefront a
                // cook publishes from, and "My Cookbook" is the user's
                // own library. Neither word belongs on this header.
                TurnInMetaLabel("Community cooks")
                Text("Find your next cook",style=PitTechTextStyles.bannerTitle)
            }
            Text("Browse, star and fork without an account. Forking makes your own private copy.",style=MaterialTheme.typography.bodyMedium,color=pitTechTokens().inkMuted)
            TurnInLiteralBoxedField("Search recipes",Modifier.fillMaxWidth()) {
                OutlinedTextField(query,{query=it.take(80)},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("discover-query"))
            }
            TurnInLiteralBoxedField("Food type (optional)",Modifier.fillMaxWidth()) {
                OutlinedTextField(food,{food=it.take(80)},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("discover-food"))
            }
            // Category is a separate controlled filter from the free-text
            // food field above: the shared taxonomy list plus All, applied
            // immediately on selection like the sort chips.
            SimpleDropdownField("Category",if(category.isEmpty())"All categories" else FoodCategories.labelFor(category),listOf("All categories")+FoodCategories.all.map { it.label },{ label -> category=if(label=="All categories")"" else FoodCategories.all.first { it.label==label }.id; manager.refresh(sort,food,query,category) },testTag="discover-category",enabled=!state.loading)
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                TurnInMetaLabel("Sort")
                // PL-19: sort changes apply on tap — no separate Search tap
                // stands between the choice and its result. (The text
                // fields still apply with the Search button below.)
                FilterChip(sort=="liked",{sort="liked";manager.refresh("liked",food,query,category)},label={Text("Most liked")},colors=turnInChipColors())
                FilterChip(sort=="forked",{sort="forked";manager.refresh("forked",food,query,category)},label={Text("Most forked")},colors=turnInChipColors())
            }
            Button(onClick={ manager.refresh(sort,food,query,category) },enabled=!state.loading,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("discover-search")) { Text("Search recipes") }
            if(state.loading)Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)){CircularProgressIndicator();Text(LoadingCopy.LOADING_RECIPES)}
            state.message?.let { TurnInStatusStrip(it,pitTechTokens().flag,Modifier.testTag("discover-message")) }
            if(state.items.isEmpty() && !state.loading && state.message == null)TurnInDashedCard(Modifier.testTag("discover-empty")) {
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("No recipes here yet. Try another food or search.",style=MaterialTheme.typography.bodyMedium)
                    Text("Published cooks appear here. Try another food, or clear the search.",style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)
                }
            }
            state.items.forEach { item ->
                DiscoverCard(manager,item,temperatureUnit) { manager.open(item.id) }
            }
            if(state.nextCursor != null)OutlinedButton(onClick={ manager.refresh(state.appliedSort,state.appliedFood,state.appliedQuery,state.appliedCategory,more=true) },enabled=!state.loading,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("More recipes") }
        }
    }
    if(managingControls && controls!=null && account!=null)CommunityControlsDialog(controls,account,manager){managingControls=false}
    if(showFork)state.detail?.let { detail->
        PublicationForkSheet(detail,busy=state.forking,error=if(state.forking)null else state.message,onDismiss={ showFork=false },onSave={ tweaks->forkInFlight=true;manager.fork(tweaks) })
    }
}
private fun JSONObject.rows(key: String)=getJSONArray(key).let { array->(0 until array.length()).map(array::getJSONObject) }

/**
 * Slice 5, element 4.1: a Discover card you can browse by — the
 * publication's photo, and the recorded cook's time and pit temp
 * when its story carries them (§4.5's slot rule: a slot appears only
 * when its data is real). Facts and photo load through the manager's
 * cached, hash-verified paths — the same fetch opening the
 * publication makes — so a card never shows an unverified image or
 * an invented number.
 */
@Composable internal fun DiscoverCard(manager: PublicManager,item: com.pittech.data.PublicItem,temperatureUnit: String,onOpen: () -> Unit) {
    var facts by remember(item.id) { mutableStateOf<com.pittech.data.PublicCardFacts?>(null) }
    var photo by remember(item.id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(item.id,temperatureUnit) {
        facts=manager.cardFacts(item,temperatureUnit)
        photo=manager.cardImage(item)
    }
    TurnInCard(Modifier.fillMaxWidth().testTag("discover-open-${item.id}"),onClick=onOpen) {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            photo?.let { bytes->
                val bitmap=remember(bytes) { BitmapFactory.decodeByteArray(bytes,0,bytes.size) }
                if(bitmap!=null)Image(bitmap.asImageBitmap(),"Photo for ${item.title}",Modifier.fillMaxWidth().heightIn(max=180.dp))
            }
            Text(item.title,style=PitTechTextStyles.bannerCard)
            Text(item.credit,style=MaterialTheme.typography.bodyMedium)
            val factsLine=listOfNotNull(facts?.timeLabel,facts?.tempLabel).joinToString(" · ")
            if(factsLine.isNotEmpty())Text(factsLine,style=PitTechTextStyles.monoMeta,modifier=Modifier.testTag("discover-card-facts-${item.id}"))
            Text("${item.categoryLabel} · ★ ${item.likes} · ⑂ ${item.forks}",style=PitTechTextStyles.monoMeta,color=pitTechTokens().inkMuted)
        }
    }
}

/** Shared TURN-IN chip colors for this area: marigold fill when selected. */
@Composable internal fun turnInChipColors() = FilterChipDefaults.filterChipColors(
    containerColor=pitTechTokens().surface,
    labelColor=pitTechTokens().ink,
    selectedContainerColor=pitTechTokens().marigold,
    selectedLabelColor=pitTechTokens().onAccent,
)
