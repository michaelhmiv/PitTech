package com.pittech.ui

/**
 * #172 Slice 7 (PL-10 / PL-13, DD-8): one wording per state, identical on both
 * platforms. The iOS mirror is `StateCopy.swift`; unit tests on both platforms
 * pin the exact strings so the two cannot drift apart.
 *
 * Sync copy is presentation only: it derives from the coordinator's existing
 * presentation fields and never changes sync behaviour, conflict semantics or
 * enablement. Every sentence follows DD-8 — what happened, what is still safe,
 * and the recovery action — and only promises what the engine already does:
 * a resolution is recorded on this device immediately (the coordinator saves
 * it with `pending = true`) and uploads on the next sync; a never-completed
 * sync has uploaded nothing.
 */
internal data class SyncStatusRowCopy(val headline: String, val body: String)

internal object SyncStatusCopy {
    /** The status row Account shows for private sync, per state. [status] is the
     * coordinator's existing status string and stays the headline, except for the
     * signed-out state, where the coordinator's default "Sync is off" would
     * misdescribe an enabled-but-signed-out sync. */
    fun row(status: String, detail: String?, lastSyncedAt: Long, conflictCount: Int, signedIn: Boolean, enabled: Boolean): SyncStatusRowCopy = when {
        !enabled -> SyncStatusRowCopy(
            status,
            "Private sync is not available on this build. Your journal stays on this device and nothing is uploaded.",
        )
        conflictCount > 0 -> SyncStatusRowCopy(
            status,
            "Two devices changed the same ${items(conflictCount)}. Nothing is overwritten until you choose. " +
                conflictConsequence(conflictCount),
        )
        status == "Waiting for connection" -> SyncStatusRowCopy(
            status,
            detail ?: "Your changes stay on this device. Sync tries again when a connection returns — or tap Sync now to try now.",
        )
        status == "Retry needed" -> SyncStatusRowCopy(
            status,
            detail ?: "Your journal is unchanged on this device. Tap Sync now to retry.",
        )
        !signedIn -> SyncStatusRowCopy(
            "Sign in to start private sync",
            "Nothing is uploaded while you are signed out, and your journal stays on this device. Sign in above, then tap Sync now to make the first backup.",
        )
        lastSyncedAt <= 0 -> SyncStatusRowCopy(
            status,
            "Waiting for first connection — nothing has been backed up yet. Your journal is safe on this device. Tap Sync now to make the first backup.",
        )
        else -> SyncStatusRowCopy(
            status,
            detail ?: "Changes you make now stay on this device until the next sync uploads them.",
        )
    }

    /** The concrete consequence of resolving the whole pending conflict set,
     * computed from the actual number of conflicts awaiting review. */
    fun conflictConsequence(conflictCount: Int): String =
        "Choosing “Use theirs” for ${all(conflictCount)} replaces this device's unsynced changes to ${items(conflictCount)}. " +
            "Each choice is recorded on this device now and uploads on the next sync."

    /** Shown under each conflict's two summaries, next to its choices. */
    const val CONFLICT_ITEM_NOTE: String =
        "Keep mine keeps this device's version of this item. Use theirs replaces it with the other device's version."

    /** PL-16: the consequence printed on a choice itself, before it is
     * tapped — the choice applies on this device immediately, and the
     * resolution uploads on the next sync (the coordinator records every
     * resolution with `pending = true`; see the header note). */
    fun conflictChoiceNote(choice: String): String = when (choice) {
        "mine" -> "Keep mine applies on this device right away — your version stays. The choice uploads on the next sync."
        "theirs" -> "Use theirs applies on this device right away — the other device's version replaces yours. The choice uploads on the next sync."
        else -> "Keep both applies on this device right away — both versions stay, the other as a new entry. The choice uploads on the next sync."
    }

    /** PL-16: where Keep both cannot exist for a record kind, the sheet says
     * so for that item instead of the button silently not being there. The
     * two kinds are the projection's link/singleton keys (see
     * PrivateSyncProtocol.keepBothUnavailableTypes). */
    fun conflictKeepBothUnavailable(recordType: String): String = when (recordType) {
        "lifecycles" -> "Keep both isn't available for meal timing — a meal has one timing record, so choose one version."
        "logReadingLinks" -> "Keep both isn't available for a note temperature link — a reading links to one note, so choose one version."
        else -> "Keep both isn't available for this entry, so choose one version."
    }

