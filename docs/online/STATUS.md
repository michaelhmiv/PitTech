# Issue #89 delivery status

Owner authorized implementation on October 14, including the deletion amendment.
Baseline: `82cbe1b335499aba561fc58c91d727055b8ce4fc`. Store-launch PRs #77/#78/#88
remain separate. No online phase is released until its complete parity gate passes.

| PR | Deliverable | State | Evidence |
|---|---|---|---|
| 00 | Design, parity, contracts, CI requirements | Draft PR #92 | Three schemas validated; concept states rendered/inspected; backend workflow write path remains open |
| 01 | Native refresh and core parity | Draft PR #93 | Toolbar navigation/height fix pushed at `43270a0`; exact-head native runs pending; deeper free Insights/provider port remain open |
| 02 | Verdicts, collections, persistent drafts | Draft PR #95 | Reuses existing result/companion storage; archive v10, Room remains v10; source isolation, owned-photo restore/reimport and stale-draft tests added; native CI/review pending |
| 03 | Cook Packs and exports | Draft PR #97 | Native file export, photo preview and selective recipe import added; partial→whole/repeated import tests own reference media independently. Initial Android gate and iOS archive-boundary tests passed; updated exact-head CI, scoped recorded-history import, workbook/CSV parity and actual writer fixture exchange remain open |
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
