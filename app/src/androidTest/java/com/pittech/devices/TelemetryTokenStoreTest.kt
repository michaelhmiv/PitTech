package com.pittech.devices

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TelemetryTokenStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun encryptedTokensAreIsolatedNeverWrittenPlaintextAndNotBackedUp() {
        val store = TelemetryTokenStore(context)
        val meater = "test-account-meater"
        val home = "test-home-assistant"
        val secret1 = "private-meater-token-987654"
        val secret2 = "private-home-token-123456"
        try {
            store.save("meater", meater, secret1)
            store.save("home_assistant", home, secret2)
            assertEquals(secret1, store.load("meater", meater))
            assertEquals(secret2, store.load("home_assistant", home))
            assertNull(store.load("meater", home))
            val files = context.noBackupFilesDir.listFiles()?.filter {
                it.name.startsWith("telemetry-") && it.name.endsWith(".bin")
            }.orEmpty()
            assertTrue(files.isNotEmpty())
            assertTrue(files.none { it.readBytes().toString(Charsets.ISO_8859_1).contains(secret1) })
            assertTrue(files.none { it.readBytes().toString(Charsets.ISO_8859_1).contains(secret2) })
            assertTrue(files.none { it.name.contains(meater) || it.name.contains(home) })
        } finally {
            store.clear("meater", meater)
            store.clear("home_assistant", home)
        }
    }

    @Test fun invalidAndUnsupportedTokensAreRejectedWithoutFallback() {
        val store = TelemetryTokenStore(context)
        try { store.save("grillirg", "legacy", "secret"); fail("Legacy provider accepted") }
        catch (_: IllegalArgumentException) {}
        try { store.save("meater", "invalid", "password contains spaces"); fail("Whitespace token accepted") }
        catch (_: IllegalArgumentException) {}
    }
}
