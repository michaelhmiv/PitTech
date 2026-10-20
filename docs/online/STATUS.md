## October 20 online rollout checkpoint

MCP #114 is fully planned and paused for online enablement #123. Isolated
Railway staging is now provisioned at `5d246b2`: restricted runtime bootstrap
reported readiness and the API HTTPS health check returned `200/ok`. All online
feature flags remain off pending complete operational acceptance. Backend
Actions 39449305667 passed 178 tests with zero skips. Native billing environment
and account-cache fixes are in review, not yet verified on their final SHA.
See [STAGING.md](STAGING.md) and [20-online-enablement.md](20-online-enablement.md).

# Issue #89 delivery status

Owner authorized implementation on October 14, including the deletion amendment.
Baseline: `82cbe1b335499aba561fc58c91d727055b8ce4fc`. Store-launch PRs #77/#78/#88
remain separate. No online phase is released until its complete parity gate passes.
Slices 00–19 and cleanup #122 are merged; main `743b6a5` carries the tested
cleanup tree (backend 170, Android JVM 376/all emulator lanes, iOS 250 unit/UI).
The online stack is still undeployed and disabled. Owner authorization on
October 20 now covers making it usable and a Firebase test distribution; the
remaining rollout is tracked in #123 and `20-online-enablement.md`.
Historical checkpoints below are source evidence, not a live-service claim.
The post-merge Android push run 39432333361 requires investigation/rerun of its
API 36/font 1.5 process crash before exact-main distribution; iOS push verification
was still running when the rollout audit began.

