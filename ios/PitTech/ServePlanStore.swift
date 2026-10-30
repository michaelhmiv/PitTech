import Foundation

/// Serve-plan persistence and lifecycle (Flowing Slice 4 §3, Slice 5
/// §4.4): plans live in companion records — kind "upcoming" before the
/// cook starts, kind "serve_goal" (id "serve:<cookId>") once attached —
/// with the Android codec's payload string, so plans sync and read
/// identically on both platforms. Mirrors Android's ServePlanRepository
/// and the plan-this half of its CooksViewModel.
@MainActor extension CookStore {
    private func companionRecord(_ id: String) -> [String: JSONValue]? {
        library.companionRecords.first { $0.objectValue?["id"]?.stringValue == id }?.objectValue
    }

    private func decodePlan(_ record: [String: JSONValue]) -> ServePlan? {
        guard let payload = record["payload"]?.stringValue else { return nil }
        return try? ServeTimePlanner.decodeString(payload)
    }

    private func upsertCompanionRecord(_ record: [String: JSONValue]) {
        guard let id = record["id"]?.stringValue else { return }
        library.companionRecords.removeAll { $0.objectValue?["id"]?.stringValue == id }
        library.companionRecords.append(.object(record))
    }

    /// Every unstarted plan, soonest serve first.
    func upcomingServePlans() -> [(id: String, plan: ServePlan)] {
        library.companionRecords.compactMap { row -> (id: String, plan: ServePlan)? in
            guard let record = row.objectValue, record["kind"]?.stringValue == "upcoming",
                  let id = record["id"]?.stringValue, let plan = decodePlan(record),
                  plan.startedCookId == nil else { return nil }
            return (id, plan)
        }.sorted { $0.plan.serveAtUtcMillis < $1.plan.serveAtUtcMillis }
    }

    func servePlan(_ id: String) -> ServePlan? {
        guard let record = companionRecord(id), record["kind"]?.stringValue == "upcoming" else { return nil }
        return decodePlan(record)
    }

    /// The started plan guiding a live cook, when it has one.
    func serveGoal(cookId: String) -> ServePlan? {
        guard let record = companionRecord("serve:" + cookId), record["kind"]?.stringValue == "serve_goal" else { return nil }
        return decodePlan(record)
    }

    @discardableResult
    func saveServePlan(_ plan: ServePlan, id: String?) throws -> String {
        try ServeTimePlanner.validate(plan)
        guard plan.startedCookId == nil else {
            throw ServePlanError.invalid("Edit the active cook's plan instead.")
        }
        let nowMs = Int64(Date.now.timeIntervalSince1970 * 1000)
        guard plan.serveAtUtcMillis > nowMs else {
            throw ServePlanError.invalid("Choose a future serving time.")
        }
        let recordId = id ?? UUID().uuidString.lowercased()
        let payload = try ServeTimePlanner.encodeString(plan)
        let prior = library
        var record = companionRecord(recordId) ?? [
            "id": .string(recordId), "kind": .string("upcoming"),
            "createdAtUtcMillis": .number(Double(nowMs)),
        ]
        record["kind"] = .string("upcoming")
        record["title"] = .string(plan.book.name)
        record["payload"] = .string(payload)
        record["updatedAtUtcMillis"] = .number(Double(nowMs))
        record.removeValue(forKey: "cookId")
        upsertCompanionRecord(record)
        save()
        if saveError != nil {
            library = prior
            throw ServePlanError.invalid("PitTech couldn't save the plan.")
        }
        return recordId
    }

    func deleteServePlan(_ id: String) throws {
        let prior = library
        library.companionRecords.removeAll { $0.objectValue?["id"]?.stringValue == id }
        save()
        if saveError != nil {
            library = prior
            throw ServePlanError.invalid("PitTech couldn't delete the plan.")
        }
    }

