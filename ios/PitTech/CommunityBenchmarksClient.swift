import Foundation

// MARK: - Typed community aggregates transport

enum NativeCommunityError: LocalizedError {
    case invalid
    case unavailable
    case network
    case status(Int)

    var errorDescription: String? {
        switch self {
        case .invalid: return "PitTech couldn't read those community figures. Your local cookbook is unchanged."
        case .unavailable: return "Community benchmarks aren't available on this server yet. Your local cookbook is unchanged."
        case .network: return "Couldn't reach PitTech. Retry the same request when you're online."
        case .status(401): return "Sign in again to see community benchmarks. Your local cookbook is unchanged."
        case .status(403): return "Community benchmarks need PitTech Pro on this account."
        default: return "Community benchmarks are unavailable right now. Your local cookbook is unchanged."
        }
    }
}

protocol NativeCommunityBenchmarksAPI {
    func aggregates(bucket: String?, metric: String?, runId: String?, access: String?) async throws -> Data
    func jobRuns(limit: Int?, cursor: String?, access: String?) async throws -> Data
}

/// Pro-authorized aggregate reads behind the same transport idiom as the
/// subscription client: Bearer [REDACTED] session, no-store, bounded responses, no
/// cookies or redirects. Filters validate client-side before any request.
struct NativeCommunityBenchmarksClient: NativeCommunityBenchmarksAPI {
    var configuration: URLSessionConfiguration?

    /// Latest runs carry every recorded cell plus curve intervals, so the
    /// bound is roomier than the subscription client's without being open.
    private let maximumResponseBytes = 262_144

    func aggregates(bucket: String? = nil, metric: String? = nil, runId: String? = nil, access: String? = nil) async throws -> Data {
        if let bucket, !CommunityBenchmarks.bucketAllowed(bucket) { throw NativeCommunityError.invalid }
        if let metric, !CommunityBenchmarks.metricAllowed(metric) { throw NativeCommunityError.invalid }
        if let runId, !CommunityBenchmarks.runIdValid(runId) { throw NativeCommunityError.invalid }
        guard let access, NativeAccountProtocol.credential(access) else { throw NativeCommunityError.invalid }
        var components = URLComponents(string: "\(NativeOnlineConfiguration.current.apiOrigin)" + CommunityBenchmarks.aggregatesPath)!
        var items: [URLQueryItem] = []
        if let bucket { items.append(URLQueryItem(name: "bucket", value: bucket)) }
        if let metric { items.append(URLQueryItem(name: "metric", value: metric)) }
        if let runId { items.append(URLQueryItem(name: "runId", value: runId)) }
        components.queryItems = items.isEmpty ? nil : items
        return try await get(components: components, access: access)
    }

    func jobRuns(limit: Int? = nil, cursor: String? = nil, access: String? = nil) async throws -> Data {
        if let limit, !CommunityBenchmarks.jobRunsLimitValid(limit) { throw NativeCommunityError.invalid }
        if let cursor, !CommunityBenchmarks.runIdValid(cursor) { throw NativeCommunityError.invalid }
        guard let access, NativeAccountProtocol.credential(access) else { throw NativeCommunityError.invalid }
        var components = URLComponents(string: "\(NativeOnlineConfiguration.current.apiOrigin)" + CommunityBenchmarks.jobRunsPath)!
        var items: [URLQueryItem] = []
        if let limit { items.append(URLQueryItem(name: "limit", value: String(limit))) }
        if let cursor { items.append(URLQueryItem(name: "cursor", value: cursor)) }
        components.queryItems = items.isEmpty ? nil : items
        return try await get(components: components, access: access)
    }

    private func get(components: URLComponents, access: String) async throws -> Data {
        guard let url = components.url else { throw NativeCommunityError.invalid }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
        do {
            let result = try await NativeRelayTransport(configuration: configuration, responseError: { NativeCommunityError.status($0) }, maximumResponseBytes: Int64(maximumResponseBytes)).run(request)
            guard result.data.count <= maximumResponseBytes else { throw NativeCommunityError.invalid }
            return result.data
        } catch let error as NativeCommunityError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw NativeCommunityError.network
        }
    }
}
