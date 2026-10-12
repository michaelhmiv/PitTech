import Foundation

struct MealPresetPayload {
    let items: [JSONValue]
    let setup: [String: JSONValue]
    static func decode(_ raw: String) throws -> Self {
        let value = try JSONDecoder().decode(JSONValue.self, from: Data(raw.utf8))
        if let items = value.arrayValue { return Self(items: items, setup: [:]) }
        guard let o = value.objectValue, o["version"]?.numberValue == 1, let items = o["items"]?.arrayValue,
              let setup = o["setup"]?.objectValue else { throw PitTechArchiveError.invalidArchive }
        return Self(items: items, setup: setup)
    }
}
