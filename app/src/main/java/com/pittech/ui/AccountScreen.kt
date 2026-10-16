package com.pittech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.data.AccountManager
import com.pittech.data.AccountPresentation
import kotlinx.coroutines.delay

@Composable internal fun AccountDialog(account: AccountManager) {
    val showing by account.showing.collectAsStateWithLifecycle()
    if(!showing)return
    AlertDialog(onDismissRequest={account.close()},title={Text("Account")},text={AccountContent(account)},confirmButton={TextButton(onClick={account.close()}){Text("Done")}})
}
@Composable internal fun AccountContent(account: AccountManager) {
    val state by account.presentation.collectAsStateWithLifecycle()
    var confirm by remember{mutableStateOf(false)}
    var reviewingDeletion by remember{mutableStateOf(false)}
    LaunchedEffect(account,state.challengeEmail){while(true){delay(1000);account.tick()}}
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Your cookbook works without an account. Signing in doesn't publish or upload your local records.")
        if(!account.enabled)Text("Accounts arrive with community recipes and private sync.",Modifier.testTag("account-unavailable"))
        else if(state.deletion!=null){
            val deletion=state.deletion!!
            Text("Account deletion",style=MaterialTheme.typography.titleMedium)
            Text(deletion.receipt?.description?:"The deletion response was not confirmed. Recover the request before retrying.",Modifier.testTag("account-deletion-status"))
            Text("Your local journal stays available on this device. Deletion does not stop your local cooking.")
            if(deletion.receipt?.state=="complete")Button(onClick={account.dismissCompletedDeletion()},enabled=!state.busy){Text("Close completed request")}
            else{
                if(deletion.receipt!=null)Button(onClick={account.checkDeletion()},enabled=!state.busy,modifier=Modifier.testTag("account-deletion-check")){Text("Check deletion progress")}
                Button(onClick={account.recoverDeletion()},enabled=!state.busy,modifier=Modifier.testTag("account-deletion-recover")){Text("Recover deletion request")}
                if(deletion.receipt==null && state.signedIn)TextButton(onClick={reviewingDeletion=true},enabled=!state.busy,modifier=Modifier.testTag("account-deletion-retry")){Text("Review and retry the same request")}
            }
            if(deletion.receipt==null && !state.signedIn){Text("Verify the original account email to retry this device’s saved request. Another account cannot take over the request.");AccountSignIn(account,state)}
        }else if(state.signedIn){
            Text("Signed in",Modifier.testTag("account-signed-in"),style=MaterialTheme.typography.titleMedium)
            Button(onClick={account.checkSession()},enabled=!state.busy){Text("Check connection")}
            TextButton(onClick={confirm=true},enabled=!state.busy){Text("Sign out")}
            TextButton(onClick={reviewingDeletion=true},enabled=!state.busy,modifier=Modifier.testTag("account-delete")){Text("Delete account and server data")}
        }else AccountSignIn(account,state)
        if(state.busy)CircularProgressIndicator()
        state.message?.let{Text(it,Modifier.testTag("account-message"))}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Sign out on this device?")},text={Text("Your local cooks, recipes and photos stay on this device. Signing out doesn't delete your account.")},confirmButton={TextButton(onClick={confirm=false;account.signOut()},modifier=Modifier.testTag("account-confirm-signout")){Text("Sign out")}},dismissButton={TextButton(onClick={confirm=false}){Text("Cancel")}})
    if(reviewingDeletion)AccountDeletionConfirmation(onCancel={reviewingDeletion=false},onConfirm={reviewingDeletion=false;account.requestDeletion()})
}
@Composable private fun AccountSignIn(account:AccountManager,state:AccountPresentation){
    var email by remember{mutableStateOf("")};var code by remember{mutableStateOf("")}
    val keyboard=LocalSoftwareKeyboardController.current
    OutlinedTextField(email,{email=it},label={Text("Email address")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth().testTag("account-email"))
    Button(onClick={keyboard?.hide();account.start(email)},enabled=!state.busy && email.isNotEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("account-start")){Text("Send sign-in email")}
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
