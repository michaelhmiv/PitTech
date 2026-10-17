import Foundation

/// Clears account-bound device metadata after server acceptance; never opens the journal.
@MainActor enum NativeAccountLocalCleanup {
    static func run(_ user: String) throws {
        try NativePublicationOperationsStore().purgeAccount(user)
        let vault = NativeCommunityVault()
        if var state = try vault.load() {
            if state.pending?.userId == user { state.pending = nil }
            state.reports.removeAll { $0.userId == user }
            state.generation = UUID().uuidString.lowercased()
            try vault.save(state)
        }
    }
}
