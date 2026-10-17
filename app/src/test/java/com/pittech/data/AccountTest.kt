package com.pittech.data

import kotlinx.coroutines.*
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

}
