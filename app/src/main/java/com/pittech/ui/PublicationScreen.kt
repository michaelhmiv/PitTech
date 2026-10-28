package com.pittech.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel
import com.pittech.data.*
import com.pittech.domain.FoodCategories
import com.pittech.domain.SharingDraftCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PublicationDialog(draft: String, viewModel: CooksViewModel, account: AccountManager = viewModel.account, writer: PublicationWriter = viewModel.publicationWriter, onClose: () -> Unit) {
    val state by account.presentation.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var prepared by remember { mutableStateOf<PreparedPublication?>(null) }; var previewUser by remember { mutableStateOf<String?>(null) }
    var identity by remember { mutableStateOf("anonymous") }; var kind by remember { mutableStateOf("recipe") }
    var category by remember { mutableStateOf(FoodCategories.DEFAULT_ID) }
    var updateSummary by remember { mutableStateOf<String?>(null) }
    var linked by remember { mutableStateOf<PublicationReceipt?>(null) }; var updating by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<PublicationOperation?>(null) }; var reviewed by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("a PitTech cook") }; var published by remember { mutableStateOf<PublicationReceipt?>(null) }
    var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }; var signingIn by remember { mutableStateOf(false) }
    var lastSignedInUser by remember { mutableStateOf<String?>(null) }
    fun invalidate() { prepared=null; reviewed=false; previewUser=null; updateSummary=null }
    suspend fun refreshPending() {
        pending=writer.pending(); linked=writer.binding(draft)
        pending?.takeIf { it.draftId==draft && it.contentType=="application/zip" }?.let { op -> val value=withContext(Dispatchers.IO) { pendingPublication(writer.store.verify(op)) }; check(account.publicationUserId()==op.userId); prepared=value }
    }
    fun run(action: suspend () -> Unit) {
        if(busy)return; busy=true; message=null
        scope.launch { try { action() } catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException)throw e; message=ErrorCopy.failureText(e, "Couldn't reconnect. The exact request stays saved."); try { refreshPending() } catch(_: Exception) { } } finally { busy=false } }
    }
    LaunchedEffect(state.signedIn) {
        // PL-18: a prepared preview survives the sign-in detour. It is
        // only discarded when the signed-in account actually changes to a
        // different user — the confirm step separately re-checks that the
        // current user is the one who prepared it. Signing out keeps the
        // marker so signing back in as the same user restores the preview;
        // the signed-out branch of this sheet shows the sign-in prompt
        // meanwhile, never the stale preview.
        val currentUser = if(state.signedIn) runCatching { account.publicationUserId() }.getOrNull() else null
        if(currentUser != null) {
            if(lastSignedInUser != null && lastSignedInUser != currentUser) { invalidate(); published=null }
            lastSignedInUser = currentUser
        } else if(!state.signedIn) {
            published = null
        }
        try { val document=withContext(Dispatchers.IO) { SharingDraftCodec.decode(checkNotNull(viewModel.sharing.get(draft)).payload) }; identity=document.identityMode; category=document.categoryId; refreshPending(); if(pending==null)linked?.kind?.let { kind=it } } catch(e: Exception) { message=ErrorCopy.failureText(e, "Couldn't reconnect. Nothing changed on this device.") }
    }
    // Slice 8 (PL-15 / DD-10): completing sign-in returns to this sheet on
    // its own — the account dialog closes itself instead of waiting for a
    // manual "Return to publication" tap. Cancelling it lands here too.
    LaunchedEffect(state.signedIn) { if(state.signedIn) signingIn=false }
    ModalBottomSheet(sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),onDismissRequest={ if(!busy)onClose() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth()) { Text("Publish preview",style=PitTechTextStyles.bannerTitle,modifier=Modifier.weight(1f)); TextButton(onClick=onClose,enabled=!busy) { Text("Done") } }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("Publish a selected recipe, collection or actual cook. Anyone can read and save a public copy.",color=pitTechTokens().inkMuted)
                if(!state.signedIn) {
                    Text("Sign in to publish. This sharing draft stays on this device, and sign-in won't publish it.")
                    Button(onClick={ signingIn=true },modifier=Modifier.testTag("publication-sign-in")) { Text("Sign in to continue") }
                } else if(pending!=null) {
                    val op=pending!!
                    Text(if(op.rejected)"This request was rejected" else "An interrupted request is saved",style=PitTechTextStyles.bannerCard)
                    Text("Retry uses the exact saved content and the same account. Editing the sharing draft won't change this request.",color=pitTechTokens().inkMuted)
                    prepared?.let { PublicationContent(it) }
                    if(op.draftId==draft && prepared!=null && !op.rejected)Button(onClick={ run { writer.recover(); prepared=null; refreshPending(); message="The saved publication was recovered." } },enabled=!busy,modifier=Modifier.testTag("publication-recover")) { Text("Retry the saved publication") }
                    if(op.draftId!=draft)Text("Recover the saved account or publication change in My cookbook first.")
                    if(op.rejected) {
                        Text("Review the current publication before making a new preview. A rejection did not apply this request.")
                        TextButton(onClick={ run { withContext(Dispatchers.IO) { writer.store.discardRejected(checkNotNull(account.publicationUserId())) }; prepared=null; refreshPending() } },enabled=!busy) { Text("Discard rejected request") }
                    }
                } else if(published!=null) {
                    Text(if(published!!.status=="pending_review")"Submitted for review" else "Published",style=PitTechTextStyles.bannerTitle,modifier=Modifier.testTag("publication-complete"))
                    val uri=androidx.compose.ui.platform.LocalUriHandler.current
                    if(published!!.status=="pending_review")Text("This revision becomes public after its text and selected photos pass review. Check My cookbook for its status.")
                    else Button(onClick={ uri.openUri(published!!.url) }) { Text("Open public recipe") }
                    // J9: after publishing, the next step lands where the
                    // thing went — My Cookbook — not back at the draft.
                    OutlinedButton(onClick={ onClose(); viewModel.requestCookbookOpen() },enabled=!busy,modifier=Modifier.testTag("publication-open-cookbook")) { Text("Open My cookbook") }
                    Text("Your local recipe and measured results stay on this device. Independent copies saved by other cooks remain theirs.")
                } else {
                    val kindLabels=mapOf("recipe" to "Recipe","collection" to "Meal collection","attempt" to "Actual recorded cook")
                    SimpleDropdownField("Publication",kindLabels.getValue(kind),kindLabels.values.toList(),{ label -> kind=kindLabels.entries.first { it.value==label }.key; invalidate() },enabled=!busy)
                    SimpleDropdownField("Public credit",if(identity=="anonymous")"Anonymous · a PitTech cook" else "My display name",listOf("Anonymous · a PitTech cook","My display name"),{ identity=if(it=="My display name")"named" else "anonymous"; invalidate() },enabled=!busy)
                    Text(if(identity=="anonymous")"Your profile name and cookbook link won't appear on this publication." else "Your current display name appears publicly. Your cookbook link appears only while your public cookbook is enabled.",color=pitTechTokens().inkMuted)
                    CategoryDropdownField(category,enabled=!busy) { category=it; invalidate() }
                    if(linked!=null)Row { Checkbox(updating,{ updating=it; invalidate() },enabled=!busy); Text("Update the publication linked to this draft",Modifier.weight(1f)) }
                    Button(onClick={ run {
                        invalidate(); val user=checkNotNull(account.publicationUserId()); val profile=writer.read("/v1/me/profile")
                        displayName=if(identity=="anonymous")"a PitTech cook" else profile.optString("displayName").ifBlank { "a PitTech cook" }
                        withContext(Dispatchers.IO) { val row=checkNotNull(viewModel.sharing.get(draft)); val document=SharingDraftCodec.decode(row.payload).copy(identityMode=identity,categoryId=category); viewModel.sharing.save(draft,document,row.updatedAtUtcMillis) }
                        val context=if(updating && linked!=null)writer.editContext(draft) else null
                        val value=viewModel.sharing.preparePublication(draft,kind,context); check(account.publicationUserId()==user)
                        linked=writer.binding(draft); updateSummary=null
                        linked?.takeIf { updating }?.let { old ->
                            val current=writer.read("/v1/me/recipes/"+old.id); require(current.getString("id")==old.id && current.getString("status") in setOf("active","pending_review","unpublished")) { "This publication is unavailable for updates. Your private draft stays on this device." }
                            if(context!=null && context.receipt.revisionId!=current.getString("revisionId"))throw AccountFailure(409)
                            linked=PublicationReceipt(old.id,current.getString("revisionId"),current.getInt("revision"),old.url).also { it.validate() }
                            updateSummary=if(current.getString("status")=="unpublished")"This publication is currently removed. Publishing this preview will make it public again." else "This preview will replace public revision ${current.getInt("revision")}."
                        }
                        check(account.publicationUserId()==user); prepared=value; previewUser=user; reviewed=false
                    } },enabled=!busy,modifier=Modifier.testTag("publication-prepare")) { Text("Prepare public preview") }
                    prepared?.let { value ->
                        Text("Public credit · $displayName",style=PitTechTextStyles.bannerCard,modifier=Modifier.testTag("publication-credit"))
                        updateSummary?.let { TurnInStatusStrip(it,pitTechTokens().marigold) }
                        PublicationContent(value)
                        Text(PublicationTerms.text,modifier=Modifier.testTag("publication-terms"))
                        Row { Checkbox(reviewed,{ reviewed=it },modifier=Modifier.testTag("publication-reviewed")); Text("I reviewed the content and credit and agree to these public-copy terms",Modifier.weight(1f)) }
                        Button(onClick={ run {
                            check(reviewed && previewUser==account.publicationUserId()) { "Sign in with the account that prepared this preview." }
                            published=PublicationReceipt.parse(writer.submit(draft,value,if(updating)linked else null,PublicationTerms.version)); prepared=null; refreshPending()
                        } },enabled=!busy && reviewed,modifier=Modifier.testTag("publication-confirm")) { Text(if(updating && linked!=null)"Publish this update" else "Publish this preview") }
                    }
                }
                if(busy)Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)){CircularProgressIndicator();Text(LoadingCopy.PUBLISHING)}
                message?.let { TurnInStatusStrip(it,pitTechTokens().flag,Modifier.testTag("publication-message")) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    // AccountContent is itself the scrolling container: wrapping it in a
    // second verticalScroll here measured it with infinite height and
    // crashed (issue #233). The sheet wrapper must not scroll.
    if(signingIn)ModalBottomSheet(onDismissRequest={ signingIn=false }){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Account",style=PitTechTextStyles.bannerTitle);AccountContent(account);TextButton(onClick={ signingIn=false }) { Text("Return to publication") }}}
}

/**
 * The publish-flow category picker: the shared taxonomy list, one primary
 * category per publication. Each item shows the label with its examples
 * underneath so people classify by recognition (a smoked cheese is an app,
 * mac & cheese is a side) instead of guessing at the label alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CategoryDropdownField(selectedId: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = FoodCategories.byId(selectedId) ?: FoodCategories.byId(FoodCategories.DEFAULT_ID)!!
    Column(Modifier.fillMaxWidth()) {
        Text("Category", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("publication-category"),
            ) {
                Text(selected.label, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose Category")
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                FoodCategories.all.forEach { category ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(category.label)
                                Text(category.examples.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = pitTechTokens().inkMuted)
                            }
                        },
                        modifier = Modifier.testTag("publication-category-option-${category.label.lowercase(java.util.Locale.ROOT).replace(' ', '-')}"),
                        onClick = {
                            expanded = false
                            onSelect(category.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable internal fun PublicationContent(prepared: PreparedPublication) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val temperatureUnit=context.getSharedPreferences("pittech-preferences",0).getString("temperature-unit","°F") ?: "°F"
    val payload=prepared.payload; val recipes=remember(prepared) { payload.rows("recipes") }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(payload.getString("title"),style=PitTechTextStyles.bannerTitle,modifier=Modifier.testTag("publication-preview-title"))
        Text(when(payload.getString("kind")) { "attempt" -> "Actual recorded cook"; "collection" -> "Meal collection"; else -> "Recipe" },style=PitTechTextStyles.monoMeta,color=pitTechTokens().inkMuted)
        Text("${recipes.size} selected recipes · ${prepared.photos.size} selected photos",style=PitTechTextStyles.monoMeta,color=pitTechTokens().inkMuted)
        Text("Public credit: "+if(payload.getString("identityMode")=="named")"your display name" else "a PitTech cook")
        recipes.forEach { recipe ->
            Text(recipe.getString("title"),style=PitTechTextStyles.bannerCard); Text(recipe.getString("description"))
            Text(listOf("foodType","cut","method").mapNotNull { if(recipe.isNull(it))null else recipe.optString(it).takeIf(String::isNotBlank) }.joinToString(" · "),style=PitTechTextStyles.monoMeta,color=pitTechTokens().inkMuted)
            if(!recipe.isNull("weightGrams"))Text("Weight · "+String.format(java.util.Locale.ROOT,"%.2f kg",recipe.getDouble("weightGrams")/1000),style=PitTechTextStyles.monoMeta,color=pitTechTokens().inkMuted)
            val tags=recipe.getJSONArray("tags"); Text((0 until tags.length()).joinToString(" · ") { tags.getString(it) },style=PitTechTextStyles.monoMeta,color=pitTechTokens().inkMuted)
            recipe.optJSONObject("source")?.let { source ->
                val uri=androidx.compose.ui.platform.LocalUriHandler.current
                Text(if(source.optBoolean("removed"))"Based on a removed recipe" else "Based on a pinned public recipe")
                if(!source.optBoolean("removed"))TextButton(onClick={ uri.openUri("https://pittech.app/c/"+source.getString("itemId")) }) { Text("View source recipe") }
            }
            recipe.rows("ingredients").forEach { Text(it.getString("name")+" · "+it.getString("amount")) }
            recipe.rows("instructions").forEach { step ->
                Text(step.getString("title"),style=MaterialTheme.typography.titleMedium); Text(step.getString("details"))
                if(!step.isNull("method"))Text("Method · "+step.getString("method"))
                if(!step.isNull("action"))Text("Action · "+step.getString("action"))
                if(!step.isNull("suggestedOffsetMillis"))Text("Suggested timing · "+com.pittech.domain.RecipeReferenceTimeline.relativeLabel(step.getLong("suggestedOffsetMillis")))
            }
            PublicationPhotoPreview(recipe.rows("assets"),prepared.photos,payload.optString("heroAssetId"))
            recipe.optJSONObject("story")?.let { PublicStoryView(it,temperatureUnit) }
            HorizontalDivider()
        }
    }
}

private fun JSONObject.rows(key: String)=getJSONArray(key).let { array -> (0 until array.length()).map(array::getJSONObject) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PublicLikeActions(id: String, account: AccountManager, writer: PublicationWriter) {
    val state by account.presentation.collectAsStateWithLifecycle(); val scope=rememberCoroutineScope()
    var busy by remember(id) { mutableStateOf(false) }; var signingIn by remember { mutableStateOf(false) }; var message by remember(id) { mutableStateOf<String?>(null) }
    // Slice 8 (PL-15 / DD-10): sign-in completion closes the account
    // dialog and returns here automatically, like the publication sheet.
    LaunchedEffect(state.signedIn) { message=null; if(state.signedIn) signingIn=false }
    fun set(liked: Boolean) { if(busy)return; busy=true; scope.launch {
        try { writer.json(if(liked)"POST" else "DELETE","/v1/recipes/$id/like"); message=if(liked)"Your like is saved." else "Your like is removed." }
        catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException)throw e; message=ErrorCopy.failureText(e, "Couldn't reconnect.")+" Recover interrupted changes in My cookbook." } finally { busy=false }
    } }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        // §7 disposition: the Like is a placard, not a text button. The app
        // carries no local like state or count on this surface, so the
        // placard keeps the existing copy; Remove stays a quiet button.
        if(state.signedIn) {
            TurnInPlacard(icon=Icons.Filled.Favorite,label="Like this recipe",onClick={ set(true) },modifier=Modifier.fillMaxWidth(),testTag="public-like",enabled=!busy)
            TextButton(onClick={ set(false) },enabled=!busy,modifier=Modifier.testTag("public-unlike")) { Text("Remove my like") }
        } else {
            TextButton(onClick={ signingIn=true }) { Text("Sign in to like this recipe") }; Text("Signing in won't add a like automatically.",style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)
        }
        message?.let { Text(it) }
    }
    // AccountContent is itself the scrolling container: wrapping it in a
    // second verticalScroll here measured it with infinite height and
    // crashed (issue #233). The sheet wrapper must not scroll.
    if(signingIn)ModalBottomSheet(onDismissRequest={ signingIn=false }){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Account",style=PitTechTextStyles.bannerTitle);AccountContent(account);TextButton(onClick={ signingIn=false }) { Text("Return to recipe") }}}
}

@Composable private fun PublicationPhotoPreview(assets: List<JSONObject>, photos: Map<String,ByteArray>, hero: String) {
    if(assets.isEmpty())return
    var selected by remember(assets) { mutableStateOf(0) }; val index=selected.coerceIn(0,assets.lastIndex); val asset=assets[index]
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Selected photo ${index+1} of ${assets.size}",style=PitTechTextStyles.bannerCard)
        photos[asset.getString("id")]?.let { bytes ->
            val bitmap=remember(bytes) {
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                var sample=1; while(bounds.outWidth/sample>800 || bounds.outHeight/sample>800)sample*=2
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })?.asImageBitmap()
            }
            if(bitmap!=null)Image(bitmap,asset.getString("caption").ifBlank { "Selected public photo" },Modifier.fillMaxWidth().heightIn(max=260.dp))
        }
        if(hero==asset.getString("id"))Text("Cover photo",style=MaterialTheme.typography.bodySmall,color=pitTechTokens().attentionText)
        Text(asset.getString("caption"))
        if(assets.size>1)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick={ selected=maxOf(0,index-1) },enabled=index>0,modifier=Modifier.weight(1f).heightIn(min=48.dp)) { Text("Previous photo") }
            TextButton(onClick={ selected=minOf(assets.lastIndex,index+1) },enabled=index<assets.lastIndex,modifier=Modifier.weight(1f).heightIn(min=48.dp)) { Text("Next photo") }
        }
    }
}
