# Online enablement and full-feature test delivery

Status: **staging rollout complete October 22, 2026; production
stand-up (slice R5) executing — owner ruling November 10, 2026** — the
isolated staging stack is fully activated and live-verified (all seven
flags; email sign-in, entitlement/reconcile and RTDN processing proven
against the real services; see STATUS.md). The earlier statement that
the remaining leg was only the physical-device walkthrough and the
Firebase/Play test delivery, waiting on an iOS 17+ test device, is
superseded: on November 10, 2026 the owner ruled the environment law
below and a production gap audit found that production had never been
stood up, so R5 is the open leg and is now executing. The text below
is the plan as written October 20, 2026, with the stale passages the
audit identified corrected or marked superseded in place.

**Environment law (owner, November 10, 2026):** STAGING is the
disposable test bench — Firebase/dev builds, the sim fleet, the MCP
lab and `BETA_ALL_ACCESS`. PRODUCTION is where approved work lives:
every store-distributed build (TestFlight, Play tracks) targets the
production backend. Firebase stays on staging.

**Production discovery (November 10, 2026):** production was never
stood up. `api.pittech.app` did not resolve (NXDOMAIN) and the
production Railway project held only the Privacy and Feedback Relay
services — no production API, Postgres or bucket. Slice R5
(production stand-up) is therefore now **executing**, with
**subscriptions included in the first cut** — the owner's explicit
ruling, overriding the production gap audit's recommendation to
stand production up with subscriptions off. iOS TestFlight build 687
is live in open beta targeting the production profile (online
enabled); the Play open-testing release follows the Play-lane fix
that builds every track with `-PpittechOnlineEnabled=true` (see the
continuation evidence at the end of this document).
Source baseline: main `743b6a5ab0f0b7c952355b26b891d02063e4a732`. This extends issue #89's implemented slices; it does not rebuild their domain features. Michael has requested online features to become usable and an explicitly dispatched Firebase build for hands-on testing. MCP #114 is fully planned and paused while this work is completed.

## Outcome and authorization

Deliver real account sign-in, private sync including photos, public discovery/publishing with reporting/blocking/deletion, and verified Pro access on Android and iOS. Preserve offline logging, local records, free exports and the five read-only Beta equipment providers. Produce an installable Firebase test release and an honest checklist of observed and still-unverified physical/store behaviors.

The October 20 instruction authorizes preparing and performing the necessary non-destructive online deployment and distributing the tested build to the existing Firebase tester group. Complete the documented release checks before enablement. Do not request the same authorization again merely because an older status file says deployment was not yet authorized. Console login, legal/financial agreements, credentials unavailable through the current connections, and automatic approval-review rejections may still require Michael directly. Never substitute fake Pro access for a verified purchase.

## Verified baseline

