package com.pittech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.data.AccountManager
import kotlinx.coroutines.delay

@Composable internal fun AccountDialog(account: AccountManager) {
    val showing by account.showing.collectAsStateWithLifecycle()
    if(!showing) return
    AlertDialog(onDismissRequest={ account.close() },title={ Text("Account") }, text={ AccountContent(account) },confirmButton={ TextButton(onClick={ account.close() }) { Text("Done") } })
}
@Composable internal fun AccountContent(account: AccountManager) {
    val state by account.presentation.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(account,state.challengeEmail) { while(true) { delay(1000); account.tick() } }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Your cookbook works without an account. Signing in doesn't publish or upload your local records.")
        if(!account.enabled) Text("Accounts arrive with community recipes and private sync.",Modifier.testTag("account-unavailable"))
        else if(state.signedIn) {
            Text("Signed in",Modifier.testTag("account-signed-in"),style=MaterialTheme.typography.titleMedium)
            Button(onClick={ account.checkSession() },enabled=!state.busy) { Text("Check connection") }
            TextButton(onClick={ confirm=true },enabled=!state.busy) { Text("Sign out") }
        } else {
            OutlinedTextField(email,{ email=it },label={ Text("Email address") },singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth().testTag("account-email"))
            Button(onClick={ keyboard?.hide(); account.start(email) },enabled=!state.busy && email.isNotEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("account-start")) { Text("Send sign-in email") }
            state.challengeEmail?.let { address ->
                Text("Enter the six-digit code sent to $address. You can open the email on another device.")
                OutlinedTextField(code,{ code=it },label={ Text("Six-digit code") },singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth().testTag("account-code"))
                Button(onClick={ keyboard?.hide(); account.verify(code); code="" },enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("account-verify")) { Text(if(state.interruptedVerification) "Retry sign-in" else if(state.pendingLink) "Confirm email link" else "Verify code") }
                TextButton(onClick={ account.start(address,resend=true) },enabled=!state.busy && state.canResend,modifier=Modifier.testTag("account-resend")) { Text("Send a new code") }
                Text("Codes expire after 15 minutes. Wait at least a minute before requesting another.",style=MaterialTheme.typography.bodySmall)
            }
        }
        if(state.busy) CircularProgressIndicator()
        state.message?.let { Text(it,Modifier.testTag("account-message")) }
    }
    if(confirm) AlertDialog(onDismissRequest={ confirm=false },title={ Text("Sign out on this device?") },text={ Text("Your local cooks, recipes and photos stay on this device. Signing out doesn't delete your account.") },confirmButton={ TextButton(onClick={ confirm=false; account.signOut() }) { Text("Sign out") } },dismissButton={ TextButton(onClick={ confirm=false }) { Text("Cancel") } })
}
