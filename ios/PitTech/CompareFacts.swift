import Foundation

/// Flowing Slice 6 — Spread 05 "Compare that answers" (spec §5), iOS.
/// The Swift mirror of Android's `CompareFacts.kt` — same rules, same
/// strings; an element is built only when every clause below is satisfied:
///
/// - 5.1 "Aligned at food-on with an offset stepper (FireBoard's
///   pattern)." → `CompareFacts.foodOnUtcMillis` anchors every compared
///   curve at its own food-on moment; the chart's per-cook stepper shifts
///   a curve by ±5 min steps through `CookTemperatureChart`'s
///   `cookOffsetMillis` (display-only).
/// - 5.2 "Inputs recorded as data (§4.2) and diffed beside outcomes: wrap
///   material, rub, setpoint — stall 2h 40m → 1h 35m as the canonical
///   example." → the grid's Wrap / Rub / Spritz / Setpoint rows come from
///   `CookInputVocabulary` + the cook's recorded initial setpoint, beside
///   outcome rows whose Stall figure is the §4.1 detector's total over
///   the lead dish's recorded readings. The canonical pair is a unit test.
/// - 5.3 "Projections wear a PROJECTED tag, never blended into fact; a
///   projected total is computed by §4.1 from the in-progress cook."
///   → `CompareFacts.facts` runs `CookPaceEngine.assess` for an
///   in-progress cook and returns the range as `ProjectedCompareTotal`,
///   a type the factual rows never carry; the grid tags it PROJECTED and
///   states its basis (§4.1: "Every surface showing the range shows the
///   basis").
/// - 5.4 "One generated sentence from the cook's own history does the
///   teaching ("Paper is winning: your last three paper wraps stalled
///   ~1h shorter than foil." — generated only when the underlying
///   recorded data supports it; otherwise absent, never templated
///   filler)." → `CompareFacts.teachingSentence`; §9 restraint: "it
///   appears only with recorded-data support (≥3 comparable cooks).
///   Below that, silence."
/// - 5.5 "Honest history: cooks predating §4.2 show "inputs not
///   recorded" in the diff; the overlay and outcome diff still work
///   from readings." → input cells read
///   `CookInputVocabulary.notRecordedLabel` when no §4.2 row exists,
///   while stall / time / temperature outcomes are still computed from
///   the cook's readings.

/// The per-cook facts compare needs that the insights archive does not
/// carry (title, status, recorded setpoint) — the local library's cook
/// row, in the shapes Android's `CookWithDishes` hands its compare.
struct CompareCookInput: Equatable {
    let id: String
    let title: String
    let status: String
    let startedAtUtcMillis: Double
    let endedAtUtcMillis: Double?
    let initialSetpointValue: Double?
    let initialSetpointUnit: String?

    init(id: String, title: String, status: String, startedAtUtcMillis: Double, endedAtUtcMillis: Double?, initialSetpointValue: Double?, initialSetpointUnit: String?) {
        self.id = id
        self.title = title
        self.status = status
        self.startedAtUtcMillis = startedAtUtcMillis
        self.endedAtUtcMillis = endedAtUtcMillis
        self.initialSetpointValue = initialSetpointValue
        self.initialSetpointUnit = initialSetpointUnit
    }

    init(record: CookRecord) {
        self.init(
            id: record.id, title: record.title, status: record.status,
            startedAtUtcMillis: Double(record.startedAtUtcMillis),
            endedAtUtcMillis: record.endedAtUtcMillis.map { Double($0) },
            initialSetpointValue: record.initialSetpointValue,
            initialSetpointUnit: record.initialSetpointUnit)
    }
}

/// A §4.1 projection for an in-progress cook: the total time from food-on
/// to the projected finish, as a range — never a point, never a fact.
struct ProjectedCompareTotal: Equatable {
    let earliestMinutes: Double
    let latestMinutes: Double
    let basis: PaceBasis

    /// §4.1: every surface showing the range shows the basis.
    var basisText: String { basis.displayText }
}

