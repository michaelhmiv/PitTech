import Foundation

/// #172 Slice 7 (PL-10 / PL-13, DD-8): one wording per state, identical on both
/// platforms. The Android mirror is `StateCopy.kt`; unit tests on both platforms
/// pin the exact strings so the two cannot drift apart.
///
/// Sync copy is presentation only: it derives from the coordinator's existing
/// presentation fields and never changes sync behaviour, conflict semantics or
/// enablement. Every sentence follows DD-8 — what happened, what is still safe,
/// and the recovery action — and only promises what the engine already does:
/// a resolution is recorded on this device immediately (the coordinator saves
/// it with `pending = true`) and uploads on the next sync; a never-completed
/// sync has uploaded nothing.
struct SyncStatusRowCopy: Equatable {
    let headline: String
    let body: String
}

enum SyncStatusCopy {
    /// The status row Account shows for private sync, per state. `status` is the
    /// coordinator's existing status string and stays the headline, except for the
    /// signed-out state, where the coordinator's default "Sync is off" would
    /// misdescribe an enabled-but-signed-out sync.
    static func row(status: String, detail: String?, lastSyncedAt: Int64, conflictCount: Int, signedIn: Bool, enabled: Bool) -> SyncStatusRowCopy {
        if !enabled {
            return SyncStatusRowCopy(
                headline: status,
                body: "Private sync is not available on this build. Your journal stays on this device and nothing is uploaded.")
        }
        if conflictCount > 0 {
            return SyncStatusRowCopy(
                headline: status,
                body: "Two devices changed the same \(items(conflictCount)). Nothing is overwritten until you choose. " +
                    conflictConsequence(conflictCount))
        }
        if status == "Waiting for connection" {
            return SyncStatusRowCopy(
                headline: status,
                body: detail ?? "Your changes stay on this device. Sync tries again when a connection returns — or tap Sync now to try now.")
        }
        if status == "Retry needed" {
            return SyncStatusRowCopy(
                headline: status,
                body: detail ?? "Your journal is unchanged on this device. Tap Sync now to retry.")
        }
        if !signedIn {
            return SyncStatusRowCopy(
                headline: "Sign in to start private sync",
                body: "Nothing is uploaded while you are signed out, and your journal stays on this device. Sign in above, then tap Sync now to make the first backup.")
        }
        if lastSyncedAt <= 0 {
            return SyncStatusRowCopy(
                headline: status,
                body: "Waiting for first connection — nothing has been backed up yet. Your journal is safe on this device. Tap Sync now to make the first backup.")
        }
        return SyncStatusRowCopy(
            headline: status,
            body: detail ?? "Changes you make now stay on this device until the next sync uploads them.")
    }

    /// The concrete consequence of resolving the whole pending conflict set,
    /// computed from the actual number of conflicts awaiting review.
    static func conflictConsequence(_ conflictCount: Int) -> String {
        "Choosing “Use theirs” for \(all(conflictCount)) replaces this device's unsynced changes to \(items(conflictCount)). " +
            "Each choice is recorded on this device now and uploads on the next sync."
    }

    /// Shown under each conflict's two summaries, next to its choices.
    static let conflictItemNote = "Keep mine keeps this device's version of this item. Use theirs replaces it with the other device's version."

    /// PL-16: the consequence printed on a choice itself, before it is
    /// tapped — the choice applies on this device immediately, and the
    /// resolution uploads on the next sync (the coordinator records every
    /// resolution with `pending = true`; see the header note).
    static func conflictChoiceNote(_ choice: String) -> String {
        switch choice {
        case "mine": return "Keep mine applies on this device right away — your version stays. The choice uploads on the next sync."
        case "theirs": return "Use theirs applies on this device right away — the other device's version replaces yours. The choice uploads on the next sync."
        default: return "Keep both applies on this device right away — both versions stay, the other as a new entry. The choice uploads on the next sync."
        }
    }

    /// PL-16: where Keep both cannot exist for a record kind, the sheet says
    /// so for that item instead of the button silently not being there. The
    /// two kinds are the projection's link/singleton keys (see
    /// NativePrivateSyncProtocol.keepBothUnavailableTypes).
    static func conflictKeepBothUnavailable(_ recordType: String) -> String {
        switch recordType {
        case "lifecycles": return "Keep both isn't available for meal timing — a meal has one timing record, so choose one version."
        case "logReadingLinks": return "Keep both isn't available for a note temperature link — a reading links to one note, so choose one version."
        default: return "Keep both isn't available for this entry, so choose one version."
        }
    }

    private static func items(_ count: Int) -> String { count == 1 ? "1 item" : "\(count) items" }
    private static func all(_ count: Int) -> String { count == 1 ? "it" : "all \(count)" }
}

