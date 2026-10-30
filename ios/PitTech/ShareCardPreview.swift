import SwiftUI
import UniformTypeIdentifiers
import UIKit

struct ShareCardOptionsView: View {
    @Binding var document: NativeSharingDocument
    private var options: ShareCardOptions { document.cardOptions }
    private var photos: [(id: String, label: String)] {
        document.entries.filter(\.included).flatMap { entry in entry.photoIds.enumerated().map { (id:$0.element,label:"\((try? entry.snapshot.name) ?? "Recipe") · photo \($0.offset+1)") } }
    }
    private func binding<T>(_ key: WritableKeyPath<ShareCardOptions,T>) -> Binding<T> {
        Binding(get:{ document.cardOptions[keyPath:key] },set:{ value in var copy = document.cardOptions;copy[keyPath:key] = value;document.card = copy })
    }
    var body: some View {
        VStack(alignment:.leading,spacing:12) {
            TurnInBannerText("Share-card appearance")
            Picker("Hero",selection:Binding(get:{ options.hero },set:{ value in
                var copy = options;copy.hero = value;document.card = copy
                if value == "photo" && !photos.contains(where:{ $0.id == document.heroPhotoId }) { document.heroPhotoId = photos.first?.id }
            })) { Text("Recorded chart").tag("chart");Text("No hero").tag("none");if !photos.isEmpty { Text("Photo").tag("photo") } }
            if options.hero == "photo" {
                Picker("Cover photo",selection:Binding(get:{ document.heroPhotoId ?? "" },set:{ document.heroPhotoId = $0 })) {
                    Text("Choose photo").tag("");ForEach(photos,id:\.id) { Text($0.label).tag($0.id) }
                }
                Text("Horizontal crop");Slider(value:binding(\.cropX),in:0...1).accessibilityLabel("Horizontal crop")
                Text("Vertical crop");Slider(value:binding(\.cropY),in:0...1).accessibilityLabel("Vertical crop")
                Text("Zoom");Slider(value:binding(\.zoom),in:1...3).accessibilityLabel("Zoom")
            }
            Picker("Temperature unit",selection:binding(\.unit)) { Text("Automatic").tag("auto");Text("°C").tag("°C");Text("°F").tag("°F") }
            TextField("Card note (optional)",text:Binding(get:{ options.note },set:{ value in var copy = options;copy.note = shareCardLimited(value,2000);document.card = copy }),axis:.vertical).lineLimit(2...5).turnInBoxedField().accessibilityIdentifier("card-note")
            Toggle("Include recorded verdict",isOn:binding(\.includeVerdict)).disabled(!document.entries.contains { $0.included && $0.includeStory })
            Text("Only selected photos and enabled recorded stories appear. Full text and original measurements remain in the Cook Pack.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
        }
    }
}
struct ShareCardPNGDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.png] }
    var data: Data
    init(data:Data) { self.data = data }
    init(configuration:ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration:WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents:data) }
}
struct NativeShareCardPreview: View {
    @Environment(CookStore.self) private var store
    @Environment(ProSubscriptionCoordinator.self) private var pro
    @Environment(ProCustomizationStore.self) private var customization
    @Environment(\.dismiss) private var dismiss
    let id: String
    var relay: ShareCardRelay? = nil
    var publication: ShareCardPublication? = nil
    var onPack: () -> Void
    @State private var bytes: Data?
    @State private var error: String?
    @State private var working = true
    @State private var retry = 0
    @State private var sharing = false
    @State private var saving = false
    private var entitled: Bool { pro.entitlement == .entitled }
    private var theme: ProCustomization.CardTheme {
        ProCustomization.effectiveTheme(storedId: customization.themeId, entitled: entitled)
    }
    private var palette: ProCustomization.ChartPalette {
        ProCustomization.effectivePalette(storedId: customization.paletteId, entitled: entitled)
    }
    var body: some View {
        ScrollView {
            VStack(alignment:.leading,spacing:16) {
                if working { ProgressView("Preparing card…") }
                if let bytes, let image = UIImage(data:bytes) { Image(uiImage:image).resizable().scaledToFit().accessibilityLabel("Exact exported card preview").accessibilityIdentifier("share-card-preview") }
                Text(theme.isPro ? "1080 × 1350 PNG · \(theme.name) theme" : "1080 × 1350 PNG · default theme").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                if let publication { Text("Posted-recipe card · the QR code opens \(publication.url)").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted).accessibilityIdentifier("share-card-publication-note") }
                ProEntryRow(entry: .themes)
                if let error { TurnInStatusStrip(text: error, accent: PitTechStyle.flag); Button("Retry image") { retry += 1 } }
                Button("Share image") { sharing = true }.buttonStyle(.borderedProminent).frame(minHeight:44).disabled(bytes == nil || working).accessibilityIdentifier("card-share")
                Button("Save PNG") { saving = true }.buttonStyle(.bordered).frame(minHeight:44).disabled(bytes == nil || working).accessibilityIdentifier("card-save")
                Button("Share Cook Pack file",action:onPack).frame(minHeight:44)
                Button("Back to edit") { dismiss() }.frame(minHeight:44).accessibilityIdentifier("card-close")
            }.padding(20)
        }.background(PitTechStyle.canvas.ignoresSafeArea()).navigationTitle("Share card").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement:.cancellationAction) { Button("Done") { dismiss() } } }
            .task(id:retry) {
                working = true;error = nil
                do { let prepared = try await store.prepareShareCard(id, relay: relay, publication: publication, themeOverride: theme.id, paletteOverride: palette.id);try Task.checkCancellation();bytes = try NativeShareCardRenderer.render(prepared) }
                catch is CancellationError { }
                catch { self.error = error.localizedDescription }
                working = false
            }
            .fileExporter(isPresented:$saving,document:ShareCardPNGDocument(data:bytes ?? Data()),contentType:.png,defaultFilename:"PitTech-card") { result in
                if case .failure(let failure) = result, (failure as? CocoaError)?.code != .userCancelled { error = failure.localizedDescription }
            }
            .sheet(isPresented:$sharing) { if let bytes { ShareCardActivity(bytes:bytes) { sharing = false } } }
    }
}
private struct ShareCardActivity: UIViewControllerRepresentable {
    let bytes: Data
    let finished: @MainActor @Sendable () -> Void
    func makeUIViewController(context:Context) -> UIActivityViewController {
        let items: [Any] = UIImage(data:bytes).map { [$0] } ?? []
        let controller = UIActivityViewController(activityItems:items,applicationActivities:nil)
        controller.completionWithItemsHandler = { _,_,_,_ in Task { @MainActor in finished() } }
        return controller
    }
    func updateUIViewController(_ uiViewController:UIActivityViewController,context:Context) { }
}
