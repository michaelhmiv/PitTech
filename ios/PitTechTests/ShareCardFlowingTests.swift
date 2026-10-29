import XCTest
import UIKit
import Vision
@testable import PitTech

/// Mirrors Android's ShareCardFlowingTest + the Slice 3 additions to
/// ShareCardIntegrationTest (Flowing Slice 3, Spread 07): the
/// publication card variant (footer slot, canonical URL, both-or-
/// neither counts, relay exclusivity, UUID validation), the
/// manual-logger variant (drawable-curve rule, timezone-labeled chips,
/// the twelve-chip even sampling), and the two render proofs — the
/// rendered publication card's QR decodes to the canonical URL, and
/// the manual variant renders for a curve-less hand-logged cook.
@MainActor final class ShareCardFlowingTests: XCTestCase {
    private let itemId = "123e4567-e89b-12d3-a456-426614174000"
    /// Oct 12, 2026 10:05 UTC = 6:05 AM in America/New_York.
    private let base = 1_791_799_500_000.0

    private func reading(_ id: String, source: String, value: Double, atMillis: Double, type: String = "food", sampling: Double = 0) -> JSONValue {
        .object(["id": .string(id), "probeId": .string("probe-1"), "probeName": .string("Probe 1"),
                 "measurementType": .string(type), "value": .number(value), "unit": .string("°F"),
                 "measuredAtUtcMillis": .number(atMillis), "timeZoneId": .string("America/New_York"),
                 "source": .string(source), "samplingIntervalMillis": .number(sampling), "qualityStatus": .string("valid")])
    }

    private func document(readings: [JSONValue]) throws -> NativeSharingDocument {
        let snapshot = RecipeSnapshot(version: 1, name: "Pork", description: "", tags: [],
                                      draft: .object(["dishes": .array([.object(["name": .string("Pork")])])]),
                                      instructions: [], observations: [], targets: [], resultNotes: "",
                                      rating: nil, smokingAnchorUtcMillis: Int64(base), servings: "")
        let story: JSONValue = .object(["version": .number(1), "attemptId": .string("attempt-1"),
                                        "anchorUtcMillis": .number(base), "observations": .array([]),
                                        "readings": .array(readings), "results": .array([])])
        var entry = NativeSharingEntry(snapshotJson: try recipeJSON(snapshot), story: story)
        entry.includeStory = true
        return NativeSharingDocument(title: "Pepper pork", entries: [entry], card: ShareCardOptions())
    }

    /// Two device readings fifteen seconds apart on one channel at a
    /// 15-second sampling interval: one segment of two points — a
    /// drawable curve.
    private func deviceReadings() -> [JSONValue] {
        [reading("d1", source: "device", value: 100, atMillis: base, sampling: 15_000),
         reading("d2", source: "device", value: 110, atMillis: base + 15_000, sampling: 15_000)]
    }

    func testPublicationSlotCarriesCanonicalUrlAndCounts() throws {
        let doc = try document(readings: deviceReadings())
        let card = try NativeShareCardContent.build(doc, photo: nil, publication: ShareCardPublication(itemId: itemId, likes: 12, forks: 3))
        let root = try XCTUnwrap(card.content.objectValue)
        XCTAssertEqual(root["relay"], JSONValue.null)
        let publication = try XCTUnwrap(root["publication"]?.objectValue)
        XCTAssertEqual(publication["url"]?.stringValue, "https://pittech.app/c/\(itemId)")
        XCTAssertEqual(publication["likes"]?.numberValue, 12)
        XCTAssertEqual(publication["forks"]?.numberValue, 3)
    }

    func testPublicationWithoutCountsCarriesUrlOnly() throws {
        let doc = try document(readings: deviceReadings())
        let card = try NativeShareCardContent.build(doc, photo: nil, publication: ShareCardPublication(itemId: itemId))
        let publication = try XCTUnwrap(card.content.objectValue?["publication"]?.objectValue)
        XCTAssertEqual(publication["url"]?.stringValue, "https://pittech.app/c/\(itemId)")
        XCTAssertNil(publication["likes"])
        XCTAssertNil(publication["forks"])
    }

    func testPublicationValidationRejectsBadIdentityAndHalfCounts() throws {
        let doc = try document(readings: deviceReadings())
        XCTAssertThrowsError(try NativeShareCardContent.build(doc, photo: nil, publication: ShareCardPublication(itemId: "not-a-uuid")))
        XCTAssertThrowsError(try NativeShareCardContent.build(doc, photo: nil, publication: ShareCardPublication(itemId: itemId, likes: 12)))
        XCTAssertThrowsError(try NativeShareCardContent.build(doc, photo: nil, publication: ShareCardPublication(itemId: itemId, forks: 3)))
        XCTAssertThrowsError(try NativeShareCardContent.build(doc, photo: nil, publication: ShareCardPublication(itemId: itemId, likes: -1, forks: 0)))
    }

