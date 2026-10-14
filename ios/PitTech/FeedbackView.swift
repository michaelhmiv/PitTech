import SwiftUI
import UIKit

struct NativeFeedbackView: View {
    @AppStorage("feedback-draft-kind") private var kind = "BUG"
    @AppStorage("feedback-draft-title") private var title = ""
    @AppStorage("feedback-draft-description") private var details = ""
    @State private var preview: NativeFeedbackSubmission?
    @State private var showingPreview = false
    @State private var sending = false
    @State private var error: String?
    @State private var receipt: String?
    var body: some View {
        Form {
            Section {
                Text("Feedback creates a public issue on the PitTech project. Review the text before sending; leave out emails, credentials and private cook details.").font(.subheadline)
                Picker("Feedback type", selection: $kind) { Text("Problem").tag("BUG"); Text("Feature idea").tag("FEATURE") }
            }
            Section("Your feedback") {
                TextField("Title", text: $title).onChange(of: title) { _, value in title = String(value.prefix(120)) }
                TextEditor(text: $details).frame(minHeight: 140).onChange(of: details) { _, value in details = String(value.prefix(1500)) }.accessibilityLabel("Feedback details")
                Text("\(details.count)/1500").font(.caption).foregroundStyle(.secondary)
            }
            Section {
                Button("Review feedback") {
                    preview = NativeFeedbackSubmission(kind: kind, title: title.trimmingCharacters(in: .whitespacesAndNewlines), description: details.trimmingCharacters(in: .whitespacesAndNewlines), appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0", osVersion: UIDevice.current.systemVersion, device: UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone")
                    showingPreview = true
                }.disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Text("Drafts stay on this device. Only app version, iOS version and device category accompany your text; no account, device identifier, connection logs or cook history is included.").font(.caption).foregroundStyle(.secondary)
            }
            if let receipt { Section { Text(receipt).foregroundStyle(PitTechStyle.sage) } }
        }.navigationTitle("Feedback").navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showingPreview) {
                NavigationStack {
                    Form {
                        if let preview {
                            Section(preview.kind == "BUG" ? "Problem report" : "Feature idea") { Text(preview.title).font(.headline); Text(preview.description) }
                            Section("Included app details") { LabeledContent("PitTech", value: preview.appVersion); LabeledContent("iOS", value: preview.osVersion); LabeledContent("Device category", value: preview.device) }
                            Section { Text("This text will be public on GitHub."); Button(sending ? "Submitting…" : "Submit feedback") {
                                sending = true; error = nil
                                Task { do {
                                    let number = try await NativeFeedbackClient.submit(preview)
                                    receipt = number.map { "Feedback submitted as issue #\($0). Thank you for helping improve PitTech." } ?? "Feedback submitted."
                                    title = ""; details = ""; showingPreview = false
                                } catch { self.error = error.localizedDescription }; sending = false }
                            }.disabled(sending) }
                            if let error { Section { Text(error).foregroundStyle(.red) } }
                        }
                    }.navigationTitle("Review feedback").navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Back") { showingPreview = false }.disabled(sending) } }
                }.interactiveDismissDisabled(sending)
            }
    }
}
