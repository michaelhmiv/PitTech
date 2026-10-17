# Issue #89 delivery status

Owner authorized implementation on October 14, including the deletion amendment.
Baseline: `82cbe1b335499aba561fc58c91d727055b8ce4fc`. Store-launch PRs #77/#78/#88
remain separate. No online phase is released until its complete parity gate passes.

| PR | Deliverable | State | Evidence |
|---|---|---|---|
| 00 | Design, parity, contracts, CI requirements | Draft PR #92 | Three schemas validated; concept states rendered/inspected; backend workflow write path remains open |
| 01 | Native refresh and core parity | Draft PR #93 | All configured checks passed at `d9389c9`; iOS run 38592169385 and Android run 38592169411. Native composition/finish and large-text screenshots inspected. Deeper free Insights/provider port remain open |
| 02 | Verdicts, collections, persistent drafts | Draft PR #95 | Both Android API 36 text-size lanes passed at `4ee5077`. Native reminder test now handles notification permission before waiting for dismissal; a Simulator termination failure in the Beta onboarding test is being retried. Archive v10, Room v10 |
| 03 | Cook Packs and exports | Draft PR #97 | Actual Android↔native recipe/collection/cook files, CSV and workbook fixtures retained with producer provenance. At `cfeea73`, all 53 native unit tests and all 16 Android data / 13 companion tests passed; native UI was canceled by the next push, and the Android count assertion needed correction. Reciprocal recorded-cook import tests and native 72 MiB memory/time measurement are added; their Actions gates and final native UI/visual review remain pending |
| 04 | Shared guide presentation | Pending | |
| 05 | Native share cards | Pending | |
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
