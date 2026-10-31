import SwiftUI

/// Cook guidance surfaces (issue #183, iOS half): Android's
/// CookGuidanceCard — the next-action card on Live and the plan sheet
/// behind it. A plan reaches iOS by sync today (authoring lives in the
/// Serve Plan surface); what was missing, and what this adds, is the
/// whole action path: evaluate, show the next step, Done / Snooze /
/// Skip with Android's semantics. Nothing renders without a plan.
struct CookGuidanceCard: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    let now: Date
    @State private var showingPlan = false
    @State private var removing: EvaluatedStep?
    @State private var error: String?

    private var nowMillis: Int64 { Int64(now.timeIntervalSince1970 * 1000) }

    var body: some View {
        if let plan = store.guidancePlan(cookId: cookId) {
            let evaluated = store.evaluatedGuidance(cookId: cookId, now: nowMillis)
            let next = store.nextGuidanceStep(cookId: cookId, now: nowMillis)
            TurnInCard {
                VStack(alignment: .leading, spacing: 6) {
                    TurnInMetaLabel(plan.paused ? "Guidance paused" : "Next action")
                    HStack(spacing: 12) {
                        ForEach(store.dishRecords(cookId)) { dish in
                            Text("\(dish.name) · \(CookPlanEngine.stage(events: store.guidanceEvents(cookId: cookId), dishId: dish.id))")
                                .font(PitTechFont.monoMeta)
                                .foregroundStyle(PitTechStyle.inkMuted)
                        }
                    }
                    TurnInBannerText(next?.step.title ?? "\(plan.book.name) · view your plan")
                    if let next {
                        Text(next.ready ? "Ready to check" : next.dueAt.map { RecipeReferenceTimeline.time(Double($0)) } ?? next.waitingFor ?? "When ready")
                            .font(PitTechFont.monoMeta)
                            .foregroundStyle(next.ready ? PitTechStyle.ember : PitTechStyle.inkMuted)
                        if !next.step.instructions.isEmpty {
                            Text(next.step.instructions).font(PitTechFont.body)
                        }
                        if next.ready {
                            Button {
                                if next.step.action == "remove", next.dishId != nil { removing = next }
                                else { act { try store.completeGuidanceStep(cookId: cookId, evaluated: next) } }
                            } label: {
                                Text("Done")
                                    .font(PitTechFont.bodyEmphasis)
                                    .foregroundStyle(PitTechStyle.onAccent)
                                    .frame(maxWidth: .infinity, minHeight: 48)
                            }
                            .background(PitTechStyle.primaryFill, in: RoundedRectangle(cornerRadius: 12))
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("guidance-done")
                        }
                    }
                    Button("View plan") { showingPlan = true }
                        .frame(minHeight: 48)
                        .accessibilityIdentifier("view-plan")
                    if let error { Text(error).font(PitTechFont.body).foregroundStyle(PitTechStyle.flag) }
                }
            }
            .sheet(isPresented: $showingPlan) {
                CookGuidancePlanView(cookId: cookId)
            }
            .confirmationDialog(
                removing.map { e in "Remove \(store.dishRecords(cookId).first { $0.id == e.dishId }?.name ?? "")?" } ?? "",
                isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                titleVisibility: .visible
            ) {
                if let removing {
                    Button("Removed · start rest") { act { try store.completeGuidanceStep(cookId: cookId, evaluated: removing, rest: true) }; self.removing = nil }
                    Button("Removed only") { act { try store.completeGuidanceStep(cookId: cookId, evaluated: removing) }; self.removing = nil }
                    Button("Cancel", role: .cancel) { self.removing = nil }
                }
            } message: {
                Text("Confirm when the food leaves the smoker. You can start its rest now.")
            }
        }
    }

    private func act(_ block: () throws -> Void) {
        do { try block(); error = nil } catch { self.error = error.localizedDescription }
    }
}

