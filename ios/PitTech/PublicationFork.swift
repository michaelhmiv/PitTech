import Foundation

enum PublicationForkError: Error, Equatable {
    case invalid(String)
}

extension PublicationForkError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .invalid(let message): return message
        }
    }
}

/// What a Discover card can honestly show beyond its title (Slice 5
/// §4.1): the hero recipe's recorded duration and pit temperature, from
/// the detail payload the tap would open anyway. A slot is nil when the
/// story doesn't carry it — absence renders as absence.
struct PublicCardFacts: Equatable {
    var timeLabel: String?
    var tempLabel: String?
}

/// The hero recipe is the one carrying the payload's hero asset (else
/// the first); its story's food-on → food-off span is the time, and the
/// median of its pit-channel measurements — converted to the reader's
/// unit — is the temp. Mirrors Android's publicCardFacts.
func publicCardFacts(detail: NativePublicDetail, temperatureUnit: String) -> PublicCardFacts {
    let heroAssetId = detail.payload.objectValue?["heroAssetId"]?.stringValue
    let heroRecipe = detail.recipes.first { recipe in
        (recipe.objectValue?["assets"]?.arrayValue ?? []).contains {
            $0.objectValue?["id"]?.stringValue == heroAssetId
        }
    } ?? detail.recipes.first
    let story = heroRecipe?.objectValue?["story"]?.objectValue
    var timeLabel: String?
    if let on = story?["foodOnOffsetMillis"]?.numberValue, let off = story?["foodOffOffsetMillis"]?.numberValue {
        let total = Int64((off - on) / 60_000)
        if total > 0 {
            let hours = total / 60, minutes = total % 60
            timeLabel = if hours > 0, minutes > 0 { "\(hours)H \(minutes)M" } else if hours > 0 { "\(hours)H" } else { "\(minutes)M" }
        }
    }
    var tempLabel: String?
    let pitValues = (story?["measurements"]?.arrayValue ?? [])
        .compactMap(\.objectValue)
        .filter { $0["role"]?.stringValue == "pit" }
        .compactMap { $0["valueCelsius"]?.numberValue }
        .sorted()
    if !pitValues.isEmpty {
        let median = pitValues.count % 2 == 1
            ? pitValues[pitValues.count / 2]
            : (pitValues[pitValues.count / 2 - 1] + pitValues[pitValues.count / 2]) / 2
        let display = temperatureUnit == "°C" ? median : median * 9 / 5 + 32
        tempLabel = "\(Int64(display.rounded()))°"
    }
    return PublicCardFacts(timeLabel: timeLabel, tempLabel: tempLabel)
}

/// Flowing Slice 5, element 4.2: Fork — a publication saved as *mine*
/// from the detail payload already on the device, with no Cook Pack
/// download round-trip. The conversion mirrors the server's pack builder
/// field for field (the same mapping `NativeOwnedPublication.draft`
/// applies for owner drafts), so a fork lands in the same end state a
/// pack import produces — same snapshot shape, same frozen story pinned
/// into the recipe's guide record. Two honest transport differences,
/// both forced by the iOS writer: photo bytes pass through the JPEG
/// derivative every iOS photo write uses, and the public source pins the
/// detail's own credit (the owner-draft mapping hardcodes a placeholder
/// name). The writer is `CookStore.saveRecipe` — never the archive
/// intake.
enum PublicationFork {
    /// The pack builder's internal coordinate origin: 2000-01-01 UTC.
    /// Relative timing only; never displayed as a cook date.
    static let origin = 946_684_800_000.0

    enum WrapChoice: String, Equatable, CaseIterable {
        case asWritten, none, foil, butcherPaper

        var label: String? {
            switch self {
            case .asWritten: return nil
            case .none: return WrapMaterial.none.label
            case .foil: return WrapMaterial.foil.label
            case .butcherPaper: return WrapMaterial.butcherPaper.label
            }
        }
    }

    /// One recipe's tweak-lite decisions (§4.3): name, rub, wrap,
    /// target — and nothing else.
    struct Tweak: Equatable {
        var name: String
        var rub: String = ""
        var wrap: WrapChoice = .asWritten
        var targetF: Double? = nil
        /// True when `targetF` was pre-filled from the source cook's
        /// last recorded food reading — the sheet says so.
        var targetFromStory: Bool = false
    }

