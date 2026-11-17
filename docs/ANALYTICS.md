# Product analytics — GA4 via Firebase Analytics (issue #322)

PitTech measures product usage with Google Analytics (GA4) through Firebase
Analytics, mirroring the CyberCache slice (CyberCache PR #322). GA4's
standard reports (active users, retention cohorts, engagement) come from the
SDK's automatic collection; the apps add screen views and five custom events.

## Ownership and privacy law

- One owner per platform: `app/src/main/java/com/pittech/analytics/PitTechAnalytics.kt`
  (Android) and `ios/PitTech/Analytics.swift` (iOS). Nothing else touches the
  Firebase SDK.
- No PII ever: no emails, cook titles, notes text, photos, or account ids in
  event params. Params are content ids and small enums only, enforced by the
  taxonomy allowlist sanitizer in each wrapper; unit tests on both platforms
  pin the exact vocabulary (`PitTechAnalyticsTest`, `AnalyticsTests`).
- Analytics is never a gate: every wrapper function is fire-and-forget and
  cannot throw into a caller. An unconfigured build is a silent no-op.

## Taxonomy

Screen views (`screen_view` with `screen_name`): Home, Live board, Timeline,
History (a finished cook's story), Recipes, Settings.

Custom events (no params except where noted):

| Event | Settles at |
| --- | --- |
| `cook_started` | Cook repository/store start succeeds |
| `cook_completed` | Cook completion succeeds |
| `cook_shared` | Relay upload finishes and the share code is saved |
| `recipe_created` | Recipe save with no prior recipe id (duplicates included) |
| `sign_in` | Session saved; param `method` ∈ `email` / `google` / `apple` |

## Configuration mechanism

Firebase project: **`pittech-529a1`** (project number **848714330882**) — the
existing App Distribution project. Before this change the repo carried no
Firebase client config at all: App Distribution runs server-side via the
Firebase CLI and a service account (the only registered app id in the repo is
the Android Dev app, `1:848714330882:android:328e155174c939db6b481b`).

- **Android:** the google-services Gradle plugin is applied ONLY when
  `app/google-services.json` exists, and the manifest removes
  `FirebaseInitProvider`; the wrapper initializes Firebase manually only when
  the generated `google_app_id` resource is present. CI and dev builds
  without the file build and run with analytics dark.
- **iOS:** the Firebase iOS SDK is a Swift Package dependency (FirebaseAnalytics);
  the wrapper calls `FirebaseApp.configure()` only when
  `GoogleService-Info.plist` is in the app bundle.

## Phase B — console steps (done 2026-11-17)

1. ✅ Google Analytics enabled in Firebase project `pittech-529a1`; GA4
   property **564773286** linked.
2. ✅ Android apps registered and `google-services.json` downloaded; the file
   carries clients for **all three** Android application ids:
   `com.pittech` (`1:848714330882:android:af7008163788812f6b481b`),
   `com.pittech.dev` (`1:848714330882:android:328e155174c939db6b481b`),
   `com.pittech.debug` (`1:848714330882:android:3f0c968a524926496b481b`).
   Note: the console's registration dialog showed a different id for the
   debug app (`…android:6d5b4c56ec4d4eaa78f8fc6`); the downloaded JSON —
   verified against Project Settings — is authoritative and carries
   `3f0c968a524926496b481b`.
3. ✅ iOS app `com.pittech` registered
   (`1:848714330882:ios:2293d44c070e1d8d6b481b`); `GoogleService-Info.plist`
   installed at `ios/PitTech/GoogleService-Info.plist` and declared in the
   app target's `resources:` in `ios/project.yml` (folder-source inclusion
   alone does not guarantee bundle copies).
4. ✅ Both config files committed with this change (client config
   identifiers, not credentials): `app/google-services.json`,
   `ios/PitTech/GoogleService-Info.plist`.
5. ⏳ Still outstanding (console, not code): update the store privacy
   declarations for the analytics addition: Play data safety (analytics:
   product interaction + device/other ids, pseudonymous) and the App Store
   privacy label (Product Interaction + Device ID, Analytics purpose, not
   linked, not tracking — matching `ios/PitTech/PrivacyInfo.xcprivacy`).

## Phase C — verification

"Done" means a live event lands in the GA4 property: run a configured build
(Android: `adb shell setprop debug.firebase.analytics.app com.pittech` for
DebugView; iOS: `-FIRDebugEnabled` launch argument) and confirm the
`cook_started` / `screen_view` events appear in GA4 DebugView/Realtime.
