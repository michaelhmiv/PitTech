import Foundation
import Observation

@MainActor @Observable final class NativeTelemetryRuntime {
    var profiles: [NativeTelemetryProfile] = []
    var messages: [String: String] = [:]
    private let vault = NativeTelemetryTokenVault()
    private let configuration: URL
    private var connectors: [String: any NativeReadOnlyTelemetry] = [:]
    private var collection: Task<Void, Never>?
    private weak var store: CookStore?
    private var visible = false
    init(directory: URL? = nil) {
        let folder = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        configuration = folder.appendingPathComponent("telemetry-connections-v1.json")
        profiles = (try? JSONDecoder().decode([NativeTelemetryProfile].self, from: Data(contentsOf: configuration))) ?? []
    }
    func foreground(_ active: Bool, store: CookStore) {
        self.store = store; visible = active
        store.telemetryOnLog = { [weak self] cook in Task { await self?.onLog(cook) } }
        restart()
    }
    func persist() throws {
        try JSONEncoder().encode(profiles).write(to: configuration, options: .atomic)
        var values = URLResourceValues(); values.isExcludedFromBackup = true; var url = configuration; try url.setResourceValues(values)
        restart()
    }
    func connect(_ profile: NativeTelemetryProfile, user: String, secret: String) async throws {
        let token: String
        if profile.provider == .combustion { token = "" }
        else if secret.isEmpty { token = try vault.load(profile.id) ?? "" }
        else if profile.provider == .home_assistant || user.isEmpty { token = secret }
        else { token = try await NativeTelemetryHTTP().authenticate(profile.provider, user: user, password: secret) }
        let connector = try factory(profile, token: token)
        let sources = try await connector.discover()
        if !token.isEmpty { try vault.save(profile.id, token) }
        connectors[profile.id]?.disconnect(); connectors[profile.id] = connector
        var saved = profile; saved.sources = sources.map { source in
            guard let previous = profile.sources.first(where: { $0.id == source.id }) else { return source }
            var mapped = source; mapped.channels = source.channels.map { channel in var ch = channel; ch.kind = previous.channels.first { $0.id == ch.id }?.kind ?? ch.kind; return ch }; return mapped
        }
        profiles.removeAll { $0.id == profile.id }; profiles.append(saved); try persist(); messages[profile.id] = "Connected · read-only"
    }
    private func factory(_ profile: NativeTelemetryProfile, token: String) throws -> any NativeReadOnlyTelemetry {
        switch profile.provider {
        case .meater, .fireboard, .home_assistant:
            guard !token.isEmpty else { throw NativeTelemetryError.authorization }
            if profile.provider == .home_assistant { _ = try NativeTelemetryEndpoint.homeAssistant(profile.endpoint) }
            return NativeTelemetryCloudConnector(profile: profile, token: token)
        case .combustion: return NativeCombustionConnector()
        case .flame_boss: throw NativeTelemetryError.unavailable
        }
    }
    private func connector(_ profile: NativeTelemetryProfile) throws -> any NativeReadOnlyTelemetry {
        if let current = connectors[profile.id] { return current }
        let current = try factory(profile, token: try vault.load(profile.id) ?? ""); connectors[profile.id] = current; return current
    }
    func attach(_ profile: NativeTelemetryProfile, source: NativeTelemetrySource, cook: String, assignments: [String: String?], store: CookStore) async throws {
        try store.attachTelemetry(source, cookId: cook, assignments: assignments)
        guard let index = profiles.firstIndex(where: { $0.id == profile.id }) else { return }
        if profiles[index].cookId != cook { profiles[index].selected = [] }
        profiles[index].cookId = cook; profiles[index].monitoring = true
        profiles[index].sources.removeAll { $0.id == source.id }; profiles[index].sources.append(source)
        if !profiles[index].selected.contains(source.id) { profiles[index].selected.append(source.id) }
        try persist()
        let readings = try await connector(profiles[index]).snapshot(source.id)
        try store.ingestTelemetry(source, cookId: cook, observations: readings)
    }
    func remove(_ profile: NativeTelemetryProfile, store: CookStore) throws {
        if let cook = profile.cookId { for source in profile.sources where profile.selected.contains(source.id) { try store.detachTelemetry(source, cookId: cook) } }
        connectors.removeValue(forKey: profile.id)?.disconnect(); vault.clear(profile.id)
        profiles.removeAll { $0.id == profile.id }; try persist()
    }
    func update(_ profile: NativeTelemetryProfile) throws { if let index = profiles.firstIndex(where: { $0.id == profile.id }) { profiles[index] = profile; try persist() } }
    func onLog(_ cook: String) async {
        for profile in profiles where profile.cookId == cook && profile.monitoring && profile.onLogOnly { await collect(profile, recover: false) }
    }
    private func restart() {
        collection?.cancel(); collection = nil
        guard visible else { return }
        collection = Task { [weak self] in
            guard let self else { return }
            await withTaskGroup(of: Void.self) { group in
                for profile in profiles where profile.monitoring && !profile.onLogOnly {
                    group.addTask { @MainActor [weak self] in
                        guard let self else { return }
                        var first = true
                        while !Task.isCancelled {
                            guard self.store?.library.cooks.first(where: { $0.id == profile.cookId })?.status == "active" else { return }
                            await self.collect(profile, recover: first); first = false
                            try? await Task.sleep(for: .milliseconds(max(profile.interval, profile.provider == .home_assistant ? 15_000 : 30_000)))
                        }
                    }
                }
            }
        }
    }
    private func collect(_ profile: NativeTelemetryProfile, recover: Bool) async {
        guard let cook = profile.cookId, let store, store.library.cooks.first(where: { $0.id == cook })?.status == "active" else { return }
        do {
            let adapter = try connector(profile)
            for source in profile.sources where profile.selected.contains(source.id) {
                if recover && profile.provider == .home_assistant {
                    let id = NativeTelemetryIdentity.device(cook, source)
                    let last = store.library.readings.compactMap(\.objectValue).filter { $0["sourceDeviceId"]?.stringValue == id }.compactMap { $0["measuredAtUtcMillis"]?.numberValue }.max()
                    if let last {
                        let now = Date.now.timeIntervalSince1970 * 1000; var from = max(last, now - 864_000_000); var windows = 0
                        while from < now && windows < 40 && !Task.isCancelled {
                            let until = min(now, from + 21_600_000)
                            try store.ingestTelemetry(source, cookId: cook, observations: await adapter.history(source.id, from: from, until: until))
                            from = until; windows += 1
                        }
                    }
                }
                try store.ingestTelemetry(source, cookId: cook, observations: await adapter.snapshot(source.id))
            }
            messages[profile.id] = "Connected"
        } catch is CancellationError { return }
        catch { messages[profile.id] = error is NativeTelemetryError ? error.localizedDescription : "Reconnecting…" }
    }
}
