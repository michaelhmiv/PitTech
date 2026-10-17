package com.pittech.data

/** Thrown when local journal removal is refused to protect an in-progress cook or recording. */
internal class LocalJournalRemovalBlocked(message: String) : IllegalStateException(message)

/**
 * Removes the device-local cook journal without touching the server account.
 *
 * Cleared (the whole journal):
 * - every [PitTechDatabase] table via `clearAllTables`: cooks, dishes, ingredients,
 *   timeline events, devices, probes, sensor readings, targets, cook results, photos,
 *   cook reminders, cook recordings, probe assignments, companion records and photos,
 *   log reading links, cook lifecycle, cook log drafts, target history, saved recipes,
 *   recipe revisions and assets, dish recipe sources, meal presets, activity timers,
 *   and reminder rules and occurrences.
 * - the journal's files via [PhotoStorage]: the `photos/` library directory and the
 *   whole `note-drafts/` staging directory.
 *
 * Deliberately excluded library-adjacent stores, with reasons:
 * - [AccountVault] (Keystore credentials, sessions, deletion proof/receipt records):
 *   authentication and deletion recovery must survive journal removal.
 * - [CommunityVault] (device block/report state): community safety state is separate
 *   from the cook journal.
 * - [PublicationOperationsStore] (account-bound publication operations): publishing
 *   retry state belongs to the account, not the journal.
 * - telemetry connection profiles (`telemetry-profiles-v1.json`): grill connection
 *   settings belong to the device; cook-scoped entries become inert once the cook
 *   rows are gone.
 * - `pittech-preferences` SharedPreferences: device display/unit settings, not
 *   journal content.
 */
internal class LocalJournalRemoval(
    private val database: PitTechDatabase,
    private val photos: PhotoStorage,
) {
    suspend fun remove() {
        if (database.cookDao().getAllCooks().any { it.status != CookStatus.COMPLETED })
            throw LocalJournalRemovalBlocked("Finish or stop the active cook first. Removing the local journal never interrupts a cook in progress.")
        if (database.recordingDao().getActiveRecording() != null)
            throw LocalJournalRemovalBlocked("Stop the in-progress recording first. Removing the local journal never interrupts a recording in progress.")
        database.clearAllTables()
        photos.clearJournalFiles()
    }
}
