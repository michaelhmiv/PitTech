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
}

@MainActor extension CookStore {
    private static let resultDisplayTypes: Set<String> = [
        "overall_rating", "cook_again", "final_temperature", "rest_minutes",
        "bark", "tenderness", "juiciness", "smoke", "seasoning",
        "result_notes", "keep_doing", "change_next_time",
    ]

    private func resultRows(cookId: String, dishId: String?) -> [[String: JSONValue]] {
        library.results.compactMap(\.objectValue).filter {
            $0["cookId"]?.stringValue == cookId && $0["dishId"]?.stringValue == dishId
        }
    }

    func hasDisplayableResults(cookId: String, dishId: String?) -> Bool {
        resultRows(cookId: cookId, dishId: dishId).contains { Self.resultDisplayTypes.contains($0["resultType"]?.stringValue ?? "") }
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
        return values
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

/// Read-only display of one scope's saved facts, shared by the results card.
private struct CookResultsFacts: View {
    let values: CookResultsValues
    let includeCookAgain: Bool
    var body: some View {
        let details = [("Bark", values.bark), ("Tenderness", values.tenderness), ("Juiciness", values.juiciness), ("Smoke", values.smoke), ("Seasoning", values.seasoning)]
            .compactMap { (label, value) in value.map { "\(label) \(resultsRatingText($0))" } }
        VStack(alignment: .leading, spacing: 2) {
            if !details.isEmpty { Text(details.joined(separator: " · ")).font(.subheadline) }
            if let temp = values.finalTemperature { Text("Final temperature: \(resultsNumberText(temp)) \(values.finalTemperatureUnit)").font(.subheadline) }
            if let rest = values.restMinutes { Text("Rest: \(resultsNumberText(rest)) min").font(.subheadline) }
            if includeCookAgain, let again = values.cookAgain { Text("Cook again: \(again ? "Yes" : "No")").font(.subheadline) }
            if !values.notes.isEmpty { Text("Notes: \(values.notes)").font(.subheadline) }
            if !values.keepDoing.isEmpty { Text("Keep doing: \(values.keepDoing)").font(.subheadline) }
            if !values.changeNextTime.isEmpty { Text("Change next time: \(values.changeNextTime)").font(.subheadline) }
        }
    }
}

/// PL-08: saved results render read-only where the cook is reviewed — the
/// Timeline summary. One card: the overall verdict, then one block per dish.
struct CookResultsCard: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    var body: some View {
        let whole = store.cookResults(cookId: cookId, dishId: nil)
        VStack(alignment: .leading, spacing: 10) {
            TurnInBannerText("Cook results", font: PitTechFont.bannerTitle)
            Text("Overall verdict: \(resultsRatingText(whole.overallRating))").font(.subheadline.weight(.semibold))
            CookResultsFacts(values: whole, includeCookAgain: false)
            ForEach(store.dishRecords(cookId), id: \.id) { dish in
                if store.hasDisplayableResults(cookId: cookId, dishId: dish.id) {
                    let values = store.cookResults(cookId: cookId, dishId: dish.id)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(dish.name).font(.subheadline.weight(.semibold))
                        Text("Verdict: \(resultsRatingText(values.overallRating))").font(.subheadline)
                        CookResultsFacts(values: values, includeCookAgain: true)
                    }.accessibilityIdentifier("cook-results-dish-" + dish.id)
                }
            }
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
        .accessibilityIdentifier("cook-results-card")
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

private struct ResultsScopeFields: View {
    @Binding var values: CookResultsValues
    let showCookAgain: Bool
    @State private var finalTempText = ""
    @State private var restText = ""
    var body: some View {
        Group {
            RatingPickerRow(label: "Verdict", selection: $values.overallRating)
            if showCookAgain {
                Picker("Cook again?", selection: $values.cookAgain) {
                    Text("Not decided").tag(Bool?.none); Text("Yes").tag(Bool?.some(true)); Text("No").tag(Bool?.some(false))
                }
            }
            HStack {
                TextField("Final temperature", text: $finalTempText).keyboardType(.decimalPad)
                Picker("Unit", selection: $values.finalTemperatureUnit) { Text("°F").tag("°F"); Text("°C").tag("°C") }.pickerStyle(.segmented).frame(width: 110)
            }
            TextField("Rest time in minutes", text: $restText).keyboardType(.decimalPad)
            RatingPickerRow(label: "Bark", selection: $values.bark)
            RatingPickerRow(label: "Tenderness", selection: $values.tenderness)
            RatingPickerRow(label: "Juiciness", selection: $values.juiciness)
            RatingPickerRow(label: "Smoke", selection: $values.smoke)
            RatingPickerRow(label: "Seasoning", selection: $values.seasoning)
            TextField("Cook notes", text: $values.notes, axis: .vertical).lineLimit(2...6)
            TextField("Keep doing", text: $values.keepDoing)
            TextField("Change next time", text: $values.changeNextTime)
        }
        .onAppear {
            finalTempText = values.finalTemperature.map(resultsNumberText) ?? ""
            restText = values.restMinutes.map(resultsNumberText) ?? ""
        }
        .onChange(of: finalTempText) { _, text in values.finalTemperature = Double(text) }
        .onChange(of: restText) { _, text in values.restMinutes = Double(text) }
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
            Form {
                Section("Whole cook") {
                    ResultsScopeFields(values: $whole, showCookAgain: false)
                }
                ForEach(orderedDishes, id: \.id) { dish in
                    Section(dish.name) {
                        ResultsScopeFields(values: dishBinding(dish.id), showCookAgain: true)
                    }
                }
                if let error { Section { Text(error).foregroundStyle(.red) } }
            }
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