/// Canonical empty-state copy (PL-13): what belongs on the surface, plus the
/// action that creates it where the viewer owns that action. One wording per
/// surface, identical on both platforms.
enum EmptyStateCopy {
    static let timelineTitle = "No cook history yet"
    static let timelineBody = "Notes, temperature checks and photos you add during the cook will show up here."
    static let photosEmptyBody = "Photos you add to this cook will show up here. Add one with the Photo button on the cook."
    static let connectionsEmpty = "No connections yet. Add one below, then attach it to a cook on the next step."
    static let sharedCodesEmpty = "No shared codes yet. Open a saved recipe or meal, choose Share, then Share by code."
    static let blocksTitle = "No publishers blocked on this device."
    static let blocksBody = "Publishers you block from a publication or profile will be listed here, and their publications stay hidden."
    static let reportsTitle = "No report receipts on this device."
    static let reportsBody = "Receipts for reports you send about a publication or profile will show up here."
    static let profilePublicationsTitle = "No named publications here yet."
    static let profilePublicationsBody = "Named publications from this cook will show up here."
    // PL-25 (Slice 10): Insights / Recipes empties carry ONE wording on both
    // platforms — these literals replace the per-platform variants.
    static let insightsTitle = "No cooks yet"
    static let insightsBody = "Finished cooks will show up here with their results, readings and statistics. Start a cook to begin your history."
    static let recipesTitle = "Your cookbook starts here"
    static let recipesBody = "Save selected dishes from a finished meal here. The original cook stays in your meal history."
    static let recipesFilteredTitle = "No matching recipes"
    static let recipesFilteredBody = "No recipes match these filters. Try another search or change the filters."
}

/// DD-8 error language (PL-23, Slice 10): every failure the app shows is
/// [what happened] + [what is still safe] + [recovery action]. The Android
/// mirror is `StateCopy.kt`; unit tests on both platforms pin the strings.
enum ErrorCopy {
    static let saveChangeFailed = "Couldn't save that change. Your cook log is unchanged on this device — try again."
    static let saveNoteDraftFailed = "Couldn't save this note draft. The draft is kept on this device — try again."
    static let saveCheckInFailed = "Couldn't save that check-in. Nothing was recorded — try again."
    static let saveTimelineEntryFailed = "Couldn't save that timeline entry. Your cook log is unchanged on this device — try again."
    static let accountActionFailed = "That didn't go through. Nothing changed on this device — check your email, code or link and try again."
}

/// PL-23 / PL-25 (Slice 10): every spinner carries a gerund label, and the
/// shared jobs use these exact labels on both platforms.
enum LoadingCopy {
    static let syncing = "Syncing…"
    static let connecting = "Connecting…"
    static let saving = "Saving…"
    static let workingWithData = "Working with your cook data…"
    static let checkingPro = "Checking PitTech Pro status…"
    static let loadingBenchmarks = "Loading community benchmarks…"
    static let loadingRecipes = "Loading community recipes…"
    static let loadingRecipe = "Loading recipe…"
    static let creatingConnection = "Creating connection…"
    static let loadingCookbook = "Loading your cookbook…"
    static let loadingControls = "Loading community controls…"
    static let loadingProfile = "Loading profile…"
    static let preparingCookPack = "Preparing Cook Pack…"
    static let downloadingCookPack = "Downloading Cook Pack…"
    static let renderingShareCard = "Rendering share card…"
    static let publishing = "Publishing…"
    static let working = "Working…"
}

/// DD-8 offline state (PL-23, Slice 10): the app-wide banner copy plus the
/// per-surface "changes wait" line for network surfaces. Identical on both
/// platforms.
enum OfflineCopy {
    static let banner = "You're offline. Changes wait on this device until a connection returns."
    static let changesWait = "Offline — changes wait on this device until a connection returns."
}

/// PL-25 (Slice 10): the one feedback-failure wording per outcome, shared
/// with Android's FeedbackApi (which carried two generic variants before
/// this slice; both now resolve to these).
enum FeedbackCopy {
    static let submitFailed = "Couldn't submit your feedback. What you wrote stays in the form — check your connection and try again."
    static let submitRateLimited = "Couldn't submit your feedback — too many reports were sent recently. What you wrote stays in the form — try again later."
    static let submitTooLong = "That report couldn't be submitted — it's too long. What you wrote stays in the form — shorten it and try again."
    static let submitUnavailable = "Couldn't submit your feedback — the feedback service is unavailable. What you wrote stays in the form — try again later."
}

/// PL-30 (#172): the ONE "not available yet" pattern. Every surface whose
/// feature is not available on this build renders the same shape — icon,
/// title, what-isn't-available message, an optional note about what works
/// instead, and exactly one route onward — with these strings, identical
/// on both platforms (pinned by unit tests on each side). iOS has no
/// Devices placeholder (its telemetry connections ship enabled); the
/// Devices strings are declared here so the contract stays complete and
/// the Discover surface uses the same pattern.
enum NotAvailableCopy {
    static let devicesTitle = "Devices"
    static let devicesMessage = "Grill connections aren't available yet."
    static let devicesNote = "You can add temperature readings by hand during a cook."
    static let devicesOnward = "Open Meals"
    static let discoverTitle = "Find your next cook"
    static let discoverMessage = "Community recipes are being prepared for the next PitTech release. You'll be able to browse, save and cook them without an account."
    static let discoverOnward = "Open your recipes"
}

/// PL-26 (Slice 10): Settings alignment. The section order is identical on
/// both platforms (pinned by unit tests on each side), backup/export lives
/// in "Your data" on both, and the backup scope is stated with these exact
/// sentences on both.
enum SettingsCopy {
    static let sectionOrder = [
        "Appearance", "Units", "Cook notifications", "Your data", "Feedback", "Ads and privacy", "About",
    ]
    static let fullBackupScope = "A full backup (ZIP) includes your cooks, log entries, readings, results, reminders, photos, playbooks, plans, checklists, equipment, and app preferences."
    static let dataStaysLine = "Your cook records and photos stay in PitTech's private storage on this device. No account is needed, and you can use your cook log offline. Save a backup somewhere you can find it if you change phones or reinstall PitTech."
}
