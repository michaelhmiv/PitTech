import Foundation
import Observation

// MARK: - Benchmark loading state (the slice-16 gate owns access)

/// What the benchmarks entry shows once the Pro gate resolves to `.pro`.
/// Signed-out and non-Pro states never reach the loader: the gate shows the
/// paywall instead. A stale entitled cache that the server rejects (401/403)
/// converges through one entitlement refresh, then shows the paywall.
enum CommunityBenchmarksState: Equatable, Sendable {
    case loading
    case loaded(CommunityBenchmarksSnapshot)
    case empty
    case signedOut
    case forbidden
    case unavailable
}

/// Loads the latest recorded run plus its figures. Figures live only while the
/// benchmarks view is shown: the view resets on disappear, so a removed or
/// expired entitlement never keeps serving figures.
@MainActor @Observable final class CommunityBenchmarksLoader {
    private let api: any NativeCommunityAPI
    private let sessionProvider: () async -> String?
    private let onAuthMismatch: () async -> Void

    private(set) var state = CommunityBenchmarksState.loading
    private var mismatched = false

    init(
        api: any NativeCommunityAPI = NativeCommunityClient(),
        sessionProvider: @escaping () async -> String?,
        onAuthMismatch: @escaping () async -> Void = {}
    ) {
        self.api = api
        self.sessionProvider = sessionProvider
        self.onAuthMismatch = onAuthMismatch
    }

    static func forApp(account: NativeAccountCoordinator, pro: ProSubscriptionCoordinator) -> CommunityBenchmarksLoader {
        #if DEBUG
        if CommunityBenchmarksUITestSupport.active {
            return CommunityBenchmarksUITestSupport.make()
        }
        #endif
        return CommunityBenchmarksLoader(
            sessionProvider: { [account] in try? await account.access() },
            onAuthMismatch: { [pro] in await pro.refresh() }
        )
    }

    func load() async {
        state = .loading
        guard let token = await sessionProvider() else {
            await mismatch()
            state = .signedOut
            return
        }
        do {
            let payload = try CommunityBenchmarks.decodeAggregates(
                try await api.aggregates(bucket: nil, metric: nil, runId: nil, access: token)
            )
            guard let run = payload.run else {
                state = .empty
                return
            }
            let built = CommunityBenchmarks.cards(figures: payload.figures)
            state = .loaded(CommunityBenchmarksSnapshot(runId: run.runId, cards: built.cards, omitted: built.omitted))
        } catch let error as NativeCommunityError {
            switch error {
            case .status(401):
                await mismatch()
                state = .signedOut
            case .status(403):
                await mismatch()
                state = .forbidden
            default:
                state = .unavailable
            }
        } catch is CancellationError {
        } catch {
            // Strict decode failures fail closed: no partial figures render.
            state = .unavailable
        }
    }

    func reset() {
        state = .loading
        mismatched = false
    }

    private func mismatch() async {
        guard !mismatched else { return }
        mismatched = true
        await onAuthMismatch()
    }
}