    /// One converted, tweaked recipe ready for `saveRecipe`: its frozen
    /// story travels separately because the guide pin — not the
    /// snapshot — is where the story's readings live.
    struct PreparedRecipe {
        var snapshot: RecipeSnapshot
        var frozenStory: JSONValue
        var storyPresent: Bool
        /// Detail media ids for this recipe's photos, in asset order.
        var photoMediaIds: [String]
        var photoCaptions: [String]
        var changeNotes: String
    }

    /// JS `String(number)` for the values a weight can take: integral
    /// values carry no decimal point ("5", not "5.0").
    static func jsNumber(_ value: Double) -> String {
        if value.isFinite, value == value.rounded(), abs(value) < 1e15 {
            return String(Int64(value))
        }
        return String(value)
    }

    /// The sheet's opening state for one payload recipe: the book's
    /// "<title> — my version" name, the rub as written (publications
    /// carry no rub entry, so blank), wrap as written, and a target
    /// pre-filled from the source cook's last recorded food reading
    /// when the story carries one — a recorded fact, never a guess.
    static func prefill(detail: NativePublicDetail, recipeIndex: Int) -> Tweak {
        let recipe = detail.recipes[recipeIndex].objectValue ?? [:]
        let story = recipe["story"]?.objectValue
        let measurements = (story?["measurements"]?.arrayValue ?? []).compactMap(\.objectValue)
        let lastFood = measurements
            .filter { $0["role"]?.stringValue == "food" }
            .max { ($0["offsetMillis"]?.numberValue ?? 0) < ($1["offsetMillis"]?.numberValue ?? 0) }
        let targetF = lastFood?["valueCelsius"]?.numberValue.map { ($0 * 9 / 5 + 32).rounded() }
        return Tweak(
            name: (recipe["title"]?.stringValue ?? "Recipe") + " — my version",
            targetF: targetF,
            targetFromStory: targetF != nil
        )
    }

    /// The decisions exactly as written — the silent fork behind
    /// "Plan this" on the publication page (no sheet shown).
    static func untweaked(detail: NativePublicDetail) -> [Tweak] {
        detail.recipes.map { recipe in
            Tweak(name: recipe.objectValue?["title"]?.stringValue ?? "Recipe")
        }
    }

    static func prepare(detail: NativePublicDetail, tweaks: [Tweak]) throws -> [PreparedRecipe] {
        guard tweaks.count == detail.recipes.count else {
            throw PublicationForkError.invalid("Fork decisions are needed for every recipe in this publication.")
        }
        let mediaIds = Set(detail.media.map(\.id))
        return try detail.recipes.enumerated().map { index, value in
            guard let recipe = value.objectValue else {
                throw PublicationForkError.invalid("This publication is unavailable.")
            }
            let converted = try convertRecipe(recipe, detail: detail)
            let snapshot = try applyTweak(converted.snapshot, tweak: tweaks[index])
            var photoMediaIds: [String] = []
            var photoCaptions: [String] = []
            for assetValue in recipe["assets"]?.arrayValue ?? [] {
                guard let asset = assetValue.objectValue, let assetId = asset["id"]?.stringValue,
                      mediaIds.contains(assetId) else {
                    throw PublicationForkError.invalid("A photo in this publication is unavailable.")
                }
                photoMediaIds.append(assetId)
                photoCaptions.append(asset["caption"]?.stringValue ?? "")
            }
            return PreparedRecipe(
                snapshot: snapshot,
                frozenStory: converted.story,
                storyPresent: converted.storyPresent,
                photoMediaIds: photoMediaIds,
                photoCaptions: photoCaptions,
                changeNotes: changeNotes(detail: detail, recipe: recipe, tweak: tweaks[index])
            )
        }
    }

