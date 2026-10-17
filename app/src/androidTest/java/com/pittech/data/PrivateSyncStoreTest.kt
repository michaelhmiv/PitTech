package com.pittech.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class) class PrivateSyncStoreTest {
    private val userA = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
    private val userB = "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb"
    private fun dir(context: Context) = File(context.noBackupFilesDir, "private-sync-test-" + UUID.randomUUID())
    private fun row() = JSONObject().put("recordType", "events").put("recordId", "note-1")
        .put("version", JSONObject().put("counter", 1).put("clientId", userA)).put("deleted", false)
        .put("payload", JSONObject().put("id", "note-1")).put("assetIds", JSONArray())
    private fun state() = PrivateSyncState(account = userA, serverVersion = 3, maxCounter = 9,
        base = JSONArray().put(row()).toString(), conflicts = "[]", resolved = listOf("1".repeat(64)), pending = true,
        mutationId = "22222222-2222-4222-8222-222222222222", mutationSha = "2".repeat(64),
        photos = mapOf("photos|photo-1" to PrivateSyncPhotoEntry("33333333-3333-4333-8333-333333333333", "3".repeat(64), 10, "image/jpeg", listOf(0), true)),
        lastSyncedAt = 1000, outcome = "waiting", message = "Waiting for connection.")

    @Test fun syncJournalSurvivesRestartStaysAccountBoundAndPurgesCleanly() {
        val context = ApplicationProvider.getApplicationContext<Context>(); val directory = dir(context)
        try {
            val store = PrivateSyncStore(directory)
            val device = store.deviceId()
            assertEquals(device, PrivateSyncStore(directory).deviceId())
            store.save(userA, state())
            store.writeBody(userA, state().mutationId!!, "exact-bytes".toByteArray())
            // A restart is a new instance over the same directory: everything round-trips.
            val restarted = PrivateSyncStore(directory)
            assertEquals(device, restarted.deviceId())
            assertEquals(state().json().toString(), restarted.load(userA).json().toString())
            assertArrayEquals("exact-bytes".toByteArray(), restarted.readBody(userA, state().mutationId!!))
            // Another account never sees this journal.
            assertEquals(PrivateSyncState(account = userB).json().toString(), restarted.load(userB).json().toString())
            restarted.save(userB, PrivateSyncState(account = userB, serverVersion = 40))
            assertEquals(3, restarted.load(userA).serverVersion)
            restarted.purgeAccount(userA)
            assertEquals(PrivateSyncState(account = userA).json().toString(), restarted.load(userA).json().toString())
            assertEquals(40, restarted.load(userB).serverVersion)
            restarted.purgeAccount(userB)
            // Only the per-device identity remains; every account journal and body is gone.
            assertEquals(listOf("device.json"), directory.listFiles().orEmpty().map { it.name }.sorted())
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptJournalFailsClosedWithoutTouchingTheOtherAccount() {
        val context = ApplicationProvider.getApplicationContext<Context>(); val directory = dir(context)
        try {
            val store = PrivateSyncStore(directory)
            store.save(userA, state()); store.save(userB, PrivateSyncState(account = userB, serverVersion = 7))
            File(directory, RelayProtocol.hash(userA.toByteArray()) + ".json").writeBytes("{\"version\":1,\"account\":\"tampered\"}".toByteArray())
            assertThrows(Exception::class.java) { PrivateSyncStore(directory).load(userA) }
            // The surviving account still loads; repair is an explicit purge, never silent.
            assertEquals(7, PrivateSyncStore(directory).load(userB).serverVersion)
        } finally { directory.deleteRecursively() }
    }
}
