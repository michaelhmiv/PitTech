import SwiftUI
import UIKit

struct NativeFeedbackView: View {
    /// PL-20 (#184): set when the view is opened from the crash-recovery
    /// surface's "Attach to a problem report" — the report starts out
    /// included, and the preview below shows exactly what will be sent.
    var includeDiagnosticOnOpen = false
    @AppStorage("feedback-draft-kind") private var kind = "BUG"
    @AppStorage("feedback-draft-title") private var title = ""
    @AppStorage("feedback-draft-description") private var details = ""
    @AppStorage("feedback-draft-include-diagnostic") private var includeDraft = true
    @State private var preview: NativeFeedbackSubmission?
    @State private var showingPreview = false
    @State private var sending = false
    @State private var error: String?
    @State private var receipt: String?
    @State private var savedReport: DiagnosticReport?
    @State private var includeReport = false
    @State private var showReportPreview = false
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
                if kind == "BUG", let report = savedReport {
                    // PL-20 (#184): the opt-in, exactly Android's — off
                    // (or a feature idea) means the report never leaves
                    // the device; the preview is the exact payload.
                    VStack(alignment: .leading, spacing: 8) {
                        Toggle(isOn: $includeReport) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Include the saved crash report")
                                    .font(PitTechFont.body)
                                Text("Reference \(report.referenceCode); includes the exception summary and stack trace.")
                                    .font(.caption)
                                    .foregroundStyle(PitTechStyle.supporting)
                            }
                        }
                        .accessibilityIdentifier("feedback-include-diagnostic")
                        .onChange(of: includeReport) { _, value in includeDraft = value }
                        if includeReport {
                            Button(showReportPreview ? "Hide crash report preview" : "Preview crash report") { showReportPreview.toggle() }
                                .accessibilityIdentifier("feedback-crash-preview-toggle")
                            if showReportPreview {
                                TurnInCard {
                                    Text(report.plainText)
                                        .font(PitTechFont.monoMeta)
                                        .foregroundStyle(PitTechStyle.ink)
                                        .textSelection(.enabled)
                                }
                                .accessibilityIdentifier("feedback-crash-preview")
                            }
                        }
                    }
                }
                Button("Review feedback") {
                    preview = NativeFeedbackSubmission(kind: kind, title: title.trimmingCharacters(in: .whitespacesAndNewlines), description: details.trimmingCharacters(in: .whitespacesAndNewlines), appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0", osVersion: UIDevice.current.systemVersion, device: UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone", diagnosticReport: kind == "BUG" && includeReport ? savedReport : nil)
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
        .onAppear {
            savedReport = CrashDiagnostics.shared.savedReport()
            includeReport = kind == "BUG" && savedReport != nil && (includeDiagnosticOnOpen || includeDraft)
        }
        .onChange(of: kind) { _, value in
            // Android: switching to Problem re-arms the attach default.
            if value == "BUG" { includeReport = savedReport != nil }
        }
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
                                if let report = preview.diagnosticReport {
                                    // PL-20 (#184): the review shows the
                                    // exact diagnostic payload, not just
                                    // a note that one is attached.
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text("The saved crash report (reference \(report.referenceCode)) is included.")
                                            .font(PitTechFont.body)
                                        TurnInCard {
                                            Text(report.plainText)
                                                .font(PitTechFont.monoMeta)
                                                .foregroundStyle(PitTechStyle.ink)
                                                .textSelection(.enabled)
                                        }
                                        .accessibilityIdentifier("feedback-review-diagnostic")
                                    }
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
