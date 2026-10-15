package com.pittech.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RelayStorageTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun remove(value: RelayOwnedShare) {
        File(context.noBackupFilesDir, "relay-v1").listFiles().orEmpty().filter { it.name.startsWith(value.operation + ".") }.forEach { it.delete() }
    }
    @Test fun ownerCapabilitiesStayEncryptedAndPendingBytesSurviveVaultRestart() {
        val source = File.createTempFile("relay-instrument-", ".pittech", context.cacheDir).apply { writeText("immutable selected export") }
        val vault = RelayVault(context)
        val value = vault.prepare(UUID.randomUUID().toString(), 1, "Selected pork", source)
        try {
            assertEquals(43, value.owner.length)
            val restarted = RelayVault(context).load(value.document)!!
            assertEquals(value.owner, restarted.owner); assertArrayEquals(source.readBytes(), vault.pack(restarted).readBytes())
            val oldOrphan = File(context.noBackupFilesDir, "relay-v1/${UUID.randomUUID()}.pittech").apply { writeText("incomplete export"); check(setLastModified(System.currentTimeMillis() - 172_800_000L)) }
            val freshOrphan = File(context.noBackupFilesDir, "relay-v1/${UUID.randomUUID()}.pittech").apply { writeText("being prepared") }
            try {
                vault.prunePending()
                assertFalse(oldOrphan.exists()); assertTrue(freshOrphan.exists()); assertTrue(vault.pack(value).exists())
            } finally { oldOrphan.delete(); freshOrphan.delete() }
            val encrypted = File(context.noBackupFilesDir, "relay-v1/${value.operation}.owner")
            assertFalse(String(encrypted.readBytes(), Charsets.ISO_8859_1).contains(value.owner))
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("pittech-relay-owner-v1", null)
            assertNull(key.encoded)
            try { vault.prepare(value.document, 2, "Changed selection", source); fail("Pending immutable retry was overwritten") } catch (_: IllegalArgumentException) { }
        } finally { remove(value); source.delete() }
    }
    @Test fun issuingAnotherCodeRetainsManagementOfEveryEarlierCode() {
        val source = File.createTempFile("relay-instrument-", ".pittech", context.cacheDir).apply { writeText("selected export") }
        val vault = RelayVault(context); val first = vault.prepare(UUID.randomUUID().toString(), 1, "First selected pack", source)
        var second: RelayOwnedShare? = null
        try {
            val receipt = RelayReceipt("ABCD-1234-5678-9EF0", "2027-04-13T12:34:56.789Z", "a".repeat(64), 1024, "recipe", 1)
            vault.finish(first, receipt); assertFalse(vault.pack(first).exists())
            val next = vault.prepare(first.document, 2, "New selected pack", source); second = next
            val retained = RelayVault(context).all().filter { it.document == first.document }
            assertEquals(2, retained.size); assertEquals(first.owner, retained.single { it.operation == first.operation }.owner)
            assertNotEquals(first.owner, next.owner); assertEquals(next.operation, vault.load(first.document)!!.operation)
        } finally { remove(first); second?.let(::remove); source.delete() }
    }
}
