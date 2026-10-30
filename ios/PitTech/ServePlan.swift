import Foundation

/// Flowing Slice 4/5 serve-plan domain (the iOS port of Android's
/// ServeTimePlanner + PlaybookCodec). The persisted shape is the Android
/// codec's, key for key: a serve plan rides a companion record of kind
/// "upcoming" (and, once started, "serve_goal") whose payload is this
/// codec's JSON string, so a plan written on either platform reads on the
/// other. org.json drops null values on write; the encoder here omits nil
/// keys the same way, and the decoder tolerates absent or null throughout.

enum ServePlanError: Error, Equatable {
    case invalid(String)
}

extension ServePlanError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .invalid(let message): return message
        }
    }
}

// MARK: - Playbook (the saved-guide shape a serve plan carries)

struct PlaybookIngredientDraft: Equatable {
    var name: String
    var stage: String = "seasoning"
    var brand: String = ""
    var amount: String = ""
    var unit: String = "tbsp"
}

struct PlaybookDishDraft: Equatable {
    var name: String
    var foodType: String
    var cut: String = ""
    var weightText: String = ""
    var weightUnit: String = "lb"
    var startingCondition: String? = nil
    var boneIn: Bool? = nil
    var placement: String = ""
    var grade: String = ""
    var thickness: String = ""
    var prep: String = ""
    var ingredients: [PlaybookIngredientDraft] = []

    /// The dish's rub, read the way every surface reads it: the ingredient
    /// prepared at the rub stage (Slice 5 §4.2's fork writes it there too).
    var rub: String {
        ingredients.first { $0.stage.lowercased() == "rub" }?.name ?? ""
    }
}

struct PlaybookCookDraft: Equatable {
    var title: String
    var smokerName: String = ""
    var setpointText: String = ""
    var temperatureUnit: String = "°F"
    var notes: String = ""
    var fuelType: String = ""
    var woodBlend: String = ""
    var dishes: [PlaybookDishDraft] = []
}

struct PlaybookStep: Equatable, Identifiable {
    var id: String
    var dishIndex: Int? = nil
    var action: String
    var title: String
    var instructions: String = ""
    var trigger: String = "manual"
    var minutes: Int64 = 0
    var temperatureF: Double? = nil
    var anchor: String = "food_on"
    var clockAtUtcMillis: Int64? = nil
    var repeatEveryMinutes: Int64? = nil
    var stage: String = "cooking"
    var observations: String = ""
    var probeName: String = ""
}

struct PlaybookTarget: Equatable {
    var dishIndex: Int? = nil
    var type: String
    var value: Double
    var unit: String
    var explanation: String? = nil
}

struct CookPlaybook: Equatable {
    var name: String
    var draft: PlaybookCookDraft
    var steps: [PlaybookStep] = []
    var targets: [PlaybookTarget] = []
    var sourceCookId: String? = nil
    var revision: Int = 1
    var previousRevisionId: String? = nil
    var keepDoing: String = ""
    var changeNextTime: String = ""
}

// MARK: - Serve plan

struct DishSchedule: Equatable {
    var dishIndex: Int
    var cookMinMinutes: Int64
    var cookMaxMinutes: Int64
    var prepMinutes: Int64 = 30
    var preheatMinutes: Int64 = 30
    var restMinutes: Int64 = 60
    var holdMinutes: Int64 = 0
    var pitTemperatureF: Double? = nil
    var method: String = ""
    var evidenceCount: Int = 0
    var evidenceNote: String = "Your duration range"

    static let unseededNote = "Enter your duration range"
}

struct ServePlan: Equatable {
    var book: CookPlaybook
    var serveAtUtcMillis: Int64
    var zoneId: String
    var bufferMinutes: Int64 = 60
    var startedCookId: String? = nil
    var schedules: [DishSchedule] = []
}

/// One completed cook's measured duration for one dish, in the shape the
/// recorded-range matcher compares (Android's DurationEvidence).
struct DurationEvidence: Equatable {
    var dishName: String
    var cut: String
    var foodType: String
    var smokerName: String?
    var weightKg: Double?
    var startingCondition: String?
    var boneIn: Bool?
    var setpointF: Double?
    var durationMinutes: Int64
    var cookId: String
    var wrapped: Bool
    var cookedAtUtcMillis: Int64
}

