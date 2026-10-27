import SwiftUI
import UIKit
import CoreImage.CIFilterBuiltins

struct NativeRelayProgress: View {
    @Environment(NativeRelayCoordinator.self) private var relay
    var body: some View {
        if relay.busy {
            VStack(alignment: .leading, spacing: 10) {
                Text(relay.operationLabel).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                ProgressView(value: relay.progress).accessibilityLabel(relay.operationLabel)
                Button("Cancel request") { relay.cancel() }.frame(minHeight: 44)
            }
        }
        if let message = relay.message { Text(message).font(PitTechFont.body).accessibilityIdentifier("relay-message") }
    }
}
struct NativeRelayImportView: View {
    @Environment(NativeRelayCoordinator.self) private var relay
    @FocusState private var codeFocused: Bool
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                TurnInBannerText("Bring a shared cook into your cookbook", font: PitTechFont.bannerTitle)
                Text("Enter the code from a card or paste a PitTech share link. You can preview the pack and choose individual dishes before saving.").foregroundStyle(PitTechStyle.inkMuted)
                TextField("Cook Pack code or link", text: Binding(get: { relay.input }, set: { relay.input = String($0.prefix(256)) }))
                    .turnInBoxedField().textInputAutocapitalization(.never).autocorrectionDisabled().focused($codeFocused)
                    .submitLabel(.go).onSubmit { codeFocused = false; relay.download() }.accessibilityIdentifier("relay-code-input")
                Button("Download & preview") { codeFocused = false; relay.download() }.buttonStyle(.borderedProminent).frame(minHeight: 44).disabled(relay.busy || relay.input.isEmpty).accessibilityIdentifier("relay-download")
                Text("No account required. A downloaded pack is not added to your recipes or history until you choose to save it.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                NativeRelayProgress()
            }.padding(20)
        }.accessibilityIdentifier("relay-import-content").scrollDismissesKeyboard(.interactively)
            .background(PitTechStyle.canvas.ignoresSafeArea()).navigationTitle("Import by code").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { relay.cancel(); relay.showingImport = false } } }
            .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Hide keyboard") { codeFocused = false } } }
            .onDisappear { relay.cancel() }
    }
}
struct NativeRelayShareView: View {
    @Environment(CookStore.self) private var store
    @Environment(NativeRelayCoordinator.self) private var relay
    @Environment(\.dismiss) private var dismiss
    let id: String
    let includeHistory: Bool
    @State private var previewCard = false
    private var document: NativeSharingDocument? { try? store.sharingDocument(id) }
    private var revision: Double { store.sharingRecord(id)?["updated"]?.numberValue ?? 0 }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if let document {
                    TurnInBannerText(document.title, font: PitTechFont.bannerTitle)
                    Text("Review the selected pack").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                    ForEach(document.entries.filter(\.included)) { entry in
                        VStack(alignment: .leading, spacing: 5) {
                            TurnInBannerText((try? entry.snapshot.name) ?? "Selected dish")
                            Text("\(entry.photoIds.count) selected photos · " + (entry.includeStory ? "Recorded story included" : "Instructions only")).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                        }
                    }
                    Text(includeHistory ? "Includes this recorded meal; recipients can choose whether to add it to history." : "Recipients can save the selected recipes without importing your meal history.").font(PitTechFont.body)
                    Text("Anyone with the code can download this selected pack for 180 days. It is not listed publicly. Reading the code does not renew it. Renewal and revocation are controlled from this device.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                    Button("Upload selected pack") { relay.upload(id, includeHistory: includeHistory, store: store) }.buttonStyle(.borderedProminent).frame(minHeight: 44).disabled(relay.busy).accessibilityIdentifier("relay-upload")
                    if let card = relay.card(id, revision: revision) {
                        Button("Preview card with this code") { previewCard = true }.buttonStyle(.bordered).frame(minHeight: 44)
                        Text("The code refers to this frozen upload. Later recipe edits do not change its contents.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                        ShareLink(item: URL(string: card.url)!) { Label("Share link", systemImage: "square.and.arrow.up") }.frame(minHeight: 44)
                    }
                }
                NativeRelayProgress()
                ForEach(relay.shares.filter { $0.document == id }) { NativeOwnedRelayRow(value: $0) }
                Button("Back to share a file or image") { dismiss() }.frame(minHeight: 44)
            }.padding(20)
        }.background(PitTechStyle.canvas.ignoresSafeArea()).navigationTitle("Share by code").navigationBarTitleDisplayMode(.inline)
            .onAppear { relay.refresh() }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .sheet(isPresented: $previewCard) {
                NavigationStack { NativeShareCardPreview(id: id, relay: relay.card(id, revision: revision)) { previewCard = false; dismiss() } }.modifier(ScreenshotTextSize())
            }
    }
}
struct NativeSharedCodesView: View {
    @Environment(NativeRelayCoordinator.self) private var relay
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                TurnInBannerText("Codes created on this device", font: PitTechFont.bannerTitle)
                Text("Your management capability stays on this device. Revocation stops new downloads; recipients keep copies they already saved.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                NativeRelayProgress()
                if relay.shares.isEmpty { TurnInDashedCard { Text(EmptyStateCopy.sharedCodesEmpty).font(PitTechFont.body) } }
                ForEach(relay.shares) { NativeOwnedRelayRow(value: $0) }
            }.padding(20)
        }.background(PitTechStyle.canvas.ignoresSafeArea()).navigationTitle("My shared codes").navigationBarTitleDisplayMode(.inline).onAppear { relay.refresh() }
    }
}
private struct NativeOwnedRelayRow: View {
    @Environment(NativeRelayCoordinator.self) private var relay
    let value: NativeRelayOwnedShare
    @State private var confirmingRevoke = false
    @State private var showingQR = false
    var body: some View {
        TurnInCard {
        VStack(alignment: .leading, spacing: 12) {
            TurnInBannerText(value.title)
            if let receipt = value.receipt {
                Text(receipt.code).font(PitTechFont.monoReadout).textSelection(.enabled).accessibilityIdentifier("relay-owned-code")
                Text(value.revoked ? "Revoked" : receipt.expired ? "Expired" : "Available until " + receipt.expiry.formatted(date: .abbreviated, time: .omitted)).font(PitTechFont.monoMeta).foregroundStyle(value.revoked || receipt.expired ? PitTechStyle.flag : PitTechStyle.inkMuted)
                if !value.revoked && !receipt.expired {
                    Button(showingQR ? "Hide QR code" : "Show QR code") { showingQR.toggle() }.frame(minHeight: 44)
                    if showingQR, let image = Self.qr("https://pittech.app/s/" + receipt.code) { Image(uiImage: image).interpolation(.none).resizable().scaledToFit().frame(maxWidth: 240).padding(32).background(Color.white).accessibilityLabel("QR code for this Cook Pack") }
                    ShareLink(item: URL(string: "https://pittech.app/s/" + receipt.code)!) { Label("Share link", systemImage: "square.and.arrow.up") }.frame(minHeight: 44)
                    Button("Copy code") { UIPasteboard.general.string = receipt.code }.frame(minHeight: 44)
                    Button("Renew for 180 days") { relay.renew(value) }.frame(minHeight: 44).disabled(relay.busy)
                    Button("Revoke code", role: .destructive) { confirmingRevoke = true }.frame(minHeight: 44).disabled(relay.busy)
                }
            } else {
                Text("Upload not confirmed. Retry the saved bytes to recover the same code if the server already accepted them.").font(PitTechFont.body)
                Button("Retry saved upload") { relay.retry(value) }.frame(minHeight: 44).disabled(relay.busy)
            }
        }
        }
            .confirmationDialog("Stop new downloads for this code?", isPresented: $confirmingRevoke) {
                Button("Revoke code", role: .destructive) { relay.revoke(value) }
                Button("Cancel", role: .cancel) { }
            } message: { Text("Copies already saved by recipients remain available to them.") }
    }
    private static func qr(_ value: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(value.utf8); filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)), let image = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: image)
    }
}
