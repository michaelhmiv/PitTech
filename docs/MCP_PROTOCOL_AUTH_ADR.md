# ADR — PitTech MCP protocol & auth (ChatGPT + Muse only)

Status: PROPOSED with M0 (issue #114). Decisions below are the working contract for M1/M2;
anything marked "to test" is an open question until observed client evidence replaces it.
Targets locked by Michael 2026-10-22: **ChatGPT and Muse**. Claude is out of scope for v1.

## Why per-client decisions are needed at all

MCP standardizes the wire protocol, not the client product behavior around it:
discovery/caching of the tool list, the OAuth dance a given product will actually
perform, whether a bearer/PAT header can be configured at all, and whether file
*bytes* from a chat ever reach a remote MCP server are all client-specific.
Issue #114 §5/§8 therefore require a tested client/version matrix, not a
"spec-compliant, works everywhere" claim.

## Protocol decisions to freeze in M0

1. Transport: Streamable HTTP at a versioned `/mcp` path on the existing api-service. No legacy HTTP+SSE endpoint.
2. Spec/SDK pin: issue cites MCP spec 2026-07-28. At implementation time, recheck
   (a) the current official spec revision, (b) the official TypeScript SDK version that
   implements it, (c) what ChatGPT and Muse actually negotiate. Pin all three in the
   ADR; add a compatibility adapter only for explicitly tested older behavior.
3. Discovery: tool catalog is derived from the capability registry + registered handlers
   at deploy time. Clients that cache descriptors need a documented reconnect/refresh
   path (goes in docs/MCP_SETUP.md at M8).
4. Every tool: strict input/output schemas, read/write/destructive annotations,
   structured content + concise text, frozen error vocabulary from #114 §8
   (pro_required, version_conflict, unsupported_schema, photo_incomplete,
   quota_exceeded, device_unavailable, permission_required, ...).

## Auth: two paths, one principal

Both paths resolve to the same narrow MCP principal (account id + grant id + scopes);
tool arguments never carry an owner id, and an MCP credential is rejected on native
publishing / billing / auth / operator routes (negative test in M2).

### Path A — OAuth (the release path for real clients)
- Protected-resource + auth-server metadata, authorization-code + PKCE, audience/resource
  binding, exact redirect URIs, short-lived codes, refresh rotation, revocation.
- Identity proof reuses PitTech's first-party email magic-link/code verification; consent is
  an explicit first-party page listing the requested scopes in plain language.
- Per-client unknowns to test, not assume:
  - ChatGPT (connector/developer-mode flow): which discovery document shape it fetches,
    which redirect URI(s) it registers, whether it demands dynamic client registration
    or a pre-registered client id/secret, token lifetime expectations.
  - Muse (custom connector flow): same questions, answered independently — passing
    ChatGPT's flow proves nothing about Muse's.

### Path B — Personal access token (pilot / advanced)
- Created only in More -> Account -> Connect your AI (both apps), with nickname, scope
  selection, expiry; shown once; stored only as a domain-separated hash.
- Header-carried only, never in URLs or logs. Revocation + disconnect-all take effect on
  every subsequent call, including in-flight photo chunks and queued device commands.
- Which of ChatGPT / Muse can actually present a PAT header in their remote-connector
  UI is a test result, not an assumption. If a client can't, PAT is a developer/pilot
  path for that client and OAuth is its only release path — say so in the matrix.

## Scopes (frozen in M0, from #114 §5)

library:read, library:write, library:delete, media:read, media:write, planning:write,
device_actions:request, exports:read, imports:write, community:read.
No public-write, billing, credential-management, admin, or hardware-control scope exists.
Destructive/bulk/import operations additionally require an expiring preview/confirmation
bound to exact ids + versions + content hash; stale previews fail.

## Photos / attachments: the open question per client

A chat attachment is not automatically available to a remote MCP server. For each target
client, M0/M5 must test and record one of:
(a) client can hand file bytes to the server through a documented mechanism, or
(b) it cannot -> first-party upload handoff flow (user uploads via PitTech-owned page/app),
    with the tool returning pending/completed honestly.
The chunked protocol itself reuses the landed private-photo contract (1 MiB chunks,
16 MiB max, SHA-256, bounded decode — revalidate at M5 against final merged values).

## Client test matrix (skeleton — filled with observed results, never aspirations)

| Client | Version/surface | OAuth | PAT | Read | Write | Photo in | Photo out | Reconnect/refresh | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| ChatGPT | TBD at M0 | ? | ? | ? | ? | ? | ? | ? | untested |
| Muse | TBD at M0 | ? | ? | ? | ? | ? | ? | ? | untested |

A client ships in support claims only when every applicable cell has observed evidence.
