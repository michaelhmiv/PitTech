package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel
import com.pittech.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun MyCookbookDialog(viewModel: CooksViewModel, account: AccountManager = viewModel.account, writer: PublicationWriter = viewModel.publicationWriter, onClose: () -> Unit) {
    val state by account.presentation.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope(); val uri=LocalUriHandler.current
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    val contentScroll=rememberScrollState()
    var loadedUser by remember { mutableStateOf<String?>(null) }; var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }; var cursor by remember { mutableStateOf<String?>(null) }
    var handle by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var isPublic by remember { mutableStateOf(false) }
    var profileReviewStatus by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<JSONObject?>(null) }; var removing by remember { mutableStateOf<JSONObject?>(null) }
    var pending by remember { mutableStateOf<PublicationOperation?>(null) }; var pendingArchive by remember { mutableStateOf<PreparedPublication?>(null) }; var pendingProfile by remember { mutableStateOf<JSONObject?>(null) }
    var ownerPreview by remember { mutableStateOf<PreparedPublication?>(null) }; var ownerReceipt by remember { mutableStateOf<PublicationReceipt?>(null) }; var ownerUser by remember { mutableStateOf<String?>(null) }; var editingDraft by remember { mutableStateOf<String?>(null) }
    val context=androidx.compose.ui.platform.LocalContext.current
    var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }; var signingIn by remember { mutableStateOf(false) }
    suspend fun checkAccount() { check(loadedUser!=null && loadedUser==account.publicationUserId()) { "Sign in with the account that opened this cookbook." } }
    suspend fun pendingPreview() {
        pending=writer.pending(); pendingArchive=null; pendingProfile=null
        pending?.let { op ->
            val value=withContext(Dispatchers.IO) {
                val file=writer.store.verify(op)
                (if(op.contentType=="application/zip")pendingPublication(file) else null) to (if(op.path=="/v1/me/profile")JSONObject(file.readText()) else null)
            }
            checkAccount(); pendingArchive=value.first; pendingProfile=value.second
        }
    }
    suspend fun loadItems(more: Boolean) {
        checkAccount(); val page=writer.read("/v1/me/recipes"+if(more && cursor!=null)"?cursor=$cursor" else "")
        val rows=page.getJSONArray("items"); require(rows.length()<=20)
        val next=(0 until rows.length()).map(rows::getJSONObject)
        next.forEach { require(publicationUuid(it.getString("id")) && publicationUuid(it.getString("revisionId")) && it.getInt("revision")>0 && it.getString("title").length in 1..160 && it.getString("identityMode") in setOf("named","anonymous") && it.getString("status") in setOf("active","pending_review","unpublished","taken_down")) }
        val nextCursor=if(page.isNull("nextCursor"))null else page.getString("nextCursor"); require(nextCursor==null || publicationUuid(nextCursor)); checkAccount()
        items=if(more)items+next.filter { new -> items.none { it.getString("id")==new.getString("id") } } else next; cursor=nextCursor
    }
    suspend fun reload() {
        checkAccount(); pendingPreview(); val profile=writer.read("/v1/me/profile"); checkAccount()
        handle=if(profile.isNull("handle"))"" else profile.getString("handle"); name=profile.getString("displayName"); isPublic=profile.getBoolean("isPublic"); profileReviewStatus=profile.optString("reviewStatus").takeIf{it.isNotEmpty()}; preview=null; loadItems(false)
    }
    fun run(action: suspend () -> Unit) {
        if(busy)return; focus.clearFocus(); busy=true; message=null
        scope.launch { try { action() } catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException)throw e; message=e.message ?: "Couldn't reconnect. Your saved request remains available."; try { pendingPreview() } catch(_: Exception) { } } finally { busy=false } }
    }
    LaunchedEffect(state.signedIn) {
        loadedUser=account.publicationUserId(); items=emptyList(); cursor=null; handle=""; name=""; isPublic=false; profileReviewStatus=null; preview=null; removing=null; pending=null; pendingArchive=null; pendingProfile=null; ownerPreview=null; ownerReceipt=null; ownerUser=null; editingDraft=null
        if(loadedUser!=null)try { reload() } catch(e: Exception) { message=e.message }
    }
    ModalBottomSheet(sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),onDismissRequest={ if(!busy)onClose() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row { Text("My cookbook",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f)); TextButton(onClick=onClose,enabled=!busy) { Text("Done") } }
            Column(Modifier.weight(1f).verticalScroll(contentScroll),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                if(!state.signedIn) {
                    Text("Your public cookbook and publications belong to your signed-in account. Your private library stays on this device.")
                    Button(onClick={ signingIn=true }) { Text("Sign in") }
                } else {
                    pending?.let { op ->
                        Text(if(op.rejected)"Rejected change" else "Saved interrupted change",style=MaterialTheme.typography.titleMedium)
                        pendingArchive?.let { PublicationContent(it) }; pendingProfile?.let { CookbookProfileSummary(it) }
                        if(pendingArchive==null && pendingProfile==null) {
                            Text(if(op.path.endsWith("/like")) { if(op.method=="POST")"Add your like to this recipe" else "Remove your like from this recipe" } else "Remove this publication from public access")
                            val parts=op.path.split('/'); if(parts.size>=4)TextButton(onClick={ uri.openUri("https://pittech.app/c/"+parts[3]) }) { Text("View affected recipe") }
                        }
                        if(!op.rejected)Button(onClick={ run { checkAccount(); writer.recover(); reload() } },enabled=!busy,modifier=Modifier.testTag("cookbook-recover")) { Text("Retry this exact change") }
                        else TextButton(onClick={ run { checkAccount(); withContext(Dispatchers.IO) { writer.store.discardRejected(checkNotNull(loadedUser)) }; reload() } },enabled=!busy) { Text("Discard rejected change") }
                    }
                    ownerPreview?.let { prepared -> ownerReceipt?.let { receipt ->
                        Text("Private editing preview",style=MaterialTheme.typography.titleLarge)
                        Text("This creates an editable sharing draft on this device. It does not publish or change your meal history.")
                        PublicationContent(prepared)
                        Button(onClick={ run {
                            val user=checkNotNull(ownerUser);check(user==account.publicationUserId())
                            val draft=withContext(Dispatchers.Default) { OwnedPublication.draft(prepared,receipt) };check(user==account.publicationUserId())
                            withContext(Dispatchers.IO) { writer.store.attachEditContext(user,draft.id,draft.context); viewModel.sharing.saveOwnedPublicationDraft(draft) }
                            check(user==account.publicationUserId());ownerPreview=null;ownerReceipt=null;editingDraft=draft.id
                        } },enabled=!busy && pending==null,modifier=Modifier.testTag("cookbook-create-editing-draft")) { Text("Create private editing draft") }
                        TextButton(onClick={ ownerPreview=null;ownerReceipt=null;ownerUser=null },enabled=!busy) { Text("Cancel editing preview") }
                    } }
                    Text("Cookbook identity",style=MaterialTheme.typography.titleLarge)
                    OutlinedTextField(name,{ name=it; preview=null },label={ Text("Display name") },modifier=Modifier.fillMaxWidth().testTag("cookbook-name"))
                    OutlinedTextField(handle,{ handle=it; preview=null },label={ Text("Handle (3–24 letters, numbers or underscores)") },modifier=Modifier.fillMaxWidth().testTag("cookbook-handle"))
                    if(profileReviewStatus=="pending")Text("Identity changes are awaiting review and are not yet public.")
                    Row { Checkbox(isPublic,{ isPublic=it; preview=null },modifier=Modifier.testTag("cookbook-public")); Text("Enable my public cookbook",Modifier.weight(1f)) }
                    Text("Named publications show your display name. Only named publications appear in an enabled public cookbook; anonymous publications stay separate.")
                    Button(onClick={ run { require(handle.isEmpty() || handle.matches(Regex("[A-Za-z0-9_]{3,24}"))) { "Choose a handle with 3–24 letters, numbers or underscores." }; require(name.length<=160 && name.none { it.code<32 || it.code==127 } && (!isPublic || handle.isNotEmpty() && name.isNotBlank())) { "Choose a display name and handle before enabling your public cookbook." }; preview=JSONObject().put("handle",handle.takeIf { it.isNotEmpty() }?.lowercase(java.util.Locale.ROOT) ?: JSONObject.NULL).put("displayName",name.trim()).put("isPublic",isPublic) } },enabled=!busy && pending==null,modifier=Modifier.testTag("cookbook-preview")) { Text("Preview cookbook changes") }
                    preview?.let { profile -> CookbookProfileSummary(profile)
                        Button(onClick={ run { checkAccount(); writer.json("PUT","/v1/me/profile",profile); preview=null; reload() } },enabled=!busy && pending==null,modifier=Modifier.testTag("cookbook-save")) { Text("Save previewed changes") }
                    }
                    Text("My publications",style=MaterialTheme.typography.titleLarge)
                    Text("Removing a publication hides its public link. Other cooks keep independent copies they already saved.")
                    items.forEach { item -> Card { Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(item.getString("title"),style=MaterialTheme.typography.titleMedium)
                        val status=item.getString("status")
                        Text(if(status=="active")"Public · revision ${item.getInt("revision")} · ${item.getString("identityMode")}" else if(status=="pending_review")"Submitted for review · not public" else if(status=="unpublished")"Removed from public access" else "Unavailable after moderation")
                        if(status in setOf("active","pending_review","unpublished"))TextButton(onClick={ run {
                            checkAccount();val user=checkNotNull(loadedUser);val receipt=PublicationReceipt(item.getString("id"),item.getString("revisionId"),item.getInt("revision"),"https://pittech.app/c/"+item.getString("id"))
                            val file=java.io.File(context.cacheDir,"publication-edit-"+java.util.UUID.randomUUID()+".zip")
                            try {
                                writer.ownedArchive(receipt,file);val prepared=withContext(Dispatchers.IO) { pendingPublication(file) }
                                check(user==account.publicationUserId() && user==loadedUser);ownerPreview=prepared;ownerReceipt=receipt;ownerUser=user
                                contentScroll.scrollTo(0)
                            } finally { file.delete() }
                        } },enabled=!busy && pending==null,modifier=Modifier.testTag("cookbook-edit-"+item.getString("id"))) { Text("Preview an editable private copy") }
                        if(status=="active")TextButton(onClick={ uri.openUri("https://pittech.app/c/"+item.getString("id")) }) { Text("Open publication") }
                        if(status in setOf("active","pending_review"))TextButton(onClick={ removing=item },enabled=!busy && pending==null) { Text(if(status=="pending_review")"Withdraw review request" else "Remove from public access") }
                    } } }
                    if(cursor!=null)Button(onClick={ run { loadItems(true) } },enabled=!busy) { Text("More publications") }
                    Button(onClick={ run { reload() } },enabled=!busy) { Text("Refresh cookbook") }
                }
                if(busy)CircularProgressIndicator(); message?.let { Text(it,Modifier.testTag("cookbook-message")) }; Spacer(Modifier.height(24.dp))
            }
        }
    }
    editingDraft?.let { draft -> SharingDraftEditor(draft,viewModel,account,writer,publishingEnabled=account.enabled) { editingDraft=null } }
    removing?.let { item -> AlertDialog(onDismissRequest={ removing=null },title={ Text("Remove this publication from public access?") },text={ Text("Other cooks keep independent copies they already saved.") },confirmButton={ TextButton(onClick={ removing=null; run { checkAccount(); writer.json("DELETE","/v1/recipes/"+item.getString("id"),expected=item.getString("revisionId")); reload() } },modifier=Modifier.testTag("cookbook-unpublish-confirm")) { Text("Remove publication") } },dismissButton={ TextButton(onClick={ removing=null }) { Text("Cancel") } }) }
    if(signingIn)AlertDialog(onDismissRequest={ signingIn=false },title={ Text("Account") },text={ AccountContent(account) },confirmButton={ TextButton(onClick={ signingIn=false }) { Text("Return to cookbook") } })
}
@Composable private fun CookbookProfileSummary(profile: JSONObject) {
    Card { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(profile.optString("reviewStatus")=="pending")Text("Identity changes are awaiting review and are not yet public.")
        Text("Public display name · "+profile.optString("displayName").ifBlank { "a PitTech cook" },style=MaterialTheme.typography.titleMedium)
        Text(if(profile.getBoolean("isPublic"))"Public cookbook: https://pittech.app/p/"+profile.optString("handle") else "Public cookbook disabled. Your named recipe credit remains visible.")
    } }
}
