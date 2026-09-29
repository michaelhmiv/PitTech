package com.pittech.devices

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerDiagnosticReportBuilderTest {
    @Test
    fun preservesMongooseUuidWhileRedactingUnseparatedMacAddress() {
        val mongooseUuid = "5f6d4f53-5f52-5043-5f53-56435f49445f"
        val sanitized = ControllerDiagnosticSanitizer.sanitizeText(
            "RPC service=$mongooseUuid device address 001122AABBCC",
        )

        assertTrue(sanitized.contains(mongooseUuid))
        assertFalse(sanitized.contains("001122AABBCC"))
        assertTrue(sanitized.contains("[bluetooth-address-redacted]"))
    }

    @Test
    fun priorityKeepsFingerprintGattIdentityAndRpcInventoryAheadOfVerboseLogs() {
        val evidence = ControllerDiagnosticSanitizer.sanitizeSections(
            listOf(
                RawDiagnosticSection("VERBOSE EVENTS", 1, "event-data-".repeat(500)),
                RawDiagnosticSection("CONTROLLER FINGERPRINT", 100, "Fingerprint: pittech:sha256:abc123"),
                RawDiagnosticSection("GATT INVENTORY", 90, "Service: Mongoose RPC UUID"),
                RawDiagnosticSection("RPC INVENTORY", 95, "RPC.List, PB.GetState, Sys.GetInfo"),
                RawDiagnosticSection("FIRMWARE", 100, "Firmware: 0.6.0"),
            ),
        )

        val report = ControllerDiagnosticReportBuilder.build(evidence, maxChars = 260)

        assertTrue(report.contains("CONTROLLER FINGERPRINT"))
        assertTrue(report.contains("pittech:sha256:abc123"))
        assertTrue(report.contains("RPC INVENTORY"))
        assertTrue(report.contains("GATT INVENTORY"))
        assertFalse(report.contains("event-data-event-data"))
        assertTrue(report.length <= 260)
    }
}
