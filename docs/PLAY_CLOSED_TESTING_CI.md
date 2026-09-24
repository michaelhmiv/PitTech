# Play closed-testing automation

`.github/workflows/play-closed-testing.yml` builds a signed Android App Bundle and publishes it to the Play Console's Alpha closed-testing track whenever `main` receives a push (including a pull-request merge). Releases are serialized so concurrent Google Play edits do not race.

## Required GitHub Actions secrets

Add these as repository Actions secrets before merging the workflow:

- `ANDROID_KEYSTORE_BASE64` — base64-encoded copy of the same upload keystore used to sign the AAB already uploaded to Play.
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `GCP_SERVICE_ACCOUNT_JSON` — raw JSON key for a Google Cloud service account invited in Play Console with permission to manage this app's testing releases. The Cloud Owner role is not needed for this upload workflow; grant only the access required for the app's testing track.

Do not commit the keystore or service-account JSON, and do not paste either into an issue or pull request. Do not generate a replacement upload key for this workflow: it must match the upload certificate already registered for the app.

The Play Developer API must be enabled in the service account's Google Cloud project. The app must already exist in Play Console (it does once the first manual bundle is uploaded). Before the first automated rollout, resolve the existing draft Alpha release in Play Console; if it is still pending, submit it or discard it so the automation starts from a clean track state.

For each workflow run, `versionCode` is derived from the workflow run number and retry attempt, and `versionName` is `0.1.<run number>`. Normal local builds keep `versionCode` 1 and `versionName` 0.1.0 unless Gradle properties override them.
