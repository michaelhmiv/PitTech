import Foundation
import Network
import SwiftUI

/// Slice 10 (PL-23 / DD-8): the app-wide offline state. Before this slice
/// iOS had no offline UI at all — a sync or publish failure was the only
/// signal. The monitor tracks the default path; `ContentView` renders the
/// one banner wording (`OfflineCopy.banner`) above the tab bar's content
/// while offline, and network surfaces show the per-surface
/// `OfflineCopy.changesWait` line in place. Mirrors Android's
/// ConnectivityManager-backed state in `Connectivity.kt`.
@MainActor
@Observable
final class ConnectivityMonitor {
    var isOnline = true

    private let monitor = NWPathMonitor()

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in self?.isOnline = online }
        }
        monitor.start(queue: DispatchQueue(label: "pittech.connectivity"))
    }
}

struct OfflineBannerView: View {
    var body: some View {
        Text(OfflineCopy.banner)
            .font(.subheadline)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .accessibilityIdentifier("offline-banner")
    }
}
