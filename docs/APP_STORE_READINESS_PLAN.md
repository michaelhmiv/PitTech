# PitTech iOS and App Store Readiness

## Product and release direction

- `main` remains the source of truth for Android and iOS. The iOS app and its workflow live in this repository and use the current `main` codebase and archive schema.
- The iOS app is native SwiftUI with bundle identifier `com.pittech`.
- The App Store listing is English (U.S.), free, and set to be available in all 175 countries or regions when released.
- The individual Apple Developer account supplies the seller identity.
- GrillirG monitoring is optional and read-only. It shows available status and temperature data and does not send ignition, setpoint, auger, or other controller-setting commands.
- Pit Boss and Traeger monitoring remain experimental on Android and are not part of the current iOS version. A provider name does not mean every controller has been physically verified.
- iOS ads use Google's consent flow. The current placement is limited to completed-cook history.
- App Store Connect is set to manual release after review. The GitHub workflow builds artifacts only; it does not upload, submit, or release them. The user will run iOS and Android releases manually.

## Current implementation

- The iOS app supports local cook start and finish, cook titles and notes, multiple dish names, manual temperature and timeline entries, basic cook summaries, recorded-value charts, and schema-5 ZIP archive import/export.
- The app stores the cook library locally and excludes it from device backup. It does not upload cook records to PitTech or include them in ad requests.
- Optional GrillirG sign-in uses email and a one-time code. The session is stored in Keychain, and the app requests only grill list, status, and temperature data.
- The iOS version does not currently add or display photos, does not provide all of Android's preparation, target/result, reminder, saved-guidance, insights, and controller-sampling features, and does not implement Pit Boss or Traeger connections. Store copy should continue to describe these limits plainly.
- `ios/PitTech/PrivacyInfo.xcprivacy` describes the app's GrillirG sign-in email and selected grill identifier. The app does not read or retain a GrillirG account user ID; the returned session token is used only as a credential. Google SDK privacy manifests cover their own collection; App Store Connect disclosures are managed separately.
- The public privacy policy covers the current iOS and Android behavior. Railway serves the public page from the `main` branch; verify its deployment after the next push.

## App Store Connect status

- App record: PitTech, bundle ID `com.pittech`, SKU `PITTECH-IOS`, version 1.0, status Prepare for Submission.
- Listing text, support and marketing URLs, age-rating answers, category, copyright, review notes, and manual release selection are saved.
- Age rating answers currently produce 4+; the app is not intended for children.
- Free pricing is saved for the U.S. base price and all 175 countries or regions are marked available on app release.
- The App Privacy page currently contains nine configured data types, including Google Ads disclosures and GrillirG sign-in identifiers. The `User ID` entry should be removed because the iOS app does not read or retain a GrillirG account user ID. Publish only after the public privacy page is verified and all data declarations are confirmed against the final SDK privacy report.
- The privacy policy URL is `https://pittech-privacy-production.up.railway.app/`; no separate privacy-choices URL is set.
- The App Store listing still needs screenshots, a build, and App Review contact details before it can be submitted.
- The content-rights declaration and EU Digital Services Act trader status have not been submitted. These require account-owner decisions. Apple identifies revenue from ads and commercial advertising as relevant trader factors, alongside whether an app is a non-commercial hobby project.
- `ITSAppUsesNonExemptEncryption` is false because the app uses standard platform networking and Keychain services rather than custom non-exempt cryptography. Confirm Apple's upload questionnaire if it presents one.

## Build and release path

- `.github/workflows/ios-build.yml` builds the Simulator app and captures real iPhone and iPad Simulator screenshots with a local-only sample cook library on pushes to `main` that change `ios/**` or the workflow. The screenshot fixtures are not part of the app bundle. The workflow uses GitHub-hosted macOS 26 and Xcode 26 or later, satisfying Apple's current SDK floor.
- A manual workflow run can create a signed App Store IPA and archive. It does not upload to App Store Connect or submit the app.
- Signed builds require a distribution certificate, matching provisioning profile, and production iOS AdMob app and native ad-unit IDs stored in GitHub Actions secrets/variables. These are not in the repository.
- No App Store build has been uploaded yet. The Windows checkout cannot produce a signed iOS archive locally; GitHub-hosted macOS is the build environment.

## Remaining gates

1. Verify the public Railway privacy page after deploying these policy changes.
2. Download the generated iPhone and iPad screenshots from the GitHub Actions artifact and upload them to the matching App Store Connect screenshot slots.
3. Add App Review contact information, including a reachable phone number.
4. Obtain production iOS AdMob app and ad-unit IDs.
5. Set up Apple signing material in GitHub Actions secrets, then run the manual signed-IPA workflow.
6. Complete the content-rights and EU trader declarations after the account owner confirms the relevant facts. Review all App Privacy answers before publishing them.
7. Upload the IPA to App Store Connect and attach the processed build to version 1.0. Keep release manual; submission to review and public release remain owner-controlled.
