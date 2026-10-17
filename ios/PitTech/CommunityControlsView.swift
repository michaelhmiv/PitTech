import SwiftUI

struct NativeCommunityActions: View {
    let detail: NativePublicDetail
    @Environment(NativeCommunityControls.self) private var controls
    @Environment(NativeAccountCoordinator.self) private var account
    @Environment(NativePublicCoordinator.self) private var community
    @State private var reporting = false
    @State private var publisherReport = false
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button("Report this publication") { publisherReport = false; reporting = true }.disabled(controls.busy).accessibilityIdentifier("public-report")
            if let alias = detail.publisherAlias {
                Button("Report publisher") { publisherReport = true; reporting = true }.disabled(controls.busy).accessibilityIdentifier("public-report-publisher")
                Button("Block publisher", role: .destructive) { Task { await controls.block(alias: alias, title: detail.title, blocked: true, account: account) { community.controlsChanged() } } }.disabled(controls.busy || controls.pending != nil).accessibilityIdentifier("public-block")
            }
            Text("Reports and blocks work without signing in. Blocking does not remove copies already saved in your journal.").font(.footnote)
            NavigationLink("Reports, saved requests and blocked publishers") { NativeCommunityControlsView() }
        }
        .sheet(isPresented: $reporting) { NavigationStack { NativeCommunityReportView(detail: detail, publisher: publisherReport) } }
    }
}
struct NativeCommunityControlsView: View {
    @Environment(NativeCommunityControls.self) private var controls
    @Environment(NativeAccountCoordinator.self) private var account
    @Environment(NativePublicCoordinator.self) private var community
    var body: some View {
        Form {
            if let pending = controls.pending {
                Section(pending.rejected ? "Rejected request" : "Saved request") {
                    Text(pending.kind == "report" ? "Your report is saved on this device." : "Your account block change is saved on this device.")
                    Text(pending.userId == nil ? "Retry stays anonymous on this device." : "Retry requires the account that started this request.")
                    if let fields = (try? JSONSerialization.jsonObject(with: pending.body)) as? [String: Any], let reason = fields["reason"] as? String { Text("Reason · " + reason.capitalized); if let details = fields["details"] as? String, !details.isEmpty { Text(details).privacySensitive() } }
                    if pending.rejected { Button("Discard rejected request", role: .destructive) { controls.discardRejected() }.disabled(controls.busy) }
                    else { Button("Retry the saved request") { Task { await controls.retry(account: account) } }.disabled(controls.busy).accessibilityIdentifier("community-retry") }
                }
            }
            Section("Blocked publishers on this device") {
                if controls.blocks.isEmpty { Text("No publishers blocked on this device.") }
                ForEach(controls.blocks, id: \.alias) { block in
                    VStack(alignment: .leading) {
                        Text(block.title.isEmpty ? "Blocked publisher" : "Blocked from: " + block.title)
                        Button("Unblock publisher") { Task { await controls.block(alias: block.alias, title: block.title, blocked: false, account: account) { community.controlsChanged(dismissPublic: false) } } }.disabled(controls.busy || controls.pending != nil)
                    }
                }
            }
            Section("Report receipts") {
                if controls.reports.isEmpty { Text("No report receipts on this device.") }
                ForEach(controls.reports) { receipt in
                    VStack(alignment: .leading) {
                        Text("Report " + String(receipt.id.prefix(8)) + " · " + receipt.status.capitalized)
                        Button("Check report status") { Task { await controls.checkReport(receipt, account: account) } }.disabled(controls.busy)
                    }
                }
                Text("Only your report status is shown. Moderator notes and account identities stay private.").font(.footnote)
            }
            if controls.busy { ProgressView("Saving community controls…") }
            if let message = controls.message { Section { Text(message).accessibilityIdentifier("community-message") } }
        }.navigationTitle("Community controls").navigationBarTitleDisplayMode(.inline)
    }
}
private struct NativeCommunityReportView: View {
    let detail: NativePublicDetail
    let publisher: Bool
    @Environment(NativeCommunityControls.self) private var controls
    @Environment(NativeAccountCoordinator.self) private var account
    @Environment(\.dismiss) private var dismiss
    @State private var reason = "other"
    @State private var details = ""
    @State private var asset = ""
    @FocusState private var typing: Bool
    var body: some View {
        Form {
            Section { Text(detail.title).font(.headline); Text(publisher ? "Report this publisher for review. Anonymous identity is not exposed." : "Report selected public content for review.") }
            Section("What should be reviewed?") {
                if !publisher { Picker("Content", selection: $asset) { Text("Whole publication").tag(""); ForEach(Array(detail.media.enumerated()), id: \.element.id) { index, medium in Text("Photo \(index + 1)").tag(medium.id) } } }
                Picker("Reason", selection: $reason) { ForEach(NativeCommunityControls.reasons, id: \.self) { Text($0.capitalized).tag($0) } }.accessibilityIdentifier("report-reason")
                Text("Details (optional, up to 2,000 characters)").font(.subheadline)
                TextEditor(text: $details).frame(minHeight: 140).focused($typing).privacySensitive().accessibilityIdentifier("report-details")
                Text("\(details.utf16.count)/2000 characters").font(.caption).foregroundStyle(.secondary)
                Text("Do not include passwords or private device credentials. Your report is private to the review team.").font(.footnote)
            }
            Section {
                if controls.pending != nil { Text("Recover or review the saved request in Community controls before sending another report."); NavigationLink("Open community controls") { NativeCommunityControlsView() } }
                Button("Send report") { typing = false; Task { await controls.report(item: detail.id, asset: asset.isEmpty ? nil : asset, publisher: publisher, reason: reason, details: details, account: account); if controls.pending == nil && controls.message?.hasPrefix("Report received") == true { dismiss() } } }.disabled(controls.busy || controls.pending != nil || details.utf16.count > 2000).accessibilityIdentifier("report-submit")
            }
            if let message = controls.message { Section { Text(message) } }
        }.navigationTitle(publisher ? "Report publisher" : "Report publication").navigationBarTitleDisplayMode(.inline).scrollDismissesKeyboard(.interactively)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }; ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Hide keyboard") { typing = false } } }
    }
}
