package com.pittech.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.FeedbackKind
import com.pittech.FeedbackRequest
import com.pittech.FeedbackSubmitResult
import com.pittech.devices.BluetoothAdvertisementVariant
import com.pittech.devices.BluetoothGattCharacteristicInfo
import com.pittech.devices.BluetoothGattDescriptorInfo
import com.pittech.devices.BluetoothGattInspectionReport
import com.pittech.devices.BluetoothGattReadResult
import com.pittech.devices.BluetoothGattServiceInfo
import com.pittech.devices.BluetoothScanSummary
import com.pittech.devices.CapabilityState
import com.pittech.devices.ControllerProbeReport
import com.pittech.devices.ControllerProtocolDetector
import com.pittech.devices.ControllerProtocolFamily
import com.pittech.devices.ControllerTransportCapability
import com.pittech.devices.ControllerTransportType
import com.pittech.devices.NearbyBluetoothDevice
import com.pittech.devices.ProbeStepState
import com.pittech.devices.RpcProbeObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControllerDiagnosticsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ControllerDiagnosticsTestActivity>()

    @Test
    fun selectedControllerIsAutomaticallyInterrogatedAndReportCanBeReviewedSubmittedOrCanceled() {
        val engine = FakeControllerDiagnosticsEngine(knownMongoose = true)
        var submittedRequest: FeedbackRequest? = null
        val submitter = ControllerReportSubmitter { request, onResult ->
            submittedRequest = request
            onResult(FeedbackSubmitResult.Success(812))
        }
        composeRule.setContent {
            PitTechTheme {
                ControllerDiagnosticsScreen(engineOverride = engine, submitterOverride = submitter)
            }
        }

        composeRule.onNodeWithText("Controller diagnostics").assertIsDisplayed()
        composeRule.onNodeWithText("Scan for controllers").assertIsDisplayed()
        composeRule.onNodeWithTag("controller-scan").assertHeightIsAtLeast(56.dp).performClick()
        composeRule.onNodeWithTag("controller-candidate").assertIsDisplayed().performClick()

        assertTrue(engine.inspectionStarted)
        assertTrue(engine.protocolProbeStarted)
        composeRule.onNodeWithText("Controller detected").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Protocol: Mongoose OS RPC over Bluetooth").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Controller features discovered: 3").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Test relay", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("relay ID candidate", substring = true).assertCountEquals(0)

        composeRule.onNodeWithTag("controller-submit-diagnostics").performScrollTo().performClick()
        composeRule.onNodeWithText("Review controller diagnostics").assertIsDisplayed()
        composeRule.onNodeWithTag("controller-report-preview").assertTextContains("RPC INVENTORY")
        composeRule.onNodeWithTag("controller-report-preview").assertTextContains("PBL (device suffix withheld)")
        composeRule.onNodeWithTag("controller-report-preview").assertTextContains("sha256=")
        composeRule.onNodeWithTag("controller-report-cancel").performClick()
        composeRule.onAllNodesWithText("Review controller diagnostics").assertCountEquals(0)
        assertEquals(null, submittedRequest)

        composeRule.onNodeWithTag("controller-submit-diagnostics").performScrollTo().performClick()
        composeRule.onNodeWithTag("controller-report-submit").performClick()
        assertEquals(FeedbackKind.DEVICE_DIAGNOSTIC, submittedRequest?.kind)
        val publicDetails = submittedRequest?.diagnosticReport?.details.orEmpty()
        assertTrue(publicDetails.contains("RPC INVENTORY"))
        assertFalse(publicDetails.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(publicDetails.contains("Session device ID:"))
        composeRule.onNodeWithText("Controller diagnostics submitted as GitHub issue #812.").assertIsDisplayed()
    }

    @Test
    fun controllerWithoutKnownProtocolIsShownAsInspectedAndUnverified() {
        val engine = FakeControllerDiagnosticsEngine(knownMongoose = false)
        composeRule.setContent {
            PitTechTheme {
                ControllerDiagnosticsScreen(engineOverride = engine)
            }
        }

        composeRule.onNodeWithTag("controller-scan").performClick()
        composeRule.onNodeWithTag("controller-candidate").performClick()
        composeRule.onNodeWithText("Controller inspected").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Protocol: Unknown").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("PitTech support: Not yet verified by PitTech").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Submit controller for support").performScrollTo().assertIsDisplayed()
    }
}

