# MCP staging acceptance — issue #114 §10 mapped to evidence

Status key: **CI** = proven by an automated test in this repo's suites ·
**STAGE** = a step to run against the staging deployment once the MCP
stack is enabled there · **CLIENT** = a walkthrough in a real AI client
(ChatGPT or Muse — the two v1 targets) and/or on a phone ·
Every scenario's build is now complete (owner directive 2026-10-23:
nothing deferred) — what remains open is evidence, not code.

Nothing here is "passed" until its evidence exists; merge state,
staging state and client state are recorded separately, per the issue.

| # | Scenario | Evidence path |
|---|---|---|
| 1 | Whole cooking loop | CI: writes suite composes multi-dish meals, notes, lifecycle (test/mcp-library-writes.test.ts, mcp-photos.test.ts). STAGE: repeat via a real grant against staging; verify the same meal on Android + iOS after sync. CLIENT: drive it from ChatGPT. |
| 2 | Recipe editing | CI: M4/M4b suites (partial-edit inheritance, duplicate with remapped revisions, archive/restore/favorite, save_dish_as_recipe). STAGE: combine three recipes via compose_meal; confirm originals untouched. |
| 3 | Community boundary | CI: community suite (private save pins source + credit; the codebase contains no publish/like/fork MCP tools — parity denylist enforces). STAGE: present an MCP token to native write routes → rejected (management routes are session-only; proven in CI for /v1/me/mcp/*). |
| 4 | Photo parity | CI: photos suite (chunked upload → finish validation → attach → image content read → caption → remove; attach-before-complete refused). STAGE: upload from ChatGPT with a real attachment; interrupt mid-upload and resume via get_photo_upload_status. CLIENT: ChatGPT photo handoff is the known per-client variable — record the result explicitly. |
| 5 | Concurrent phones and AI | CI: CAS/idempotency suites (receipt-first retries, sync_version_changed mapping, writer stamps). STAGE: Android offline note + iOS recipe edit + MCP event, reconnect all, verify merge + conflicts surface. |
| 6 | Corrections and deletion | CI: undo suite (drift refusal, restore-as-copy with new identities, tombstones). Note: MCP reads/writes synced records only — raw device samples are never MCP-mutable by construction. |
| 7 | Finish reliability | CI: transition suite (status machine, idempotent receipts: same key = same outcome, no duplicate completion). |
| 8 | Planning/reminders | CI: rules + plans sync via M6a tools; device action requests are built end to end — server request store + MCP tools (test/mcp-device.test.ts, incl. opt-in, finished-meal refusal, expiry, claim/receipt state machine), Android executors (McpDeviceActionsTest) and iOS executors (McpDeviceActionsTests) wired to the real reminder/timer repositories. Ordinary sync never arms a rule — enforced by design (no synced enabled flag exists); arming happens only when a registered phone claims a request it opted into. CLIENT: on a real phone via the Firebase dev build — request enable/snooze/timer from the assistant, watch the phone claim, apply and report; kill the phone's connectivity mid-request and confirm the request expires honestly rather than applying late. |
| 9 | Preparation/equipment | CI: companion suite (checklist/prep_combo/equipment/fuel kinds via upsert_companion, kind-scope enforcement). Equipment hours are computed by the apps from their records; MCP writes the same records. |
| 10 | Analysis | CI: insights suite (raw paged + reduced modes with algorithm version + sample counts, summaries, compare, personal insights, recap; benchmarks read the existing aggregation store with its minimum-n). |
| 11 | Connection lifecycle | CI: grants/OAuth suites (revoke/expire/entitlement-loss all deny identically; refresh rotation; family revocation on replay; disconnect-all; management routes session-only, not entitlement-gated). CLIENT: full connect → revoke → reconnect in ChatGPT and Muse, incl. what each client caches and how it recovers. |
| 12 | Account lifecycle | CI: deletion coverage — grants, OAuth tables, the operation journal, export/preview artifacts and the device registry + command ledger are deleted in all three account-deletion flows; role SQL grants match. STAGE: delete a staging account with an MCP write in flight; verify no cross-account residue. |
| 13 | Portability | CI: portability suite (test/mcp-portability.test.ts — export → preview → apply round trip into a fresh snapshot, stale/wrong-hash/replay refusals, changed-vs-conflict dispositions, selective apply, unresolvable-photo blocking; Postgres store suite for the artifact/preview store). The download route is session-or-grant authorized and expires with the artifact. CLIENT: export from the assistant, download the archive, import it back and diff the library; render a share card for a finished cook and view it in the app. |
| 14 | Limits and injection | CI: body caps (10 MB MCP transport, sized for archive imports; tight per-route caps on grant/device management), chunk caps, strict argument fields, scope gates, denylist (parity). STAGE: replay/parallel-abuse pass against staging rate limits. Community text is data, never instructions — server has no fetch/execute surface. |
| 15 | Release regression | CI: the repo's existing lanes (Android API 36/37 + font scales, iOS, relay with real Postgres) run on every PR in the stack. CLIENT/STAGE: physical-device + store evidence recorded separately when the rollout reaches it. |
| 16 | Future-feature drift prevention | CI: `Capability parity` runs on every PR (M0) and demonstrably fails on undeclared record types/kinds, missing native symbols, denylisted tools and stale generated docs (self-test job). The M0 field inventory extends it to fields. |

## Rollout order (per the issue, updated by the owner's 2026-10-23 directive)

1. Stack merged; exact-main CI green.
2. Firebase dev distribution of the merged main; the owner runs the
   CLIENT rows on his own phones (device actions end to end, photo
   handoff, export/import round trip) and the ChatGPT/Muse
   walkthroughs for rows marked CLIENT. Results are recorded here.
3. Staging enablement (`MCP_ENABLED`, then `MCP_WRITES_ENABLED`) is a
   separate owner-gated decision; the STAGE rows run then.
4. Production is a separate, owner-gated decision after the #123
   production tail (device walkthrough, production reconciliation)
   and this checklist are complete.

## Verification remaining (no open build items)

- The CLIENT/STAGE evidence above — every build item from the issue
  is implemented, including device actions and portability.
- Registry flips: each capability's `mcp.status` moves from
  `planned_disabled` to `implemented` as its slice is verified —
  code landing alone does not flip a status, and neither does this
  document.
- One recorded platform difference: iOS observes telemetry but runs
  no recording engine, so collection pause/resume/cadence requests
  answer `needs_attention` on iPhone (the recording device owns
  them). Android executes them against its recording repository.
