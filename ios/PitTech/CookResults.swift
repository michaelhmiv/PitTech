import SwiftUI

/// PL-08: the one results model on iOS, mirroring Android exactly. Results
/// are rows in `library.results` — the shared archive contract — scoped to
/// the whole cook (`dishId == nil`) or to one dish. `overall_rating` is the
/// verdict; the detail ratings are bark / tenderness / juiciness / smoke /
/// seasoning; a dish also carries `cook_again`. No schema change: these are
/// the rows both platforms already write.
struct CookResultsValues: Equatable {
    var finalTemperature: Double? = nil
    var finalTemperatureUnit: String = "°F"
    var restMinutes: Double? = nil
    var overallRating: Double? = nil
    var bark: Double? = nil
    var tenderness: Double? = nil
    var juiciness: Double? = nil
    var smoke: Double? = nil
    var seasoning: Double? = nil
    var cookAgain: Bool? = nil
    var notes: String = ""
    var keepDoing: String = ""
    var changeNextTime: String = ""
    /// Flowing §4.2: the scope's structured inputs (wrap / rub / spritz).
    var inputs: CookInputs = CookInputs()
}

@MainActor extension CookStore {
    private static let resultDisplayTypes: Set<String> = [
        "overall_rating", "cook_again", "final_temperature", "rest_minutes",
        "bark", "tenderness", "juiciness", "smoke", "seasoning",
        "result_notes", "keep_doing", "change_next_time",
    ]

    /// Flowing §4.2 structured inputs: rendered as facts on the results
    /// card (see CookResultsFacts), but on their own they raise the card
    /// only for a finished cook — they are written during the cook by
    /// ordinary logging, and the card is the finished cook's record.
    private static let inputResultTypes: Set<String> = [
        "wrap_material", "rub", "spritz", "spritz_medium",
    ]

    private func resultRows(cookId: String, dishId: String?) -> [[String: JSONValue]] {
        library.results.compactMap(\.objectValue).filter {
            $0["cookId"]?.stringValue == cookId && $0["dishId"]?.stringValue == dishId
        }
    }

    func hasDisplayableResults(cookId: String, dishId: String?) -> Bool {
        let rows = resultRows(cookId: cookId, dishId: dishId)
        if rows.contains(where: { Self.resultDisplayTypes.contains($0["resultType"]?.stringValue ?? "") }) { return true }
        let completed = library.cooks.first { $0.id == cookId }?.status == "completed"
        return completed && rows.contains { Self.inputResultTypes.contains($0["resultType"]?.stringValue ?? "") }
    }

    func hasAnyDisplayableResults(cookId: String) -> Bool {
        hasDisplayableResults(cookId: cookId, dishId: nil) || dishRecords(cookId).contains { hasDisplayableResults(cookId: cookId, dishId: $0.id) }
    }

    func cookResults(cookId: String, dishId: String?) -> CookResultsValues {
        let rows = resultRows(cookId: cookId, dishId: dishId)
        func number(_ type: String) -> Double? { rows.first { $0["resultType"]?.stringValue == type }?["numericValue"]?.numberValue }
        func text(_ type: String) -> String { rows.first { $0["resultType"]?.stringValue == type }?["textValue"]?.stringValue ?? "" }
        var values = CookResultsValues()
        values.finalTemperature = number("final_temperature")
        values.finalTemperatureUnit = rows.first { $0["resultType"]?.stringValue == "final_temperature" }?["unit"]?.stringValue ?? "°" + temperatureUnit
        values.restMinutes = number("rest_minutes")
        // "overall" is the legacy spelling of the verdict row; prefer the
        // current one, exactly as dishVerdict does.
        values.overallRating = number("overall_rating") ?? number("overall")
        values.bark = number("bark")
        values.tenderness = number("tenderness")
        values.juiciness = number("juiciness")
        values.smoke = number("smoke")
        values.seasoning = number("seasoning")
        values.cookAgain = number("cook_again").map { $0 == 1 }
        values.notes = text("result_notes")
        values.keepDoing = text("keep_doing")
        values.changeNextTime = text("change_next_time")
        // Flowing §4.2: inputs read through the shared vocabulary — dish
        // rows win over cook rows, a logged spritz means yes, and absence
        // stays "Not recorded" (never inferred from notes).
        let allRows = library.results.compactMap(\.objectValue)
            .filter { $0["cookId"]?.stringValue == cookId }
            .map { row in
                InsightResultInput(
                    id: row["id"]?.stringValue ?? "", cookId: cookId, dishId: row["dishId"]?.stringValue,
                    resultType: row["resultType"]?.stringValue ?? "", numericValue: row["numericValue"]?.numberValue,
                    textValue: row["textValue"]?.stringValue, recordedAtUtcMillis: row["recordedAtUtcMillis"]?.numberValue ?? 0)
            }
        let hasSpritzEvent = library.events.contains {
            $0.objectValue?["action"]?.stringValue == "spritz" && $0.objectValue?["cookId"]?.stringValue == cookId &&
                $0.objectValue?["dishId"]?.stringValue == dishId
        }
        values.inputs = CookInputVocabulary.fromResults(allRows, cookId: cookId, dishId: dishId, hasSpritzEvent: hasSpritzEvent)
        return values
    }

