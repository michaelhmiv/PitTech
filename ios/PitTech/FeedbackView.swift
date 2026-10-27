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
        // TURN-IN (Slice 7, frames `24-feedback` / `25-feedback-review` /
        // `26-feedback-error`): the composer leaves the system Form for
        // the app-wide treatment — canvas, segmented kind switch, boxed
        // fields with tracked labels and mono counters. Draft storage,
        // the review-before-submit step, and the submit flow are the
        // pre-existing behavior, unchanged.
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("Feedback creates a public issue on the PitTech project. Review the text before sending; leave out emails, credentials and private cook details.")
                    .font(PitTechFont.body)
                    .foregroundStyle(PitTechStyle.inkMuted)
                Picker("Feedback type", selection: $kind) { Text("Problem").tag("BUG"); Text("Feature idea").tag("FEATURE") }
                    .pickerStyle(.segmented)
                VStack(alignment: .leading, spacing: 6) {
                    Text("Title")
                        .font(PitTechFont.metaLabel)
                        .tracking(PitTechFont.metaLabelTracking)
                        .foregroundStyle(PitTechStyle.inkMuted)
                    TextField("Title", text: $title)
                        .turnInBoxedField()
                        .onChange(of: title) { _, value in title = String(value.prefix(120)) }
                    Text("\(title.count)/120")
                        .font(PitTechFont.monoMeta)
                        .foregroundStyle(PitTechStyle.inkMuted)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(kind == "BUG" ? "What happened?" : "What would you like PitTech to do?")
                        .font(PitTechFont.metaLabel)
                        .tracking(PitTechFont.metaLabelTracking)
                        .foregroundStyle(PitTechStyle.inkMuted)
                    TextEditor(text: $details)
                        .font(PitTechFont.body)
                        .scrollContentBackground(.hidden)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .frame(minHeight: 140)
                        .background(PitTechStyle.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(PitTechStyle.ruling, lineWidth: 1))
                        .onChange(of: details) { _, value in details = String(value.prefix(1500)) }
                        .accessibilityLabel("Feedback details")
                    Text("\(details.count)/1500")
                        .font(PitTechFont.monoMeta)
                        .foregroundStyle(PitTechStyle.inkMuted)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
                Button("Review feedback") {
                    preview = NativeFeedbackSubmission(kind: kind, title: title.trimmingCharacters(in: .whitespacesAndNewlines), description: details.trimmingCharacters(in: .whitespacesAndNewlines), appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0", osVersion: UIDevice.current.systemVersion, device: UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone")
                    showingPreview = true
                }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity, minHeight: 54)
                .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Text("Drafts stay on this device. Only app version, iOS version and device category accompany your text; no account, device identifier, connection logs or cook history is included.")
                    .font(.caption)
                    .foregroundStyle(PitTechStyle.supporting)
                if let receipt {
                    TurnInStatusStrip(text: receipt, accent: PitTechStyle.sage)
                }
            }
            .padding(20)
        }
        .background(PitTechStyle.canvas.ignoresSafeArea())
        .navigationTitle("Feedback").navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showingPreview) {
                NavigationStack {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 20) {
                            if let preview {
                                // Frame `25-feedback-review`: the exact
                                // public text rides one laminated card.
                                TurnInCard {
                                    VStack(alignment: .leading, spacing: 8) {
                                        TurnInBannerText(preview.kind == "BUG" ? "Problem report" : "Feature idea")
                                        TurnInBannerText(preview.title)
                                        Text(preview.description)
                                            .font(PitTechFont.body)
                                            .foregroundStyle(PitTechStyle.ink)
                                    }
                                }
                                VStack(alignment: .leading, spacing: 8) {
                                    TurnInBannerText("Included app details")
                                    LabeledContent("PitTech", value: preview.appVersion)
                                    LabeledContent("iOS", value: preview.osVersion)
                                    LabeledContent("Device category", value: preview.device)
                                }
                                Text("This text will be public on GitHub.")
                                    .font(.caption)
                                    .foregroundStyle(PitTechStyle.supporting)
                                Button(sending ? "Submitting…" : "Submit feedback") {
                                    sending = true; error = nil
                                    Task { do {
                                        let number = try await NativeFeedbackClient.submit(preview)
                                        receipt = number.map { "Feedback submitted as issue #\($0). Thank you for helping improve PitTech." } ?? "Feedback submitted."
                                        title = ""; details = ""; showingPreview = false
                                    } catch { self.error = error.localizedDescription }; sending = false }
                                }
                                .buttonStyle(.borderedProminent)
                                .frame(maxWidth: .infinity, minHeight: 54)
                                .disabled(sending)
                                if let error {
                                    // Frame `26-feedback-error`: the
                                    // failure rides a flag strip; the
                                    // draft and preview stay put.
                                    TurnInStatusStrip(text: error, accent: PitTechStyle.flag)
                                }
                            }
                        }
                        .padding(20)
                    }
                    .background(PitTechStyle.canvas.ignoresSafeArea())
                    .navigationTitle("Review feedback").navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Back") { showingPreview = false }.disabled(sending) } }
                }.interactiveDismissDisabled(sending)
            }
    }
}
