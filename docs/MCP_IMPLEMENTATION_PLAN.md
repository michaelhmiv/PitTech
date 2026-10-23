# PitTech MCP — implementation plan (issue #114, M0)

Status: M0 contract work in progress on `work/issue-114-mcp-m0`.
MCP is **not implemented or released**. Nothing in this plan enables a flag,
deploys a service, or claims client support. The full product contract lives
in issue #114; this document is the in-repo framing for building it.

## Product contract (settled in #114 — do not re-litigate in PRs)

- MCP access is a PitTech Pro perk, checked server-side against the verified
  entitlement on every call. A client flag or token scope cannot grant Pro.
- The owner's own private data (meals, recipes, logs, photos, plans) is
  read-write through MCP. No AI-provenance label is added to user content.
- Community content is **read-only** through MCP. There is no MCP tool or
  credential authority to publish, like, fork publicly, contribute attempts,
  change a public profile, or moderate. Publishing stays a deliberate in-app
  action.
- Equipment is **read-only**. MCP never ignites, shuts down, sets a
  temperature, or changes fan/auger/Wi-Fi/firmware/configuration. Editing a
  recorded target or an equipment journal profile is a data write, not a
  command to a grill (see also the hard rule in `AGENTS.md`).
- Photos are part of the first complete release, not a later add-on.
- Account, billing, credential and consent operations stay in trusted
  first-party UI.
- Offline-first remains: losing Pro or disabling MCP never touches local
  records or ordinary free features.
- Target clients for v1 testing and support claims: **ChatGPT and Muse**
  (owner decision, 2026-10-22). Each client is certified separately — auth
  flow, token handling, tool discovery/caching and attachment handoff all
  differ per client despite the shared protocol. See
  `docs/MCP_PROTOCOL_AUTH_ADR.md`.

## Three interfaces, one product — how parity is enforced, not remembered

Android, iOS and MCP are three maintained interfaces to the same product.
The mechanism (issue #114 §3.1) is a deliverable of M0, not a reminder:

1. **One machine-readable registry** — `contracts/capabilities-v1.json`
   (schema: `contracts/capabilities-v1.schema.json`). Each capability
   declares its classification, scopes, projection record types / definition
   kinds, per-operation coverage (read / write / delete / media /
   activation are separate — a read-only mapping does not satisfy a writable
   feature), real Android/iOS source-symbol bindings, and its MCP tool
   binding or explicit handoff/prohibition.
2. **Generated views** — `docs/MCP_PARITY.md` is generated from the registry
   by `scripts/mcp-parity.mjs`; hand edits are drift and fail CI.
3. **A required, always-reporting check** — `.github/workflows/capability-parity.yml`
   runs on **every** pull request with no path filter (the online-services
   workflow's path filters omit ordinary Kotlin/Swift changes, which is
   exactly where drift starts). It fails when:
   - the private-sync projection gains a record type or definition kind
     with no declared MCP disposition — *adding a private feature to the
     sync contract breaks the build until MCP is factored in*;
   - a platform binding names a symbol that does not exist in the native
     sources (a stub cannot pass as coverage);
   - a prohibited capability exposes a tool, or any tool name matches the
     public-write / hardware / credential denylist;
   - the generated parity doc is stale.
   The checker's `--self-test` mode proves each of these failure modes on
   mutated fixtures, in CI, on every run.
4. **Process hooks** — the PR template requires a capability-impact
   declaration, and `AGENTS.md` carries the three-interface rule. A "no
   surface change" disposition needs a concrete reason; a feature already
   enabled in either app cannot hide behind a future-feature placeholder.
5. **Release together** — from MCP's first public release, a private feature
   is release-complete only when its Android, iOS and MCP coverage passes.
   Sequenced work may merge behind disabled flags; it may not ship enabled
   in the apps with MCP permanently parked as "later".

## Architecture in one paragraph

Serve authenticated Streamable HTTP at a versioned `/mcp` path from the
existing Node 22 `api-service` (Railway), behind `MCP_ENABLED` /
`MCP_WRITES_ENABLED` flags that default off. Build a `PrivateLibraryService`
command/query layer over the landed `PrivateSnapshots` / `PrivatePhotos`
contracts: the current REST sync API is snapshot GET/PUT, not a resource API,
so MCP needs real domain operations with expected versions, durable operation
receipts, CAS with bounded rebase, and structured conflicts — never
last-write-wins over a whole library, and never a second store of authored
meals/recipes. Every mutation rechecks account, Pro entitlement, scope and
per-connection/per-account budgets at the write boundary. Server-committed is
not phone-applied; results carry freshness and sync status honestly.

## Slice sequence (from #114 §9 — summaries, see the issue for exit evidence)

- **M0 — Capability contract and enforced parity** *(this work)*: registry,
  schema, generator, parity CI, this plan, protocol/auth ADR, PR-template and
  AGENTS rules, plus the field-level inventory: all 459 projection fields
  (21 record types + object definition kinds) are inventoried in the
  registry with an MCP access classification (editable / identity / system /
  association / content / not_exposed), enforced complete by the same
  checker. M3/M4 handlers are written against this inventory.
- **M1 — Connect your AI, grants and Pro**: OAuth (discovery + PKCE + consent)
  for the target clients plus the hashed personal-token pilot path, narrow
  MCP principal/scopes, revocation/disconnect-all, both native connection
  screens, entitlement adapter. Off by default.
- **M2 — Transport, reads and durable command spine**: pinned MCP SDK,
  `/mcp` adapter, read tools, `PrivateLibraryService`, receipts/CAS/quotas.
- **M3 — Meals, dishes, notes and results**: first data round trip —
  MCP create/log/finish → both apps read/edit → MCP reads canonical back.
- **M4 — Recipes and composition**: immutable revisions, expected-parent
  conflicts, private community save with pinned source credit.
- **M5 — Photos and private files**: chunked resumable upload, attach /
  caption / detach, private import/export/cards. First complete everyday
  vertical slice.
- **M6 — Planning, preparation and explicit phone actions**: playbooks,
  checklists, equipment/fuel journal, and the separate device-command path
  for reminders/timers with claim/apply receipts and truthful states.
- **M7 — Existing analysis and remaining parity**: TS adapter for the merged
  Insights engines, pinned to the same fixtures; close every registry row.
- **M8 — Release proof and controlled rollout**: full three-interface
  acceptance suite (the 16 scenarios in #114 §10), per-client certification
  evidence for ChatGPT and Muse, staging verification, bounded pilot,
  read-then-write enablement, kill-switch tests.

Critical path: M0 → M1/M2 → M3/M4/M5 → M6/M7 → M8. "Merged", "staging
deployed", "phone applied" and "released" are separate evidence states;
no slice may borrow evidence from another.

## Sequencing against issue #123

M0–M7 are built and merged behind disabled flags. #123's controlled
activation completed on staging on 2026-10-22 (all seven online flags on,
live-verified), so the accounts/sync/entitlement prerequisite for MCP is
satisfied on staging. Still required before *public* MCP access: the
release-ready projection (it still declares `releaseReady: false`), the
#123 physical-device walkthrough and test delivery, production
reconciliation, and the M8 release proof in issue #114 §10/§11. The owner
lifted the MCP merge hold on 2026-10-22 after #123 merged (PR #147,
main `8c91090`).
