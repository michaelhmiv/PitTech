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
- `online-profile-checks.yml` builds and inspects isolated staging identities and
  link configuration on both clients. `contracts/fixtures/online-environments-v1.json`
  keeps backend/native origins, schemes and retry namespaces aligned.
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
   Workflow map: `capability-parity.yml` (issue #114 three-interface parity
   gate — every PR, no path filter), `ios-build.yml` (simulator build/test/screenshots, plus the
   manual App Store IPA mode), `android-build.yml` (unit + emulator tests —
   API 36 is the gate; the API 37 job is advisory), `play-bundle.yml` and
   `play-closed-testing.yml` (Google Play), `firebase-dev-distribution.yml`
   (dev builds), `feedback-relay.yml`, `online-services.yml` (Node 22,
   real Postgres 16, relay contracts/security and production-container checks),
   `online-store-inventory.yml` / `online-store-drafts.yml` (dispatch-only
   store reads/writes), `online-store-paywall-capture.yml` (dispatch-only Pro
   paywall review capture), `online-store-review-screenshot.yml`
   (dispatch-only review-screenshot upload).
   The core data gate in `scripts/run-emulator-ui-tests.sh` explicitly includes
   sharing ownership, Cook Pack exchange, guides, native share-card pixels,
   dense-document persistence and workbook tests.
   Keep its expected test counts aligned with the explicit class selectors.
   The sharded suite inside `ios-build.yml` is the iOS test gate (issue #134):
   guard + unit job + UI shards split by measured duration,
   `MealRecipeFlowsTests` split by method, parallelizing across runners.
   Hosted runners cannot reliably operate multiple simulators on one host —
   multiple hosted simulators caused runner-launch and UI-query failures — so
   every job runs exactly one simulator per host. The serial test steps in
   the `simulator-build` job were retired after the sharded suite posted two
   consecutive all-green full runs; that job now does the Simulator build,
   the public-configuration inspection, the writer-fixture export, and the
   App Store screenshots. The shard map
   (`scripts/ios-test-shards.json`, guarded by
   `scripts/check-ios-test-shards.py`) must be updated in the same change as
   any added, renamed, or removed XCTestCase class or split-class method.
   Writer fixtures are produced in the `simulator-build` job so container
   resets in the sharded UI jobs cannot remove their CI exports.
   iOS retains compact test-result JSON separately from full screenshots and
   xcresult diagnostics so failures can be inspected without downloading video.
2. **iOS signing is fully CI-side.** The `app-store-ipa` workflow mints its
   own distribution certificate and "PitTech App Store CI" provisioning
   profile via the App Store Connect API on every run. Never add steps that
   assume local keychains, exported .p12 files, or manually created
   certificates/profiles.
   It enables Associated Domains and Sign in with Apple before creating the
   profile. The app's `PitTech.entitlements` and the final HTTPS association
   files must match.
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

The owner requires iOS/Android parity going forward, and — for issue #114 —
full parity across **Android, iOS and MCP** as three maintained interfaces
to the same product:

- All three interfaces are in every private feature's definition of done.
  Sequencing work (one interface leads) is fine; parking another interface
  as "later" is not. Sequenced work may merge behind disabled flags; a
  feature may not ship enabled in the apps with MCP left as a follow-up.
- The machine-readable registry is `contracts/capabilities-v1.json`
  (schema `contracts/capabilities-v1.schema.json`); `docs/MCP_PARITY.md`
  is generated from it by `scripts/mcp-parity.mjs` — never edit it by hand.
  Every PR declares its capability impact in the PR template. The
  `Capability parity` workflow runs on every PR with no path filter and
  fails when the private-sync projection gains a record type or definition
  kind with no declared MCP disposition, when a platform binding names a
  source symbol that does not exist, when a prohibited capability
  (public/social writes, hardware control, account/billing/credentials)
  exposes an MCP tool, or when the generated doc is stale. See
  `docs/MCP_IMPLEMENTATION_PLAN.md`.
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
- Room schema 11 permits immutable recipe branches with equal display ordinals.
  Revision/parent UUIDs identify branches; never renumber their stored contents.
  Manual reading corrections keep note links; automatic samples and provenance
  remain immutable. See `docs/online/14-private-sync.md`.
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
  enter exports or relay storage. Account entry defaults off in build configuration until the entire
  accounts/social/sync/deletion phase passes. See `docs/online/09-native-accounts.md`.
- Code/link sharing keeps owner capabilities in device-only Keychain/Keystore
  storage outside library exports. Incoming links open a preview flow; they
  never import or create an account automatically. See `docs/online/07-code-links.md`.
- The [connection parity matrix](docs/CONNECTION_PLATFORM_PARITY.md) records
  store availability on both clients and explicit port/platform decisions
  for provider history and background monitoring differences.

## Keep this file current

Online enablement is tracked in issue #123 and `docs/online/20-online-enablement.md`.
On October 20 the owner authorized completing the online rollout and distributing
the tested build through Firebase. Missing runtime/store inputs and verification
remain real prerequisites; older “awaiting authorization” notes do not require
another permission request. MCP #114: M0 contract/parity work is prepared on
`work/issue-114-mcp-m0` (registry, generated parity matrix, parity CI) and
does not merge to main until the owner gives the go-ahead; public MCP access
still waits for this rollout's controlled activation. The isolated staging
foundation and private runtime/storage probe have passed; the API is deployed at
backend-tested 9ee2e2d (see `docs/online/STAGING.md`). Online feature flags stay
off until full operational acceptance. The companion emulator selector now
requires 73 tests, including persistent subscription-cache account isolation
and Google provider sign-in/attach.
Native online configuration selects production or staging with fixed first-party
origins, separate credential/retry/cache namespaces and explicit enablement (off
by default). iOS Staging is com.pittech.staging; Android Dev stays com.pittech.dev.
Real store sandbox builds keep the registered store identity and need the explicit
sandbox configuration. Never infer paid access from the chosen build profile.
Firebase online dispatch also requires completed environment acceptance markers;
its Dev package supports account/sync testing, while Play purchases need the
registered package. PR #124 merged at `9a97216` (2026-10-21) after all six
exact-head candidate workflows passed; the bounded merge guard (run 39526241615)
completed that merge and was retired in the operations integration that followed.
Because the guard merged with GITHUB_TOKEN, no push workflows ran on `9a97216`;
exact-main CI is established through the connected GitHub integration, never
assumed from a workflow-token merge. The retained CI-only operations under
`.github/scripts/online-*` (public signer evidence, read-only store inventory,
idempotent Apple draft completion) are dispatch-only on main. Apple's monthly
product is a draft (state MISSING_METADATA). Play: the `pittech.pro.monthly`
draft exists — created 2026-10-21 after the CI publisher identity received
"Manage store presence" (base plan `monthly`, state DRAFT, US $3). Staging
mail is still blocked: pittech.app was purchased through Railway Domains, so
Railway manages its DNS, and the four Resend staging records go in through
the Railway API once it recovers from the 2026-10-21 platform incident.
See docs/online/20-online-enablement.md.

If your change alters the repo layout, the workflows and what they prove,
signing, the ads setup, or any rule above, update this file in the same PR.
Keep it lean — pointers over prose — and retire a stale line when you add
one. A wrong instruction here costs every future agent time.
