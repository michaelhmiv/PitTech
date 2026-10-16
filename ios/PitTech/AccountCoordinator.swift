import Foundation
import Observation

@MainActor @Observable final class NativeAccountCoordinator {
    let enabled: Bool
    private let vault: any NativeAccountStorage
    private let api: any NativeAccountAPI
    private let cleanup: (String) throws -> Void
    private var state = NativeAccountState()
    private var storageReady = true
    private var refreshTask: Task<String, Error>?
    private(set) var busy = false
    private(set) var message: String?
    var showingAccount = false
    private(set) var pendingLink: String?
    var challengeEmail: String? { state.challenge?.email }
    var signedIn: Bool { state.session != nil }
    var stateUserId: String? { state.deletion == nil ? state.session?.userId : nil }
    var deletionRecord: NativeDeletionRecord? { state.deletion }
    var canResend: Bool { state.challenge.map { Date().timeIntervalSince($0.created) >= 60 } ?? true }
    var interruptedVerification: Bool { state.challenge?.operation != nil }
    init(enabled: Bool = NativeAccountProtocol.enabled, vault: any NativeAccountStorage = NativeAccountVault(), api: any NativeAccountAPI = NativeAccountClient(), cleanup: @escaping (String) throws -> Void = { _ in }) {
        self.enabled = enabled; self.vault = vault; self.api = api; self.cleanup = cleanup
        do {
            state = try vault.load(); try state.deletion?.validate()
            if state.deletion?.receipt != nil { do { try cleanupAcceptedDeletion() } catch { message = "Device account metadata cleanup needs a retry. Your journal is unchanged." } }
            if let challenge = state.challenge, Date().timeIntervalSince(challenge.created) >= 900 { state.challenge = nil; try vault.save(state) }
            if let session = state.session {
                try session.validate()
                if try NativeAccountProtocol.date(session.sessionExpiresAt) <= Date() {
                    state = NativeAccountState(logoutToken: session.refreshToken, deletion: state.deletion); try vault.save(state)
                }
            }
        } catch { storageReady = false; message = "This device's account storage is unavailable. Your local cookbook is unchanged." }
    }
    private func save(_ next: NativeAccountState) throws { try vault.save(next); state = next }
    private func requireReady() throws { guard enabled else { throw NativeAccountError.unavailable }; guard storageReady else { throw NativeAccountError.invalid } }
    private func flushLogout() async throws {
        if let token = state.logoutToken {
            _ = try await api.call("/v1/auth/logout", body: ["refreshToken": token], access: nil)
            var next = state; next.logoutToken = nil; try save(next)
        }
    }
    private func perform(_ operation: () async throws -> Void) async {
        guard !busy else { return }; busy = true; message = nil
        defer { busy = false }
        do { try requireReady(); try await operation() }
        catch is CancellationError { message = "Request interrupted. Retry to recover the same sign-in." }
        catch { message = (error as? NativeAccountError)?.localizedDescription ?? "Account storage is unavailable. Try again on this device." }
    }
    func start(_ input: String, resend: Bool = false) async {
        await perform {
            guard self.state.deletion?.receipt == nil else { throw NativeAccountError.invalid }
            try await self.flushLogout()
            let email = try NativeAccountProtocol.email(input)
            var next = self.state
            if let old = next.challenge, old.email == email, !resend, Date().timeIntervalSince(old.created) < 900 {
                // An interrupted start always retries its original UUID and proof.
            } else {
                guard self.canResend else { throw NativeAccountError.status(429) }
                next.challenge = NativeAccountChallenge(id: UUID().uuidString.lowercased(), email: email, verifier: try NativeAccountProtocol.verifier(), created: .now)
                try self.save(next); self.pendingLink = nil
            }
            guard var challenge = self.state.challenge else { throw NativeAccountError.invalid }
            let bytes = try await self.api.call("/v1/auth/challenges", body: ["email": challenge.email, "idempotencyId": challenge.id, "proofChallenge": NativeAccountProtocol.proof(challenge.verifier)], access: nil)
            let reply = try JSONSerialization.jsonObject(with: bytes) as? [String: Any]
            guard reply?["challengeId"] as? String == challenge.id, reply?["expiresInSeconds"] as? Int == 900 else { throw NativeAccountError.invalid }
            challenge.accepted = true; next = self.state; next.challenge = challenge; try self.save(next)
            self.message = "Check your email for a link and six-digit code. Both expire in 15 minutes."
        }
    }
    func receive(_ url: URL) {
        guard enabled, !busy else { return }
        showingAccount = true
        do {
            let (id, token) = try NativeAccountProtocol.link(url)
            guard let challenge = state.challenge, challenge.id == id, Date().timeIntervalSince(challenge.created) < 900 else { throw NativeAccountError.invalid }
            pendingLink = token; message = "Confirm sign-in on this device."
        } catch { pendingLink = nil; message = "Start sign-in on this device, then enter the code from your email." }
    }
    func verify(_ code: String = "") async {
        await perform {
            guard var challenge = self.state.challenge, Date().timeIntervalSince(challenge.created) < 900 else { throw NativeAccountError.status(401) }
            if challenge.operation == nil {
                let secret = self.pendingLink ?? code
                guard self.pendingLink != nil || secret.range(of: "^[0-9]{6}$", options: .regularExpression) != nil else { throw NativeAccountError.invalid }
                challenge.operation = UUID().uuidString.lowercased(); challenge.secret = secret; challenge.isLink = self.pendingLink != nil
                var next = self.state; next.challenge = challenge; try self.save(next)
            }
            var body = ["challengeId": challenge.id, "verifier": challenge.verifier, "idempotencyId": challenge.operation!]
            body[challenge.isLink ? "token" : "code"] = challenge.secret!
            do {
                let bytes = try await self.api.call("/v1/auth/verify", body: body, access: nil)
                let session = try JSONDecoder().decode(NativeAccountSession.self, from: bytes); try session.validate()
                if let owner = self.state.deletion?.userId, owner != session.userId {
                    _ = try? await self.api.call("/v1/auth/logout", body: ["refreshToken": session.refreshToken], access: nil)
                    throw NativeAccountError.invalid
                }
                var next = self.state; next.session = session; next.challenge = nil; next.refreshOperation = nil; try self.save(next)
                self.pendingLink = nil; self.message = "Signed in. Your local cookbook is unchanged."
            } catch NativeAccountError.status(401) {
                challenge.operation = nil; challenge.secret = nil; var next = self.state; next.challenge = challenge; try self.save(next)
                self.pendingLink = nil; throw NativeAccountError.status(401)
            }
        }
    }
    /// Single-flight refresh; persist the operation before sending, including across app restarts.
    func access(forceRefresh: Bool = false) async throws -> String { try await accountAccess(forceRefresh: forceRefresh) }
    private func accountAccess(forceRefresh: Bool = false, forDeletion: Bool = false) async throws -> String {
        try requireReady()
        guard state.deletion == nil || forDeletion else { throw NativeAccountError.status(401) }
        if let refreshTask { return try await refreshTask.value }
        guard (!busy || forDeletion), let session = state.session else { throw NativeAccountError.status(401) }
        if !forceRefresh, state.refreshOperation == nil, try NativeAccountProtocol.date(session.accessExpiresAt).timeIntervalSinceNow > 30 { return session.accessToken }
        let task = Task { () throws -> String in
            guard try NativeAccountProtocol.date(session.sessionExpiresAt) > Date() else { try self.save(NativeAccountState(logoutToken: session.refreshToken, deletion: self.state.deletion)); throw NativeAccountError.status(401) }
            var next = self.state; next.refreshOperation = next.refreshOperation ?? UUID().uuidString.lowercased(); try self.save(next)
            do {
                let bytes = try await self.api.call("/v1/auth/refresh", body: ["refreshToken": session.refreshToken, "idempotencyId": self.state.refreshOperation!], access: nil)
                let rotated = try JSONDecoder().decode(NativeAccountSession.self, from: bytes); try rotated.validate()
                guard rotated.userId == session.userId else { throw NativeAccountError.invalid }
                next = self.state; next.session = rotated; next.refreshOperation = nil; try self.save(next); return rotated.accessToken
            } catch NativeAccountError.status(401) { try self.save(NativeAccountState(deletion: self.state.deletion)); throw NativeAccountError.status(401) }
        }
        let wasBusy = busy; refreshTask = task; busy = true
        defer { refreshTask = nil; busy = wasBusy }
        return try await task.value
    }
    func checkSession() async {
        guard !busy else { return }
        do {
            let token = try await access()
            // Gate further UI operations while validating the current account.
            busy = true; defer { busy = false }
            let data = try await api.call("/v1/me", body: nil, access: token)
            let reply = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            guard reply?["id"] as? String == state.session?.userId else { throw NativeAccountError.invalid }
            message = "Your session is active."
        } catch NativeAccountError.status(401) { try? save(NativeAccountState(deletion: state.deletion)); message = "Sign in again to reconnect. Your local cookbook is unchanged." }
        catch { message = "Couldn't reconnect. Your local cookbook is available offline." }
    }
    func signOut() async {
        await perform {
            // One atomic write removes credentials from the active account before network access.
            try self.save(NativeAccountState(logoutToken: self.state.session?.refreshToken ?? self.state.logoutToken, deletion: self.state.deletion))
            self.pendingLink = nil
            do { try await self.flushLogout(); self.message = "Signed out. Your local cookbook stays on this device." }
            catch { self.message = "Signed out on this device. Server sign-out will retry before your next sign-in." }
        }
    }
    func requestDeletion() async {
        guard !busy else { return }
        await perform {
            guard let user = self.state.session?.userId else { throw NativeAccountError.status(401) }
            if let pending = self.state.deletion { guard pending.userId == user, pending.receipt == nil else { throw NativeAccountError.invalid } }
            else {
                var next = self.state
                next.deletion = NativeDeletionRecord(userId: user, operationId: UUID().uuidString.lowercased(), verifier: try NativeAccountProtocol.verifier())
                try self.save(next) // Exact proof and operation precede all deletion network access.
            }
            guard let record = self.state.deletion else { throw NativeAccountError.invalid }
            let token = try await self.accountAccess(forDeletion: true)
            let bytes: Data
            do { bytes = try await self.api.call("/v1/me/deletion", body: record.request, access: token) }
            catch NativeAccountError.status(401) {
                let refreshed = try await self.accountAccess(forceRefresh: true, forDeletion: true)
                guard self.state.session?.userId == record.userId else { throw NativeAccountError.invalid }
                bytes = try await self.api.call("/v1/me/deletion", body: record.request, access: refreshed)
            }
            try self.acceptDeletion(bytes)
        }
        if state.deletion?.receipt == nil { message = "Deletion was not confirmed. Recover the request first, or retry the same request on this account. Your local journal stays available." }
    }
    private func acceptDeletion(_ bytes: Data) throws {
        guard var record = state.deletion else { throw NativeAccountError.invalid }
        let receipt = try JSONDecoder().decode(NativeDeletionReceipt.self, from: bytes); try receipt.validate()
        if let previous = record.receipt { guard previous.deletionId == receipt.deletionId, previous.statusToken == receipt.statusToken else { throw NativeAccountError.invalid } }
        record.cleanupUserId = record.cleanupUserId ?? record.userId
        record.receipt = receipt; record.userId = nil
        if receipt.state == "complete" { record.verifier = nil }
        try save(NativeAccountState(deletion: record)); pendingLink = nil
        do { try cleanupAcceptedDeletion(); message = receipt.description }
        catch { message = "Server deletion was accepted. Device account metadata cleanup needs a retry. Your journal is unchanged." }
    }
    private func cleanupAcceptedDeletion() throws {
        guard var record = state.deletion, record.receipt != nil, let user = record.cleanupUserId else { return }
        try cleanup(user)
        record.cleanupUserId = nil; try save(NativeAccountState(deletion: record))
    }
    func retryDeviceCleanup() {
        guard !busy else { return }
        do { try cleanupAcceptedDeletion(); message = state.deletion?.receipt?.description }
        catch { message = "Device account metadata could not be cleared. Retry on this device. Your journal is unchanged." }
    }
    func recoverDeletion() async {
        await perform {
            guard let record = self.state.deletion, let verifier = record.verifier else { throw NativeAccountError.invalid }
            try self.acceptDeletion(await self.api.call("/v1/deletion/recover", body: ["operationId": record.operationId, "verifier": verifier], access: nil))
        }
    }
    func checkDeletion() async {
        await perform {
            guard let receipt = self.state.deletion?.receipt else { throw NativeAccountError.invalid }
            let bytes = try await self.api.call("/v1/deletion/status", body: ["deletionId": receipt.deletionId, "statusToken": receipt.statusToken], access: nil)
            guard let result = try JSONSerialization.jsonObject(with: bytes) as? [String: String], result["deletionId"] == receipt.deletionId, let status = result["state"] else { throw NativeAccountError.invalid }
            var next = receipt; next.state = status; try next.validate()
            try self.acceptDeletion(JSONEncoder().encode(next))
        }
    }
    func dismissCompletedDeletion() {
        guard !busy, state.deletion?.receipt?.state == "complete" else { return }
        do { try cleanupAcceptedDeletion(); try save(NativeAccountState()); message = "Account deletion is complete. Your local journal stays on this device." }
        catch { message = "This device could not clear its recovery record. Try again." }
    }

}