    /// Flowing §4.2: persist one scope's structured inputs as results rows
    /// (same table, same deterministic IDs as Android's saveCookInputs).
    func saveCookInputs(cookId: String, dishId: String?, inputs: CookInputs) throws {
        if let dishId {
            guard library.dishes.contains(where: { $0.objectValue?["id"]?.stringValue == dishId && $0.objectValue?["cookId"]?.stringValue == cookId }) else { throw PitTechArchiveError.invalidArchive }
        }
        let prior = library, now = Date.now.timeIntervalSince1970 * 1_000
        let scope = dishId ?? "cook"
        let rows = CookInputVocabulary.rows(inputs)
        for type in CookInputVocabulary.resultTypes {
            library.results.removeAll { row in
                guard let o = row.objectValue else { return false }
                return o["cookId"]?.stringValue == cookId && o["dishId"]?.stringValue == dishId && o["resultType"]?.stringValue == type
            }
            if let row = rows.first(where: { $0.resultType == type }), let text = row.textValue {
                library.results.append(.object([
                    "id": .string("\(cookId):\(scope):\(type)"), "cookId": .string(cookId),
                    "dishId": dishId.map(JSONValue.string) ?? .null, "resultType": .string(type),
                    "textValue": .string(text), "recordedAtUtcMillis": .number(now),
                ]))
            }
        }
        save(); if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
    }

    private static func validRating(_ value: Double?) -> Bool {
        value.map { $0.isFinite && (1...5).contains($0) } != false
    }

