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
            """{"ssid":"HomeWifi","password":"hunter2","nested":{"token":"abc"},"firmware":"0.6.0"}""",
        )

        assertFalse(sanitized.contains("HomeWifi"))
        assertFalse(sanitized.contains("hunter2"))
        assertFalse(sanitized.contains("\"abc\""))
        assertTrue(sanitized.contains("0.6.0"))
        assertTrue(sanitized.contains("[redacted]"))
    }

    @Test
    fun textSanitizerRedactsKeyValueSecretsButKeepsDiagnostics() {
        val sanitized = ControllerDiagnosticSanitizer.sanitizeText(
            "ssid=BackPorch password=hunter2 firmware=0.6.0 state=online",
        )

        assertFalse(sanitized.contains("BackPorch"))
        assertFalse(sanitized.contains("hunter2"))
        assertTrue(sanitized.contains("firmware=0.6.0"))
        assertTrue(sanitized.contains("state=online"))
    }
}
