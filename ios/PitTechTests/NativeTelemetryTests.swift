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
    func testHaOAuthCallbackRequiresExactOriginAndState() throws {
        let request = try NativeHAAuthRequest(server: "https://HOME.example.com:443/", state: "nonce")
        XCTAssertEqual(request.origin.absoluteString, "https://home.example.com")
        XCTAssertEqual(try request.code(URL(string: NativeHASession.callback + "?code=valid&state=nonce")!), "valid")
        XCTAssertThrowsError(try request.code(URL(string: NativeHASession.callback + "?code=valid&state=other")!))
        XCTAssertNil(try request.code(URL(string: "https://pittech-privacy-production.up.railway.app.evil.example/ha/auth_callback?code=valid&state=nonce")!))
        XCTAssertFalse(request.allows(URL(string: "https://home.example.com.evil.example/auth/authorize")!))
    }
    func testMqttGuardAndManufacturerFrameDoNotAcceptBooleanTemperatures() throws {
        XCTAssertThrowsError(try NativeFlameBossBroker("mqtt://myflameboss.com:1883"))
        XCTAssertThrowsError(try NativeFlameBossBroker("mqtts://myflameboss.com.evil.example:8883"))
        XCTAssertEqual(try NativeFlameBossCodec.subscribe("8838").first, 0x82)
        let frame: [String: Any] = ["name": "temps", "sec": 1621028684, "temps": [298, 249, -32767, true], "set_temp": 1056]
        let readings = NativeFlameBossCodec.observations(frame, receipt: 1621028685000)
        XCTAssertEqual(readings[0].value, 29.8)
        XCTAssertEqual(readings[1].value, 24.9)
        XCTAssertNil(readings[2].normalized); XCTAssertNil(readings[3].normalized)
        XCTAssertEqual(readings.last?.value, 105.6)
        XCTAssertNil(NativeTelemetryEndpoint.number(true))
    }
    @MainActor func testFireBoardHistoryMapsUniqueLabelsAndSharesLiveEventIdentity() {
        let at = NativeTelemetryEndpoint.millis("2026-10-12T20:00:00Z")!
        let channels = [NativeTelemetryChannel(id: "channel2", name: "Brisket", kind: .food)]
        let chart: [[String: Any]] = [["device": "device", "label": "Brisket", "degreetype": 2, "x": [at / 1000], "y": [201.0]]]
        let readings = NativeTelemetryCloudConnector.fireBoardHistory(chart, device: "device", channels: channels, from: at - 1000, until: at + 1000, receipt: at + 2000)
        XCTAssertEqual(readings.first?.channel, "channel2")
        XCTAssertEqual(readings.first?.eventId, String(Int64(at)))
        XCTAssertTrue(readings.first?.history == true)
        XCTAssertTrue(NativeTelemetryCloudConnector.fireBoardHistory(chart, device: "other", channels: channels, from: at - 1000, until: at + 1000, receipt: at + 2000).isEmpty)
        XCTAssertTrue(NativeTelemetryCloudConnector.fireBoardHistory(chart, device: "device", channels: channels + [.init(id: "channel3", name: "Brisket", kind: .food)], from: at - 1000, until: at + 1000, receipt: at + 2000).isEmpty)
    }
    @MainActor func testExplicitPauseRejectsHistoricalSamplesAndKeepsRecoveryCursor() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder); store.startCook(title: "Pause", firstDish: "Brisket")
        let cook = store.library.cooks[0].id, start = Double(store.library.cooks[0].startedAtUtcMillis)
        let source = NativeTelemetrySource(provider: .home_assistant, id: "sensor.probe", label: "Probe", channels: [.init(id: "temperature", name: "Food", kind: .food)])
        try store.attachTelemetry(source, cookId: cook, assignments: [:], at: start)
        try store.setTelemetryMonitoring(source, cookId: cook, enabled: false, at: start + 10)
        try store.setTelemetryMonitoring(source, cookId: cook, enabled: true, at: start + 30)
        let readings = [5.0, 20, 40].map { NativeTelemetryObservation(channel: "temperature", value: 180, unit: "°F", observedAt: start + $0, receivedAt: start + 50, eventId: String($0), history: true) }
        XCTAssertEqual(try store.ingestTelemetry(source, cookId: cook, observations: readings), 2)
        XCTAssertFalse(store.events(for: cook).contains { $0.title == "Temperature monitoring" })
        try store.saveTelemetryRecovery(source, cook: cook, from: start + 10, until: start + 100)
        let restored = CookStore(directory: folder)
        XCTAssertEqual(restored.pendingTelemetryRecovery(source, cook: cook)?.lowerBound, start + 10)
    }

    func testProbeReminderRequiresFreshConnectedSamplesSustainedAcrossTime() throws {
        let config: [String: JSONValue] = ["thresholdF": .number(185), "direction": .string("above"), "sustainMillis": .number(60_000), "hysteresisF": .number(2)]
        func sample(_ at: Double, _ value: Double = 190) -> TemperatureSample {
            TemperatureSample(json: .object(["id": .string(String(at)), "cookId": .string("cook"), "probeName": .string("Brisket"), "measurementType": .string("food_probe"), "value": .number(value), "unit": .string("°F"), "measuredAtUtcMillis": .number(at), "source": .string("telemetry:fireboard"), "samplingIntervalMillis": .number(30_000)]))!
        }
        var state: [String: JSONValue] = [:]
        XCTAssertNil(NativeProbeReminder.due(config: config, sample: sample(100_000), eligible: true, now: 100_000, state: &state))
        XCTAssertEqual(NativeProbeReminder.due(config: config, sample: sample(160_000), eligible: true, now: 160_000, state: &state), 160_000)
        state["dueAtUtcMillis"] = .number(160_000)
        XCTAssertEqual(NativeProbeReminder.due(config: config, sample: sample(180_000, 184), eligible: true, now: 180_000, state: &state), 160_000)
        XCTAssertNil(NativeProbeReminder.due(config: config, sample: sample(190_000, 182), eligible: true, now: 190_000, state: &state))
        XCTAssertNil(NativeProbeReminder.due(config: config, sample: sample(200_000), eligible: false, now: 200_000, state: &state))
        XCTAssertNil(NativeProbeReminder.due(config: config, sample: sample(200_000), eligible: true, now: 600_000, state: &state))
        XCTAssertNil(state["badSinceUtcMillis"]?.numberValue)
    }

    @MainActor func testDefaultTelemetryTimestampMetadataExportsAsIntegerMilliseconds() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder); store.startCook(title: "Default time", firstDish: "Food")
        let cook = store.library.cooks[0].id
        let source = NativeTelemetrySource(provider: .meater, id: "probe", label: "MEATER", channels: [.init(id: "internal", name: "Food", kind: .food)])
        try store.attachTelemetry(source, cookId: cook, assignments: [:])
        let now = Double(Int64(Date.now.timeIntervalSince1970 * 1000)) + 1
        try store.ingestTelemetry(source, cookId: cook, observations: [.init(channel: "internal", value: 180, unit: "°F", observedAt: now + 0.125, receivedAt: now + 1, eventId: "fixture")])
        let exported = try store.exportArchive()
        let restored = CookStore(directory: folder.appendingPathComponent("restored")); _ = try restored.importArchive(exported)
        XCTAssertEqual(restored.library.readings.count, 1)
        XCTAssertEqual(restored.library.readings[0].objectValue?["measuredAtUtcMillis"]?.numberValue, now)
    }

    @MainActor func testOneMissingSensorCannotStopAnotherSensorOnTheSameConnection() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder); store.startCook(title: "Sensor isolation", firstDish: "Food")
        let cook = store.library.cooks[0].id
        final class Fixture: NativeReadOnlyTelemetry {
            var failing = false
            let sources = ["sensor.missing", "sensor.healthy"].map { NativeTelemetrySource(provider: .home_assistant, id: $0, label: $0, scope: "https://home.example.com", channels: [.init(id: "temperature", name: "Food", kind: .food)]) }
            func discover() async throws -> [NativeTelemetrySource] { sources }
            func snapshot(_ device: String) async throws -> [NativeTelemetryObservation] {
                if !failing { return [] }
                if device == "sensor.missing" { throw NativeTelemetryError.configuration }
                let now = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
                return [.init(channel: "temperature", value: 180, unit: "°F", observedAt: now, receivedAt: now, eventId: String(Int64(now)))]
            }
            func disconnect() {}
        }
        let adapter = Fixture(), runtime = NativeTelemetryRuntime(directory: folder, adapterFactory: { _, _ in adapter })
        runtime.foreground(true, store: store)
        let profile = NativeTelemetryProfile(provider: .home_assistant, endpoint: "https://home.example.com", onLogOnly: true)
        try await runtime.connect(profile, user: "", secret: "fixture-token")
        for source in adapter.sources { try await runtime.attach(runtime.profiles[0], source: source, cook: cook, assignments: [:], store: store) }
        adapter.failing = true
        await runtime.onLog(cook)
        XCTAssertEqual(store.library.readings.count, 1)
        XCTAssertEqual(runtime.messages[profile.id + ":sensor.healthy"], "Connected")
        XCTAssertEqual(runtime.messages[profile.id + ":sensor.missing"], NativeTelemetryError.configuration.localizedDescription)
        try await runtime.remove(runtime.profiles[0], store: store); runtime.foreground(false, store: store)
    }

}
