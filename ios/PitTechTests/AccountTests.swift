import XCTest
import Security
@testable import PitTech

@MainActor final class AccountTests: XCTestCase {
    private final class Memory: NativeAccountStorage {
        var value = NativeAccountState()
        var fail = false
        func load() throws -> NativeAccountState { value }
        func save(_ value: NativeAccountState) throws { if fail { throw NativeAccountError.invalid }; self.value = value }
    }
    private struct API: NativeAccountAPI {
        let handler: ([String: String], String) async throws -> Data
        func call(_ path: String, body: [String: String]?, access: String?) async throws -> Data { try await handler(body ?? [:], path) }
    }
    private final class AppleSpy {
        var nonces: [String] = []
        var result: Result<String, Error> = .success("fake-apple-id-token")
    }
    private struct FakeApple: AppleAuthorizing {
        let spy: AppleSpy
        @MainActor func authorize(nonce: String) async throws -> String { spy.nonces.append(nonce); return try spy.result.get() }
    }
    private var token: String { String(repeating: "A", count: 43) }
    private func session(expired: Bool = false, rotated: Bool = false) -> NativeAccountSession {
        NativeAccountSession(accessToken: rotated ? String(repeating: "B", count: 42) + "A" : token, refreshToken: token,
            accessExpiresAt: expired ? "2000-01-01T00:00:00.000Z" : "2100-01-01T00:00:00.000Z", sessionExpiresAt: "2100-02-01T00:00:00.000Z", userId: "12345678-1234-4234-8234-123456789abc")
    }
    private func fixture() throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "auth-native-v1", withExtension: "json"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }
    func testSharedProofLinksAndEmailContract() throws {
        let f = try fixture(), verifier = try XCTUnwrap(f["verifier"] as? String)
        XCTAssertEqual(NativeAccountProtocol.proof(verifier), f["proof"] as? String)
        let provider = try XCTUnwrap(f["provider"] as? [String: String])
        XCTAssertEqual(NativeAccountProtocol.proof(try XCTUnwrap(provider["verifier"])), provider["nonce"])
        XCTAssertTrue(NativeAccountProtocol.credential(try NativeAccountProtocol.verifier()))
        for value in try XCTUnwrap(f["validLinks"] as? [String]) { XCTAssertEqual(try NativeAccountProtocol.link(XCTUnwrap(URL(string: value))).0, f["challengeId"] as? String) }
        for value in try XCTUnwrap(f["invalidLinks"] as? [String]) { XCTAssertThrowsError(try NativeAccountProtocol.link(XCTUnwrap(URL(string: value))), value) }
        for value in try XCTUnwrap(f["emails"] as? [[String]]) { XCTAssertEqual(try NativeAccountProtocol.email(value[0]), value[1]) }
        for value in try XCTUnwrap(f["invalidEmails"] as? [String]) { XCTAssertThrowsError(try NativeAccountProtocol.email(value)) }
    }
    func testLostStartAndVerifyResponsesRecoverTheExactRequestAcrossRestart() async throws {
        let memory = Memory(), result = try JSONEncoder().encode(session())
        var start: [String: String]?, verify: [String: String]?, loseStart = true, loseVerify = true
        let api = API { body, path in
            if path.hasSuffix("challenges") {
                XCTAssertNotNil(memory.value.challenge)
                if let start { XCTAssertEqual(body, start) } else { start = body }
                if loseStart { loseStart = false; throw NativeAccountError.network }
                return try JSONSerialization.data(withJSONObject: ["challengeId": body["idempotencyId"]!, "expiresInSeconds": 900])
            }
            XCTAssertNotNil(memory.value.challenge?.operation)
            if let verify { XCTAssertEqual(body, verify) } else { verify = body }
            if loseVerify { loseVerify = false; throw NativeAccountError.network }; return result
        }
        let first = NativeAccountCoordinator(enabled: true, vault: memory, api: api)
        await first.start("cook@example.com")
        let second = NativeAccountCoordinator(enabled: true, vault: memory, api: api)
        await second.start("cook@example.com"); await second.verify("123456")
        XCTAssertTrue(second.interruptedVerification)
        let third = NativeAccountCoordinator(enabled: true, vault: memory, api: api)
        await third.verify("999999"); XCTAssertTrue(third.signedIn); XCTAssertNil(memory.value.challenge)
    }
    func testLinkReceiptRequiresOriginatingDeviceAndExplicitConfirmation() async throws {
        let f = try fixture(), memory = Memory(), result = try JSONEncoder().encode(session())
        let id = try XCTUnwrap(f["challengeId"] as? String), verifier = try XCTUnwrap(f["verifier"] as? String)
        memory.value.challenge = NativeAccountChallenge(id: id, email: "cook@example.com", verifier: verifier, created: .now)
        var calls = 0
        let api = API { body, _ in calls += 1; XCTAssertEqual(body["token"], verifier); return result }
        let account = NativeAccountCoordinator(enabled: true, vault: memory, api: api)
        let link = try XCTUnwrap(URL(string: (f["validLinks"] as! [String])[0]))
        account.receive(link); XCTAssertEqual(calls, 0); XCTAssertNotNil(account.pendingLink)
        await account.verify(); XCTAssertEqual(calls, 1); XCTAssertTrue(account.signedIn)
        let other = NativeAccountCoordinator(enabled: true, vault: Memory(), api: api)
        other.receive(link); XCTAssertNil(other.pendingLink); XCTAssertEqual(calls, 1)
    }
    func testRefreshSerializesAndRetainsOperationAfterInterruptedResponse() async throws {
        let memory = Memory(); memory.value.session = session(expired: true)
        let result = try JSONEncoder().encode(session(rotated: true))
        var calls = 0, operation: String?
        let api = API { body, _ in
            calls += 1; XCTAssertEqual(body["idempotencyId"], memory.value.refreshOperation)
            if let operation { XCTAssertEqual(body["idempotencyId"], operation) } else { operation = body["idempotencyId"] }
            try await Task.sleep(for: .milliseconds(10))
            if calls == 1 { throw NativeAccountError.network }; return result
        }
        let account = NativeAccountCoordinator(enabled: true, vault: memory, api: api)
        do { _ = try await account.access(); XCTFail("Lost response accepted") } catch { }
        let restarted = NativeAccountCoordinator(enabled: true, vault: memory, api: api)
        async let first = restarted.access(); async let second = restarted.access()
        let values = try await [first, second]
        XCTAssertEqual(values[0], values[1]); XCTAssertEqual(calls, 2); XCTAssertNil(memory.value.refreshOperation)
    }
    func testForcedRefreshReplacesAnOtherwiseUnexpiredAccessCredential() async throws {
        let memory = Memory(); memory.value.session = session(); var calls = 0
        let result = try JSONEncoder().encode(session(rotated: true))
        let account = NativeAccountCoordinator(enabled: true, vault: memory, api: API { _, path in calls += 1; XCTAssertEqual(path, "/v1/auth/refresh"); return result })
        let cached = try await account.access(); XCTAssertEqual(cached, token); XCTAssertEqual(calls, 0)
        let refreshed = try await account.access(forceRefresh: true); XCTAssertNotEqual(refreshed, token); XCTAssertEqual(calls, 1)
        let latest = try await account.access(); XCTAssertEqual(latest, refreshed); XCTAssertEqual(calls, 1)
    }
    func testRefreshRejectionRemovesActiveAccount() async throws {
        let memory = Memory(); memory.value.session = session(expired: true)
        let account = NativeAccountCoordinator(enabled: true, vault: memory, api: API { _, _ in throw NativeAccountError.status(401) })
        do { _ = try await account.access(); XCTFail("Rejected refresh accepted") } catch { }
        XCTAssertFalse(account.signedIn); XCTAssertNil(memory.value.session)
    }
    func testOfflineSignOutQueuesRevocationBeforeAnyNewSignIn() async throws {
        let memory = Memory(); memory.value.session = session(); var offline = true, paths: [String] = []
        let api = API { body, path in
            paths.append(path); if offline { throw NativeAccountError.network }
            return try JSONSerialization.data(withJSONObject: path.hasSuffix("challenges") ? ["challengeId": body["idempotencyId"]!, "expiresInSeconds": 900] : [:])
        }
        let account = NativeAccountCoordinator(enabled: true, vault: memory, api: api)
        await account.signOut(); XCTAssertFalse(account.signedIn); XCTAssertNil(memory.value.session); XCTAssertEqual(memory.value.logoutToken, token)
        offline = false
        let restarted = NativeAccountCoordinator(enabled: true, vault: memory, api: api)
        await restarted.start("new@example.com")
        XCTAssertEqual(paths, ["/v1/auth/logout", "/v1/auth/logout", "/v1/auth/challenges"]); XCTAssertNil(memory.value.logoutToken)
    }
    func testDisabledAccountsAndStorageFailureNeverSendMail() async {
        let memory = Memory(); var calls = 0
        let api = API { _, _ in calls += 1; return Data() }
        let disabled = NativeAccountCoordinator(vault: memory, api: api); await disabled.start("cook@example.com")
        memory.fail = true
        let failing = NativeAccountCoordinator(enabled: true, vault: memory, api: api); await failing.start("cook@example.com")
        XCTAssertEqual(calls, 0)
    }
    func testWrongCodeCanBeCorrectedWithoutChangingDeviceProofAndResendHasCooldown() async throws {
        let memory = Memory(), result = try JSONEncoder().encode(session()); var starts = 0, proofs: [String] = []
        let api = API { body, path in
            if path.hasSuffix("challenges") { starts += 1; return try JSONSerialization.data(withJSONObject: ["challengeId": body["idempotencyId"]!, "expiresInSeconds": 900]) }
            proofs.append(body["verifier"]!); if proofs.count == 1 { throw NativeAccountError.status(401) }; return result
        }
        let account = NativeAccountCoordinator(enabled: true, vault: memory, api: api)
        await account.start("cook@example.com"); await account.start("cook@example.com", resend: true); XCTAssertEqual(starts, 1)
        await account.verify("111111"); await account.verify("222222")
        XCTAssertTrue(account.signedIn); XCTAssertEqual(proofs.count, 2); XCTAssertEqual(proofs.first, proofs.last)
    }
    func testVaultSurvivesRestartUsesDeviceOnlyKeychainAndRemainsSeparateFromRelay() throws {
        let service = "com.pittech.account.test." + UUID().uuidString
        defer { SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary) }
        let vault = NativeAccountVault(service: service)
        var state = NativeAccountState(session: session(), refreshOperation: UUID().uuidString)
        try vault.save(state); XCTAssertEqual(try NativeAccountVault(service: service).load().session?.refreshToken, token)
        var result: CFTypeRef?
        XCTAssertEqual(SecItemCopyMatching([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecReturnAttributes as String: true] as CFDictionary, &result), errSecSuccess)
        let attrs = try XCTUnwrap(result as? [String: Any]); XCTAssertEqual(attrs[kSecAttrAccessible as String] as? String, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
        XCTAssertNotEqual(attrs[kSecAttrSynchronizable as String] as? Bool, true)
        state = NativeAccountState(logoutToken: token); try vault.save(state)
        XCTAssertNil(try vault.load().session); XCTAssertEqual(try vault.load().logoutToken, token)
    }
    func testDeletionPersistsExactProofBeforeNetworkAndRecoversWithoutSessionAfterRestart() async throws {
        let memory = Memory(); memory.value.session = session()
        let receipt = NativeDeletionReceipt(deletionId: UUID().uuidString.lowercased(), statusToken: token, state: "queued")
        var submitted: [String: String]?, lost = true
        let api = API { body, path in
            if path == "/v1/me/deletion" {
                XCTAssertEqual(body, memory.value.deletion?.request)
                if let submitted { XCTAssertEqual(body, submitted) } else { submitted = body }
                if lost { lost = false; throw NativeAccountError.network }
                return try JSONEncoder().encode(receipt)
            }
            if path == "/v1/deletion/recover" {
                XCTAssertNil(memory.value.session)
                XCTAssertEqual(NativeAccountProtocol.proof(body["verifier"]!), submitted?["proofChallenge"])
                return try JSONEncoder().encode(receipt)
            }
            XCTAssertEqual(path, "/v1/deletion/status")
            XCTAssertEqual(body["statusToken"], receipt.statusToken)
            return try JSONSerialization.data(withJSONObject: ["deletionId": receipt.deletionId, "state": "complete"])
        }
        let first = NativeAccountCoordinator(enabled: true, vault: memory, api: api)
        await first.requestDeletion(); XCTAssertNotNil(memory.value.deletion); XCTAssertNil(first.stateUserId)
        do { _ = try await first.access(); XCTFail("Publishing remained available") } catch { }
        let second = NativeAccountCoordinator(enabled: true, vault: memory, api: api)
        await second.requestDeletion(); XCTAssertNil(memory.value.session); XCTAssertEqual(second.deletionRecord?.receipt?.state, "queued")
        let restarted = NativeAccountCoordinator(enabled: true, vault: memory, api: api)
        await restarted.recoverDeletion(); await restarted.checkDeletion()
        XCTAssertEqual(restarted.deletionRecord?.receipt?.state, "complete"); XCTAssertNil(memory.value.deletion?.verifier)
        restarted.dismissCompletedDeletion(); XCTAssertNil(memory.value.deletion)
    }
    func testDeletionStorageFailureNeverSendsAndMalformedReceiptKeepsRecovery() async throws {
        let memory = Memory(); memory.value.session = session(); memory.fail = true
        var calls = 0
        let api = API { _, _ in calls += 1; return Data("{}".utf8) }
        let account = NativeAccountCoordinator(enabled: true, vault: memory, api: api)
        await account.requestDeletion(); XCTAssertEqual(calls, 0)
        memory.fail = false; await account.requestDeletion(); XCTAssertEqual(calls, 1)
        XCTAssertNotNil(memory.value.deletion?.verifier); XCTAssertNil(memory.value.deletion?.receipt); XCTAssertNotNil(memory.value.session)
        XCTAssertThrowsError(try NativeDeletionReceipt(deletionId: UUID().uuidString, statusToken: token, state: "unknown").validate())
        let legacy = try JSONDecoder().decode(NativeAccountState.self, from: Data("{}".utf8)); XCTAssertNil(legacy.deletion)
    }
    func testAcceptedDeletionKeepsCleanupOwnerAfterFailureAndRetriesOnRestart() async throws {
        let memory = Memory(); memory.value.session = session()
        let user = memory.value.session!.userId
        let bytes = try JSONEncoder().encode(NativeDeletionReceipt(deletionId: UUID().uuidString.lowercased(), statusToken: token, state: "complete"))
        let api = API { _, _ in bytes }
        var attempts = 0
        let first = NativeAccountCoordinator(enabled: true, vault: memory, api: api, cleanup: { owner in
            XCTAssertEqual(owner, user); XCTAssertNil(memory.value.session); XCTAssertNotNil(memory.value.deletion?.receipt)
            attempts += 1; throw NativeAccountError.invalid
        })
        await first.requestDeletion(); XCTAssertEqual(attempts, 1); XCTAssertNil(memory.value.session)
        XCTAssertEqual(memory.value.deletion?.cleanupUserId, user); XCTAssertNil(memory.value.deletion?.userId)
        let restarted = NativeAccountCoordinator(enabled: true, vault: memory, api: api, cleanup: { owner in XCTAssertEqual(owner, user); attempts += 1 })
        XCTAssertEqual(attempts, 2); XCTAssertNil(restarted.deletionRecord?.cleanupUserId)
        restarted.dismissCompletedDeletion(); XCTAssertNil(memory.value.deletion)
    }
    func testDeletionRetries401OnceWithSameOperationAndRotatedAccount() async throws {
        let memory = Memory(); memory.value.session = session()
        var calls = 0, refreshed = 0; var request: [String: String]?
        let response = try JSONEncoder().encode(NativeDeletionReceipt(deletionId: UUID().uuidString.lowercased(), statusToken: token, state: "queued"))
        let rotated = try JSONEncoder().encode(session(rotated: true))
        let api = API { body, path in
            if path == "/v1/auth/refresh" { refreshed += 1; return rotated }
            calls += 1
            if let request { XCTAssertEqual(request, body) } else { request = body }
            if calls == 1 { throw NativeAccountError.status(401) }; return response
        }
        let account = NativeAccountCoordinator(enabled: true, vault: memory, api: api)
        await account.requestDeletion(); XCTAssertEqual(calls, 2); XCTAssertEqual(refreshed, 1); XCTAssertNil(memory.value.session)
    }
    func testAppleSignInPersistsChallengePresentsProofNonceAndRetriesLostResponses() async throws {
        let memory = Memory(), spy = AppleSpy(), result = try JSONEncoder().encode(session())
        var challenges: [[String: String]] = [], verifies: [[String: String]] = [], verifyCalls = 0, loseVerify = true
        let api = API { body, path in
            if path == "/v1/auth/provider/challenges" {
                XCTAssertNotNil(memory.value.providerChallenge)
                XCTAssertEqual(body["provider"], "apple")
                challenges.append(body)
                return try JSONSerialization.data(withJSONObject: ["challengeId": body["idempotencyId"]!, "expiresInSeconds": 900])
            }
            if path == "/v1/me" {
                return try JSONSerialization.data(withJSONObject: ["id": "12345678-1234-4234-8234-123456789abc", "email": "cook@example.com", "identities": [["provider": "apple"]]])
            }
            XCTAssertEqual(path, "/v1/auth/provider/verify")
            XCTAssertNotNil(memory.value.providerChallenge?.operation)
            XCTAssertEqual(body["idToken"], "fake-apple-id-token")
            verifyCalls += 1
            if let first = verifies.first { XCTAssertEqual(body, first) } else { verifies.append(body) }
            if loseVerify { loseVerify = false; throw NativeAccountError.network }
            return result
        }
        let first = NativeAccountCoordinator(enabled: true, vault: memory, api: api, apple: FakeApple(spy: spy), appleSignInAdvertised: true)
        await first.signInWithApple()
        XCTAssertFalse(first.signedIn)
        XCTAssertEqual(first.message, "Couldn't reach PitTech. Retry the same request when you're online.")
        XCTAssertNotNil(memory.value.providerChallenge?.operation)
        let second = NativeAccountCoordinator(enabled: true, vault: memory, api: api, apple: FakeApple(spy: spy), appleSignInAdvertised: true)
        await second.signInWithApple()
        XCTAssertTrue(second.signedIn)
        XCTAssertEqual(second.message, "Signed in with Apple. Your local cookbook is unchanged.")
        XCTAssertNil(memory.value.providerChallenge)
        XCTAssertEqual(challenges.count, 2); XCTAssertEqual(challenges[0], challenges[1])
        XCTAssertEqual(verifyCalls, 2)
        let proof = NativeAccountProtocol.proof(verifies[0]["verifier"]!)
        XCTAssertEqual(spy.nonces, [proof, proof])
        XCTAssertEqual(challenges[0]["proofChallenge"], proof)
        XCTAssertEqual(second.accountEmail, "cook@example.com")
        XCTAssertTrue(second.appleLinked)
    }
    func testAppleSheetCancellationStaysSilentAndKeepsPendingChallenge() async throws {
        let memory = Memory(), spy = AppleSpy()
        spy.result = .failure(AppleAuthorizationCancelled())
        var paths: [String] = []
        let api = API { body, path in
            paths.append(path)
            XCTAssertEqual(path, "/v1/auth/provider/challenges")
            return try JSONSerialization.data(withJSONObject: ["challengeId": body["idempotencyId"]!, "expiresInSeconds": 900])
        }
        let account = NativeAccountCoordinator(enabled: true, vault: memory, api: api, apple: FakeApple(spy: spy), appleSignInAdvertised: true)
        await account.signInWithApple()
        XCTAssertNil(account.message)
        XCTAssertFalse(account.signedIn)
        XCTAssertEqual(paths, ["/v1/auth/provider/challenges"])
        XCTAssertNotNil(memory.value.providerChallenge)
        XCTAssertNotNil(memory.value.providerChallenge?.operation)
        XCTAssertEqual(spy.nonces.count, 1)
    }
    func testAppleAttachLinksWhileSignedInWithoutANewSession() async throws {
        let memory = Memory(); memory.value.session = session()
        let spy = AppleSpy()
        var identitiesCalls = 0
        let api = API { body, path in
            if path == "/v1/auth/provider/challenges" {
                XCTAssertEqual(memory.value.providerChallenge?.bound, true)
                return try JSONSerialization.data(withJSONObject: ["challengeId": body["idempotencyId"]!, "expiresInSeconds": 900])
            }
            if path == "/v1/me/identities" {
                identitiesCalls += 1
                XCTAssertEqual(memory.value.providerChallenge?.bound, true)
                return try JSONSerialization.data(withJSONObject: ["identities": [["provider": "apple"]]])
            }
            XCTAssertEqual(path, "/v1/me")
            return try JSONSerialization.data(withJSONObject: ["id": "12345678-1234-4234-8234-123456789abc", "email": "cook@example.com", "identities": [["provider": "apple"]]])
        }
        let account = NativeAccountCoordinator(enabled: true, vault: memory, api: api, apple: FakeApple(spy: spy), appleSignInAdvertised: true)
        await account.attachApple()
        XCTAssertEqual(identitiesCalls, 1)
        XCTAssertTrue(account.appleLinked)
        XCTAssertEqual(account.accountEmail, "cook@example.com")
        XCTAssertEqual(memory.value.session?.refreshToken, token)
        XCTAssertNil(memory.value.providerChallenge)
        XCTAssertEqual(account.message, "Apple sign-in is linked. Email sign-in still works as a backup.")
        XCTAssertEqual(spy.nonces.count, 1)
    }
    func testAppleSignInConflictDirectsToExistingMethodThenAttach() async throws {
        let memory = Memory(), spy = AppleSpy()
        let api = API { body, path in
            if path == "/v1/auth/provider/challenges" {
                return try JSONSerialization.data(withJSONObject: ["challengeId": body["idempotencyId"]!, "expiresInSeconds": 900])
            }
            XCTAssertEqual(path, "/v1/auth/provider/verify")
            throw NativeAccountError.status(409)
        }
        let account = NativeAccountCoordinator(enabled: true, vault: memory, api: api, apple: FakeApple(spy: spy), appleSignInAdvertised: true)
        await account.signInWithApple()
        XCTAssertEqual(account.message, "Sign in with your existing method first, then add Apple sign-in in account settings.")
        XCTAssertFalse(account.signedIn)
        XCTAssertNotNil(memory.value.providerChallenge)
        XCTAssertNil(memory.value.providerChallenge?.operation)
    }
    func testAppleSignInWithoutEmailDirectsToEmailThenAttach() async throws {
        let memory = Memory(), spy = AppleSpy()
        let api = API { body, path in
            if path == "/v1/auth/provider/challenges" {
                return try JSONSerialization.data(withJSONObject: ["challengeId": body["idempotencyId"]!, "expiresInSeconds": 900])
            }
            XCTAssertEqual(path, "/v1/auth/provider/verify")
            throw NativeAccountError.status(401)
        }
        let account = NativeAccountCoordinator(enabled: true, vault: memory, api: api, apple: FakeApple(spy: spy), appleSignInAdvertised: true)
        await account.signInWithApple()
        XCTAssertEqual(account.message, "This Apple sign-in expired or is unavailable. Try again, or sign in by email first and then add Apple sign-in in account settings.")
        XCTAssertFalse(account.signedIn)
        XCTAssertNotNil(memory.value.providerChallenge)
        XCTAssertNil(memory.value.providerChallenge?.operation)
    }
    func testAppleAttachConflictAndRejectionKeepGuidance() async throws {
        for (status, expected) in [(409, "Sign in with your existing method first, then add Apple sign-in in account settings."), (401, "Couldn't add Apple sign-in. Try again.")] {
            let memory = Memory(); memory.value.session = session()
            let api = API { body, path in
                if path == "/v1/auth/provider/challenges" {
                    return try JSONSerialization.data(withJSONObject: ["challengeId": body["idempotencyId"]!, "expiresInSeconds": 900])
                }
                XCTAssertEqual(path, "/v1/me/identities")
                throw NativeAccountError.status(status)
            }
            let account = NativeAccountCoordinator(enabled: true, vault: memory, api: api, apple: FakeApple(spy: AppleSpy()), appleSignInAdvertised: true)
            await account.attachApple()
            XCTAssertEqual(account.message, expected, "status \(status)")
            XCTAssertTrue(account.signedIn)
            XCTAssertFalse(account.appleLinked)
            XCTAssertNotNil(memory.value.providerChallenge)
        }
    }
    func testAppleButtonNeedsAccountsAndAnAdvertisedDeployment() async throws {
        XCTAssertTrue(NativeAccountCoordinator(enabled: true, vault: Memory(), appleSignInAdvertised: true).appleSignInAvailable)
        XCTAssertFalse(NativeAccountCoordinator(enabled: true, vault: Memory(), appleSignInAdvertised: false).appleSignInAvailable)
        XCTAssertFalse(NativeAccountCoordinator(enabled: false, vault: Memory(), appleSignInAdvertised: true).appleSignInAvailable)
        let memory = Memory(), spy = AppleSpy()
        var calls = 0
        let disabled = NativeAccountCoordinator(enabled: false, vault: memory, api: API { _, _ in calls += 1; return Data() }, apple: FakeApple(spy: spy), appleSignInAdvertised: true)
        await disabled.signInWithApple()
        await disabled.attachApple()
        XCTAssertEqual(calls, 0)
        XCTAssertTrue(spy.nonces.isEmpty)
        XCTAssertEqual(disabled.message, NativeAccountError.unavailable.localizedDescription)
    }
    func testAppleChallengeUnavailableFallsBackToEmail() async throws {
        let memory = Memory(), spy = AppleSpy()
        let deployed = NativeAccountCoordinator(enabled: true, vault: memory, api: API { _, _ in throw NativeAccountError.status(401) }, apple: FakeApple(spy: spy), appleSignInAdvertised: true)
        await deployed.signInWithApple()
        XCTAssertEqual(deployed.message, "Sign in with Apple is unavailable. Try email sign-in instead.")
        XCTAssertTrue(spy.nonces.isEmpty)
        XCTAssertFalse(deployed.signedIn)
    }
    func testCheckSessionStoresAccountEmailAndLinkedProviders() async throws {
        let memory = Memory(); memory.value.session = session()
        let account = NativeAccountCoordinator(enabled: true, vault: memory, api: API { _, path in
            XCTAssertEqual(path, "/v1/me")
            return try JSONSerialization.data(withJSONObject: ["id": "12345678-1234-4234-8234-123456789abc", "email": "cook@example.com", "identities": [["provider": "apple"], ["provider": "unknown-future"]]])
        })
        await account.checkSession()
        XCTAssertEqual(account.message, "Your session is active.")
        XCTAssertEqual(account.accountEmail, "cook@example.com")
        XCTAssertEqual(account.linkedProviders, ["apple"])
    }

}

