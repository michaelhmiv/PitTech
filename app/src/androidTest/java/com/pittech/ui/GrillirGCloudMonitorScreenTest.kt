package com.pittech.ui

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.FeedbackRequest
import com.pittech.FeedbackSubmitResult
import com.pittech.devices.PolarisDevice
import com.pittech.devices.PolarisExchange
import com.pittech.devices.PolarisMonitorEngine
import com.pittech.devices.PolarisMonitorState
import com.pittech.devices.PolarisOperation
import com.pittech.devices.PolarisPayload
import com.pittech.devices.PolarisPhase
import com.pittech.devices.PolarisSample
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GrillirGCloudMonitorScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ControllerDiagnosticsTestActivity>()

    @Test fun signInRequiresExplicitActionsAndFindsGrillWithoutBluetooth() {
        val engine = FakePolarisMonitor()
        composeRule.setContent { PitTechTheme { GrillirGCloudMonitorScreen(engineOverride = engine) } }
        composeRule.onNodeWithTag("cloud-request-code").assertIsNotEnabled()
        assertEquals(0, engine.requestedCodes)
        composeRule.onNodeWithTag("cloud-email").performTextInput("private@example.com")
        composeRule.onNodeWithTag("cloud-request-code").performClick()
        composeRule.onNodeWithTag("cloud-code").performScrollTo().performTextInput("123456")
        composeRule.onNodeWithTag("cloud-sign-in").performScrollTo().performClick()
        composeRule.onNodeWithTag("cloud-grill-status").performScrollTo().assertIsDisplayed().assertTextContains("Grill online")
        assertEquals(1, engine.requestedCodes)
        assertEquals("private@example.com", engine.email)
        assertEquals("123456", engine.code)
        assertTrue(engine.foreground)
    }

    @Test fun offlineDataIsMarkedAndPublicReportRequiresPreviewThenSubmission() {
        val engine = FakePolarisMonitor(connectedState(online = 1))
        var submitted: FeedbackRequest? = null
        composeRule.setContent {
            PitTechTheme {
                GrillirGCloudMonitorScreen(engineOverride = engine, submitter = ControllerReportSubmitter { request, callback ->
                    submitted = request
                    callback(FeedbackSubmitResult.Success(123))
                })
            }
        }
        composeRule.onNodeWithTag("cloud-stale").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("cloud-grill-status").assertTextContains("offline")
        composeRule.onNodeWithTag("cloud-review-report").performScrollTo().performClick()
        assertNull(submitted)
        composeRule.onNodeWithTag("cloud-cancel-report").performClick()
        assertNull(submitted)
        composeRule.onNodeWithTag("cloud-review-report").performScrollTo().performClick()
        composeRule.onNodeWithTag("cloud-submit-report").performClick()
        val json = submitted!!.toJson()
        assertTrue(json.contains("furnaceTempMeasured=225"))
        assertFalse(json.contains("private-device-id"))
        assertFalse(json.contains("private-device-name"))
        composeRule.onNodeWithText("Report submitted as GitHub issue #123.").performScrollTo().assertIsDisplayed()
    }

    @Test fun manualRefreshRecoversStaleViewAndSignOutClearsReadings() {
        val engine = FakePolarisMonitor(connectedState().copy(readingRequestFailed = true))
        composeRule.setContent { PitTechTheme { GrillirGCloudMonitorScreen(engineOverride = engine) } }
        composeRule.onNodeWithTag("cloud-stale").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("cloud-refresh").performScrollTo().performClick()
        composeRule.onNodeWithTag("cloud-stale").assertDoesNotExist()
        composeRule.onNodeWithTag("cloud-sign-out").performScrollTo().performClick()
        composeRule.onNodeWithTag("cloud-email").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("cloud-grill-status").assertDoesNotExist()
    }
}

private fun connectedState(online: Int = 0): PolarisMonitorState {
    val now = System.currentTimeMillis()
    val sample = PolarisSample(now, PolarisPayload(mapOf("furnaceTempMeasured" to 225.0, "tempUnit" to 0.0), listOf("furnaceTempMeasured", "tempUnit"), 0, 0))
    return PolarisMonitorState(
        phase = PolarisPhase.MONITORING, authenticated = true, message = "Monitoring your grill.",
        devices = listOf(PolarisDevice("private-device-id", "private-device-name", "PITBOSS", "Austin", "P7", "2.0")),
        selectedDeviceId = "private-device-id", latest = sample, samples = listOf(sample),
        onlineStatus = online, statusFetchedAtMillis = now, lastApiSuccessMillis = now,
        exchanges = listOf(PolarisExchange(now, PolarisOperation.READINGS, 50, 200, 10000)),
    )
}

private class FakePolarisMonitor(initial: PolarisMonitorState = PolarisMonitorState(phase = PolarisPhase.SIGNED_OUT)) : PolarisMonitorEngine {
    override val state = MutableStateFlow(initial)
    var requestedCodes = 0
    var email = ""
    var code = ""
    var foreground = false
    override fun setForeground(active: Boolean) { foreground = active }
    override fun requestCode(email: String) { requestedCodes++; this.email = email; state.value = state.value.copy(phase = PolarisPhase.CODE_SENT) }
    override fun signIn(email: String, code: String) { this.email = email; this.code = code; state.value = connectedState() }
    override fun reloadDevices() = Unit
    override fun selectDevice(id: String) = Unit
    override fun refresh() { state.value = connectedState() }
    override fun signOut() { state.value = PolarisMonitorState(phase = PolarisPhase.SIGNED_OUT) }
    override fun close() { foreground = false }
}
