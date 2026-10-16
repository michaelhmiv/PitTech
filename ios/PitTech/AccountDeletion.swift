import Foundation

struct NativeDeletionReceipt: Codable {
    let deletionId: String
    let statusToken: String
    var state: String
    func validate() throws {
        guard UUID(uuidString: deletionId) != nil, NativeAccountProtocol.credential(statusToken), ["queued", "purging", "notifying", "complete"].contains(state) else { throw NativeAccountError.invalid }
    }
    var description: String {
        switch state {
        case "queued": return "Your account deletion is queued."
        case "purging": return "Original server data is being removed. Cleanup retries automatically if storage is unavailable."
        case "notifying": return "Your account and original server data have been removed. The completion email is being processed."
        default: return "Account deletion is complete. Your local journal and other cooks’ independent copies are separate."
        }
    }
}
/// Device-only recovery, separate from the journal and all export formats.
struct NativeDeletionRecord: Codable {
    var userId: String?
    let operationId: String
    var verifier: String?
    var receipt: NativeDeletionReceipt?
    func validate() throws {
        guard UUID(uuidString: operationId) != nil, userId.map({ UUID(uuidString: $0) != nil }) ?? true,
              verifier.map(NativeAccountProtocol.credential) ?? (receipt?.state == "complete"), receipt != nil || userId != nil else { throw NativeAccountError.invalid }
        try receipt?.validate()
    }
    var request: [String: String] {
        ["operationId": operationId, "proofChallenge": NativeAccountProtocol.proof(verifier ?? ""), "confirm": "delete-account", "policyVersion": "account-deletion-v1"]
    }
}
