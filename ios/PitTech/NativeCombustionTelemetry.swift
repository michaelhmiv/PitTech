import Foundation
import CombustionBLE

@MainActor final class NativeCombustionConnector: NativeReadOnlyTelemetry {
    private var active = false
    var minimumInterval: Double { 15_000 }
    func discover() async throws -> [NativeTelemetrySource] {
        if !active { DeviceManager.shared.initBluetooth(); active = true }
        try await Task.sleep(for: .seconds(5))
        return DeviceManager.shared.getProbes().map { probe in
            .init(provider: .combustion, id: probe.serialNumberString, label: probe.name,
                  channels: [.init(id: "core", name: "Core", kind: .food), .init(id: "surface", name: "Surface", kind: .food), .init(id: "ambient", name: "Ambient", kind: .ambient), .init(id: "instant", name: "Instant-read", kind: .food)])
        }
    }
    func snapshot(_ device: String) async throws -> [NativeTelemetryObservation] {
        if !active { DeviceManager.shared.initBluetooth(); active = true }
        guard let probe = DeviceManager.shared.getProbes().first(where: { $0.serialNumberString == device }) else { return [] }
        let receipt = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
        let values = probe.virtualTemperatures
        return [("core", values?.coreTemperature), ("surface", values?.surfaceTemperature), ("ambient", values?.ambientTemperature), ("instant", probe.instantReadCelsius)].map { channel, temp in
            .init(channel: channel, value: temp, unit: "°C", observedAt: nil, receivedAt: receipt, available: temp?.isFinite == true && !probe.stale, eventId: nil)
        }
    }
    // SDK session logs need a validated wall-clock anchor before any backfill can be recorded.
    func disconnect() { active = false }
}
