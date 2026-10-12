import XCTest
@testable import PitTech

final class LibraryCompatibilityTests: XCTestCase {
    func testVersionSixRoundTripPreservesStructuredNotesAndOldLibrariesDefaultSafely() throws {
        let data = Data("{\"schemaVersion\":5,\"cooks\":[],\"events\":[]}".utf8)
        let old = try JSONDecoder().decode(PitTechLibrary.self, from: data)
        XCTAssertTrue(old.logReadingLinks.isEmpty)
        XCTAssertTrue(old.lifecycles.isEmpty)
        var library = PitTechLibrary()
        let cook = CookRecord.start(title: "Cook")
        library.cooks = [cook]
        library.events = [.object(["id": .string("note"), "cookId": .string(cook.id), "eventType": .string("note"), "noteMetadataJson": .string(CookNoteMetadata(category: "temperature", action: "wrap").json)])]
        library.readings = [.object(["id": .string("reading"), "cookId": .string(cook.id), "source": .string("manual")])]
        library.logReadingLinks = [.object(["readingId": .string("reading"), "eventId": .string("note")])]
        library.lifecycles = [.object(["cookId": .string(cook.id), "timingMode": .string("explicit_phases")])]
        try library.validateCookExperience()
        let decoded = try JSONDecoder().decode(PitTechLibrary.self, from: JSONEncoder().encode(library))
        XCTAssertEqual(library.events, decoded.events)
        XCTAssertEqual(library.logReadingLinks, decoded.logReadingLinks)
        XCTAssertEqual(library.lifecycles, decoded.lifecycles)
        library.readings[0] = .object(["id": .string("reading"), "cookId": .string("other"), "source": .string("manual")])
        XCTAssertThrowsError(try library.validateCookExperience())
    }
    func testUnsupportedNoteMetadataIsRejected() {
        XCTAssertFalse(CookNoteMetadata(version: 3).isValid)
        XCTAssertFalse(CookNoteMetadata(action: "unknown").isValid)
        XCTAssertFalse(CookNoteMetadata(dishIds: ["a", "a"]).isValid)
    }
}