/// Everything the compare grid renders for one cook, pre-computed from
/// recorded data only. Factual fields and the projection stay in separate
/// fields so a projection can never be blended into fact by the UI.
struct CompareCookFacts: Equatable {
    let cookId: String
    let title: String
    let inProgress: Bool
    /// 5.1 anchor: the cook's earliest recorded food-on moment.
    let foodOnUtcMillis: Int64
    let inputs: CookInputs
    /// True when at least one §4.2 field was recorded for this cook.
    let inputsRecorded: Bool
    let wrapText: String
    let rubText: String
    let spritzText: String
    let setpointText: String
    /// Total stall minutes the §4.1 detector finds in the lead dish's
    /// recorded readings; nil when no stall was recorded.
    let stallMinutes: Double?
    /// 5.3: non-nil only for an in-progress cook with a §4.1 range.
    let projected: ProjectedCompareTotal?
    /// Why §4.1 stated no range, when it didn't (its own words).
    let projectionWithheldReason: String?
    /// The in-progress cook's probe has reached its recorded target.
    let reachedTarget: Bool
    /// Minutes from food-on to now, for an in-progress cook's "so far".
    let elapsedMinutes: Double
    /// Outcome cells Android's grid derives in its UI layer from the same
    /// recorded data; computed here so the iOS grid and its unit tests
    /// share one source. The values follow Android's grid rules exactly.
    let timeOnText: String
    let finalTempText: String
    let toWrapText: String
    let restText: String
    let overallText: String
    /// Detail-rating cells by result type, present only where recorded.
    let detailRatingTexts: [String: String]
}

/// One grid cell; `isProjected` is the 5.3 tag carried as data so tests
/// assert the PROJECTED tagging without rendering a view.
struct CompareGridCell: Equatable {
    let text: String
    let isProjected: Bool
}

struct CompareGridRow: Equatable {
    let label: String
    let cells: [CompareGridCell]
}

enum CompareFacts {

    /// The dish compare reads first: the first dish with recorded food
    /// readings, else the first dish — the Live board's lead-dish convention.
    static func leadDish(cookId: String, archive: InsightArchiveInput) -> InsightDishInput? {
        let dishes = archive.dishes.filter { $0.cookId == cookId }
        return dishes.first { dish in
            archive.readings.contains {
                $0.cookId == cookId && $0.dishId == dish.id && InsightsCalculations.role($0.measurementType) == "food"
            }
        } ?? dishes.first
    }

    /// 5.1 anchor: the earliest food-on plan event, else the session start.
    static func foodOnUtcMillis(cook: CompareCookInput, archive: InsightArchiveInput) -> Int64 {
        let earliest = archive.events.filter { $0.cookId == cook.id && $0.action == "food_on" }.map(\.at).min()
        return Int64(earliest ?? cook.startedAtUtcMillis)
    }

    private static func inputs(cookId: String, archive: InsightArchiveInput, leadDish: InsightDishInput?) -> CookInputs {
        let hasSpritzEvent = archive.events.contains { event in
            event.cookId == cookId && event.action == "spritz" &&
                (leadDish == nil || event.dishIds.isEmpty || event.dishIds.contains(leadDish!.id))
        }
        return CookInputVocabulary.fromResults(archive.results, cookId: cookId, dishId: leadDish?.id, hasSpritzEvent: hasSpritzEvent)
    }

    /// The lead dish's food-probe series in °F, built exactly the way the
    /// §4.1 engine's own history derivation builds it (dish-scoped or
    /// cook-scoped readings, food role, valid, finite).
    static func foodSeries(cookId: String, readings: [InsightReadingInput], dishId: String?) -> [PaceReading] {
        readings.filter {
            $0.cookId == cookId && (dishId == nil || $0.dishId == dishId || $0.dishId == nil) &&
                InsightsCalculations.role($0.measurementType) == "food" && $0.qualityStatus == "valid" &&
                $0.value != nil && $0.value!.isFinite
        }.map { PaceReading(atUtcMillis: Int64($0.measuredAtUtcMillis), fahrenheit: InsightsCalculations.fahrenheit($0.value!, unit: $0.unit)) }
            .sorted { $0.atUtcMillis < $1.atUtcMillis }
    }

    /// The §4.1 detector's total stall for one cook's lead dish, from
    /// readings alone — so it works for cooks that predate §4.2 (5.5).
    static func stallMinutes(cookId: String, readings: [InsightReadingInput], dishId: String?) -> Double? {
        let spans = CookPaceEngine.stallSpans(foodSeries(cookId: cookId, readings: readings, dishId: dishId))
        return spans.isEmpty ? nil : spans.reduce(0) { $0 + $1.durationMinutes }
    }

