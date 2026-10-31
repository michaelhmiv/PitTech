import Foundation
import SwiftUI

/// PL-20 (#172, iOS half — issue #184): the local crash diagnostic report,
/// field-for-field the Android `FeedbackDiagnosticReport`: a reference
/// code, when it happened (UTC), where it came from, a one-line summary
/// and the technical details. The report is device-local: it is written
/// to a file nothing syncs or exports, and it leaves the device only when
/// the user explicitly includes it in a problem report (see
/// NativeFeedbackView) — the relay accepts exactly this shape as the
/// submission's `diagnosticReport`.
struct DiagnosticReport: Codable, Equatable {
    var referenceCode: String
    var occurredAtUtc: String
    var source: String
    var summary: String
    var details: String

    /// The exact plain text Android renders (`toPlainText`): what Copy
    /// puts on the pasteboard and what the feedback preview shows.
    var plainText: String {
        let body = """
        PitTech local diagnostic report
        Reference code: \(referenceCode)
        Occurred (UTC): \(occurredAtUtc)
        Source: \(source)
        Summary: \(summary)

        \(details)
        """
        return body.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func newReferenceCode() -> String {
        "PT-" + UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8).uppercased()
    }

    static func formatUtc(_ millis: Double) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss 'UTC'"
        return formatter.string(from: Date(timeIntervalSince1970: millis / 1000))
    }
}

/// Storage + capture for the crash-recovery surface. Mirrors Android's
/// CrashDiagnostics semantics one for one where iOS allows it:
/// - an uncaught-exception handler writes the report synchronously, then
///   chains to the previous handler;
/// - a session that never stood down (the app died while active with no
///   captured exception — a native crash, a jetsam kill, a hang) yields a
///   synthesized "previous session" report at the next launch, the
///   closest iOS equivalent of Android's process-exit records;
/// - pending = a saved report whose reference is not the acknowledged
///   one; acknowledging keeps the report (Settings can still offer it for
///   a problem report) until the user deletes it;
/// - the open cook is remembered device-locally as navigation changes,
///   so recovery can return to it. Never exported or synced.
final class CrashDiagnosticsCore: @unchecked Sendable {
    private let directory: URL
    private let defaults: UserDefaults

    static let reportFileName = "pittech-last-crash.json"
    static let acknowledgedKey = "pittech-diagnostics-acknowledged-report"
    static let openCookKey = "pittech-diagnostics-open-cook-id"
    static let launchActiveKey = "pittech-diagnostics-launch-active"
    static let launchStartedAtKey = "pittech-diagnostics-launch-started-at"
    static let maxDetailsLength = 24_000

