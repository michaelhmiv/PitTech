package com.pittech.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.data.AccountManager
import com.pittech.data.AccountPresentation
import com.pittech.data.ActivityGoogleSignInHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PL-14 (#172): Account is a Night Service sheet, not a dialog — a full
 * surface whose sections each carry their status in the header (DD-2).
 * The hosted content is unchanged: hosted embeds (and tests) compose
 * [AccountContent] directly and get the same sectioned layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AccountSheet(account: AccountManager, sync: com.pittech.data.PrivateSyncCoordinator? = null, mcp: com.pittech.data.McpConnectionsManager? = null, device: com.pittech.data.McpDeviceActionsManager? = null, subscription: com.pittech.billing.SubscriptionManager? = null) {
    val showing by account.showing.collectAsStateWithLifecycle()
    if(!showing)return
    Dialog(onDismissRequest={account.close()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        Scaffold(
            topBar={TopAppBar(title={Text("Account")},navigationIcon={IconButton(onClick={account.close()}){Icon(Icons.Filled.Close,"Close account")}})},
        ){padding->
            Box(Modifier.padding(padding).fillMaxSize()){AccountContent(account, sync, mcp, device, subscription)}
        }
    }
}

/** One PL-14 section: a Night Service card whose header always carries the
 * section's current status (DD-2), so a collapsed-away state never hides
 * whether the section needs attention. Sections stay expanded — collapsing
 * them would bury controls that used to be one scroll away. */
@Composable private fun AccountPanel(title: String, status: String, tag: String, content: @Composable () -> Unit) {
    Card(
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),
        shape=RoundedCornerShape(16.dp),
        modifier=Modifier.fillMaxWidth().testTag(tag),
    ){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
                Text(title,style=MaterialTheme.typography.titleMedium)
                Text(status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("$tag-status"))
            }
            content()
        }
    }
}