    func testRelayAndPublicationTogetherAreRejected() throws {
        let doc = try document(readings: deviceReadings())
        XCTAssertThrowsError(try NativeShareCardContent.build(doc, photo: nil,
                                                              relay: ShareCardRelay(code: "EMBER-OAK-7", url: "https://pittech.app/s/EMBER-OAK-7"),
                                                              publication: ShareCardPublication(itemId: itemId)),
                             "a card carries one footer: the Cook Pack code or the publication link, never both")
    }

    func testManualHeroWinsWhenNoSegmentIsDrawable() throws {
        // Hand-logged readings never join segments: four manual food
        // readings an hour apart leave only singleton segments.
        let readings = (0..<4).map { reading("m\($0)", source: "manual", value: 100 + Double($0) * 10, atMillis: base + Double($0) * 3_600_000) }
        let card = try NativeShareCardContent.build(try document(readings: readings), photo: nil)
        let root = try XCTUnwrap(card.content.objectValue)
        XCTAssertEqual(root["hero"]?.stringValue, "manual")
        let temps = (root["manualTemps"]?.arrayValue ?? []).compactMap(\.objectValue)
        XCTAssertEqual(temps.count, 4)
        XCTAssertEqual(temps.first?["label"]?.stringValue, "6:05 AM", "the chip clock reads in the reading's own timezone")
        XCTAssertEqual(temps.first?["value"]?.stringValue, "100 °F")
        XCTAssertEqual(temps.last?["label"]?.stringValue, "9:05 AM")
        XCTAssertEqual(temps.last?["value"]?.stringValue, "130 °F")
    }

    func testManualSamplingCapsAtTwelveEvenlySpaced() throws {
        let readings = (0..<14).map { reading("m\($0)", source: "manual", value: 100 + Double($0), atMillis: base + Double($0) * 3_600_000) }
        let card = try NativeShareCardContent.build(try document(readings: readings), photo: nil)
        let temps = (card.content.objectValue?["manualTemps"]?.arrayValue ?? []).compactMap(\.objectValue)
        XCTAssertEqual(temps.count, 12)
        // Even sampling over 14 readings picks source indices
        // 0,1,2,4,5,6,7,8,9,11,12,13 — the values identify them.
        XCTAssertEqual(temps.compactMap { $0["value"]?.stringValue },
                       ["100 °F", "101 °F", "102 °F", "104 °F", "105 °F", "106 °F", "107 °F", "108 °F", "109 °F", "111 °F", "112 °F", "113 °F"])
        XCTAssertEqual(temps[3]["label"]?.stringValue, "10:05 AM")
    }

    func testDrawableCurveKeepsChartHero() throws {
        var readings = deviceReadings()
        readings.append(reading("m0", source: "manual", value: 165, atMillis: base + 3_600_000))
        let card = try NativeShareCardContent.build(try document(readings: readings), photo: nil)
        XCTAssertEqual(card.content.objectValue?["hero"]?.stringValue, "chart",
                       "a drawable segment keeps the chart; manual chips never replace a real curve")
    }

    /// The gate evidence Android's ShareCardIntegrationTest carries:
    /// render a publication card, decode the footer QR from the
    /// pixels, and require the canonical URL back.
    func testRenderedPublicationCardQRDecodesToCanonicalUrl() throws {
        let card = try NativeShareCardContent.build(try document(readings: deviceReadings()), photo: nil,
                                                    publication: ShareCardPublication(itemId: itemId, likes: 12, forks: 3))
        let bytes = try NativeShareCardRenderer.render(card)
        let image = try XCTUnwrap(UIImage(data: bytes))
        XCTAssertEqual(image.cgImage?.width, 1080)
        XCTAssertEqual(image.cgImage?.height, 1350)
        let attachment = XCTAttachment(data: bytes, uniformTypeIdentifier: "public.png")
        attachment.name = "Publication share card"; attachment.lifetime = .keepAlways; add(attachment)
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        try VNImageRequestHandler(cgImage: try XCTUnwrap(image.cgImage), options: [:]).perform([request])
        let payloads = (request.results ?? []).compactMap(\.payloadStringValue)
        XCTAssertTrue(payloads.contains("https://pittech.app/c/\(itemId)"),
                      "the footer QR must decode to the canonical URL; decoded: \(payloads)")
    }

    /// The manual variant's render proof: a curve-less hand-logged
    /// cook renders a full card (badge, chips, footnote), never a
    /// blank or a thrown render.
    func testManualVariantRendersForCurveLessCook() throws {
        let readings = (0..<4).map { reading("m\($0)", source: "manual", value: 165 + Double($0) * 7, atMillis: base + Double($0) * 3_600_000) }
        let card = try NativeShareCardContent.build(try document(readings: readings), photo: nil)
        XCTAssertEqual(card.content.objectValue?["hero"]?.stringValue, "manual")
        let bytes = try NativeShareCardRenderer.render(card)
        let image = try XCTUnwrap(UIImage(data: bytes)?.cgImage)
        XCTAssertEqual(image.width, 1080)
        XCTAssertEqual(image.height, 1350)
        let attachment = XCTAttachment(data: bytes, uniformTypeIdentifier: "public.png")
        attachment.name = "Manual share card"; attachment.lifetime = .keepAlways; add(attachment)
    }
}
