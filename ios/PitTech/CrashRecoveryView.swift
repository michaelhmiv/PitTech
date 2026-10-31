import SwiftUI
import UIKit

/// PL-20 (#172, iOS half — issue #184): the launch surface shown when the
/// previous session left an unacknowledged crash report. Copy, order and
/// jobs mirror Android's CrashRecoveryScreen exactly: nothing was sent,
/// the reference code and summary ride a card, Continue returns to the
/// cook that was open at the crash, and attaching hands the report to the
/// feedback composer — the only door off the device, and only by choice.
struct CrashRecoveryView: View {
    let report: DiagnosticReport
    let onContinue: () -> Void
    let onAttach: () -> Void
    @State private var showDetails = false
    @State private var copied = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                TurnInMetaLabel("PitTech")
                TurnInBannerText("PitTech stopped unexpectedly", font: PitTechFont.bannerTitle)
                Text("We saved a diagnostic report on this device. Nothing was sent automatically.")
                    .font(PitTechFont.body)
                    .foregroundStyle(PitTechStyle.inkMuted)
                TurnInCard {
                    VStack(alignment: .leading, spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            TurnInMetaLabel("Reference code")
                            Text(report.referenceCode)
                                .font(PitTechFont.monoMeta)
                                .foregroundStyle(PitTechStyle.ink)
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            TurnInMetaLabel("What happened")
                            Text(report.summary)
                                .font(PitTechFont.body)
                                .foregroundStyle(PitTechStyle.ink)
                        }
                        Text("Time: \(report.occurredAtUtc)")
                            .font(PitTechFont.monoMeta)
                            .foregroundStyle(PitTechStyle.inkMuted)
                    }
                }
                Button(action: onContinue) {
                    Text("Continue to PitTech")
                        .font(PitTechFont.bodyEmphasis)
                        .foregroundStyle(PitTechStyle.onAccent)
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .background(PitTechStyle.primaryFill, in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("crash-report-continue")
                Button("Attach to a problem report", action: onAttach)
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .accessibilityIdentifier("crash-report-attach")
                Button("Copy diagnostic report") {
                    UIPasteboard.general.string = report.plainText
                    copied = true
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity, minHeight: 48)
                .accessibilityIdentifier("crash-report-copy")
                if copied {
                    Text("Report copied. Include it in Settings → Report a problem if you'd like to share it.")
                        .font(PitTechFont.body)
                        .foregroundStyle(PitTechStyle.inkMuted)
                }
                Button(showDetails ? "Hide technical details" : "Show technical details") { showDetails.toggle() }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .accessibilityIdentifier("crash-report-details")
                if showDetails {
                    TurnInCard {
                        Text(report.details)
                            .font(PitTechFont.monoMeta)
                            .foregroundStyle(PitTechStyle.ink)
                            .textSelection(.enabled)
                    }
                }
            }
            .padding(20)
        }
        .background(PitTechStyle.canvas.ignoresSafeArea())
    }
}
