import SwiftUI
import PhotosUI

/// Legacy cook reminders (issue #183, iOS half): Android's composer,
/// pending list and "How did it go?" check-in — the section rides at
/// the top of the cook's Reminders surface, above the unified rules.
/// One vocabulary everywhere (PL-02): Log it records, Snooze 10 min
/// asks again soon, Dismiss ends it without recording; closing the
/// check-in changes nothing.
struct LegacyRemindersSection: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    @State private var showingComposer = false
    @State private var checkIn: LegacyReminder?
    @State private var error: String?

    private var pending: [LegacyReminder] { store.legacyReminders(cookId: cookId).filter { $0.status == "pending" } }
    private var active: Bool { store.library.cooks.contains { $0.id == cookId && $0.isActive } }

    var body: some View {
        Section {
            ForEach(pending) { reminder in
                VStack(alignment: .leading, spacing: 4) {
                    Text(reminder.title).font(PitTechFont.bodyEmphasis)
                    Text(reminder.isDue ? "Due now" : "Due " + RecipeReferenceTimeline.time(Double(reminder.dueAtUtcMillis)))
                        .font(PitTechFont.monoMeta)
                        .foregroundStyle(reminder.isDue ? PitTechStyle.flag : PitTechStyle.inkMuted)
                    HStack {
                        if reminder.isDue {
                            Button("Check in") { checkIn = reminder }
                                .accessibilityIdentifier("legacy-reminder-checkin-\(reminder.id)")
                        }
                        Button("Dismiss") { store.cancelCookReminder(reminder.id) }
                    }
                    .buttonStyle(.borderless)
                }
                .padding(.vertical, 2)
            }
            if active {
                Button("Set a cook reminder") { showingComposer = true }
                    .accessibilityIdentifier("legacy-reminder-add")
            }
            if let error { Text(error).foregroundStyle(PitTechStyle.flag) }
        } header: {
            Text("Cook reminders")
        }
        .sheet(isPresented: $showingComposer) {
            LegacyReminderComposer(cookId: cookId)
        }
        .sheet(item: $checkIn) { reminder in
            LegacyReminderCheckInView(reminder: reminder)
        }
    }
}

/// "Set a cook reminder": a title and a delay chip — Android's
/// composer, word for word.
struct LegacyReminderComposer: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let cookId: String
    @State private var title = ""
    @State private var delayMillis: Int64 = 30 * 60 * 1000
    @State private var error: String?

    private let options: [(millis: Int64, label: String)] = [
        (30 * 60 * 1000, "30 min"), (60 * 60 * 1000, "1 hour"), (2 * 60 * 60 * 1000, "2 hours"), (5 * 60 * 60 * 1000, "5 hours"),
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("What should you check?", text: $title)
                        .accessibilityIdentifier("reminder-title")
                }
                Section("Remind me in") {
                    Picker("Remind me in", selection: $delayMillis) {
                        ForEach(options, id: \.millis) { Text($0.label).tag($0.millis) }
                    }
                    .pickerStyle(.segmented)
                    Text("Notifications may arrive a little after the selected time.")
                        .font(.caption)
                        .foregroundStyle(PitTechStyle.supporting)
                }
                if let error { Text(error).foregroundStyle(PitTechStyle.flag) }
            }
            .navigationTitle("Set a cook reminder")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Set reminder") {
                        do {
                            try store.createCookReminder(cookId: cookId, title: title, dueAtUtcMillis: Int64(Date.now.timeIntervalSince1970 * 1000) + delayMillis)
                            dismiss()
                        } catch { self.error = error.localizedDescription }
                    }
                    .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityIdentifier("reminder-save")
                }
            }
        }
    }
}

/// "How did it go?" — the fired reminder's check-in: a note, an
/// optional photo, and the unified Log it / Snooze 10 min / Dismiss.
struct LegacyReminderCheckInView: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let reminder: LegacyReminder
    @State private var note = ""
    @State private var photo: NotePhotoDraft?
    @State private var photoCaption = ""
    @State private var pickerItem: PhotosPickerItem?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(reminder.title).font(PitTechFont.bodyEmphasis)
                    TextField("Add a note", text: $note, axis: .vertical)
                        .lineLimit(3...6)
                        .accessibilityIdentifier("reminder-checkin-note")
                }
                Section {
                    if let photo {
                        Text("Photo ready to save with this check-in.")
                            .foregroundStyle(PitTechStyle.sage)
                        TextField("Photo caption (optional)", text: $photoCaption)
                            .accessibilityIdentifier("reminder-checkin-photo-caption")
                        Button("Remove photo", role: .destructive) { self.photo = nil; photoCaption = "" }
                    }
                    PhotosPicker(selection: $pickerItem, matching: .images) {
                        Text(photo == nil ? "Add a photo to this check-in" : "Change attached photo")
                    }
                    .onChange(of: pickerItem) { _, item in
                        guard let item else { return }
                        Task {
                            do {
                                if let data = try await item.loadTransferable(type: Data.self) {
                                    photo = try store.stagePhoto(data)
                                }
                            } catch { self.error = error.localizedDescription }
                        }
                    }
                }
                if let error { Text(error).foregroundStyle(PitTechStyle.flag) }
            }
            .navigationTitle("How did it go?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Dismiss") {
                        store.dismissCookReminder(reminder.id)
                        dismiss()
                    }
                    .accessibilityIdentifier("reminder-checkin-dismiss")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Log it") {
                        do {
                            var attached = photo
                            if attached != nil { attached!.caption = photoCaption }
                            try store.completeCookReminder(reminder.id, note: note, photo: attached)
                            dismiss()
                        } catch { self.error = error.localizedDescription }
                    }
                    .accessibilityIdentifier("reminder-checkin-save")
                }
                ToolbarItemGroup(placement: .bottomBar) {
                    Button("Snooze 10 min") {
                        store.snoozeCookReminder(reminder.id)
                        dismiss()
                    }
                    .accessibilityIdentifier("reminder-checkin-snooze")
                }
            }
        }
    }
}
