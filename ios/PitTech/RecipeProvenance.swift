import Foundation

enum ProvenancePhotoRole: Int, Equatable {
    case onPit = 0
    case atWrap = 1
    case sliced = 2
    case other = 3
}

struct ProvenancePhoto: Equatable {
    let photoId: String
    let role: ProvenancePhotoRole
    let caption: String?
    let capturedAtUtcMillis: Int64
}

struct ProvenanceCurvePoint: Equatable {
    let offsetMinutes: Double
    let fahrenheit: Double
}

/// The provenance payload (Flowing spec §4.3): everything a recipe's story
/// needs from the cook that earned it, assembled once from the archive so
/// Spread 06 (recipe story), Spread 03 (plan seeding) and Spread 04
/// (plan-this) all render the same facts. A slot is nil/empty when the
/// source cook didn't record it — absence is data, never filled in.
/// Mirrors Android's RecipeProvenance rule for rule.
struct RecipeProvenance: Equatable {
    let sourceCookId: String
    let sourceDishId: String
    let cookTitle: String
    /// The cook's date with the year — "October 12, 2026" (Michael's rule).
    let cookedDateText: String
    let cookedAtUtcMillis: Int64
    let timeZoneId: String
    let pitName: String?
    let setpointF: Double?
    let wood: String?
    let fuel: String?
    let dishName: String
    let cut: String?
    let weightText: String?
    let totalMinutes: Double?
    let stallMinutes: Double?
    let wrappedAtF: Double?
    let restMinutes: Double?
    let curve: [ProvenanceCurvePoint]
    let photos: [ProvenancePhoto]
    let note: String?
    let rating: Double?
    let cookAgain: Bool?
    let keepChips: [String]
    let changeChips: [String]

    static let maxCurvePoints = 60

    /// The archive tables the builder reads, decoded leniently: results and
    /// lifecycles may be absent from older archives.
    private struct ArchiveIn: Decodable {
        struct CookRow: Decodable {
            let id: String
            let title: String?
            let startedAtUtcMillis: Double
            let endedAtUtcMillis: Double?
            let startedTimeZoneId: String?
            let smokerName: String?
            let initialSetpointValue: Double?
            let initialSetpointUnit: String?
            let woodOrPelletBlend: String?
            let fuelType: String?
            let notes: String?
        }
        struct DishRow: Decodable {
            let id: String
            let cookId: String?
            let name: String?
            let cut: String?
            let weightValue: Double?
            let weightUnit: String?
        }
        struct PhotoRow: Decodable {
            let id: String
            let cookId: String?
            let dishId: String?
            let eventId: String?
            let caption: String?
            let capturedAtUtcMillis: Double?
            let addedAtUtcMillis: Double?
        }
        let cooks: [CookRow]
        let dishes: [DishRow]
        let photos: [PhotoRow]?
        let insights: InsightArchiveInput