/// A plan-relevant cook event (Android's PlanEvent): an action resolved to
/// its dish scope — nil dishId means the whole cook.
struct PlanEvent: Equatable {
    var action: String
    var dishId: String?
    var atUtcMillis: Int64
}

// MARK: - JSONValue field access with org.json semantics

private func fieldString(_ object: [String: JSONValue], _ key: String) -> String? {
    guard let value = object[key], value != .null else { return nil }
    return value.stringValue
}

private func fieldLong(_ object: [String: JSONValue], _ key: String) -> Int64? {
    guard let value = object[key], value != .null else { return nil }
    if let number = value.numberValue { return Int64(number) }
    if let text = value.stringValue { return Int64(text) }
    return nil
}

private func fieldDouble(_ object: [String: JSONValue], _ key: String) -> Double? {
    guard let value = object[key], value != .null else { return nil }
    if let number = value.numberValue { return number }
    if let text = value.stringValue { return Double(text) }
    return nil
}

private func fieldBool(_ object: [String: JSONValue], _ key: String) -> Bool? {
    guard let value = object[key], value != .null else { return nil }
    return value.boolValue
}

private func requiredString(_ object: [String: JSONValue], _ key: String, _ context: String) throws -> String {
    guard let value = fieldString(object, key) else {
        throw ServePlanError.invalid("\(context) is missing \(key).")
    }
    return value
}

private func requiredLong(_ object: [String: JSONValue], _ key: String, _ context: String) throws -> Int64 {
    guard let value = fieldLong(object, key) else {
        throw ServePlanError.invalid("\(context) is missing \(key).")
    }
    return value
}

private func requiredObject(_ object: [String: JSONValue], _ key: String, _ context: String) throws -> [String: JSONValue] {
    guard let value = object[key]?.objectValue else {
        throw ServePlanError.invalid("\(context) is missing \(key).")
    }
    return value
}

private func objects(_ object: [String: JSONValue], _ key: String) -> [[String: JSONValue]] {
    (object[key]?.arrayValue ?? []).compactMap { $0.objectValue }
}

// MARK: - PlaybookCodec

enum PlaybookCodec {
    static let actions: Set<String> = ["food_on", "spritz", "wrap", "check_in", "remove", "rest_start", "hold_start", "dish_done"]
    static let triggers: Set<String> = ["manual", "elapsed", "after_action", "temperature", "clock"]

