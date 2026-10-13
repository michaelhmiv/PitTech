import XCTest
@testable import PitTech

final class NativeTelemetryTests: XCTestCase {
    func testNormalizationUsesSourceTimestampsAndNeverInventsHistory() {
        let c = NativeTelemetryObservation(channel: "internal", value: 100, unit: "°C", observedAt: 1000, receivedAt: 2000, eventId: "sample")
        XCTAssertEqual(c.normalized?.value, 212)
        XCTAssertEqual(c.normalized?.at, 1000)
        XCTAssertNil(NativeTelemetryObservation(channel: "p", value: .nan, unit: "°C", observedAt: nil, receivedAt: 2000, eventId: nil).normalized)
        XCTAssertNil(NativeTelemetryObservation(channel: "p", value: 100, unit: "°C", observedAt: nil, receivedAt: 2000, eventId: nil, history: true).normalized)
        XCTAssertNotEqual(NativeTelemetryIdentity.key("ab", "c"), NativeTelemetryIdentity.key("a", "bc"))
    }
    func testHaUrlsCannotRedirectTokensToInsecureOrUnexpectedEndpoints() throws {
        XCTAssertEqual(try NativeTelemetryEndpoint.homeAssistant("https://ha.example.com:8123").host, "ha.example.com")
        for url in ["http://ha.example.com", "https://user:password@ha.example.com", "https://ha.example.com/api/services", "https://ha.example.com?target=other"] { XCTAssertThrowsError(try NativeTelemetryEndpoint.homeAssistant(url)) }
    }
    @MainActor func testMixedSourceArchiveAndDelayedRoleHistoryRemainIdempotent() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        store.startCook(title: "Telemetry", firstDish: "Brisket")
        let cook = store.library.cooks[0].id, food = store.dishRecords(cook)[0].id
        let start = Double(store.library.cooks[0].startedAtUtcMillis)
        let ha = NativeTelemetrySource(provider: .home_assistant, id: "sensor.private_id", label: "Probe", scope: "https://home.example.com", channels: [.init(id: "temperature", name: "Food", kind: .food)])
        let meater = NativeTelemetrySource(provider: .meater, id: "private_probe_id", label: "MEATER", channels: [.init(id: "internal", name: "Food", kind: .food)])
        try store.attachTelemetry(ha, cookId: cook, assignments: ["temperature": food], at: start + 1)
        try store.attachTelemetry(meater, cookId: cook, assignments: ["internal": food], at: start + 1)
        let sample = NativeTelemetryObservation(channel: "temperature", value: 90, unit: "°C", observedAt: start + 2, receivedAt: start + 10, eventId: "same-sample", history: true)
        XCTAssertEqual(try store.ingestTelemetry(ha, cookId: cook, observations: [sample]), 1)
        XCTAssertEqual(try store.ingestTelemetry(ha, cookId: cook, observations: [sample]), 0)
        var chamber = ha; chamber.channels[0].kind = .chamber
        try store.attachTelemetry(chamber, cookId: cook, assignments: [:], at: start + 5)
        var older = sample; older.eventId = "delayed"
        XCTAssertEqual(try store.ingestTelemetry(chamber, cookId: cook, observations: [older]), 1)
        XCTAssertEqual(store.library.readings.last?.objectValue?["dishId"]?.stringValue, food)
        XCTAssertEqual(store.library.readings.last?.objectValue?["measurementType"]?.stringValue, "food_probe")
        XCTAssertFalse(store.library.devices.contains { $0.objectValue?["id"]?.stringValue?.contains("private") == true })
        let restored = CookStore(directory: folder.appendingPathComponent("restored"))
        let bytes = try store.exportArchive(); _ = try restored.importArchive(bytes); _ = try restored.importArchive(bytes)
        XCTAssertEqual(restored.library.readings.count, 2)
        XCTAssertEqual(restored.library.devices.count, 2)
        try store.detachTelemetry(chamber, cookId: cook)
        var later = sample; later.observedAt = start + 20; later.receivedAt = start + 20; later.eventId = "later"
        XCTAssertEqual(try store.ingestTelemetry(chamber, cookId: cook, observations: [later]), 0)
        store.finishCook(cook)
        XCTAssertEqual(try store.ingestTelemetry(meater, cookId: cook, observations: [later]), 0)
    }
    @MainActor func testMeaterAndFireBoardSchemaPreservesUnavailableAndOriginalUnits() {
        let now = 1_760_000_000_000.0
        let meater: [String: Any] = ["id": "probe", "updated_at": now / 1000, "temperature": ["internal": 100.0, "ambient": 120.0]]
        let observations = NativeTelemetryCloudConnector.observations(provider: .meater, item: meater, receipt: now)
        XCTAssertEqual(observations[0].normalized?.value, 212)
        XCTAssertNil(observations.last?.normalized)
        let stale = NativeTelemetryCloudConnector.observations(provider: .meater, item: meater, receipt: now + 200_000)
        XCTAssertNil(stale[0].normalized)
        let fireboard: [String: Any] = ["latest_temps": [["channel": 2, "degreetype": 2, "created": "2026-10-12T20:00:00Z", "temp": 201.0]]]
        let at = NativeTelemetryEndpoint.millis("2026-10-12T20:00:00Z")!
        let fb = NativeTelemetryCloudConnector.observations(provider: .fireboard, item: fireboard, receipt: at)
        XCTAssertEqual(fb.first?.channel, "channel2"); XCTAssertEqual(fb.first?.unit, "°F")
    }
    @MainActor func testFailedCloudCallsConsumeQuotaAcrossDeviceReads() async {
        var calls = 0; let key = UUID().uuidString
        do { _ = try await NativeTelemetryCloudCache.shared.read(key: key, interval: 30_000, now: 1000) { calls += 1; throw NativeTelemetryError.network }; XCTFail() } catch {}
        do { _ = try await NativeTelemetryCloudCache.shared.read(key: key, interval: 30_000, now: 2000) { calls += 1; return Data() }; XCTFail() } catch {}
        XCTAssertEqual(calls, 1)
    }
}
