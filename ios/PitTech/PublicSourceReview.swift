import SwiftUI

struct NativeSourceComparisonSection: Equatable {
    let title: String
    let saved: [String]
    let shared: [String]
    var changed: Bool { saved != shared }
}
enum NativeSourceComparison {
    static func sections(_ snapshot: RecipeSnapshot, recipe: JSONValue) throws -> [NativeSourceComparisonSection] {
        try snapshot.validate()
        guard let row = recipe.objectValue, let ingredients = row["ingredients"]?.arrayValue, ingredients.count <= 100,
              let instructions = row["instructions"]?.arrayValue, instructions.count <= 100 else { throw NativePublicError.invalid }
        func text(_ value: JSONValue?, _ limit: Int) throws -> String {
            guard let value, value != .null else { return "" }
            guard let string = value.stringValue, string.utf16.count <= limit else { throw NativePublicError.invalid }; return string
        }
        let setup = snapshot.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue ?? [:]
        let savedIngredients = try (setup["ingredients"]?.arrayValue ?? []).map { value -> String in
            let r = value.objectValue ?? [:]
            return try [text(r["name"], 160), text(r["brand"], 160), [text(r["amount"], 2000), text(r["unit"], 2000)].filter { !$0.isEmpty }.joined(separator: " ")].filter { !$0.isEmpty }.joined(separator: " · ")
        }
        let sharedIngredients = try ingredients.map { value -> String in
            try [text(value.objectValue?["name"], 160), text(value.objectValue?["amount"], 2000)].filter { !$0.isEmpty }.joined(separator: " · ")
        }
        func step(_ value: JSONValue, shared: Bool) throws -> String {
            let r = value.objectValue ?? [:]
            var fields = try [text(r["title"], 160), text(r["details"], 2000), text(r["method"], 160), text(r["action"], 80)].filter { !$0.isEmpty }
            if let offset = r[shared ? "suggestedOffsetMillis" : "offsetMillis"]?.numberValue {
                guard offset.isFinite, (-604_800_000...2_592_000_000).contains(offset.rounded()) else { throw NativePublicError.invalid }
                fields.append("Suggested offset · " + RecipeReferenceTimeline.relative(offset.rounded()))
            }
            return fields.joined(separator: " · ")
        }
        return [NativeSourceComparisonSection(title: "Ingredients", saved: savedIngredients, shared: sharedIngredients), NativeSourceComparisonSection(title: "Instructions in order", saved: try snapshot.instructions.map { try step($0, shared: false) }, shared: try instructions.map { try step($0, shared: true) })]
    }
}
struct NativePublicSourceReview: View {
    @Environment(NativePublicCoordinator.self) private var community
    let snapshot: RecipeSnapshot
    @State private var detail: NativePublicDetail?
    @State private var offline = false
    @State private var selected: Int?
    @State private var busy = false
    @State private var message: String?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // TURN-IN (Slice 3, Area 03 `dark-09-source-review`): the
                // revisions read as mono lines and every compared section
                // is a saved/shared card pair.
                Text("Compare your saved copy with the shared source. Your edits may also appear as differences. Meals already using your saved version keep it.").font(PitTechFont.body)
                if let source = snapshot.publicSource {
                    Text("Saved source reference · " + String(source.revisionId.prefix(8))).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                    Button("Check shared source") { Task {
                        guard !busy else { return }; busy = true; detail = nil; selected = nil; message = nil; defer { busy = false }
                        do { let result = try await community.detail(source.itemId); detail = result.0; offline = result.1 } catch { message = error.localizedDescription }
                    } }.disabled(busy).accessibilityIdentifier("source-review-check")
                    if let detail {
                        Text(offline ? "Saved browsing preview only. Connect to check the current source." : detail.revisionId == source.revisionId ? "The shared reference is unchanged." : "The shared recipe has a different reference.").font(PitTechFont.bodyEmphasis)
                        Text("Shared reference · " + String(detail.revisionId.prefix(8))).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                        Text("Choose the shared dish to compare. A collection may contain several dishes.").font(PitTechFont.body)
                        ForEach(detail.recipes.indices, id: \.self) { index in Button(detail.recipes[index].objectValue?["title"]?.stringValue ?? "Recipe") { selected = index }.buttonStyle(.bordered).accessibilityIdentifier("source-review-member-\(index)") }
                        if let selected, detail.recipes.indices.contains(selected) {
                            if let sections = try? NativeSourceComparison.sections(snapshot, recipe: detail.recipes[selected]) {
                                ForEach(sections, id: \.title) { section in
                                    Text(section.title + (section.changed ? " · differences" : " · unchanged")).font(PitTechFont.bodyEmphasis).foregroundStyle(section.changed ? PitTechStyle.attentionText : PitTechStyle.ink)
                                    comparison("Your saved copy", section.saved); comparison(offline ? "Saved shared preview" : "Shared recipe", section.shared)
                                }
                            } else { Text("This recipe could not be compared safely. Your saved copy is unchanged.").font(PitTechFont.body) }
                        }
                    }
                }
                Text("This review does not replace recipes, reference measurements, or an active meal. To keep a different shared version, open its source and use the separate Save preview.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                if busy { ProgressView("Checking source…") }; if let message { TurnInStatusStrip(text: message, accent: PitTechStyle.flag) }
            }.padding(20)
        }.accessibilityIdentifier("source-review-content").navigationTitle("Review shared changes").navigationBarTitleDisplayMode(.inline)
    }
    @ViewBuilder private func comparison(_ title: String, _ rows: [String]) -> some View {
        TurnInCard {
            VStack(alignment: .leading, spacing: 8) {
                TurnInMetaLabel(title)
                if rows.isEmpty { Text("None listed").font(PitTechFont.body) }
                ForEach(rows.indices, id: \.self) { index in Text("\(index + 1). " + rows[index]).font(PitTechFont.body) }
            }
        }
    }
}
