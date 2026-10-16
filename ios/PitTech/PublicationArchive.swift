import Foundation
import ZIPFoundation

struct NativePublicationPhoto: Sendable {
    let id: String
    let entryId: String
    let url: URL
    let caption: String
}
struct NativePreparedPublication: Sendable {
    let payload: JSONValue
    let bytes: Data
    let photos: [String: Data]
}
enum NativePublicationError: LocalizedError {
    case invalid, limits, accountChanged, pending
    var errorDescription: String? {
        switch self {
        case .limits: return "This public preview exceeds the sharing limits. Choose fewer dishes, photos or recorded stories."
        case .accountChanged: return "Sign in with the account that started this publication to recover it. Your draft stays on this device."
        case .pending: return "Recover the previous publication before starting another. Its exact preview is saved on this device."
        case .invalid: return "Some selected content cannot be published in this format. Review the title, instructions, photos and recorded story. Your draft is unchanged."
        }
    }
}
/// Public IDs are stable aliases of random local record identities, never account/device IDs.
enum NativePublicationProjection {
    static func id(_ value: String) -> String {
        let hash = NativeRelayProtocol.hash(Data(("PitTech public v1:" + value).utf8))
        let characters = Array(hash.prefix(32)); let hex = String(characters)
        return String(hex.prefix(8)) + "-" + String(hex.dropFirst(8).prefix(4)) + "-5" + String(hex.dropFirst(13).prefix(3)) + "-a" + String(hex.dropFirst(17).prefix(3)) + "-" + String(hex.dropFirst(20).prefix(12))
    }
    private static func text(_ value: String, _ max: Int) throws -> JSONValue {
        guard value.utf16.count <= max else { throw NativePublicationError.limits }; return .string(value)
    }
    private static func optional(_ value: String?, _ max: Int) throws -> JSONValue { try value.map { try text($0, max) } ?? .null }
    private static func offset(_ value: Double) throws -> JSONValue {
        guard value.isFinite, value.rounded() == value, (-604_800_000...2_592_000_000).contains(value) else { throw NativePublicationError.invalid }; return .number(value)
    }
    private static func source(_ value: PublicRecipeSource?) -> JSONValue {
        guard let value else { return .null }
        return .object(["itemId": .string(value.itemId), "revisionId": .string(value.revisionId), "removed": .bool(value.removed)])
    }
    private static func story(_ entry: NativeSharingEntry, actualMeal: Bool) throws -> JSONValue {
        guard entry.includeStory else { return .null }
        let raw = entry.story.objectValue ?? [:]
        guard let attempt = raw["attemptId"]?.stringValue, !attempt.isEmpty else { throw NativePublicationError.invalid }
        let snapshot = try entry.snapshot
        if snapshot.publicSource != nil, !actualMeal, let frozen = raw["publicStory"]?.objectValue {
            guard frozen["attemptId"]?.stringValue == attempt else { throw NativePublicationError.invalid }
            return .object(frozen)
        }
        let observations = (raw["observations"]?.arrayValue ?? []).compactMap(\.objectValue)
        let readings = (raw["readings"]?.arrayValue ?? []).compactMap(\.objectValue)
        let anchor = raw["anchorUtcMillis"]?.numberValue
        let origin = anchor ?? (observations.compactMap { $0["occurredAtUtcMillis"]?.numberValue } + readings.compactMap { $0["measuredAtUtcMillis"]?.numberValue }).min() ?? 0
        let pitTypes: Set<String> = ["pit", "pit_ambient", "grill_temperature", "pit_temperature", "pit_probe", "ambient_temperature"]
        var channelIds: [String: String] = [:], channels: [JSONValue] = [], samples: [JSONValue] = []
        for row in readings {
            if row["measurementType"]?.stringValue == "setpoint" { continue } // A target is not a measured pit temperature.
            let rawRole = row["measurementType"]?.stringValue ?? ""
            guard pitTypes.contains(rawRole) || ["food", "food_probe", "internal_temperature"].contains(rawRole) else { throw NativePublicationError.invalid }
            let role = pitTypes.contains(rawRole) ? "pit" : "food"
            let manual = row["source"]?.stringValue == "manual"
            let key = role + ":" + (row["probeId"]?.stringValue ?? (manual ? "manual" : row["id"]?.stringValue ?? ""))
            if channelIds[key] == nil {
                let channel = id("channel:" + attempt + ":" + key); channelIds[key] = channel
                channels.append(.object(["id": .string(channel), "role": .string(role), "label": try text(manual ? "Manual readings" : row["probeName"]?.stringValue ?? "Recorded probe", 160)]))
            }
            guard channels.count <= 32, let value = row["value"]?.numberValue, let at = row["measuredAtUtcMillis"]?.numberValue,
                  let unit = row["unit"]?.stringValue, ["°C", "°F"].contains(unit) else { throw NativePublicationError.invalid }
            let celsius = unit == "°C" ? value : (value - 32) * 5 / 9
            guard celsius.isFinite, (-100...1000).contains(celsius) else { throw NativePublicationError.invalid }
            samples.append(.object(["channelId": .string(channelIds[key]!), "role": .string(role), "offsetMillis": try offset(at - origin), "valueCelsius": .number(celsius), "quality": .string(manual ? "manual" : row["qualityStatus"]?.stringValue == "recovered" ? "recovered" : "measured")]))
        }
        guard samples.count <= 250000, observations.count <= 1000 else { throw NativePublicationError.limits }
        let milestones = try observations.map { row -> JSONValue in
            guard let at = row["occurredAtUtcMillis"]?.numberValue, let identity = row["sourceEventId"]?.stringValue else { throw NativePublicationError.invalid }
            let title = row["title"]?.stringValue ?? "", details = row["details"]?.stringValue ?? ""
            return .object(["id": .string(id("event:" + identity)), "offsetMillis": try offset(at - origin), "action": try text(row["action"]?.stringValue ?? "note", 80), "text": try text(title + (details.isEmpty ? "" : " — " + details), 2000)])
        }
        let off = observations.filter { ["remove", "rest_start", "hold_start", "dish_done"].contains($0["action"]?.stringValue ?? "") }.compactMap { $0["occurredAtUtcMillis"]?.numberValue }.max()
        // The immutable story's results own recorded verdicts. Editable recipe instructions/notes do not.
        let results = (raw["results"]?.arrayValue ?? []).compactMap(\.objectValue)
        let rating = results.first { ["overall_rating", "overall"].contains($0["type"]?.stringValue ?? "") }?["numericValue"]?.numberValue
        let again = results.first { $0["type"]?.stringValue == "cook_again" }?["numericValue"]?.numberValue
        let notes = results.compactMap { $0["textValue"]?.stringValue }.joined(separator: "\n")
        guard rating.map({ (1...5).contains($0) && $0.rounded() == $0 }) ?? true else { throw NativePublicationError.invalid }
        let attemptId = snapshot.publicSource != nil && !actualMeal ? attempt : id("attempt:" + attempt)
        guard UUID(uuidString: attemptId) != nil else { throw NativePublicationError.invalid }
        let markers = raw["connectionGaps"]?.arrayValue ?? []
        guard markers.count * channels.count <= 1000 else { throw NativePublicationError.limits }
        let gaps = try channels.flatMap { channel -> [JSONValue] in
            try markers.map { marker in
                guard let at = marker.numberValue else { throw NativePublicationError.invalid }
                let position = try offset(at - origin)
                return .object(["channelId": channel.objectValue?["id"] ?? .null, "fromMillis": position, "toMillis": position])
            }
        }
        return .object(["attemptId": .string(attemptId), "foodOnOffsetMillis": try anchor.map { try offset($0 - origin) } ?? .null,
            "foodOffOffsetMillis": try off.map { try offset($0 - origin) } ?? .null, "channels": .array(channels), "measurements": .array(samples), "milestones": .array(milestones),
            "verdict": .object(["rating": rating.map(JSONValue.number) ?? .null, "cookAgain": again.map { .bool($0 == 1) } ?? .null, "notes": try text(notes, 2000)]),
            "gaps": .array(gaps)]) // Local outage markers have unknown duration; never invent an end time.
    }
    static func build(_ document: NativeSharingDocument, updated: Double, photos: [NativePublicationPhoto], kind: String) throws -> NativePreparedPublication {
        try document.validate(); let selected = document.entries.filter(\.included)
        guard (1...20).contains(selected.count), ["recipe", "collection", "attempt"].contains(kind), kind != "recipe" || selected.count == 1,
              kind != "attempt" || document.sourceCookId != nil && selected.count == 1 && selected[0].includeStory else { throw NativePublicationError.invalid }
        var publicPhotos: [String: Data] = [:], recipes: [JSONValue] = [], expanded = 0
        for entry in selected {
            try Task.checkCancellation(); let snapshot = try entry.snapshot
            let setup = snapshot.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue ?? [:]
            let unit = setup["weightUnit"]?.stringValue ?? "lb", weight = Double(setup["weight"]?.stringValue ?? "")
            let multipliers: [String: Double] = ["g": 1, "kg": 1000, "lb": 453.59237, "lbs": 453.59237, "oz": 28.349523125]
            if weight != nil && multipliers[unit] == nil { throw NativePublicationError.invalid }
            let grams = weight.map { $0 * (multipliers[unit] ?? 1) }
            guard grams.map({ $0.isFinite && $0 > 0 && $0 <= 1_000_000 }) ?? true, snapshot.tags.count <= 20, snapshot.instructions.count <= 100, entry.photoIds.count <= 30 else { throw NativePublicationError.limits }
            let ingredients = try (setup["ingredients"]?.arrayValue ?? []).map { value -> JSONValue in
                let row = value.objectValue ?? [:], amount = rowString(value, "amount"), unit = rowString(value, "unit")
                return .object(["name": try text(row["name"]?.stringValue ?? "", 160), "amount": try text([amount, unit].filter { !$0.isEmpty }.joined(separator: " "), 2000)])
            }
            let instructions = try snapshot.instructions.map { value -> JSONValue in
                let row = value.objectValue ?? [:]
                guard let identity = row["id"]?.stringValue else { throw NativePublicationError.invalid }
                return .object(["id": .string(id("instruction:" + identity)), "title": try text(row["title"]?.stringValue ?? "", 160), "details": try text(row["details"]?.stringValue ?? "", 2000),
                    "method": try optional(row["method"]?.stringValue, 160), "action": try optional(row["action"]?.stringValue, 80), "suggestedOffsetMillis": try row["offsetMillis"]?.numberValue.map(offset) ?? .null])
            }
            var assets: [JSONValue] = []
            for photoId in entry.photoIds {
                guard let photo = photos.first(where: { $0.id == photoId && $0.entryId == entry.id }) else { throw NativePublicationError.invalid }
                let assetId = id("photo:" + photoId), path = "attachments/" + assetId + ".jpg"
                let bytes = try NativeCookPackPhoto.derivative(Data(contentsOf: photo.url)); expanded += bytes.count
                guard expanded <= 64 * 1024 * 1024 else { throw NativePublicationError.limits }
                publicPhotos[assetId] = bytes
                assets.append(.object(["id": .string(assetId), "path": .string(path), "mimeType": .string("image/jpeg"), "sha256": .string(NativeRelayProtocol.hash(bytes)), "caption": try text(photo.caption, 2000)]))
            }
            recipes.append(.object(["id": .string(id("recipe:" + entry.id)), "revisionId": .string(id("revision:" + entry.id + ":" + String(Int64(updated)))),
                "title": try text(snapshot.name, 160), "description": try text(snapshot.description, 2000), "foodType": try text(setup["foodType"]?.stringValue ?? "", 160), "cut": try optional(setup["cut"]?.stringValue, 160),
                "weightGrams": grams.map(JSONValue.number) ?? .null, "method": .null, "ingredients": .array(ingredients), "instructions": .array(instructions), "tags": .array(try snapshot.tags.map { try text($0, 40) }),
                "story": try story(entry, actualMeal: document.sourceCookId != nil), "assets": .array(assets), "source": source(snapshot.publicSource)]))
        }
        let attempts = recipes.compactMap { $0.objectValue?["story"]?.objectValue?["attemptId"]?.stringValue }
        guard Set(attempts).count == attempts.count else { throw NativePublicationError.invalid }
        let roots = try selected.map { try $0.snapshot.publicSource }
        let commonSource: PublicRecipeSource? = roots.first.flatMap { first in guard let first, roots.allSatisfy({ $0?.itemId == first.itemId && $0?.revisionId == first.revisionId }) else { return nil }; return first }
        let payload: JSONValue = .object(["contractVersion": .number(1), "kind": .string(kind), "identityMode": .string(document.identityMode), "title": try text(document.title, 160), "recipes": .array(recipes),
            "source": source(commonSource), "measuredAttemptId": kind == "attempt" ? recipes[0].objectValue?["story"]?.objectValue?["attemptId"] ?? .null : .null,
            "heroAssetId": document.heroPhotoId.map { .string(id("photo:" + $0)) } ?? .null])
        return try package(payload, photos: publicPhotos)
    }
    static func package(_ payload: JSONValue, photos: [String: Data]) throws -> NativePreparedPublication {
        guard let recipes = payload.objectValue?["recipes"]?.arrayValue, (1...20).contains(recipes.count) else { throw NativePublicationError.invalid }
        var attachments: [String: Data] = [:], ids = Set<String>()
        for recipe in recipes {
            for asset in recipe.objectValue?["assets"]?.arrayValue ?? [] {
                guard let row = asset.objectValue, let id = row["id"]?.stringValue, UUID(uuidString: id) != nil, ids.insert(id).inserted,
                      row["path"]?.stringValue == "attachments/" + id + ".jpg", row["mimeType"]?.stringValue == "image/jpeg", let bytes = photos[id],
                      bytes.count <= 16 * 1024 * 1024, NativeRelayProtocol.hash(bytes) == row["sha256"]?.stringValue else { throw NativePublicationError.invalid }
                attachments["attachments/" + id + ".jpg"] = bytes
            }
        }
        guard ids == Set(photos.keys) else { throw NativePublicationError.invalid }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let json = try encoder.encode(payload)
        guard json.count <= 8 * 1024 * 1024, json.count + photos.values.reduce(0, { $0 + $1.count }) <= 64 * 1024 * 1024, attachments.count + 1 <= 1000 else { throw NativePublicationError.limits }
        let archive = try Archive(accessMode: .create)
        for (path, bytes) in ["publication.json": json].merging(attachments, uniquingKeysWith: { a, _ in a }).sorted(by: { $0.key < $1.key }) {
            try archive.addEntry(with: path, type: .file, uncompressedSize: Int64(bytes.count), compressionMethod: .deflate) { at, size in bytes.subdata(in: Int(at)..<(Int(at) + size)) }
        }
        guard let bytes = archive.data, bytes.count <= 64 * 1024 * 1024 else { throw NativePublicationError.limits }
        return NativePreparedPublication(payload: payload, bytes: bytes, photos: photos)
    }
    private static func rowString(_ row: JSONValue, _ key: String) -> String { row.objectValue?[key]?.stringValue ?? "" }
}
@MainActor extension CookStore {
    func preparePublication(_ id: String, kind: String, editContext: NativePublicationEditContext? = nil) async throws -> NativePreparedPublication {
        let document = try sharingDocument(id), updated = sharingRecord(id)?["updated"]?.numberValue ?? 0
        let photos = try sharingPhotos(id).map { row -> NativePublicationPhoto in
            guard let id = row["id"]?.stringValue, let entry = row["action"]?.stringValue, let path = row["archivePath"]?.stringValue else { throw NativePublicationError.invalid }
            return NativePublicationPhoto(id: id, entryId: entry, url: photoURL(path), caption: row["caption"]?.stringValue ?? "")
        }
        let task = Task.detached(priority: .userInitiated) {
            let projected = try NativePublicationProjection.build(document, updated: updated, photos: photos, kind: kind == "attempt" && editContext?.receipt.kind == "attempt" ? "recipe" : kind)
            return try editContext?.apply(projected, document: document, kind: kind) ?? projected
        }
        return try await withTaskCancellationHandler(operation: { try await task.value }, onCancel: { task.cancel() })
    }
}