    /// Mirrors Android's PlaybookCodec.validate rule for rule, messages
    /// included: the two platforms reject the same playbook with the
    /// same words.
    static func validate(_ playbook: CookPlaybook) throws {
        let stages: Set<String> = ["prep", "cooking", "resting", "holding"]
        if playbook.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || playbook.name.count > 200 {
            throw ServePlanError.invalid("Give this playbook a name.")
        }
        if playbook.draft.dishes.count > 30 || playbook.steps.count > 100 {
            throw ServePlanError.invalid("This playbook has too many dishes or steps.")
        }
        if Set(playbook.steps.map(\.id)).count != playbook.steps.count {
            throw ServePlanError.invalid("Each step needs its own ID.")
        }
        for step in playbook.steps {
            if step.id.range(of: "^[A-Za-z0-9_-]{1,100}$", options: .regularExpression) == nil {
                throw ServePlanError.invalid("Each step needs a valid ID.")
            }
            if step.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                || !actions.contains(step.action) || !triggers.contains(step.trigger) || !stages.contains(step.stage) {
                throw ServePlanError.invalid("Choose a valid action and trigger for each step.")
            }
            if let dish = step.dishIndex, dish < 0 || dish >= playbook.draft.dishes.count {
                throw ServePlanError.invalid("Choose a dish for this step.")
            }
            if step.minutes < 0 || step.minutes > 100_800
                || (step.repeatEveryMinutes.map { $0 < 1 || $0 > 10_080 } ?? false) {
                throw ServePlanError.invalid("Use a positive interval, up to a week.")
            }
            if step.trigger == "temperature",
               !(step.temperatureF.map { $0.isFinite && $0 >= 32 && $0 <= 600 } ?? false) {
                throw ServePlanError.invalid("Enter a temperature between 32 and 600 °F.")
            }
            if step.trigger == "clock", step.clockAtUtcMillis == nil {
                throw ServePlanError.invalid("Choose a time for this step.")
            }
            if !actions.contains(step.anchor) {
                throw ServePlanError.invalid("Choose an anchor action.")
            }
        }
        // after_action dependencies: a step anchors on the most recent
        // earlier step with its anchor action (or the first, looking
        // forward); a step that can only anchor on itself is refused,
        // and dependency loops are refused.
        var dependencies: [String: String] = [:]
        for (index, step) in playbook.steps.enumerated() where step.trigger == "after_action" {
            let providers = playbook.steps.enumerated().filter {
                $0.offset != index && $0.element.action == step.anchor
                    && ($0.element.dishIndex == step.dishIndex || $0.element.dishIndex == nil)
            }
            let provider = providers.last { $0.offset < index } ?? providers.first
            if provider == nil, step.action == step.anchor, step.repeatEveryMinutes == nil {
                throw ServePlanError.invalid("\(step.title) depends on itself. Choose another action or a recurring check.")
            }
            if let provider { dependencies[step.id] = provider.element.id }
        }
        var visiting = Set<String>(), visited = Set<String>()
        func visit(_ id: String) throws {
            if visited.contains(id) { return }
            if !visiting.insert(id).inserted {
                throw ServePlanError.invalid("Steps depend on each other in a loop. Use Food on or an independent action as the anchor.")
            }
            if let next = dependencies[id] { try visit(next) }
            visiting.remove(id); visited.insert(id)
        }
        for step in playbook.steps { try visit(step.id) }
        for target in playbook.targets {
            if !target.value.isFinite || !["°F", "°C"].contains(target.unit)
                || (target.dishIndex.map { $0 < 0 || $0 >= playbook.draft.dishes.count } ?? false) {
                throw ServePlanError.invalid("A target is invalid.")
            }
        }
    }

    static func encode(_ playbook: CookPlaybook) throws -> JSONValue {
        try validate(playbook)
        var object: [String: JSONValue] = [
            "version": .number(1),
            "name": .string(playbook.name),
            "draft": draftJSON(playbook.draft),
            "steps": .array(playbook.steps.map(stepJSON)),
            "targets": .array(playbook.targets.map(targetJSON)),
            "revision": .number(Double(playbook.revision)),
            "keepDoing": .string(playbook.keepDoing),
            "changeNextTime": .string(playbook.changeNextTime),
        ]
        if let sourceCookId = playbook.sourceCookId { object["sourceCookId"] = .string(sourceCookId) }
        if let previous = playbook.previousRevisionId { object["previousRevisionId"] = .string(previous) }
        return .object(object)
    }

    static func encodeString(_ playbook: CookPlaybook) throws -> String {
        let data = try JSONEncoder().encode(try encode(playbook))
        return String(decoding: data, as: UTF8.self)
    }

    static func decode(_ value: JSONValue) throws -> CookPlaybook {
        guard let object = value.objectValue else {
            throw ServePlanError.invalid("Playbook payload is not an object.")
        }
        return try decode(object)
    }

    static func decode(_ object: [String: JSONValue]) throws -> CookPlaybook {
        guard fieldLong(object, "version") == 1 else {
            throw ServePlanError.invalid("Unsupported playbook version.")
        }
        let playbook = CookPlaybook(
            name: try requiredString(object, "name", "Playbook"),
            draft: try draft(requiredObject(object, "draft", "Playbook")),
            steps: try objects(object, "steps").map(step),
            targets: try objects(object, "targets").map(target),
            sourceCookId: fieldString(object, "sourceCookId"),
            revision: Int(fieldLong(object, "revision") ?? 1),
            previousRevisionId: fieldString(object, "previousRevisionId"),
            keepDoing: fieldString(object, "keepDoing") ?? "",
            changeNextTime: fieldString(object, "changeNextTime") ?? ""
        )
        try validate(playbook)
        return playbook
    }