    /// The pace engine's input series for one dish: dish-scoped, valid,
    /// finite, in a temperature unit — Android's `foodSeries` rule
    /// (`FoodLivePresentation.accepted`), at absolute times (anchor 0).
    private static func paceSeries(cookId: String, readings: [InsightReadingInput], dishId: String) -> [PaceReading] {
        readings.filter {
            $0.cookId == cookId && $0.dishId == dishId && $0.qualityStatus == "valid" &&
                $0.value != nil && $0.value!.isFinite && ["°F", "°C"].contains($0.unit)
        }.map { PaceReading(atUtcMillis: Int64($0.measuredAtUtcMillis), fahrenheit: InsightsCalculations.fahrenheit($0.value!, unit: $0.unit)) }
            .sorted { $0.atUtcMillis < $1.atUtcMillis }
    }

    /// The grid's result pick: a cook-scoped row wins, else the first
    /// dish-scoped row — Android's `ComparisonGrid.result` rule.
    private static func result(_ archive: InsightArchiveInput, cookId: String, type: String) -> InsightResultInput? {
        archive.results.first { $0.cookId == cookId && $0.dishId == nil && $0.resultType == type }
            ?? archive.results.first { $0.cookId == cookId && $0.resultType == type }
    }

    /// Android's `cookHeatingMinutes`: explicit-phase cooks read their
    /// recorded cooking/wrapped spans (unioned across dishes, the same
    /// construction `InsightsCalculations` uses for equipment heating);
    /// every other cook reads its session length.
    private static func heatingMinutes(cook: CompareCookInput, archive: InsightArchiveInput, nowUtcMillis: Int64) -> Double {
        let dishes = archive.dishes.filter { $0.cookId == cook.id }
        let mode = archive.cooks.first { $0.id == cook.id }?.timingMode
            ?? archive.lifecycles.first { $0.cookId == cook.id }?.timingMode ?? "legacy_preserved"
        let end = cook.endedAtUtcMillis ?? Double(nowUtcMillis)
        if mode == "explicit_phases" && !dishes.isEmpty {
            let actions = archive.events.filter { $0.cookId == cook.id }
                .map { CookAction(id: $0.id, action: $0.action, dishIds: $0.dishIds, at: $0.at) }
            let phases = dishes.map {
                CookPhaseEngine.dish(id: $0.id, name: "", actions: actions, start: cook.startedAtUtcMillis, end: end)
            }
            // Android's heatingMillis returns 0 when no heating span exists.
            return (InsightsCalculations.unionHeatingMillis(phases) ?? 0) / 60_000
        }
        return max(0, end - cook.startedAtUtcMillis) / 60_000
    }

    static func facts(cook: CompareCookInput, archive: InsightArchiveInput, nowUtcMillis: Int64) -> CompareCookFacts {
        let lead = leadDish(cookId: cook.id, archive: archive)
        let inputs = inputs(cookId: cook.id, archive: archive, leadDish: lead)
        let foodOn = foodOnUtcMillis(cook: cook, archive: archive)
        let inProgress = cook.status != "completed"
        var projected: ProjectedCompareTotal? = nil
        var withheld: String? = nil
        var reachedTarget = false
        if inProgress {
            if lead == nil {
                withheld = "no dish recorded for this cook"
            } else if let target = archive.targets.first(where: { $0.cookId == cook.id && $0.dishId == lead!.id && $0.targetType == "personal_finish" })
                ?? archive.targets.first(where: { $0.cookId == cook.id && $0.dishId == nil && $0.targetType == "personal_finish" }) {
                // The same target the Live board reads: the dish's own
                // personal_finish target, else the cook-level one.
                let series = paceSeries(cookId: cook.id, readings: archive.readings, dishId: lead!.id)
                let history = LiveGuidance.historyFor(archive: archive, currentCookId: cook.id, currentDishId: lead!.id, cut: lead!.cut)
                let assessment = CookPaceEngine.assess(
                    readings: series,
                    targetF: InsightsCalculations.fahrenheit(target.value, unit: target.unit),
                    nowUtcMillis: nowUtcMillis, foodOnUtcMillis: foodOn, history: history)
                switch assessment.finish {
                case .ranged(let range):
                    projected = ProjectedCompareTotal(
                        earliestMinutes: Double(range.earliestUtcMillis - foodOn) / 60_000,
                        latestMinutes: Double(range.latestUtcMillis - foodOn) / 60_000,
                        basis: range.basis)
                case .planOnly(let reason):
                    withheld = reason
                case .finished:
                    reachedTarget = true
                }
            } else {
                withheld = "no finish target recorded for this cook"
            }
        }
        let elapsed = Double(max(0, nowUtcMillis - foodOn)) / 60_000
        let timeOnText: String
        if !inProgress {
            timeOnText = cook.endedAtUtcMillis != nil
                ? formatMinutes(heatingMinutes(cook: cook, archive: archive, nowUtcMillis: nowUtcMillis)) : "—"
        } else if let projected {
            timeOnText = "\(formatMinutes(projected.earliestMinutes)) – \(formatMinutes(projected.latestMinutes))"
        } else if reachedTarget {
            timeOnText = "\(formatMinutes(elapsed)) · at target"
        } else {
            timeOnText = "\(formatMinutes(elapsed)) so far"
        }
        let wrapAt = archive.events.filter { $0.cookId == cook.id }.first { $0.action == "wrap" }?.at
        let detailTypes = ["bark", "tenderness", "juiciness", "smoke", "seasoning"]
        return CompareCookFacts(
            cookId: cook.id,
            title: cook.title,
            inProgress: inProgress,
            foodOnUtcMillis: foodOn,
            inputs: inputs,
            inputsRecorded: inputs.wrapMaterial != nil || inputs.rub != nil || inputs.spritz != .notRecorded,
            wrapText: inputs.wrapMaterial?.label ?? CookInputVocabulary.notRecordedLabel,
            rubText: inputs.rub ?? CookInputVocabulary.notRecordedLabel,
            spritzText: spritzText(inputs),
            setpointText: setpointText(cook),
            stallMinutes: stallMinutes(cookId: cook.id, readings: archive.readings, dishId: lead?.id),
            projected: projected,
            projectionWithheldReason: withheld,
            reachedTarget: reachedTarget,
            elapsedMinutes: elapsed,
            timeOnText: timeOnText,
            finalTempText: result(archive, cookId: cook.id, type: "final_temperature")?.numericValue.map { "\(formatResultNumber($0))°" } ?? "—",
            toWrapText: wrapAt.map { formatMinutes(max(0, $0 - Double(foodOn)) / 60_000) } ?? "—",
            restText: result(archive, cookId: cook.id, type: "rest_minutes")?.numericValue.map { formatMinutes($0) } ?? "—",
            overallText: result(archive, cookId: cook.id, type: "overall_rating")?.numericValue.map { "\(formatResultNumber($0)) ★" } ?? "—",
            detailRatingTexts: Dictionary(uniqueKeysWithValues: detailTypes.compactMap { type in
                result(archive, cookId: cook.id, type: type)?.numericValue.map { (type, formatResultNumber($0)) }
            })
        )
    }