extension NativePreparedPublication {
    /// Read only a verified, device-owned pending archive for its exact retry preview.
    static func pending(file: URL) throws -> NativePreparedPublication {
        let archive = try Archive(url: file, accessMode: .read)
        func read(_ path: String, limit: Int) throws -> Data {
            guard let entry = archive[path], entry.type == .file, entry.uncompressedSize <= UInt64(limit) else { throw NativePublicationError.invalid }
            var bytes = Data()
            let crc = try archive.extract(entry) { chunk in
                guard bytes.count + chunk.count <= limit else { throw NativePublicationError.limits }; bytes.append(chunk)
            }
            guard crc == entry.checksum else { throw NativePublicationError.invalid }; return bytes
        }
        let payload = try JSONDecoder().decode(JSONValue.self, from: read("publication.json", limit: 8 * 1024 * 1024))
        guard let recipes = payload.objectValue?["recipes"]?.arrayValue, (1...20).contains(recipes.count) else { throw NativePublicationError.invalid }
        var photos: [String: Data] = [:]
        for recipe in recipes {
            for asset in recipe.objectValue?["assets"]?.arrayValue ?? [] {
                guard let row = asset.objectValue, let id = row["id"]?.stringValue, UUID(uuidString: id) != nil,
                      let mime = row["mimeType"]?.stringValue, let suffix = ["image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"][mime],
                      row["path"]?.stringValue == "attachments/" + id + "." + suffix else { throw NativePublicationError.invalid }
                let bytes = try read("attachments/" + id + "." + suffix, limit: 16 * 1024 * 1024)
                guard NativeRelayProtocol.hash(bytes) == row["sha256"]?.stringValue else { throw NativePublicationError.invalid }; photos[id] = bytes
            }
        }
        return NativePreparedPublication(payload: payload, bytes: try Data(contentsOf: file), photos: photos)
    }
}
