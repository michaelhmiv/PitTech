package com.pittech.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.devices.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConnectedGrillProviderScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ControllerDiagnosticsTestActivity>()
    private class Engine(provider: GrillProvider) : PolarisMonitorEngine {
        override val state = MutableStateFlow(PolarisMonitorState(phase = PolarisPhase.SIGNED_OUT, provider = provider))
        var signIns = 0
        var email = ""
        var secret = ""
        override fun setForeground(active: Boolean) {}
        override fun selectProvider(provider: GrillProvider) { state.value = PolarisMonitorState(phase = PolarisPhase.SIGNED_OUT, provider = provider) }
        override fun requestCode(email: String) { error("These providers do not request email codes") }
        override fun signIn(email: String, code: String) {
            signIns++; this.email = email; secret = code
            val now = System.currentTimeMillis()
            val sample = PolarisSample(now, PolarisPayload(mapOf("tempUnit" to 0.0, "furnaceTempMeasured" to 225.0), emptyList(), 0, null))
            state.value = state.value.copy(authenticated = true, phase = PolarisPhase.MONITORING,
                devices = listOf(PolarisDevice("synthetic-owned", "Test grill", null, null, null, null, 4)),
                selectedDeviceId = "synthetic-owned", latest = sample, samples = listOf(sample), onlineStatus = 0, statusFetchedAtMillis = now)
        }
        override fun reloadDevices() {}
        override fun selectDevice(id: String) {}
        override fun refresh() {}
        override fun signOut() {}
        override fun close() {}
    }
    private fun choose(label: String) {
        compose.onNodeWithTag("cloud-provider").performScrollTo().performClick()
        compose.onNodeWithTag("cloud-provider-option-" + label.lowercase(java.util.Locale.ROOT).replace(' ', '-')).performClick()
    }
    @Test fun pitBossConnectsExplicitControllerIdWithOptionalPasswordAndNoEmailCode() {
        val engine = Engine(GrillProvider.GRILLIRG)
        compose.setContent { PitTechTheme { GrillirGCloudMonitorScreen(engineOverride = engine) } }
        choose(GrillProvider.PIT_BOSS.label)
        compose.onNodeWithTag("cloud-request-code").assertDoesNotExist()
        compose.onNodeWithTag("cloud-sign-in").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-email").performScrollTo().performTextInput("PBVA-Patio")
        assertEquals(0, engine.signIns)
        compose.onNodeWithTag("cloud-sign-in").performScrollTo().performClick()
        assertEquals(1, engine.signIns)
        assertEquals("PBVA-Patio", engine.email)
        assertEquals("", engine.secret)
        compose.onNodeWithTag("cloud-grill-status").performScrollTo().assertTextContains("Grill online")
    }
    @Test fun traegerRequiresExplicitPasswordAndProviderSwitchClearsIt() {
        val engine = Engine(GrillProvider.TRAEGER)
        compose.setContent { PitTechTheme { GrillirGCloudMonitorScreen(engineOverride = engine) } }
        compose.onNodeWithTag("cloud-request-code").assertDoesNotExist()
        compose.onNodeWithTag("cloud-email").performScrollTo().performTextInput("synthetic@example.test")
        compose.onNodeWithTag("cloud-sign-in").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-code").performScrollTo().performTextInput("private-password")
        assertEquals(0, engine.signIns)
        choose(GrillProvider.PIT_BOSS.label)
        choose(GrillProvider.TRAEGER.label)
        val value = compose.onNodeWithTag("cloud-code").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("", value)
        compose.onNodeWithTag("cloud-email").performScrollTo().performTextInput("synthetic@example.test")
        compose.onNodeWithTag("cloud-code").performScrollTo().performTextInput("private-password")
        compose.onNodeWithTag("cloud-sign-in").performScrollTo().performClick()
        assertEquals(1, engine.signIns)
        assertEquals("private-password", engine.secret)
        compose.onNodeWithText("Probe 4").performScrollTo().assertIsDisplayed()
    }
    @Test fun fourProbeCookSetupAssignsThirdAndFourthChannelsIndependently() {
        val device = PolarisDevice("traeger:synthetic", "Test Traeger", "Traeger", null, null, null, 4)
        val state = PolarisMonitorState(authenticated = true, devices = listOf(device), selectedDeviceId = device.id, provider = GrillProvider.TRAEGER)
        var third: String? = null
        var fourth: String? = null
        compose.setContent { PitTechTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                StartGrillRecordingPanel(state, true, {}, listOf("roast" to "Roast", "loin" to "Loin"),
                    null, null, {}, {}, {}, onProbe3 = { third = it }, onProbe4 = { fourth = it })
            }
        } }
        compose.onNodeWithTag("cook-probe3").performScrollTo().performClick()
        compose.onNodeWithTag("cook-probe3-dish-1").performClick()
        compose.onNodeWithTag("cook-probe4").performScrollTo().performClick()
        compose.onNodeWithTag("cook-probe4-dish-0").performClick()
        assertEquals("loin", third); assertEquals("roast", fourth)
        val screenshot = File(compose.activity.filesDir, "pittech-ui-test/provider-four-probe-setup.png").apply { parentFile?.mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().apply {
            screenshot.outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
    }
}

