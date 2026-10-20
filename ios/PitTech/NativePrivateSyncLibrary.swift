import Foundation

struct NativeSyncUpsert { var type: String; var id: String; var payload: [String: JSONValue] }
struct NativeSyncDelete { var type: String; var id: String }
struct NativeSyncApplyPlan { var upserts: [NativeSyncUpsert]; var deletes: [NativeSyncDelete]; var attachments: [String: Data] }

/** Library boundary for private sync. Build reads the full journal; apply writes one
 * validated merge result. Runtime activation never imports; finishing/deleting a
 * meal only cancels that meal's local recording, timers and deliveries. */
@MainActor protocol NativeSyncLibrary: AnyObject {
    func archive() throws -> JSONValue
    func photoData(type: String, id: String) throws -> (Data, String)?
    func apply(_ plan: NativeSyncApplyPlan) throws
}

@MainActor final class NativeCookStoreSyncLibrary: NativeSyncLibrary {
    private let store: CookStore
    private static let photoKinds: Set<String> = ["photos", "recipeAssets", "companionPhotos"]
    nonisolated init(_ store: CookStore) { self.store = store }
    func archive() throws -> JSONValue { try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(store.library)) }
    func photoData(type: String, id: String) throws -> (Data, String)? {
        guard Self.photoKinds.contains(type), let row = rows(store.library, type).first(where: { $0.objectValue?[NativePrivateSyncProtocol.keyField(type)]?.stringValue == id })?.objectValue,
              let path = row["archivePath"]?.stringValue, let mime = row["mimeType"]?.stringValue,
              let data = try? Data(contentsOf: store.photoURL(path)) else { return nil }
        return (data, mime)
    }
    func apply(_ plan: NativeSyncApplyPlan) throws {
        for upsert in plan.upserts { guard upsert.payload[NativePrivateSyncProtocol.keyField(upsert.type)]?.stringValue == upsert.id else { throw NativeSyncError.invalid } }
        var next = store.library
        var copied: [URL] = []
        // Tombstoned and relocated photo paths must be captured before the rows change.
        var removed: [URL] = []
        for tombstone in plan.deletes where Self.photoKinds.contains(tombstone.type) {
            if let path = rows(next, tombstone.type).first(where: { $0.objectValue?[NativePrivateSyncProtocol.keyField(tombstone.type)]?.stringValue == tombstone.id })?.objectValue?["archivePath"]?.stringValue { removed.append(store.photoURL(path)) }
        }
        for upsert in plan.upserts where plan.attachments[upsert.id] != nil {
            if let path = rows(next, upsert.type).first(where: { $0.objectValue?[NativePrivateSyncProtocol.keyField(upsert.type)]?.stringValue == upsert.id })?.objectValue?["archivePath"]?.stringValue, path != photoPath(upsert) { removed.append(store.photoURL(path)) }
        }
        do {
            try FileManager.default.createDirectory(at: store.photoURL("attachments"), withIntermediateDirectories: true)
            for (id, bytes) in plan.attachments {
                guard let upsert = plan.upserts.first(where: { $0.id == id && Self.photoKinds.contains($0.type) }), (1...NativePrivateSyncProtocol.maxPhotoBytes).contains(bytes.count) else { throw NativeSyncError.invalid }
                let url = store.photoURL(photoPath(upsert))
                try bytes.write(to: url, options: .atomic)
                copied.append(url)
            }
            for tombstone in plan.deletes { next = try removing(next, tombstone) }
            for upsert in plan.upserts { next = try overlaying(next, upsert, relocated: plan.attachments[upsert.id] != nil) }
            let removedCooks = Set(store.library.cooks.map(\.id)).subtracting(next.cooks.map(\.id))
            let terminal = removedCooks.union(next.cooks.filter { !$0.isActive }.map(\.id))
            let removedRules = Set(store.library.reminderRules.compactMap { $0.objectValue?["id"]?.stringValue }).subtracting(next.reminderRules.compactMap { $0.objectValue?["id"]?.stringValue })
            let canceledRules = removedRules.union(store.library.reminderRules.compactMap { row -> String? in
                guard let o = row.objectValue, let cook = o["cookId"]?.stringValue, terminal.contains(cook) else { return nil }; return o["id"]?.stringValue
            })
            let cancellations = store.library.reminderOccurrences.compactMap { row -> String? in
                guard let o = row.objectValue, let rule = o["ruleId"]?.stringValue, canceledRules.contains(rule), let id = o["id"]?.stringValue else { return nil }; return "meal-reminder:" + id
            }
            func cancelRuntime(_ rows: [JSONValue], status: String, active: Set<String>) -> [JSONValue] {
                rows.compactMap { row in
                    guard var o = row.objectValue, let cook = o["cookId"]?.stringValue else { return row }
                    if removedCooks.contains(cook) { return nil }
                    if terminal.contains(cook), active.contains(o["status"]?.stringValue ?? "") { o["status"] = .string(status) }
                    return .object(o)
                }
            }
            next.activityTimers = cancelRuntime(next.activityTimers, status: "cancelled", active: ["running"])
            next.recordings = cancelRuntime(next.recordings, status: "stopped", active: ["recording", "paused", "connecting", "recovering"])
            next.reminders = cancelRuntime(next.reminders, status: "cancelled", active: ["pending", "scheduled"])
            next.probeAssignments.removeAll { removedCooks.contains($0.objectValue?["cookId"]?.stringValue ?? "") }
            next.reminderOccurrences.removeAll { removedRules.contains($0.objectValue?["ruleId"]?.stringValue ?? "") }
            // The merged library must validate exactly like an archive import before it replaces anything.
            try next.validateCookExperience()
            let prior = store.library; store.library = next; store.save()
            guard store.saveError == nil else { store.library = prior; throw NativeSyncError.invalid }
            store.submitReminderSchedules(NativeReminderReconciliation(cancellations: cancellations))
        } catch {
            for url in copied { try? FileManager.default.removeItem(at: url) }
            throw error
        }
        for url in removed { try? FileManager.default.removeItem(at: url) }
    }
    private func rows(_ library: PitTechLibrary, _ type: String) -> [JSONValue] {
        switch type {
        case "dishes": return library.dishes; case "ingredients": return library.ingredients; case "events": return library.events
        case "readings": return library.readings; case "targets": return library.targets; case "results": return library.results
        case "devices": return library.devices; case "probes": return library.probes; case "photos": return library.photos
        case "recipes": return library.recipes; case "recipeRevisions": return library.recipeRevisions; case "recipeAssets": return library.recipeAssets
        case "companionRecords": return library.companionRecords; case "companionPhotos": return library.companionPhotos; case "mealPresets": return library.mealPresets
        case "dishRecipeSources": return library.dishRecipeSources; case "lifecycles": return library.lifecycles; case "targetHistory": return library.targetHistory
        case "logReadingLinks": return library.logReadingLinks; case "reminderRules": return library.reminderRules; default: return []
        }
    }
    private func setting(_ library: PitTechLibrary, _ type: String, _ value: [JSONValue]) -> PitTechLibrary {
        var next = library
        switch type {
        case "dishes": next.dishes = value; case "ingredients": next.ingredients = value; case "events": next.events = value
        case "readings": next.readings = value; case "targets": next.targets = value; case "results": next.results = value
        case "devices": next.devices = value; case "probes": next.probes = value; case "photos": next.photos = value
        case "recipes": next.recipes = value; case "recipeRevisions": next.recipeRevisions = value; case "recipeAssets": next.recipeAssets = value
        case "companionRecords": next.companionRecords = value; case "companionPhotos": next.companionPhotos = value; case "mealPresets": next.mealPresets = value
        case "dishRecipeSources": next.dishRecipeSources = value; case "lifecycles": next.lifecycles = value; case "targetHistory": next.targetHistory = value
        case "logReadingLinks": next.logReadingLinks = value; case "reminderRules": next.reminderRules = value; default: break
        }
        return next
    }
    private static let knownTypes: Set<String> = ["cooks", "dishes", "ingredients", "events", "readings", "targets", "results", "devices", "probes", "photos", "recipes", "recipeRevisions", "recipeAssets", "companionRecords", "companionPhotos", "mealPresets", "dishRecipeSources", "lifecycles", "targetHistory", "logReadingLinks", "reminderRules"]
    private func removing(_ library: PitTechLibrary, _ tombstone: NativeSyncDelete) throws -> PitTechLibrary {
        guard Self.knownTypes.contains(tombstone.type) else { throw NativeSyncError.invalid }
        let key = NativePrivateSyncProtocol.keyField(tombstone.type)
        if tombstone.type == "cooks" {
            var next = library; next.cooks.removeAll { $0.id == tombstone.id }; return next
        }
        return setting(library, tombstone.type, rows(library, tombstone.type).filter { $0.objectValue?[key]?.stringValue != tombstone.id })
    }
    private func overlaying(_ library: PitTechLibrary, _ upsert: NativeSyncUpsert, relocated: Bool) throws -> PitTechLibrary {
        if upsert.type == "cooks" {
            var next = library
            let record = try JSONDecoder().decode(CookRecord.self, from: JSONEncoder().encode(JSONValue.object(upsert.payload)))
            guard record.id == upsert.id else { throw NativeSyncError.invalid }
            next.cooks.removeAll { $0.id == record.id }; next.cooks.append(record)
            return next
        }
        guard Self.knownTypes.contains(upsert.type) else { throw NativeSyncError.invalid }
        let key = NativePrivateSyncProtocol.keyField(upsert.type)
        var value = rows(library, upsert.type)
        if let index = value.firstIndex(where: { $0.objectValue?[key]?.stringValue == upsert.id }) {
            // Overlay portable fields over the local row, preserving device-only keys such
            // as archive paths and rule activation. Existing local state is never armed.
            var merged = value[index].objectValue ?? [:]
            let changed = upsert.payload.contains { (merged[$0.key] ?? .null) != $0.value }
            if changed && upsert.type == "recipeRevisions" { throw NativeSyncError.invalid }
            if changed && upsert.type == "readings" {
                guard merged["source"]?.stringValue == "manual", upsert.payload["source"]?.stringValue == "manual",
                      ["id", "cookId", "sourceDeviceId", "timestampBasis", "samplingIntervalMillis"].allSatisfy({ (merged[$0] ?? .null) == (upsert.payload[$0] ?? .null) }) else { throw NativeSyncError.invalid }
            }
            for (field, content) in upsert.payload { merged[field] = content }
            if relocated, Self.photoKinds.contains(upsert.type) { merged["archivePath"] = .string(photoPath(upsert)) }
            if upsert.type == "companionRecords" {
                if let created = merged["created"]?.numberValue { merged["createdAtUtcMillis"] = .number(created) }
                if let updated = merged["updated"]?.numberValue { merged["updatedAtUtcMillis"] = .number(updated) }
            }
            value[index] = .object(merged)
        } else {
            var row = upsert.payload
            if upsert.type == "reminderRules" {
                // Imported definitions arrive inactive and create no delivery state.
                // Enabling one is an explicit local adoption.
                row["enabled"] = .bool(false); row["status"] = .string("inactive")
            }
            if upsert.type == "companionRecords" {
                if let created = row["created"]?.numberValue { row["createdAtUtcMillis"] = .number(created) }
                if let updated = row["updated"]?.numberValue { row["updatedAtUtcMillis"] = .number(updated) }
            }
            if Self.photoKinds.contains(upsert.type) {
                row["archivePath"] = .string(photoPath(upsert))
                if upsert.type == "photos" { row["originalFileName"] = .string(upsert.id + "." + photoExtension(upsert.payload["mimeType"]?.stringValue ?? "image/jpeg")) }
            }
            value.append(.object(row))
        }
        return setting(library, upsert.type, value)
    }
    private func photoExtension(_ mime: String) -> String { mime == "image/png" ? "png" : mime == "image/webp" ? "webp" : "jpg" }
    private func photoPath(_ row: NativeSyncUpsert) -> String { "attachments/" + row.id + "." + photoExtension(row.payload["mimeType"]?.stringValue ?? "image/jpeg") }
}
