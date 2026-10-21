package com.pittech.data

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.NoCredentialException
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AccountTest {
    private class Memory(var value: AccountState = AccountState(), var fail: Boolean = false) : AccountStorage {
        override fun load() = value
        override fun save(value: AccountState) { check(!fail); this.value = AccountState.parse(value.json()) }
    }
    private fun fixture() = JSONObject(javaClass.classLoader!!.getResourceAsStream("auth-native-v1.json")!!.bufferedReader().use { it.readText() })
    private val token = "A".repeat(43)
    private object TestHost : GoogleSignInHost
    private class FakeGoogle(var result: Result<String> = Result.success("fake-google-id-token")) : GoogleIdProvider {
        val nonces = mutableListOf<String>()
        override suspend fun authorize(host: GoogleSignInHost, nonce: String): String { nonces += nonce; return result.getOrThrow() }
    }
    private val webClientId = "synthetic-web-client.apps.googleusercontent.com"
    private fun session(expired: Boolean = false, rotated: Boolean = false) = AccountSession(if(rotated) "B".repeat(42)+"A" else token,token,if(expired) "2000-01-01T00:00:00.000Z" else "2100-01-01T00:00:00.000Z","2100-02-01T00:00:00.000Z","12345678-1234-4234-8234-123456789abc")
    @Test fun sharedProofCanonicalLinksAndEmailCasesAgreeWithTheBackend() {
        val f=fixture(); assertEquals(f.getString("proof"),AccountProtocol.proof(f.getString("verifier")))
        assertEquals(43,AccountProtocol.verifier().length)
        val links=f.getJSONArray("validLinks"); for(i in 0 until links.length()) assertEquals(f.getString("challengeId"),AccountProtocol.link(links.getString(i)).first)
        val bad=f.getJSONArray("invalidLinks"); for(i in 0 until bad.length()) assertThrows(Exception::class.java) { AccountProtocol.link(bad.getString(i)) }
        val emails=f.getJSONArray("emails"); for(i in 0 until emails.length()) assertEquals(emails.getJSONArray(i).getString(1),AccountProtocol.email(emails.getJSONArray(i).getString(0)))
        val invalid=f.getJSONArray("invalidEmails"); for(i in 0 until invalid.length()) assertThrows(Exception::class.java) { AccountProtocol.email(invalid.getString(i)) }
    }
    @Test fun lostStartAndVerificationResponsesRecoverAcrossRestartWithoutChangingCredentials() = runBlocking {
        val memory=Memory(); var start: String?=null; var verify: String?=null; var loseStart=true; var loseVerify=true
        val api=AccountAPI { path,body,_ ->
            if(path.endsWith("challenges")) {
                assertNotNull(memory.value.challenge); if(start == null) start=body.toString() else assertEquals(start,body.toString())
                if(loseStart) { loseStart=false; throw AccountFailure() }
                JSONObject().put("challengeId",body!!.getString("idempotencyId")).put("expiresInSeconds",900)
            } else {
                assertNotNull(memory.value.challenge!!.operation)
                if(verify == null) verify=body.toString() else assertEquals(verify,body.toString())
                if(loseVerify) { loseVerify=false; throw AccountFailure() }; session().json()
            }
        }
        val first=AccountManager(memory,this,api,true); first.start("cook@example.com"); yield()
        val second=AccountManager(memory,this,api,true); second.start("cook@example.com"); yield(); second.verify("123456"); yield()
        assertTrue(second.presentation.value.interruptedVerification)
        val third=AccountManager(memory,this,api,true); third.verify("999999"); yield()
        assertTrue(third.presentation.value.signedIn); assertNull(memory.value.challenge)
    }
    @Test fun linkReceiptNeverSignsInAndOnlyTheRequestingDeviceCanConfirm() = runBlocking {
        val f=fixture(); val memory=Memory(AccountState(challenge=AccountChallenge(f.getString("challengeId"),"cook@example.com",f.getString("verifier"),System.currentTimeMillis())))
        var calls=0; val manager=AccountManager(memory,this,AccountAPI { _,body,_ -> calls++; assertEquals(f.getString("verifier"),body!!.getString("token")); session().json() },true)
        manager.receive(f.getJSONArray("validLinks").getString(0)); assertEquals(0,calls); assertTrue(manager.presentation.value.pendingLink)
        manager.verify(); yield(); assertEquals(1,calls); assertTrue(manager.presentation.value.signedIn)
        val other=AccountManager(Memory(),this,AccountAPI { _,_,_ -> fail("Unrequested link exchanged"); JSONObject() },true)
        other.receive(f.getJSONArray("validLinks").getString(0)); assertFalse(other.presentation.value.pendingLink)
    }
    @Test fun refreshIsSingleFlightAndInterruptedRotationKeepsTheSameOperation() = runBlocking {
        val memory=Memory(AccountState(session=session(true))); var operation: String?=null; var calls=0; var lose=true
        val api=AccountAPI { _,body,_ ->
            calls++; assertEquals(memory.value.refreshOperation,body!!.getString("idempotencyId"))
            if(operation == null) operation=body.getString("idempotencyId") else assertEquals(operation,body.getString("idempotencyId"))
            delay(10); if(lose) { lose=false; throw AccountFailure() }; session(rotated=true).json()
        }
        val manager=AccountManager(memory,this,api,true)
        try { manager.access(); fail("Lost response accepted") } catch(_: AccountFailure) { }
        val restarted=AccountManager(memory,this,api,true)
        val values=awaitAll(async { restarted.access() },async { restarted.access() })
        assertEquals(values[0],values[1]); assertEquals(2,calls); assertNull(memory.value.refreshOperation)
    }
    @Test fun forcedRefreshReplacesAnOtherwiseUnexpiredAccessCredential() = runBlocking {
        val memory=Memory(AccountState(session=session())); var calls=0
        val account=AccountManager(memory,this,AccountAPI { path,_,_-> calls++; assertEquals("/v1/auth/refresh",path); session(rotated=true).json() },true)
        assertEquals(token,account.access()); assertEquals(0,calls)
        val refreshed=account.access(true); assertNotEquals(token,refreshed); assertEquals(1,calls)
        assertEquals(refreshed,account.access()); assertEquals(1,calls)
    }
    @Test fun rejectedRefreshClearsTheSessionButOfflineRefreshRetainsItsRetry() = runBlocking {
        val memory=Memory(AccountState(session=session(true)))
        val manager=AccountManager(memory,this,AccountAPI { _,_,_ -> throw AccountFailure(401) },true)
        try { manager.access(); fail("Rejected refresh accepted") } catch(_: AccountFailure) { }
        assertNull(memory.value.session); assertFalse(manager.presentation.value.signedIn)
    }
    @Test fun offlineSignOutRemovesActiveAccountAndRetriesRevocationBeforeAnotherSignIn() = runBlocking {
        val memory=Memory(AccountState(session=session())); var offline=true; val paths=mutableListOf<String>()
        val api=AccountAPI { path,body,_ ->
            paths+=path
            if(offline) throw AccountFailure()
            if(path.endsWith("challenges")) JSONObject().put("challengeId",body!!.getString("idempotencyId")).put("expiresInSeconds",900) else JSONObject()
        }
        val manager=AccountManager(memory,this,api,true); manager.signOut(); yield()
        assertFalse(manager.presentation.value.signedIn); assertNull(memory.value.session); assertEquals(token,memory.value.logoutToken)
        offline=false; val restarted=AccountManager(memory,this,api,true); restarted.start("new@example.com"); yield()
        assertEquals(listOf("/v1/auth/logout","/v1/auth/logout","/v1/auth/challenges"),paths); assertNull(memory.value.logoutToken)
    }
    @Test fun disabledAccountsAndStorageFailuresNeverSendEmail() = runBlocking {
        var calls=0; val api=AccountAPI { _,_,_ -> calls++; JSONObject() }
        val disabled=AccountManager(Memory(),this,api); disabled.start("cook@example.com"); yield()
        val failing=AccountManager(Memory(fail=true),this,api,true); failing.start("cook@example.com"); yield()
        assertEquals(0,calls)
    }
    @Test fun resendCooldownAndWrongCodePermitANewAttemptWithoutDiscardingTheProof() = runBlocking {
        var clock=Instant.parse("2026-10-15T12:00:00Z").toEpochMilli(); val memory=Memory(); var starts=0; var verifies=0; val proof=mutableListOf<String>()
        val api=AccountAPI { path,body,_ ->
            if(path.endsWith("challenges")) { starts++; JSONObject().put("challengeId",body!!.getString("idempotencyId")).put("expiresInSeconds",900) }
            else { verifies++; proof+=body!!.getString("verifier"); if(verifies == 1) throw AccountFailure(401); session().json() }
        }
        val manager=AccountManager(memory,this,api,true,now={ clock }); manager.start("cook@example.com"); yield()
        manager.start("cook@example.com",true); yield(); assertEquals(1,starts)
        manager.verify("111111"); yield(); manager.verify("222222"); yield(); assertEquals(2,verifies); assertEquals(proof[0],proof[1])
        assertTrue(manager.presentation.value.signedIn)
    }
    @Test fun deletionPersistsExactProofAndRecoversWithoutSessionAcrossRestart()=runBlocking {
        val memory=Memory(AccountState(session=session()));var submitted:String?=null;var lost=true
        val receipt=DeletionReceipt(java.util.UUID.randomUUID().toString(),token,"queued")
        val api=AccountAPI{path,body,access->
            when(path){
                "/v1/me/deletion"->{
                    assertEquals(memory.value.deletion!!.request().toString(),body.toString())
                    if(submitted==null)submitted=body.toString() else assertEquals(submitted,body.toString())
                    if(lost){lost=false;throw AccountFailure()};receipt.json()
                }
                "/v1/deletion/recover"->{assertNull(access);assertNull(memory.value.session);assertEquals(JSONObject(submitted!!).getString("proofChallenge"),AccountProtocol.proof(body!!.getString("verifier")));receipt.json()}
                else->{assertEquals("/v1/deletion/status",path);assertNull(access);JSONObject().put("deletionId",receipt.deletionId).put("state","complete")}
            }
        }
        val first=AccountManager(memory,this,api,true);first.requestDeletion();yield();assertNotNull(memory.value.deletion);assertNull(first.publicationUserId())
        try{first.access();fail("Publishing remained available")}catch(_:Exception){}
        val second=AccountManager(memory,this,api,true);second.requestDeletion();yield();assertNull(memory.value.session);assertEquals("queued",memory.value.deletion!!.receipt!!.state)
        val restarted=AccountManager(memory,this,api,true);restarted.recoverDeletion();yield();restarted.checkDeletion();yield();assertEquals("complete",memory.value.deletion!!.receipt!!.state);assertNull(memory.value.deletion!!.verifier)
        restarted.dismissCompletedDeletion();yield();assertNull(memory.value.deletion)
    }
    @Test fun deletionStorageFailureNeverSendsAndMalformedReceiptKeepsProof()=runBlocking {
        val memory=Memory(AccountState(session=session()));memory.fail=true;var calls=0
        val api=AccountAPI{_,_,_->calls++;JSONObject()}
        val account=AccountManager(memory,this,api,true);account.requestDeletion();yield();assertEquals(0,calls)
        memory.fail=false;account.requestDeletion();yield();assertEquals(1,calls);assertNotNull(memory.value.deletion!!.verifier);assertNull(memory.value.deletion!!.receipt);assertNotNull(memory.value.session)
        assertThrows(Exception::class.java){DeletionReceipt(java.util.UUID.randomUUID().toString(),token,"unknown").validate()}
        assertNull(AccountState.parse(JSONObject()).deletion)
    }
    @Test fun acceptedDeletionRetainsCleanupOwnerAfterFailureAndRetriesOnRestart()=runBlocking {
        val memory=Memory(AccountState(session=session()));val user=memory.value.session!!.userId;var attempts=0
        val receipt=DeletionReceipt(java.util.UUID.randomUUID().toString(),token,"complete")
        val api=AccountAPI{_,_,_->receipt.json()}
        val first=AccountManager(memory,this,api,true,cleanup={owner->assertEquals(user,owner);assertNull(memory.value.session);assertNotNull(memory.value.deletion!!.receipt);attempts++;error("Synthetic storage interruption")})
        first.requestDeletion();yield();assertEquals(1,attempts);assertNull(memory.value.session);assertEquals(user,memory.value.deletion!!.cleanupUserId);assertNull(memory.value.deletion!!.userId)
        val restarted=AccountManager(memory,this,api,true,cleanup={owner->assertEquals(user,owner);attempts++})
        assertEquals(2,attempts);assertNull(memory.value.deletion!!.cleanupUserId);restarted.dismissCompletedDeletion();yield();assertNull(memory.value.deletion)
    }
    @Test fun localJournalRemovalWorksWhileDisabledAndLeavesDeletionAlone()=runBlocking {
        val deletion=DeletionRecord("12345678-1234-4234-8234-123456789abc",java.util.UUID.randomUUID().toString(),token)
        val memory=Memory(AccountState(session=session(),deletion=deletion));var calls=0
        val api=AccountAPI{_,_,_->fail("Journal removal must not use the network");JSONObject()}
        val account=AccountManager(memory,this,api,false,removeJournal={calls++})
        account.removeLocalJournal();yield()
        assertEquals(1,calls);assertEquals(deletion,memory.value.deletion);assertNotNull(memory.value.session)
        assertEquals("Local journal removed from this device. Your server account is unchanged.",account.presentation.value.message)
        val blocked=AccountManager(memory,this,api,false,removeJournal={throw LocalJournalRemovalBlocked("Finish or stop the active cook first.")})
        blocked.removeLocalJournal();yield()
        assertEquals("Finish or stop the active cook first.",blocked.presentation.value.message)
        assertEquals(deletion,memory.value.deletion)
        val failing=AccountManager(memory,this,api,false,removeJournal={error("Synthetic storage interruption")})
        failing.removeLocalJournal();yield()
        assertEquals("Local journal removal failed. Your journal is unchanged.",failing.presentation.value.message)
    }
    @Test fun deletionRetries401OnceWithoutChangingBodyOrAccount()=runBlocking {
        val memory=Memory(AccountState(session=session()));var calls=0;var refreshed=0;var request:String?=null
        val receipt=DeletionReceipt(java.util.UUID.randomUUID().toString(),token,"queued")
        val api=AccountAPI{path,body,_->
            if(path=="/v1/auth/refresh"){refreshed++;session(rotated=true).json()}else{
                calls++;if(request==null)request=body.toString() else assertEquals(request,body.toString())
                if(calls==1)throw AccountFailure(401);receipt.json()
            }
        }
        val account=AccountManager(memory,this,api,true);account.requestDeletion();yield();assertEquals(2,calls);assertEquals(1,refreshed);assertNull(memory.value.session)
    }
    @Test fun providerNonceIsTheProofHashBothClientsAndTheBackendShare() {
        val provider=fixture().getJSONObject("provider")
        assertEquals(provider.getString("nonce"),AccountProtocol.proof(provider.getString("verifier")))
    }
    @Test fun googleSignInPersistsChallengePresentsProofNonceAndRetriesLostResponses() = runBlocking {
        val memory=Memory(); val fake=FakeGoogle(); val challenges=mutableListOf<String>(); val verifies=mutableListOf<String>(); var verifyCalls=0; var loseVerify=true
        val api=AccountAPI { path,body,_ ->
            if(path=="/v1/auth/provider/challenges") {
                assertNotNull(memory.value.providerChallenge); assertEquals("google",body!!.getString("provider"))
                challenges+=body.toString()
                JSONObject().put("challengeId",body.getString("idempotencyId")).put("expiresInSeconds",900)
            } else if(path=="/v1/me") JSONObject().put("id","12345678-1234-4234-8234-123456789abc").put("email","cook@example.com").put("identities",JSONArray().put(JSONObject().put("provider","google")))
            else {
                assertEquals("/v1/auth/provider/verify",path); assertNotNull(memory.value.providerChallenge!!.operation)
                assertEquals("fake-google-id-token",body!!.getString("idToken")); verifyCalls++
                if(verifies.isEmpty()) verifies+=body.toString() else assertEquals(verifies[0],body.toString())
                if(loseVerify) { loseVerify=false; throw AccountFailure() }; session().json()
            }
        }
        val first=AccountManager(memory,this,api,true,googleWebClientId=webClientId,googleAdvertised=true,google=fake)
        first.signInWithGoogle(TestHost); yield()
        assertFalse(first.presentation.value.signedIn)
        assertEquals("Couldn't complete sign-in. Retry the same request when you're online.",first.presentation.value.message)
        assertNotNull(memory.value.providerChallenge?.operation)
        val second=AccountManager(memory,this,api,true,googleWebClientId=webClientId,googleAdvertised=true,google=fake)
        second.signInWithGoogle(TestHost); yield()
        assertTrue(second.presentation.value.signedIn)
        assertEquals("Signed in with Google. Your local cookbook is unchanged.",second.presentation.value.message)
        assertNull(memory.value.providerChallenge)
        assertEquals(2,challenges.size); assertEquals(challenges[0],challenges[1]); assertEquals(2,verifyCalls)
        val proof=AccountProtocol.proof(JSONObject(verifies[0]).getString("verifier"))
        assertEquals(listOf(proof,proof),fake.nonces)
        assertEquals(proof,JSONObject(challenges[0]).getString("proofChallenge"))
        assertEquals("cook@example.com",second.presentation.value.accountEmail); assertTrue(second.presentation.value.googleLinked)
        assertFalse(ProviderChallenge("id","google","distinctive-verifier-value",0,"op",false).toString().contains("distinctive-verifier-value"))
    }
    @Test fun googleSheetCancellationStaysSilentAndKeepsPendingChallenge() = runBlocking {
        val memory=Memory(); val fake=FakeGoogle(Result.failure(GoogleIdCancellation())); val paths=mutableListOf<String>()
        val api=AccountAPI { path,body,_ -> paths+=path; assertEquals("/v1/auth/provider/challenges",path); JSONObject().put("challengeId",body!!.getString("idempotencyId")).put("expiresInSeconds",900) }
        val account=AccountManager(memory,this,api,true,googleWebClientId=webClientId,googleAdvertised=true,google=fake)
        account.signInWithGoogle(TestHost); yield()
        assertNull(account.presentation.value.message); assertFalse(account.presentation.value.signedIn)
        assertEquals(listOf("/v1/auth/provider/challenges"),paths)
        assertNotNull(memory.value.providerChallenge); assertNotNull(memory.value.providerChallenge?.operation); assertEquals(1,fake.nonces.size)
    }
    @Test fun onlySheetDismissalAndNoCredentialStaySilent() {
        assertTrue(googleIdSilenced(GetCredentialCancellationException()))
        assertTrue(googleIdSilenced(NoCredentialException()))
        assertFalse(googleIdSilenced(GetCredentialInterruptedException()))
    }
    @Test fun googleSheetFailureFallsBackToEmail() = runBlocking {
        val memory=Memory(); val fake=FakeGoogle(Result.failure(GetCredentialInterruptedException()))
        val api=AccountAPI { _,body,_ -> JSONObject().put("challengeId",body!!.getString("idempotencyId")).put("expiresInSeconds",900) }
        val account=AccountManager(memory,this,api,true,googleWebClientId=webClientId,googleAdvertised=true,google=fake)
        account.signInWithGoogle(TestHost); yield()
        assertEquals("Sign in with Google is unavailable. Try email sign-in instead.",account.presentation.value.message)
        assertFalse(account.presentation.value.signedIn); assertNotNull(memory.value.providerChallenge)
    }
    @Test fun googleAttachLinksWhileSignedInWithoutANewSession() = runBlocking {
        val memory=Memory(AccountState(session=session())); val fake=FakeGoogle(); var identitiesCalls=0
        val api=AccountAPI { path,body,access ->
            if(path=="/v1/auth/provider/challenges") { assertEquals(true,memory.value.providerChallenge?.bound); assertEquals(token,access); JSONObject().put("challengeId",body!!.getString("idempotencyId")).put("expiresInSeconds",900) }
            else if(path=="/v1/me/identities") { identitiesCalls++; assertEquals(true,memory.value.providerChallenge?.bound); assertEquals(token,access); JSONObject().put("identities",JSONArray().put(JSONObject().put("provider","google"))) }
            else { assertEquals("/v1/me",path); JSONObject().put("id","12345678-1234-4234-8234-123456789abc").put("email","cook@example.com").put("identities",JSONArray().put(JSONObject().put("provider","google"))) }
        }
        val account=AccountManager(memory,this,api,true,googleWebClientId=webClientId,googleAdvertised=true,google=fake)
        account.attachGoogle(TestHost); yield()
        assertEquals(1,identitiesCalls); assertTrue(account.presentation.value.googleLinked)
        assertEquals("cook@example.com",account.presentation.value.accountEmail)
        assertEquals(token,memory.value.session?.refreshToken); assertNull(memory.value.providerChallenge)
        assertEquals("Google sign-in is linked. Email sign-in still works as a backup.",account.presentation.value.message)
        assertEquals(1,fake.nonces.size)
    }
    @Test fun googleSignInConflictDirectsToExistingMethodThenAttach() = runBlocking {
        val memory=Memory()
        val api=AccountAPI { path,body,_ ->
            if(path=="/v1/auth/provider/challenges") JSONObject().put("challengeId",body!!.getString("idempotencyId")).put("expiresInSeconds",900)
            else { assertEquals("/v1/auth/provider/verify",path); throw AccountFailure(409) }
        }
        val account=AccountManager(memory,this,api,true,googleWebClientId=webClientId,googleAdvertised=true,google=FakeGoogle())
        account.signInWithGoogle(TestHost); yield()
        assertEquals("Sign in with your existing method first, then add Google sign-in in account settings.",account.presentation.value.message)
        assertFalse(account.presentation.value.signedIn)
        assertNotNull(memory.value.providerChallenge); assertNull(memory.value.providerChallenge?.operation)
    }
    @Test fun googleSignInWithoutEmailDirectsToEmailThenAttach() = runBlocking {
        val memory=Memory()
        val api=AccountAPI { path,body,_ ->
            if(path=="/v1/auth/provider/challenges") JSONObject().put("challengeId",body!!.getString("idempotencyId")).put("expiresInSeconds",900)
            else { assertEquals("/v1/auth/provider/verify",path); throw AccountFailure(401) }
        }
        val account=AccountManager(memory,this,api,true,googleWebClientId=webClientId,googleAdvertised=true,google=FakeGoogle())
        account.signInWithGoogle(TestHost); yield()
        assertEquals("This Google sign-in expired or is unavailable. Try again, or sign in by email first and then add Google sign-in in account settings.",account.presentation.value.message)
        assertFalse(account.presentation.value.signedIn)
        assertNotNull(memory.value.providerChallenge); assertNull(memory.value.providerChallenge?.operation)
    }
    @Test fun googleAttachConflictAndRejectionKeepGuidance() = runBlocking {
        for((status,expected) in listOf(409 to "Sign in with your existing method first, then add Google sign-in in account settings.",401 to "Couldn't add Google sign-in. Try again.")) {
            val memory=Memory(AccountState(session=session()))
            val api=AccountAPI { path,body,_ ->
                if(path=="/v1/auth/provider/challenges") JSONObject().put("challengeId",body!!.getString("idempotencyId")).put("expiresInSeconds",900)
                else { assertEquals("/v1/me/identities",path); throw AccountFailure(status) }
            }
            val account=AccountManager(memory,this,api,true,googleWebClientId=webClientId,googleAdvertised=true,google=FakeGoogle())
            account.attachGoogle(TestHost); yield()
            assertEquals(expected,account.presentation.value.message)
            assertTrue(account.presentation.value.signedIn); assertFalse(account.presentation.value.googleLinked)
            assertNotNull(memory.value.providerChallenge)
        }
    }
    @Test fun googleButtonNeedsAccountsAdvertisedDeploymentAndClientId() = runBlocking {
        fun manager(enabled: Boolean,advertised: Boolean,clientId: String,scope: CoroutineScope) = AccountManager(Memory(),scope,AccountAPI { _,_,_ -> JSONObject() },enabled,googleWebClientId=clientId,googleAdvertised=advertised,google=FakeGoogle())
        assertTrue(manager(true,true,webClientId,this).googleSignInAvailable)
        assertFalse(manager(false,true,webClientId,this).googleSignInAvailable)
        assertFalse(manager(true,false,webClientId,this).googleSignInAvailable)
        assertFalse(manager(true,true,"",this).googleSignInAvailable)
        val fake=FakeGoogle(); var calls=0
        val disabled=AccountManager(Memory(),this,AccountAPI { _,_,_ -> calls++; JSONObject() },false,googleWebClientId=webClientId,googleAdvertised=true,google=fake)
        disabled.signInWithGoogle(TestHost); yield(); disabled.attachGoogle(TestHost); yield()
        assertEquals(0,calls); assertTrue(fake.nonces.isEmpty())
        assertEquals(AccountFailure(503).message,disabled.presentation.value.message)
    }
    @Test fun googleChallengeUnavailableFallsBackToEmail() = runBlocking {
        val fake=FakeGoogle()
        val account=AccountManager(Memory(),this,AccountAPI { _,_,_ -> throw AccountFailure(401) },true,googleWebClientId=webClientId,googleAdvertised=true,google=fake)
        account.signInWithGoogle(TestHost); yield()
        assertEquals("Sign in with Google is unavailable. Try email sign-in instead.",account.presentation.value.message)
        assertTrue(fake.nonces.isEmpty()); assertFalse(account.presentation.value.signedIn)
    }
    @Test fun checkSessionStoresAccountEmailAndLinkedProviders() = runBlocking {
        val memory=Memory(AccountState(session=session()))
        val account=AccountManager(memory,this,AccountAPI { path,_,_ ->
            assertEquals("/v1/me",path)
            JSONObject().put("id","12345678-1234-4234-8234-123456789abc").put("email","cook@example.com").put("identities",JSONArray().put(JSONObject().put("provider","google")).put(JSONObject().put("provider","unknown-future")))
        },true)
        account.checkSession(); yield()
        assertEquals("Your session is active.",account.presentation.value.message)
        assertEquals("cook@example.com",account.presentation.value.accountEmail)
        assertTrue(account.presentation.value.googleLinked)
    }

}
