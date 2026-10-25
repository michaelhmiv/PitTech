package com.pittech

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.*
import com.pittech.ui.AccountContent
import com.pittech.ui.PitTechTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.graphics.Bitmap

@RunWith(AndroidJUnit4::class) class AccountUserFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun explicitCodeSignInAndSignOutWorkWithoutTouchingTheLocalJournal() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val storage=object : AccountStorage {
            var value=AccountState()
            override fun load()=value
            override fun save(value: AccountState) { this.value=value }
        }
        var emails=0
        val api=AccountAPI { path,body,_ ->
            if(path.endsWith("challenges")) { emails++; JSONObject().put("challengeId",body!!.getString("idempotencyId")).put("expiresInSeconds",900) }
            else if(path.endsWith("verify")) { require(body!!.getString("code") == "123456"); AccountSession("A".repeat(43),"A".repeat(43),"2100-01-01T00:00:00.000Z","2100-02-01T00:00:00.000Z","12345678-1234-4234-8234-123456789abc").json() }
            else JSONObject()
        }
        val account=AccountManager(storage,scope,api,true)
        try {
            compose.setContent { PitTechTheme { AccountContent(account) } }
            compose.onNodeWithTag("account-email").performTextReplacement("cook@example.com")
            compose.onNodeWithTag("account-start").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("account-code").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("account-code").performScrollTo().performTextReplacement("123456")
            compose.onNodeWithTag("account-verify").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("account-signed-in").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("account-signed-in").assertIsDisplayed(); assertEquals(1,emails)
            screenshot("account-signed-in")
            compose.onNodeWithText("Sign out").performScrollTo().performClick()
            compose.onNodeWithTag("account-confirm-signout").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("account-start").fetchSemanticsNodes().isNotEmpty() }
            assertNull(storage.value.session); screenshot("account-signed-out")
        } finally { scope.cancel() }
    }
    @Test fun deletionRequiresReviewThenRecoversAndChecksProgressWithoutSession() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val storage=object:AccountStorage{
            var value=AccountState(session=AccountSession("A".repeat(43),"A".repeat(43),"2100-01-01T00:00:00.000Z","2100-02-01T00:00:00.000Z","12345678-1234-4234-8234-123456789abc"))
            override fun load()=value
            override fun save(value:AccountState){this.value=value}
        }
        val receipt=DeletionReceipt("23456789-1234-4234-8234-123456789abc","A".repeat(43),"queued")
        var requests=0
        val api=AccountAPI{path,body,access->
            when(path){
                "/v1/me/deletion"->{requests++;assertEquals("delete-account",body!!.getString("confirm"));assertNotNull(storage.value.deletion);receipt.json()}
                "/v1/deletion/recover"->{assertNull(access);assertNull(storage.value.session);receipt.json()}
                "/v1/deletion/status"->{assertNull(access);JSONObject().put("deletionId",receipt.deletionId).put("state","complete")}
                else->error("Unexpected local test path")
            }
        }
        val account=AccountManager(storage,scope,api,true)
        try{
            compose.setContent{PitTechTheme{AccountContent(account)}}
            compose.onNodeWithTag("account-delete").performScrollTo().performClick()
            compose.onNodeWithTag("account-deletion-submit").assertIsNotEnabled();assertEquals(0,requests)
            compose.onNodeWithTag("account-deletion-acknowledge").performScrollTo().performClick()
            compose.onNodeWithTag("account-deletion-confirmation").performScrollTo().performTextReplacement("DELETE")
            screenshot("account-deletion-review")
            compose.onNodeWithTag("account-deletion-submit").performClick()
            compose.waitUntil(10000){storage.value.deletion?.receipt!=null}
            assertNull(storage.value.session);assertEquals(1,requests)
            compose.onNodeWithTag("account-deletion-recover").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("account-deletion-check").performScrollTo().performClick()
            compose.waitUntil(10000){storage.value.deletion?.receipt?.state=="complete"}
            screenshot("account-deletion-complete")
            compose.onNodeWithText("Close completed request").performScrollTo().performClick()
            compose.waitUntil(10000){storage.value.deletion==null};assertNull(storage.value.session)
        }finally{scope.cancel()}
    }
    @Test fun privateSyncStatusAndConflictReviewActionsWorkOffline() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val user="12345678-1234-4234-8234-123456789abc"
        val storage=object:AccountStorage{
            var value=AccountState(session=AccountSession("A".repeat(43),"A".repeat(43),"2100-01-01T00:00:00.000Z","2100-02-01T00:00:00.000Z",user))
            override fun load()=value
            override fun save(value:AccountState){this.value=value}
        }
        val account=AccountManager(storage,scope,AccountAPI{_,_,_->JSONObject()},true)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=PrivateSyncMemoryStore(); val device=store.deviceId()
        fun notePayload(title: String)=JSONObject().put("id","note-1").put("cookId","cook-1").put("eventType","note").put("title",title)
            .put("occurredAtUtcMillis",1000).put("recordedAtUtcMillis",1000).put("timeZoneId","UTC").put("source","manual").put("createdAtUtcMillis",1000).put("updatedAtUtcMillis",1000)
        fun versioned(counter: Long,client: String,payload: JSONObject)=JSONObject().put("recordType","events").put("recordId","note-1")
            .put("version",JSONObject().put("counter",counter).put("clientId",client)).put("deleted",false).put("payload",payload).put("assetIds",JSONArray())
        val baseRow=versioned(1,device,notePayload("Base title")); val localRow=versioned(2,device,notePayload("Local title"))
        val remoteRow=versioned(3,"bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb",notePayload("Remote title"))
        val conflict=PrivateSyncMerge.merge(JSONArray().put(baseRow),JSONArray().put(localRow),JSONArray().put(remoteRow)).getJSONArray("conflicts").getJSONObject(0)
        val archive=JSONObject()
            .put("cooks",JSONArray().put(JSONObject().put("id","cook-1").put("title","Weeknight cook").put("status","active").put("startedAtUtcMillis",1000).put("startedTimeZoneId","UTC").put("createdAtUtcMillis",1000).put("updatedAtUtcMillis",1000)))
            .put("dishes",JSONArray().put(JSONObject().put("id","dish-1").put("cookId","cook-1").put("name","Brisket").put("foodType","beef").put("createdAtUtcMillis",1000).put("updatedAtUtcMillis",1000)))
            .put("events",JSONArray().put(notePayload("Remote title")))
        val library=object:PrivateSyncLibrary{
            var current=archive
            fun snapshot()=JSONObject(current.toString())
            override suspend fun archive()=snapshot()
            override suspend fun photoBytes(type: String,id: String): PrivateSyncPhotoSource?=null
            override suspend fun apply(plan: PrivateSyncApplyPlan){
                for(upsert in plan.upserts){
                    val rows=current.optJSONArray(upsert.type)?:JSONArray().also{current.put(upsert.type,it)}
                    val next=JSONArray();var replaced=false
                    for(i in 0 until rows.length()){
                        if(rows.getJSONObject(i).optString("id","")==upsert.id){val mergedRow=JSONObject(rows.getJSONObject(i).toString());for(field in upsert.payload.keys())mergedRow.put(field,upsert.payload.get(field));next.put(mergedRow);replaced=true}
                        else next.put(rows.getJSONObject(i))
                    }
                    if(!replaced)next.put(JSONObject(upsert.payload.toString()))
                    current.put(upsert.type,next)
                }
            }
        }
        val offline=object:PrivateSyncAPI{
            override suspend fun snapshot(access: String): JSONObject=throw java.io.IOException("offline")
            override suspend fun commit(body: ByteArray,mutationId: String,access: String): JSONObject=throw java.io.IOException("offline")
            override suspend fun beginPhoto(assetId: String,bytes: Long,mime: String,sha: String,access: String): JSONObject=throw java.io.IOException("offline")
            override suspend fun photoStatus(assetId: String,access: String): JSONObject=throw java.io.IOException("offline")
            override suspend fun putPart(assetId: String,index: Int,chunk: ByteArray,access: String): JSONObject=throw java.io.IOException("offline")
            override suspend fun completePhoto(assetId: String,access: String): JSONObject=throw java.io.IOException("offline")
            override suspend fun abandonPhoto(assetId: String,access: String): JSONObject=throw java.io.IOException("offline")
            override suspend fun downloadPhoto(assetId: String,to: File,access: String): File=throw java.io.IOException("offline")
        }
        val sync=PrivateSyncCoordinator(store,AccountPublicationSession(account),scope,offline,library,PrivateSyncProjection(context.assets.open("private-sync-projection-v1.json").bufferedReader().use{it.readText()}),true)
        fun seed(){store.save(user,PrivateSyncState(account=user,serverVersion=1,maxCounter=4,base=JSONArray().put(baseRow).toString(),conflicts=JSONArray().put(conflict).toString()));sync.refresh()}
        try{
            seed()
            compose.setContent{PitTechTheme{AccountContent(account,sync)}}
            compose.waitUntil(10000){compose.onAllNodesWithText("Conflicts to review").fetchSemanticsNodes().isNotEmpty()}
            // Slice 7 (PL-10): the Account sync status row carries the state and
            // the safety clause, not just the bare status string.
            compose.onNodeWithTag("sync-status-detail").performScrollTo().assertTextContains("Nothing is overwritten until you choose.", substring = true)
            compose.onNodeWithTag("sync-conflicts").performScrollTo().performClick()
            compose.onNodeWithText("Yours: Note \"Local title\"").assertIsDisplayed()
            compose.onNodeWithText("Other device: Note \"Remote title\"").assertIsDisplayed()
            // Slice 7 (PL-10): the review states the concrete consequence for the
            // actual pending set (one conflict here), not a generic warning.
            compose.onNodeWithTag("sync-conflict-consequence").performScrollTo().assertTextEquals(com.pittech.ui.SyncStatusCopy.conflictConsequence(1))
            screenshot("sync-conflict-review")
            compose.onNodeWithTag("sync-keep-mine-${conflict.getString("id")}").performScrollTo().performClick()
            compose.waitUntil(10000){store.load(user).resolved.contains(conflict.getString("id"))}
            assertEquals("Local title",library.snapshot().optJSONArray("events")!!.getJSONObject(0).getString("title"))
            compose.onNodeWithText("Done").performClick();compose.waitForIdle()
            seed()
            compose.waitUntil(10000){compose.onAllNodesWithText("Conflicts to review").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("sync-conflicts").performScrollTo().performClick()
            compose.onNodeWithTag("sync-keep-theirs-${conflict.getString("id")}").performScrollTo().performClick()
            compose.waitUntil(10000){store.load(user).resolved.contains(conflict.getString("id"))}
            assertEquals("Local title",library.snapshot().optJSONArray("events")!!.getJSONObject(0).getString("title"))
            compose.onNodeWithText("Done").performClick();compose.waitForIdle()
            seed()
            compose.waitUntil(10000){compose.onAllNodesWithText("Conflicts to review").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("sync-conflicts").performScrollTo().performClick()
            compose.onNodeWithTag("sync-keep-both-${conflict.getString("id")}").performScrollTo().performClick()
            compose.waitUntil(10000){store.load(user).resolved.contains(conflict.getString("id"))}
            val events=library.snapshot().optJSONArray("events")!!
            assertEquals(2,events.length())
            assertTrue((0 until events.length()).all{events.getJSONObject(it).getString("title")=="Local title"})
            compose.onNodeWithText("Done").performClick();compose.waitForIdle()
            compose.onNodeWithTag("sync-now").performScrollTo().performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("Waiting for connection").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(0,store.load(user).lastSyncedAt)
        }finally{scope.cancel()}
    }
    @Test fun localJournalRemovalRequiresTypedConfirmationAndLeavesAccountStateAlone() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val storage=object:AccountStorage{
            var value=AccountState()
            override fun load()=value
            override fun save(value:AccountState){this.value=value}
        }
        var removals=0
        // Accounts stay disabled: the journal is device-local and must not need them.
        val account=AccountManager(storage,scope,AccountAPI{_,_,_->JSONObject()},false,removeJournal={removals++})
        try{
            compose.setContent{PitTechTheme{AccountContent(account)}}
            compose.onNodeWithTag("account-unavailable").assertIsDisplayed()
            compose.onNodeWithTag("local-journal-remove").performScrollTo().performClick()
            compose.onNodeWithTag("local-journal-submit").assertIsNotEnabled();assertEquals(0,removals)
            compose.onNodeWithTag("local-journal-acknowledge").performScrollTo().performClick()
            compose.onNodeWithTag("local-journal-confirmation").performScrollTo().performTextReplacement("REMOVE")
            screenshot("account-local-journal-review")
            compose.onNodeWithTag("local-journal-submit").performClick()
            compose.waitUntil(10000){removals==1}
            compose.waitUntil(10000){compose.onAllNodesWithTag("account-message").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("account-message").assertTextContains("Local journal removed", substring = true)
            screenshot("account-local-journal-removed")
            assertNull(storage.value.session);assertNull(storage.value.deletion)
        }finally{scope.cancel()}
    }
    @Test fun googleSignInButtonSignsInAndSettingsShowBothAuthenticators() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val storage=object : AccountStorage {
            var value=AccountState()
            override fun load()=value
            override fun save(value: AccountState) { this.value=value }
        }
        val fake=object : GoogleIdProvider {
            val nonces=mutableListOf<String>()
            override suspend fun authorize(host: GoogleSignInHost, nonce: String): String { nonces+=nonce; return "fake-google-id-token" }
        }
        val api=AccountAPI { path,body,_ ->
            if(path=="/v1/auth/provider/challenges") JSONObject().put("challengeId",body!!.getString("idempotencyId")).put("expiresInSeconds",900)
            else if(path=="/v1/auth/provider/verify") { require(body!!.getString("idToken")=="fake-google-id-token"); AccountSession("A".repeat(43),"A".repeat(43),"2100-01-01T00:00:00.000Z","2100-02-01T00:00:00.000Z","12345678-1234-4234-8234-123456789abc").json() }
            else { require(path=="/v1/me"); JSONObject().put("id","12345678-1234-4234-8234-123456789abc").put("email","cook@example.com").put("identities",JSONArray().put(JSONObject().put("provider","google"))) }
        }
        val account=AccountManager(storage,scope,api,true,googleWebClientId="synthetic-web-client.apps.googleusercontent.com",googleAdvertised=true,google=fake)
        try {
            compose.setContent { PitTechTheme { AccountContent(account) } }
            compose.onNodeWithTag("account-google-signin").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("account-signed-in").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("account-auth-email").assertTextContains("cook@example.com", substring=true)
            compose.onNodeWithTag("account-auth-google").assertIsDisplayed()
            assertEquals(1,fake.nonces.size)
        } finally { scope.cancel() }
    }
    @Test fun googleAttachLinksWhileSignedInAndButtonHidesWhenUnconfigured() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        fun storage(session: AccountSession?) = object : AccountStorage {
            var value=AccountState(session=session)
            override fun load()=value
            override fun save(value: AccountState) { this.value=value }
        }
        val fake=object : GoogleIdProvider {
            override suspend fun authorize(host: GoogleSignInHost, nonce: String) = "fake-google-id-token"
        }
        val api=AccountAPI { path,body,_ ->
            if(path=="/v1/auth/provider/challenges") JSONObject().put("challengeId",body!!.getString("idempotencyId")).put("expiresInSeconds",900)
            else if(path=="/v1/me/identities") JSONObject().put("identities",JSONArray().put(JSONObject().put("provider","google")))
            else { require(path=="/v1/me"); JSONObject().put("id","12345678-1234-4234-8234-123456789abc").put("email","cook@example.com").put("identities",JSONArray().put(JSONObject().put("provider","google"))) }
        }
        val session=AccountSession("A".repeat(43),"A".repeat(43),"2100-01-01T00:00:00.000Z","2100-02-01T00:00:00.000Z","12345678-1234-4234-8234-123456789abc")
        try {
            val signedIn=storage(session)
            val account=AccountManager(signedIn,scope,api,true,googleWebClientId="synthetic-web-client.apps.googleusercontent.com",googleAdvertised=true,google=fake)
            // One setContent per activity: the hidden matrix swaps the manager through
            // snapshot state under key(), which disposes the previous account subtree.
            var current by mutableStateOf<AccountManager>(account)
            compose.setContent { PitTechTheme { key(current) { AccountContent(current) } } }
            compose.onNodeWithTag("account-google-attach").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("account-auth-google").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(session.refreshToken,signedIn.value.session?.refreshToken)
            val hidden=listOf(
                AccountManager(storage(null),scope,api,false,googleWebClientId="synthetic-web-client.apps.googleusercontent.com",googleAdvertised=true,google=fake),
                AccountManager(storage(null),scope,api,true,googleWebClientId="synthetic-web-client.apps.googleusercontent.com",googleAdvertised=false,google=fake),
                AccountManager(storage(null),scope,api,true,googleWebClientId="",googleAdvertised=true,google=fake),
                AccountManager(storage(session),scope,api,true,googleWebClientId="",googleAdvertised=true,google=fake)
            )
            for(hiddenAccount in hidden) {
                compose.runOnIdle { current=hiddenAccount }
                compose.waitForIdle()
                compose.onAllNodesWithTag("account-google-signin").assertCountEquals(0)
                compose.onAllNodesWithTag("account-google-attach").assertCountEquals(0)
            }
        } finally { scope.cancel() }
    }
    private fun screenshot(name: String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val image=instrumentation.uiAutomation.takeScreenshot()
        val file=File(instrumentation.targetContext.filesDir,"pittech-ui-test/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
    }

    /** Slice 8 fixture: a signed-in account, a fake MCP API, and a real
     * SubscriptionManager over stub billing whose entitlement the test
     * flips between the server states unknown / none / active. */
    private class Slice8Fixture(val scope: CoroutineScope) {
        @Volatile var entitlementState = "unknown"
        val storage = object : AccountStorage {
            var value = AccountState(session = AccountSession("A".repeat(43), "A".repeat(43), "2100-01-01T00:00:00.000Z", "2100-02-01T00:00:00.000Z", "12345678-1234-4234-8234-123456789abc"))
            override fun load() = value
            override fun save(value: AccountState) { this.value = value }
        }
        val account = AccountManager(storage, scope, AccountAPI { _, _, _ -> JSONObject() }, true)
        val mcpApi = McpAPI { method, path, _, _ ->
            when {
                path.endsWith("describe") -> JSONObject().put("clientName", "ChatGPT").put("scopes", JSONArray(listOf("library:read"))).put("expiresAt", System.currentTimeMillis() + 900_000)
                path.endsWith("approve") -> JSONObject().put("approved", true)
                method == "POST" && path.endsWith("grants") -> JSONObject()
                    .put("grant", JSONObject().put("id", "grant-1").put("nickname", "ChatGPT").put("scopes", JSONArray(listOf("library:read"))).put("createdAt", 1).put("expiresAt", Long.MAX_VALUE / 4).put("revokedAt", JSONObject.NULL).put("lastUsedAt", JSONObject.NULL).put("clientId", JSONObject.NULL))
                    .put("token", "pitmcp_uitesttoken")
                else -> JSONObject().put("grants", JSONArray())
            }
        }
        val mcp = McpConnectionsManager(account, scope, mcpApi)
        val billing = object : com.pittech.billing.ProBillingClient {
            override val productId = "pittech.pro.monthly"
            override suspend fun queryProduct(): com.pittech.billing.ProStoreProduct? = null
            override suspend fun launchPurchase(activity: android.app.Activity?) = com.pittech.billing.ProPurchaseOutcome.Cancelled
            override suspend fun queryOwnedPurchases(): List<com.pittech.billing.ProStorePurchase> = emptyList()
            override suspend fun acknowledge(purchaseToken: String) = true
            override var externalPurchases: ((com.pittech.billing.ProStorePurchase) -> Unit)? = null
            override fun close() {}
        }
        val subApi = com.pittech.billing.SubscriptionAPI { path, _, _ ->
            JSONObject("""{"version":"entitlement-cache-v1","environment":"production","verified":${entitlementState != "unknown"},"state":"$entitlementState","verifiedUntil":"2026-11-17T12:00:00Z","lastVerifiedAt":"2026-10-18T12:00:00Z","offlineValidUntil":"2026-10-25T12:00:00Z","contractVersion":"store-verification-v1"}""")
        }
        val subscription = com.pittech.billing.SubscriptionManager(billing, scope, subApi, { "A".repeat(43) }, true, com.pittech.billing.MemorySubscriptionCacheStore(), ownerId = { "stub-account" })
    }

    /** Slice 8 (PL-14): with Pro unverified the section shows the checking
     * state and no create/approve controls; "Check again" re-reads the
     * entitlement and, once active, the controls appear. */
    @Test fun mcpSectionGatesOnProState() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val f = Slice8Fixture(scope)
        try {
            compose.setContent { PitTechTheme { AccountContent(f.account, mcp = f.mcp, subscription = f.subscription) } }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mcp-pro-checking").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("mcp-pro-checking").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithTag("mcp-new").assertCountEquals(0)
            compose.onAllNodesWithTag("mcp-review-code").assertCountEquals(0)
            f.entitlementState = "active"
            compose.onNodeWithTag("mcp-pro-check").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mcp-new").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("mcp-new").performScrollTo().assertIsDisplayed()
        } finally { scope.cancel() }
    }

    /** Slice 8 (PL-14): with Pro verified absent (server state "none" —
     * the canonical not-entitled state in the entitlement mapping), the
     * honest gate card replaces the create/approve controls entirely —
     * existing connections stay listed and disconnectable, but nothing
     * new can be created or approved until Pro is active. */
    @Test fun mcpSectionShowsProGateWhenNotEntitled() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val f = Slice8Fixture(scope)
        try {
            f.entitlementState = "none"
            kotlinx.coroutines.runBlocking { f.subscription.refresh() }
            compose.waitUntil(10000) { f.subscription.presentation.value.entitlement == com.pittech.ads.AdEntitlement.NOT_ENTITLED }
            compose.setContent { PitTechTheme { AccountContent(f.account, mcp = f.mcp, subscription = f.subscription) } }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mcp-pro-gate").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("mcp-pro-gate").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("mcp-pro-see").assertIsDisplayed()
            compose.onAllNodesWithTag("mcp-new").assertCountEquals(0)
            compose.onAllNodesWithTag("mcp-review-code").assertCountEquals(0)
        } finally { scope.cancel() }
    }

    /** Slice 8 (PL-16 / DD-9): creating a connection ends in the hard-stop
     * token dialog whose copy action confirms itself; approving a code
     * first shows the client and its scopes — Approve does not exist
     * until the review has happened. */
    @Test fun mcpTokenDialogAndApprovalConfirmation() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val f = Slice8Fixture(scope)
        try {
            f.entitlementState = "active"
            kotlinx.coroutines.runBlocking { f.subscription.refresh() }
            compose.waitUntil(10000) { f.subscription.presentation.value.entitlement == com.pittech.ads.AdEntitlement.ENTITLED }
            compose.setContent { PitTechTheme { AccountContent(f.account, mcp = f.mcp, subscription = f.subscription) } }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mcp-new").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("mcp-new").performScrollTo().performClick()
            compose.onNodeWithTag("mcp-create-submit").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mcp-created-token").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("mcp-token-value").assertTextEquals("pitmcp_uitesttoken")
            screenshot("mcp-token-dialog")
            compose.onNodeWithTag("mcp-copy-token").performClick()
            compose.onNodeWithText("Copied").assertIsDisplayed()
            compose.onNodeWithTag("mcp-token-done").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mcp-created-token").fetchSemanticsNodes().isEmpty() }
            compose.onAllNodesWithTag("mcp-create-submit").assertCountEquals(0)
            compose.onNodeWithTag("mcp-approve-code").performScrollTo().performTextReplacement("ABCD-2345")
            compose.onAllNodesWithTag("mcp-approve").assertCountEquals(0)
            compose.onNodeWithTag("mcp-review-code").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mcp-approval-preview").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Connect ChatGPT?", substring = true).assertIsDisplayed()
            // Scoped to the preview subtree: the created grant's row in
            // the list above carries the same scope label.
            compose.onNodeWithTag("mcp-approval-preview").onChildren().filterToOne(hasText("See your library", substring = true)).assertIsDisplayed()
            screenshot("mcp-approval-confirmation")
            compose.onNodeWithTag("mcp-approve").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mcp-approval-preview").fetchSemanticsNodes().isEmpty() }
        } finally { scope.cancel() }
    }
}
