package com.pittech.ui

import com.pittech.data.CookStatus
import org.junit.Assert.*
import org.junit.Test

/**
 * Flowing E4 decision logic: finished cooks open on their story, and the
 * "Protect your cook log?" prompt fires only for the first finished cook
 * whose log is genuinely unprotected, once.
 */
class FlowingE4LogicTest {
    @Test fun finishedCooksOpenOnTheirStory() {
        assertEquals(CookTab.TIMELINE, initialCookTab(CookStatus.COMPLETED))
        assertEquals(CookTab.LIVE, initialCookTab(CookStatus.ACTIVE))
        assertEquals(CookTab.LIVE, initialCookTab(CookStatus.PAUSED))
    }

    @Test fun protectPromptNeedsFinishedUnprotectedAndUnanswered() {
        assertTrue(shouldPromptProtectLog(CookStatus.COMPLETED, promptShown = false, lastSyncedAt = 0L, automaticSync = false))
        // Already answered once — never again.
        assertFalse(shouldPromptProtectLog(CookStatus.COMPLETED, promptShown = true, lastSyncedAt = 0L, automaticSync = false))
        // A completed backup exists — the log is protected.
        assertFalse(shouldPromptProtectLog(CookStatus.COMPLETED, promptShown = false, lastSyncedAt = 1_700_000_000_000L, automaticSync = false))
        // Automatic sync is on — protection is arranged.
        assertFalse(shouldPromptProtectLog(CookStatus.COMPLETED, promptShown = false, lastSyncedAt = 0L, automaticSync = true))
        // Not a finished cook — nothing to prompt about yet.
        assertFalse(shouldPromptProtectLog(CookStatus.ACTIVE, promptShown = false, lastSyncedAt = 0L, automaticSync = false))
        assertFalse(shouldPromptProtectLog(CookStatus.PAUSED, promptShown = false, lastSyncedAt = 0L, automaticSync = false))
    }
}