        enum CodingKeys: String, CodingKey { case cooks, dishes, photos, events, readings, results, lifecycles }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            cooks = try c.decode([CookRow].self, forKey: .cooks)
            dishes = try c.decode([DishRow].self, forKey: .dishes)
            photos = try c.decodeIfPresent([PhotoRow].self, forKey: .photos)
            let events = try c.decode([InsightEventInput].self, forKey: .events)
            let readings = try c.decode([InsightReadingInput].self, forKey: .readings)
            let results = try c.decodeIfPresent([InsightResultInput].self, forKey: .results) ?? []
            let lifecycles = try c.decodeIfPresent([InsightLifecycleInput].self, forKey: .lifecycles) ?? []
            // Rebuild the insights inputs with the full cook/dish rows mapped
            // onto the insights shapes (which carry fewer fields).
            let insightCooks = cooks.map { InsightCookInput(id: $0.id, startedAtUtcMillis: $0.startedAtUtcMillis, endedAtUtcMillis: $0.endedAtUtcMillis, timingMode: nil) }
            let insightDishes = dishes.map { InsightDishInput(id: $0.id, cookId: $0.cookId ?? "", cut: $0.cut, weightValue: $0.weightValue, weightUnit: $0.weightUnit) }
            insights = InsightArchiveInput(cooks: insightCooks, dishes: insightDishes, events: events, readings: readings, results: results, lifecycles: lifecycles)
        }
    }

    /// Assemble the payload for `sourceDishId` of `sourceCookId` from a
    /// full library archive (JSON data in the export vocabulary). Returns
    /// nil when the recipe has no source cook, or the cook/dish is not in
    /// the archive — the honest-absence case the surfaces render around.
    static func fromArchive(data: Data, sourceCookId: String?, sourceDishId: String?) -> RecipeProvenance? {
        guard let sourceCookId, let sourceDishId,
              let archive = try? JSONDecoder().decode(ArchiveIn.self, from: data),
              let cook = archive.cooks.first(where: { $0.id == sourceCookId }),
              let dish = archive.dishes.first(where: { $0.id == sourceDishId && $0.cookId == sourceCookId })
        else { return nil }

        let insights = archive.insights
        let asOf = cook.endedAtUtcMillis
            ?? insights.readings.filter { $0.cookId == sourceCookId }.map { $0.measuredAtUtcMillis }.max()
            ?? cook.startedAtUtcMillis
        let report = InsightsCalculations.compute(insights, asOf: asOf)
        let metrics = report.cooks.first { $0.cookId == sourceCookId }?.dishes.first { $0.dishId == sourceDishId }

        let series = insights.readings
            .filter { $0.cookId == sourceCookId && ($0.dishId == sourceDishId || $0.dishId == nil) &&
                InsightsCalculations.role($0.measurementType) == "food" && $0.qualityStatus == "valid" && $0.value != nil }
            .map { PaceReading(atUtcMillis: Int64($0.measuredAtUtcMillis), fahrenheit: InsightsCalculations.fahrenheit($0.value!, unit: $0.unit)) }
            .sorted { $0.atUtcMillis < $1.atUtcMillis }

        let foodOn = metrics?.foodOnAt.map { Int64($0) } ?? series.first?.atUtcMillis
        let total: Double? = foodOn.flatMap { on in
            (metrics?.dishDoneAt ?? metrics?.removeAt).map { ($0 - Double(on)) / 60_000.0 }
        }
        let stall = CookPaceEngine.stallSpans(series).map { $0.durationMinutes }.max()
        let wrappedAt: Double? = metrics?.wrapAt.flatMap { wrapAt in
            series.min { abs($0.atUtcMillis - Int64(wrapAt)) < abs($1.atUtcMillis - Int64(wrapAt)) }
                .flatMap { abs($0.atUtcMillis - Int64(wrapAt)) <= 10 * 60_000 ? $0.fahrenheit : nil }
        }
        let curve: [ProvenanceCurvePoint] = foodOn.map { on in downsample(series, foodOn: on) } ?? []

        let eventActions = Dictionary(uniqueKeysWithValues: insights.events.filter { $0.cookId == sourceCookId }.map { ($0.id, $0.action) })
        let photos = (archive.photos ?? [])
            .filter { $0.cookId == sourceCookId && ($0.dishId == sourceDishId || $0.dishId == nil) }
            .map { photo -> ProvenancePhoto in
                let role: ProvenancePhotoRole
                switch photo.eventId.flatMap({ eventActions[$0] }) {
                case "food_on": role = .onPit
                case "wrap": role = .atWrap
                case "remove", "rest_start", "dish_done": role = .sliced
                default: role = .other
                }
                return ProvenancePhoto(
                    photoId: photo.id, role: role, caption: photo.caption,
                    capturedAtUtcMillis: Int64(photo.capturedAtUtcMillis ?? photo.addedAtUtcMillis ?? 0))
            }
            .sorted { ($0.role.rawValue, $0.capturedAtUtcMillis) < ($1.role.rawValue, $1.capturedAtUtcMillis) }

        let results = insights.results.filter { $0.cookId == sourceCookId }
        func scopedTexts(_ type: String) -> [String] {
            let dishRows = results.filter { $0.resultType == type && $0.dishId == sourceDishId }
            let rows = dishRows.isEmpty ? results.filter { $0.resultType == type && $0.dishId == nil } : dishRows
            var seen: [String] = []
            for row in rows {
                if let text = row.textValue?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty, !seen.contains(text) {
                    seen.append(text)
                }
            }
            return seen
        }
        let note = scopedTexts("result_notes").last ?? cook.notes

        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US")
        formatter.dateFormat = "MMMM d, yyyy"
        formatter.timeZone = TimeZone(identifier: cook.startedTimeZoneId ?? "UTC") ?? TimeZone(secondsFromGMT: 0)
        let cookedDate = formatter.string(from: Date(timeIntervalSince1970: cook.startedAtUtcMillis / 1000))

        let weight = dish.weightValue.map { v -> String in
            "\(trimNumber(v)) \(dish.weightUnit ?? "lb")"
        }
        return RecipeProvenance(
            sourceCookId: sourceCookId,
            sourceDishId: sourceDishId,
            cookTitle: cook.title ?? "",
            cookedDateText: cookedDate,
            cookedAtUtcMillis: Int64(cook.startedAtUtcMillis),
            timeZoneId: cook.startedTimeZoneId ?? "UTC",
            pitName: cook.smokerName,
            setpointF: cook.initialSetpointValue.map { InsightsCalculations.fahrenheit($0, unit: cook.initialSetpointUnit ?? "°F") },
            wood: cook.woodOrPelletBlend,
            fuel: cook.fuelType,
            dishName: dish.name ?? "",
            cut: dish.cut,
            weightText: weight,
            totalMinutes: total,
            stallMinutes: stall,
            wrappedAtF: wrappedAt,
            restMinutes: metrics?.restMillis.map { $0 / 60_000.0 },
            curve: curve,
            photos: photos,
            note: note,
            rating: metrics?.rating,
            cookAgain: metrics?.cookAgain,
            keepChips: scopedTexts("keep_doing"),
            changeChips: scopedTexts("change_next_time"))
    }

    private static func downsample(_ series: [PaceReading], foodOn: Int64) -> [ProvenanceCurvePoint] {
        let points = series.map { ProvenanceCurvePoint(offsetMinutes: Double($0.atUtcMillis - foodOn) / 60_000.0, fahrenheit: $0.fahrenheit) }
        guard points.count > maxCurvePoints else { return points }
        let stride = Double(points.count - 1) / Double(maxCurvePoints - 1)
        return (0..<maxCurvePoints).map { points[min(Int(Double($0) * stride), points.count - 1)] }
    }

    private static func trimNumber(_ value: Double) -> String {
        value == value.rounded() ? "\(Int64(value))" : "\(value)"
    }
}