@Composable internal fun AccountContent(account: AccountManager, sync: com.pittech.data.PrivateSyncCoordinator? = null, mcp: com.pittech.data.McpConnectionsManager? = null, device: com.pittech.data.McpDeviceActionsManager? = null, subscription: com.pittech.billing.SubscriptionManager? = null) {
    val state by account.presentation.collectAsStateWithLifecycle()
    val syncState by (sync?.presentation ?: remember{ kotlinx.coroutines.flow.MutableStateFlow(com.pittech.data.PrivateSyncPresentation()) }).collectAsStateWithLifecycle()
    val mcpState by (mcp?.presentation ?: remember{ kotlinx.coroutines.flow.MutableStateFlow(com.pittech.data.McpConnectionsPresentation(available=false)) }).collectAsStateWithLifecycle()
    val deviceState by (device?.presentation ?: remember{ kotlinx.coroutines.flow.MutableStateFlow(com.pittech.data.McpDevicePresentation(available=false)) }).collectAsStateWithLifecycle()
    val subState by (subscription?.presentation ?: remember{ kotlinx.coroutines.flow.MutableStateFlow(com.pittech.billing.SubscriptionPresentation()) }).collectAsStateWithLifecycle()
    val aiGate = if(subscription==null) com.pittech.data.McpProGate.ENTITLED else com.pittech.data.mcpProGate(subState.entitlement)
    var confirm by remember{mutableStateOf(false)}
    var reviewingDeletion by remember{mutableStateOf(false)}
    var reviewingJournal by remember{mutableStateOf(false)}
    LaunchedEffect(account,state.challengeEmail){while(true){delay(1000);account.tick()}}
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Your cookbook works without an account. Signing in doesn't publish or upload your local records.")
        if(!account.enabled)Text("Accounts arrive with community recipes and private sync.",Modifier.testTag("account-unavailable"))
        // Session — sign-in state, the deletion-in-progress flow, sign out.
        AccountPanel(
            title="Session",
            status=when{
                state.deletion!=null->"Deletion in progress"
                state.signedIn->state.accountEmail?.let{"Signed in · $it"}?:"Signed in"
                !account.enabled->"Not available on this build"
                else->"Signed out"
            },
            tag="account-panel-session",
        ){
            if(state.deletion!=null){
                val deletion=state.deletion!!
                Text("Account deletion",style=MaterialTheme.typography.titleMedium)
                Text(deletion.receipt?.description?:"The deletion response was not confirmed. Recover the request before retrying.",Modifier.testTag("account-deletion-status"))
                Text("Your local journal stays available on this device. Deletion does not stop your local cooking.")
                if(deletion.cleanupUserId!=null)Button(onClick={account.retryDeviceCleanup()},enabled=!state.busy){Text("Retry device account cleanup")}
                if(deletion.receipt?.state=="complete")Button(onClick={account.dismissCompletedDeletion()},enabled=!state.busy){Text("Close completed request")}
                else{
                    if(deletion.receipt!=null)Button(onClick={account.checkDeletion()},enabled=!state.busy,modifier=Modifier.testTag("account-deletion-check")){Text("Check deletion progress")}
                    Button(onClick={account.recoverDeletion()},enabled=!state.busy,modifier=Modifier.testTag("account-deletion-recover")){Text("Recover deletion request")}
                    if(deletion.receipt==null && state.signedIn)TextButton(onClick={reviewingDeletion=true},enabled=!state.busy,modifier=Modifier.testTag("account-deletion-retry")){Text("Review and retry the same request")}
                }
                if(deletion.receipt==null && !state.signedIn){Text("Verify the original account email to retry this device’s saved request. Another account cannot take over the request.");AccountSignIn(account,state)}
            }else if(state.signedIn){
                Text("Signed in",Modifier.testTag("account-signed-in"),style=MaterialTheme.typography.titleMedium)
                state.accountEmail?.let{Text("Email sign-in: $it",Modifier.testTag("account-auth-email"))}
                if(state.googleLinked)Text("Google sign-in is linked.",Modifier.testTag("account-auth-google"))
                else if(account.googleSignInAvailable){(LocalContext.current as? Activity)?.let{activity->Button(onClick={account.attachGoogle(ActivityGoogleSignInHost(activity))},enabled=!state.busy,modifier=Modifier.testTag("account-google-attach")){Text("Add Google sign-in")}}}
                Button(onClick={account.checkSession()},enabled=!state.busy){Text("Check connection")}
                TextButton(onClick={confirm=true},enabled=!state.busy){Text("Sign out")}
            }else AccountSignIn(account,state)
        }
        // Sync — Slice 7's status row lives here, under a header that
        // carries the same headline; signing in surfaces the first sync.
        if(sync!=null)AccountPanel(
            title="Sync",
            status=SyncStatusCopy.row(syncState.status, syncState.detail, syncState.lastSyncedAt, syncState.conflicts.size, state.signedIn, sync.enabled).headline,
            tag="account-panel-sync",
        ){ SyncSection(sync,state.signedIn) }
        // Connect your AI — Slice 8's gating, approval preview and token
        // hard-stop are preserved exactly; signed out, the section says so
        // in its header and body instead of vanishing.
        AccountPanel(
            title="Connect your AI",
            status=when{
                mcp==null->"Not available"
                !state.signedIn->"Sign in to connect"
                !mcpState.available->"Not available yet"
                aiGate==com.pittech.data.McpProGate.NEEDS_PRO->"Needs PitTech Pro"
                aiGate==com.pittech.data.McpProGate.CHECKING->"Checking Pro status"
                mcpState.grants.count{it.active}>0->"${mcpState.grants.count{it.active}} connection${if(mcpState.grants.count{it.active}==1)"" else "s"}"
                else->"No connections"
            },
            tag="account-panel-ai",
        ){
            if(mcp==null)Text("AI connections aren't available yet.",Modifier.testTag("mcp-unavailable"))
            else if(!state.signedIn)Text("Sign in above to connect an AI assistant like ChatGPT or Muse to your own meals, recipes and logs.",Modifier.testTag("account-ai-signin-note"),style=MaterialTheme.typography.bodySmall)
            else McpConnectionsSection(mcp, subscription)
        }
        // Device actions — same treatment as Connect your AI.
        AccountPanel(
            title="Device actions",
            status=when{
                device==null->"Not available"
                !state.signedIn->"Sign in to connect"
                !deviceState.available->"Not available yet"
                deviceState.registered->"On — this phone accepts requests"
                else->"Off"
            },
            tag="account-panel-device",
        ){
            if(device==null)Text("Device actions aren't available yet.",Modifier.testTag("mcp-device-unavailable"))
            else if(!state.signedIn)Text("Sign in above to let an assistant ask this phone to do small things in the app — never to control a grill.",Modifier.testTag("account-device-signin-note"),style=MaterialTheme.typography.bodySmall)
            else McpDeviceActionsSection(device)
        }
        // Danger zone — the two destructive actions, named in the header.
        AccountPanel(
            title="Danger zone",
            status="Delete account · Remove local journal",
            tag="account-panel-danger",
        ){
            if(state.signedIn && state.deletion==null){
                Text("Delete account and server data",style=MaterialTheme.typography.titleMedium)
                Text("Deletes your account identity, profile, sessions, likes, private server sync and original public publications. Your local journal stays on this device unless you remove it below.",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={reviewingDeletion=true},enabled=!state.busy,modifier=Modifier.testTag("account-delete")){Text("Delete account and server data")}
            }
            Text("Remove local journal",style=MaterialTheme.typography.titleMedium)
            Text("Removes this device's cooks, recipes, photos, notes, drafts, reminders and timers in the journal. This does not delete the server account; credentials, deletion records and recovery, and community block and report device state stay untouched.")
            TextButton(onClick={reviewingJournal=true},enabled=!state.busy,modifier=Modifier.testTag("local-journal-remove")){Text("Remove local journal")}
        }
        if(state.busy)CircularProgressIndicator()
        state.message?.let{Text(it,Modifier.testTag("account-message"))}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Sign out on this device?")},text={Text("Your local cooks, recipes and photos stay on this device. Signing out doesn't delete your account.")},confirmButton={TextButton(onClick={confirm=false;account.signOut()},modifier=Modifier.testTag("account-confirm-signout")){Text("Sign out")}},dismissButton={TextButton(onClick={confirm=false}){Text("Cancel")}})
    if(reviewingDeletion)AccountDeletionConfirmation(onCancel={reviewingDeletion=false},onConfirm={reviewingDeletion=false;account.requestDeletion()})
    if(reviewingJournal)LocalJournalRemovalConfirmation(onCancel={reviewingJournal=false},onConfirm={reviewingJournal=false;account.removeLocalJournal()})
}

