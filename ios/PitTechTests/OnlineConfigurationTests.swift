import XCTest
@testable import PitTech

final class OnlineConfigurationTests: XCTestCase {
    private func fixture() throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "online-environments-v1", withExtension: "json"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    func testSharedProfilesKeepOriginsLinksAndStoredRetriesInTheSameEnvironment() throws {
        let data = try fixture()
        let rows = try XCTUnwrap(data["profiles"] as? [[String: Any]])
        let id = "12345678-1234-4234-8234-123456789abc"
        let token = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        let code = "0123-4567-89AB-CDEF"
        for row in rows {
            let config = try NativeOnlineConfiguration(environment: XCTUnwrap(row["environment"] as? String))
            XCTAssertFalse(config.enabled)
            XCTAssertEqual(config.apiOrigin, row["apiOrigin"] as? String)
            XCTAssertEqual(config.publicOrigin, row["publicOrigin"] as? String)
            XCTAssertEqual(config.publicHost, row["publicHost"] as? String)
            XCTAssertEqual(config.linkScheme, row["linkScheme"] as? String)
            XCTAssertEqual(config.scopedName("saved-retry-v1"), row["scopedName"] as? String)
            XCTAssertEqual(config.sandbox, row["sandbox"] as? Bool)
            XCTAssertTrue(config.acceptsAPI(URL(string: config.apiOrigin + "/v1/me")))
            let link = try XCTUnwrap(URL(string: config.publicOrigin + "/auth#challenge=\(id)&token=\(token)"))
            XCTAssertEqual(try NativeAccountProtocol.link(link, configuration: config).0, id)
            XCTAssertEqual(try NativePublicLink.destination(config.publicOrigin + "/c/" + id, configuration: config), id)
            XCTAssertEqual(try NativePublicLink.destination(config.linkScheme + "://profile/chef", configuration: config), "profile:chef")
            XCTAssertEqual(try NativeRelayProtocol.code(config.publicOrigin + "/s/" + code, configuration: config), code)
            XCTAssertEqual(try NativeRelayProtocol.code(config.linkScheme + "://share/" + code, configuration: config), code)
            let other = try NativeOnlineConfiguration(environment: config.sandbox ? "production" : "staging")
            XCTAssertNotEqual(config.scopedName("saved-retry-v1"), other.scopedName("saved-retry-v1"))
            XCTAssertFalse(config.acceptsAPI(URL(string: other.apiOrigin + "/v1/me")))
            let otherLink = try XCTUnwrap(URL(string: other.publicOrigin + "/auth#challenge=\(id)&token=\(token)"))
            XCTAssertThrowsError(try NativeAccountProtocol.link(otherLink, configuration: config))
            XCTAssertThrowsError(try NativePublicLink.destination(other.publicOrigin + "/c/" + id, configuration: config))
            XCTAssertThrowsError(try NativeRelayProtocol.code(other.linkScheme + "://share/" + code, configuration: config))
            for value in try XCTUnwrap(data["invalidApiTemplates"] as? [String]) {
                XCTAssertFalse(config.acceptsAPI(URL(string: value.replacingOccurrences(of: "HOST", with: config.apiHost))), value)
            }
        }
    }

    func testAppleAudiencesAndAdvertisementFollowTheSharedEnvironments() throws {
        for row in try XCTUnwrap(fixture()["profiles"] as? [[String: Any]]) {
            let config = try NativeOnlineConfiguration(environment: XCTUnwrap(row["environment"] as? String))
            let audiences = try XCTUnwrap(row["appleAudiences"] as? [String])
            XCTAssertEqual(config.appleAudiences, audiences)
            for audience in audiences { XCTAssertTrue(config.advertisesAppleSignIn(bundleIdentifier: audience), audience) }
            XCTAssertFalse(config.advertisesAppleSignIn(bundleIdentifier: "com.pittech.unconfigured"))
            XCTAssertFalse(config.advertisesAppleSignIn(bundleIdentifier: nil))
        }
        XCTAssertTrue(try NativeOnlineConfiguration(environment: "staging").advertisesAppleSignIn(bundleIdentifier: "com.pittech"))
        XCTAssertFalse(try NativeOnlineConfiguration(environment: "production").advertisesAppleSignIn(bundleIdentifier: "com.pittech.staging"))
    }

    func testUnknownProfilesAreRejectedAndEnablementIsExplicit() throws {
        for environment in try XCTUnwrap(fixture()["invalidEnvironments"] as? [String]) {
            XCTAssertThrowsError(try NativeOnlineConfiguration(environment: environment, enabled: true))
        }
        for environment in ["production", "staging"] {
            XCTAssertFalse(try NativeOnlineConfiguration(environment: environment).enabled)
            XCTAssertTrue(try NativeOnlineConfiguration(environment: environment, enabled: true).enabled)
        }
        XCTAssertEqual(NativeAccountProtocol.enabled, NativeOnlineConfiguration.current.enabled)
    }
}
