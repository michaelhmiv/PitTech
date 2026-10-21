package com.pittech.data

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** User dismissal or no available credential: silent, pending provider state retained. Never logged. */
internal class GoogleIdCancellation : Exception()

/** Sheet dismissal and no-credential stay silent; every other provider failure surfaces guidance. */
internal fun googleIdSilenced(error: GetCredentialException): Boolean = error is GetCredentialCancellationException || error is NoCredentialException

/** Activity carrier for the Credential Manager sheet; tests pass any anonymous instance. */
internal interface GoogleSignInHost
internal class ActivityGoogleSignInHost(val activity: Activity) : GoogleSignInHost

/** Sign in with Google behind an injectable seam. The nonce is the SHA-256 proof hash; the Google
 * ID token returns as a string and is exchanged with the raw verifier. No test contacts Google. */
internal fun interface GoogleIdProvider { suspend fun authorize(host: GoogleSignInHost, nonce: String): String }

/** Credential Manager Google ID. `filterByAuthorizedAccounts=false` so first sign-in can create the
 * account; the audience is the server (web) client ID, never an Android client ID. The ID token is
 * returned to the caller, never logged. */
internal class CredentialManagerGoogleId(private val webClientId: String) : GoogleIdProvider {
    override suspend fun authorize(host: GoogleSignInHost, nonce: String): String {
        val activity = (host as ActivityGoogleSignInHost).activity
        require(webClientId.isNotEmpty())
        val option = GetGoogleIdOption.Builder().setServerClientId(webClientId).setNonce(nonce).setFilterByAuthorizedAccounts(false).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        try {
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            check(credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (e: GetCredentialException) { if (googleIdSilenced(e)) throw GoogleIdCancellation() else throw e }
    }
}
