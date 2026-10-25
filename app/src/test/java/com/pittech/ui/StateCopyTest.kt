package com.pittech.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 7 (PL-10 / PL-13, DD-8): the sync status row and the canonical empty
 * states. These literals are the cross-platform contract — StateCopy.swift on
 * iOS pins the same strings in its own unit tests. */
class StateCopyTest {
    @Test fun neverSyncedRowNamesTheFirstConnectionWait() {
        val row = SyncStatusCopy.row("Last synced never", null, 0, 0, signedIn = true, enabled = true)
        assertEquals("Last synced never", row.headline)
        assertEquals(
            "Waiting for first connection — nothing has been backed up yet. Your journal is safe on this device. Tap Sync now to make the first backup.",
            row.body,
        )
    }

    @Test fun signedOutRowOffersSignInAsTheRecovery() {
        val row = SyncStatusCopy.row("Sync is off", null, 0, 0, signedIn = false, enabled = true)
        assertEquals("Sign in to start private sync", row.headline)
        assertTrue(row.body.startsWith("Nothing is uploaded while you are signed out"))
        assertTrue(row.body.contains("Sign in above, then tap Sync now to make the first backup."))
    }

    @Test fun disabledRowIsHonestAboutTheBuild() {
        val row = SyncStatusCopy.row("Sync is off", null, 0, 0, signedIn = false, enabled = false)
        assertEquals("Sync is off", row.headline)
        assertEquals(
            "Private sync is not available on this build. Your journal stays on this device and nothing is uploaded.",
            row.body,
        )
    }

    @Test fun conflictRowCountsTheActualPendingSet() {
        val row = SyncStatusCopy.row("Conflicts to review", null, 123, 3, signedIn = true, enabled = true)
        assertEquals("Conflicts to review", row.headline)
        assertTrue(row.body.contains("Two devices changed the same 3 items."))
        assertTrue(row.body.contains("replaces this device's unsynced changes to 3 items"))
    }

    @Test fun conflictConsequenceSingularAndPlural() {
        assertEquals(
            "Choosing “Use theirs” for it replaces this device's unsynced changes to 1 item. Each choice is recorded on this device now and uploads on the next sync.",
            SyncStatusCopy.conflictConsequence(1),
        )
        assertEquals(
            "Choosing “Use theirs” for all 4 replaces this device's unsynced changes to 4 items. Each choice is recorded on this device now and uploads on the next sync.",
            SyncStatusCopy.conflictConsequence(4),
        )
    }

    @Test fun waitingRowPrefersTheCoordinatorDetail() {
        val row = SyncStatusCopy.row("Waiting for connection", "Waiting for connection. Photo uploads resume where they stopped.", 0, 0, signedIn = true, enabled = true)
        assertEquals("Waiting for connection. Photo uploads resume where they stopped.", row.body)
        val fallback = SyncStatusCopy.row("Waiting for connection", null, 0, 0, signedIn = true, enabled = true)
        assertTrue(fallback.body.contains("Sync tries again when a connection returns"))
    }

    @Test fun retryRowNamesTheRetryAction() {
        val row = SyncStatusCopy.row("Retry needed", null, 99, 0, signedIn = true, enabled = true)
        assertEquals("Your journal is unchanged on this device. Tap Sync now to retry.", row.body)
    }

    @Test fun syncedRowDoesNotOverclaimCurrency() {
        val row = SyncStatusCopy.row("Last synced Oct 24, 2026", null, 99, 0, signedIn = true, enabled = true)
        assertEquals("Changes you make now stay on this device until the next sync uploads them.", row.body)
    }

    @Test fun emptyStatesTeachWhatBelongsAndWhatCreatesIt() {
        assertEquals("Notes, temperature checks and photos you add during the cook will show up here.", EmptyStateCopy.TIMELINE_BODY)
        assertEquals("Photos you add to this cook will show up here. Add one with the Photo button on the cook.", EmptyStateCopy.PHOTOS_EMPTY_BODY)
        assertEquals("No shared codes yet. Open a saved recipe or meal, choose Share, then Share by code.", EmptyStateCopy.SHARED_CODES_EMPTY)
        assertEquals("No connections yet. Add one below, then attach it to a cook on the next step.", EmptyStateCopy.CONNECTIONS_EMPTY)
        assertEquals("Publishers you block from a publication or profile will be listed here, and their publications stay hidden.", EmptyStateCopy.BLOCKS_BODY)
        assertEquals("Receipts for reports you send about a publication or profile will show up here.", EmptyStateCopy.REPORTS_BODY)
        assertEquals("Named publications from this cook will show up here.", EmptyStateCopy.PROFILE_PUBLICATIONS_BODY)
    }
}
