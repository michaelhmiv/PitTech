import SwiftUI

/// Flowing Slice 5, element 4.3 — tweak-lite: the fork opens three
/// decisions (rub, wrap, target), not the full version editor. The full
/// editor remains one level down, on the saved recipe. Pre-fills are the
/// recipe's own facts — the name gains "— my version", the target is the
/// source cook's last recorded food reading when the story carries one —
/// and saving is the decision that commits them. Mirrors Android's
/// PublicationForkSheet, copy and validation included.
struct PublicationForkSheet: View {
    @Environment(\.dismiss) private var dismiss
    let detail: NativePublicDetail
    let busy: Bool
    let error: String?
    var onSave: ([PublicationFork.Tweak]) -> Void

    @State private var names: [String]
    @State private var rubs: [String]
    @State private var wraps: [PublicationFork.WrapChoice]
    @State private var targetTexts: [String]
    private let prefills: [PublicationFork.Tweak]

    init(detail: NativePublicDetail, busy: Bool, error: String?, onSave: @escaping ([PublicationFork.Tweak]) -> Void) {
        self.detail = detail
        self.busy = busy
        self.error = error
        self.onSave = onSave
        let prefills = detail.recipes.indices.map { PublicationFork.prefill(detail: detail, recipeIndex: $0) }
        self.prefills = prefills
        _names = State(initialValue: prefills.map(\.name))
        _rubs = State(initialValue: prefills.map(\.rub))
        _wraps = State(initialValue: prefills.map(\.wrap))
        _targetTexts = State(initialValue: prefills.map { $0.targetF.map(PublicationFork.jsNumber) ?? "" })
    }

    private var targetsValid: Bool {
        targetTexts.allSatisfy { text in
            text.isEmpty || Double(text).map { (32 ... 600).contains($0) } == true
        }
    }