    private static func changeNotes(detail: NativePublicDetail, recipe: [String: JSONValue], tweak: Tweak) -> String {
        var parts: [String] = []
        // A publication payload carries no rub at all, so any rub typed
        // in the sheet is a real change by construction.
        if !tweak.rub.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            parts.append("Rub: \(tweak.rub.trimmingCharacters(in: .whitespacesAndNewlines))")
        }
        if let label = tweak.wrap.label { parts.append("Wrap: \(label)") }
        if let target = tweak.targetF { parts.append("Target: \(jsNumber(target))°F") }
        let renamed = tweak.name.trimmingCharacters(in: .whitespacesAndNewlines) != (recipe["title"]?.stringValue ?? "")
        if parts.isEmpty, !renamed { return "Saved public recipe" }
        return "Fork of \(detail.title)" + (parts.isEmpty ? "" : " — " + parts.joined(separator: " · "))
    }

    private static func applyTweak(_ snapshot: RecipeSnapshot, tweak: Tweak) throws -> RecipeSnapshot {
        var result = snapshot
        let name = tweak.name.trimmingCharacters(in: .whitespacesAndNewlines)
        // The fork renames the snapshot only; the dish keeps the food's
        // own name (Android's shipped fork behavior — the recipe is
        // "Brisket — my version", the dish is still brisket).
        if !name.isEmpty { result.name = name }
        let rub = tweak.rub.trimmingCharacters(in: .whitespacesAndNewlines)
        if !rub.isEmpty {
            // The rub lands as the rub-staged ingredient — the form every
            // reader reads back (the snapshot dish has no rub field; a
            // field-only write would silently drop the decision).
            guard var draft = result.draft.objectValue,
                  var dishes = draft["dishes"]?.arrayValue, !dishes.isEmpty,
                  var dish = dishes[0].objectValue else {
                throw PublicationForkError.invalid("This recipe is unavailable.")
            }
            var ingredients = (dish["ingredients"]?.arrayValue ?? []).filter {
                $0.objectValue?["stage"]?.stringValue?.lowercased() != "rub"
            }
            ingredients.append(.object([
                "name": .string(rub), "stage": .string("rub"), "brand": .string(""),
                "amount": .string(""), "unit": .string("tbsp"),
            ]))
            dish["ingredients"] = .array(ingredients)
            dishes[0] = .object(dish)
            draft["dishes"] = .array(dishes)
            result.draft = .object(draft)
        }
        if let label = tweak.wrap.label {
            if let index = result.instructions.firstIndex(where: { $0.objectValue?["action"]?.stringValue == "wrap" }) {
                var row = result.instructions[index].objectValue ?? [:]
                row["method"] = .string(label)
                result.instructions[index] = .object(row)
            } else {
                result.instructions.append(.object([
                    "id": .string(UUID().uuidString.lowercased()), "title": .string("Wrap"),
                    "details": .string(""), "method": .string(label), "action": .string("wrap"),
                    "offsetMillis": .null, "durationMillis": .null, "sourceEventId": .null,
                ]))
            }
        }
        if let target = tweak.targetF {
            result.targets = [.object([
                "dish": .number(0), "type": .string("personal_finish"), "value": .number(target),
                "unit": .string("°F"), "explanation": .null,
            ])]
        }
        try result.validate()
        return result
    }

    /// One payload recipe → the snapshot + frozen story the pack builder
    /// would have written for it (the `NativeOwnedPublication.draft`
    /// mapping, with the detail's real credit pinned).
    private static func convertRecipe(_ recipe: [String: JSONValue], detail: NativePublicDetail) throws -> (snapshot: RecipeSnapshot, story: JSONValue, storyPresent: Bool) {
        let story = recipe["story"]?.objectValue
        let anchor = story?["foodOnOffsetMillis"]?.numberValue.map { origin + $0 }
        var observations: [JSONValue] = (story?["milestones"]?.arrayValue ?? []).compactMap(\.objectValue).map { row in
            let at = origin + (row["offsetMillis"]?.numberValue ?? 0)
            let label = (row["text"]?.stringValue).flatMap { $0.isEmpty ? nil : $0 } ?? row["action"]?.stringValue ?? "Recorded action"
            return .object([
                "sourceEventId": .string(UUID().uuidString.lowercased()), "occurredAtUtcMillis": .number(at),
                "timeZoneId": .string("UTC"), "title": .string(label), "details": row["text"] ?? .string(""),
                "action": row["action"] ?? .null, "offsetMillis": anchor.map { .number(at - $0) } ?? .null,
                "recordedTemperatures": .array([]),
            ])
        }
        for (key, action, label) in [("foodOnOffsetMillis", "food_on", "Food on"), ("foodOffOffsetMillis", "remove", "Food off")] {
            if let offset = story?[key]?.numberValue, !observations.contains(where: { $0.objectValue?["action"]?.stringValue == action }) {
                let at = origin + offset
                observations.append(.object([
                    "sourceEventId": .string(UUID().uuidString.lowercased()), "occurredAtUtcMillis": .number(at),
                    "timeZoneId": .string("UTC"), "title": .string(label), "details": .string(""),
                    "action": .string(action), "offsetMillis": anchor.map { .number(at - $0) } ?? .null,
                    "recordedTemperatures": .array([]),
                ]))
            }
        }
        let channels = (story?["channels"]?.arrayValue ?? []).compactMap(\.objectValue)
        var probes: [String: String] = [:]
        for channel in channels { if let id = channel["id"]?.stringValue { probes[id] = UUID().uuidString.lowercased() } }
        let readings: [JSONValue] = try (story?["measurements"]?.arrayValue ?? []).map { value in
            guard let row = value.objectValue, let channelId = row["channelId"]?.stringValue,
                  let probe = probes[channelId],
                  let channel = channels.first(where: { $0["id"]?.stringValue == channelId }),
                  let celsius = row["valueCelsius"]?.numberValue, let offset = row["offsetMillis"]?.numberValue else {
                throw PublicationForkError.invalid("This publication's story names a channel it does not carry.")
            }
            return .object([
                "id": .string(UUID().uuidString.lowercased()), "probeId": .string(probe),
                "probeName": channel["label"] ?? .string("Recorded channel"),
                "measurementType": row["role"] ?? .string("food"), "value": .number(celsius), "unit": .string("°C"),
                "measuredAtUtcMillis": .number(origin + offset), "timeZoneId": .string("UTC"),
                "source": .string(row["quality"]?.stringValue == "manual" ? "manual" : "recorded"),
                "qualityStatus": .string(row["quality"]?.stringValue == "recovered" ? "recovered" : "valid"),
                "samplingIntervalMillis": .number(0),
            ])
        }
        let instructions: [JSONValue] = try (recipe["instructions"]?.arrayValue ?? []).map { value in
            guard let row = value.objectValue, row["id"]?.stringValue != nil else {
                throw PublicationForkError.invalid("This publication is unavailable.")
            }
            return .object([
                "id": .string(UUID().uuidString.lowercased()), "title": row["title"] ?? .string("Step"),
                "details": row["details"] ?? .string(""), "method": row["method"] ?? .null,
                "action": row["action"] ?? .null, "offsetMillis": row["suggestedOffsetMillis"] ?? .null,
                "durationMillis": .null, "sourceEventId": .null,
            ])
        }
        let ingredients: [JSONValue] = (recipe["ingredients"]?.arrayValue ?? []).compactMap(\.objectValue).map { row in
            .object([
                "name": row["name"] ?? .string(""), "stage": .string("main_ingredient"), "brand": .string(""),
                "amount": .string(""), "unit": row["amount"] ?? .string(""),
            ])
        }
        let name = recipe["title"]?.stringValue ?? "Recipe"
        let verdict = story?["verdict"]?.objectValue ?? [:]
        let setup: JSONValue = .object([
            "title": .string(name),
            "dishes": .array([.object([
                "name": .string(name), "foodType": recipe["foodType"] ?? .string("other"),
                "cut": recipe["cut"]?.stringValue.map(JSONValue.string) ?? .string(""),
                "weight": .string(recipe["weightGrams"]?.numberValue.map { jsNumber($0 / 1000) } ?? ""),
                "weightUnit": .string("kg"),
                "prep": recipe["method"]?.stringValue.map(JSONValue.string) ?? .string(""),
                "ingredients": .array(ingredients),
            ])]),
        ])
        let snapshot = RecipeSnapshot(
            version: 1, name: name, description: recipe["description"]?.stringValue ?? "",
            tags: (recipe["tags"]?.arrayValue ?? []).compactMap(\.stringValue),
            draft: setup, instructions: instructions, observations: observations, targets: [],
            resultNotes: verdict["notes"]?.stringValue ?? "", rating: verdict["rating"]?.numberValue,
            smokingAnchorUtcMillis: anchor.map(Int64.init), preferredPitTemperatureF: nil, servings: "",
            cookAgain: verdict["cookAgain"]?.boolValue,
            publicSource: PublicRecipeSource(itemId: detail.id, revisionId: detail.revisionId, displayName: detail.credit.displayName)
        )
        try snapshot.validate()
        let gaps = (story?["gaps"]?.arrayValue ?? []).compactMap(\.objectValue).flatMap { row in
            ["fromMillis", "toMillis"].compactMap { row[$0]?.numberValue.map { JSONValue.number(origin + $0) } }
        }
        let frozen: JSONValue = .object([
            "version": .number(1), "attemptId": story?["attemptId"] ?? .null,
            "anchorUtcMillis": anchor.map(JSONValue.number) ?? .null,
            "observations": .array(observations), "readings": .array(readings),
            "connectionGaps": .array(gaps), "results": .array([]),
            "publicStory": recipe["story"] ?? .null,
        ])
        return (snapshot, frozen, story != nil)
    }
}