| Area | Current evidence | Remaining work |
|---|---|---|
| Source | #89 slices 00–19 and #122 are merged; PR #124 merged at 9a97216 after candidate 9ee2e2d passed all six exact-head workflows, including the complete iOS run 39513125256. The bounded merge guard (39526241615) completed the merge and was retired. PR #125 merged the operations integration at b945e9a with both exact-main gates green. Provider sign-in (owner decision 2026-10-21, plan doc 21) landed as backend PR #127, iOS PR #128 and Android PR #130 (main dfaacb3); Google OAuth clients (web + both Android packages) were created in GCP project pittech-529a1. Play RTDN is wired console-side (topic projects/pittech-529a1/topics/pittech-play-rtdn, push identity pittech-rtdn-push@pittech-529a1.iam.gserviceaccount.com, test notification sent); the push subscription and backend config install at deployment time. | Because the guard merged with GITHUB_TOKEN, no push workflows ran on 9a97216; exact-main CI is established by the operations integration (Android push checks plus an explicit online-profile-checks dispatch on the resulting main SHA). Superseded October 22, 2026: provider env configuration was installed and provider sign-in went live on staging during the October 22 activation (the deployed staging backend no longer predates the provider merges). |
| Native accounts/sync/community | Compile-time account flags false; discovery defaults false. Foreground sync is optional after explicit library attachment; credentials/settings/activation remain device-local. | Enable both clients together only after the complete deployed account/community/sync/deletion phase passes. Keep ordinary restore inactive. |
| Backend | The deployed 9ee2e2d backend passed 181 tests with zero skips, real Postgres, strict typecheck and production-container checks. Runtime/provider fixes and shared profiles are verified. | Complete actual sign-in, community, sync/photos, deletion and store acceptance before feature activation. |
| Railway staging | API deployment 22329c13-36bd-4bab-9a3a-debe941e4150 is SUCCESS at 9ee2e2d. The private readiness probe passed all three runtime-role and object-namespace checks; restart recovered. Both custom HTTPS origins and native association files are served. | Superseded October 22, 2026: all seven feature flags are ON and live-verified (see STATUS.md). Remaining staging evidence is the physical-device walkthrough; mail, backup/restore and the live phase were proven during the October 22 activation. |
| Production | PitTech project currently has Feedback Relay and Privacy only. (Verified still true November 10, 2026 — this row is the gap, not a documentation error: `api.pittech.app` was NXDOMAIN and no production API, Postgres or bucket existed.) | Add/review the online stack after isolated staging acceptance. Preserve those existing services and unrelated projects. **Executing since November 10, 2026 as slice R5, with subscriptions included in the first cut (owner ruling, November 10, 2026).** |
| Email | auth.pittech.app verified. Superseded October 22, 2026: the auth-staging verification failure recorded here was resolved that day — the four required records were added through the Railway API, the staging domain verified, the domain-scoped staging key installed and actual sign-in delivery proven during activation. (As written October 20–21: auth-staging verification had failed, the four required records were absent, and pittech.app — purchased through Railway Domains, which manages its DNS, Name.com being only the registrar of record — waited on Railway's platform incident, open since 2026-10-21 ~07:24 UTC.) | Resolved October 22, 2026 (records added, domain verified, staging key installed, sign-in delivery proven). Production email still needs its own sending key scoped to the verified `auth.pittech.app` domain, installed as part of R5. |
| Stores | Apple product pittech.pro.monthly (6825135995) is configured as ONE_MONTH at US $3, English metadata and USA availability; state MISSING_METADATA (review metadata outstanding). Play: the earlier pricing 403 was a missing permission, not a pricing block — "Manage store presence" was granted to the CI publisher identity on 2026-10-21 and the pittech.pro.monthly draft now exists (base plan monthly, state DRAFT, P1M, US $3, verified on readback). | Complete Apple review metadata, Play RTDN topic and license-testing wiring, dedicated server credentials, notifications and real license/sandbox lifecycle tests. No annual commitment, web checkout or client-driven unlock. |
| Firebase | Existing main-only workflow builds/signs com.pittech.dev with test ads and distributes to pittech-dev; requires exact-main Android push success. | Feed approved online/build/product configuration into it, retain artifact/release links, and dispatch after the deployed functionality is usable. |

GitHub Actions can use the existing CI store credentials without exposing them to the model. The retained operations tooling on main holds the public signing evidence, complete read-only store inventory and idempotent monthly-draft configuration as dispatch-only workflows. Apple setup succeeded; Play product creation succeeded 2026-10-21 after the correct permission grant. pittech.app DNS is managed by Railway (purchased through Railway Domains); the staging records wait on Railway's platform incident, not on registrar access. The earlier rejected `google.play` origin remains off limits. These are access prerequisites, not another general authorization request. No local build was used.

## Delivery sequence

| Slice | Concrete work | Acceptance before moving on |
|---|---|---|
| R0 — Plan and evidence | Preserve the complete MCP plan in #114, track this rollout, refresh baseline/status and identify actual external access needs. | One durable plan, explicit source/runtime/store/distribution states, no unresolved scope hidden behind “implemented.” |
| R1 — Runtime and billing readiness | Correct Google Play subscription GET/acknowledgement resource paths; reject failed acknowledgement instead of treating 404 as success. Read Google's JWKS in its actual format with atomic key rotation/expiry; bind RTDN to the configured verified Pub/Sub service-account identity. Supply a separately scoped migration/bootstrap path and required container operations files. Audit the runtime flag/storage/key/role matrix. | Backend Actions: strict typecheck, real Postgres migrations/role isolation, provider-format/HTTP contract tests, verified/invalid notification fixtures, restart/retry behavior, production image and fail-closed startup. No local test/build substitutes. |
| R2 — Deployed isolated online stack | Configure/provision staging Postgres and private object storage; bootstrap migrations and the reviewed NOLOGIN/runtime role grants with separate API, lifecycle and sync logins. Wire key references, domain-scoped email key, HTTPS origins/DNS and sweeper. Resolve staging mail DNS. Pin/deploy the tested source. | Terminal deployment success plus DB/storage/readiness checks, actual sign-in delivery, restart recovery, limits/redaction and deletion cleanup. No public database proxy, public bucket or administrator URL in API/sweeper. |
| R3 — Stores and native test configuration | Configure Play/Apple monthly products and notification providers. Add reviewed build-time online/API-origin/store-environment configuration on both clients with production-safe defaults and strict origin validation. Fix native sandbox refresh/reconcile routing and bind persistent caches/in-flight responses to the local account identity, preserving only its bounded offline entitlement when access-token refresh is unavailable. Verify production defaults, explicit sandbox context, restart, account switch, late response and strict transport paths on both platforms. Resolve Firebase/Play package identities below. | Signed artifact inspection; authorized real sandbox/license purchase, acknowledgement, restore, renewal/cancel/refund/revoke and cross-device entitlement refresh. Sandbox records never grant production entitlement; the client cannot select the verification result's environment. |
| R4 — Complete native phase enablement | Enable account/community/private sync together in the approved test/release configuration after R2/R3 acceptance. Review projection releaseReady marker with its full coverage/evidence instead of flipping it alone. Update privacy/disclosures/review notes to the shipped behavior. Reconcile applicable open App Store readiness/ads/screenshot PRs separately. | Android API 36 standard/large text + upgrade/restart and full iOS unit/UI on one Simulator worker. Actual native writer fixtures, screenshots and live Android↔iOS sync/account/deletion walkthroughs. Source/CI evidence is not physical/store proof. |
| R5 — Production readiness | Apply the reviewed production stack/config after staging, preserving environment-specific keys, DB roles, object namespaces and limits. Configure api.pittech.app/pittech.app association files and native release identities; demonstrate backup/restore, key rotation, retention and incident switches. **Executing since November 10, 2026 (owner ruling): every store-distributed build targets production, and subscriptions are included in the first cut.** | Live account/sync/publication/deletion/store smoke checks, HTTPS/deep-link installation evidence, no private data in public APIs/aggregates/logs, operator reporting/moderation working and runtime costs/budgets inspected. |
| R6 — Firebase and test handoff | Merge verified source as permitted by the ongoing implementation instruction, wait for successful Android checks on that exact main SHA, then explicitly dispatch Firebase with confirm_distribution=true. Supply approved product/origin/env values through CI configuration. Retain signed APK, version/source SHA, release notes and Firebase tester link; provide Play test link where purchase testing requires it. **Amended November 10, 2026: Firebase stays on staging (test bench); store delivery (TestFlight, Play tracks) goes to production under R5 — it is not an R6 Firebase handoff.** | Observe the workflow's terminal success and actual Firebase release/upload result. Download/install path works; features call the intended live backend. Do not distribute the current disabled build and call it the requested online test release. |

R1 can proceed while console access is restored. R2/R3 can be prepared together; the complete phase gate governs R4. Missing store or physical evidence stays explicitly open rather than being replaced with mocks. A blocker in one external console does not stop independent code/docs/CI work.

## Deployment and role design

Use the existing Node 22 production image and reviewed migrations. Both 007 migrations have distinct names and recorded hashes; do not rename or rewrite shipped migrations. The image must include reviewed operations SQL if a one-off bootstrap service uses it.

Run schema migrations/bootstrap in a dedicated, private, one-off administrator/migrator context. Revoke PUBLIC schema-creation privileges before validating runtime grants. API login inherits only the relay/auth/publication/billing/public-only aggregate roles needed by enabled modules; lifecycle and private sync retain dedicated pools/logins. Grant no runtime principal schema ownership, superuser, role creation or migration access. Check account/lifecycle fencing and object reference cleanup using runtime credentials.

Use Railway variable references for private database hosts and bucket connection values. Generate independent account, lifecycle, rate-HMAC and versioned private-sync encryption keys; keep raw secrets out of the repo, test fixtures, shell history and logs. Separate staging/production. Use migration idempotency and expand/backfill/contract changes rather than destructive resets.

Separate one-off migrations from periodic runtime cleanup. Prove relay expiry, private upload abandonment, account/object purges, auth delivery/pruning, store reconciliation and public-only aggregates actually execute after restarts; review least privileges for every scheduled context. Verify the pending initial patch only after all required service configuration is concrete. User authorization now exists for this rollout, but missing runtime inputs are still real technical prerequisites.

## Firebase, Play and entitlement test contract

The existing Firebase package is **com.pittech.dev**. The Play package is **com.pittech**. A Play product belongs to its registered package: installing the Dev APK does not make the production package's purchase sheet available.

Preferred delivery preserves the side-by-side Firebase Dev install for account/sync/journal testing and uses a Play internal/closed-test com.pittech build from the same tested source for real Play Billing. If the owner needs the purchase sheet inside the Firebase-distributed APK itself, add a Firebase app/distribution variant for the actual Play package after verifying the signing/install path and license tester account. Do not silently change the existing Dev package or wipe the installed journal. Back up before any necessary signing/package replacement.

Both builds use server-verified entitlement; test and production purchase records stay separate. Make the test environment an explicit approved build/deployment setting, not a guessed query based on an unverified token. Sandbox refresh/reconcile must retain the selected test context, and account switching must clear another account's cache. Verify cross-platform account behavior explicitly. No fake product price, hidden entitlement override or “unlock all” testing flag replaces billing.

The final handoff must say which APK/link supports purchase testing and which supports the remaining online features. “Fully testable” includes clear limits, rather than treating a successful Firebase upload as functional acceptance.

## Required end-to-end walkthrough

1. Install/update with an existing journal and active-meal preservation; ordinary offline logging/export still works.
2. Sign in by real code/link; expired/scanner-prefetched/wrong-device challenges behave correctly. Sign out and recover safely.
3. Explicitly attach a populated phone library; sync meals/recipes and real photos to another account-matched device. Independent offline edits survive; same-record conflicts remain recoverable.
4. Exercise manual-reading correction, equal-ordinal recipe branches, interrupted photo resume, account switch and rollback. No automatic sample rewrite or local runtime activation on restore.
5. Remote Finish/delete reconciles only the affected device activity; local journal clear detaches without deleting the cloud library.
6. Signed-out discovery/save/cook; deliberate publication with exact selected data/credit; owner edit, report, block and unpublish. Anonymous/public paths never see private libraries.
7. Delete a real test account, including original public/private objects and sessions; independent other-owner copies survive. Demonstrate a late upload cannot revive purged bytes.
8. Real sandbox/license purchase, renewal/expiry/cancel/refund/revoke, restore/account binding, ad removal and free fallback. No cook interruption or private loss.
9. Verify installed native links, expected host/certificate identities and accessibility at both required text sizes.
10. Observe Firebase release success and the intended deployed backend from the installed APK. Record live/store/device evidence separately from automated fixtures.

## Completion ledger

- [x] MCP #114 fully planned through M0–M8, with continuous parity enforcement and photos; implementation paused.
- [x] Source/runtime/email/Firebase package baseline audited.
- [x] R1 runtime/provider fixes pass exact-head backend Actions (181 tests, no skips, at 9ee2e2d).
- [x] Staging resources, mail DNS/delivery, keys and least-privilege bootstrap complete. (Completed October 22, 2026 during staging activation; left unchecked in error until the November 10, 2026 audit.)
- [x] Store products/provider access/notifications and native test context configured. (Substantially complete as of October 22, 2026: products exist as drafts, RTDN is wired console-side and provider configuration is installed on staging; product activation in the stores remains part of the R5 production cut. Left unchecked in error until the November 10, 2026 audit.)
- [ ] Complete account/community/sync/deletion phase enabled and verified on both platforms.
- [ ] Production rollout acceptance and required physical/store evidence complete.
- [ ] Exact-main Android checks pass; Firebase action succeeds; downloadable release and walkthrough supplied.

## Primary references

- Existing product/phase decisions: issue #89 and docs/ONLINE_SERVICES_PR_PLAN.md.
- Code/role policies: AGENTS.md, docs/online/{08-auth,09-native-accounts,13-lifecycle,14-private-sync,16-subscriptions,19-final-gate,STAGING}.md.
- Google subscription GET: https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2/get
- Google acknowledgement: https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptions/acknowledge
- Google signing-key discovery: https://accounts.google.com/.well-known/openid-configuration
- Pub/Sub push identity checks: https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions
- Licensed/sideloaded billing tests: https://developer.android.com/google/play/billing/test
- Firebase distribution: https://firebase.google.com/docs/app-distribution/android/distribute-cli


## Build profiles and continuation evidence

PR #124 now prepares the complete first-party profile boundary. Android uses
`-PpittechOnlineEnvironment=production|staging` and the independent
`-PpittechOnlineEnabled=true|false` flag; defaults are production and false.
The Dev package remains `com.pittech.dev`. A release for the registered Play
package can use staging only with `-PpittechStoreSandboxBuild=true` and test ads.

iOS Debug/Release keep the existing production identity and disabled online
entry. The Staging configuration uses `com.pittech.staging`, test ads,
`staging.pittech.app` links and the `pittech-staging` custom scheme.
StoreSandbox retains `com.pittech` for real App Store sandbox receipts, uses the
staging API and test ads, and also defaults online entry off. Enabling either
test configuration requires `PITTECH_ONLINE_ENABLED=YES`. These configurations
do not register products, create signing identities or grant Pro entitlement.

Both clients select endpoints, response-link validation, device credential
services, public caches, pending publication/relay/sync files and entitlement
caches consistently. Production storage names are preserved without migration;
staging cannot adopt a production account or resume its pending upload.
The backend selects `ONLINE_ENVIRONMENT=staging` with matching email origin;
publication receipts, asset/profile URLs, web app-opening links and association
files follow that profile. Staging Android associations default to the Dev
package, with an explicit registered-package option for store sandbox testing.
The shared fixture is `contracts/fixtures/online-environments-v1.json`.
`online-profile-checks.yml` compiles and inspects both native staging artifacts
without making live authenticated requests; the normal suites retain offline,
account-switch, retry and data-preservation coverage.

Firebase dispatch now selects offline/staging/production explicitly. Online
distribution requires the corresponding repository variable
`PITTECH_STAGING_ONLINE_READY=true` or `PITTECH_PRODUCTION_ONLINE_READY=true`,
the real `PITTECH_PRO_MONTHLY_PRODUCT_ID`, successful profile inspection for the
exact main commit, and the existing exact-main Android gate. Set readiness
markers only after the complete deployed walkthrough and store acceptance above;
they are evidence of reviewed readiness, not a substitute for those checks.
Release notes identify the selected environment and explain that the Dev package
requires a companion registered-package Play build for purchase-sheet testing.

Observed before these new profile changes: all Android/backend/bundle/public
checks passed at 40504b6; iOS had one missing `try` in the subscription assertion,
fixed in 6ec3029. Both custom staging domains now have valid DNS/TLS. Email-domain
verification is pending. The private role/object probe has not run: Railway
temporarily refused deployment and left a pending source patch. Browser access
is unavailable in this chat, so store-console setup, repository variable
administration and manual workflow dispatch still need that connection.
MCP #114 remains paused. No online Firebase release has been dispatched.

> **Superseded October 22, 2026:** every open clause in the paragraph
> above was resolved by the staging activation that day — email-domain
> verification completed and sign-in delivery was proven, the private
> role/object probe ran, and the staging stack went live with all
> seven flags on (see STATUS.md). The paragraph is retained as the
> pre-activation record; it does not describe current state.

## November 10, 2026 — environment law and production stand-up

Owner ruling (November 10, 2026): **staging is the disposable test
bench** (Firebase/dev builds, the sim fleet, the MCP lab,
`BETA_ALL_ACCESS`); **production is where approved work lives** —
every store-distributed build (TestFlight, Play tracks) targets the
production backend. Firebase stays on staging.

A production gap audit the same day found production had never been
stood up: `api.pittech.app` was NXDOMAIN and the production Railway
project held only the Privacy and Feedback Relay services. Slice R5
is now **executing**, with **subscriptions included in the first
cut** — the owner's explicit ruling, overriding the audit's
recommendation to stand production up with subscriptions off.
Production MCP stays off.

Store state under the ruling: iOS TestFlight build 687 is live in
open beta targeting the production profile (the iOS online-flag flip
landed November 9, 2026). The Play lane previously built every track
with online off; it now passes `-PpittechOnlineEnabled=true` in both
its unit-test and bundleRelease invocations, unconditionally for all
tracks, and the Play open-testing release follows that lane fix.
Store builds pointing at production are why R5 — not the device
walkthrough alone — is the remaining leg of this plan.
