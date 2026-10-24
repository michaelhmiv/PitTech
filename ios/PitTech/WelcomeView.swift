import SwiftUI

/// First-run welcome (#187, #172 PL-31): one screen, first launch only.
/// The seen flag is a UserDefaults bool, following the app's existing
/// @AppStorage / UserDefaults preference patterns.
enum WelcomeGate {
    static let seenKey = "pittech-welcome-seen-v1"

    /// UI tests and screenshot runs launch with fixture arguments and must
    /// land on the surfaces under test — the same reason PitTechApp skips
    /// ads consent for those launches.
    static var suppressedForTesting: Bool {
        CommandLine.arguments.contains("--ui-test-reset")
            || CommandLine.arguments.contains { $0.hasPrefix("--screenshot") }
            || ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

    /// Pure routing rule, identical to Android's resolveLaunchSurface:
    /// a crash pending at launch suppresses the welcome for that launch
    /// (iOS has no crash-recovery surface today, so the flag simply stays
    /// unseen until a clean launch), and a seen welcome never shows again.
    static func shouldShow(seen: Bool, crashPendingAtLaunch: Bool, suppressedForTesting: Bool) -> Bool {
        !seen && !crashPendingAtLaunch && !suppressedForTesting
    }
}

struct WelcomeView: View {
    let onStartCooking: () -> Void
    let onRestoreBackup: () -> Void
    let onConnectThermometer: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing = 16) {
                // Hero mascot: DD-1 sanctions hero size in exactly two
                // places — this welcome and Settings → About.
                Image("Mascot")
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity)
                    .frame(height: 190)
                    .accessibilityIdentifier("welcome-mascot")
                Text("Welcome to PitTech")
                    .font(.largeTitle.weight(.semibold))
                Text("Log your cooks with live temps, notes and photos — no account needed to start.")
                    .font(.body)
                    .foregroundStyle(PitTechStyle.supporting)
                WelcomeRow(symbol: "thermometer", title: "Live cook tracking", subtitle: "Connect a thermometer or log temps by hand.")
                WelcomeRow(symbol: "book", title: "Recipes, playbooks & reminders", subtitle: "Plan the cook and get nudged at the right moments.")
                WelcomeRow(symbol: "safari", title: "Share & Discover", subtitle: "Cook Packs, codes and community recipes.")
                Button(action: onStartCooking) {
                    Text("Start cooking")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight = 54)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("welcome-start-cooking")
                // Quiet secondaries: the existing first-run jobs, same
                // destinations as the empty-Meals entries.
                HStack(spacing: 8) {
                    Spacer()
                    Button("Restore from backup", action: onRestoreBackup)
                        .accessibilityIdentifier("welcome-restore-backup")
                    Button("Connect a thermometer", action: onConnectThermometer)
                        .accessibilityIdentifier("welcome-connect-thermometer")
                    Spacer()
                }
                .font(.subheadline)
            }
            .padding(24)
        }
        .background(PitTechStyle.canvas.ignoresSafeArea())
        .accessibilityIdentifier("welcome-screen")
    }
}

private struct WelcomeRow: View {
    let symbol: String
    let title: String
    let subtitle: String
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: symbol)
                .foregroundStyle(PitTechStyle.accent)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline).foregroundStyle(PitTechStyle.ink)
                Text(subtitle).font(.subheadline).foregroundStyle(PitTechStyle.supporting)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
    }
}