    private fun items(count: Int): String = if (count == 1) "1 item" else "$count items"
    private fun all(count: Int): String = if (count == 1) "it" else "all $count"
}

/** Canonical empty-state copy (PL-13): what belongs on the surface, plus the
 * action that creates it where the viewer owns that action. One wording per
 * surface, identical on both platforms. */
internal object EmptyStateCopy {
    const val TIMELINE_TITLE: String = "No cook history yet"
    const val TIMELINE_BODY: String = "Notes, temperature checks and photos you add during the cook will show up here."
    const val TIMELINE_FILTERED_TITLE: String = "No matching entries"
    const val TIMELINE_FILTERED_BODY: String = "Nothing here matches this filter yet. Choose another filter, or add an entry and it will show up here."
    const val PHOTOS_EMPTY_BODY: String = "Photos you add to this cook will show up here. Add one with the Photo button on the cook."
    const val CONNECTIONS_EMPTY: String = "No connections yet. Add one below, then attach it to a cook on the next step."
    const val SHARED_CODES_EMPTY: String = "No shared codes yet. Open a saved recipe or meal, choose Share, then Share by code."
    const val BLOCKS_TITLE: String = "No publishers blocked on this device."
    const val BLOCKS_BODY: String = "Publishers you block from a publication or profile will be listed here, and their publications stay hidden."
    const val REPORTS_TITLE: String = "No report receipts on this device."
    const val REPORTS_BODY: String = "Receipts for reports you send about a publication or profile will show up here."
    const val PROFILE_PUBLICATIONS_TITLE: String = "No named publications here yet."
    const val PROFILE_PUBLICATIONS_BODY: String = "Named publications from this cook will show up here."
    // PL-25 (Slice 10): Insights / Recipes / Home empties carry ONE wording on
    // both platforms — these literals replace the per-platform variants that
    // used to describe the same state differently.
    const val INSIGHTS_TITLE: String = "No cooks yet"
    const val INSIGHTS_BODY: String = "Finished cooks will show up here with their results, readings and statistics. Start a cook to begin your history."
    const val RECIPES_TITLE: String = "Your cookbook starts here"
    const val RECIPES_BODY: String = "Save selected dishes from a finished meal here. The original cook stays in your meal history."
    const val RECIPES_FILTERED_TITLE: String = "No matching recipes"
    const val RECIPES_FILTERED_BODY: String = "No recipes match these filters. Try another search or change the filters."
}

/** DD-8 error language (PL-23, Slice 10): every failure the app shows is
 * [what happened] + [what is still safe] + [recovery action]. Producers use
 * [failureText] so an authored validation sentence can serve as the "what
 * happened" clause while a technical exception message (SQL text, class
 * names, "Failed requirement.") never reaches the screen — the DD-8
 * fallback for the job is shown instead. The iOS mirror is `StateCopy.swift`. */
internal object ErrorCopy {
    const val SAVE_CHANGE_FAILED: String =
        "Couldn't save that change. Your cook log is unchanged on this device — try again."
    const val SAVE_NOTE_DRAFT_FAILED: String =
        "Couldn't save this note draft. The draft is kept on this device — try again."
    const val SAVE_CHECK_IN_FAILED: String =
        "Couldn't save that check-in. Nothing was recorded — try again."
    const val SAVE_TIMELINE_ENTRY_FAILED: String =
        "Couldn't save that timeline entry. Your cook log is unchanged on this device — try again."
    const val ACCOUNT_ACTION_FAILED: String =
        "That didn't go through. Nothing changed on this device — check your email, code or link and try again."

    private val technicalMarkers = listOf(
        "Exception", "Failed requirement", "SQL", "sqlite", "column", "index:",
        "com.pittech", "java.", "kotlin.", "android.", "null",
    )