    /// Measured food-on → remove durations from this library's completed
    /// cooks: exactly one of each per dish, in a sane range. Anything less
    /// exact is not evidence.
    func planEvidence() -> [DurationEvidence] {
        var evidence: [DurationEvidence] = []
        for cook in library.cooks where cook.status == "completed" {
            let actions = library.actions(for: cook.id)
            for dish in dishRecords(cook.id) {
                let scoped = actions.filter { $0.dishIds.isEmpty || $0.dishIds.contains(dish.id) }
                let foodOns = scoped.filter { $0.action == "food_on" }
                let removes = scoped.filter { $0.action == "remove" }
                guard foodOns.count == 1, removes.count == 1,
                      let foodOn = foodOns.first, let remove = removes.first,
                      remove.at > foodOn.at else { continue }
                let duration = Int64((remove.at - foodOn.at) / 60_000)
                guard (1 ... 100_800).contains(duration) else { continue }
                let row = library.dishes.first { $0.objectValue?["id"]?.stringValue == dish.id }?.objectValue
                evidence.append(DurationEvidence(
                    dishName: dish.name,
                    cut: row?["cut"]?.stringValue ?? "",
                    foodType: row?["foodType"]?.stringValue ?? "Other",
                    smokerName: cook.smokerName,
                    weightKg: ServeTimePlanner.weightKg(row?["weightValue"]?.numberValue, row?["weightUnit"]?.stringValue),
                    startingCondition: row?["startingCondition"]?.stringValue,
                    boneIn: row?["boneIn"]?.boolValue,
                    setpointF: cook.initialSetpointValue.flatMap { ServeTimePlanner.fahrenheit($0, unit: cook.initialSetpointUnit) },
                    durationMinutes: duration,
                    cookId: cook.id,
                    wrapped: scoped.contains { $0.action == "wrap" },
                    cookedAtUtcMillis: cook.startedAtUtcMillis
                ))
            }
        }
        // Library order (oldest first), like Android's DAO iteration —
        // comparable's one-row-per-cook rule keeps the first match.
        return evidence
    }

    /// A cook's plan-relevant events, expanded per dish scope; pass a
    /// dishId to keep only that dish's events plus cook-wide ones.
    func planEvents(cookId: String, dishId: String? = nil) -> [PlanEvent] {
        library.actions(for: cookId).flatMap { action -> [PlanEvent] in
            let at = Int64(action.at)
            if action.dishIds.isEmpty { return [PlanEvent(action: action.action, dishId: nil, atUtcMillis: at)] }
            return action.dishIds.map { PlanEvent(action: action.action, dishId: $0, atUtcMillis: at) }
        }.filter { dishId == nil || $0.dishId == dishId || $0.dishId == nil }
    }

