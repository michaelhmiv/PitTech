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