    static func draftJSON(_ draft: PlaybookCookDraft) -> JSONValue {
        .object([
            "title": .string(draft.title),
            "smoker": .string(draft.smokerName),
            "setpoint": .string(draft.setpointText),
            "unit": .string(draft.temperatureUnit),
            "notes": .string(draft.notes),
            "fuel": .string(draft.fuelType),
            "wood": .string(draft.woodBlend),
            "dishes": .array(draft.dishes.map(dishJSON)),
        ])
    }

    static func dishJSON(_ dish: PlaybookDishDraft) -> JSONValue {
        var object: [String: JSONValue] = [
            "name": .string(dish.name),
            "foodType": .string(dish.foodType),
            "cut": .string(dish.cut),
            "weight": .string(dish.weightText),
            "weightUnit": .string(dish.weightUnit),
            "placement": .string(dish.placement),
            "grade": .string(dish.grade),
            "thickness": .string(dish.thickness),
            "prep": .string(dish.prep),
            "ingredients": .array(dish.ingredients.map(ingredientJSON)),
        ]
        if let condition = dish.startingCondition { object["startingCondition"] = .string(condition) }
        if let boneIn = dish.boneIn { object["boneIn"] = .bool(boneIn) }
        return .object(object)
    }

    static func ingredientJSON(_ ingredient: PlaybookIngredientDraft) -> JSONValue {
        .object([
            "name": .string(ingredient.name),
            "stage": .string(ingredient.stage),
            "brand": .string(ingredient.brand),
            "amount": .string(ingredient.amount),
            "unit": .string(ingredient.unit),
        ])
    }

    static func stepJSON(_ step: PlaybookStep) -> JSONValue {
        var object: [String: JSONValue] = [
            "id": .string(step.id),
            "action": .string(step.action),
            "title": .string(step.title),
            "instructions": .string(step.instructions),
            "trigger": .string(step.trigger),
            "minutes": .number(Double(step.minutes)),
            "anchor": .string(step.anchor),
            "stage": .string(step.stage),
            "observations": .string(step.observations),
            "probeName": .string(step.probeName),
        ]
        if let dish = step.dishIndex { object["dish"] = .number(Double(dish)) }
        if let temperature = step.temperatureF { object["temperatureF"] = .number(temperature) }
        if let clock = step.clockAtUtcMillis { object["clock"] = .number(Double(clock)) }
        if let every = step.repeatEveryMinutes { object["repeat"] = .number(Double(every)) }
        return .object(object)
    }

    static func targetJSON(_ target: PlaybookTarget) -> JSONValue {
        var object: [String: JSONValue] = [
            "type": .string(target.type),
            "value": .number(target.value),
            "unit": .string(target.unit),
        ]
        if let dish = target.dishIndex { object["dish"] = .number(Double(dish)) }
        if let explanation = target.explanation { object["explanation"] = .string(explanation) }
        return .object(object)
    }

    static func draft(_ object: [String: JSONValue]) throws -> PlaybookCookDraft {
        PlaybookCookDraft(
            title: try requiredString(object, "title", "Cook draft"),
            smokerName: fieldString(object, "smoker") ?? "",
            setpointText: fieldString(object, "setpoint") ?? "",
            temperatureUnit: fieldString(object, "unit") ?? "°F",
            notes: fieldString(object, "notes") ?? "",
            fuelType: fieldString(object, "fuel") ?? "",
            woodBlend: fieldString(object, "wood") ?? "",
            dishes: try objects(object, "dishes").map(dish)
        )
    }

    static func dish(_ object: [String: JSONValue]) throws -> PlaybookDishDraft {
        PlaybookDishDraft(
            name: try requiredString(object, "name", "Dish draft"),
            foodType: try requiredString(object, "foodType", "Dish draft"),
            cut: fieldString(object, "cut") ?? "",
            weightText: fieldString(object, "weight") ?? "",
            weightUnit: fieldString(object, "weightUnit") ?? "lb",
            startingCondition: fieldString(object, "startingCondition"),
            boneIn: fieldBool(object, "boneIn"),
            placement: fieldString(object, "placement") ?? "",
            grade: fieldString(object, "grade") ?? "",
            thickness: fieldString(object, "thickness") ?? "",
            prep: fieldString(object, "prep") ?? "",
            ingredients: try objects(object, "ingredients").map(ingredient)
        )
    }

