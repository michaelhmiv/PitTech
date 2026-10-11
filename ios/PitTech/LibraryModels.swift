import Foundation

struct CookRecord: Codable, Identifiable, Equatable {
    var id: String
    var title: String
    var status: String
    var startedAtUtcMillis: Int64
    var startedTimeZoneId: String
    var endedAtUtcMillis: Int64?
    var smokerName: String?
    var initialSetpointValue: Double?
    var initialSetpointUnit: String?
    var notes: String?
    var createdAtUtcMillis: Int64
    var updatedAtUtcMillis: Int64
    var fuelType: String?
    var woodOrPelletBlend: String?
    var outdoorTemperatureValue: Double?
    var outdoorTemperatureUnit: String?
    var weatherNotes: String?
    var windNotes: String?

    var isActive: Bool { status == "active" }
    var startedAt: Date { Date(timeIntervalSince1970: Double(startedAtUtcMillis) / 1_000) }

    static func start(title: String, now: Date = .now) -> CookRecord {
        let millis = Int64(now.timeIntervalSince1970 * 1_000)
        return CookRecord(
            id: UUID().uuidString.lowercased(),
            title: title,
            status: "active",
            startedAtUtcMillis: millis,
            startedTimeZoneId: TimeZone.current.identifier,
            endedAtUtcMillis: nil,
            smokerName: nil,
            initialSetpointValue: nil,
            initialSetpointUnit: nil,
            notes: nil,
            createdAtUtcMillis: millis,
            updatedAtUtcMillis: millis,
            fuelType: nil,
            woodOrPelletBlend: nil,
            outdoorTemperatureValue: nil,
            outdoorTemperatureUnit: nil,
            weatherNotes: nil,
            windNotes: nil
        )
    }
}

indirect enum JSONValue: Codable, Equatable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case object([String: JSONValue])
    case array([JSONValue])
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([String: JSONValue].self) {
            self = .object(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Unsupported JSON value")
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }

    var stringValue: String? { if case .string(let value) = self { value } else { nil } }
    var numberValue: Double? { if case .number(let value) = self { value } else { nil } }
    var objectValue: [String: JSONValue]? { if case .object(let value) = self { value } else { nil } }
}

struct PitTechLibrary: Codable {
    var schemaVersion: Int
    var preferences: [String: JSONValue]?
    var cooks: [CookRecord]
    var dishes: [JSONValue]
    var ingredients: [JSONValue]
    var events: [JSONValue]
    var readings: [JSONValue]
    var targets: [JSONValue]
    var results: [JSONValue]
    var devices: [JSONValue]
    var probes: [JSONValue]
    var photos: [JSONValue]
    var reminders: [JSONValue]
    var recordings: [JSONValue]
    var probeAssignments: [JSONValue]
    var companionRecords: [JSONValue]
    var companionPhotos: [JSONValue]
    var logReadingLinks: [JSONValue]
    var lifecycles: [JSONValue]
    var targetHistory: [JSONValue]

    init() {
        schemaVersion = 6
        preferences = ["temperatureUnit": .string("F"), "weightUnit": .string("lb"), "themeMode": .string("system")]
        cooks = []
        dishes = []
        ingredients = []
        events = []
        readings = []
        targets = []
        results = []
        devices = []
        probes = []
        photos = []
        reminders = []
        recordings = []
        probeAssignments = []
        companionRecords = []
        companionPhotos = []
        logReadingLinks = []
        lifecycles = []
        targetHistory = []
    }

    private enum CodingKeys: String, CodingKey {
        case schemaVersion, preferences, cooks, dishes, ingredients, events, readings, targets, results
        case devices, probes, photos, reminders, recordings, probeAssignments, companionRecords, companionPhotos
        case logReadingLinks, lifecycles, targetHistory
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try container.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 5
        preferences = try container.decodeIfPresent([String: JSONValue].self, forKey: .preferences)
        cooks = try container.decodeIfPresent([CookRecord].self, forKey: .cooks) ?? []
        dishes = try container.decodeIfPresent([JSONValue].self, forKey: .dishes) ?? []
        ingredients = try container.decodeIfPresent([JSONValue].self, forKey: .ingredients) ?? []
        events = try container.decodeIfPresent([JSONValue].self, forKey: .events) ?? []
        readings = try container.decodeIfPresent([JSONValue].self, forKey: .readings) ?? []
        targets = try container.decodeIfPresent([JSONValue].self, forKey: .targets) ?? []
        results = try container.decodeIfPresent([JSONValue].self, forKey: .results) ?? []
        devices = try container.decodeIfPresent([JSONValue].self, forKey: .devices) ?? []
        probes = try container.decodeIfPresent([JSONValue].self, forKey: .probes) ?? []
        photos = try container.decodeIfPresent([JSONValue].self, forKey: .photos) ?? []
        reminders = try container.decodeIfPresent([JSONValue].self, forKey: .reminders) ?? []
        recordings = try container.decodeIfPresent([JSONValue].self, forKey: .recordings) ?? []
        probeAssignments = try container.decodeIfPresent([JSONValue].self, forKey: .probeAssignments) ?? []
        companionRecords = try container.decodeIfPresent([JSONValue].self, forKey: .companionRecords) ?? []
        companionPhotos = try container.decodeIfPresent([JSONValue].self, forKey: .companionPhotos) ?? []
        logReadingLinks = try container.decodeIfPresent([JSONValue].self, forKey: .logReadingLinks) ?? []
        lifecycles = try container.decodeIfPresent([JSONValue].self, forKey: .lifecycles) ?? []
        targetHistory = try container.decodeIfPresent([JSONValue].self, forKey: .targetHistory) ?? []
    }
}

struct TemperatureSample: Identifiable {
    let id: String
    let cookId: String
    let probeName: String
    let measurementType: String
    let value: Double
    let unit: String
    let measuredAt: Date
    let source: String
    let qualityStatus: String

    init?(json: JSONValue) {
        guard let item = json.objectValue,
              let id = item["id"]?.stringValue,
              let cookId = item["cookId"]?.stringValue,
              let probeName = item["probeName"]?.stringValue,
              let value = item["value"]?.numberValue,
              let unit = item["unit"]?.stringValue,
              let measuredAt = item["measuredAtUtcMillis"]?.numberValue else { return nil }
        self.id = id
        self.cookId = cookId
        self.probeName = probeName
        self.measurementType = item["measurementType"]?.stringValue ?? "food"
        self.value = value
        self.unit = unit
        self.measuredAt = Date(timeIntervalSince1970: measuredAt / 1_000)
        self.source = item["source"]?.stringValue ?? "manual"
        self.qualityStatus = item["qualityStatus"]?.stringValue ?? "valid"
    }
}

struct TimelineEntry: Identifiable {
    let id: String
    let title: String
    let details: String
    let occurredAt: Date
    let source: String

    init?(json: JSONValue) {
        guard let item = json.objectValue,
              let id = item["id"]?.stringValue,
              let title = item["title"]?.stringValue,
              let millis = item["occurredAtUtcMillis"]?.numberValue else { return nil }
        self.id = id
        self.title = title
        self.details = item["details"]?.stringValue ?? ""
        self.occurredAt = Date(timeIntervalSince1970: millis / 1_000)
        self.source = item["source"]?.stringValue ?? "manual"
    }
}
