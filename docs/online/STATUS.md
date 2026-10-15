# Issue #89 delivery status

Owner authorized implementation on October 14, including the deletion amendment.
Baseline: `82cbe1b335499aba561fc58c91d727055b8ce4fc`. Store-launch PRs #77/#78/#88
remain separate. No online phase is released until its complete parity gate passes.

| PR | Deliverable | State | Evidence |
|---|---|---|---|
| 00 | Design, parity, contracts, CI requirements | Draft PR #92 | Three schemas validated; concept states rendered/inspected; workflow writing verified October 15 and backend gate installed in draft #102 |
| 01 | Native refresh and core parity | Draft PR #93 | All configured checks passed at `d9389c9`; iOS run 38592169385 and Android run 38592169411. Native composition/finish and large-text screenshots inspected. Deeper free Insights/provider port remain open |
| 02 | Verdicts, collections, persistent drafts | Draft PR #95 | Required Android API 36 lanes and iOS workflow passed at `4ee5077`, including expanded data and meal/recipe UI tests. Archive v10, Room v10 |
| 03 | Cook Packs and exports | Draft PR #97 | Required Android API 36 font 1.15/1.5 and complete iOS workflow passed at `8592689` (runs 38648389211 / 38648389206). Actual reciprocal recorded-cook imports and accessible selective-save/history UI passed; exported workbooks and PNGs reviewed. Native 72 MiB import: 0.14–0.20s, ~70.7 MB peak process physical memory; raw Actions metrics retained |
| 04 | Shared guide presentation | Draft PR #100 | Both clients pin independently owned recipe stories, share one saved visibility/scope setting across Live/Timeline/Charts, pair semantic actions and overlay distinct reference channels. Required Android API 36 lanes pass at `ad67275`; actual reciprocal guide fixtures pass their native consumers. Latest iOS run 38715355029 built successfully but failed the native compatibility-test step; investigation remains open |
| 05 | Native share cards | Draft PR #101 | Both native renderers and data tests pass; iOS and Android exported pixels reviewed at `ceefb36`. Required Android API 36 font 1.15/1.5 lanes pass at `e53192b`; latest iOS run 38715368055 built successfully but failed the native compatibility-test step; investigation remains open |
| 06 | API/relay | Draft PR #102 | Node 22 TypeScript relay, private storage adapters, migration, bounded validation/canonicalization, 64-bit codes, independent hashed owner capabilities, durable quotas/cleanup and native canonical fixture consumers. Backend Actions run 38723450831 passes at `ed79b04`: 23 tests pass, zero skips, including real Postgres 16; strict typecheck, runtime audit, canonical fixture comparison, Docker build, six actual native packs in the production image and fail-closed startup all pass. Android passes at `fc1b8cb`; iOS run 38715388629 built successfully but failed compatibility tests. Isolated staging remains pending |
| 07 | Code/link import | Pending | |
| 08 | Resend and backend auth | Pending | |
| 09 | Native account flow | Pending | |
| 10 | Public data/API | Pending | |
| 11 | Discovery/save/cook | Pending | |
| 12 | Publishing/profiles/forks | Pending | |
| 13 | Moderation/deletion | Pending | |
| 14 | Private sync | Pending | |
| 15 | Shared free Insights | Pending | |
| 16 | Verified subscriptions/ad removal | Pending | |
| 17 | Eligible community analysis | Pending | |
| 18 | Personal Pro/customization | Pending | |
| 19 | Complete release verification | Pending | |

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
