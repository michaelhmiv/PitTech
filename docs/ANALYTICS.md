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

## Phase B — console steps still required (not code)

1. In Firebase project `pittech-529a1`, confirm Google Analytics is enabled
   and note the GA4 property + measurement identifiers.
2. Register the production Android app `com.pittech` and download
   `google-services.json`. The file must contain clients for **all three**
   Android application ids or the google-services plugin fails the variants
   it cannot match: `com.pittech` (release), `com.pittech.dev` (already
   registered), `com.pittech.debug` (debug build type).
3. Register the iOS app `com.pittech` and download `GoogleService-Info.plist`;
   install it at `ios/PitTech/GoogleService-Info.plist` (if XcodeGen's
   folder-source inclusion does not copy it into the bundle, add it to the
   app target's `resources:` in `ios/project.yml` in the same change).
4. Commit both config files (client config identifiers, not credentials) —
   or inject them in the release workflows the same way; either way the
   Phase C verification below decides.
5. Update the store privacy declarations for the analytics addition: Play
   data safety (analytics: product interaction + device/other ids,
   pseudonymous) and the App Store privacy label (Product Interaction +
   Device ID, Analytics purpose, not linked, not tracking — matching
   `ios/PitTech/PrivacyInfo.xcprivacy`).

## Phase C — verification

"Done" means a live event lands in the GA4 property: run a configured build
(Android: `adb shell setprop debug.firebase.analytics.app com.pittech` for
DebugView; iOS: `-FIRDebugEnabled` launch argument) and confirm the
`cook_started` / `screen_view` events appear in GA4 DebugView/Realtime.
