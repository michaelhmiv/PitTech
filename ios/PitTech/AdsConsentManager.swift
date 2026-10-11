import GoogleMobileAds
import Observation
import UserMessagingPlatform

@MainActor
@Observable
final class AdsConsentManager {
    static let shared = AdsConsentManager()

    private(set) var canRequestAds = false
    private(set) var isPrivacyOptionsRequired = false
    private(set) var isAdsSDKReady = false
    private var refreshedThisLaunch = false
    private var didStartMobileAds = false

    func gatherConsent() {
        guard !refreshedThisLaunch else { return }
        refreshedThisLaunch = true

        let parameters = RequestParameters()
        ConsentInformation.shared.requestConsentInfoUpdate(with: parameters) { [weak self] error in
            Task { @MainActor in
                guard let self else { return }

                if error == nil {
                    do {
                        try await ConsentForm.loadAndPresentIfRequired(from: nil)
                    } catch {
                        // Consent remains controlled by UMP. Never request ads unless it says they are allowed.
                    }
                }

                self.refreshStatus()
            }
        }
    }

    func presentPrivacyOptions() async {
        do {
            try await ConsentForm.presentPrivacyOptionsForm(from: nil)
        } catch {
            // The system form presents its own error state; keep the existing choice intact.
        }
        refreshStatus()
    }

    private func refreshStatus() {
        canRequestAds = ConsentInformation.shared.canRequestAds
        isPrivacyOptionsRequired = ConsentInformation.shared.privacyOptionsRequirementStatus == .required

        guard canRequestAds, !didStartMobileAds else { return }
        didStartMobileAds = true
        MobileAds.shared.start { [weak self] _ in
            Task { @MainActor in self?.isAdsSDKReady = true }
        }
    }
}
