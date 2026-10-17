# Issue #89 delivery status

Owner authorized implementation on October 14, including the deletion amendment.
Baseline: `82cbe1b335499aba561fc58c91d727055b8ce4fc`. Store-launch PRs #77/#78/#88
remain separate. No online phase is released until its complete parity gate passes.

| PR | Deliverable | State | Evidence |
|---|---|---|---|
| 00 | Design, parity, contracts, CI requirements | Draft PR #92 | Three schemas validated; concept states rendered/inspected; backend workflow write path remains open |
| 01 | Native refresh and core parity | Draft PR #93 | All configured checks passed at `d9389c9`; iOS run 38592169385 and Android run 38592169411. Native composition/finish and large-text screenshots inspected. Deeper free Insights/provider port remain open |
| 02 | Verdicts, collections, persistent drafts | Draft PR #95 | Required Android API 36 lanes and iOS workflow passed at `4ee5077`, including expanded data and meal/recipe UI tests. Archive v10, Room v10 |
| 03 | Cook Packs and exports | Draft PR #97 | Required Android API 36 font 1.15/1.5 and complete iOS workflow passed at `8592689` (runs 38648389211 / 38648389206). Actual reciprocal recorded-cook imports and accessible selective-save/history UI passed; exported workbooks and PNGs reviewed. Native 72 MiB import: 0.14–0.20s, ~70.7 MB peak process physical memory; raw Actions metrics retained |
| 04 | Shared guide presentation | Draft PR #100 | Both clients pin independently owned recipe stories, share one saved visibility/scope setting across Live/Timeline/Charts, pair semantic actions and overlay distinct reference channels. Source-deletion, three-dish alignment, gaps/quality, persistence and UI tests added; Actions/visual and actual guide-fixture exchange gates pending |
| 05 | Native share cards | Draft implementation | Both native renderers, explicit public content selection, recipe/meal entry points, durable options, PNG preview/save/system sharing and cancellation tests added. Actions compile, native pixel review and final test gates pending |
| 06 | API/relay | Pending | |
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

The existing App credential cannot edit workflow files. The required backend
workflow will be prepared as a reviewable patch; establish a workflow-capable
write path before merging backend work. Do not call local checks Actions proof.
Physical provider, store-purchase and installed deep-link checks require their
own evidence; never infer them from a successful compile.
