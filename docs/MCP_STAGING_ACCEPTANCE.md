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

## Pre-pass results — 2026-10-27 (STAGE rows, agent-run)

Method: a scripted MCP client (MCP over HTTPS, protocol 2025-03-26)
against `https://api-staging.pittech.app/mcp`, authenticated with
personal-access-token grants minted through the real `McpGrants`
path (the same grant store the in-app "Connect your AI" flow and the
OAuth flow write to). Two staging-only fixture accounts:
`mcp-acceptance-a@staging.pittech.app` (primary) and
`mcp-acceptance-b@staging.pittech.app` (throwaway: import target and
the deletion row). 77 tools were listed. Before this pass, staging
could not pass the MCP Pro gate at all (see "Entitlement
prerequisite" below); with that fixed, the rows below ran end to end.

- **Row 1 (cooking loop, STAGE portion) — PASS.** Meal created,
  notes, manual readings (food + pit), pause/resume, results +
  lessons saved, completed, verified by re-read. Remaining: the
  CLIENT portions (same meal verified on Android + iOS after sync;
  driven from ChatGPT).
- **Row 2 (recipe editing) — FAIL, defect filed (#227).**
  `compose_meal` rejects every input against a real library
  (`invalid_private_library`); the same defect breaks
  `add_recipe_to_meal` and `save_dish_as_recipe`, and
  `duplicate_meal_setup` fails on a stale lifecycles record shape.
  Recipe create/update/archive/restore/favorite/duplicate/delete,
  search and revisions all PASS, and the three source recipes were
  verified byte-identical after the compose attempts.
- **Row 3 (community boundary, STAGE portion) — PASS.**
  `search_community_recipes` answered (staging community library is
  empty, so no positive save could be exercised); an MCP grant
  token presented to `POST /v1/me/mcp/grants`, `GET
  /v1/me/mcp/grants` and `GET /v1/me/entitlement` was rejected (401)
  on every route. tools/list contains no community write tools.
- **Row 4 (photo parity, STAGE portion) — PASS.** A real
  2,161,188-byte PNG uploaded in 3 chunks; the upload was
  interrupted mid-way and resumed via `get_photo_upload_status`
  (received parts [0] reported, remainder sent, completion verified);
  downloaded bytes were identical (same SHA-256). Caption, attach,
  list, read, remove and cancel also exercised. Non-image bytes are
  refused at completion, as designed. Remaining: the CLIENT ChatGPT
  attachment handoff.
- **Row 5 (concurrent phones + AI) — NOT RUN** (needs two phones;
  CI CAS/idempotency evidence stands).
- **Row 6 (corrections/deletion) — PASS.** `undo_private_operation`
  on a note removed it; the journal/audit read paths answered.
- **Row 7 (finish reliability, STAGE portion) — PASS.** Repeating a
  keyed mutation with the same idempotency key returned the original
  receipt (no duplicate note). Repeating a terminal transition
  without a key returns a clear state error; nothing duplicated.
- **Row 8 (planning/reminders, STAGE portion) — PARTIAL, defect
  filed (#228).** Reminder rules work with the projection
  contract's native trigger shapes (proven: duration rule created,
  listed, deleted), but the vocabulary the tool contract itself
  teaches (`kind: "temperature"`, `{type: "temperature", threshold}`)
  can never validate — the contract's trigger cases are
  duration/clock/action/historical/probe. Device actions:
  `list_my_devices` (0 devices), untargeted
  `request_device_action` → state `saved`, get + cancel exercised.
  Execution on a real phone remains CLIENT.
- **Row 9 (preparation/equipment) — PASS.** Companions created,
  read, listed and deleted with complete contract documents
  (checklist, equipment, fuel log with epoch `at`, meal plan).
- **Row 10 (analysis) — PASS.** Cook summary, temperature history
  (raw 2 points / reduced), two-meal compare, personal insights,
  community benchmarks (real aggregation run answered) and the
  private recap all returned live results.
- **Row 11 (connection lifecycle) — STAGE portion PASS**
  (connection status/capabilities/sync status live; grant
  revocation proven by the row-12 deletion: the deleted account's
  grant returns 401). The connect → revoke → reconnect walkthrough
  in ChatGPT and Muse remains CLIENT.
- **Row 12 (account lifecycle, STAGE) — PASS on the throwaway
  account.** Deletion requested through the real provider-event
  path after real MCP writes; the deletion staged through the sweep
  (state `purging` at request); afterwards the account's grant
  returns 401 over HTTPS and its `auth.users` row is gone. The
  primary fixture account was verified unaffected. (The
  provider-event path purges via the sweep rather than instantly at
  request time; one earlier crash-looped series of attempts also
  completed a deletion of the first throwaway account — same end
  state, observed after the fact.)
- **Row 13 (portability, STAGE portion) — PASS.** `create_export`
  produced the full archive; the authorized download route returned
  it; `preview_import` → `apply_import` into the fresh throwaway
  account applied 24 records and the imported cook listed alongside
  the target account's own meal; a second preview showed no new
  dispositions. Sharing drafts: create/get/list/update/delete with
  the canonical document, and `render_private_share_card` returned
  a card. Remaining CLIENT: viewing the rendered card in the app.
- **Row 14 (limits/injection, light-touch) — PASS.** An ~11 MB
  `/mcp` body was refused 413 (`request_too_large`); a burst of 12
  unauthenticated calls all returned 401.
- **Rows 15/16 — unchanged** (CI lanes; parity gate green on the
  evidence PR).

### Defects found (both masked by the same test-fidelity gap)

The library suites' `FakeSnapshots` never runs the production
projection validator (`privateLibraryEnvelope`), so handler-built
records and client payloads that the real validator rejects passed
CI. Details and fix shapes: **#227** (composition tools) and
**#228** (reminder-rule trigger vocabulary). Also observed: an
in-process `McpServer.handle` create_meal call from the staging
fixture process returned -32603 while the identical call over HTTPS
succeeded — noted in #227; the production client path is HTTPS only.

### Registry flips recorded with this evidence

`mcp.status` → `implemented`: connection.context, meals.log,
media.photos, planning.playbooks_guides, insights.personal,
portability.private. Left `planned_disabled`: meals.dishes
(duplicate_meal_setup broken, #227), recipes.library
(save_dish_as_recipe broken, #227), recipes.composition (#227),
reminders.rules (#228), community.lookup (no community content
exists on staging to exercise the positive path),
meals.ingredients_targets_results / preparation.checklists /
equipment.journal (zero-tool capabilities whose records were
exercised through companions/meals; flip with their host
capabilities). device.actions unchanged (`handoff_only`).

### Entitlement prerequisite (why no client had ever connected)

MCP checks Pro entitlement on every request. Two staging blockers
were fixed and deployed 2026-10-27 (PR #224): (1) the MCP
entitlement lookup used the `production` store environment, while
staging purchases are sandbox — it now uses `sandbox` on staging;
(2) a staging-only test-Pro mechanism (`MCP_TEST_PRO_EMAILS`,
refused at boot outside staging) grants Pro by account email with
no billing records written. The owner's staging account
(sportfolioholdings@gmail.com) and both fixture accounts are on
the list; the store product remains DRAFT, per the owner's
standing order.
