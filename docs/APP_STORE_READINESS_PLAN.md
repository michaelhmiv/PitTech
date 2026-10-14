# PitTech iOS and App Store Readiness

Updated October 13, 2026. iOS features and repository release tooling have changed since the original October 12 audit; reverify App Store Connect before submission.

## Verified iOS implementation

- PitTech already has a native SwiftUI iOS app at `ios/`, generated with XcodeGen from `ios/project.yml`. The iOS target is `com.pittech`, supports iPhone and iPad, and targets iOS 17.0. The app version is 1.0, with project build number 1; the signed CI workflow assigns its run number as the build number.
- iOS supports local cook and dish records, cooking milestones, notes, manually entered temperature readings and charts, photos added through the camera or system photo picker, saved recipes, recipe history and comparisons, activity timers, local notifications, and ZIP import/export through Files. Android/iOS ZIP archives use schema 9 and share the structured cook data format.
- Core cook logging and review work offline. Consent refresh and advertising use Google's network services. The iOS app contains no account creation, PitTech cloud sync, feedback submission, Bluetooth/GATT code, or grill-controller connection. It does not monitor grills in the background or automatically read temperatures. These are Android-only capabilities and must not appear as iOS listing claims.
- Camera use is explained by `NSCameraUsageDescription`. The photo picker uses Apple's system picker. Reminders are scheduled as local notifications after user authorization; there are no push-notification or background-processing capabilities. No Bluetooth, location, contacts, or photo-library-wide permission is requested. No `NSUserTrackingUsageDescription`, App Tracking Transparency request, or background mode is present.
- The app privacy manifest is bundled and currently declares no first-party collection, tracking, or required-reason API use. Source inspection found no direct required-reason API use. Confirm the final Xcode privacy report and every resolved SDK manifest after CI resolves the package graph.
- Google Mobile Ads and UMP are the only iOS third-party service SDKs found in the app code. Consent gates ad requests. Google's current iOS disclosure says Mobile Ads may collect IP address/general location, diagnostics and performance data, device identifiers, advertising data, and product interactions. PitTech does not send cook records, notes, photos, or temperatures in ad requests. No ATT prompt is implemented; Google's AdMob account configuration still needs owner review before production ads are enabled.
- The library, note drafts, and photo attachments are local app data. The app now marks the data directory, existing and new library/draft files, and photo attachments as excluded from device backup, with simulator tests for those flags. ZIP export remains the user's explicit backup route.

## Verified Apple account and App Store Connect state

- The Windows browser session can access Apple Developer and App Store Connect. The Apple Developer Program membership is Individual and active; the team is `R42LWFBXBH`. The current program license agreement is accepted. The signed-in App Store Connect role is Account Holder and Admin with All Apps access.
- PitTech has an App Store Connect record: Apple ID `6821518899`, SKU `PITTECH-IOS`, bundle ID `com.pittech`, English (U.S.), category Food & Drink, and version 1.0 in Prepare for Submission. The listing does not require sign-in. Manual release is selected.
- The App Review contact fields and current iOS listing copy have since been entered and saved in App Store Connect (verified October 13). Screenshot tour assets have also been uploaded. Verify the processed build, review notes, and final binary before submission.
- Content Rights remains an owner action in App Store Connect (verified October 13). United States-only availability, age rating 4+, and non-trader status have been set. Review all declarations again before submission.
- The public privacy-policy URL is `https://pittech-privacy-production.up.railway.app/`. App Privacy was published on October 13 as “Data Not Collected,” which must be corrected for advertising SDK behavior before any release. Review and publish accurate data disclosures based on the final binary; PR #88 contains proposed production AdMob settings and still requires verification.

## Build, signing, and testing path

- This machine is Windows 10 and does not have Xcode or `xcodebuild`. Xcode 26 or later with the iOS 26 SDK is required for new App Store Connect uploads as of April 28, 2026. Use the repository's GitHub-hosted macOS 26 workflow or another Mac; local Windows cannot build, sign, archive, or run an iOS simulator.
- `.github/workflows/ios-build.yml` builds and tests on GitHub-hosted macOS, including screenshots. Its signed App Store IPA job now provisions Apple signing through the App Store Connect API and can upload to App Store Connect; inspect the output archive, signing, privacy declarations, and actual uploaded result.
- Before release, inspect the resolved SDK privacy manifests and Xcode privacy report, test on the simulator and a real iPhone, and distribute a signed build through TestFlight for owner testing. CI screenshots are simulator captures; a real iPhone check is still needed.

## Prepared listing draft

See [`distribution/app-store/metadata-en-US.md`](../distribution/app-store/metadata-en-US.md) for the replacement U.S. English promotional text, description, keywords, and reviewer notes. The draft describes the actual iOS behavior and explicitly says that controller connectivity is not available on iOS.

## Remaining owner-controlled steps

1. Complete Content Rights and verify the App Store Connect age rating, trader status, and U.S.-only distribution.
2. Correct and publish App Privacy disclosures against the final compiled app and Google Mobile Ads / UMP SDK manifests; address App Tracking Transparency consistency if tracking is enabled.
3. Verify the saved App Store listing, review contact details, screenshot set, and support links.
4. Build and inspect the signed IPA, test on an actual iPhone via TestFlight, and attach the processed build to the first version.
5. Validate the Railway-hosted policy after these privacy changes are deployed.
6. Keep manual release selected. Submission to App Review and public release remain owner decisions.