private class FakeControllerDiagnosticsEngine(
    private val knownMongoose: Boolean,
) : ControllerDiagnosticsEngine {
    override val requiresBluetoothPermissions: Boolean = false
    var inspectionStarted = false
    var protocolProbeStarted = false
    private val device = NearbyBluetoothDevice(
        key = "fake-controller-key",
        advertisedName = "PBL-ABC123",
        address = "AA:BB:CC:DD:EE:FF",
        rssi = -52,
        advertisements = listOf(
            BluetoothAdvertisementVariant(
                firstSeenOffsetMillis = 0,
                lastSeenOffsetMillis = 1_000,
                observationCount = 2,
                weakestRssi = -56,
                strongestRssi = -52,
                advertisedName = "PBL-ABC123",
                txPower = null,
                connectable = true,
                advertiseFlags = 6,
                primaryPhy = 1,
                secondaryPhy = null,
                advertisingSid = null,
                periodicAdvertisingInterval = null,
                dataStatus = 0,
                serviceUuids = listOf(ControllerProtocolDetector.MONGOOSE_RPC_SERVICE),
                serviceSolicitationUuids = emptyList(),
                manufacturerData = mapOf("0x1234" to "AABB"),
                serviceData = emptyMap(),
                rawRecordHex = "020106",
            ),
        ),
        omittedAdvertisementVariants = 0,
    )

    override fun startScan(
        onDevices: (List<NearbyBluetoothDevice>) -> Unit,
        onFinished: (BluetoothScanSummary, String?) -> Unit,
    ) {
        onDevices(listOf(device))
        onFinished(
            BluetoothScanSummary(
                startedAtUtc = "2026-09-28 12:00:00 UTC",
                finishedAtUtc = "2026-09-28 12:00:01 UTC",
                durationMillis = 1_000,
                scanMode = "LOW_LATENCY",
                totalResults = 2,
                capturedDeviceCount = 1,
                omittedDeviceCount = 0,
                error = null,
            ),
            null,
        )
    }

    override fun stopScan() = Unit

    override fun inspect(
        address: String,
        onProgress: (String) -> Unit,
        onFinished: (BluetoothGattInspectionReport) -> Unit,
    ) {
        inspectionStarted = true
        onProgress("Bluetooth services discovered")
        onFinished(
            BluetoothGattInspectionReport(
                address = address,
                startedAtUtc = "2026-09-28 12:00:01 UTC",
                finishedAtUtc = "2026-09-28 12:00:02 UTC",
                outcome = "GATT service inspection completed.",
                connected = true,
                connectionStatusCode = 0,
                serviceDiscoveryStatusCode = 0,
                services = listOf(
                    BluetoothGattServiceInfo(
                        uuid = if (knownMongoose) ControllerProtocolDetector.MONGOOSE_RPC_SERVICE else "0000180F-0000-1000-8000-00805F9B34FB",
                        kind = "primary",
                        characteristics = listOf(
                            BluetoothGattCharacteristicInfo(
                                uuid = ControllerProtocolDetector.MONGOOSE_RPC_DATA,
                                properties = listOf("READ", "WRITE"),
                                permissions = listOf("READ", "WRITE"),
                                descriptors = listOf(BluetoothGattDescriptorInfo("00002902-0000-1000-8000-00805F9B34FB", listOf("READ"))),
                            ),
                        ),
                    ),
                ),
                totalCharacteristicCount = 1,
                readableCharacteristicCount = 1,
                omittedReadableCharacteristicCount = 0,
                reads = listOf(
                    BluetoothGattReadResult(
                        serviceUuid = ControllerProtocolDetector.MONGOOSE_RPC_SERVICE,
                        characteristicUuid = ControllerProtocolDetector.MONGOOSE_RPC_DATA,
                        initiated = true,
                        statusCode = 0,
                        valueLengthBytes = 4,
                        valueHex = "7077733D",
                        valueText = "psw=",
                        truncatedValueBytes = 0,
                        note = null,
                    ),
                ),
                events = emptyList(),
                omittedEventCount = 0,
            ),
        )
    }

    override fun probe(
        address: String,
        inspection: BluetoothGattInspectionReport,
        onProgress: (String) -> Unit,
        onFinished: (ControllerProbeReport) -> Unit,
    ) {
        protocolProbeStarted = true
        onProgress("Controller capabilities checked")
        onFinished(
            ControllerProbeReport(
                address = address,
                startedAtUtc = "2026-09-28 12:00:02 UTC",
                finishedAtUtc = "2026-09-28 12:00:03 UTC",
                outcome = "Automatic observational controller interrogation completed.",
                protocols = if (knownMongoose) setOf(ControllerProtocolFamily.MONGOOSE_RPC) else setOf(ControllerProtocolFamily.UNKNOWN),
                rpcMethods = if (knownMongoose) listOf("RPC.Ping", "RPC.List", "Sys.GetInfo") else emptyList(),
                rpcDescriptions = emptyMap(),
                observations = if (knownMongoose) listOf(
                    RpcProbeObservation("Ping", "RPC.Ping", ProbeStepState.SUCCESS, response = "{}"),
                ) else emptyList(),
                debugMessages = emptyList(),
                transportCapabilities = listOf(
                    ControllerTransportCapability(ControllerTransportType.BLE, CapabilityState.SUPPORTED, "Mongoose RPC exchange succeeded."),
                    ControllerTransportCapability(ControllerTransportType.LOCAL_HTTP, CapabilityState.POSSIBLE, "HTTP capability evidence found."),
                    ControllerTransportCapability(ControllerTransportType.VENDOR_RELAY, CapabilityState.UNKNOWN, "Not tested."),
                ),
                events = emptyList(),
                omittedEventCount = 0,
            ),
        )
    }

    override fun close() = Unit
}
