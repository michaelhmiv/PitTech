package com.pittech.devices

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class ConnectedGrillSessionStoreTest {
    @Test fun providersUseIndependentEncryptedFilesKeysAndRefreshTokens() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stores = GrillProvider.entries.associateWith { AndroidPolarisSessionStore(context, it) }
        try {
            stores.forEach { (provider, store) ->
                store.save(PolarisSession("private-token-" + provider.name, 2_000_000_000_000L,
                    "private-id-" + provider.name, if (provider == GrillProvider.TRAEGER) "private-refresh" else null))
            }
            stores.forEach { (provider, store) -> assertEquals("private-token-" + provider.name, store.load()!!.token) }
            assertEquals("private-refresh", stores.getValue(GrillProvider.TRAEGER).load()!!.refreshToken)
            val pbFile = File(context.noBackupFilesDir, "pitboss-session-v1.bin")
            val tFile = File(context.noBackupFilesDir, "traeger-session-v1.bin")
            assertFalse(tFile.readBytes().toString(Charsets.ISO_8859_1).contains("private-refresh"))
            // A ciphertext copied from another provider cannot be accepted as that provider's account.
            pbFile.writeBytes(tFile.readBytes())
            try { stores.getValue(GrillProvider.PIT_BOSS).load(); fail("Cross-provider ciphertext accepted") } catch (_: java.security.GeneralSecurityException) {}
            stores.getValue(GrillProvider.PIT_BOSS).clear()
            assertNull(stores.getValue(GrillProvider.PIT_BOSS).load())
            assertNotNull(stores.getValue(GrillProvider.GRILLIRG).load())
            assertNotNull(stores.getValue(GrillProvider.TRAEGER).load())
        } finally { stores.values.forEach { it.clear() } }
    }
    @Test fun originalGrillirGEncryptionAndJsonRemainReadableAfterProviderUpgrade() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AndroidPolarisSessionStore(context)
        val file = File(context.noBackupFilesDir, "grillirg-session-v1.bin")
        try {
            store.save(PolarisSession("create-key"))
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("pittech-grillirg-session-v1", null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key)
                updateAAD("PitTech GrillirG session v1".toByteArray())
            }
            val legacy = JSONObject().put("token", "legacy-private-token").put("expires", 2_000_000_000_000L).put("selected", "legacy-grill").toString()
            file.writeBytes(cipher.iv + cipher.doFinal(legacy.toByteArray()))
            val restored = store.load()!!
            assertEquals("legacy-private-token", restored.token)
            assertEquals("legacy-grill", restored.selectedDeviceId)
            assertNull(restored.refreshToken)
        } finally { store.clear() }
    }
}

