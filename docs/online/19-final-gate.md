# Slice 19 — final gate: dormant-feature audit, Task 1 fix record, timeout item

Stage 19 closes slices 00–18 (PRs #92–#120, main `6e78653`). No flag default
is flipped and no capability is enabled in this stage. This doc records:

1. The Apple guideline 2.5.19 dormant-feature audit (inventory + review notes).
2. The CommunityBenchmarks iOS UI failure diagnosis and fix (Task 1).
3. The historical iOS timeout and its verified resolution, plus current cleanup evidence.

## 1. Dormant-feature audit (Apple guideline 2.5.19)

Rule applied per capability: a remote switch must not be able to wake an
undisclosed capability. Sweep method: every `*_ENABLED` / `*_READY` env flag,
every `enabled = false` default, every `#if DEBUG` / `BuildConfig` gate,
every `--*` launch argument, plus repo-wide greps for `featureFlag`,
`remoteConfig`, `RemoteConfig`, `killSwitch`, `opt-in`, `MCP`, and server
`features`/`flags` response parsing. Negative results are recorded in §4.

Dispositions: (a) user-visible + documented; (b) server-side flag gating an
already-visible feature / normal operations; (c) must be named in App Review
notes (§2 drafts the exact text); (d) debug/test-only, excluded from release.

### (a) User-visible + documented

| Capability | Flag / switch | File | Default | What it gates | Why (a) |
|---|---|---|---|---|---|
| Cook Pack relay share-by-code | None client-side (always on) | `app/.../data/RelayProtocol.kt`, `ios/PitTech/RelayProtocol.swift`, `RelayCoordinator.swift`, `RelayViews.swift` | on | Upload with preview, code/QR/link display, signed-out code entry and link import | User sees Share by code / Import by code, the pre-upload preview, and the code/QR/link. No hidden path. Server incident switch is row (b7). |
| Telemetry device connections (5) | Android `TELEMETRY_BETA_ENABLED` | `app/build.gradle.kts:36,47,56` (true in release, debug, dev); iOS has no flag (`NativeTelemetryProvider.allCases` picker, `ios/PitTech/NativeTelemetryConnectionsView.swift:53`) | on | Home Assistant, Combustion, FireBoard, Flame Boss, MEATER read-only connections | User sees Devices / "Temperature connections · Beta", per-provider connect, and per-meal monitoring toggles. Posture matches build 169: same five, read-only, Beta-labeled both platforms. No drift, no per-provider switch. |
| PitTech Pro entitlement + paywall | Verified entitlement cache (`store-verification-v1`); product IDs empty | `ios/PitTech/ProSubscription*.swift`, `ProPaywallView.swift`, `AdsConsentManager.swift`; `app/.../billing/*`, `app/.../ads/AdEntitlementGate.kt`; `ios/project.yml:62-64`, `app/build.gradle.kts:10-12` | paywall visible; purchase not-configured | Pro analysis/curves/benchmarks/themes/hub behind purchase; ad removal for entitled users | NOT a dormant feature: the paywall UI, entries, price/Restore/Manage/close, expired fallback, and neutral loading are all visible. Gating input is a verified store receipt, never a remote switch: client booleans cannot unlock (`adEntitlementFromServerCache` requires verified + contract version). Empty product ID shows an honest not-configured state (owner-gated store product). |
| AdMob test/live inventory | Android `-PpittechLiveAds` (default false); iOS build config | `app/build.gradle.kts:9,16,33,45`; `ios/project.yml:60-61`; `ios/PitTech/Info.plist:47-50` | test inventory | Which ad unit ID ships | Visible consequence (Google-labeled test ads vs live inventory). Build-time only: no runtime or remote path. Android forces the test unit in debug; iOS currently ships Google test IDs in every config (no live IDs in the tree — adding Release live IDs is owner-gated). |

### (b) Server-side flags — normal operations, no disclosure needed

Every flag below defaults to off (anything but the exact string `'true'`),
lives only in `api-service/src/config.ts`, takes effect only at process boot,
and gates server routes only. None can wake client capability: the clients
hardcode `https://api.pittech.app`, parse no server feature flags (grep for
server `features`/`flags`/`killSwitch` in clients: no hits), and additionally
require their own local `enabled` constants (rows (c1)–(c3)) before touching
these routes. Flipping any of them requires an owner redeploy with secrets.

| Capability | Flag | Default | What it gates | Why (b) |
|---|---|---|---|---|
| Passwordless auth + Resend mailer | `AUTH_ENABLED` (config.ts:45) | off | `/v1/auth/*` routes, challenge mailer, auth timers | Server routes only; client account UI needs local enabled=true (false). |
| Moderation/deletion pool | `ACCOUNT_LIFECYCLE_ENABLED` (config.ts:49) | off | Separate lifecycle pool, community lifecycle, account deletion, notices | Requires auth secrets + separate keys (enforced); no client wake path. |
| Public publications API | `PUBLIC_API_ENABLED` + `PUBLIC_MODERATION_READY` + `ACCOUNT_DELETION_READY` (config.ts:59-61) | off | Publications/discovery routes | Boot refuses partial phases ("Complete phase required"); no client wake path. |
| Private sync routes | `PRIVATE_SYNC_ENABLED` (config.ts:62-80) | off | Sync snapshot/photo routes, dedicated pool, separate encryption keys, limits | Client additionally needs local enabled + sign-in + explicit Sync now tap. |
| Store subscriptions | `SUBSCRIPTIONS_ENABLED` (config.ts:83-126) | off | Purchase verification, ASN V2/RTDN handlers, entitlement endpoint, OIDC refresh | Client paywall is visible regardless and degrades honestly without it. |
| Community aggregation | `COMMUNITY_AGGREGATION_ENABLED` (config.ts:130; `main.ts:33-37`) | off | Aggregation scheduler + aggregate API | Fail closed: no timer exists when off. Client shows honest insufficient/unavailable states. |
| Relay upload incident switch | `RELAY_UPLOADS_ENABLED` (config.ts:132; `http.ts:168`) | off | `POST /packs` (503 `uploads_paused` when off) | Gates the already-visible share-by-code upload path; the client surfaces the error. Normal incident operations. |
| Deployment knobs (not capability switches) | `NODE_ENV`, `OBJECT_STORAGE`, `S3_*`, `PORT`, `TRUSTED_PROXY_CIDRS`, `PRO_APPLE_TRUST_MODE`, `MIGRATION_DATABASE_URL`, secrets, storage/rate budgets | n/a | Storage backend, proxies, ports, trust roots, budgets | Selected at deploy; cannot add or remove client capabilities. |
| Android feedback relay | `PORT`, `GITHUB_TOKEN`, `GITHUB_REPOSITORY` (`feedback-service/server.mjs:10-12`) | n/a | Relay target only | No feature switches in the service; no client wake path. |

### (c) Dormant in-binary layers — MUST be named in App Review notes

These three capabilities are compiled into the release binary but unreachable:
each hangs off a compile-time `false` constant with no runtime or remote path
to `true`. What a reviewer (or user) sees today is an honest placeholder, not
the capability. §2 drafts the exact notes text for each.

| Capability | Flag / switch | File | Default | What it gates |
|---|---|---|---|---|
| (c1) Account/online layer | iOS `NativeAccountProtocol.enabled = false`; Android `AccountProtocol.ENABLED = false` | `ios/PitTech/AccountProtocol.swift:7`; `app/src/main/java/com/pittech/data/AccountProtocol.kt:14` | false | Sign-in/challenges/sessions, account-bound likes, publishing (with discovery), source review, cookbook, community-controls quorum, sync default. Visible today: "Accounts arrive with community recipes and private sync." (`AccountView.swift:19`, `AccountScreen.kt:55`). |
| (c2) Public discovery/publishing layer | iOS `NativePublicCoordinator(enabled: Bool = false)`; Android `PublicManager(enabled: Boolean = false)` | `ios/PitTech/PublicDiscovery.swift:141`; `app/src/main/java/com/pittech/data/PublicDiscovery.kt:114` | false | Discover tab content, public recipe/collection/cook links, publishing/saving public content (accountless when enabled). Visible today: `DiscoveryWelcomeView` / `DiscoveryWelcome` placeholder. Test-only `enabled: true` wiring lives under row (d1)/(d4). |
| (c3) Private sync | iOS `NativePrivateSyncCoordinator(... enabled: Bool = NativeAccountProtocol.enabled)`; Android `PrivateSyncCoordinator(enabled = AccountProtocol.ENABLED)` | `ios/PitTech/NativePrivateSyncCoordinator.swift:29`; `app/src/main/java/com/pittech/data/PrivateSyncCoordinator.kt:33` | false | Encrypted backup, Sync now, conflict review. Activation path when enabled: sign in, then an explicit per-action Sync now tap (`AccountView.swift:45-52`); there is no always-on background switch in the UI. `releaseReady: false` in `contracts/private-sync-projection-v1.json:5151` is a test-asserted marker, not an enforcement gate. |

No-remote-wake evidence for (c1)–(c3): the constants are compile-time
(`static let` / `const val` / default parameters); the only `true` assignments
are inside `#if DEBUG` files or `src/test`/`src/androidTest` (rows (d1), (d4));
clients parse no server-driven feature flags; API hosts are hardcoded.

### (d) Debug/test-only — excluded from release binaries

| Capability | Flag / switch | File | Default | Mechanism that excludes it |
|---|---|---|---|---|
| (d1) iOS UI-test system (24 launch args + stub APIs + fixtures) | `--ui-test-reset/--ui-test-empty/--ui-test-pack/--ui-test-guide/--ui-test-recorded-pack`, `--screenshot-large-text/--screenshot-no-ads`, `--account-ui-test/--account-deletion-ui-test`, `--sync-ui-test`, `--pro-ui-test/--pro-entitled/--pro-expired/--pro-not-entitled/--pro-signed-out/--pro-no-product`, `--community-ui-test/--community-empty/--community-insufficient/--community-unavailable/--community-forbidden`, `--community-controls-ui-test`, `--discovery-ui-test`, `--publishing-ui-test` | `*UITestSupport.swift`, `UIFlowFixtures.swift`, `PublicUITestFixture.swift`, and `#if DEBUG` call sites in `PitTechApp.swift`, `CookStore.swift`, `ProSubscriptionCoordinator.swift`, `CommunityBenchmarksLoader.swift`, `NativePrivateSyncCoordinator.swift`, `PublicDiscoveryView.swift`, `PublicationUITestSupport.swift`, `CommunityUITestSupport.swift` (14 files) | absent | Swift `#if DEBUG` conditional compilation — `DEBUG` is undefined in Release, so the code is absent from the release binary; release builds are never launched with these arguments. |
| (d2) Android grill-controller testing + recording | `CONTROLLER_TESTING_ENABLED` | `app/build.gradle.kts:35` (release/default false; true only in debug:46 and dev:55); `CookRecordingService.kt`, `CookGrillPanel.kt`, `CookDetailScreen.kt:671`, `CookScreens.kt:703` | false in release | Per-build-type `BuildConfig` constant (release=false, unreachable). The controller interrogation/diagnostics implementation in `app/src/internal/java/**` is compiled into debug+dev source sets only (`app/build.gradle.kts:64-65`) and excluded from the release APK/AAB. |
| (d3) Android DEBUG fixtures | `BuildConfig.DEBUG` | `PitTechApplication.kt:57`, `devices/TelemetryRuntime.kt:52`, `CookRecordingService.kt:123` (upgrade-recording-fixture file hook), `CooksViewModel.kt:54` (`check(DEBUG)`) | false in release | Compile-time `BuildConfig.DEBUG` false in release (dead code). |
| (d4) Test source-set enabling | `enabled = true` constructor args in tests | `app/src/test/**`, `app/src/androidTest/**` only (e.g. `AccountUserFlowTest.kt`, `PublicDiscoveryUserFlowTest.kt`, `PrivateSyncCoordinatorTest.kt`) | n/a | Gradle test source sets are excluded from the release APK/AAB. |

### Explicit negatives (searched, absent)

- No remote-configuration system anywhere (no Firebase Remote Config,
  LaunchDarkly, or equivalent dependency or client; no MCP code of any kind).
- No kill-switch SDK and no server-driven client feature flags: clients parse
  no `features`/`flags` fields from any response.
- No per-provider telemetry switch and no staging/production endpoint switch:
  all API hosts are hardcoded (`https://api.pittech.app`, `https://pittech.app`).
- `privacy-site/` is static HTML with no switches; Associated Domains / App
  Links / entitlements are static routing with no switches.
- User preferences (Show guide, theme, units, reminder/monitoring toggles,
  per-publication identity, cookbook opt-in, sync conflict choices) are
  user-driven and visible, not remotely switchable; they are not inventoried
  as flags. Reminder/monitoring imports restore inactive by design.

## 2. App Review notes draft (paste verbatim for a submission carrying #89 code)

### Note for (c1) — dormant account layer

> This build contains disabled account-management code (email sign-in,
> sessions, account deletion) that is compiled in but unreachable: it is
> gated by a compile-time constant set to false, there is no setting, server
> response, or remote switch that can turn it on, and enabling it requires a
> future app update. The Account screen shows only local journal controls and
> the text "Accounts arrive with community recipes and private sync." No
> account data is collected or transmitted by this build.

### Note for (c2) — dormant public discovery/publishing layer

> This build contains disabled public-recipe discovery and publishing code
> that is compiled in but unreachable: it is gated by a compile-time constant
> set to false, there is no setting, server response, or remote switch that
> can turn it on, and enabling it requires a future app update. The Discover
> tab shows an informational placeholder only. No public content is fetched,
> displayed, or uploaded by this build.

### Note for (c3) — dormant private sync

> This build contains disabled private-library-sync code (encrypted backup)
> that is compiled in but unreachable: it is gated by a compile-time constant
> set to false, there is no setting, server response, or remote switch that
> can turn it on, and enabling it requires a future app update. Sync is
> documented in the app as user-initiated only ("Sync now" per explicit tap,
> never automatic, never publishing anything). No library data leaves the
> device in this build.

## 3. Bottom-line verdict

Posture is clean for a production submission carrying #89 code **provided**
the punch list below holds. Counts: (a) 4, (b) 9, (c) 3, (d) 4 — 20 rows.
Every dormant capability is either unreachable-by-constant with no remote
wake path and an honest visible placeholder ((c), disclosed via §2), or
absent from release binaries by build construction ((d)). Purchase gating is
disclosed purchase gating, not a dormant feature. No hidden switch was found
that any server, link, file, or setting can flip.

Punch list (exact):

1. Paste the three §2 paragraphs into the App Review notes for every
   submission until the Phase 2 flags flip in a reviewed release.
2. Keep `NativeAccountProtocol.enabled`, `AccountProtocol.ENABLED`, and both
   discovery `enabled` defaults at `false` until the whole
   accounts/social/sync/deletion phase passes (per `09-native-accounts.md`).
3. iOS live AdMob IDs (Release config only) and the Pro store product ID are
   still owner-gated: currently iOS ships Google test IDs everywhere and the
   paywall reports not-configured. Add them in the owner session before a
   monetized submission.
4. Verify current-head cleanup checks (§5–6) before
   declaring stage 19 green.
5. Do not flip any (b) server flag in production ahead of its documented
   phase gate; `PUBLIC_API_ENABLED` additionally requires both `*_READY`
   flags or boot fails closed.

## 4. Task 1 fix record — CommunityBenchmarks finish-tap failure

Failure: `CommunityBenchmarksUITests.testLiveAndFinishShowNoBenchmarkSurfacesAtBothTextSizes`
failed at line 218 (`app.alerts["Finish meal?"]` never appears within 20s
after tapping `finish-meal`) on main `bf156d9` and `24b4093`.

Diagnosed mechanism (test defect, not a product defect): the suite's `reveal()`
accepts any button position inside the navigation-bar-to-tab-bar window
(`CommunityBenchmarksUITests.swift` old lines 18–22: bottom =
`tabBar.frame.minY`). But the meal detail screen carries a fixed shortcut bar
(Log/Temp/Photo/Reminders) in a bottom `safeAreaInset` *above* the tab bar
(`ios/PitTech/CookDetailView.swift:64`), roughly 100pt tall. The capped-step
servo stops at the first accepted position — the window's bottom edge — which
is the band where the button sits under the shortcut-bar overlay: reported
hittable by AX, but the tap lands on the bar, `finishing` never becomes true,
and the alert never presents. The existing `isHittable` checks (old lines
212–216) cannot catch this: `MealRecipeFlowsTests.reveal()` carries an
explicit positional post-assertion (`MealRecipeFlowsTests.swift:82-87`) plus
a cook-screen branch ending 20pt above the shortcut bar (lines 37–56)
precisely because hittable alone proved insufficient on this screen.

Fix (minimal, in the proven style): ported `MealRecipeFlowsTests`'
cook-content branch (nav-anchored gutter drags, window
`[navBar.maxY + 10, meal-log.minY − 20]`) and its positional post-assertion
into `CommunityBenchmarksUITests.reveal()` and, preemptively, into the
identical `ProPersonalUITests.reveal()` (same flaw, same Live/Finish flow).
No timeout raised, no retry added, no assertion removed (only added), alert
machinery untouched. Files changed:

- `ios/PitTechUITests/CommunityBenchmarksUITests.swift` (reveal cook branch +
  positional assert)
- `ios/PitTechUITests/ProPersonalUITests.swift` (same alignment)

Why the passing suites were immune: `MealRecipeFlowsTests` forbids the band
by construction (shortcut-bar-aware window + positional assert, proven at
both text sizes on the same finish→alert flow); `ProPaywallUITests` uses
full-screen `app.swipeUp()` steps that overshoot the ~92pt band instead of
stopping at its edge. `ProPersonalUITests`' Live/Finish test never started
at `24b4093` (see §6), so same-run immunity was never established for it —
hence the preemptive identical fix.

Evidence ruled out: competing alerts/sheets (single `.alert` on the cook
screen; all sheets nil in this flow), `phases()` crash (trivial pure
function, safe on empty dishes), main-thread block (telemetry has no
profiles in UI tests; account/pro refreshes are fast-path async), launch-arg
differences (all three Live/Finish tests launch identically), stale element
handles (XCUIElement queries re-resolve).

## 5. iOS timeout resolution and complete evidence

The 120-minute timeout at `24b4093` was a historical failure. Current main
`5eacb00` already has 240-minute job and native-test caps. The complete iOS
run [39325946232](https://github.com/michaelhmiv/PitTech/actions/runs/39325946232)
passed: its retained native-test summary reports **247 passed, zero failures,
zero skips**. Compatibility tests, actual native writer exports, screenshots and
the public Simulator inspection all completed successfully. The formerly
unstarted ProPersonal cases are no longer an unverified remainder for that head.

PR #122 retains the complete unit/UI suite on one simulator worker. Multiple
hosted simulators produced runner-launch and UI-query failures. Native writer
fixtures run again after UI teardown, with a fresh timestamp fence, so their
collection does not depend on suite order. The PR's exact-head results are
separate acceptance evidence and must be recorded before merging.

## 6. Foundation cleanup and remaining release gates

PR #122 addresses manual-reading corrections, concurrent immutable recipe branches,
foreground sync consent/account attachment, local finish/deletion reconciliation
and the Android cookbook failure. It does not implement MCP or enable accounts.
The API 37 advisory failure on `5eacb00` was a system_server TaskSnapshotPersister
SIGABRT (`hasReadColorBufferDma`), followed by system-wide DeadSystemExceptions.
The emulator script now uses the SDK action's selected AVD tools from PATH and
checks the API 37 target before boot. Retain real restart/UI evidence; do not
call an advisory graphics crash an app data failure or hide a failed test.

Release still requires the integrated accounts/social/sync/deletion operational
gates, isolated deployed storage/roles and actual store-product/provider evidence.
Merged source and a complete Simulator run do not prove deployed cloud sync or
a successful store purchase. See `STAGING.md`, `14-private-sync.md` and
`16-subscriptions.md` for those remaining requirements.