    private var namesValid: Bool {
        names.allSatisfy {
            !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && $0.trimmingCharacters(in: .whitespacesAndNewlines).count <= 200
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    VStack(alignment: .leading, spacing: 4) {
                        TurnInMetaLabel("Fork · 3 decisions, that's it")
                        TurnInBannerText("Make it yours", font: PitTechFont.bannerTitle)
                        Text("Change what you'd change at your own pit. Everything else carries over from \(detail.credit.displayName)'s cook.")
                            .font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                    }
                    ForEach(detail.recipes.indices, id: \.self) { index in
                        recipeSection(index)
                    }
                    if !targetsValid {
                        Text("Use a target between 32 and 600 °F, or leave it blank.")
                            .font(PitTechFont.body).foregroundStyle(PitTechStyle.flag)
                    }
                    if !namesValid {
                        Text("Give your version a name (up to 200 characters).")
                            .font(PitTechFont.body).foregroundStyle(PitTechStyle.flag)
                    }
                    if let error {
                        TurnInStatusStrip(text: error, accent: PitTechStyle.flag)
                            .accessibilityIdentifier("fork-error")
                    }
                    Text("The original stays untouched in Discover.")
                        .font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                    Button("Save my version") { save() }
                        .buttonStyle(.borderedProminent)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .disabled(busy || !targetsValid || !namesValid)
                        .accessibilityIdentifier("fork-save")
                    Button("Cancel") { dismiss() }
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .disabled(busy)
                    if busy {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Saving your version…")
                        }
                    }
                }
                .padding(20)
            }
            .background(PitTechStyle.canvas.ignoresSafeArea())
            .navigationTitle("Fork")
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("fork-sheet")
        }
    }

    private func recipeSection(_ index: Int) -> some View {
        let recipe = detail.recipes[index].objectValue ?? [:]
        return VStack(alignment: .leading, spacing: 12) {
            if detail.recipes.count > 1 {
                TurnInBannerText(recipe["title"]?.stringValue ?? "Recipe", font: PitTechFont.bannerCard)
            }
            VStack(alignment: .leading, spacing: 4) {
                TurnInMetaLabel("Your name for it")
                TextField("Your name for it", text: binding($names, index))
                    .turnInBoxedField()
                    .disabled(busy)
                    .accessibilityIdentifier("fork-name-\(index)")
            }
            VStack(alignment: .leading, spacing: 4) {
                TurnInMetaLabel("Rub")
                TextField("Rub", text: binding($rubs, index), prompt: Text("As written"))
                    .turnInBoxedField()
                    .disabled(busy)
                    .accessibilityIdentifier("fork-rub-\(index)")
            }
            VStack(alignment: .leading, spacing: 6) {
                TurnInMetaLabel("Wrap")
                HStack(spacing: 8) {
                    ForEach(wrapOptions, id: \.choice) { option in
                        Button(option.label) { wraps[index] = option.choice }
                            .font(PitTechFont.bodySmall)
                            .padding(.horizontal, 10).padding(.vertical, 6)
                            .background(
                                wraps[index] == option.choice ? PitTechStyle.primaryFill : PitTechStyle.surfaceRaised,
                                in: Capsule()
                            )
                            .foregroundStyle(wraps[index] == option.choice ? PitTechStyle.onAccent : PitTechStyle.ink)
                            .buttonStyle(.plain)
                            .disabled(busy)
                            .accessibilityIdentifier("fork-wrap-\(index)-\(option.tag)")
                    }
                }
                if wraps[index] == .asWritten, let hint = wrapHint(recipe) {
                    Text("As written — \(hint.label.lowercased()) in the source cook.")
                        .font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                TurnInMetaLabel("Target temp (°F)")
                TextField("Target temp (°F)", text: binding($targetTexts, index), prompt: Text("As written — no target"))
                    .turnInBoxedField()
                    .keyboardType(.decimalPad)
                    .disabled(busy)
                    .accessibilityIdentifier("fork-target-\(index)")
                if prefills[index].targetFromStory, !targetTexts[index].isEmpty {
                    Text("Pre-filled from the source cook's last recorded food temperature — change it or clear it.")
                        .font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                }
            }
        }
    }

    private var wrapOptions: [(label: String, choice: PublicationFork.WrapChoice, tag: String)] {
        [
            ("As written", .asWritten, "as_written"),
            (WrapMaterial.none.label, .none, "no_wrap"),
            (WrapMaterial.foil.label, .foil, "foil"),
            (WrapMaterial.butcherPaper.label, .butcherPaper, "butcher_paper"),
        ]
    }

    /// The wrap the source cook's words imply — shown as a hint under
    /// "As written", never silently applied (the cook still chooses).
    private func wrapHint(_ recipe: [String: JSONValue]) -> WrapMaterial? {
        var texts: [String] = []
        for instruction in recipe["instructions"]?.arrayValue ?? [] {
            let row = instruction.objectValue ?? [:]
            texts.append(row["title"]?.stringValue ?? "")
            texts.append(row["details"]?.stringValue ?? "")
            texts.append(row["method"]?.stringValue ?? "")
        }
        for milestone in recipe["story"]?.objectValue?["milestones"]?.arrayValue ?? [] {
            texts.append(milestone.objectValue?["text"]?.stringValue ?? "")
        }
        return CookInputVocabulary.wrapMaterialHint(texts: texts)
    }

    private func binding(_ values: Binding<[String]>, _ index: Int) -> Binding<String> {
        Binding(
            get: { values.wrappedValue.indices.contains(index) ? values.wrappedValue[index] : "" },
            set: { if values.wrappedValue.indices.contains(index) { values.wrappedValue[index] = $0 } }
        )
    }

    private func save() {
        onSave(detail.recipes.indices.map { index in
            PublicationFork.Tweak(
                name: names[index],
                rub: rubs[index],
                wrap: wraps[index],
                targetF: Double(targetTexts[index]),
                targetFromStory: prefills[index].targetFromStory && Double(targetTexts[index]) == prefills[index].targetF
            )
        })
    }
}
