import Foundation
import Observation

@MainActor @Observable final class NativeRelayCoordinator {
    var showingImport = false
    var input = ""
    private(set) var shares: [NativeRelayOwnedShare] = []
    private(set) var busy = false
    private(set) var progress: Double = 0
    private(set) var operationLabel = ""
    var message: String?
    var downloaded: URL?
    private let vault = NativeRelayVault()
    private let client = NativeRelayClient()
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var operation = UUID()
    func refresh() { do {
        shares = try vault.all()
        for share in shares where share.receipt != nil { if let file = try? vault.pack(share) { try? FileManager.default.removeItem(at: file) } }
    } catch { message = "This device's saved sharing capabilities are unavailable. Existing public copies may still be accessible." } }
    func receive(_ url: URL) {
        do { input = try NativeRelayProtocol.code(url.absoluteString); showingImport = true; message = nil }
        catch { message = error.localizedDescription }
    }
    func cancel() { task?.cancel() }
    private func start(_ label: String, action: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }; busy = true; progress = 0; operationLabel = label; message = nil
        operation = UUID(); let token = operation
        task = Task { @MainActor in
            do { try await action(); try Task.checkCancellation() }
            catch is CancellationError { message = "Canceled. Your local draft and any saved upload retry are unchanged." }
            catch { message = error.localizedDescription }
            if operation == token { busy = false; task = nil; refresh() }
        }
    }
    private func progressCallback() -> @Sendable (Int64, Int64) -> Void {
        let token = operation
        return { [weak self] done, total in Task { @MainActor in
            guard let self, self.operation == token, self.busy else { return }
            self.progress = total > 0 ? min(1, max(0, Double(done) / Double(total))) : 0
        } }
    }
    func upload(_ document: String, includeHistory: Bool, store: CookStore) {
        start("Preparing the selected Cook Pack") {
            let revision = store.sharingRecord(document)?["updated"]?.numberValue ?? 0
            let title = try store.sharingDocument(document).title
            let bytes = try await store.exportCookPackAsync(document, includeRecordedCook: includeHistory); try Task.checkCancellation()
            guard store.sharingRecord(document)?["updated"]?.numberValue == revision else { throw NativeRelayError.message("This draft changed while preparing. Review it and try again.") }
            let value = try await Task.detached(priority: .userInitiated) { try NativeRelayVault().prepare(document, revision: revision, title: title, bytes: bytes) }.value
            try Task.checkCancellation(); try await self.send(value)
        }
    }
    func retry(_ value: NativeRelayOwnedShare) { start("Retrying the same saved upload") { try await self.send(value) } }
    private func send(_ value: NativeRelayOwnedShare) async throws {
        operationLabel = "Uploading the selected Cook Pack"
        let file = try vault.pack(value), receipt = try await client.upload(value, file: file, progress: progressCallback())
        try Task.checkCancellation(); var ready = value; ready.receipt = receipt
        try vault.save(ready); try? FileManager.default.removeItem(at: file)
        message = "Your code is ready. Anyone with it can access this selected pack."
    }
    func download() {
        start("Downloading for preview") {
            let code = try NativeRelayProtocol.code(self.input)
            let target = FileManager.default.temporaryDirectory.appendingPathComponent("relay-" + UUID().uuidString + ".pittech")
            do {
                let file = try await self.client.download(code, to: target, progress: self.progressCallback())
                try Task.checkCancellation(); self.downloaded = file; self.showingImport = false
            } catch { try? FileManager.default.removeItem(at: target); throw error }
        }
    }
    func renew(_ value: NativeRelayOwnedShare) { start("Renewing this code") {
        var updated = value; updated.receipt = try await self.client.renew(value); try Task.checkCancellation(); try self.vault.save(updated)
        self.message = "Renewed for 180 days. Reading a code never renews it."
    } }
    func revoke(_ value: NativeRelayOwnedShare) { start("Revoking this code") {
        try await self.client.revoke(value); try Task.checkCancellation(); var updated = value; updated.revoked = true
        try self.vault.save(updated); self.message = "Code revoked. Copies already saved by recipients remain theirs."
    } }
    func card(_ document: String, revision: Double) -> ShareCardRelay? {
        guard let saved = shares.first(where: { $0.document == document && $0.revision == revision && !$0.revoked }),
              let receipt = saved.receipt, !receipt.expired else { return nil }
        return ShareCardRelay(code: receipt.code, url: "https://pittech.app/s/" + receipt.code)
    }
}
