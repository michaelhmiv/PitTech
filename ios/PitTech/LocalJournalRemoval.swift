import Foundation

enum LocalJournalRemovalError: LocalizedError {
    case activeCook, activeRecording, syncing
    var errorDescription: String? {
        switch self {
        case .activeCook: return "Finish or stop the active cook first. Removing the local journal never interrupts a cook in progress."
        case .activeRecording: return "Stop the in-progress recording first. Removing the local journal never interrupts a recording in progress."
        case .syncing: return "Wait for private sync to finish before removing the journal on this device."
        }
    }
}

/// Removes the device-local cook journal without touching the server account.
/// Mirror image of NativeAccountLocalCleanup: journal rows and files only, never the
/// account vault, NativeCommunityVault, publication operations store, or deletion records.
@MainActor extension CookStore {
    func removeLocalJournal() throws {
        guard library.cooks.allSatisfy({ !$0.isActive }) else { throw LocalJournalRemovalError.activeCook }
        guard telemetryLiveDevices.isEmpty, library.recordings.allSatisfy({ $0.objectValue?["status"]?.stringValue != "recording" }) else { throw LocalJournalRemovalError.activeRecording }
        try onBeforeLocalJournalRemoval?()
        library = PitTechLibrary()
        noteDrafts = []
        deletedNote = nil
        for file in [storageURL, draftsURL] {
            if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
        }
        let attachments = storageURL.deletingLastPathComponent().appendingPathComponent("attachments", isDirectory: true)
        if FileManager.default.fileExists(atPath: attachments.path) { try FileManager.default.removeItem(at: attachments) }
    }
}
