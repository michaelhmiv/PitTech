package com.pittech

import androidx.activity.ComponentActivity
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
            compose.onNodeWithTag("sync-conflicts").performScrollTo().performClick()
            compose.onNodeWithText("Yours: Note \"Local title\"").assertIsDisplayed()
            compose.onNodeWithText("Other device: Note \"Remote title\"").assertIsDisplayed()
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
    private fun screenshot(name: String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val image=instrumentation.uiAutomation.takeScreenshot()
        val file=File(instrumentation.targetContext.filesDir,"pittech-ui-test/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
    }
}