/// The plan sheet: every step a card, the current one leading, with
/// Done · title / Snooze 10 min / Skip on pending steps and the
/// pause control — Android's sheet, minus its step editor (iOS plan
/// authoring lives in the Serve Plan surface).
struct CookGuidancePlanView: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let cookId: String
    @State private var skipping: EvaluatedStep?
    @State private var removing: EvaluatedStep?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            TimelineView(.periodic(from: .now, by: 15)) { context in
                let now = Int64(context.date.timeIntervalSince1970 * 1000)
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        if let plan = store.guidancePlan(cookId: cookId) {
                            TurnInMetaLabel("Guidance plan")
                            TurnInBannerText(plan.book.name, font: PitTechFont.bannerTitle)
                            Button(plan.paused ? "Resume guidance" : "Pause guidance") {
                                act { try store.setGuidancePaused(cookId: cookId, paused: !plan.paused) }
                            }
                            .buttonStyle(.bordered)
                            .frame(maxWidth: .infinity, minHeight: 48)
                            let evaluated = store.evaluatedGuidance(cookId: cookId, now: now)
                            let next = store.nextGuidanceStep(cookId: cookId, now: now)
                            ForEach(evaluated, id: \.step.id) { e in
                                stepCard(e, isCurrent: e.progress.status == "pending" && next?.step.id == e.step.id)
                            }
                            Divider()
                            TurnInMetaLabel("Dish stages")
                            ForEach(store.dishRecords(cookId)) { dish in
                                Text("\(dish.name) · \(CookPlanEngine.stage(events: store.guidanceEvents(cookId: cookId), dishId: dish.id))")
                                    .font(PitTechFont.bodyEmphasis)
                            }
                            Text("Guidance pause leaves recording and physical rest/hold timers running.")
                                .font(PitTechFont.body)
                                .foregroundStyle(PitTechStyle.inkMuted)
                            if let error { Text(error).foregroundStyle(PitTechStyle.flag) }
                        }
                    }
                    .padding(16)
                }
                .background(PitTechStyle.canvas.ignoresSafeArea())
            }
            .navigationTitle("Guidance")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
        }
        .confirmationDialog(
            skipping.map { "Skip \($0.step.title)?" } ?? "",
            isPresented: Binding(get: { skipping != nil }, set: { if !$0 { skipping = nil } }),
            titleVisibility: .visible
        ) {
            if let skipping {
                Button("Skip") { act { try store.skipGuidanceStep(cookId: cookId, stepId: skipping.step.id, useNowAsAnchor: false) }; self.skipping = nil }
                Button("Skip · use now for later steps") { act { try store.skipGuidanceStep(cookId: cookId, stepId: skipping.step.id, useNowAsAnchor: true) }; self.skipping = nil }
                Button("Cancel", role: .cancel) { self.skipping = nil }
            }
        } message: {
            Text("Skipping records no physical action. If later steps depend on this action, choose whether to use the current time as their planning anchor.")
        }
        .confirmationDialog(
            removing.map { e in "Remove \(store.dishRecords(cookId).first { $0.id == e.dishId }?.name ?? "")?" } ?? "",
            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
            titleVisibility: .visible
        ) {
            if let removing {
                Button("Removed · start rest") { act { try store.completeGuidanceStep(cookId: cookId, evaluated: removing, rest: true) }; self.removing = nil }
                Button("Removed only") { act { try store.completeGuidanceStep(cookId: cookId, evaluated: removing) }; self.removing = nil }
                Button("Cancel", role: .cancel) { self.removing = nil }
            }
        } message: {
            Text("Confirm when the food leaves the smoker. You can start its rest now.")
        }
    }

    @ViewBuilder private func stepCard(_ e: EvaluatedStep, isCurrent: Bool) -> some View {
        TurnInCard {
            VStack(alignment: .leading, spacing: 6) {
                Text(e.step.title)
                    .font(PitTechFont.bodyEmphasis)
                    .accessibilityIdentifier("guidance-step-\(e.step.id)")
                if let dishId = e.dishId {
                    Text(store.dishRecords(cookId).first { $0.id == dishId }?.name ?? "")
                        .font(PitTechFont.body)
                        .foregroundStyle(PitTechStyle.inkMuted)
                }
                Text(e.progress.status != "pending" ? e.progress.status : e.waitingFor ?? CookPlanEngine.stepDescription(e.step))
                    .font(PitTechFont.monoMeta)
                    .foregroundStyle(PitTechStyle.inkMuted)
                if !e.step.instructions.isEmpty {
                    Text(e.step.instructions).font(PitTechFont.body)
                }
                if e.progress.status == "pending" {
                    Button("Done · \(e.step.title)") {
                        if e.step.action == "remove", e.dishId != nil { removing = e }
                        else { act { try store.completeGuidanceStep(cookId: cookId, evaluated: e) } }
                    }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    HStack {
                        Button("Snooze 10 min") { act { try store.snoozeGuidanceStep(cookId: cookId, evaluated: e) } }
                        Button("Skip") { skipping = e }
                    }
                }
            }
        }
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(isCurrent ? PitTechStyle.marigold : Color.clear, lineWidth: 1.5))
    }

    private func act(_ block: () throws -> Void) {
        do { try block(); error = nil } catch { self.error = error.localizedDescription }
    }
}