final class AccountTransportTests: XCTestCase {
    func testCredentialTransportBoundsResponsesRejectsRedirectsAndRedactsProviderErrors() async throws {
        for status in [200, 302, 401, 429, 999] {
            let id = UUID().uuidString; AccountURLProtocol.set(id, status: status)
            let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [AccountURLProtocol.self]
            config.httpAdditionalHeaders = ["X-PitTech-Unit": id]
            do {
                let data = try await NativeAccountClient(configuration: config).call("/v1/me", body: nil, access: String(repeating: "A", count: 43))
                XCTAssertEqual(status, 200); XCTAssertEqual(data, Data("{}".utf8))
            } catch {
                XCTAssertNotEqual(status, 200); XCTAssertFalse(error.localizedDescription.contains("private-provider-diagnostic"))
            }
        }
    }
}
private final class AccountURLProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private static var modes: [String: Int] = [:]
    static func set(_ id: String, status: Int) { lock.lock(); modes[id] = status; lock.unlock() }
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "api.pittech.app" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); let status = Self.modes.removeValue(forKey: request.value(forHTTPHeaderField: "X-PitTech-Unit") ?? "") ?? 401; Self.lock.unlock()
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer " + String(repeating: "A", count: 43))
        XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
        let bytes = status == 999 ? Data(repeating: 120, count: 8193) : Data((status == 200 ? "{}" : "private-provider-diagnostic").utf8)
        let response = HTTPURLResponse(url: request.url!, statusCode: status == 999 ? 200 : status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json", "Set-Cookie": "sensitive=discard", "Location": "https://attacker.invalid/"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: bytes); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }

}