    static func ingredient(_ object: [String: JSONValue]) throws -> PlaybookIngredientDraft {
        PlaybookIngredientDraft(
            name: try requiredString(object, "name", "Ingredient"),
            stage: fieldString(object, "stage") ?? "seasoning",
            brand: fieldString(object, "brand") ?? "",
            amount: fieldString(object, "amount") ?? "",
            unit: fieldString(object, "unit") ?? "tbsp"
        )
    }

    static func step(_ object: [String: JSONValue]) throws -> PlaybookStep {
        PlaybookStep(
            id: try requiredString(object, "id", "Playbook step"),
            dishIndex: fieldLong(object, "dish").map(Int.init),
            action: try requiredString(object, "action", "Playbook step"),
            title: try requiredString(object, "title", "Playbook step"),
            instructions: fieldString(object, "instructions") ?? "",
            trigger: fieldString(object, "trigger") ?? "manual",
            minutes: fieldLong(object, "minutes") ?? 0,
            temperatureF: fieldDouble(object, "temperatureF"),
            anchor: fieldString(object, "anchor") ?? "food_on",
            clockAtUtcMillis: fieldLong(object, "clock"),
            repeatEveryMinutes: fieldLong(object, "repeat"),
            stage: fieldString(object, "stage") ?? "cooking",
            observations: fieldString(object, "observations") ?? "",
            probeName: fieldString(object, "probeName") ?? ""
        )
    }

    static func target(_ object: [String: JSONValue]) throws -> PlaybookTarget {
        PlaybookTarget(
            dishIndex: fieldLong(object, "dish").map(Int.init),
            type: try requiredString(object, "type", "Playbook target"),
            value: try requiredDouble(object, "value", "Playbook target"),
            unit: try requiredString(object, "unit", "Playbook target"),
            explanation: fieldString(object, "explanation")
        )
    }

    private static func requiredDouble(_ object: [String: JSONValue], _ key: String, _ context: String) throws -> Double {
        guard let value = fieldDouble(object, key) else {
            throw ServePlanError.invalid("\(context) is missing \(key).")
        }
        return value
    }
}

// MARK: - ServeTimePlanner

enum ServeTimePlanner {
    /// Mirrors Android's ServeTimePlanner.validate: the playbook rules
    /// first, then the plan-level rules with Android's messages.
    static func validate(_ plan: ServePlan) throws {
        try PlaybookCodec.validate(plan.book)
        if TimeZone(identifier: plan.zoneId) == nil {
            throw ServePlanError.invalid("Choose a valid time zone.")
        }
        if plan.book.draft.dishes.isEmpty {
            throw ServePlanError.invalid("Add a dish to the serving plan.")
        }
        if plan.bufferMinutes < 0 || plan.bufferMinutes > 1_440
            || plan.schedules.map(\.dishIndex).sorted() != Array(0 ..< plan.book.draft.dishes.count) {
            throw ServePlanError.invalid("Schedule each dish once and use a valid buffer.")
        }
        for schedule in plan.schedules {
            let stagesValid = [schedule.prepMinutes, schedule.preheatMinutes, schedule.restMinutes, schedule.holdMinutes]
                .allSatisfy { $0 >= 0 && $0 <= 10_080 }
            if schedule.cookMinMinutes <= 0
                || schedule.cookMaxMinutes < schedule.cookMinMinutes || schedule.cookMaxMinutes > 100_800
                || !stagesValid
                || (schedule.pitTemperatureF.map { !$0.isFinite } ?? false) {
                throw ServePlanError.invalid("Check each dish's cooking and preparation durations.")
            }
        }
    }

    static func encode(_ plan: ServePlan) throws -> JSONValue {
        try validate(plan)
        var object: [String: JSONValue] = [
            "version": .number(1),
            "book": try PlaybookCodec.encode(plan.book),
            "serveAt": .number(Double(plan.serveAtUtcMillis)),
            "zoneId": .string(plan.zoneId),
            "buffer": .number(Double(plan.bufferMinutes)),
            "schedules": .array(plan.schedules.map(scheduleJSON)),
        ]
        if let started = plan.startedCookId { object["startedCookId"] = .string(started) }
        return .object(object)
    }

