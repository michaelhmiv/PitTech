import GoogleMobileAds
import SwiftUI
import UIKit
import UserMessagingPlatform

@MainActor
@Observable
final class NativeAdLoader: NSObject, NativeAdLoaderDelegate, AdLoaderDelegate {
    private(set) var ad: NativeAd?
    private var loader: AdLoader?

    func load(entitlement: AdEntitlementState = .unknown, previouslyEntitled: Bool = false, monitor: AdRequestMonitor = .shared) {
        guard ConsentInformation.shared.canRequestAds,
              let unitID = Bundle.main.object(forInfoDictionaryKey: "PITTECHNativeAdUnitID") as? String,
              !unitID.isEmpty else { return }
        guard monitor.shouldAttempt(entitlement: entitlement, previouslyEntitled: previouslyEntitled) else { return }

        let loader = AdLoader(adUnitID: unitID, rootViewController: nil, adTypes: [.native], options: nil)
        loader.delegate = self
        self.loader = loader
        loader.load(Request())
    }

    func adLoader(_ adLoader: AdLoader, didReceive nativeAd: NativeAd) {
        nativeAd.delegate = self
        ad = nativeAd
    }

    func adLoader(_ adLoader: AdLoader, didFailToReceiveAdWithError error: Error) {
        ad = nil
    }
}

extension NativeAdLoader: NativeAdDelegate {}

struct NativeAdCard: View {
    @Environment(AdsConsentManager.self) private var consent
    @State private var loader = NativeAdLoader()

    var body: some View {
        Group {
            if let ad = loader.ad {
                NativeAdPlatformView(nativeAd: ad)
                    .frame(height: 126)
                    .padding(14)
                    .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
            }
        }
        .task(id: consent.isAdsSDKReady) {
            guard consent.adsServingAllowed, consent.isAdsSDKReady else { return }
            loader.load(entitlement: consent.entitlement, previouslyEntitled: consent.previouslyEntitled)
        }
    }
}

private struct NativeAdPlatformView: UIViewRepresentable {
    let nativeAd: NativeAd

    func makeUIView(context: Context) -> NativeAdView {
        let adView = NativeAdView()
        adView.translatesAutoresizingMaskIntoConstraints = false

        let adLabel = UILabel()
        adLabel.text = "Ad"
        adLabel.font = .systemFont(ofSize: 11, weight: .bold)
        adLabel.textColor = .secondaryLabel

        let headline = UILabel()
        headline.font = .preferredFont(forTextStyle: .headline)
        headline.numberOfLines = 2
        headline.setContentCompressionResistancePriority(.required, for: .vertical)

        let body = UILabel()
        body.font = .preferredFont(forTextStyle: .subheadline)
        body.textColor = .secondaryLabel
        body.numberOfLines = 2

        let icon = UIImageView()
        icon.contentMode = .scaleAspectFit
        icon.clipsToBounds = true
        icon.layer.cornerRadius = 8

        let action = UIButton(type: .system)
        action.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        action.backgroundColor = UIColor(red: 0.70, green: 0.30, blue: 0.13, alpha: 1)
        action.setTitleColor(.white, for: .normal)
        action.layer.cornerRadius = 9
        action.contentEdgeInsets = UIEdgeInsets(top: 8, left: 12, bottom: 8, right: 12)

        let textStack = UIStackView(arrangedSubviews: [adLabel, headline, body, action])
        textStack.axis = .vertical
        textStack.alignment = .leading
        textStack.spacing = 5
        textStack.translatesAutoresizingMaskIntoConstraints = false

        adView.addSubview(icon)
        adView.addSubview(textStack)
        icon.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            icon.leadingAnchor.constraint(equalTo: adView.leadingAnchor),
            icon.centerYAnchor.constraint(equalTo: adView.centerYAnchor),
            icon.widthAnchor.constraint(equalToConstant: 58),
            icon.heightAnchor.constraint(equalToConstant: 58),
            textStack.leadingAnchor.constraint(equalTo: icon.trailingAnchor, constant: 12),
            textStack.trailingAnchor.constraint(equalTo: adView.trailingAnchor),
            textStack.topAnchor.constraint(greaterThanOrEqualTo: adView.topAnchor),
            textStack.bottomAnchor.constraint(lessThanOrEqualTo: adView.bottomAnchor),
            textStack.centerYAnchor.constraint(equalTo: adView.centerYAnchor),
            action.heightAnchor.constraint(greaterThanOrEqualToConstant: 34)
        ])

        adView.headlineView = headline
        adView.bodyView = body
        adView.iconView = icon
        adView.callToActionView = action
        adView.nativeAd = nativeAd

        headline.text = nativeAd.headline
        body.text = nativeAd.body
        icon.image = nativeAd.icon?.image
        action.setTitle(nativeAd.callToAction ?? "Learn more", for: .normal)
        body.isHidden = nativeAd.body == nil
        icon.isHidden = nativeAd.icon == nil
        action.isHidden = nativeAd.callToAction == nil
        return adView
    }

    func updateUIView(_ adView: NativeAdView, context: Context) {
        adView.nativeAd = nativeAd
        (adView.headlineView as? UILabel)?.text = nativeAd.headline
        (adView.bodyView as? UILabel)?.text = nativeAd.body
        (adView.iconView as? UIImageView)?.image = nativeAd.icon?.image
        (adView.callToActionView as? UIButton)?.setTitle(nativeAd.callToAction ?? "Learn more", for: .normal)
    }
}