    /// Full-scope save — the unified editor's writer. Every field of the
    /// scope is written (or its row removed when cleared), in the same row
    /// shape Android's saveCookResults + saveLearning produce.
    func saveCookResults(cookId: String, dishId: String?, values: CookResultsValues) throws {
        guard Self.validRating(values.overallRating), Self.validRating(values.bark), Self.validRating(values.tenderness),
              Self.validRating(values.juiciness), Self.validRating(values.smoke), Self.validRating(values.seasoning),
              values.finalTemperature.map({ $0.isFinite && $0 > 0 }) != false,
              values.restMinutes.map({ $0.isFinite && $0 > 0 }) != false else { throw PitTechArchiveError.invalidArchive }
        if let dishId {
            guard library.dishes.contains(where: { $0.objectValue?["id"]?.stringValue == dishId && $0.objectValue?["cookId"]?.stringValue == cookId }) else { throw PitTechArchiveError.invalidArchive }
        }
        let prior = library, now = Date.now.timeIntervalSince1970 * 1_000
        let scope = dishId ?? "cook"
        func upsertNumeric(_ type: String, _ value: Double?, unit: String?) {
            library.results.removeAll { row in
                guard let o = row.objectValue else { return false }
                return o["cookId"]?.stringValue == cookId && o["dishId"]?.stringValue == dishId && o["resultType"]?.stringValue == type
            }
            if let value {
                library.results.append(.object([
                    "id": .string("\(cookId):\(scope):\(type)"), "cookId": .string(cookId),
                    "dishId": dishId.map(JSONValue.string) ?? .null, "resultType": .string(type),
                    "numericValue": .number(value), "unit": unit.map(JSONValue.string) ?? .null,
                    "recordedAtUtcMillis": .number(now),
                ]))
            }
        }
        func upsertText(_ type: String, idSuffix: String, _ value: String) {
            library.results.removeAll { row in
                guard let o = row.objectValue else { return false }
                return o["cookId"]?.stringValue == cookId && o["dishId"]?.stringValue == dishId && o["resultType"]?.stringValue == type
            }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                library.results.append(.object([
                    "id": .string("\(cookId):\(scope):\(idSuffix)"), "cookId": .string(cookId),
                    "dishId": dishId.map(JSONValue.string) ?? .null, "resultType": .string(type),
                    "textValue": .string(trimmed), "recordedAtUtcMillis": .number(now),
                ]))
            }
        }
        upsertNumeric("final_temperature", values.finalTemperature, unit: values.finalTemperatureUnit)
        upsertNumeric("rest_minutes", values.restMinutes, unit: "min")
        upsertNumeric("overall_rating", values.overallRating, unit: "stars")
        upsertNumeric("bark", values.bark, unit: "stars")
        upsertNumeric("tenderness", values.tenderness, unit: "stars")
        upsertNumeric("juiciness", values.juiciness, unit: "stars")
        upsertNumeric("smoke", values.smoke, unit: "stars")
        upsertNumeric("seasoning", values.seasoning, unit: "stars")
        if dishId != nil { upsertNumeric("cook_again", values.cookAgain.map { $0 ? 1.0 : 0.0 }, unit: nil) }
        upsertText("result_notes", idSuffix: "notes", values.notes)
        upsertText("keep_doing", idSuffix: "keep_doing", values.keepDoing)
        upsertText("change_next_time", idSuffix: "change_next_time", values.changeNextTime)
        // Flowing §4.2: the scope's inputs save with the rest of the
        // results; a field set back to Not recorded removes its row.
        let inputRows = CookInputVocabulary.rows(values.inputs)
        for type in CookInputVocabulary.resultTypes {
            upsertText(type, idSuffix: type, inputRows.first { $0.resultType == type }?.textValue ?? "")
        }
        save(); if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
    }

    /// PL-08 quick verdict: writes ONLY the verdict rows of one scope (the
    /// overall rating, plus "cook again?" for a dish), so the finish sheet
    /// can record verdicts without touching any other saved result. A nil
    /// rating removes the row — "Not recorded" is a first-class state.
    func saveVerdict(cookId: String, dishId: String?, rating: Double?, again: Bool?) throws {
        guard Self.validRating(rating) else { throw PitTechArchiveError.invalidArchive }
        if let dishId {
            guard library.dishes.contains(where: { $0.objectValue?["id"]?.stringValue == dishId && $0.objectValue?["cookId"]?.stringValue == cookId }) else { throw PitTechArchiveError.invalidArchive }
        }
        let prior = library, now = Date.now.timeIntervalSince1970 * 1_000
        let scope = dishId ?? "cook"
        func upsertVerdictRow(_ type: String, _ value: Double?, unit: String?) {
            library.results.removeAll { row in
                guard let o = row.objectValue else { return false }
                return o["cookId"]?.stringValue == cookId && o["dishId"]?.stringValue == dishId && o["resultType"]?.stringValue == type
            }
            if let value {
                library.results.append(.object([
                    "id": .string("\(cookId):\(scope):\(type)"), "cookId": .string(cookId),
                    "dishId": dishId.map(JSONValue.string) ?? .null, "resultType": .string(type),
                    "numericValue": .number(value), "unit": unit.map(JSONValue.string) ?? .null,
                    "recordedAtUtcMillis": .number(now),
                ]))
            }
        }
        upsertVerdictRow("overall_rating", rating, unit: "stars")
        if dishId != nil { upsertVerdictRow("cook_again", again.map { $0 ? 1.0 : 0.0 }, unit: nil) }
        save(); if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
    }

    /// PL-08 "Undo finish": available on the finished cook itself, not only
    /// in the moment after finishing. Recorded facts — results, readings,
    /// events — stay exactly as recorded.
    func reopenCook(_ cookId: String) {
        guard let index = library.cooks.firstIndex(where: { $0.id == cookId }), library.cooks[index].status == "completed" else { return }
        let prior = library
        library.cooks[index].status = "active"
        library.cooks[index].endedAtUtcMillis = nil
        library.cooks[index].updatedAtUtcMillis = Int64(Date.now.timeIntervalSince1970 * 1_000)
        save()
        if saveError != nil { library = prior }
    }
}

private func resultsNumberText(_ value: Double) -> String {
    value.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(value)) : String(value)
}

