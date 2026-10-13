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
        if !active { connectors.values.forEach { $0.disconnect() }; connectors.removeAll() }
        store.telemetryOnLog = { [weak self] cook in Task { await self?.onLog(cook) } }
        refreshLiveDevices()
        restart()
    }
    func persist() throws {
        try JSONEncoder().encode(profiles).write(to: configuration, options: .atomic)
        var values = URLResourceValues(); values.isExcludedFromBackup = true; var url = configuration; try url.setResourceValues(values)
        refreshLiveDevices()
        restart()
    }
    private func refreshLiveDevices() {
        guard let store else { return }
        store.telemetryLiveDevices = visible ? Set(profiles.filter { $0.monitoring }.flatMap { profile in
            profile.sources.filter { profile.selected.contains($0.id) && messages[profile.id + ":" + $0.id]?.hasPrefix("Connected") == true && store.library.cooks.first(where: { $0.id == profile.cookId })?.status == "active" }.map { NativeTelemetryIdentity.device(profile.cookId ?? "", $0) }
        }) : []
    }
    func connect(_ profile: NativeTelemetryProfile, user: String, secret: String) async throws {
        if profile.provider == .home_assistant { _ = try NativeTelemetryEndpoint.homeAssistant(profile.endpoint) }
        if profile.provider == .flame_boss { _ = try NativeFlameBossBroker(profile.endpoint) }
        if profile.provider == .fireboard && !profile.deviceHint.isEmpty && profile.deviceHint.range(of: "^[0-9]{1,18}$", options: .regularExpression) == nil { throw NativeTelemetryError.configuration }
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
        if let cloud = connector as? NativeTelemetryCloudConnector { cloud.configure(saved) }
        profiles.removeAll { $0.id == profile.id }; profiles.append(saved); try persist(); messages[profile.id] = "Connected · read-only"
    }
    private func factory(_ profile: NativeTelemetryProfile, token: String) throws -> any NativeReadOnlyTelemetry {
        switch profile.provider {
        case .meater, .fireboard:
            guard !token.isEmpty else { throw NativeTelemetryError.authorization }
            return NativeTelemetryCloudConnector(profile: profile, token: token)
        case .home_assistant: return try NativeHomeAssistantConnector(profile: profile, credential: token)
        case .combustion: return NativeCombustionConnector()
        case .flame_boss: return try NativeFlameBossConnector(profile: profile, credential: token)
        }
    }
    private func connector(_ profile: NativeTelemetryProfile) throws -> any NativeReadOnlyTelemetry {
        if let current = connectors[profile.id] { return current }
        let current = try factory(profile, token: try vault.load(profile.id) ?? ""); connectors[profile.id] = current; return current
    }
    func attach(_ profile: NativeTelemetryProfile, source: NativeTelemetrySource, cook: String, assignments: [String: String?], store: CookStore) async throws {
        try store.attachTelemetry(source, cookId: cook, assignments: assignments)
        guard let index = profiles.firstIndex(where: { $0.id == profile.id }) else { return }
        if let oldCook = profiles[index].cookId, oldCook != cook {
            for oldSource in profiles[index].sources where profiles[index].selected.contains(oldSource.id) { try store.detachTelemetry(oldSource, cookId: oldCook) }
            profiles[index].selected = []
        }
        profiles[index].cookId = cook; profiles[index].monitoring = true
        profiles[index].sources.removeAll { $0.id == source.id }; profiles[index].sources.append(source)
        if !profiles[index].selected.contains(source.id) { profiles[index].selected.append(source.id) }
        try persist()
        let readings = try await connector(profiles[index]).snapshot(source.id)
        try store.ingestTelemetry(source, cookId: cook, observations: readings, samplingInterval: profiles[index].onLogOnly ? 0 : max(profiles[index].interval, connector(profiles[index]).minimumInterval))
    }
    func remove(_ profile: NativeTelemetryProfile, store: CookStore) async throws {
        let adapter = try? connector(profile)
        if let cook = profile.cookId { for source in profile.sources where profile.selected.contains(source.id) { try store.detachTelemetry(source, cookId: cook) } }
        profiles.removeAll { $0.id == profile.id }; try persist(); connectors[profile.id] = nil
        defer { adapter?.disconnect(); vault.clear(profile.id) }
        // Recording is stopped locally before attempting best-effort server revocation.
        try? await adapter?.revoke()
    }
    func update(_ profile: NativeTelemetryProfile) throws {
        if let index = profiles.firstIndex(where: { $0.id == profile.id }) {
            if profiles[index].monitoring != profile.monitoring, let cook = profile.cookId, let store {
                for source in profile.sources where profile.selected.contains(source.id) { try store.setTelemetryMonitoring(source, cookId: cook, enabled: profile.monitoring) }
            }
            profiles[index] = profile; try persist()
        }
    }
    func onLog(_ cook: String) async {
        await withTaskGroup(of: Void.self) { group in
            for profile in profiles where profile.cookId == cook && profile.monitoring && profile.onLogOnly { group.addTask { @MainActor [weak self] in await self?.collect(profile, recover: false) } }
        }
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
                        var nextRecovery = 0.0
                        while !Task.isCancelled {
                            guard self.store?.library.cooks.first(where: { $0.id == profile.cookId })?.status == "active" else { return }
                            let now = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
                            await self.collect(profile, recover: now >= nextRecovery)
                            if now >= nextRecovery { nextRecovery = now + 300_000 }
                            guard self.profiles.first(where: { $0.id == profile.id })?.monitoring == true else { return }
                            let minimum = (try? self.connector(profile).minimumInterval) ?? 30_000
                            try? await Task.sleep(for: .milliseconds(max(profile.interval, minimum)))
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
            var valid = false, historyUnavailable = false
            for source in profile.sources where profile.selected.contains(source.id) {
                if recover && adapter.supportsHistory {
                    do { try await backfill(profile, source: source, adapter: adapter, store: store) }
                    catch is CancellationError { return }
                    catch { historyUnavailable = true }
                }
                let batch = try await adapter.snapshot(source.id)
                if Task.isCancelled { return }
                messages[profile.id + ":" + source.id] = batch.contains { $0.normalized != nil } ? "Connected" : "Unavailable or stale"
                refreshLiveDevices()
                try store.ingestTelemetry(source, cookId: cook, observations: batch, samplingInterval: profile.onLogOnly ? 0 : max(profile.interval, adapter.minimumInterval))
                valid = valid || batch.contains { $0.normalized != nil }
                messages[profile.id + ":" + source.id] = batch.contains { $0.normalized != nil } ? "Connected" : "Unavailable or stale"
            }
            messages[profile.id] = valid ? (historyUnavailable ? "Connected · history unavailable" : "Connected") : "Unavailable or stale"
        } catch is CancellationError { return }
        catch NativeTelemetryError.authorization {
            for source in profile.sources { messages[profile.id + ":" + source.id] = "Sign in again" }; refreshLiveDevices()
            messages[profile.id] = NativeTelemetryError.authorization.localizedDescription
            if var current = profiles.first(where: { $0.id == profile.id }) { current.monitoring = false; try? update(current) }
        }
        catch { for source in profile.sources { messages[profile.id + ":" + source.id] = "Reconnecting…" }; refreshLiveDevices(); messages[profile.id] = error is NativeTelemetryError ? error.localizedDescription : "Reconnecting…" }
    }
    private func backfill(_ profile: NativeTelemetryProfile, source: NativeTelemetrySource, adapter: any NativeReadOnlyTelemetry, store: CookStore) async throws {
        guard let cook = profile.cookId else { return }
        let now = Double(Int64(Date.now.timeIntervalSince1970 * 1000)), device = NativeTelemetryIdentity.device(cook, source)
        let pending = store.pendingTelemetryRecovery(source, cook: cook)
        let probes = store.library.probes.compactMap(\.objectValue).filter { $0["deviceId"]?.stringValue == device }
        let readings = store.library.readings.compactMap(\.objectValue)
        let watermark = probes.compactMap { probe -> Double? in
            readings.filter { $0["probeId"]?.stringValue == probe["id"]?.stringValue }.compactMap { $0["measuredAtUtcMillis"]?.numberValue }.max() ?? probe["createdAtUtcMillis"]?.numberValue
        }.min()
        guard let previous = pending?.lowerBound ?? watermark else { return }
        let until = pending?.upperBound ?? now
        var from = max(previous, until - 864_000_000)
        guard until > from else { return }
        try store.saveTelemetryRecovery(source, cook: cook, from: from, until: until)
        var requests = 0
        func window(_ start: Double, _ end: Double, depth: Int) async throws -> Bool {
            if Task.isCancelled { throw CancellationError() }
            guard requests < 80 else { return false }; requests += 1
            let values = try await adapter.history(source.id, from: start, until: end)
            if values.count >= 20_000 && end - start > 1000 && depth < 16 {
                let middle = start + (end - start) / 2
                guard try await window(start, middle, depth: depth + 1) else { return false }
                return try await window(middle, end, depth: depth + 1)
            }
            try store.ingestTelemetry(source, cookId: cook, observations: values.map { var item = $0; item.history = true; return item })
            try store.saveTelemetryRecovery(source, cook: cook, from: end, until: until)
            return true
        }
        var windows = 0
        while from < until && windows < 40 {
            let end = min(until, from + 21_600_000)
            guard try await window(from, end, depth: 0) else { return }
            from = end; windows += 1
        }
        try store.saveTelemetryRecovery(source, cook: cook, from: until, until: until, complete: true)
        if pending != nil && until < now { try store.saveTelemetryRecovery(source, cook: cook, from: until, until: now) }
    }
}
