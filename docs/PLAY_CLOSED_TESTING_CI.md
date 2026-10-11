# Play release workflow

The manually triggered Play release workflow builds, tests, signs, and uploads an Android App Bundle from main. Its release track input defaults to Alpha closed testing, which uses Google's native test ad unit. Choose production only after completing the Play checks in PRODUCTION_RELEASE_READINESS.md and selecting the production-readiness confirmation. Production uploads use PitTech's live AdMob unit and start an in-progress rollout at the selected 5%, 10%, 25%, or 50% user fraction. The default fraction is 10%; increase rollout later in Play Console after monitoring. AdMob may limit or delay serving until its app-ads.txt verification and app-readiness review are complete.

The workflow runs JVM unit tests, validates its signing and Google service-account secrets, signs with the existing Play upload key, authenticates to Google Cloud, and uploads to the selected Play track. Keep the workflow at .github/workflows/play-closed-testing.yml: Alpha and production share one GitHub Actions run counter, which keeps Android version codes increasing across both tracks.

## Required GitHub Actions secrets

Add these as repository Actions secrets:

- ANDROID_KEYSTORE_BASE64 — base64-encoded copy of the same upload keystore used to sign the AAB already uploaded to Play.
- ANDROID_KEYSTORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD
- GCP_SERVICE_ACCOUNT_JSON — raw JSON key for a Google Cloud service account with Play Console access to the tracks you intend to publish to. Production requires production-release access; the previous successful Alpha upload only verified its Alpha permissions.

Do not commit the keystore or service-account JSON, and do not paste either into an issue or pull request. Do not generate a replacement upload key for this workflow: it must match the upload certificate already registered for the app.

The Play Developer API must be enabled in the service account's Google Cloud project. The workflow enables it during each run. The app must already exist in Play Console. Before an Alpha upload, resolve any pending draft release on that track. Before a production upload, the account must have production access, all Play Console app-content and release requirements must be complete, and the production track must accept a release. Configure UMP and the Play Ads declaration before distribution. If AdMob app-readiness is still pending after the first public listing, the app can publish but full ad serving is not assured.

For each run, versionCode is derived from the workflow run number and retry attempt, and versionName is 0.1.<run number>. Do not split Play releases into a second workflow without first preserving a shared monotonic version-code sequence. Normal local builds keep versionCode 1 and versionName 0.1.0 unless Gradle properties override them.
