package com.pittech.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@Composable internal fun AccountDialog(account: AccountManager, sync: com.pittech.data.PrivateSyncCoordinator? = null) {
    val showing by account.showing.collectAsStateWithLifecycle()
    if(!showing)return
    AlertDialog(onDismissRequest={account.close()},title={Text("Account")},text={AccountContent(account, sync)},confirmButton={TextButton(onClick={account.close()}){Text("Done")}})
}
@Composable internal fun AccountContent(account: AccountManager, sync: com.pittech.data.PrivateSyncCoordinator? = null) {
    val state by account.presentation.collectAsStateWithLifecycle()
    var confirm by remember{mutableStateOf(false)}
    var reviewingDeletion by remember{mutableStateOf(false)}
    var reviewingJournal by remember{mutableStateOf(false)}
    LaunchedEffect(account,state.challengeEmail){while(true){delay(1000);account.tick()}}
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Your cookbook works without an account. Signing in doesn't publish or upload your local records.")
        if(!account.enabled)Text("Accounts arrive with community recipes and private sync.",Modifier.testTag("account-unavailable"))
        else if(state.deletion!=null){
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
            TextButton(onClick={reviewingDeletion=true},enabled=!state.busy,modifier=Modifier.testTag("account-delete")){Text("Delete account and server data")}
        }else AccountSignIn(account,state)
        sync?.let{SyncSection(it,state.signedIn)}
        Text("Remove local journal",style=MaterialTheme.typography.titleMedium)
        Text("Removes this device's cooks, recipes, photos, notes, drafts, reminders and timers in the journal. This does not delete the server account; credentials, deletion records and recovery, and community block and report device state stay untouched.")
        TextButton(onClick={reviewingJournal=true},enabled=!state.busy,modifier=Modifier.testTag("local-journal-remove")){Text("Remove local journal")}
        if(state.busy)CircularProgressIndicator()
        state.message?.let{Text(it,Modifier.testTag("account-message"))}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Sign out on this device?")},text={Text("Your local cooks, recipes and photos stay on this device. Signing out doesn't delete your account.")},confirmButton={TextButton(onClick={confirm=false;account.signOut()},modifier=Modifier.testTag("account-confirm-signout")){Text("Sign out")}},dismissButton={TextButton(onClick={confirm=false}){Text("Cancel")}})
    if(reviewingDeletion)AccountDeletionConfirmation(onCancel={reviewingDeletion=false},onConfirm={reviewingDeletion=false;account.requestDeletion()})
    if(reviewingJournal)LocalJournalRemovalConfirmation(onCancel={reviewingJournal=false},onConfirm={reviewingJournal=false;account.removeLocalJournal()})
}
@Composable private fun SyncSection(sync: com.pittech.data.PrivateSyncCoordinator, signedIn: Boolean) {
    val state by sync.presentation.collectAsStateWithLifecycle()
    var reviewing by remember{mutableStateOf(false)}
    val scope = rememberCoroutineScope()
    LaunchedEffect(signedIn){sync.refresh()}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Private sync",style=MaterialTheme.typography.titleMedium)
        Text("Sync keeps an encrypted backup of this journal for signed-in cooks. It never publishes anything.",style=MaterialTheme.typography.bodySmall)
        Text(state.status,Modifier.testTag("sync-status"))
        state.detail?.let{Text(it,Modifier.testTag("sync-message"),style=MaterialTheme.typography.bodySmall)}
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
        if(state.busy)CircularProgressIndicator()
    }
    if(reviewing)SyncConflictDialog(sync,state.conflicts,state.busy,onClose={reviewing=false})
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SyncConflictDialog(sync: com.pittech.data.PrivateSyncCoordinator, conflicts: List<com.pittech.data.PrivateSyncConflictView>, busy: Boolean, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest=onClose,title={Text("Review synced edits")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Two devices changed the same entry. Your journal keeps the current version until you choose.")
            conflicts.forEach { conflict ->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Text(conflict.reason,style=MaterialTheme.typography.titleSmall)
                    Text("Yours: ${conflict.mineSummary}")
                    Text("Other device: ${conflict.theirsSummary}")
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
