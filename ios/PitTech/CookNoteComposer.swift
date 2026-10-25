import SwiftUI
import PhotosUI
import UIKit

struct CookNoteComposer: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State var draft: CookNoteDraft
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var camera = false
    @State private var error: String?
    @State private var busy = false
    @State private var closed = false
    @State private var durationText = ""
    var body: some View {
        NavigationStack {
            Form {
                Section("Note") {
                    TextField("Title (optional)", text: $draft.title)
                    TextField("Notes or details", text: $draft.description, axis: .vertical).lineLimit(2...8)
                    DatePicker("Occurred at", selection: dateBinding($draft.occurredAt), in: ...Date.now)
                    Picker("Action", selection: Binding(get: { draft.metadata.action ?? "" }, set: { draft.metadata.action = $0.isEmpty ? nil : $0 })) {
                        Text("None").tag("")
                        ForEach(CookNoteMetadata.actions, id: \.self) { action in Text(CookStore.actionLabel(action)).tag(action) }
                    }
                }
                Section {
                    DisclosureGroup("Method, duration & ingredients") {
                        Picker("Cooking method", selection: Binding(get: { draft.metadata.method ?? "" }, set: { draft.metadata.method = $0.isEmpty ? nil : $0; draft.metadata.version = 2 })) {
                            Text("Not set").tag("")
                            ForEach(CookingMethod.order, id: \.self) { Text(CookingMethod.labels[$0]!).tag($0) }
                        }
                        if let duration = draft.metadata.durationMillis, draft.metadata.startedAtUtcMillis != nil && draft.metadata.endedAtUtcMillis != nil {
                            Text("Measured duration: \(duration / 60000, specifier: "%.2f") minutes")
                        } else {
                            TextField("Duration in minutes (optional)", text: $durationText).keyboardType(.decimalPad)
                                .onChange(of: durationText) { _, raw in draft.metadata.durationMillis = Double(raw).map { $0 * 60000 }; draft.metadata.version = 2 }
                        }
                        ForEach((draft.metadata.ingredients ?? []).indices, id: \.self) { index in
                            TextField("Ingredient", text: ingredientBinding(index, \.name))
                            TextField("Amount (optional)", text: ingredientBinding(index, \.amount)).keyboardType(.decimalPad)
                            Picker("Unit", selection: ingredientBinding(index, \.unit)) {
                                ForEach(["cup", "tbsp", "tsp", "fl oz", "ml", "L", "g", "oz", "lb", "kg", "count"], id: \.self) { Text($0).tag($0) }
                            }
                            Picker("Type", selection: ingredientBinding(index, \.stage)) {
                                ForEach(["main_ingredient", "seasoning", "sauce", "marinade", "binder", "brine", "glaze", "topping", "other"], id: \.self) { Text($0.replacingOccurrences(of: "_", with: " ").capitalized).tag($0) }
                            }
                            TextField("Brand (optional)", text: ingredientBinding(index, \.brand))
                            Button("Remove ingredient") { draft.metadata.ingredients?.remove(at: index) }
                        }
                        Button("+ Ingredient") { draft.metadata.ingredients = (draft.metadata.ingredients ?? []) + [NoteIngredient()]; draft.metadata.version = 2 }
                    }
                }
                Section("For") {
                    Toggle("Whole cook", isOn: Binding(get: { draft.dishId == nil && draft.metadata.dishIds.isEmpty }, set: { if $0 { draft.dishId = nil; draft.metadata.dishIds = [] } }))
                    ForEach(store.dishRecords(draft.cookId), id: \.id) { dish in
                        Toggle(dish.name, isOn: Binding(get: { draft.dishId == dish.id || draft.metadata.dishIds.contains(dish.id) }, set: { selected in
                            var ids = Set(draft.metadata.dishIds + [draft.dishId].compactMap { $0 })
                            if selected { ids.insert(dish.id) } else { ids.remove(dish.id) }
                            draft.dishId = ids.count == 1 ? ids.first : nil
                            draft.metadata.dishIds = ids.count > 1 ? ids.sorted() : []
                        }))
                    }
                }
                ForEach($draft.temperatures) { $t in
                    Section("Temperature") {
                        TextField("Probe or location", text: $t.probeName)
                        TextField("Temperature", text: $t.valueText).keyboardType(.numbersAndPunctuation)
                        // Legacy standalone readings can carry "setpoint"/
                        // "other" (the retired Temperature Entry dialog's
                        // types); keep that type selectable so editing one
                        // never silently rewrites it.
                        Picker("Measurement", selection: Binding(get: { ["pit", "pit_ambient"].contains(t.measurementType) ? "pit" : ["setpoint", "other"].contains(t.measurementType) ? t.measurementType : "food" }, set: { t.measurementType = $0 })) {
                            Text("Food").tag("food"); Text("Pit").tag("pit")
                            if t.measurementType == "setpoint" { Text("Setpoint").tag("setpoint") }
                            if t.measurementType == "other" { Text("Other").tag("other") }
                        }
                        Picker("Unit", selection: $t.unit) { Text("°F").tag("°F"); Text("°C").tag("°C") }
                        Picker("Temperature for", selection: Binding(get: { t.dishId ?? "" }, set: { t.dishId = $0.isEmpty ? nil : $0 })) {
                            Text("Whole cook").tag("")
                            ForEach(store.dishRecords(draft.cookId), id: \.id) { Text($0.name).tag($0.id) }
                        }
                        DatePicker("Measured at", selection: dateBinding($t.measuredAt), in: ...Date.now)
                        Button("Remove temperature", role: .destructive) { draft.temperatures.removeAll { $0.id == t.id } }
                    }
                }
                Section {
                    Button("Add temperature") { draft.temperatures.append(NoteTemperatureDraft(unit: "°" + store.temperatureUnit, measuredAt: draft.occurredAt, dishId: draft.dishId)) }
                }
                Section("Photos") {
                    ForEach($draft.photos) { $photo in
                        if let image = store.photoPreview(photo.path) { Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 160) }
                        TextField("Photo caption", text: $photo.caption)
                        if let captured = photo.capturedAt { Button("Use photo capture time") { draft.occurredAt = captured } }
                        Button("Remove photo", role: .destructive) { draft.photos.removeAll { $0.id == photo.id } }
                    }
                    PhotosPicker(selection: $pickerItems, maxSelectionCount: 10, matching: .images) { Label("Choose photos", systemImage: "photo.on.rectangle") }
                        .simultaneousGesture(TapGesture().onEnded { persist() })
                    Button { persist(); camera = true } label: { Label("Take a photo", systemImage: "camera") }.disabled(!UIImagePickerController.isSourceTypeAvailable(.camera))
                }
                if let error { Text(error).foregroundStyle(.red) }
                if busy { ProgressView("Saving photos…") }
            }
            .navigationTitle("Cook note")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Keep draft") { if persist() { closed = true; dismiss() } }.disabled(busy) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { do { busy = true; try store.saveNote(draft); closed = true; dismiss() } catch { self.error = error.localizedDescription }; busy = false }
                        .disabled(busy || (try? draft.validate()) == nil || !durationText.isEmpty && Double(durationText).map { $0.isFinite && $0 >= 0 && $0 <= 43200 } != true)
                }
                ToolbarItem(placement: .bottomBar) { Button("Discard draft", role: .destructive) { do { try store.discardDraft(draft.id); closed = true; dismiss() } catch { self.error = error.localizedDescription } }.disabled(busy) }
            }
            .onAppear { durationText = draft.metadata.durationMillis.map { String($0 / 60000) } ?? ""; persist() }
            .onChange(of: draft) { _, _ in persist() }
            .onChange(of: scenePhase) { _, _ in persist() }
            .onChange(of: pickerItems) { _, items in
                guard !items.isEmpty else { return }
                busy = true
                Task { @MainActor in
                    do { for item in items { if let bytes = try await item.loadTransferable(type: Data.self) { draft.photos.append(try store.stagePhoto(bytes)); persist() } } }
                    catch { self.error = error.localizedDescription }
                    pickerItems = []; busy = false
                }
            }
            .sheet(isPresented: $camera) { NoteCamera { data in
                camera = false
                do { if let data { draft.photos.append(try store.stagePhoto(data, capturedAt: Date.now.timeIntervalSince1970 * 1_000)); persist() } }
                catch { self.error = error.localizedDescription }
            } }
        }
        .interactiveDismissDisabled(busy)
    }
    private func ingredientBinding(_ index: Int, _ key: WritableKeyPath<NoteIngredient, String>) -> Binding<String> {
        Binding(get: { draft.metadata.ingredients?[index][keyPath: key] ?? "" }, set: { draft.metadata.ingredients?[index][keyPath: key] = $0; draft.metadata.version = 2 })
    }
    @discardableResult private func persist() -> Bool { guard !closed else { return true }; do { try store.persistDraft(draft); return true } catch { self.error = error.localizedDescription; return false } }
    private func dateBinding(_ milliseconds: Binding<Double>) -> Binding<Date> {
        Binding(get: { Date(timeIntervalSince1970: milliseconds.wrappedValue / 1_000) }, set: { milliseconds.wrappedValue = $0.timeIntervalSince1970 * 1_000 })
    }
}

private struct NoteCamera: UIViewControllerRepresentable {
    var done: (Data?) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(done: done) }
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController(); picker.sourceType = .camera; picker.delegate = context.coordinator; return picker
    }
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) { }
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let done: (Data?) -> Void
        init(done: @escaping (Data?) -> Void) { self.done = done }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { done(nil) }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) { done((info[.originalImage] as? UIImage)?.jpegData(compressionQuality: 0.95)) }
    }
}