private func resultsRatingText(_ value: Double?) -> String {
    guard let value else { return "Not recorded" }
    return "\(resultsNumberText(value)) / 5"
}

/// The recorded rest as the scorecard's mono duration (`1H 20M`).
private func resultsRestText(_ minutes: Double) -> String {
    let total = Int(minutes)
    return total >= 60 ? "\(total / 60)H \(total % 60)M" : "\(total)M"
}

/// Read-only display of one scope's saved facts, shared by the results card.
/// TURN-IN (Slice 2, Area 02 `results-card`): the detail ratings ride as
/// boxed cells and the final/rest facts as one mono line; the prose lines
/// (notes, keep doing, change next time) keep the app's words.
private struct CookResultsFacts: View {
    let values: CookResultsValues
    let includeCookAgain: Bool
    var body: some View {
        let details = [("Bark", values.bark), ("Tenderness", values.tenderness), ("Juiciness", values.juiciness), ("Smoke", values.smoke), ("Seasoning", values.seasoning)]
            .compactMap { (label, value) in value.map { (label, $0) } }
        VStack(alignment: .leading, spacing: 8) {
            if !details.isEmpty {
                HStack(alignment: .top, spacing: 8) {
                    ForEach(Array(details.enumerated()), id: \.offset) { _, detail in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(detail.0.uppercased()).font(PitTechFont.metaLabel).tracking(PitTechFont.metaLabelTracking).foregroundStyle(PitTechStyle.inkMuted)
                            Text(resultsRatingText(detail.1)).font(PitTechFont.monoReadout).foregroundStyle(PitTechStyle.ink)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 10).padding(.vertical, 8)
                        .background(PitTechStyle.surfaceRaised, in: RoundedRectangle(cornerRadius: 10))
                    }
                }
            }
            if values.finalTemperature != nil || values.restMinutes != nil {
                Text([
                    values.finalTemperature.map { "FINAL \(resultsNumberText($0))°" },
                    values.restMinutes.map { "REST \(resultsRestText($0))" },
                ].compactMap { $0 }.joined(separator: " · "))
                    .font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
            }
            if includeCookAgain, let again = values.cookAgain { Text("Cook again: \(again ? "Yes" : "No")").font(PitTechFont.body) }
            if !values.notes.isEmpty { Text("Notes: \(values.notes)").font(PitTechFont.body) }
            if !values.keepDoing.isEmpty { Text("Keep doing: \(values.keepDoing)").font(PitTechFont.body) }
            if !values.changeNextTime.isEmpty { Text("Change next time: \(values.changeNextTime)").font(PitTechFont.body) }
            // Flowing §4.2: recorded inputs render as facts; unrecorded
            // inputs render as nothing — never an inferred value.
            if let wrap = values.inputs.wrapMaterial { Text("Wrap: \(wrap.label)").font(PitTechFont.body) }
            if let rub = values.inputs.rub { Text("Rub: \(rub)").font(PitTechFont.body) }
            if values.inputs.spritz == .yes {
                Text("Spritz: \(["Yes", values.inputs.spritzMedium].compactMap { $0 }.joined(separator: " — "))").font(PitTechFont.body)
            } else if values.inputs.spritz == .no {
                Text("Spritz: No").font(PitTechFont.body)
            }
        }
    }
}