    /** The user-facing text for [failure]: its message when the message is
     * an authored sentence (the repository/domain layers write those as
     * user copy), [fallback] when the message is missing or technical. */
    fun failureText(failure: Throwable?, fallback: String): String {
        val message = failure?.message?.trim().orEmpty()
        if (message.isEmpty() || message.length > 200) return fallback
        if (technicalMarkers.any { message.contains(it) }) return fallback
        return message
    }

    /** Same treatment for a message that arrived as a plain string. */
    fun failureText(message: String?, fallback: String): String {
        val text = message?.trim().orEmpty()
        if (text.isEmpty() || text.length > 200) return fallback
        if (technicalMarkers.any { text.contains(it) }) return fallback
        return text
    }
}

/** PL-23 / PL-25 (Slice 10): every spinner carries a gerund label, and the
 * shared jobs use these exact labels on both platforms. */
internal object LoadingCopy {
    const val SYNCING: String = "Syncing…"
    const val CONNECTING: String = "Connecting…"
    const val SAVING: String = "Saving…"
    const val WORKING_WITH_DATA: String = "Working with your cook data…"
    const val CHECKING_PRO: String = "Checking PitTech Pro status…"
    const val LOADING_BENCHMARKS: String = "Loading community benchmarks…"
    const val LOADING_RECIPES: String = "Loading community recipes…"
    const val LOADING_RECIPE: String = "Loading recipe…"
    const val CREATING_CONNECTION: String = "Creating connection…"
    const val LOADING_COOKBOOK: String = "Loading your cookbook…"
    const val LOADING_CONTROLS: String = "Loading community controls…"
    const val LOADING_PROFILE: String = "Loading profile…"
    const val PREPARING_COOK_PACK: String = "Preparing Cook Pack…"
    const val DOWNLOADING_COOK_PACK: String = "Downloading Cook Pack…"
    const val RENDERING_SHARE_CARD: String = "Rendering share card…"
    const val PUBLISHING: String = "Publishing…"
    const val WORKING: String = "Working…"
}

/** DD-8 offline state (PL-23, Slice 10): the app-wide banner copy plus the
 * per-surface "changes wait" line for network surfaces. Identical on both
 * platforms. */
internal object OfflineCopy {
    const val BANNER: String = "You're offline. Changes wait on this device until a connection returns."
    const val CHANGES_WAIT: String = "Offline — changes wait on this device until a connection returns."
}

/** PL-25 (Slice 10): feedback submission failures are ONE set of sentences —
 * the two generic variants FeedbackApi used to carry are deleted. Every
 * sentence is DD-8: the form keeps the entered text when a submit fails
 * (the dialog stays open), so "what you wrote stays in the form" is true. */
internal object FeedbackCopy {
    const val SUBMIT_FAILED: String =
        "Couldn't submit your feedback. What you wrote stays in the form — check your connection and try again."
    const val SUBMIT_RATE_LIMITED: String =
        "Couldn't submit your feedback — too many reports were sent recently. What you wrote stays in the form — try again later."
    const val SUBMIT_TOO_LONG: String =
        "That report couldn't be submitted — it's too long. What you wrote stays in the form — shorten it and try again."
    const val SUBMIT_UNAVAILABLE: String =
        "Couldn't submit your feedback — the feedback service is unavailable. What you wrote stays in the form — try again later."
}

/** PL-26 (Slice 10): Settings alignment. The section order is identical on
 * both platforms (pinned by unit tests on each side), backup/export lives
 * in "Your data" on both, and the backup scope is stated with these exact
 * sentences on both. */
internal object SettingsCopy {
    val SECTION_ORDER: List<String> = listOf(
        "Appearance", "Units", "Cook notifications", "Your data", "Feedback", "Ads and privacy", "About",
    )
    const val FULL_BACKUP_SCOPE: String =
        "A full backup (ZIP) includes your cooks, log entries, readings, results, reminders, photos, playbooks, plans, checklists, equipment, and app preferences."
    const val DATA_STAYS_LINE: String =
        "Your cook records and photos stay in PitTech's private storage on this device. No account is needed, and you can use your cook log offline. Save a backup somewhere you can find it if you change phones or reinstall PitTech."
}