    /// Starting a planned cook: the plan's book becomes the cook's serve
    /// goal, the upcoming record is marked started, and the plan's
    /// clock-based derived alerts arm as reminder rules on the cook
    /// (condition-based alerts have no clock to arm). Mirrors Android's
    /// ServePlanRepository.attach.
    func attachServePlan(_ id: String, cookId: String, orderedDishIds: [String]) throws {
        guard let record = companionRecord(id), record["kind"]?.stringValue == "upcoming",
              let plan = decodePlan(record) else {
            throw ServePlanError.invalid("This scheduled cook is no longer saved.")
        }
        guard plan.startedCookId == nil else {
            throw ServePlanError.invalid("This scheduled cook has already started.")
        }
        guard orderedDishIds.count == plan.book.draft.dishes.count else {
            throw ServePlanError.invalid("Update the serving plan when changing its dish list, then start the cook.")
        }
        let prior = library
        do {
            var started = plan
            started.startedCookId = cookId
            let dishes = dishRecords(cookId)
            for (index, dishId) in orderedDishIds.enumerated() where started.book.draft.dishes.indices.contains(index) {
                if let name = dishes.first(where: { $0.id == dishId })?.name {
                    started.book.draft.dishes[index].name = name
                }
            }
            if let cook = library.cooks.first(where: { $0.id == cookId }) {
                started.book.draft.title = cook.title
            }
            let payload = try ServeTimePlanner.encodeString(started)
            let nowMs = Int64(Date.now.timeIntervalSince1970 * 1000)
            var startedRecord = record
            startedRecord["payload"] = .string(payload)
            startedRecord["updatedAtUtcMillis"] = .number(Double(nowMs))
            upsertCompanionRecord(startedRecord)
            upsertCompanionRecord([
                "id": .string("serve:" + cookId), "kind": .string("serve_goal"),
                "cookId": .string(cookId), "title": .string(started.book.name),
                "payload": .string(payload),
                "createdAtUtcMillis": .number(Double(nowMs)), "updatedAtUtcMillis": .number(Double(nowMs)),
            ])
            let timeline = PlanBoard.resolvedTimeline(plan: started, chosenPitF: nil)
            for alert in timeline.alerts {
                guard let at = alert.atUtcMillis else { continue }
                let kind = switch alert.kind {
                case .foodOn: "food_on"
                case .wrap: "wrap"
                case .off: "off"
                }
                let ruleId = "plan:\(cookId):\(alert.dishIndex):\(kind)"
                let config: [String: JSONValue] = [
                    "version": .number(1), "type": .string("clock"), "at": .number(Double(at)),
                ]
                let raw = try recipeJSON(config)
                _ = try NativeReminderEngine.configuration(raw)
                let dishId = orderedDishIds.indices.contains(alert.dishIndex) ? orderedDishIds[alert.dishIndex] : nil
                library.reminderRules.removeAll { $0.objectValue?["id"]?.stringValue == ruleId }
                library.reminderRules.append(.object([
                    "id": .string(ruleId), "cookId": .string(cookId),
                    "dishId": dishId.map(JSONValue.string) ?? .null,
                    "title": .string(alert.label), "kind": .string("clock"),
                    "triggerConfig": .string(raw),
                    "sourceRecipeRevisionId": .null, "sourceMilestoneId": .null,
                    "enabled": .bool(true), "status": .string("pending"),
                    "schemaVersion": .number(1),
                    "createdAtUtcMillis": .number(Double(nowMs)), "updatedAtUtcMillis": .number(Double(nowMs)),
                ]))
                library.reminderOccurrences.removeAll { $0.objectValue?["id"]?.stringValue == "state:" + ruleId }
                library.reminderOccurrences.append(.object([
                    "id": .string("state:" + ruleId), "ruleId": .string(ruleId),
                    "sequence": .number(0), "status": .string("pending"), "active": .bool(false),
                ]))
            }
            try library.validateReminderRules()
            save()
            if saveError != nil { throw CocoaError(.fileWriteUnknown) }
        } catch {
            library = prior
            throw error
        }
    }

    // MARK: - Editor entry points