/// PL-08: saved results render read-only where the cook is reviewed — the
/// Timeline summary. One card: the overall verdict, then one block per dish.
///
/// TURN-IN (Slice 2, Area 02 `results-card`): the card is composed as the
/// frame's scorecard — a marigold header band with the cook's name and
/// date, the overall verdict cell with its stars, the recorded detail
/// ratings as boxed cells, and the Edit results chip opening the one
/// results editor. (The frame's Favorite / Method & tags / Export chips
/// are Android homes today; iOS has no cook-favorite, method/tags editor,
/// or per-cook export — pre-existing parity gaps, not invented here.)
struct CookResultsCard: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    @State private var showingResults = false
    var body: some View {
        let whole = store.cookResults(cookId: cookId, dishId: nil)
        let cook = store.library.cooks.first { $0.id == cookId }
        VStack(alignment: .leading, spacing: 10) {
            Text("Cook results").font(PitTechFont.metaLabel).tracking(PitTechFont.metaLabelTracking).foregroundStyle(PitTechStyle.inkMuted)
            // The header band: marigold fill, navy ink (never white on
            // marigold, §3.1), the cook's name and its date.
            VStack(alignment: .leading, spacing: 2) {
                Text(cook?.title ?? "").font(PitTechFont.bannerCard).foregroundStyle(PitTechStyle.onAccent)
                if let cook {
                    Text(cook.startedAt.formatted(date: .abbreviated, time: .omitted).uppercased()).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.onAccent)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12).padding(.vertical, 10)
            .background(PitTechStyle.marigold, in: RoundedRectangle(cornerRadius: 10))
            HStack(alignment: .center, spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    TurnInMetaLabel("Overall")
                    Text("Overall verdict: \(resultsRatingText(whole.overallRating))").font(PitTechFont.bodyEmphasis).foregroundStyle(PitTechStyle.ink)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let rating = whole.overallRating { TurnInStars(rating: rating) }
            }
            CookResultsFacts(values: whole, includeCookAgain: false)
            ForEach(store.dishRecords(cookId), id: \.id) { dish in
                if store.hasDisplayableResults(cookId: cookId, dishId: dish.id) {
                    let values = store.cookResults(cookId: cookId, dishId: dish.id)
                    VStack(alignment: .leading, spacing: 4) {
                        TurnInBannerText(dish.name)
                        Text("Verdict: \(resultsRatingText(values.overallRating))").font(PitTechFont.body)
                        CookResultsFacts(values: values, includeCookAgain: true)
                    }.accessibilityIdentifier("cook-results-dish-" + dish.id)
                }
            }
            Button { showingResults = true } label: {
                Text("Edit results")
                    .font(PitTechFont.bodyEmphasis).foregroundStyle(PitTechStyle.ink)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(PitTechStyle.surface, in: Capsule())
                    .overlay(Capsule().stroke(PitTechStyle.ruling, lineWidth: 1))
            }
            .buttonStyle(.plain)
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
        .accessibilityIdentifier("cook-results-card")
        .sheet(isPresented: $showingResults) { CookResultsSheet(cookId: cookId) }
    }
}

/// PL-08 / DD-5: the completion sheet's first section — the quick verdict.
/// Overall verdict plus one verdict per dish, all visible before any other
/// choice. Saving writes only verdict rows; anything skipped stays
/// "Not recorded".
struct CompletionVerdictSection: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    @State private var overall: Double?
    @State private var dishRatings: [String: Double?] = [:]
    @State private var dishAgain: [String: Bool?] = [:]
    @State private var saved = false
    @State private var error: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            TurnInBannerText("Quick verdict", font: PitTechFont.bannerTitle)
            Text("How did it turn out? Anything you skip stays Not recorded — results can be edited any time from the cook.").font(.subheadline).foregroundStyle(PitTechStyle.supporting)
            RatingPickerRow(label: "Overall", selection: $overall)
            ForEach(store.dishRecords(cookId), id: \.id) { dish in
                RatingPickerRow(label: dish.name, selection: ratingBinding(dish.id))
                Picker("Cook again?", selection: againBinding(dish.id)) {
                    Text("Not decided").tag(Bool?.none); Text("Yes").tag(Bool?.some(true)); Text("No").tag(Bool?.some(false))
                }
            }
            Button("Save verdict") {
                do {
                    try store.saveVerdict(cookId: cookId, dishId: nil, rating: overall, again: nil)
                    for dish in store.dishRecords(cookId) {
                        try store.saveVerdict(cookId: cookId, dishId: dish.id, rating: dishRatings[dish.id] ?? nil, again: dishAgain[dish.id] ?? nil)
                    }
                    saved = true; error = nil
                } catch { self.error = "Couldn't save the verdict. Your choices are still here — try again." }
            }.accessibilityIdentifier("completion-verdict-save")
            if saved { Text("Verdict saved").font(.subheadline).accessibilityIdentifier("completion-verdict-saved") }
            if let error { Text(error).font(.subheadline).foregroundStyle(.red) }
        }
        .accessibilityIdentifier("completion-verdict")
        .onAppear {
            overall = store.cookResults(cookId: cookId, dishId: nil).overallRating
            dishRatings = Dictionary(uniqueKeysWithValues: store.dishRecords(cookId).map { ($0.id, store.cookResults(cookId: cookId, dishId: $0.id).overallRating) })
            dishAgain = Dictionary(uniqueKeysWithValues: store.dishRecords(cookId).map { ($0.id, store.cookResults(cookId: cookId, dishId: $0.id).cookAgain) })
        }
        .onChange(of: overall) { _, _ in saved = false }
    }
    private func ratingBinding(_ dishId: String) -> Binding<Double?> {
        Binding(get: { dishRatings[dishId] ?? nil }, set: { dishRatings[dishId] = $0; saved = false })
    }
    private func againBinding(_ dishId: String) -> Binding<Bool?> {
        Binding(get: { dishAgain[dishId] ?? nil }, set: { dishAgain[dishId] = $0; saved = false })
    }
}