    init(directory: URL, defaults: UserDefaults) {
        self.directory = directory
        self.defaults = defaults
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    private var reportURL: URL { directory.appendingPathComponent(Self.reportFileName) }

    private struct StoredReport: Codable {
        var report: DiagnosticReport
        var occurredAtUtcMillis: Double
    }

    // MARK: - Report storage

    func saveReport(_ report: DiagnosticReport, occurredAtUtcMillis: Double) {
        let stored = StoredReport(report: report, occurredAtUtcMillis: occurredAtUtcMillis)
        guard let data = try? JSONEncoder().encode(stored) else { return }
        try? data.write(to: reportURL, options: .atomic)
    }

    private func loadStored() -> StoredReport? {
        guard let data = try? Data(contentsOf: reportURL),
              let stored = try? JSONDecoder().decode(StoredReport.self, from: data),
              !stored.report.referenceCode.isEmpty, !stored.report.summary.isEmpty else { return nil }
        return stored
    }

    /// Most recent report, retained locally until replaced or deleted.
    func savedReport() -> DiagnosticReport? { loadStored()?.report }

    /// Report that still needs to be acknowledged by the recovery screen.
    func pendingReport() -> DiagnosticReport? {
        guard let report = savedReport() else { return nil }
        return report.referenceCode == defaults.string(forKey: Self.acknowledgedKey) ? nil : report
    }

    func acknowledge(_ report: DiagnosticReport) {
        defaults.set(report.referenceCode, forKey: Self.acknowledgedKey)
    }

    /// The Settings "Delete saved crash report" action: the file goes and
    /// the acknowledgement with it, like Android's clearPendingReport.
    func clearSavedReport() {
        try? FileManager.default.removeItem(at: reportURL)
        defaults.removeObject(forKey: Self.acknowledgedKey)
    }

    // MARK: - Open cook (PL-20)

    func recordOpenCook(_ cookId: String?) {
        if let cookId, !cookId.isEmpty { defaults.set(cookId, forKey: Self.openCookKey) }
        else { defaults.removeObject(forKey: Self.openCookKey) }
    }

    /// The cook that was open when the last crash hit, if one was open.
    func openCookAtCrash() -> String? {
        defaults.string(forKey: Self.openCookKey)?.nonBlank
    }

    // MARK: - Session tracking (the process-exit stand-in)

    /// Called once at launch, before anything else can crash. If the
    /// previous session never stood down and left no exception report
    /// behind, synthesizes its report now. Returns the synthesized
    /// report, if any (the caller reads pendingReport() for the full
    /// picture either way).
    @discardableResult
    func beginSession(now: Double = Date.now.timeIntervalSince1970 * 1000) -> DiagnosticReport? {
        var synthesized: DiagnosticReport?
        if defaults.bool(forKey: Self.launchActiveKey) {
            let startedAt = defaults.double(forKey: Self.launchStartedAtKey)
            let alreadyCaptured = loadStored().map { $0.occurredAtUtcMillis >= startedAt } ?? false
            if !alreadyCaptured, startedAt > 0 {
                let report = Self.previousSessionReport(sessionStartedAtMillis: startedAt)
                saveReport(report, occurredAtUtcMillis: startedAt)
                synthesized = report
            }
        }
        defaults.set(true, forKey: Self.launchActiveKey)
        defaults.set(now, forKey: Self.launchStartedAtKey)
        return synthesized
    }

    /// The session reached a state from which an OS kill is ordinary
    /// lifecycle (backgrounded, or the app switcher came up after the app
    /// was fully active): stand the marker down so the next launch does
    /// not manufacture a crash that never happened — Android likewise
    /// ignores user-requested and low-memory exits.
    func endSession() {
        defaults.set(false, forKey: Self.launchActiveKey)
    }

    static func previousSessionReport(sessionStartedAtMillis: Double) -> DiagnosticReport {
        DiagnosticReport(
            referenceCode: DiagnosticReport.newReferenceCode(),
            occurredAtUtc: DiagnosticReport.formatUtc(sessionStartedAtMillis),
            source: "Previous session record",
            summary: "PitTech stopped unexpectedly during its previous session.",
            details: """
            The previous session ended without a clean shutdown, and no exception was captured.
            Previous session started (UTC): \(DiagnosticReport.formatUtc(sessionStartedAtMillis))
            """)
    }

    // MARK: - Exception capture

    static func exceptionReport(name: String, reason: String?, stack: [String], threadName: String, now: Double = Date.now.timeIntervalSince1970 * 1000) -> DiagnosticReport {
        let message = (reason ?? "").replacingOccurrences(of: "\n", with: " ").prefix(300)
        let summary = message.isEmpty ? name : "\(name): \(message)"
        let details = """
        Thread: \(threadName)
        Exception: \(name)

        \(stack.joined(separator: "\n"))
        """.prefix(maxDetailsLength)
        return DiagnosticReport(
            referenceCode: DiagnosticReport.newReferenceCode(),
            occurredAtUtc: DiagnosticReport.formatUtc(now),
            source: "Uncaught application exception",
            summary: summary,
            details: String(details))
    }

    func recordException(name: String, reason: String?, stack: [String], threadName: String) {
        let now = Date.now.timeIntervalSince1970 * 1000
        saveReport(Self.exceptionReport(name: name, reason: reason, stack: stack, threadName: threadName, now: now), occurredAtUtcMillis: now)
    }

    // MARK: - Fixture support (DEBUG UI tests)

    /// UI-test launches start from a clean diagnostics slate, like the
    /// clean library --ui-test-reset already guarantees.
    func resetForUITests() {
        clearSavedReport()
        recordOpenCook(nil)
        endSession()
        defaults.removeObject(forKey: Self.launchStartedAtKey)
    }
}

private extension String {
    var nonBlank: String? { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self }
}

/// The app-wide diagnostics entry points. `install()` runs first in
/// PitTechApp.init — the handler must be in place before any UI exists,
/// and the previous session must be judged before this one overwrites
/// its marker.
enum CrashDiagnostics {
    static let shared: CrashDiagnosticsCore = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return CrashDiagnosticsCore(directory: base.appendingPathComponent("PitTechDiagnostics", isDirectory: true), defaults: .standard)
    }()

    private static var reachedActive = false
    /// The handler NSSetUncaughtExceptionHandler accepts is a C function
    /// pointer: the closure may touch globals only, so the previous
    /// handler is parked here rather than captured.
    private static var previousHandler: NSUncaughtExceptionHandler?

    static func install() {
        shared.beginSession()
        previousHandler = NSGetUncaughtExceptionHandler()
        NSSetUncaughtExceptionHandler { exception in
            CrashDiagnostics.shared.recordException(
                name: exception.name.rawValue,
                reason: exception.reason,
                stack: exception.callStackSymbols,
                threadName: Thread.current.name ?? (Thread.isMainThread ? "main" : "background"))
            CrashDiagnostics.previousHandler?(exception)
        }
    }

    /// Stand the session marker down once the user is leaving (the app
    /// switcher counts — that is how a force quit begins, and Android
    /// treats a user-requested stop as no crash), and only after the app
    /// fully came up: a launch that dies before reaching .active keeps
    /// its marker, so the next launch reports it.
    static func sessionPhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .active: reachedActive = true
        case .inactive, .background: if reachedActive { shared.endSession() }
        @unknown default: break
        }
    }
}
