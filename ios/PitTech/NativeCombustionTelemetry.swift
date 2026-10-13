import Foundation
import CombustionBLE

@MainActor final class NativeCombustionConnector: NativeReadOnlyTelemetry {
    private var active = false
    var minimumInterval: Double { 30_000 }
    func discover() async throws -> [NativeTelemetrySource] {
        if !active { DeviceManager.shared.initBluetooth(); active = true }
        try await Task.sleep(for: .seconds(5))
        return DeviceManager.shared.getProbes().map { probe in
            .init(provider: .combustion, id: probe.serialNumberString, label: probe.name,
                  channels: Self.channels)
        }
    }
    func snapshot(_ device: String) async throws -> [NativeTelemetryObservation] {
        if !active { DeviceManager.shared.initBluetooth(); active = true }
        guard let probe = DeviceManager.shared.getProbes().first(where: { $0.serialNumberString == device }) else { return [] }
        let receipt = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
        let values = probe.virtualTemperatures
        return Self.observations(raw: probe.currentTemperatures?.values ?? [], core: values?.coreTemperature,
                                 surface: values?.surfaceTemperature, ambient: values?.ambientTemperature,
                                 instant: probe.instantReadCelsius, receipt: receipt, stale: probe.stale)
    }
    static var channels: [NativeTelemetryChannel] {
        (0..<8).map { .init(id: "sensor\($0)", name: "Raw sensor \($0 + 1)", kind: $0 == 0 ? .food : $0 == 7 ? .ambient : .other) } +
        [.init(id: "core", name: "Core", kind: .food), .init(id: "surface", name: "Surface", kind: .food),
         .init(id: "ambient", name: "Ambient", kind: .ambient), .init(id: "instant", name: "Instant-read", kind: .food)]
    }
    static func observations(raw: [Double], core: Double?, surface: Double?, ambient: Double?, instant: Double?, receipt: Double, stale: Bool) -> [NativeTelemetryObservation] {
        let values: [(String, Double?)] = raw.prefix(8).enumerated().map { ("sensor\($0.offset)", $0.element) } +
            [("core", core), ("surface", surface), ("ambient", ambient), ("instant", instant)]
        return values.map { channel, temp in
            .init(channel: channel, value: temp, unit: "°C", observedAt: nil, receivedAt: receipt, available: temp?.isFinite == true && !stale, eventId: nil)
        }
    }
    // SDK 1.0.0 has no public bounded log-request or stop-scanner API. Its automatic
    // log transfer/repeater mode cannot meet this adapter's bounded radio contract.
    // Keep those capabilities unavailable instead of issuing unrestricted transfers.
    func disconnect() { active = false }
}