| PR | Deliverable | State | Evidence |
|---|---|---|---|
| 00 | Design, parity, contracts, CI requirements | Merged (PR #92) | Three schemas validated; concept states rendered/inspected; workflow writing verified October 15 and backend gate installed in draft #102 |
| 01 | Native refresh and core parity | Merged (PR #93) | All configured checks passed at `d9389c9`; iOS run 38592169385 and Android run 38592169411. Native composition/finish and large-text screenshots inspected. Deeper free Insights/provider port remain open |
| 02 | Verdicts, collections, persistent drafts | Merged (PR #95) | Required Android API 36 lanes and iOS workflow passed at `4ee5077`, including expanded data and meal/recipe UI tests. Archive v10, Room v10 |
| 03 | Cook Packs and exports | Merged (PR #97) | Required Android API 36 font 1.15/1.5 and complete iOS workflow passed at `8592689` (runs 38648389211 / 38648389206). Actual reciprocal recorded-cook imports and accessible selective-save/history UI passed; exported workbooks and PNGs reviewed. Native 72 MiB import: 0.14–0.20s, ~70.7 MB peak process physical memory; raw Actions metrics retained |
| 04 | Shared guide presentation | Merged (PR #100) | Both clients pin independently owned recipe stories, share one saved visibility/scope setting across Live/Timeline/Charts, pair semantic actions and overlay distinct reference channels. Required Android API 36 lanes pass at `ad67275`; actual reciprocal guide fixtures pass their native consumers. Latest head `c4000e7`: Android run 38739981239 passes; complete iOS run 38739981674 passes (63 tests, zero failures/skips), with standard/enlarged-text guide and finish captures reviewed |
| 05 | Native share cards | Merged (PR #101) | Both native renderers and data tests pass; iOS and Android exported pixels reviewed at `ceefb36`. Required Android API 36 font 1.15/1.5 lanes pass at `e53192b`; latest head `09de238`: Android run 38739985070 passes; iOS run 38739985175 remains in progress |
| 06 | API/relay | Merged (PR #102) | Node 22 TypeScript relay, private storage adapters, migration, bounded validation/canonicalization, 64-bit codes, independent hashed owner capabilities, durable quotas/cleanup and native canonical fixture consumers. Backend Actions run 38723450831 passes at `ed79b04`: 23 tests pass, zero skips, including real Postgres 16; strict typecheck, runtime audit, canonical fixture comparison, Docker build, six actual native packs in the production image and fail-closed startup all pass. At latest head `dd2b19d`, Android run 38739989368 and backend run 38739989385 pass; complete iOS run 38739989420 passes (66 tests, zero failures/skips). Separate staging resources are prepared but remain unapplied; see STAGING.md |
| 07 | Code/link import | Merged (PR #103) | Native explicit upload/import, secure device-only ownership, retry/cancel, code/link/QR and privacy-limited web fallback implemented. Backend Actions passes at `cfb06b0` (run 38742658407); 64 iOS unit tests, including five relay tests, passed in the preceding run before a newer commit canceled UI execution. At `cfb06b0`, both required Android API 36 lanes pass (run 38742657873); advisory API 37 has a system crash in telemetry. iOS run 38742657769 passes 71/72 tests; the new large-text import test could not reach the keyboard-covered action. Keyboard submission/focus handling and modal-scroll geometry are corrected in follow-up Actions; installed-link/staging evidence remains pending |
| 08 | Resend and backend auth | Merged (PR #104) | Backend Actions 38744834515 passes at `1572488`: 29 tests, zero skips, real Postgres 16, runtime role isolation and Docker checks. Optional proof-bound 15-minute code/link challenges, leased Resend outbox, rotating sessions and narrow runtime roles. Auth disabled by default. `auth.pittech.app` and all four DNS records verified; no mail sent or old keys retired. At `e0b9df6`, Android required lanes pass and iOS summary 229 reports 72/72 passing, zero failures/skips (workflow screenshot stage separate). Native account flows are in draft #105; delivery/operational verification pending |
| 09 | Native account flow | Merged (PR #105) | Both native account forms, proof-bound exchange, device-only vaults, interrupted-request recovery, serialized refresh and offline sign-out remain disabled until Phase 2. At `048705f`, complete iOS Actions 38770804764 passes 83/83 tests, zero failures/skips, and required Android API 36 lanes pass in 38770804862. Installed links, actual delivery and operational release gates remain open; see 09-native-accounts.md |
| 10 | Public data/API | Merged (PR #106) | Isolated public schema, immutable publication/media validation, explicit fork ownership, profiles/likes/discovery and durable quotas; disabled by default. Backend Actions 38756433348 passes at `b2c8068`: 33/33 tests, zero skips, real Postgres 16 and production Docker checks. Native follow-up UI control targeting is pending; see 10-public-api.md |
| 11 | Discovery/save/cook | Merged (PR #107) | Native signed-out discovery, public caches, exact-revision selected save/cook and pinned credit. Backend Actions passes at `0efffe2`; native compilation passes. Fixed missing telemetry environment on the new-meal sheet and the Android test attempting to scroll a fixed confirmation footer. Latest `d9a6309` also clears owned DEBUG fixture photos between text-size launches; runtime results and visual review pending; see 11-discovery.md |
| 12 | Publishing/profiles/forks | Merged (PR #108) | Public HTML, native links/cookbooks, pinned lineage and both actual archive builders. Backend Actions 38799334040 passes at `eeb4e78`; native upload queues compile on both platforms. Both required Android lanes pass all 29 core data tests; the separate discovery footer test fails and is corrected in the next increment. The actual Android publication archive passes canonical backend validation. iOS 38799333789 passes 92/93 with all queue tests passing; the enlarged discovery launch retained old fixture photos after its metadata reset, now corrected in the next increment. Selected publication/identity previews, same-draft updates, cookbook opt-in, removal, likes and exact pending-request previews are added in the next native UI increment. Full runtime/visual/actual-writer validation and owner editing without the original draft remain open; see 12-publishing.md |
| 13 | Moderation/deletion | Merged (PR #109) | Latest checkpoint `464e29c`: backend/Android pass; iOS run 38922725054 failed the existing share-card cancel test. The cumulative sync head `dfe5d8e` passes all 115 iOS tests, including deletion/report/block UI; enlarged deletion/report/source screenshots reviewed. Local-journal removal/operator usability remain open. Historical evidence: Draft #109: backend/web, native deletion and report/block sources; backend 47-test Actions gate passes at `6467867`, native large-text Android passes; remaining native/cleanup/visual gates open. |
| 14 | Private sync | Merged (PRs #111/#112) | At `dfe5d8e`, full iOS run 38922734020 passes 115/115 with zero skips; Android 38922734014 and backend 38922734005 pass. Eleven shared merge vectors, encryption, metadata ledger and lifecycle purge are verified. Encrypted snapshot/CAS and resumable-photo storage pass all 83 backend tests with zero skips at `2a94068` (run 38936939647). Authenticated HTTP and portable projection pass 89/89 backend tests, zero skips, at `3dad210` (run 38939930673). Full companion vocabulary (plan/alert/serving-plan/sharing/attempt/guide kinds, 64 shared vectors) and opt-in production wiring (`PRIVATE_SYNC_ENABLED`, dedicated pool/role, separate keys, strict projector) are implemented with config/wiring tests. Native durable apply, mutation outbox, transport, conflict review UI and both-platform tests are implemented on the slice branch (sync still disabled by default); `releaseReady` stays false, no deployment applied. Fresh Actions evidence required for the native completion; see 14-private-sync.md |
| 15 | Preserved free Insights + analysis foundation | Merged | Free inventory audited on both clients; v1 calculation contract with byte-identical copies, 14-case shared golden fixture, both native engines/adapters and comparison-honesty lines implemented with native unit, emulator (companion count 26), backend-copy and iOS UI tests. Fresh Actions evidence required; see 15-insights.md |
| 16 | Verified subscriptions/ad removal | Merged all-green (#117 at 63014ae / main a85610a) | Server verification, state machine, ASN V2/RTDN handlers, cache v1 and native ad gates; contextual paywall, StoreKit 2 / Play Billing purchase flows with server-side verification, and the entitlement-state UI matrix on both clients with native unit, emulator (companion count 45: ProPaywallTest, ProPaywallNoInterruptTest, ProPaywallContentTest, ProPaywallFlowTest), and iOS UI tests. Store products/sandbox evidence are owner-gated; see 16-subscriptions.md |
| 17 | Eligible community analysis | Merged all-green (#119 at bf156d9) | Real taxonomy v1 + weight bands, four metric summarizers with explicit stall/curve thresholds, `COMMUNITY_AGGREGATION_ENABLED` scheduler, and Pro-authorized aggregate API; stubs remain for framework tests only. Native benchmark cards on both clients behind the slice-16 gate with shared fixtures, the full state matrix, and wording guards (iOS unit + UI; Android JVM + emulator with companion count 57); see 17-community.md |

| 18 | Personal Pro/customization | Merged | Native-only slice: real personal analysis, advanced curves, scoped Year in Smoke recaps, and curated customization (card themes, accessible palettes, restrained profile options, app-icon variants) on both clients behind the slice-16 gate, with shared fixtures and the full state matrix (iOS unit + UI; Android JVM + emulator with companion count 70). QUICK gate only; the full thorough gate runs at stage 19; see 18-pro-personal.md |
| 19 | Complete release verification | Source merged (#121); operational release gated | Main `5eacb00` complete iOS run 39325946232 passes 247/247 with zero skips. Required Android large-text publishing failure and foundation corrections are tracked in #122; see `19-final-gate.md`. This is not cloud/store rollout evidence. |

## October 16 validation checkpoint

Cumulative draft PR #110: `e172754` passed the telemetry public release audit, Play App Bundle and feedback relay gates. Its online services test failed when PostgreSQL forcibly terminated a connection during the isolated publications database teardown. Commit `81f41b1` removes `DROP DATABASE ... WITH (FORCE)` and closes pools before ordinary database removal; online services and feedback relay checks pass on the new commit. Native Android large-text/API 37 and iOS gates are still outstanding; no cloud feature is enabled or released. Staging remains unapplied, and the Resend staging domain awaits DNS verification.

## CI requirements

Keep existing Android API 36 font-scale gates, upgrade tests, advisory API 37,
iOS compatibility/UI tests and signing. Add Node 22 typecheck, schema fixtures,
Postgres migration/integration tests, bounded pack/security/authorization tests,
sync conflict fixtures, subscription provider fixtures and aggregate isolation.

The GitHub connection successfully installed `.github/workflows/online-services.yml`
on October 15. Backend Actions run [38723450831](https://github.com/michaelhmiv/PitTech/actions/runs/38723450831)
passes at `ed79b04`, including real Postgres 16 and production-container validation.
The review copy and addition patch remain in `ci-proposals/`.
Do not call local checks Actions proof.
Physical provider, store-purchase and installed deep-link checks require their
own evidence; never infer them from a successful compile.