@Composable private fun McpConnectionsSection(mcp: com.pittech.data.McpConnectionsManager, subscription: com.pittech.billing.SubscriptionManager? = null) {
    val state by mcp.presentation.collectAsStateWithLifecycle()
    // Slice 8 (PL-14): the Pro gate reads the existing subscription state.
    // Hosts that embed the account content without a subscription manager
    // keep the section ungated-by-unknown rather than inventing a state.
    val subState by (subscription?.presentation ?: remember{ kotlinx.coroutines.flow.MutableStateFlow(com.pittech.billing.SubscriptionPresentation()) }).collectAsStateWithLifecycle()
    val gate = if(subscription==null) com.pittech.data.McpProGate.ENTITLED else com.pittech.data.mcpProGate(subState.entitlement)
    val scope = rememberCoroutineScope()
    var creating by remember{mutableStateOf(false)}
    var confirmRevoke by remember{mutableStateOf<String?>(null)}
    var confirmRevokeAll by remember{mutableStateOf(false)}
    var code by remember{mutableStateOf("")}
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    LaunchedEffect(Unit){mcp.refresh()}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Let an AI assistant like ChatGPT or Muse help with your own meals, recipes and logs. Connections use your PitTech Pro and can be disconnected here any time. An assistant can never publish, control a grill, or change your account or billing.",style=MaterialTheme.typography.bodySmall)
        if(!state.available)Text("AI connections aren't available yet.",Modifier.testTag("mcp-unavailable"))
        else{
            state.grants.forEach{grant->
                Column(Modifier.testTag("mcp-grant-${grant.id}"),verticalArrangement=Arrangement.spacedBy(2.dp)){
                    Text(grant.nickname,style=MaterialTheme.typography.titleSmall)
                    Text((grant.clientId?.let{"Connected app"} ?: "Personal access token")+" - "+grant.status(System.currentTimeMillis()),style=MaterialTheme.typography.bodySmall)
                    Text(grant.scopes.joinToString(", "){com.pittech.data.McpScopeCatalog.label(it)},style=MaterialTheme.typography.bodySmall)
                    grant.lastUsedAt?.let{Text("Last used ${java.time.Instant.ofEpochMilli(it)}",style=MaterialTheme.typography.bodySmall)}
                    if(grant.active)TextButton(onClick={confirmRevoke=grant.id},enabled=!state.busy,modifier=Modifier.testTag("mcp-revoke-${grant.id}")){Text("Disconnect")}
                }
            }
            if(state.grants.none{it.active})Text("No AI connections yet.",style=MaterialTheme.typography.bodySmall)
            if(state.grants.count{it.active}>1)TextButton(onClick={confirmRevokeAll=true},enabled=!state.busy,modifier=Modifier.testTag("mcp-revoke-all")){Text("Disconnect all")}
            when(gate){
                com.pittech.data.McpProGate.CHECKING->Column(Modifier.testTag("mcp-pro-checking"),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Text("Checking PitTech Pro status…",style=MaterialTheme.typography.titleSmall)
                    Text("Creating or approving a connection needs your verified Pro status first. Your existing connections are unchanged.",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={scope.launch{subscription?.reconcile()}},modifier=Modifier.testTag("mcp-pro-check")){Text("Check again")}
                }
                com.pittech.data.McpProGate.NEEDS_PRO->Column(Modifier.testTag("mcp-pro-gate"),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Text("AI connections need PitTech Pro",style=MaterialTheme.typography.titleSmall)
                    Text("Creating or approving a connection needs an active PitTech Pro subscription — without it, the assistant can't use the connection, so PitTech doesn't create one that would only fail later. Your existing connections stay listed above and can be disconnected any time.",style=MaterialTheme.typography.bodySmall)
                    Button(onClick={subscription?.openPro(com.pittech.billing.ProPlacement.MORE)},enabled=subscription!=null,modifier=Modifier.heightIn(min=48.dp).testTag("mcp-pro-see")){Text("See PitTech Pro")}
                }
                com.pittech.data.McpProGate.ENTITLED->{
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(onClick={creating=true},enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp).testTag("mcp-new")){Text("New connection")}
                    }
                    Text("Approve a connection",style=MaterialTheme.typography.titleSmall)
                    Text("If an assistant shows you a code in your browser, enter it here. PitTech shows you exactly what the code connects before anything is approved.",style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(code,{code=it;state.approvalPreview?.let{preview->if(preview.code!=it.trim().uppercase())mcp.dismissApprovalPreview()}},label={Text("Connection code")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("mcp-approve-code"))
                    val preview=state.approvalPreview
                    if(preview==null){
                        Button(onClick={mcp.reviewCode(code)},enabled=!state.busy && code.isNotBlank(),modifier=Modifier.heightIn(min=48.dp).testTag("mcp-review-code")){Text("Review connection")}
                    }else{
                        // Slice 8 (PL-16 / DD-9): the confirmation step. Approve
                        // does not exist until the client and its full scope
                        // list have been shown for this exact code.
                        Column(Modifier.testTag("mcp-approval-preview"),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text("Connect ${preview.clientName}?",style=MaterialTheme.typography.titleSmall)
                            Text("${preview.clientName} will be able to:",style=MaterialTheme.typography.bodySmall)
                            preview.scopes.forEach{Text("• "+com.pittech.data.McpScopeCatalog.label(it),style=MaterialTheme.typography.bodySmall)}
                            Text("PitTech Pro: active on this account.",style=MaterialTheme.typography.bodySmall)
                            Text("This code expires ${java.time.Instant.ofEpochMilli(preview.expiresAt)}. Nothing is connected until you approve.",style=MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                Button(onClick={mcp.approve(preview.code);code=""},enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp).testTag("mcp-approve")){Text("Approve")}
                                TextButton(onClick={mcp.dismissApprovalPreview()},enabled=!state.busy,modifier=Modifier.testTag("mcp-approve-cancel")){Text("Not now")}
                            }
                        }
                    }
                }
            }
            if(state.busy)CircularProgressIndicator()
            state.message?.let{Text(it,Modifier.testTag("mcp-message"))}
        }
    }
    // Slice 8 (PL-16 / DD-9): the show-once token is a hard-stop dialog,
    // presented the moment creation completes — not a section that can
    // materialise off-screen in the account scroll. Copy confirms itself;
    // Done is the only exit, and dismissing by scrim/back does nothing
    // because the token is never shown again.
    state.createdToken?.let{token->
        var copied by remember(token){mutableStateOf(false)}
        AlertDialog(onDismissRequest={},title={Text("Your connection token")},text={
            Column(Modifier.testTag("mcp-created-token"),verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text("For ${state.createdNickname ?: "the new connection"}",style=MaterialTheme.typography.titleSmall)
                Text("Copy it into the assistant now. It is shown only once and is not saved on this device.",style=MaterialTheme.typography.bodySmall)
                Text(token,Modifier.testTag("mcp-token-value"))
            }
        },confirmButton={Button(onClick={clipboard.setText(androidx.compose.ui.text.AnnotatedString(token));copied=true},modifier=Modifier.testTag("mcp-copy-token")){Text(if(copied)"Copied" else "Copy token")}},dismissButton={TextButton(onClick={mcp.dismissCreatedToken()},modifier=Modifier.testTag("mcp-token-done")){Text("Done")}})
    }
    if(creating)McpCreateDialog(mcp=mcp,onClose={creating=false})
    confirmRevoke?.let{id->AlertDialog(onDismissRequest={confirmRevoke=null},title={Text("Disconnect this connection?")},text={Text("The assistant using it loses access immediately. Your library is unchanged.")},confirmButton={TextButton(onClick={confirmRevoke=null;mcp.revoke(id)},modifier=Modifier.testTag("mcp-confirm-revoke")){Text("Disconnect")}},dismissButton={TextButton(onClick={confirmRevoke=null}){Text("Cancel")}})}
    if(confirmRevokeAll)AlertDialog(onDismissRequest={confirmRevokeAll=false},title={Text("Disconnect all connections?")},text={Text("Every assistant using a connection loses access immediately. Your library is unchanged.")},confirmButton={TextButton(onClick={confirmRevokeAll=false;mcp.revokeAll()},modifier=Modifier.testTag("mcp-confirm-revoke-all")){Text("Disconnect all")}},dismissButton={TextButton(onClick={confirmRevokeAll=false}){Text("Cancel")}})
}
@Composable private fun McpDeviceActionsSection(device: com.pittech.data.McpDeviceActionsManager) {
    val state by device.presentation.collectAsStateWithLifecycle()
    var confirmingUnregister by remember{mutableStateOf(false)}
    LaunchedEffect(Unit){device.refresh()}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("An assistant can ask this phone to do small things in the app — never to control a grill. Requests wait here until you open PitTech; nothing runs in the background and nothing counts as done until this phone reports it. Choose which kinds of requests this phone accepts.",style=MaterialTheme.typography.bodySmall)
        if(!state.available)Text("Device actions aren't available yet.",Modifier.testTag("mcp-device-unavailable"))
        else{
            com.pittech.data.MCP_DEVICE_CLASSES.forEach{cls->
                Row(verticalAlignment=Alignment.CenterVertically){
                    Checkbox(state.classes.contains(cls),{ value -> device.setClass(cls,value) },enabled=!state.busy,modifier=Modifier.testTag("mcp-device-class-$cls"))
                    Text(com.pittech.data.mcpDeviceClassLabel(cls),Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick={device.checkNow()},enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp).testTag("mcp-device-check")){Text("Check for requests")}
                if(state.registered)TextButton(onClick={confirmingUnregister=true},enabled=!state.busy,modifier=Modifier.testTag("mcp-device-unregister")){Text("Stop taking requests")}
            }
            state.lastCheckSummary?.let{Text(it,Modifier.testTag("mcp-device-last-check"),style=MaterialTheme.typography.bodySmall)}
            if(state.busy)CircularProgressIndicator()
            state.message?.let{Text(it,Modifier.testTag("mcp-device-message"))}
        }
    }
    if(confirmingUnregister)AlertDialog(onDismissRequest={confirmingUnregister=false},title={Text("Stop taking requests?")},text={Text("This phone stops accepting assistant requests, and any waiting requests for it are canceled. You can turn a class back on any time.")},confirmButton={TextButton(onClick={confirmingUnregister=false;device.unregister()},modifier=Modifier.testTag("mcp-device-confirm-unregister")){Text("Stop")}},dismissButton={TextButton(onClick={confirmingUnregister=false}){Text("Cancel")}})
}
@Composable private fun McpCreateDialog(mcp: com.pittech.data.McpConnectionsManager, onClose: () -> Unit) {
    val state by mcp.presentation.collectAsStateWithLifecycle()
    var nickname by remember{mutableStateOf("ChatGPT")}
    var selected by remember{mutableStateOf(com.pittech.data.McpScopeCatalog.defaultSelected)}
    var days by remember{mutableStateOf(90)}
    // Slice 8 (PL-16): the form stays open until the create call resolves.
    // Success hands off to the hard-stop token dialog; failure keeps the
    // filled form and says what happened, here — not behind the dialog.
    // Keyed on the token, not the busy edge: the manager's final publish
    // carries busy=false and the token together, and StateFlow conflation
    // can eat a bare busy true→false transition.
    var submitted by remember{mutableStateOf(false)}
    LaunchedEffect(state.createdToken, state.busy){if(submitted && !state.busy && state.createdToken!=null)onClose()}
    AlertDialog(onDismissRequest=onClose,title={Text("New AI connection")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(nickname,{nickname=it},label={Text("Name this connection")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("mcp-nickname"))
            Text("What it can do",style=MaterialTheme.typography.titleSmall)
            com.pittech.data.McpScopeCatalog.all.forEach{entry->
                Row(verticalAlignment=Alignment.CenterVertically){
                    Checkbox(selected.contains(entry.id),{checked->selected=if(checked)selected+entry.id else selected-entry.id},modifier=Modifier.testTag("mcp-scope-${entry.id}"))
                    Column{Text(entry.label);Text(entry.detail,style=MaterialTheme.typography.bodySmall)}
                }
            }
            Text("Disconnect automatically after",style=MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf(30,90,365).forEach{option->
                    Row(verticalAlignment=Alignment.CenterVertically){
                        androidx.compose.material3.RadioButton(days==option,{days=option},modifier=Modifier.testTag("mcp-days-$option"))
                        Text("$option days")
                    }
                }
            }
            Text("You can disconnect sooner any time. Nothing here can publish, control a grill, or change billing.",style=MaterialTheme.typography.bodySmall)
            if(submitted && state.busy)CircularProgressIndicator()
            if(submitted && !state.busy && state.createdToken==null)Text(state.message ?: "Couldn't create the connection. Check your connection and try again.",Modifier.testTag("mcp-create-error"),style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick={submitted=true;mcp.create(nickname,selected,days)},enabled=nickname.isNotBlank() && selected.isNotEmpty() && !state.busy,modifier=Modifier.testTag("mcp-create-submit")){Text(if(submitted && state.busy)"Creating…" else "Create connection")}},dismissButton={TextButton(onClick=onClose){Text("Cancel")}})
}
@Composable private fun SyncSection(sync: com.pittech.data.PrivateSyncCoordinator, signedIn: Boolean) {
    val state by sync.presentation.collectAsStateWithLifecycle()
    var reviewing by remember{mutableStateOf(false)}
    val scope = rememberCoroutineScope()
    LaunchedEffect(signedIn){sync.refresh()}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Sync keeps an encrypted backup of this journal for signed-in cooks. It never publishes anything.",style=MaterialTheme.typography.bodySmall)
        // PL-14 (#172): signing in surfaces the first sync as its own card,
        // so turning private sync on is discoverable instead of depending on
        // knowing that "Sync now" below is also the on switch. The card is
        // the same one action — the coordinator's explicit sync — and it
        // leaves once a first sync exists or conflicts need review instead.
        if (signedIn && sync.enabled && state.lastSyncedAt <= 0 && state.conflicts.isEmpty()) {
            Card(
                colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer),
                shape=RoundedCornerShape(12.dp),
                modifier=Modifier.fillMaxWidth().testTag("sync-turn-on-card"),
            ){
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text("Turn on private sync",style=MaterialTheme.typography.titleSmall)
                    Text("Make the first encrypted backup of this journal now. After that, you can keep it in sync automatically while the app is open.",style=MaterialTheme.typography.bodySmall)
                    Button(onClick={scope.launch{sync.syncNow()}},enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp).testTag("sync-turn-on")){Text("Turn on private sync")}
                }
            }
        }
        // Slice 7 (PL-10, DD-8): the sync status row always carries state — what
        // happened, what is still safe, and the recovery action — instead of the
        // coordinator's bare status string. First sync is an Account action:
        // "Sync now" below is the same button in every state, and the row says
        // what it will do before the first backup exists.
        val row = SyncStatusCopy.row(state.status, state.detail, state.lastSyncedAt, state.conflicts.size, signedIn, sync.enabled)
        Column(Modifier.testTag("sync-status-row"),verticalArrangement=Arrangement.spacedBy(2.dp)){
            Text(row.headline,Modifier.testTag("sync-status"),style=MaterialTheme.typography.titleSmall)
            Text(row.body,Modifier.testTag("sync-status-detail"),style=MaterialTheme.typography.bodySmall)
        }
        // The detail is already the row's body in the states that use it; only
        // repeat it as the message line where the row's body is the fixed copy.
        val detailInRow = state.conflicts.isEmpty() && sync.enabled && signedIn &&
            (state.status == "Waiting for connection" || state.status == "Retry needed" || state.lastSyncedAt > 0)
        if (!detailInRow) state.detail?.let{Text(it,Modifier.testTag("sync-message"),style=MaterialTheme.typography.bodySmall)}
        if (state.lastSyncedAt > 0 && signedIn && sync.enabled) {
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(state.automatic,{ value -> scope.launch { sync.setAutomatic(value) } },enabled=!state.busy,modifier=Modifier.testTag("sync-automatic"))
                Text("Keep this library in sync while the app is open",Modifier.weight(1f))
            }
            Text("Checks when you return to the app and after saved changes. Offline changes wait for a connection.",style=MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button(onClick={scope.launch{sync.syncNow()}},enabled=!state.busy && signedIn && sync.enabled,modifier=Modifier.heightIn(min=48.dp).testTag("sync-now")){Text("Sync now")}
            if(state.conflicts.isNotEmpty())Button(onClick={reviewing=true},enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp).testTag("sync-conflicts")){Text("Review conflicts (${state.conflicts.size})")}
        }
        if(state.busy)Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){CircularProgressIndicator();Text("Syncing…",Modifier.testTag("sync-busy-label"))}
    }
    if(reviewing)SyncConflictDialog(sync,state.conflicts,state.busy,onClose={reviewing=false})
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SyncConflictDialog(sync: com.pittech.data.PrivateSyncCoordinator, conflicts: List<com.pittech.data.PrivateSyncConflictView>, busy: Boolean, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest=onClose,title={Text("Review synced edits")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Two devices changed the same entry. Your journal keeps the current version until you choose.")
            // Slice 7 (PL-10): the consequence is computed from the actual pending
            // set, never a generic warning.
            Text(SyncStatusCopy.conflictConsequence(conflicts.size),Modifier.testTag("sync-conflict-consequence"),style=MaterialTheme.typography.bodySmall)
            conflicts.forEach { conflict ->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Text(conflict.reason,style=MaterialTheme.typography.titleSmall)
                    Text("Yours: ${conflict.mineSummary}")
                    Text("Other device: ${conflict.theirsSummary}")
                    Text(SyncStatusCopy.CONFLICT_ITEM_NOTE,style=MaterialTheme.typography.bodySmall)
                    // FlowRow: the three resolution buttons must all stay reachable on narrow
                    // screens. A fixed Row pushed "Keep both" off-screen horizontally, where
                    // taps landed outside the dialog and the resolution never fired.
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(onClick={scope.launch{sync.resolve(conflict.id,"mine")}},enabled=!busy,modifier=Modifier.testTag("sync-keep-mine-${conflict.id}")){Text("Keep mine")}
                        Button(onClick={scope.launch{sync.resolve(conflict.id,"theirs")}},enabled=!busy,modifier=Modifier.testTag("sync-keep-theirs-${conflict.id}")){Text("Use theirs")}
                        if(conflict.canKeepBoth)Button(onClick={scope.launch{sync.resolve(conflict.id,"both")}},enabled=!busy,modifier=Modifier.testTag("sync-keep-both-${conflict.id}")){Text("Keep both")}
                    }
                }
            }
        }
    },confirmButton={TextButton(onClick=onClose){Text("Done")}})
}
@Composable private fun AccountSignIn(account:AccountManager,state:AccountPresentation){
    var email by remember{mutableStateOf("")};var code by remember{mutableStateOf("")}
    val keyboard=LocalSoftwareKeyboardController.current
    OutlinedTextField(email,{email=it},label={Text("Email address")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth().testTag("account-email"))
    Button(onClick={keyboard?.hide();account.start(email)},enabled=!state.busy && email.isNotEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("account-start")){Text("Send sign-in email")}
    if(account.googleSignInAvailable){(LocalContext.current as? Activity)?.let{activity->Button(onClick={keyboard?.hide();account.signInWithGoogle(ActivityGoogleSignInHost(activity))},enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("account-google-signin")){Text("Continue with Google")}}}
    state.challengeEmail?.let{address->
        Text("Enter the six-digit code sent to $address. You can open the email on another device.")
        OutlinedTextField(code,{code=it},label={Text("Six-digit code")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth().testTag("account-code"))
        Button(onClick={keyboard?.hide();account.verify(code);code=""},enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("account-verify")){Text(if(state.interruptedVerification)"Retry sign-in" else if(state.pendingLink)"Confirm email link" else "Verify code")}
        TextButton(onClick={account.start(address,resend=true)},enabled=!state.busy && state.canResend,modifier=Modifier.testTag("account-resend")){Text("Send a new code")}
        Text("Codes expire after 15 minutes. Wait at least a minute before requesting another.",style=MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun AccountDeletionConfirmation(onCancel:()->Unit,onConfirm:()->Unit){
    var acknowledged by remember{mutableStateOf(false)};var typed by remember{mutableStateOf("")}
    val uri=LocalUriHandler.current;val keyboard=LocalSoftwareKeyboardController.current
    AlertDialog(onDismissRequest=onCancel,title={Text("Delete account")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Delete your account identity, profile, sessions, likes, private server sync, original public publications and original media.")
        Text("Other cooks keep independently owned copies and variations they already saved. Those copies show a removed source without your identity.")
        Text("This request signs you out immediately. Cleanup can take time; keep this device’s recovery record until it completes.")
        Text("Local journal",style=MaterialTheme.typography.titleMedium)
        Text("Your journal stays on this device, including an active local cook. Keeping it does not retain your server account data. Local data removal is a separate choice.")
        Text("Google Play manages subscriptions separately. You can delete your account immediately without changing billing first.")
        TextButton(onClick={uri.openUri("https://play.google.com/store/account/subscriptions?package=com.pittech")}){Text("Manage Google Play subscriptions")}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(acknowledged,{acknowledged=it},modifier=Modifier.testTag("account-deletion-acknowledge"));Text("I understand what is deleted and what other cooks keep")}
        OutlinedTextField(typed,{typed=it},label={Text("Type DELETE")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("account-deletion-confirmation"))
    }},confirmButton={TextButton(onClick={keyboard?.hide();onConfirm()},enabled=acknowledged && typed=="DELETE",modifier=Modifier.testTag("account-deletion-submit")){Text("Delete account and server data")}},dismissButton={TextButton(onClick=onCancel){Text("Cancel")}})
}
@Composable private fun LocalJournalRemovalConfirmation(onCancel:()->Unit,onConfirm:()->Unit){
    var acknowledged by remember{mutableStateOf(false)};var typed by remember{mutableStateOf("")}
    val keyboard=LocalSoftwareKeyboardController.current
    AlertDialog(onDismissRequest=onCancel,title={Text("Remove local journal")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Removes this device's cooks, recipes, photos, notes, drafts, reminders and timers in the journal. This cannot be undone.")
        Text("Finish or stop any active cook and its recording first. Removal is refused while a cook is active or a recording is in progress.")
        Text("This does not delete the server account. Signing in again will not bring the journal back. Credentials, deletion records and recovery, and community block and report device state stay untouched.")
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(acknowledged,{acknowledged=it},modifier=Modifier.testTag("local-journal-acknowledge"));Text("I understand the journal on this device is removed and my account stays")}
        OutlinedTextField(typed,{typed=it},label={Text("Type REMOVE")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("local-journal-confirmation"))
    }},confirmButton={TextButton(onClick={keyboard?.hide();onConfirm()},enabled=acknowledged && typed=="REMOVE",modifier=Modifier.testTag("local-journal-submit")){Text("Remove local journal")}},dismissButton={TextButton(onClick=onCancel){Text("Cancel")}})
}
