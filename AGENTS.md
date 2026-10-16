# AGENTS.md — working in the PitTech repo

Guidance for any coding agent (Codex, Muse, or other) working in this
repository. Read this before starting work. Product/design decisions live in
GitHub issues; this file holds the durable rules of the road.

## Repo layout

- `app/` — Android app (Kotlin / Jetpack Compose), at the repo **root** module.
- `ios/` — iOS app (Swift / SwiftUI). The Xcode project is generated from
  `ios/project.yml` (XcodeGen) — edit the spec, not the generated project.
- `privacy-site/` — the hosted privacy policy (Railway).
- `feedback-service/` — Android feedback relay (Railway → GitHub issues).
- `api-service/` — draft anonymous Cook Pack relay and optional passwordless
  auth, public publications and the private-sync draft; separate `relay` / `auth` / `publications` / `private_sync` Postgres schemas and private object storage. `online-services.yml` verifies the backend;
  its review copy/application patch lives in `ci-proposals/`.
- `store-assets/`, `distribution/` — store listing material.

## Scanning the repo efficiently

- **Check `docs/` before designing anything.** It holds 30+ specs and policy
  docs (`COOK_DATA_AND_EXPORT_SPEC.md`, `MEALS_RECIPES_SPECIFICATION.md`,
  `CONNECTION_POLICY.md`, `ADMOB_SETUP.md`, `RELEASE_PROCESS.md`, …) that
  record decisions already made.
- **Skip:** `LICENSES/`, `third_party/`, the notice files, `store-assets/`
  binaries, and `app/schemas/` (generated Room schemas — only relevant when
  writing a migration).
- **Entry points:** Android UI in `app/src/main/java/com/pittech/ui/`; ads
  logic in `app/src/main/java/com/pittech/ads/` mirrors iOS
  `ios/PitTech/AdsConsentManager.swift` + `NativeAdCard.swift`; iOS state in
  `ios/PitTech/CookStore.swift` and `LibraryModels.swift`.
- **Canonical fixtures:** `ios/PitTechTests/Fixtures/android-meal-v9.zip`
  (cross-platform archive sample), `ios/screenshot-fixtures/`. `scripts/`
  has release-notes, emulator, and upgrade-fixture helpers — use them
  instead of reinventing.
- **Versions:** Android from `-PpittechVersionCode` / `-PpittechVersionName`
  (defaults in `app/build.gradle.kts`); iOS from `MARKETING_VERSION` /
  `CURRENT_PROJECT_VERSION` in `ios/project.yml`.

## Rules of the road

1. **GitHub Actions is the only build machine.** The project owner has no Mac
   and does not build locally. A change is not verified until the relevant
   workflow proves it. If a change lacks the test/CI coverage that would make
   it trustworthy, add it — Actions time is free on this repo.
   Workflow map: `ios-build.yml` (simulator build/test/screenshots, plus the
   manual App Store IPA mode), `android-build.yml` (unit + emulator tests —
   API 36 is the gate; the API 37 job is advisory), `play-bundle.yml` and
   `play-closed-testing.yml` (Google Play), `firebase-dev-distribution.yml`
   (dev builds), `feedback-relay.yml`, `online-services.yml` (Node 22,
   real Postgres 16, relay contracts/security and production-container checks).
   The core data gate in `scripts/run-emulator-ui-tests.sh` explicitly includes
   sharing ownership, Cook Pack exchange, guides, native share-card pixels,
   dense-document persistence and workbook tests.
   Keep its expected test counts aligned with the explicit class selectors.
   iOS retains compact test-result JSON separately from full screenshots and
   xcresult diagnostics so failures can be inspected without downloading video.
2. **iOS signing is fully CI-side.** The `app-store-ipa` workflow mints its
   own distribution certificate and "PitTech App Store CI" provisioning
   profile via the App Store Connect API on every run. Never add steps that
   assume local keychains, exported .p12 files, or manually created
   certificates/profiles.
   It enables Associated Domains before creating the profile. The app's
   `PitTech.entitlements` and the final HTTPS association files must match.
3. **Workflow files:** editing `.github/workflows/*` requires the GitHub App's
   Workflows repository permission. This connection successfully installed the
   backend workflow on October 15. Verify the actual write and Actions result;
   never infer workflow access from contents-write access alone.
4. **AdMob** (publisher `ca-app-pub-2708638041809482`):
   - Android: live native unit only via `-PpittechLiveAds=true`; the `debug`
     build type always forces Google's test unit.
   - iOS: live IDs live **only** in the `Release` config of
     `ios/project.yml`; base/Debug keep Google's test IDs. Never put live
     IDs in a debug-reachable config.
5. **Privacy declarations must match the binary.** Both apps ship the Google
   Mobile Ads + UMP SDKs. Keep `ios/PitTech/PrivacyInfo.xcprivacy`,
   `privacy-site/index.html`, and the store privacy declarations consistent
   with whatever SDKs are actually compiled in. If you add or remove an SDK,
   the declarations are part of the same change.