/// TURN-IN (Slice 1): the d31 boxed score cells replace the rating
/// picker — the label above, five tappable 1–5 boxes, "Not recorded" as
/// the unrecorded state. Fractional saved values select no cell and keep
/// their note, exactly as the Android `TurnInScoreCells` adapter does.
private struct RatingPickerRow: View {
    let label: String
    @Binding var selection: Double?
    var body: some View {
        let whole = selection.flatMap { $0 == $0.rounded() ? Int($0) : nil }
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                TurnInBannerText(label, font: PitTechFont.bodyEmphasis)
                Spacer()
                if selection == nil {
                    Text("Not recorded").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                }
            }
            TurnInScoreCells(value: whole, tagPrefix: "rating-\(label.lowercased().replacingOccurrences(of: " ", with: "-"))") { picked in
                selection = picked.map(Double.init)
            }
            if let selection, selection.rounded() != selection {
                Text("Saved rating: \(selection.formatted()) / 5").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
            }
        }
        .padding(.vertical, 4)
    }
}

/// One boxed field (§4) for the results editor: the field keeps its own
/// title (placeholder and accessibility label, unchanged) and gains the
/// boxed treatment — raised surface, ruling border, rounded corners.
private struct ResultsBoxedFieldModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .textFieldStyle(.plain)
            .font(PitTechFont.body)
            .padding(.horizontal, 12).padding(.vertical, 10)
            .background(PitTechStyle.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(PitTechStyle.ruling, lineWidth: 1))
    }
}

private struct ResultsScopeFields: View {
    @Binding var values: CookResultsValues
    let showCookAgain: Bool
    @State private var finalTempText = ""
    @State private var restText = ""
    @State private var rubText = ""
    @State private var spritzMediumText = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            RatingPickerRow(label: "Verdict", selection: $values.overallRating)
            if showCookAgain {
                Picker("Cook again?", selection: $values.cookAgain) {
                    Text("Not decided").tag(Bool?.none); Text("Yes").tag(Bool?.some(true)); Text("No").tag(Bool?.some(false))
                }.pickerStyle(.segmented)
            }
            HStack(spacing: 10) {
                TextField("Final temperature", text: $finalTempText).keyboardType(.decimalPad).modifier(ResultsBoxedFieldModifier())
                Picker("Unit", selection: $values.finalTemperatureUnit) { Text("°F").tag("°F"); Text("°C").tag("°C") }.pickerStyle(.segmented).frame(width: 110)
            }
            TextField("Rest time in minutes", text: $restText).keyboardType(.decimalPad).modifier(ResultsBoxedFieldModifier())
            RatingPickerRow(label: "Bark", selection: $values.bark)
            RatingPickerRow(label: "Tenderness", selection: $values.tenderness)
            RatingPickerRow(label: "Juiciness", selection: $values.juiciness)
            RatingPickerRow(label: "Smoke", selection: $values.smoke)
            RatingPickerRow(label: "Seasoning", selection: $values.seasoning)
            TextField("Cook notes", text: $values.notes, axis: .vertical).lineLimit(2...6).modifier(ResultsBoxedFieldModifier())
            TextField("Keep doing", text: $values.keepDoing).modifier(ResultsBoxedFieldModifier())
            TextField("Change next time", text: $values.changeNextTime).modifier(ResultsBoxedFieldModifier())
            // Flowing §4.2: the scope's structured inputs, editable after
            // the fact — "Not recorded" is the honest state, never a guess.
            Picker("Wrap material", selection: $values.inputs.wrapMaterial) {
                Text("Not recorded").tag(WrapMaterial?.none)
                Text("No wrap").tag(WrapMaterial?.some(.none))
                Text("Foil").tag(WrapMaterial?.some(.foil))
                Text("Butcher paper").tag(WrapMaterial?.some(.butcherPaper))
            }
            TextField("Rub", text: $rubText).modifier(ResultsBoxedFieldModifier())
            Picker("Spritz", selection: $values.inputs.spritz) {
                Text("Not recorded").tag(SpritzState.notRecorded)
                Text("Yes").tag(SpritzState.yes)
                Text("No").tag(SpritzState.no)
            }
            if values.inputs.spritz == .yes {
                TextField("Spritzed with (optional)", text: $spritzMediumText).modifier(ResultsBoxedFieldModifier())
            }
        }
        .onAppear {
            finalTempText = values.finalTemperature.map(resultsNumberText) ?? ""
            restText = values.restMinutes.map(resultsNumberText) ?? ""
            rubText = values.inputs.rub ?? ""
            spritzMediumText = values.inputs.spritzMedium ?? ""
        }
        .onChange(of: finalTempText) { _, text in values.finalTemperature = Double(text) }
        .onChange(of: restText) { _, text in values.restMinutes = Double(text) }
        .onChange(of: rubText) { _, text in
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            values.inputs.rub = trimmed.isEmpty ? nil : trimmed
        }
        .onChange(of: spritzMediumText) { _, text in
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            values.inputs.spritzMedium = values.inputs.spritz == .yes && !trimmed.isEmpty ? trimmed : nil
        }
        .onChange(of: values.inputs.spritz) { _, state in
            if state != .yes { values.inputs.spritzMedium = nil }
        }
    }
}

