package com.pittech.devices

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControllerDiagnosticSanitizerTest {
    @Test
    fun jsonSanitizerRedactsCommonSecrets() {
        val sanitized = ControllerDiagnosticSanitizer.sanitizeJson(
            """{"ssid":"HomeWifi","password":"hunter2","nested":{"token":"abc"},"mac":"AA:BB:CC:DD:EE:FF","firmware":"0.6.0"}""",
        )

        assertFalse(sanitized.contains("HomeWifi"))
        assertFalse(sanitized.contains("hunter2"))
        assertFalse(sanitized.contains("\"abc\""))
        assertFalse(sanitized.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(sanitized.contains("0.6.0"))
        assertTrue(sanitized.contains("[redacted]"))
    }

    @Test
    fun nestedJsonArraysAndCaseVariationsAreRedactedWithoutLosingSafeMetadata() {
        val sanitized = ControllerDiagnosticSanitizer.sanitizeJson(
            """{"nested":[{"sSiD":"Home Network","AUTH":{"WiFi.Password":"grill-secret"},"private key":"pem-value"}],"firmware":"0.6.0","diagnostic":"SSID: Guest WiFi"}""",
        )

        assertFalse(sanitized.contains("Home Network"))
        assertFalse(sanitized.contains("grill-secret"))
        assertFalse(sanitized.contains("pem-value"))
        assertFalse(sanitized.contains("Guest WiFi"))
        assertTrue(sanitized.contains("0.6.0"))
        assertTrue(sanitized.startsWith("{"))
    }

    @Test
    fun textSanitizerRedactsKeyValueSecretsButKeepsDiagnostics() {
        val sanitized = ControllerDiagnosticSanitizer.sanitizeText(
            "ssid=Back Porch Network password=hunter2 firmware=0.6.0 state=online",
        )

        assertFalse(sanitized.contains("BackPorch"))
        assertFalse(sanitized.contains("Back Porch Network"))
        assertFalse(sanitized.contains("hunter2"))
        assertTrue(sanitized.contains("firmware=0.6.0"))
        assertTrue(sanitized.contains("state=online"))
    }

    @Test
    fun rawDebugTextRedactsLongWifiNamesCredentialsAndDeviceAddresses() {
        val sanitized = ControllerDiagnosticSanitizer.sanitizeText(
            "SSID: Back Porch Network; password is HoneySmoker7, token=private-token; Bluetooth address=AA:BB:CC:DD:EE:FF",
        )

        assertFalse(sanitized.contains("Back Porch Network"))
        assertFalse(sanitized.contains("HoneySmoker7"))
        assertFalse(sanitized.contains("private-token"))
        assertFalse(sanitized.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(sanitized.contains("[bluetooth-address-redacted]"))
    }

    @Test
    fun systemAndDebugOutputRedactsLocalIpAddresses() {
        val sanitized = ControllerDiagnosticSanitizer.sanitizeJson(
            """{"ip":"192.168.1.42","ipv6":"fe80::1234:5678","firmware":"0.6.0","debug":"http://10.0.0.8/rpc","time":"12:34:56"}""",
        )

        assertFalse(sanitized.contains("192.168.1.42"))
        assertFalse(sanitized.contains("10.0.0.8"))
        assertFalse(sanitized.contains("fe80::1234:5678"))
        assertTrue(sanitized.contains("[ip-address-redacted]"))
        assertTrue(sanitized.contains("0.6.0"))
        assertTrue(sanitized.contains("12:34:56"))
    }

    @Test
    fun publicDiagnosticReportWithholdsArbitraryNamesAndRawPayloadValues() {
        val report = ControllerProbeDiagnostics.details(
            summary = BluetoothScanSummary(
                startedAtUtc = "2026-09-28 15:00:00 UTC",
                finishedAtUtc = "2026-09-28 15:00:02 UTC",
                durationMillis = 2_000,
                scanMode = "LOW_LATENCY",
                totalResults = 1,
                capturedDeviceCount = 1,
                omittedDeviceCount = 0,
                error = null,
            ),
            selectedDevice = NearbyBluetoothDevice(
                key = "AA:BB:CC:DD:EE:FF",
                advertisedName = "Jamie’s Backyard Grill",
                address = "AA:BB:CC:DD:EE:FF",
                rssi = -44,
                advertisements = listOf(
                    BluetoothAdvertisementVariant(
                        firstSeenOffsetMillis = 0,
                        lastSeenOffsetMillis = 0,
                        observationCount = 1,
                        weakestRssi = -44,
                        strongestRssi = -44,
                        advertisedName = "Jamie’s Backyard Grill",
                        txPower = null,
                        connectable = true,
                        advertiseFlags = 6,
                        primaryPhy = 1,
                        secondaryPhy = null,
                        advertisingSid = null,
                        periodicAdvertisingInterval = null,
                        dataStatus = 0,
                        serviceUuids = emptyList(),
                        serviceSolicitationUuids = emptyList(),
                        manufacturerData = mapOf("0x1234" to "486F6D6557696669"),
                        serviceData = emptyMap(),
                        rawRecordHex = "486F6D6557696669",
                    ),
                ),
                omittedAdvertisementVariants = 0,
            ),
            inspection = null,
            probe = null,
        )

        assertFalse(report.contains("Jamie’s Backyard Grill"))
        assertFalse(report.contains("486F6D6557696669"))
        assertFalse(report.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(report.contains("present (value withheld for privacy)"))
        assertTrue(report.contains("sha256="))
    }
}
