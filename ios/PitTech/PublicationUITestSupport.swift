#if DEBUG
import SwiftUI

/// Deterministic UI automation only. All writes stay in memory; no server or mail is used.
@MainActor enum NativePublicationUITestSupport {
    static var active: Bool { CommandLine.arguments.contains("--publishing-ui-test") }
    static let directory = FileManager.default.temporaryDirectory.appendingPathComponent("publication-ui-" + UUID().uuidString)
    static let api = API()
    final class API: NativePublicationAPI {
        var receipts: [String: Data] = [:]
        var profile: [String: Any] = ["handle": "alex_at_the_pit", "displayName": "Alex at the pit", "isPublic": false]
        var item: [String: Any]?
        func read(_ path: String, access: String) async throws -> Data {
            let value: [String: Any]
            if path == "/v1/me/profile" { value = profile }
            else if path.hasPrefix("/v1/me/recipes/") { guard let item else { throw NativePublicError.status(404) }; value = item }
            else { value = ["items": item.map { [$0] } ?? [], "nextCursor": NSNull()] }
            return try JSONSerialization.data(withJSONObject: value)
        }
        func write(_ operation: NativePublicationOperation, file: URL, access: String) async throws -> Data {
            if let saved = receipts[operation.id] { return saved }
            let result: Data
            if operation.contentType == "application/zip" {
                let preview = try NativePreparedPublication.pending(file: file)
                if let item { guard operation.method == "PUT", operation.expectedRevision == item["revisionId"] as? String, operation.path == "/v1/recipes/" + (item["id"] as! String) else { throw NativePublicError.status(409) } }
                let id = "dddddddd-dddd-4ddd-dddd-dddddddddddd", revisionId = UUID().uuidString.lowercased(), revision = (item?["revision"] as? Int ?? 0) + 1
                item = ["id": id, "revisionId": revisionId, "revision": revision, "status": "active", "title": preview.payload.objectValue?["title"]?.stringValue ?? "", "identityMode": preview.payload.objectValue?["identityMode"]?.stringValue ?? "anonymous"]
                result = try JSONEncoder().encode(NativePublicationReceipt(id: id, revisionId: revisionId, revision: revision, url: "https://pittech.app/c/" + id))
            } else if operation.path == "/v1/me/profile" {
                profile = try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as! [String: Any]; result = try JSONSerialization.data(withJSONObject: profile)
            } else {
                if operation.method == "DELETE", !operation.path.hasSuffix("/like") { item?["status"] = "unpublished" }
                let id = String(operation.path.split(separator: "/")[2])
                result = try JSONSerialization.data(withJSONObject: operation.path.hasSuffix("/like") ? ["id": id, "liked": operation.method == "POST"] : ["id": id, "unpublished": true])
            }
            receipts[operation.id] = result; return result
        }
    }
}
struct NativePublicationUITestHost: View {
    @Environment(CookStore.self) private var store
    @State private var draft: String?
    @State private var message: String?
    var body: some View {
        NavigationStack {
            Group {
                if let draft { SharingDraftView(id: draft) }
                else if let message { Text(message) }
                else { ProgressView("Preparing selected recipe…") }
            }.toolbar { ToolbarItem(placement: .topBarTrailing) { NavigationLink("My cookbook") { NativeMyCookbookView() }.accessibilityIdentifier("publication-test-cookbook") } }
        }.task {
            do {
                let fixture = try JSONSerialization.jsonObject(with: NativePublicUITestFixture.data) as! [String: Any]
                let bytes = Data(base64Encoded: fixture["packBase64"] as! String)!, file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".pittech")
                try bytes.write(to: file); defer { try? FileManager.default.removeItem(at: file) }
                await store.receiveArchive(at: file, cookPackOnly: true)
                guard let intake = store.archiveIntake, let selected = intake.recipes.first(where: { $0.title.contains("pork") })?.id else { throw NativePublicationError.invalid }
                try await store.importSelectedRecipes(intake, selected: [selected]); store.discardArchiveIntake()
                let id = try store.createRecipeSharingDraft(selected); var document = try store.sharingDocument(id)
                document.entries[0].includeStory = true; document.entries[0].photoIds = store.sharingPhotos(id).compactMap { $0["id"]?.stringValue }
                try store.saveSharingDraft(id, document: document, expectedUpdated: store.sharingRecord(id)?["updated"]?.numberValue ?? 0); draft = id
            } catch { message = error.localizedDescription }
        }
    }
}
#endif

@MainActor extension NativePublicationWriter {
    static func forApp(session: any NativePublicationSession) -> NativePublicationWriter {
        #if DEBUG
        if NativePublicationUITestSupport.active { return NativePublicationWriter(session: session, store: NativePublicationOperationsStore(directory: NativePublicationUITestSupport.directory), api: NativePublicationUITestSupport.api) }
        #endif
        return NativePublicationWriter(session: session)
    }
}
