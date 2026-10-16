package com.pittech.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

internal data class AccountPresentation(val signedIn: Boolean = false, val challengeEmail: String? = null, val canResend: Boolean = true, val interruptedVerification: Boolean = false, val pendingLink: Boolean = false, val busy: Boolean = false, val message: String? = null, val deletion: DeletionRecord? = null)
internal class AccountManager(private val storage: AccountStorage, private val scope: CoroutineScope, private val api: AccountAPI = AccountClient(), val enabled: Boolean = AccountProtocol.ENABLED, private val now: () -> Long = System::currentTimeMillis, private val cleanup: (String) -> Unit = {}, private val removeJournal: (suspend () -> Unit)? = null) {
    private var state = AccountState()
    private var storageReady = true
    private val mutex = Mutex()
    private var link: String? = null
    private val _presentation = MutableStateFlow(AccountPresentation())
    val presentation = _presentation.asStateFlow()
    private val _showing = MutableStateFlow(false)
    val showing = _showing.asStateFlow()
    fun open() { _showing.value = true }
    fun close() { _showing.value = false }
    fun tick() { _presentation.value = _presentation.value.copy(canResend=state.challenge?.let { now()-it.created >= 60000 } ?: true) }
    init { try { state = storage.load(); state.deletion?.validate(); if(state.deletion?.receipt!=null)runCatching{cleanupAcceptedDeletion()}; if(state.challenge?.let { now()-it.created >= 900000 } == true) save(state.copy(challenge=null));
        state.session?.let { it.validate(); if(Instant.parse(it.sessionExpiresAt).toEpochMilli() <= now()) save(AccountState(logoutToken=it.refreshToken,deletion=state.deletion)) }; publish() } catch (_: Exception) { storageReady = false; publish(message="This device's account storage is unavailable. Your local cookbook is unchanged.") } }
    private fun publish(busy: Boolean = false, message: String? = null) {
        _presentation.value = AccountPresentation(state.session != null,state.challenge?.email,state.challenge?.let { now()-it.created >= 60000 } ?: true,state.challenge?.operation != null,link != null,busy,message,state.deletion)
    }
    private fun save(next: AccountState) { storage.save(next); state = next }
    private fun ready() { if(!enabled) throw AccountFailure(503); check(storageReady) }
    private suspend fun flushLogout() { state.logoutToken?.let { api.call("/v1/auth/logout",JSONObject().put("refreshToken",it),null); save(state.copy(logoutToken=null)) } }
    private fun perform(operation: suspend () -> Unit) {
        // Reserve synchronously so duplicate taps cannot enqueue multiple verification attempts.
        if(_presentation.value.busy) return
        publish(busy=true)
        scope.launch {
            mutex.withLock {
                try { ready(); operation(); publish(message=_presentation.value.message) }
                catch(e: CancellationException) { publish(message="Request interrupted. Retry to recover the same sign-in."); throw e }
                catch(e: Exception) { publish(message=if(e is AccountFailure) e.message else "Check your email, code or link and try again.") }
            }
        }
    }
    fun start(input: String, resend: Boolean = false) = perform {
        require(state.deletion?.receipt==null); flushLogout(); val email = AccountProtocol.email(input); val old = state.challenge
        if(old == null || old.email != email || resend || now()-old.created >= 900000) {
            if(old != null && now()-old.created < 60000) throw AccountFailure(429)
            save(state.copy(challenge=AccountChallenge(UUID.randomUUID().toString(),email,AccountProtocol.verifier(),now()))); link=null
        }
        val challenge = state.challenge!!
        val reply = api.call("/v1/auth/challenges",JSONObject().put("email",challenge.email).put("idempotencyId",challenge.id).put("proofChallenge",AccountProtocol.proof(challenge.verifier)),null)
        require(reply.getString("challengeId") == challenge.id && reply.getInt("expiresInSeconds") == 900)
        save(state.copy(challenge=challenge.copy(accepted=true)))
        publish(message="Check your email for a link and six-digit code. Both expire in 15 minutes.")
    }
    fun receive(input: String) {
        if(!enabled || _presentation.value.busy) return
        open()
        try {
            val (id,token) = AccountProtocol.link(input); val challenge = state.challenge
            require(challenge != null && challenge.id == id && now()-challenge.created < 900000)
            link=token; publish(message="Confirm sign-in on this device.")
        } catch(_: Exception) { link=null; publish(message="Start sign-in on this device, then enter the code from your email.") }
    }
    fun verify(code: String = "") = perform {
        var challenge = state.challenge ?: throw AccountFailure(401)
        if(now()-challenge.created >= 900000) throw AccountFailure(401)
        if(challenge.operation == null) {
            val secret = link ?: code; require(link != null || secret.matches(Regex("[0-9]{6}")))
            challenge = challenge.copy(operation=UUID.randomUUID().toString(),secret=secret,isLink=link != null)
            save(state.copy(challenge=challenge))
        }
        val body = JSONObject().put("challengeId",challenge.id).put("verifier",challenge.verifier).put("idempotencyId",challenge.operation).put(if(challenge.isLink) "token" else "code",challenge.secret)
        try {
            val session = AccountSession.parse(api.call("/v1/auth/verify",body,null))
            state.deletion?.userId?.let{owner->if(owner!=session.userId){runCatching{api.call("/v1/auth/logout",JSONObject().put("refreshToken",session.refreshToken),null)};error("Verify the original account.")}}
            save(state.copy(session=session,challenge=null,refreshOperation=null)); link=null
            publish(message="Signed in. Your local cookbook is unchanged.")
        } catch(e: AccountFailure) {
            if(e.status == 401) { save(state.copy(challenge=challenge.copy(operation=null,secret=null))); link=null }
            throw e
        }
    }
    /** Serialized refresh shares the resulting access credential; ambiguous responses keep the same operation UUID. */
    suspend fun publicationUserId(): String? = mutex.withLock { if(state.deletion==null)state.session?.userId else null }
    suspend fun access(forceRefresh: Boolean = false): String = mutex.withLock {
        require(state.deletion==null); accessLocked(forceRefresh)
    }
    private suspend fun accessLocked(forceRefresh: Boolean = false):String {
        ready(); val session = state.session ?: throw AccountFailure(401)
        if(Instant.parse(session.sessionExpiresAt).toEpochMilli() <= now()) { save(AccountState(logoutToken=session.refreshToken,deletion=state.deletion)); publish(); throw AccountFailure(401) }
        if(!forceRefresh && state.refreshOperation == null && Instant.parse(session.accessExpiresAt).toEpochMilli() > now()+30000) return session.accessToken
        save(state.copy(refreshOperation=state.refreshOperation ?: UUID.randomUUID().toString()))
        try {
            val rotated = AccountSession.parse(api.call("/v1/auth/refresh",JSONObject().put("refreshToken",session.refreshToken).put("idempotencyId",state.refreshOperation),null))
            require(rotated.userId == session.userId)
            save(state.copy(session=rotated,refreshOperation=null)); publish(busy=_presentation.value.busy); return rotated.accessToken
        } catch(e: AccountFailure) { if(e.status == 401) { save(AccountState(deletion=state.deletion)); publish() }; throw e }
    }
    fun checkSession() {
        if(_presentation.value.busy) return
        publish(busy=true)
        scope.launch {
            try {
                val token = access()
                mutex.withLock {
                    val reply = api.call("/v1/me",null,token); require(reply.getString("id") == state.session?.userId)
                    publish(message="Your session is active.")
                }
            } catch(e: Exception) {
                mutex.withLock {
                    if(e is AccountFailure && e.status == 401) save(AccountState(deletion=state.deletion))
                    publish(message="Couldn't reconnect. Your local cookbook is available offline.")
                }
                if(e is CancellationException) throw e
            }
        }
    }
    fun signOut() = perform {
        save(AccountState(logoutToken=state.session?.refreshToken ?: state.logoutToken,deletion=state.deletion)); link=null
        try { flushLogout(); publish(message="Signed out. Your local cookbook stays on this device.") }
        catch(e: Exception) { publish(message="Signed out on this device. Server sign-out will retry before your next sign-in."); if(e is CancellationException) throw e }
    }
    private fun acceptDeletion(receipt:DeletionReceipt){
        receipt.validate();val record=requireNotNull(state.deletion)
        record.receipt?.let{require(it.deletionId==receipt.deletionId && it.statusToken==receipt.statusToken)}
        save(AccountState(deletion=record.copy(userId=null,cleanupUserId=record.cleanupUserId?:record.userId,verifier=if(receipt.state=="complete")null else record.verifier,receipt=receipt)));link=null
        try{cleanupAcceptedDeletion();publish(busy=true,message=receipt.description)}
        catch(_:Exception){publish(busy=true,message="Server deletion was accepted. Device account metadata cleanup needs a retry. Your journal is unchanged.")}
    }
    private fun cleanupAcceptedDeletion(){
        val record=state.deletion?:return;if(record.receipt==null)return;val user=record.cleanupUserId?:return
        cleanup(user);save(AccountState(deletion=record.copy(cleanupUserId=null)))
    }
    fun retryDeviceCleanup()=perform{cleanupAcceptedDeletion();publish(busy=true,message=state.deletion?.receipt?.description)}
    fun requestDeletion()=perform {
        val user=state.session?.userId ?: throw AccountFailure(401)
        state.deletion?.let{require(it.userId==user && it.receipt==null)} ?: save(state.copy(deletion=DeletionRecord(user,UUID.randomUUID().toString(),AccountProtocol.verifier())))
        val record=requireNotNull(state.deletion);val body=record.request()
        try {
            val token=accessLocked()
            val reply=try{api.call("/v1/me/deletion",body,token)}catch(e:AccountFailure){
                if(e.status!=401)throw e
                val refreshed=accessLocked(true);require(state.session?.userId==record.userId)
                api.call("/v1/me/deletion",body,refreshed)
            }
            acceptDeletion(DeletionReceipt.parse(reply))
        }catch(e:Exception){publish(busy=true,message="Deletion was not confirmed. Recover the request first, or retry the same request on this account. Your local journal stays available.");if(e is CancellationException)throw e}
    }
    fun recoverDeletion()=perform {
        val record=requireNotNull(state.deletion);val verifier=requireNotNull(record.verifier)
        acceptDeletion(DeletionReceipt.parse(api.call("/v1/deletion/recover",JSONObject().put("operationId",record.operationId).put("verifier",verifier),null)))
    }
    fun checkDeletion()=perform {
        val receipt=requireNotNull(state.deletion?.receipt)
        val result=api.call("/v1/deletion/status",JSONObject().put("deletionId",receipt.deletionId).put("statusToken",receipt.statusToken),null)
        require(result.getString("deletionId")==receipt.deletionId)
        acceptDeletion(receipt.copy(state=result.getString("state")))
    }
    fun dismissCompletedDeletion()=perform {
        require(state.deletion?.receipt?.state=="complete");cleanupAcceptedDeletion();save(AccountState());publish(message="Account deletion is complete. Your local journal stays on this device.")
    }
    /** Device-local journal removal. Independent of account deletion and of account availability: the server account, credentials and deletion records are untouched, and this works whether accounts are enabled or not. */
    fun removeLocalJournal() {
        // Deliberately not perform(): the journal is device-local, so account
        // availability and account-storage readiness must not gate removal.
        if(_presentation.value.busy) return
        publish(busy=true)
        scope.launch {
            mutex.withLock {
                try {
                    val remove=removeJournal ?: error("Local journal removal is unavailable on this device.")
                    remove()
                    publish(message="Local journal removed from this device. Your server account is unchanged.")
                }
                catch(e: CancellationException) { publish(message="Local journal removal was interrupted. Your journal is unchanged."); throw e }
                catch(e: LocalJournalRemovalBlocked) { publish(message=e.message) }
                catch(e: Exception) { publish(message="Local journal removal failed. Your journal is unchanged.") }
            }
        }
    }

}