    private static func spritzText(_ inputs: CookInputs) -> String {
        switch inputs.spritz {
        case .yes: return inputs.spritzMedium.map { "Yes · \($0)" } ?? "Yes"
        case .no: return "No"
        case .notRecorded: return CookInputVocabulary.notRecordedLabel
        }
    }

    private static func setpointText(_ cook: CompareCookInput) -> String {
        guard let value = cook.initialSetpointValue else { return CookInputVocabulary.notRecordedLabel }
        let unit = cook.initialSetpointUnit?
            .replacingOccurrences(of: "°F", with: "°")
            .replacingOccurrences(of: "°C", with: "°") ?? "°"
        return "\(formatResultNumber(value))\(unit)"
    }

    /// 5.4: the one generated teaching sentence, or nil. A sentence exists
    /// only when the recorded data carries it: finished cooks of one protein
    /// (the lead dish's recorded cut) whose wrap material was recorded as
    /// data (§4.2) and whose stall the §4.1 detector finds in their readings.
    /// The winning material needs its last three recorded cooks (§9's ≥3
    /// comparable-cooks restraint), the other material at least two, and the
    /// average stall gap at least 20 minutes — the printed "~" figure is
    /// rounded down, so the sentence never claims more than the data shows.
    static func teachingSentence(cooks: [CompareCookInput], archive: InsightArchiveInput) -> String? {
        struct WrapStall {
            let material: WrapMaterial
            let stallMinutes: Double
            let endedAtUtcMillis: Double
        }
        var byProtein: [String: [WrapStall]] = [:]
        for cook in cooks where cook.status == "completed" {
            guard let lead = leadDish(cookId: cook.id, archive: archive) else { continue }
            guard let protein = lead.cut?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(), !protein.isEmpty else { continue }
            guard let material = inputs(cookId: cook.id, archive: archive, leadDish: lead).wrapMaterial, material != .none else { continue }
            guard let stall = stallMinutes(cookId: cook.id, readings: archive.readings, dishId: lead.id) else { continue }
            byProtein[protein, default: []].append(
                WrapStall(material: material, stallMinutes: stall, endedAtUtcMillis: cook.endedAtUtcMillis ?? cook.startedAtUtcMillis))
        }
        struct Verdict {
            let sentence: String
            let support: Int
            let marginMinutes: Double
            let protein: String
        }
        var verdicts: [Verdict] = []
        for (protein, rows) in byProtein {
            let paper = rows.filter { $0.material == .butcherPaper }.sorted { $0.endedAtUtcMillis > $1.endedAtUtcMillis }
            let foil = rows.filter { $0.material == .foil }.sorted { $0.endedAtUtcMillis > $1.endedAtUtcMillis }
            func verdict(winner: [WrapStall], loser: [WrapStall], winnerWord: String, loserWord: String) -> Verdict? {
                guard winner.count >= 3, loser.count >= 2 else { return nil }
                let winnerStalls = winner.prefix(3).map(\.stallMinutes)
                let loserStalls = loser.prefix(3).map(\.stallMinutes)
                let margin = loserStalls.reduce(0, +) / Double(loserStalls.count)
                    - winnerStalls.reduce(0, +) / Double(winnerStalls.count)
                guard margin >= 20 else { return nil }
                let claim = margin >= 50 ? "~\(Int(margin / 60))h" : "~\(Int(margin / 5) * 5)m"
                return Verdict(
                    sentence: "\(winnerWord.prefix(1).uppercased())\(winnerWord.dropFirst()) is winning: your last three \(winnerWord) wraps stalled \(claim) shorter than \(loserWord).",
                    support: winner.count + loser.count, marginMinutes: margin, protein: protein)
            }
            if let verdict = verdict(winner: paper, loser: foil, winnerWord: "paper", loserWord: "foil") { verdicts.append(verdict) }
            if let verdict = verdict(winner: foil, loser: paper, winnerWord: "foil", loserWord: "paper") { verdicts.append(verdict) }
        }
        return verdicts.sorted { a, b in
            if a.support != b.support { return a.support > b.support }
            if a.marginMinutes != b.marginMinutes { return a.marginMinutes > b.marginMinutes }
            return a.protein < b.protein
        }.first?.sentence
    }

