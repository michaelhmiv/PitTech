# Shared online contracts

Archive schema and Room version are independent. Baseline archive v9 / Room v10;
new local fields reserve archive v10 and the next available Room migration.
Legacy imports default absent fields. Full backups retain original media and
their supported larger limits; network packs use the bounded contract below.

| Transport | Boundary |
|---|---|
| Local archive | Existing full library contract; no equipment credentials |
| Cook Pack | Selected recipes/collection/measured story; manifest plus `data/pittech.json`; independently owned assets |
| Relay | Anonymous bearer code; no listing; separate owner renewal/revocation secret |
| Publication | Explicit public allowlist, selected identity and immutable revisions |
| Sync | Authenticated owner-only record mutations, tombstones, assets and base version; never ordinary archive import |

Network pack caps: 64 MiB compressed and expanded, 16 MiB per entry, 1,000
entries. Reject duplicate/unsafe paths, unsupported schemas, conflicting IDs,
bad relationships/checksums, invalid media signatures and decompression bombs
before mutation. No implicit remote URL fetching. Public photos strip EXIF/GPS
and thumbnails while preserving orientation; private originals remain unchanged.

## Identity and lifecycle

- UUID IDs for local/public objects; new cooking attempts have new dish/event/
  reading identities. A saved source is pinned, never silently updated.
- Stable measured attempt identity survives recipe edits/revisions. Copies and
  forks cannot increment the number of measured community cooks.
- Public recipes, collections and attempts have canonical `/c/:id` URLs.
  Anonymous payloads/lineage use opaque display credit and never owner handles,
  account IDs/emails/device IDs/internal paths.
- Relay codes contain at least 64 bits of entropy; renewal/revocation uses a
  separate 128-bit-or-greater capability stored hashed. Initial lifetime 180d.
- Magic-link/code challenges expire after 15min, share one consumption state,
  limit code attempts to five and resend to one/minute. GET never consumes a
  token. Successful POST exchange rotates short access/refresh credentials.
- Public originals are purged on account deletion. Only non-identifying removed
  source references remain; independently owned other-user copies/forks survive.

## Mutations and errors

Every write uses an idempotency ID; public updates also pin the expected
revision. Sync PUT pins the base server version; 409 requires fetch/merge/retry.
Per-record mutation versions contain a logical clock and stable client ID,
with deterministic tie-breaking and recoverable losing edits. Tombstones are
explicit and cannot silently resurrect. Photo upload is separately resumable.

JSON errors use `{ "error": { "code": "...", "message": "..." } }` with
appropriate HTTP status; clients preserve drafts and offer task-local retry.
No secrets or user content in operational logs. Public/private schemas and
fixtures are executable contracts in the backend and native test suites as the
relevant PRs land; the specification does not pretend those tests exist yet.