@MainActor extension CookStore {
    /// Recipes already saved from a public item at an exact revision —
    /// the fork/save identity the publication page's Plan this checks
    /// before forking again.
    func savedPublicRecipeIds(itemId: String, revisionId: String) -> [String] {
        library.recipes.compactMap { row in
            guard let object = row.objectValue, let id = object["id"]?.stringValue,
                  let currentRevision = object["currentRevisionId"]?.stringValue,
                  let snapshot = try? recipeSnapshot(currentRevision),
                  snapshot.publicSource?.itemId == itemId, snapshot.publicSource?.revisionId == revisionId else { return nil }
            return id
        }
    }
}

@MainActor extension NativePublicCoordinator {
    /// A Discover/profile card's facts, from the cached detail fetch —
    /// no new payload, no new endpoint (§4.1's option (b)).
    func cardFacts(for item: NativePublicItem, temperatureUnit: String) async -> PublicCardFacts? {
        do {
            let (detail, _) = try await detail(item.id)
            return publicCardFacts(detail: detail, temperatureUnit: temperatureUnit)
        } catch { return nil }
    }

    /// A card's hero photo — hash-verified through the same image path
    /// the detail view uses.
    func cardImage(for item: NativePublicItem) async -> Data? {
        guard let heroId = item.heroAssetId else { return nil }
        do {
            let (detail, _) = try await detail(item.id)
            guard let media = detail.media.first(where: { $0.id == heroId }) else { return nil }
            return try await image(detail, asset: media)
        } catch { return nil }
    }

