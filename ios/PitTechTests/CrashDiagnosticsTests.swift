import XCTest
@testable import PitTech

/// PL-20 (#184): the iOS crash-recovery store, held to Android's
/// CrashDiagnostics semantics — pending until acknowledged, retained
/// until deleted, the open cook remembered, and a session that never
/// stood down leaving a synthesized report instead of silence.
final class CrashDiagnosticsTests: XCTestCase {
    private var folder: URL!
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        suiteName = "crash-diagnostics-" + UUID().uuidString
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: folder)
        defaults.removePersistentDomain(forName: suiteName)
    }

    private func makeCore() -> CrashDiagnosticsCore { CrashDiagnosticsCore(directory: folder, defaults: defaults) }

    private func report(_ reference: String = "PT-ABCDEF12") -> DiagnosticReport {
        DiagnosticReport(referenceCode: reference, occurredAtUtc: "2026-10-30 09:15:00 UTC", source: "Uncaught application exception", summary: "Boom: it broke", details: "Thread: main\nException: Boom\n\nstack")
    }

    func testReferenceCodeAndUtcFormatMatchAndroid() {
        for _ in 0..<20 {
            let code = DiagnosticReport.newReferenceCode()
            XCTAssertTrue(code.hasPrefix("PT-"), code)
            XCTAssertEqual(code.count, 11, code)
            XCTAssertEqual(code, code.uppercased())
        }
        XCTAssertEqual(DiagnosticReport.formatUtc(1_793_000_000_000), "2026-10-26 07:33:20 UTC")
    }

    func testPlainTextLayoutMatchesAndroid() {
        let text = report().plainText
        XCTAssertEqual(text, """
        PitTech local diagnostic report
        Reference code: PT-ABCDEF12
        Occurred (UTC): 2026-10-30 09:15:00 UTC
        Source: Uncaught application exception
        Summary: Boom: it broke

        Thread: main
        Exception: Boom

        stack
        """)
    }

    func testPendingUntilAcknowledgedAndRetainedUntilDeleted() {
        let core = makeCore()
        XCTAssertNil(core.pendingReport())
        core.saveReport(report(), occurredAtUtcMillis: 1_000)
        XCTAssertEqual(core.pendingReport()?.referenceCode, "PT-ABCDEF12")
        core.acknowledge(report())
        XCTAssertNil(core.pendingReport(), "acknowledged reports stop pending")
        XCTAssertEqual(core.savedReport()?.referenceCode, "PT-ABCDEF12", "acknowledging keeps the report for Settings")
        // A newer crash replaces the saved one and pends again.
        core.saveReport(report("PT-99999999"), occurredAtUtcMillis: 2_000)
        XCTAssertEqual(core.pendingReport()?.referenceCode, "PT-99999999")
        core.clearSavedReport()
        XCTAssertNil(core.savedReport())
        XCTAssertNil(core.pendingReport())
    }

    func testOpenCookRecordFollowsTheSelection() {
        let core = makeCore()
        XCTAssertNil(core.openCookAtCrash())
        core.recordOpenCook("cook-1")
        XCTAssertEqual(core.openCookAtCrash(), "cook-1")
        core.recordOpenCook("cook-2")
        XCTAssertEqual(core.openCookAtCrash(), "cook-2")
        core.recordOpenCook(nil)
        XCTAssertNil(core.openCookAtCrash())
        core.recordOpenCook("")
        XCTAssertNil(core.openCookAtCrash())
    }

    func testExceptionReportSummaryRules() {
        let withMessage = CrashDiagnosticsCore.exceptionReport(name: "NSInvalidArgumentException", reason: "bad\nvalue", stack: ["0 PitTech 0x1"], threadName: "main", now: 1_793_000_000_000)
        XCTAssertEqual(withMessage.summary, "NSInvalidArgumentException: bad value")
        XCTAssertEqual(withMessage.source, "Uncaught application exception")
        XCTAssertTrue(withMessage.details.hasPrefix("Thread: main\nException: NSInvalidArgumentException\n\n0 PitTech 0x1"))
        let bare = CrashDiagnosticsCore.exceptionReport(name: "SIGSEGVException", reason: nil, stack: [], threadName: "main", now: 0)
        XCTAssertEqual(bare.summary, "SIGSEGVException")
        let longReason = CrashDiagnosticsCore.exceptionReport(name: "E", reason: String(repeating: "x", count: 400), stack: [], threadName: "main", now: 0)
        XCTAssertEqual(longReason.summary.count, 2 + 300)
    }

    func testSessionThatNeverStoodDownLeavesASynthesizedReport() {
        let core = makeCore()
        XCTAssertNil(core.beginSession(now: 10_000))
        // The session dies while active: no endSession, no exception.
        let next = makeCore()
        let synthesized = next.beginSession(now: 20_000)
        XCTAssertEqual(synthesized?.source, "Previous session record")
        XCTAssertEqual(next.pendingReport()?.referenceCode, synthesized?.referenceCode)
        XCTAssertEqual(next.pendingReport()?.occurredAtUtc, DiagnosticReport.formatUtc(10_000))
    }

    func testCleanSessionEndLeavesNoReport() {
        let core = makeCore()
        _ = core.beginSession(now: 10_000)
        core.endSession()
        XCTAssertNil(makeCore().beginSession(now: 20_000))
        XCTAssertNil(makeCore().pendingReport())
    }

    func testCapturedExceptionSuppressesTheSynthesizedReport() {
        let core = makeCore()
        _ = core.beginSession(now: 10_000)
        core.recordException(name: "Boom", reason: "kaput", stack: ["frame"], threadName: "main")
        // Simulate the crash-time save landing after beginSession's start:
        // recordException stamps its own now, which is >= the session
        // start, so the next launch keeps the real report.
        let next = makeCore()
        XCTAssertNil(next.beginSession(now: 30_000))
        XCTAssertEqual(next.pendingReport()?.summary, "Boom: kaput")
    }
}
