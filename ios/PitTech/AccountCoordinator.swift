import Foundation
import Observation

@MainActor @Observable final class NativeAccountCoordinator {
    let enabled: Bool
    private let vault: any NativeAccountStorage
    private let api: any NativeAccountAPI
    private var state = NativeAccountState()
    private var storageReady = true
    private var refreshTask: Task<String, Error>?
    private(set) var busy = false
    private(set) var message: String?
    var showingAccount = false
    private(set) var pendingLink: String?
    var challengeEmail: String? { state.challenge?.email }
    var signedIn: Bool { state.session != nil }
    var canResend: Bool { state.challenge.map { Date().timeIntervalSince($0.created) >= 60 } ?? true }
    var interruptedVerification: Bool { state.challenge?.operation != nil }
    init(enabled: Bool = NativeAccountProtocol.enabled, vault: any NativeAccountStorage = NativeAccountVault(), api: any NativeAccountAPI = NativeAccountClient()) {
        self.enabled = enabled; self.vault = vault; self.api = api
        do {
            state = try vault.load()
            if let challenge = state.challenge, Date().timeIntervalSince(challenge.created) >= 900 { state.challenge = nil; try vault.save(state) }
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
                var next = self.state; next.session = session; next.challenge = nil; next.refreshOperation = nil; try self.save(next)
                self.pendingLink = nil; self.message = "Signed in. Your local cookbook is unchanged."
            } catch NativeAccountError.status(401) {
                challenge.operation = nil; challenge.secret = nil; var next = self.state; next.challenge = challenge; try self.save(next)
                self.pendingLink = nil; throw NativeAccountError.status(401)
            }
        }
    }
    /// Single-flight refresh; persist the operation before sending, including across app restarts.
    func access() async throws -> String {
        try requireReady()
        if let refreshTask { return try await refreshTask.value }
        guard !busy, let session = state.session else { throw NativeAccountError.status(401) }
        if state.refreshOperation == nil, try NativeAccountProtocol.date(session.accessExpiresAt).timeIntervalSinceNow > 30 { return session.accessToken }
        let task = Task { () throws -> String in
            guard try NativeAccountProtocol.date(session.sessionExpiresAt) > Date() else { try self.save(NativeAccountState(logoutToken: session.refreshToken)); throw NativeAccountError.status(401) }
            var next = self.state; next.refreshOperation = next.refreshOperation ?? UUID().uuidString.lowercased(); try self.save(next)
            do {
                let bytes = try await self.api.call("/v1/auth/refresh", body: ["refreshToken": session.refreshToken, "idempotencyId": self.state.refreshOperation!], access: nil)
                let rotated = try JSONDecoder().decode(NativeAccountSession.self, from: bytes); try rotated.validate()
                guard rotated.userId == session.userId else { throw NativeAccountError.invalid }
                next = self.state; next.session = rotated; next.refreshOperation = nil; try self.save(next); return rotated.accessToken
            } catch NativeAccountError.status(401) { try self.save(NativeAccountState()); throw NativeAccountError.status(401) }
        }
        refreshTask = task; busy = true
        defer { refreshTask = nil; busy = false }
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
        } catch NativeAccountError.status(401) { try? save(NativeAccountState()); message = "Sign in again to reconnect. Your local cookbook is unchanged." }
        catch { message = "Couldn't reconnect. Your local cookbook is available offline." }
    }
    func signOut() async {
        await perform {
            // One atomic write removes credentials from the active account before network access.
            try self.save(NativeAccountState(logoutToken: self.state.session?.refreshToken ?? self.state.logoutToken))
            self.pendingLink = nil
            do { try await self.flushLogout(); self.message = "Signed out. Your local cookbook stays on this device." }
            catch { self.message = "Signed out on this device. Server sign-out will retry before your next sign-in." }
        }
    }
}