    /// The compare grid's rows in Android's order: the recorded inputs
    /// (§4.2) directly above the outcomes, then the detail ratings any
    /// compared cook recorded.
    static func gridRows(facts: [CompareCookFacts]) -> [CompareGridRow] {
        func row(_ label: String, _ values: [String]) -> CompareGridRow {
            CompareGridRow(label: label, cells: values.map { CompareGridCell(text: $0, isProjected: false) })
        }
        var rows: [CompareGridRow] = [
            row("Cook", facts.map(\.title)),
            row("Wrap", facts.map(\.wrapText)),
            row("Rub", facts.map(\.rubText)),
            row("Spritz", facts.map(\.spritzText)),
            row("Setpoint", facts.map(\.setpointText)),
            CompareGridRow(label: "Time on", cells: facts.map {
                CompareGridCell(text: $0.timeOnText, isProjected: $0.projected != nil)
            }),
            row("Stall", facts.map { $0.stallMinutes.map { formatMinutes($0) } ?? "—" }),
            row("Final temp", facts.map(\.finalTempText)),
            row("To wrap", facts.map(\.toWrapText)),
            row("Rest", facts.map(\.restText)),
            row("Overall", facts.map(\.overallText)),
        ]
        let detailRows: [(label: String, type: String)] = [
            ("Bark", "bark"), ("Tenderness", "tenderness"), ("Juiciness", "juiciness"),
            ("Smoke", "smoke"), ("Seasoning", "seasoning"),
        ]
        for (label, type) in detailRows where facts.contains(where: { $0.detailRatingTexts[type] != nil }) {
            rows.append(row(label, facts.map { $0.detailRatingTexts[type] ?? "—" }))
        }
        return rows
    }

    /// The 5.1 stepper's readout: how far a curve sits from food-on alignment.
    static func offsetLabel(_ offsetMillis: Double) -> String {
        let minutes = Int(offsetMillis / 60_000)
        if minutes > 0 { return "+\(minutes)m" }
        if minutes < 0 { return "−\(-minutes)m" }
        return "Food-on"
    }

    /// Android's grid `formatMinutes`: truncated, "2h 40m" / "95m".
    static func formatMinutes(_ minutes: Double) -> String {
        let hours = Int(minutes / 60)
        let remaining = Int(minutes) % 60
        return hours > 0 ? "\(hours)h \(remaining)m" : "\(remaining)m"
    }

    /// Android's `formatResultNumber` / `formatCompareNumber`: whole
    /// values print as integers, everything else as-is.
    static func formatResultNumber(_ value: Double) -> String {
        value.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(value)) : String(value)
    }
}