    static func encodeString(_ plan: ServePlan) throws -> String {
        let data = try JSONEncoder().encode(try encode(plan))
        return String(decoding: data, as: UTF8.self)
    }

    static func decode(_ value: JSONValue) throws -> ServePlan {
        guard let object = value.objectValue else {
            throw ServePlanError.invalid("Serve plan payload is not an object.")
        }
        return try decode(object)
    }

    static func decode(_ object: [String: JSONValue]) throws -> ServePlan {
        guard fieldLong(object, "version") == 1 else {
            throw ServePlanError.invalid("Unsupported serve plan version.")
        }
        let plan = ServePlan(
            book: try PlaybookCodec.decode(requiredObject(object, "book", "Serve plan")),
            serveAtUtcMillis: try requiredLong(object, "serveAt", "Serve plan"),
            zoneId: try requiredString(object, "zoneId", "Serve plan"),
            bufferMinutes: fieldLong(object, "buffer") ?? 60,
            startedCookId: fieldString(object, "startedCookId"),
            schedules: try objects(object, "schedules").map(schedule)
        )
        try validate(plan)
        return plan
    }

    static func decodeString(_ text: String) throws -> ServePlan {
        let value = try JSONDecoder().decode(JSONValue.self, from: Data(text.utf8))
        return try decode(value)
    }

    private static func scheduleJSON(_ schedule: DishSchedule) -> JSONValue {
        var object: [String: JSONValue] = [
            "dish": .number(Double(schedule.dishIndex)),
            "min": .number(Double(schedule.cookMinMinutes)),
            "max": .number(Double(schedule.cookMaxMinutes)),
            "prep": .number(Double(schedule.prepMinutes)),
            "preheat": .number(Double(schedule.preheatMinutes)),
            "rest": .number(Double(schedule.restMinutes)),
            "hold": .number(Double(schedule.holdMinutes)),
            "method": .string(schedule.method),
            "count": .number(Double(schedule.evidenceCount)),
            "note": .string(schedule.evidenceNote),
        ]
        if let pit = schedule.pitTemperatureF { object["pitF"] = .number(pit) }
        return .object(object)
    }

    private static func schedule(_ object: [String: JSONValue]) throws -> DishSchedule {
        DishSchedule(
            dishIndex: Int(try requiredLong(object, "dish", "Dish schedule")),
            cookMinMinutes: try requiredLong(object, "min", "Dish schedule"),
            cookMaxMinutes: try requiredLong(object, "max", "Dish schedule"),
            prepMinutes: fieldLong(object, "prep") ?? 30,
            preheatMinutes: fieldLong(object, "preheat") ?? 30,
            restMinutes: fieldLong(object, "rest") ?? 60,
            holdMinutes: fieldLong(object, "hold") ?? 0,
            pitTemperatureF: fieldDouble(object, "pitF"),
            method: fieldString(object, "method") ?? "",
            evidenceCount: Int(fieldLong(object, "count") ?? 0),
            evidenceNote: fieldString(object, "note") ?? "Your duration range"
        )
    }

