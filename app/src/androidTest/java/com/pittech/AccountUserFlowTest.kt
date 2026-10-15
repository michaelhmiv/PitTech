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
            compose.onAllNodesWithText("Sign out").onLast().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("account-start").fetchSemanticsNodes().isNotEmpty() }
            assertNull(storage.value.session); screenshot("account-signed-out")
        } finally { scope.cancel() }
    }
    private fun screenshot(name: String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val image=instrumentation.uiAutomation.takeScreenshot()
        val file=File(instrumentation.targetContext.filesDir,"pittech-ui-test/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
    }
}
