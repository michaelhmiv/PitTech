import XCTest
@testable import PitTech

/// Mirrors Android's FlowingE4LogicTest (Flowing Slice 3, E4): the
/// opening-section rule and the protect-your-log prompt's exact
/// eligibility matrix.
final class FlowingE4LogicTests: XCTestCase {
    func testFinishedCooksOpenOnTheirStory() {
        XCTAssertEqual(FlowingE4.initialCookSection(status: "completed"), "Timeline")
        XCTAssertEqual(FlowingE4.initialCookSection(status: "active"), "Live")
        XCTAssertEqual(FlowingE4.initialCookSection(status: "paused"), "Live")
    }

    func testProtectPromptEligibilityMatrix() {
        // The one eligible row: finished, never prompted, never
        // synced, automatic sync off.
        XCTAssertTrue(FlowingE4.shouldPromptProtectLog(status: "completed", promptShown: false, lastSyncedAt: 0, automaticSync: false))
        // Every single failing condition closes the gate on its own.
        XCTAssertFalse(FlowingE4.shouldPromptProtectLog(status: "active", promptShown: false, lastSyncedAt: 0, automaticSync: false),
                       "a live cook never prompts")
        XCTAssertFalse(FlowingE4.shouldPromptProtectLog(status: "completed", promptShown: true, lastSyncedAt: 0, automaticSync: false),
                       "it asks once, ever")
        XCTAssertFalse(FlowingE4.shouldPromptProtectLog(status: "completed", promptShown: false, lastSyncedAt: 1_791_799_500_000, automaticSync: false),
                       "a device that has synced is already protected")
        XCTAssertFalse(FlowingE4.shouldPromptProtectLog(status: "completed", promptShown: false, lastSyncedAt: 0, automaticSync: true),
                       "automatic sync already protects the log")
    }
}