    /// The wall-clock goal in `zoneId`: java.time's resolution rules — a
    /// time inside a DST gap does not occur; a time that occurs twice
    /// resolves to its first offset unless `laterOffset` asks for the
    /// second occurrence.
    static func localGoal(date: String, time: String, zoneId: String, laterOffset: Bool = false) throws -> Int64 {
        guard let zone = TimeZone(identifier: zoneId) else {
            throw ServePlanError.invalid("Unknown time zone: \(zoneId)")
        }
        let dateParts = date.split(separator: "-").map { Int($0) }
        let timeParts = time.split(separator: ":").map { Int($0) }
        guard dateParts.count == 3, timeParts.count == 2,
              let year = dateParts[0], let month = dateParts[1], let day = dateParts[2],
              let hour = timeParts[0], let minute = timeParts[1],
              (1 ... 12).contains(month), (1 ... 31).contains(day),
              (0 ... 23).contains(hour), (0 ... 59).contains(minute) else {
            throw ServePlanError.invalid("Enter the serving date and time.")
        }
        var utcCalendar = Calendar(identifier: .gregorian)
        utcCalendar.timeZone = TimeZone(secondsFromGMT: 0)!
        var components = DateComponents()
        components.year = year; components.month = month; components.day = day
        components.hour = hour; components.minute = minute; components.second = 0
        guard let wallAsUTC = utcCalendar.date(from: components),
              let roundTrip = utcCalendar.dateComponents([.year, .month, .day], from: wallAsUTC).day,
              roundTrip == day else {
            throw ServePlanError.invalid("Enter the serving date and time.")
        }
        let wallMillis = Int64(wallAsUTC.timeIntervalSince1970 * 1000)
        var offsets = Set<Int>()
        for probe in [-2, -1, 0, 1, 2] {
            let probeDate = Date(timeIntervalSince1970: TimeInterval(wallMillis) / 1000 + TimeInterval(probe * 86_400))
            offsets.insert(zone.secondsFromGMT(for: probeDate))
        }
        var candidates: [Int64] = []
        var zoneCalendar = Calendar(identifier: .gregorian)
        zoneCalendar.timeZone = zone
        for offset in offsets {
            let candidate = wallMillis - Int64(offset) * 1000
            let candidateDate = Date(timeIntervalSince1970: TimeInterval(candidate) / 1000)
            guard zone.secondsFromGMT(for: candidateDate) == offset else { continue }
            let local = zoneCalendar.dateComponents([.year, .month, .day, .hour, .minute], from: candidateDate)
            if local.year == year, local.month == month, local.day == day, local.hour == hour, local.minute == minute {
                candidates.append(candidate)
            }
        }
        candidates.sort()
        guard let first = candidates.first else {
            throw ServePlanError.invalid("This local time is skipped by daylight saving. Choose another time.")
        }
        return laterOffset ? candidates.last! : first
    }

