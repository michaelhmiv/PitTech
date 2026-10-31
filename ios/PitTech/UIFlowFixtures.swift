#if DEBUG
import SwiftUI
import Foundation

struct ScreenshotTextSize: ViewModifier {
    func body(content: Content) -> some View {
        if CommandLine.arguments.contains("--screenshot-large-text") { content.dynamicTypeSize(.accessibility3) } else { content }
    }
}
@MainActor extension CookStore {
    func prepareUIFlowFixture() {
        guard CommandLine.arguments.contains("--ui-test-reset") else { return }
        // A clean automation library also needs clean owned photo files. Fixed-ID
        // server fixtures are imported again in the enlarged-text app launch.
        let attachments = storageURL.deletingLastPathComponent().appendingPathComponent("attachments", isDirectory: true)
        if FileManager.default.fileExists(atPath: attachments.path) {
            do { try FileManager.default.removeItem(at: attachments) }
            catch { assertionFailure("The UI fixture could not reset its owned photos."); return }
        }
        library = PitTechLibrary(); noteDrafts = []
        // PL-13: a kept Start Cook setup draft is device state too — a reset
        // launch starts with no draft, like a fresh install.
        clearSetupDraft()
        // PL-20 (#184): crash diagnostics are device state too — every
        // fixture launch starts with none pending, except the fixture
        // that seeds one below.
        CrashDiagnostics.shared.resetForUITests()
        if CommandLine.arguments.contains("--ui-test-pack") { prepareIncomingPackFixture(); return }
        if !CommandLine.arguments.contains("--ui-test-empty") {
            for (name, category) in [("Chicken", "Poultry"), ("Mac and cheese", "Pasta"), ("Pork", "Pork")] {
                startCook(title: "Previous " + name, firstDish: name)
                guard let cook = activeCooks.first, let dish = dishRecords(cook.id).first else { continue }
                if let index = library.dishes.firstIndex(where: { $0.objectValue?["id"]?.stringValue == dish.id }), var value = library.dishes[index].objectValue { value["foodType"] = .string(category); library.dishes[index] = .object(value) }
                let anchor = Double(Int64(Date.now.addingTimeInterval(-5 * 3600).timeIntervalSince1970 * 1000))
                do {
                    if category == "Pasta" { try saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(method: "stovetop", durationMillis: 480000), title: "Boil macaroni", occurredAt: anchor - 600000)) }
                    try saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: anchor))
                    try saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "wrap"), title: "Wrapped", occurredAt: anchor + 12000000))
                    if CommandLine.arguments.contains("--ui-test-guide") {
                        try saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, title: "Recorded check", occurredAt: anchor + 60000,
                            temperatures: [NoteTemperatureDraft(probeName: "Same name", valueText: "74.125", unit: "°C", measuredAt: anchor + 60000, dishId: dish.id)]))
                    }
                    finishCook(cook.id)
                    let snapshot = try extractRecipe(cookId: cook.id, dishId: dish.id)
                    _ = try saveRecipe(snapshot, sourceCookId: cook.id, sourceDishId: dish.id)
                } catch { assertionFailure("UI fixture failed: " + error.localizedDescription) }
            }
        }
        if CommandLine.arguments.contains("--ui-test-crash-recovery") { prepareCrashRecoveryFixture() }
        if CommandLine.arguments.contains("--ui-test-plan-alerts") { preparePlanAlertsFixture() }
        if CommandLine.arguments.contains("--ui-test-flowing") { prepareFlowingFixture() }
        if CommandLine.arguments.contains("--ui-test-flowing-story") { prepareFlowingStoryFixture() }
        if CommandLine.arguments.contains("--ui-test-capture") {
            // Flowing §4.2: one active cook for the composer-capture flow.
            startCook(title: "Capture flow brisket", firstDish: "Brisket")
            if let cook = activeCooks.first, let dish = dishRecords(cook.id).first {
                do {
                    try saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on",
                        occurredAt: Double(Int64(Date.now.timeIntervalSince1970 * 1000)) - 3600000))
                } catch { assertionFailure("Capture UI fixture failed: " + error.localizedDescription) }
            }
        }
        if CommandLine.arguments.contains("--ui-test-guide") {
            do {
                let revisions = library.recipes.compactMap { $0.objectValue?["currentRevisionId"]?.stringValue }
                let cook = try startRecipeMeal(title: "Guided meal", revisionIds: revisions)
                for (index, dish) in dishRecords(cook).enumerated() {
                    try saveNote(CookNoteDraft(cookId: cook, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: Double(Int64(Date.now.timeIntervalSince1970 * 1000)) - 3600000 + Double(index * 600000)))
                }
            } catch { assertionFailure("Guide UI fixture failed: " + error.localizedDescription) }
        }
        save()
    }
    /// PL-20 (#184, --ui-test-crash-recovery): one active cook that was
    /// "open at the crash", plus a pending diagnostic report with a fixed
    /// reference code, so the recovery surface and the feedback attach
    /// preview are deterministic.
    private func prepareCrashRecoveryFixture() {
        startCook(title: "Crash fixture brisket", firstDish: "Brisket")
        guard let cook = activeCooks.first else { return }
        let report = DiagnosticReport(
            referenceCode: "PT-1A2B3C4D",
            occurredAtUtc: "2026-10-30 09:15:00 UTC",
            source: "Uncaught application exception",
            summary: "FixtureException: seeded for the crash-recovery flow",
            details: "Thread: main\nException: FixtureException\n\nSeeded stack trace")
        CrashDiagnostics.shared.saveReport(report, occurredAtUtcMillis: 1_793_000_000_000)
        CrashDiagnostics.shared.recordOpenCook(cook.id)
    }

    /// Issue #183 (--ui-test-plan-alerts): one active cook carrying the
    /// three surfaces this slice lights up — a guidance plan with an
    /// elapsed step already due and a manual rest step, a target alert
    /// made active by a fresh reading, and legacy reminders (one due,
    /// one future).
    private func preparePlanAlertsFixture() {
        startCook(title: "Guidance brisket", firstDish: "Brisket")
        guard let cook = activeCooks.first, let dish = dishRecords(cook.id).first else { return }
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        do {
            try saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: Double(now - 3_600_000)))
            let book = CookPlaybook(
                name: "Guidance fixture",
                draft: PlaybookCookDraft(title: "Guidance brisket", dishes: [PlaybookDishDraft(name: "Brisket", foodType: "Beef")]),
                steps: [
                    PlaybookStep(id: "ui-step-wrap", dishIndex: 0, action: "wrap", title: "Wrap when bark sets", instructions: "Wrap in butcher paper.", trigger: "elapsed", minutes: 30),
                    PlaybookStep(id: "ui-step-rest", dishIndex: 0, action: "rest", title: "Rest the brisket", instructions: "Rest in a cooler.", trigger: "manual"),
                ])
            let plan = CookPlan(book: book, dishIds: [dish.id])
            library.companionRecords.append(.object([
                "id": .string("plan:" + cook.id), "kind": .string("plan"), "cookId": .string(cook.id),
                "title": .string("Guidance fixture"), "payload": .string(try CookPlanCodec.encode(plan)),
                "createdAtUtcMillis": .number(Double(now)), "updatedAtUtcMillis": .number(Double(now)),
            ]))
            try saveCookAlert(cookId: cook.id, title: "Brisket target", rule: CookAlertRule(dishId: dish.id, targetF: 165))
            try saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, title: "Checked temp", occurredAt: Double(now),
                temperatures: [NoteTemperatureDraft(probeName: "Probe 1", valueText: "170", unit: "°F", measuredAt: Double(now), dishId: dish.id)]))
            // The due reminder predates "now", so it is inserted as a
            // row (createCookReminder rightly refuses past times).
            library.reminders.append(.object(LegacyReminder(row: [
                "id": .string("ui-legacy-due"), "cookId": .string(cook.id), "title": .string("Check the bark"),
                "dueAtUtcMillis": .number(Double(now - 60_000)), "timeZoneId": .string(TimeZone.current.identifier),
                "status": .string("pending"), "createdAtUtcMillis": .number(Double(now - 3_600_000)),
            ]).row))
            _ = try createCookReminder(cookId: cook.id, title: "Rotate the grate", dueAtUtcMillis: now + 3_600_000)
        } catch { assertionFailure("Plan/alerts fixture failed: " + error.localizedDescription) }
    }

    /// Flowing Slice 2 (--ui-test-flowing): one finished brisket (the
    /// ghost/history source) and one live brisket six hours in, out of
    /// the band and climbing at a pace the engine can state a range for.
    /// Series are hand-laid so the verdict, ETA basis and ghost delta
    /// are deterministic: the live cook's last hour climbs at
    /// 0.2°F/min on 5-minute readings, so the trailing 30-minute pace
    /// window holds a qualifying set at any realistic launch-to-open
    /// delay (PACE basis), and the ghost reached 175° at +398 min
    /// against the live cook's +360 (+38 at the cursor).
    private func prepareFlowingFixture() {
        let now = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
        func reading(cookId: String, dishId: String, type: String, value: Double, at: Double, probe: String) {
            library.readings.append(.object([
                "id": .string(UUID().uuidString.lowercased()), "cookId": .string(cookId),
                "dishId": .string(dishId), "probeName": .string(probe),
                "measurementType": .string(type), "value": .number(value), "unit": .string("°F"),
                "measuredAtUtcMillis": .number(at), "timeZoneId": .string(TimeZone.current.identifier),
                "source": .string("manual"), "qualityStatus": .string("valid"),
                "recordedAtUtcMillis": .number(at), "timestampBasis": .string("measurement"),
            ]))
        }
        func markCut(_ dishId: String) {
            if let index = library.dishes.firstIndex(where: { $0.objectValue?["id"]?.stringValue == dishId }), var value = library.dishes[index].objectValue {
                value["cut"] = .string("Brisket"); library.dishes[index] = .object(value)
            }
        }
        startCook(title: "Last brisket", firstDish: "Brisket")
        guard let ghost = activeCooks.first, let ghostDish = dishRecords(ghost.id).first else { return }
        markCut(ghostDish.id)
        let ghostOn = now - 30 * 3_600_000
        do {
            try saveNote(CookNoteDraft(cookId: ghost.id, dishId: ghostDish.id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: ghostOn))
            let ghostSeries: [(Double, Double)] = [(0, 40), (30, 58), (60, 76), (90, 92), (120, 106), (150, 118), (180, 129), (210, 139), (240, 148), (270, 154), (300, 158), (330, 162), (360, 166), (390, 172), (398, 175), (420, 180), (450, 186), (480, 191), (510, 196), (540, 200), (570, 202)]
            for (minutes, temp) in ghostSeries { reading(cookId: ghost.id, dishId: ghostDish.id, type: "food_probe", value: temp, at: ghostOn + minutes * 60_000, probe: "Brisket") }
            try saveNote(CookNoteDraft(cookId: ghost.id, dishId: ghostDish.id, metadata: CookNoteMetadata(category: "action", action: "remove"), title: "Off the pit", occurredAt: ghostOn + 580 * 60_000))
        } catch { assertionFailure("Flowing fixture failed: " + error.localizedDescription); return }
        library.results.append(.object(["id": .string("\(ghost.id):dish:final_temperature"), "cookId": .string(ghost.id), "dishId": .string(ghostDish.id), "resultType": .string("final_temperature"), "numericValue": .number(202), "recordedAtUtcMillis": .number(ghostOn + 580 * 60_000)]))
        finishCook(ghost.id)
        startCook(title: "Flowing brisket", firstDish: "Brisket")
        guard let live = activeCooks.first, let liveDish = dishRecords(live.id).first else { return }
        markCut(liveDish.id)
        let liveOn = now - 6 * 3_600_000
        do {
            try saveNote(CookNoteDraft(cookId: live.id, dishId: liveDish.id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: liveOn))
        } catch { assertionFailure("Flowing fixture failed: " + error.localizedDescription); return }
        library.targets.append(.object(["id": .string(UUID().uuidString.lowercased()), "cookId": .string(live.id), "dishId": .string(liveDish.id),
            "targetType": .string("personal_finish"), "value": .number(203), "unit": .string("°F"),
            "scope": .string("dish"), "explanation": .null, "createdAtUtcMillis": .number(liveOn)]))
        let liveSeries: [(Double, Double)] = [(0, 40), (15, 52), (30, 63), (45, 74), (60, 84), (75, 93), (90, 102), (105, 110), (120, 118), (135, 125), (150, 132), (165, 138), (180, 144), (195, 149), (210, 153), (225, 156), (240, 158), (255, 160), (270, 161), (285, 162), (300, 163), (305, 164), (310, 165), (315, 166), (320, 167), (325, 168), (330, 169), (335, 170), (340, 171), (345, 172), (350, 173), (355, 174), (360, 175)]
        for (minutes, temp) in liveSeries { reading(cookId: live.id, dishId: liveDish.id, type: "food_probe", value: temp, at: liveOn + minutes * 60_000, probe: "Brisket") }
        reading(cookId: live.id, dishId: liveDish.id, type: "setpoint", value: 250, at: liveOn + 330 * 60_000, probe: "Pit setpoint")
        save()
    }

    /// Flowing Slice 3 (--ui-test-flowing-story): one finished Boston
    /// butt with a full recorded story (phase actions through the note
    /// writer, a hand-laid curve, recorded results) and the recipe
    /// saved from it, one plain finished cook for E4, and one recipe
    /// with no source cook at all. Times are fixed — October 12,
    /// 2026 — so the story's provenance line is deterministic.
    private func prepareFlowingStoryFixture() {
        UserDefaults.standard.removeObject(forKey: "protect-log-prompt-shown")
        let start = 1_791_799_500_000.0 // Mon Oct 12, 2026 10:05 UTC
        func at(_ minute: Double) -> Double { start + minute * 60_000 }
        startCook(title: "Story butt cook", firstDish: "Boston Butt")
        guard let cook = activeCooks.first, let dish = dishRecords(cook.id).first else { return }
        if let index = library.cooks.firstIndex(where: { $0.id == cook.id }) {
            library.cooks[index].startedAtUtcMillis = Int64(start)
            library.cooks[index].startedTimeZoneId = "America/New_York"
            library.cooks[index].smokerName = "Offset smoker"
            library.cooks[index].initialSetpointValue = 250
            library.cooks[index].initialSetpointUnit = "°F"
            library.cooks[index].woodOrPelletBlend = "Hickory"
            library.cooks[index].fuelType = "Charcoal"
            library.cooks[index].createdAtUtcMillis = Int64(start)
            library.cooks[index].updatedAtUtcMillis = Int64(start)
        }
        if let index = library.dishes.firstIndex(where: { $0.objectValue?["id"]?.stringValue == dish.id }), var row = library.dishes[index].objectValue {
            row["cut"] = .string("Boston butt"); row["weightValue"] = .number(9.4); row["weightUnit"] = .string("lb")
            library.dishes[index] = .object(row)
        }
        do {
            try saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: at(0)))
            try saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "wrap"), title: "Wrapped", occurredAt: at(195)))
            try saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "remove"), title: "Off the pit", occurredAt: at(580)))
            // rest_start must be strictly after remove: at equal
            // timestamps the phase engine orders actions by event id
            // (a random UUID per note), so a tie makes validation
            // order — and this fixture — a coin flip.
            try saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "rest_start"), title: "Resting", occurredAt: at(585)))
            try saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "dish_done"), title: "Sliced", occurredAt: at(655)))
        } catch { assertionFailure("Flowing story fixture failed: " + error.localizedDescription); return }
        var minute = 0.0
        while minute <= 580 {
            let value: Double
            switch minute {
            case ...120: value = 40 + minute
            case ...210: value = 160
            case ...296: value = 160 + (minute - 210) * 0.5
            default: value = 203
            }
            library.readings.append(.object([
                "id": .string(UUID().uuidString.lowercased()), "cookId": .string(cook.id), "dishId": .string(dish.id),
                "probeId": .string("story-probe"), "probeName": .string("Probe 1"),
                "measurementType": .string("food_probe"), "value": .number(value), "unit": .string("°F"),
                "measuredAtUtcMillis": .number(at(minute)), "recordedAtUtcMillis": .number(at(minute)),
                "timeZoneId": .string("America/New_York"), "source": .string("manual"), "qualityStatus": .string("valid"),
                "timestampBasis": .string("measurement"), "samplingIntervalMillis": .number(0),
            ]))
            minute += 10
        }
        func result(_ type: String, _ numeric: Double?, _ text: String?) {
            library.results.append(.object(["id": .string(UUID().uuidString.lowercased()), "cookId": .string(cook.id), "dishId": .string(dish.id),
                                            "resultType": .string(type), "numericValue": numeric.map(JSONValue.number) ?? .null, "textValue": text.map(JSONValue.string) ?? .null,
                                            "recordedAtUtcMillis": .number(at(655))]))
        }
        result("overall_rating", 4, nil); result("cook_again", 1, nil)
        result("keep_doing", nil, "Hickory"); result("change_next_time", nil, "Wrap earlier"); result("result_notes", nil, "Bark set by hour 5.")
        finishCook(cook.id)
        if let index = library.cooks.firstIndex(where: { $0.id == cook.id }) { library.cooks[index].endedAtUtcMillis = Int64(at(655)) }
        do {
            let snapshot = try extractRecipe(cookId: cook.id, dishId: dish.id)
            _ = try saveRecipe(snapshot, sourceCookId: cook.id, sourceDishId: dish.id)
            // A recipe with no source cook: the honest-absence control.
            let plain = RecipeSnapshot(version: 1, name: "Plain toast", description: "", tags: [],
                                       draft: .object(["dishes": .array([.object(["name": .string("Toast")])])]),
                                       instructions: [], observations: [], targets: [], resultNotes: "",
                                       rating: nil, smokingAnchorUtcMillis: nil, servings: "")
            _ = try saveRecipe(plain)
        } catch { assertionFailure("Flowing story fixture failed: " + error.localizedDescription); return }
        // E4's subject: a finished cook with nothing else attached.
        startCook(title: "Finished solo", firstDish: "Ribs")
        if let solo = activeCooks.first { finishCook(solo.id) }
        save()
    }

    private func prepareIncomingPackFixture() {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        Task { @MainActor in
            defer { try? FileManager.default.removeItem(at: folder) }
            do {
                let source = CookStore(directory: folder)
                source.startCook(title: "Pork and potatoes", firstDish: "Pork")
                let cook = source.activeCooks[0].id; source.addDish(cookId: cook, name: "Potatoes")
                for dish in source.dishRecords(cook) {
                    let image = UIGraphicsImageRenderer(size: CGSize(width: 80, height: 60)).image { context in
                        (dish.name == "Pork" ? UIColor.brown : UIColor.systemYellow).setFill(); context.fill(CGRect(x: 0, y: 0, width: 80, height: 60))
                    }
                    let photo = try source.stagePhoto(image.jpegData(compressionQuality: 0.8)!)
                    try source.saveNote(CookNoteDraft(cookId: cook, dishId: dish.id, title: "Recorded result", photos: [photo]))
                }
                source.finishCook(cook)
                let id = try source.createSharingDraft(cookId: cook, dishIds: Set(source.dishRecords(cook).map(\.id)))
                var document = try source.sharingDocument(id)
                for index in document.entries.indices {
                    document.entries[index].includeStory = true
                    document.entries[index].photoIds = source.sharingPhotos(id).filter { $0["action"]?.stringValue == document.entries[index].id }.compactMap { $0["id"]?.stringValue }
                }
                try source.saveSharingDraft(id, document: document, expectedUpdated: source.sharingRecord(id)!["updated"]!.numberValue!)
                let file = folder.appendingPathComponent("preview.pittech")
                let data = try await source.exportCookPackAsync(id, includeRecordedCook: CommandLine.arguments.contains("--ui-test-recorded-pack"))
                try data.write(to: file)
                await receiveArchive(at: file)
            } catch { assertionFailure("Cook Pack UI fixture failed: " + error.localizedDescription) }
        }
    }
}
#else
import SwiftUI
struct ScreenshotTextSize: ViewModifier { func body(content: Content) -> some View { content } }
#endif