6. **Data compatibility:** the library archive format is a shared contract
   across platforms (iOS tests import an Android v9 archive fixture). Treat
   archive schema changes as migrations: version them, keep old archives
   importable, and test the round trip.
7. **Read-only equipment policy (hard rule).** Grill/cloud connectors may
   only *observe*: never write code that mutates device state (setpoints,
   ignition, power, auger, fan, Wi-Fi credentials, firmware or
   configuration). See `docs/CONNECTION_POLICY.md`. Authentication and
   telemetry-subscription packets are allowed only where observation
   requires them.
8. **No secrets in the repo.** Never put credentials, tokens, manufacturer
   secrets, device identifiers, or unredacted logs in code, fixtures,
   tests, or log output.
9. **PR conventions** (from `CONTRIBUTING.md`): every user-visible change
   includes a short plain-language release-note entry (and a `CHANGELOG.md`
   entry where appropriate); mark Android-only / iOS-only features
   accurately; the PR states user impact, supported platforms, test
   evidence, and data-migration considerations.

## Online services implementation — read issue #89 first

The post-launch online-services build (share cards, Cook Packs, code relay,
accounts, publishing, social layer, PitTech Pro) is specced in
**issue #89**, which reproduces the full implementation plan (phases 0–3)
and the settled decisions: PitTech Pro at $3/month, monthly only; magic-link
auth via Resend (no passwords); anonymous publishing allowed; comments out
of scope; sync and publishing are separate systems (backup is a private
mirror, publishing is a deliberate per-recipe act).

## Parity commitment (owner directive — do not drift)

The owner requires iOS/Android parity going forward:

- Both clients are in every feature's definition of done. Sequencing work
  (one platform leads) is fine; parking the other platform as "later" is not.
- Maintain a feature parity matrix (in the repo) as implementation proceeds:
  every platform gap is marked port, drop, or platform-appropriate with a
  written reason. An undocumented difference is a defect.
- Shared contracts (archive format, Cook Pack format, backend APIs) get
  cross-platform round-trip tests in CI.
- Native public archive builders export selected sharing drafts only. Saved public
  stories retain their public-only gap/quality/verdict contract; edited instructions
  never replace frozen measured facts. Both native writers emit CI fixtures.
- Private sync uses the explicitly reviewed `contracts/private-sync-projection-v1.json`
  allowlist, not a generic archive import or automatically generated model dump.
  Unsupported authored documents stop the complete projection; local settings,
  credentials and monitoring/notification activation never become portable rows.
- Owner editing downloads require authentication and an exact revision. Private
  account-bound edit context preserves public identities/source credit without
  entering exports. Recorded facts stay frozen; stale owner drafts must reopen
  the current revision. See `docs/online/12-publishing.md`.
- Lifecycle moderation and deletion live in separate `moderation` / account-control
  tables and a dedicated runtime pool. Public user tokens never grant operator
  authority. Deletion storage cleanup must preserve create-only fences against late
  uploads; see `docs/online/13-lifecycle.md`. Community/account entry remain disabled.
- Public publication/fork writes require explicit versioned public-copy consent.
  Acknowledgments stay in the private operation/account domain, never public
  archives. Source-update review is read-only and active meals remain pinned.
- Public writes persist the exact selected body and operation before network access,
  retry under the initiating account only, and retain ambiguous outcomes. Named
  credit and public cookbook visibility require separate explicit previews.
- Public recipe/profile links navigate only; never authenticate or import on open.
  Public profile visibility is checked live; see `docs/online/12-publishing.md`.
- Signed-out discovery uses bounded public-only caches and exact-revision Cook Pack
  previews. Saved recipes pin public credit and mark relative-only story timing;
  see `docs/online/11-discovery.md`. Never display that coordinate origin as a
  recorded calendar date or feed private libraries into public read paths.
- Account sessions use a separate device-only Keychain/Keystore namespace and never
  enter exports or relay storage. Account entry remains disabled until the entire
  accounts/social/sync/deletion phase passes. See `docs/online/09-native-accounts.md`.
- Code/link sharing keeps owner capabilities in device-only Keychain/Keystore
  storage outside library exports. Incoming links open a preview flow; they
  never import or create an account automatically. See `docs/online/07-code-links.md`.
- The [connection parity matrix](docs/CONNECTION_PLATFORM_PARITY.md) records
  store availability on both clients and explicit port/platform decisions
  for provider history and background monitoring differences.

## Keep this file current

If your change alters the repo layout, the workflows and what they prove,
signing, the ads setup, or any rule above, update this file in the same PR.
Keep it lean — pointers over prose — and retire a stale line when you add
one. A wrong instruction here costs every future agent time.