    /// True when the wall time occurs twice in the zone (a fall-back fold).
    static func occursTwice(date: String, time: String, zoneId: String) -> Bool {
        guard let zone = TimeZone(identifier: zoneId) else { return false }
        let dateParts = date.split(separator: "-").map { Int($0) }
        let timeParts = time.split(separator: ":").map { Int($0) }
        guard dateParts.count == 3, timeParts.count == 2,
              let year = dateParts[0], let month = dateParts[1], let day = dateParts[2],
              let hour = timeParts[0], let minute = timeParts[1] else { return false }
        var utcCalendar = Calendar(identifier: .gregorian)
        utcCalendar.timeZone = TimeZone(secondsFromGMT: 0)!
        var components = DateComponents()
        components.year = year; components.month = month; components.day = day
        components.hour = hour; components.minute = minute
        guard let wallAsUTC = utcCalendar.date(from: components) else { return false }
        let wallMillis = Int64(wallAsUTC.timeIntervalSince1970 * 1000)
        var valid = 0
        var offsets = Set<Int>()
        for probe in [-2, -1, 0, 1, 2] {
            offsets.insert(zone.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(wallMillis) / 1000 + TimeInterval(probe * 86_400))))
        }
        var zoneCalendar = Calendar(identifier: .gregorian)
        zoneCalendar.timeZone = zone
        for offset in offsets {
            let candidate = wallMillis - Int64(offset) * 1000
            let candidateDate = Date(timeIntervalSince1970: TimeInterval(candidate) / 1000)
            guard zone.secondsFromGMT(for: candidateDate) == offset else { continue }
            let local = zoneCalendar.dateComponents([.year, .month, .day, .hour, .minute], from: candidateDate)
            if local.year == year, local.month == month, local.day == day, local.hour == hour, local.minute == minute { valid += 1 }
        }
        return valid > 1
    }

    /// The plan's static windows for one dish (Android's ServeTimePlanner
    /// window math, which the PlanTimeline engine mirrors).
    static func foodOn(plan: ServePlan, schedule: DishSchedule) -> Int64 {
        plan.serveAtUtcMillis - (schedule.cookMaxMinutes + schedule.restMinutes + schedule.holdMinutes + plan.bufferMinutes) * 60_000
    }

    /// The planned ready window (Android's PlannedWindow): earliest is
    /// food-on + shortest cook + rest; latest is the serve moment minus
    /// hold and buffer (the rest already happened by then).
    static func readyWindow(plan: ServePlan, schedule: DishSchedule) -> (earliest: Int64, latest: Int64) {
        (foodOn(plan: plan, schedule: schedule) + (schedule.cookMinMinutes + schedule.restMinutes) * 60_000,
         plan.serveAtUtcMillis - (schedule.holdMinutes + plan.bufferMinutes) * 60_000)
    }

    /// Once real events exist, the ready window follows them: a dish
    /// that came off is ready at remove + rest (a point, not a range);
    /// a dish still on follows its actual food-on. Mirrors Android's
    /// updatedReadyWindow; `events` arrive already scoped to the dish
    /// (the store scopes them: the dish's own events plus cook-wide).
    static func updatedReadyWindow(plan: ServePlan, schedule: DishSchedule, events: [PlanEvent]) -> (earliest: Int64, latest: Int64) {
        if let remove = events.filter({ $0.action == "remove" }).map(\.atUtcMillis).max() {
            let ready = remove + schedule.restMinutes * 60_000
            return (ready, ready)
        }
        let foodOnActual = events.filter { $0.action == "food_on" }.map(\.atUtcMillis).max()
        let base = foodOnActual ?? foodOn(plan: plan, schedule: schedule)
        return (base + (schedule.cookMinMinutes + schedule.restMinutes) * 60_000,
                base + (schedule.cookMaxMinutes + schedule.restMinutes) * 60_000)
    }

    /// Cross-unit weight for evidence matching (Android's weightKg:
    /// exact unit spellings, no positivity rule — zero converts to zero).
    static func weightKg(_ value: Double?, _ unit: String?) -> Double? {
        guard let value else { return nil }
        switch unit ?? "lb" {
        case "lb": return value * 0.45359237
        case "oz": return value * 0.028349523125
        case "g": return value / 1000
        case "kg": return value
        default: return nil
        }
    }

    static func fahrenheit(_ value: Double, unit: String?) -> Double? {
        guard value.isFinite, value > -459.67 else { return nil }
        switch unit ?? "°F" {
        case "°F": return value
        case "°C": return InsightsCalculations.fahrenheit(value, unit: "°C")
        default: return nil
        }
    }

    /// The comparable recorded cooks for one draft dish (Android's
    /// ServeTimePlanner.comparable): a strict conjunction, not a score —
    /// the dish's cut must be recorded and equal, food type and smoker
    /// must match, wrap class, starting condition and bone must be
    /// equal, and weight / setpoint must both be recorded on both sides
    /// or both absent (within 25% / 25 °F when recorded). One row per
    /// source cook, first in evidence order.
    static func comparable(dish: PlaybookDishDraft, draft: PlaybookCookDraft, evidence: [DurationEvidence], wrapped: Bool) -> [DurationEvidence] {
        let dishKg = weightKg(Double(dish.weightText), dish.weightUnit)
        let pit = Double(draft.setpointText).flatMap { fahrenheit($0, unit: draft.temperatureUnit) }
        let trimmedCut = dish.cut.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedSmoker = draft.smokerName.trimmingCharacters(in: .whitespacesAndNewlines)
        var seenCooks = Set<String>()
        return evidence.filter { candidate in
            guard !trimmedCut.isEmpty,
                  candidate.cut.lowercased() == trimmedCut.lowercased(),
                  candidate.foodType.lowercased() == dish.foodType.lowercased(),
                  candidate.smokerName?.lowercased() == trimmedSmoker.lowercased(),
                  candidate.wrapped == wrapped,
                  candidate.startingCondition == dish.startingCondition,
                  candidate.boneIn == dish.boneIn else { return false }
            switch (dishKg, candidate.weightKg) {
            case (nil, nil): break
            case let (dishKg?, candidateKg?): guard abs(dishKg - candidateKg) <= dishKg * 0.25 else { return false }
            default: return false
            }
            switch (pit, candidate.setpointF) {
            case (nil, nil): break
            case let (pit?, candidatePit?): guard abs(pit - candidatePit) <= 25 else { return false }
            default: return false
            }
            return seenCooks.insert(candidate.cookId).inserted
        }
    }
}