    /// Fork (§4.2): fetch each recipe's verified photo bytes once, then
    /// save every recipe through the recipe writer. The pack endpoint is
    /// never called — unit coverage pins that.
    func fork(_ detail: NativePublicDetail, tweaks: [PublicationFork.Tweak], store: CookStore) async throws -> [String] {
        let prepared = try PublicationFork.prepare(detail: detail, tweaks: tweaks)
        var bytesByMedia: [String: Data] = [:]
        var savedIds: [String] = []
        for recipe in prepared {
            var photos: [Data] = []
            for mediaId in recipe.photoMediaIds {
                if let bytes = bytesByMedia[mediaId] {
                    photos.append(bytes)
                    continue
                }
                guard let media = detail.media.first(where: { $0.id == mediaId }) else {
                    throw PublicationForkError.invalid("A photo in this publication is unavailable.")
                }
                let bytes = try NativeCookPackPhoto.derivative(try await image(detail, asset: media))
                bytesByMedia[mediaId] = bytes
                photos.append(bytes)
            }
            savedIds.append(try store.saveRecipe(
                recipe.snapshot,
                addedPhotos: photos,
                addedPhotoCaptions: recipe.photoCaptions,
                pinnedGuideStory: recipe.frozenStory,
                changeNotes: recipe.changeNotes
            ))
        }
        return savedIds
    }

    /// Plan this on the publication page: plan the saved fork when this
    /// detail is already in the library; otherwise fork it as written
    /// first — silently, with no sheet — then plan the fork.
    @discardableResult
    func planThis(_ detail: NativePublicDetail, store: CookStore) async -> Bool {
        do {
            var ids = store.savedPublicRecipeIds(itemId: detail.id, revisionId: detail.revisionId)
            if ids.isEmpty {
                ids = try await fork(detail, tweaks: PublicationFork.untweaked(detail: detail), store: store)
                store.lastSavedPublicItemId = detail.id
            }
            guard let first = ids.first,
                  let revisionId = store.recipe(first)?["currentRevisionId"]?.stringValue else { return false }
            return store.planRecipe(recipeId: first, revisionId: revisionId)
        } catch {
            surfaceError(error)
            return false
        }
    }
}
