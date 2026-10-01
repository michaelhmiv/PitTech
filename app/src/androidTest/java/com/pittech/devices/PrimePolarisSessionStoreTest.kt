package com.pittech.devices

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PrimePolarisSessionStoreTest {
    @Test fun savedSessionIsEncryptedAndCanBeReopenedThenRemoved() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = AndroidPolarisSessionStore(context)
        val file = File(context.noBackupFilesDir, "grillirg-session-v1.bin")
        try {
            store.clear()
            store.save(PolarisSession("private-bearer-token", 2_000_000_000_000L, "private-device-id"))
            val stored = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(stored.contains("private-bearer-token"))
            assertFalse(stored.contains("private-device-id"))
            assertEquals("private-bearer-token", store.load()!!.token)
            assertEquals("private-device-id", store.load()!!.selectedDeviceId)
            store.clear()
            assertNull(store.load())
            assertFalse(file.exists())
        } finally { store.clear() }
    }

    @Test fun alteredCiphertextCannotBeReadAsASession() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = AndroidPolarisSessionStore(context)
        val file = File(context.noBackupFilesDir, "grillirg-session-v1.bin")
        try {
            store.save(PolarisSession("private-bearer-token"))
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            var rejected = false
            try { store.load() } catch (_: Exception) { rejected = true }
            assertTrue(rejected)
        } finally { store.clear() }
    }
}
