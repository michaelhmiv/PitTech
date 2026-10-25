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
}