    /// The plan-this dinner rule: today at 6 PM local while it is still
    /// ahead, else tomorrow at 6 PM.
    static func planDinner(now: Date = .now) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        var components = calendar.dateComponents([.year, .month, .day], from: now)
        components.hour = 18
        components.minute = 0
        components.second = 0
        let today = calendar.date(from: components) ?? now
        return today > now ? today : (calendar.date(byAdding: .day, value: 1, to: today) ?? today)
    }

    private static func localDateText(_ millis: Int64, zoneId: String) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zoneId) ?? .current
        let parts = calendar.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: Double(millis) / 1000))
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    func openServePlanEditor(plan: ServePlan, id: String?) {
        servePlanEditorPlan = plan
        servePlanEditorId = id
        servePlanEditorChosenPitF = nil
    }

    /// "Plan a serving time" from an empty slate (Android's
    /// planServeTime: a "Planned cook" book serving this time tomorrow).
    func newServePlan() {
        let dish = PlaybookDishDraft(name: "Main dish", foodType: "Beef")
        let plan = ServePlan(
            book: CookPlaybook(
                name: "Planned cook",
                draft: PlaybookCookDraft(title: "Planned cook", dishes: [dish])
            ),
            serveAtUtcMillis: Int64(Date.now.timeIntervalSince1970 * 1000) + 86_400_000,
            zoneId: TimeZone.current.identifier,
            schedules: [DishSchedule(dishIndex: 0, cookMinMinutes: 480, cookMaxMinutes: 600, evidenceNote: DishSchedule.unseededNote)]
        )
        openServePlanEditor(plan: plan, id: nil)
    }

    /// §4.4 plan-this: seed a lane from the recipe and open the editor on
    /// a plan serving the next 6 PM dinner — joining the unstarted plan
    /// that already serves that dinner (same local date, within three
    /// hours) as a new lane instead of starting a rival plan. An
    /// unseedable recipe still opens the editor; its dish waits for
    /// numbers the cook types.
    @discardableResult
    func planRecipe(recipeId: String, revisionId: String) -> Bool {
        guard let snapshot = try? recipeSnapshot(revisionId) else { return false }
        let revision = recipeRevision(revisionId)
        let provenance = recipeProvenance(
            sourceCookId: revision?["sourceCookId"]?.stringValue,
            sourceDishId: revision?["sourceDishId"]?.stringValue
        )
        let seeded = PlanSeeding.fromRecipe(snapshot: snapshot, provenance: provenance)
            ?? PlanSeeding.fromSnapshotStory(snapshot)
        guard let dish = seeded?.dish ?? PlanSeeding.playbookDish(snapshot) else { return false }
        let laneSchedule = seeded?.schedule ?? DishSchedule(
            dishIndex: 0, cookMinMinutes: 480, cookMaxMinutes: 600,
            pitTemperatureF: snapshot.preferredPitTemperatureF,
            evidenceNote: DishSchedule.unseededNote
        )
        let dinnerMs = Int64(Self.planDinner().timeIntervalSince1970 * 1000)
        let dinnerDate = Self.localDateText(dinnerMs, zoneId: TimeZone.current.identifier)
        // Android's join rule: unstarted, same local date as dinner (in
        // the device zone), within three hours — the closest such plan.
        let existing = upcomingServePlans()
            .filter { entry in
                entry.plan.startedCookId == nil
                    && Self.localDateText(entry.plan.serveAtUtcMillis, zoneId: TimeZone.current.identifier) == dinnerDate
                    && abs(entry.plan.serveAtUtcMillis - dinnerMs) <= 3 * 3_600_000
            }
            .min { abs($0.plan.serveAtUtcMillis - dinnerMs) < abs($1.plan.serveAtUtcMillis - dinnerMs) }
        if let existing {
            var plan = existing.plan
            var schedule = laneSchedule
            schedule.dishIndex = plan.book.draft.dishes.count
            plan.book.draft.dishes.append(dish)
            plan.schedules.append(schedule)
            openServePlanEditor(plan: plan, id: existing.id)
            return true
        }
        let plan = ServePlan(
            book: CookPlaybook(
                name: snapshot.name,
                draft: PlaybookCookDraft(title: snapshot.name, dishes: [dish])
            ),
            serveAtUtcMillis: dinnerMs,
            zoneId: TimeZone.current.identifier,
            schedules: [laneSchedule]
        )
        openServePlanEditor(plan: plan, id: nil)
        return true
    }

    /// The editor's "Add from a recipe" rows: every saved recipe's current
    /// version with its seeded lane or the honest no-seed note.
    func planSeedOptions() -> [PlanSeedOption] {
        library.recipes.compactMap { row -> PlanSeedOption? in
            guard let object = row.objectValue, let recipeId = object["id"]?.stringValue,
                  let revisionId = object["currentRevisionId"]?.stringValue,
                  let snapshot = try? recipeSnapshot(revisionId) else { return nil }
            let revision = recipeRevision(revisionId)
            let provenance = recipeProvenance(
                sourceCookId: revision?["sourceCookId"]?.stringValue,
                sourceDishId: revision?["sourceDishId"]?.stringValue
            )
            let lane = PlanSeeding.fromRecipe(snapshot: snapshot, provenance: provenance)
                ?? PlanSeeding.fromSnapshotStory(snapshot)
            return PlanSeedOption(
                recipeId: recipeId, revisionId: revisionId, title: snapshot.name,
                lane: lane, note: lane?.schedule.evidenceNote ?? PlanSeedOption.noSeedNote
            )
        }.sorted { $0.title < $1.title }
    }
}
