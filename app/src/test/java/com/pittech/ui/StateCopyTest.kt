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

    @Test fun conflictChoiceNotesStateImmediateAndNextSyncOnTheChoice() {
        // PL-16: each choice prints its own consequence before it is tapped —
        // it applies on this device right away, and the resolution uploads
        // on the next sync (previously only the sheet-level note said so).
        assertEquals(
            "Keep mine applies on this device right away — your version stays. The choice uploads on the next sync.",
            SyncStatusCopy.conflictChoiceNote("mine"),
        )
        assertEquals(
            "Use theirs applies on this device right away — the other device's version replaces yours. The choice uploads on the next sync.",
            SyncStatusCopy.conflictChoiceNote("theirs"),
        )
        assertEquals(
            "Keep both applies on this device right away — both versions stay, the other as a new entry. The choice uploads on the next sync.",
            SyncStatusCopy.conflictChoiceNote("both"),
        )
    }

    @Test fun conflictKeepBothUnavailableNamesTheKindAndWhy() {
        // PL-16: where Keep both cannot exist, the sheet says so for that
        // item instead of the button silently not being there.
        assertEquals(
            "Keep both isn't available for meal timing — a meal has one timing record, so choose one version.",
            SyncStatusCopy.conflictKeepBothUnavailable("lifecycles"),
        )
        assertEquals(
            "Keep both isn't available for a note temperature link — a reading links to one note, so choose one version.",
            SyncStatusCopy.conflictKeepBothUnavailable("logReadingLinks"),
        )
        assertEquals(
            "Keep both isn't available for this entry, so choose one version.",
            SyncStatusCopy.conflictKeepBothUnavailable("events"),
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

    // Slice 10 (PL-25 / PL-26 / PL-23): the unified state table grew the
    // Insights + Recipes empties, the DD-8 save-failure set, the feedback
    // set, the offline wording, and the Settings section order. These
    // literals are the cross-platform contract — StateCopy.swift pins the
    // same strings.
    @Test fun slice10UnifiedCopyIsPinned() {
        assertEquals("No cooks yet", EmptyStateCopy.INSIGHTS_TITLE)
        assertEquals("Finished cooks will show up here with their results, readings and statistics. Start a cook to begin your history.", EmptyStateCopy.INSIGHTS_BODY)
        assertEquals("Your cookbook starts here", EmptyStateCopy.RECIPES_TITLE)
        assertEquals("Save selected dishes from a finished meal here. The original cook stays in your meal history.", EmptyStateCopy.RECIPES_BODY)
        assertEquals("No matching recipes", EmptyStateCopy.RECIPES_FILTERED_TITLE)
        assertEquals("No recipes match these filters. Try another search or change the filters.", EmptyStateCopy.RECIPES_FILTERED_BODY)
        assertEquals("Couldn't save that change. Your cook log is unchanged on this device — try again.", ErrorCopy.SAVE_CHANGE_FAILED)
        assertEquals("Couldn't submit your feedback. What you wrote stays in the form — check your connection and try again.", FeedbackCopy.SUBMIT_FAILED)
        assertEquals("You're offline. Changes wait on this device until a connection returns.", OfflineCopy.BANNER)
        assertEquals("Offline — changes wait on this device until a connection returns.", OfflineCopy.CHANGES_WAIT)
        assertEquals(
            listOf("Appearance", "Units", "Cook notifications", "Your data", "Feedback", "Ads and privacy", "About"),
            SettingsCopy.SECTION_ORDER,
        )
        assertEquals("A full backup (ZIP) includes your cooks, log entries, readings, results, reminders, photos, playbooks, plans, checklists, equipment, and app preferences.", SettingsCopy.FULL_BACKUP_SCOPE)
        assertEquals("Checking PitTech Pro status…", LoadingCopy.CHECKING_PRO)
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
