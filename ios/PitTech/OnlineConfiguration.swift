import Foundation

enum NativeOnlineConfigurationError: Error { case invalid }

/// First-party endpoints are selected together at build time; arbitrary origins are never accepted.
struct NativeOnlineConfiguration: Equatable, Sendable {
    let environment: String
    let enabled: Bool
    init(environment: String, enabled: Bool = false) throws {
        guard ["production", "staging"].contains(environment) else { throw NativeOnlineConfigurationError.invalid }
        self.environment = environment
        self.enabled = enabled
    }
    var sandbox: Bool { environment == "staging" }
    var apiHost: String { sandbox ? "api-staging.pittech.app" : "api.pittech.app" }
    var publicHost: String { sandbox ? "staging.pittech.app" : "pittech.app" }
    var apiOrigin: String { "https://" + apiHost }
    var publicOrigin: String { "https://" + publicHost }
    var linkScheme: String { sandbox ? "pittech-staging" : "pittech" }
    func scopedName(_ productionName: String) -> String { sandbox ? productionName + "-staging" : productionName }
    /// Bundle IDs the deployment's Apple audience set accepts. The staging set
    /// covers both the staging identity and the registered identity the approved
    /// store-sandbox build keeps; production accepts the registered identity only.
    var appleAudiences: [String] { sandbox ? ["com.pittech.staging", "com.pittech"] : ["com.pittech"] }
    /// No provider button ships in a build whose deployment cannot verify that
    /// provider's tokens: the running bundle must be an accepted Apple audience.
    func advertisesAppleSignIn(bundleIdentifier: String? = Bundle.main.bundleIdentifier) -> Bool {
        guard let bundleIdentifier else { return false }
        return appleAudiences.contains(bundleIdentifier)
    }
    func acceptsAPI(_ input: URL?) -> Bool {
        guard let input, let parts = URLComponents(url: input, resolvingAgainstBaseURL: false) else { return false }
        return parts.scheme == "https" && parts.host == apiHost && parts.port == nil &&
            parts.user == nil && parts.password == nil && parts.fragment == nil &&
            input.absoluteString.hasPrefix(apiOrigin + "/")
    }

    static let current: NativeOnlineConfiguration = {
        let bundle = Bundle.main
        let environment = bundle.object(forInfoDictionaryKey: "PITTECHOnlineEnvironment") as? String ?? "production"
        let flag = bundle.object(forInfoDictionaryKey: "PITTECHOnlineEnabled") as? String ?? "NO"
        // Unknown metadata cannot enable account/community entry. The transport still uses fixed origins.
        guard ["YES", "NO"].contains(flag),
              let value = try? NativeOnlineConfiguration(environment: environment, enabled: flag == "YES"),
              bundle.object(forInfoDictionaryKey: "PITTECHPublicHost") as? String == value.publicHost,
              bundle.object(forInfoDictionaryKey: "PITTECHLinkScheme") as? String == value.linkScheme,
              !value.sandbox || bundle.bundleIdentifier == "com.pittech.staging" ||
                  (bundle.bundleIdentifier == "com.pittech" && bundle.object(forInfoDictionaryKey: "PITTECHStoreSandboxBuild") as? String == "YES") else {
            return try! NativeOnlineConfiguration(environment: "production", enabled: false)
        }
        return value
    }()
}