/// PL-08: THE results editor on iOS — one system for the whole cook and
/// every dish, replacing the per-dish DishVerdictSheet. It creates the
/// whole-cook results surface iOS never had: the same fields, in the same
/// order, writing the same shared results rows as Android.
struct CookResultsSheet: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let cookId: String
    var focusedDishId: String? = nil
    @State private var whole = CookResultsValues()
    @State private var perDish: [String: CookResultsValues] = [:]
    @State private var error: String?
    private var orderedDishes: [NoteDishRecord] {
        let dishes = store.dishRecords(cookId)
        guard let focusedDishId, let focused = dishes.first(where: { $0.id == focusedDishId }) else { return dishes }
        return [focused] + dishes.filter { $0.id != focusedDishId }
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // TURN-IN (Slice 2, Area 02 `results-editor`): the
                    // sheet kicker names the cook; each scope is its own
                    // laminated card — whole cook first, then one per dish
                    // (the per-dish verdict editor is this same sheet,
                    // focused). Fields and order are the app's own.
                    TurnInSheetKicker("Results · \(store.library.cooks.first { $0.id == cookId }?.title ?? "")")
                    if let error { TurnInStatusStrip(text: error, accent: PitTechStyle.flag) }
                    VStack(alignment: .leading, spacing: 12) {
                        TurnInBannerText("Whole cook")
                        ResultsScopeFields(values: $whole, showCookAgain: false)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
                    ForEach(orderedDishes, id: \.id) { dish in
                        VStack(alignment: .leading, spacing: 12) {
                            TurnInBannerText(dish.name)
                            ResultsScopeFields(values: dishBinding(dish.id), showCookAgain: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                        .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
                    }
                }
                .padding(20)
            }
            .background(PitTechStyle.canvas.ignoresSafeArea())
            .navigationTitle("Cook results").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { saveAll() } }
            }
            .onAppear {
                whole = store.cookResults(cookId: cookId, dishId: nil)
                perDish = Dictionary(uniqueKeysWithValues: store.dishRecords(cookId).map { ($0.id, store.cookResults(cookId: cookId, dishId: $0.id)) })
            }
        }
    }
    private func dishBinding(_ dishId: String) -> Binding<CookResultsValues> {
        Binding(get: { perDish[dishId] ?? CookResultsValues() }, set: { perDish[dishId] = $0 })
    }
    private func saveAll() {
        do {
            try store.saveCookResults(cookId: cookId, dishId: nil, values: whole)
            for dish in store.dishRecords(cookId) {
                try store.saveCookResults(cookId: cookId, dishId: dish.id, values: perDish[dish.id] ?? CookResultsValues())
            }
            dismiss()
        } catch {
            self.error = "Couldn't save these results. Your entries are still here — check the values and try again."
        }
    }
}
