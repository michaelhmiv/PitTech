# MCP staging acceptance — issue #114 §10 mapped to evidence

Status key: **CI** = proven by an automated test in this repo's suites ·
**STAGE** = a step to run against the staging deployment once the MCP
stack is enabled there · **CLIENT** = a walkthrough in a real AI client
(ChatGPT or Muse — the two v1 targets) and/or on a phone ·
**DEFERRED** = depends on a separately-tracked build.

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
| 8 | Planning/reminders | PARTIAL: rules + plans sync via M6a tools (CI). **DEFERRED**: on-device action requests (device.actions handoff: server request store + phone approval UI) — the scenario's enable/snooze/timer steps belong to that build. Ordinary sync never arms a rule — enforced by design (no synced enabled flag exists). |
| 9 | Preparation/equipment | CI: companion suite (checklist/prep_combo/equipment/fuel kinds via upsert_companion, kind-scope enforcement). Equipment hours are computed by the apps from their records; MCP writes the same records. |
| 10 | Analysis | CI: insights suite (raw paged + reduced modes with algorithm version + sample counts, summaries, compare, personal insights, recap; benchmarks read the existing aggregation store with its minimum-n). |
| 11 | Connection lifecycle | CI: grants/OAuth suites (revoke/expire/entitlement-loss all deny identically; refresh rotation; family revocation on replay; disconnect-all; management routes session-only, not entitlement-gated). CLIENT: full connect → revoke → reconnect in ChatGPT and Muse, incl. what each client caches and how it recovers. |
| 12 | Account lifecycle | CI: deletion coverage — grants, OAuth tables and the operation journal are deleted in all three account-deletion flows; role SQL grants match. STAGE: delete a staging account with an MCP write in flight; verify no cross-account residue. |
| 13 | Portability | **DEFERRED**: preview_import / apply_import / create_export / get_export_status / render_private_share_card are not yet built (the archive↔sync inverse projection is its own slice). No tool pretends otherwise. |
| 14 | Limits and injection | CI: body caps (256KB transport, 64KB app management), chunk caps, strict argument fields, scope gates, denylist (parity). STAGE: replay/parallel-abuse pass against staging rate limits. Community text is data, never instructions — server has no fetch/execute surface. |
| 15 | Release regression | CI: the repo's existing lanes (Android API 36/37 + font scales, iOS, relay with real Postgres) run on every PR in the stack. CLIENT/STAGE: physical-device + store evidence recorded separately when the rollout reaches it. |
| 16 | Future-feature drift prevention | CI: `Capability parity` runs on every PR (M0) and demonstrably fails on undeclared record types/kinds, missing native symbols, denylisted tools and stale generated docs (self-test job). The M0 field inventory extends it to fields. |

## Rollout order (per the issue)

1. Stack merged; exact-main CI green.
2. Staging: enable `MCP_ENABLED` (reads) with the seven #123 flags as
   already activated; run the STAGE rows; record results here.
3. Client walkthroughs (ChatGPT, Muse) for rows marked CLIENT.
4. Enable `MCP_WRITES_ENABLED` on staging; repeat write rows.
5. Production is a separate, owner-gated decision after the #123
   production tail (device walkthrough, production reconciliation)
   and this checklist are complete.

## Open build items (not acceptance failures — tracked scope)

- Device action requests (scenario 8's device half).
- Portability tools (scenario 13).
- Registry flips: each capability's `mcp.status` moves from
  `planned_disabled` to `implemented` as its slice is verified on
  staging — code landing alone does not flip a status.
