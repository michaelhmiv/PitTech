# PR 14 — Private sync (in progress)

This draft is a merge/encryption foundation, not working cloud backup. Accounts,
community and private sync stay disabled. Nothing is uploaded by these helpers.
The local library remains authoritative for immediate Log/Finish actions.

The three-way merge compares the last acknowledged base with local and remote
snapshots. Every mutation advances a logical counter beyond observed counters;
phone calendar time is never a conflict tie-breaker. Stable device UUIDs break
concurrent logical ties consistently. Both losing and winning edits remain in the
result until the native transaction saves the new records and recoverable review
copies. Opposite merge directions produce the same winners and conflict IDs.

Omitting a prior row is invalid; deletion requires an explicit tombstone with no
payload/assets. A tombstone wins a concurrent offline edit and preserves that edit
for recovery as a new identity. No tombstone compaction is implemented. Counter
exhaustion or capacity failure stops before applying anything. Different recipe
revision IDs remain independent immutable branches. Changing a measured sample
or immutable recipe revision under its existing identity blocks the commit, with
both versions retained for review. New sample identities and corrected-reading
relationships still require explicit native projection rules.

The current generic merge vocabulary covers library data and rule definitions.
It excludes account/relay/publication operation stores, provider credential stores,
recording state, probe-assignment activation, staged timers and reminder delivery
state. A generic payload validator does **not** prove private-library projection
or relational safety; those are mandatory unfinished boundaries. Native import
must never arm rules or start recording implicitly. No public route or aggregation
job may call a private-sync reader.

Current bounds: 100,000 records, 32 MiB JSON per snapshot, 16 MiB per record,
100 asset references per record and 64 MiB merged result including conflicts.
Identifiers may contain safe composite local keys; device UUIDs and logical
counters are separate metadata. These are transport bounds, not changes to local
archive limits. Photo objects transfer separately. Capacity errors retain the
original local library and outbox. No automatic data pruning is authorized.

A separate AES-256-GCM key ring authenticates owner UUID, random object UUID,
object kind, plaintext SHA-256 and key ID. Snapshot/photo plaintext limits are
32/16 MiB. Fresh nonces make repeated encryption distinct; decryption rejects
owner substitution, altered metadata, tampering and unknown/removed keys. New
objects use the configured current key, while retained old keys permit staged
rotation. This is server-managed encryption at rest, **not** end-to-end encryption.
Real key provisioning, runtime upload/read configuration, encrypted snapshot
transport and rotation/re-encryption verification remain unimplemented gates.
The draft metadata ledger and lifecycle cleanup adapter are described below.
No real keys or cloud resources have been created.

Eleven identical shared cases cover independent notes, clock skew, recoverable
same-note conflicts, offline edit/deletion, immutable identity collisions,
concurrent recipe branches, equivalent content and rejected omissions/replays.
Backend unit checks pass locally; new native implementations/tests require
Actions. Existing lifecycle tests and native release gates remain independently
open in PR 13.

Still to implement: strict native library projection and per-record mutation
tracking; encrypted authenticated API and resumable photo transfer; durable
account-bound native outboxes/association; conflict review/restore UI; inactive
rule import; verified lifecycle purge integration; supported background opportunities; full
two-device Android↔iOS and real Postgres ownership/race evidence. No backup success
or completed sync claim is made by this draft.

Migration 006 adds an isolated private metadata ledger (account head/version,
opaque encrypted-object ownership/hash/leases, operation receipts and anonymous
global budgets). It contains no authored library payload. The private-sync runtime
role cannot read authentication identities, relay or public publication payloads;
public roles cannot read private tables. Cleanup has only explicit metadata
read/delete and lease/head updates. Apply `private-sync-runtime-role.sql` after
the lifecycle role script; lifecycle startup checks those grants before serving.
No infrastructure migration has been applied outside CI by this work.

The lifecycle adapter refuses an unknown private table and requires an accepted
server deletion. It clears head/operation references, fences owned storage keys,
retains failed objects and leases, and removes the private account row only after
every object is gone. It never decrypts private objects. File/S3 unit races now
cover the private namespace, including cleanup before a late upload. A new real
Postgres test covers forbidden role access, late writes after deletion, unknown
schema fail-closed behavior, storage outage/retry, retained independent owner data
and generic completion mail. That test requires Actions before claiming success.

Snapshot metadata transition validation preserves explicit tombstones and existing
recovery copies. Dropping a saved conflict requires its explicit resolution ID;
rewriting that copy is rejected. A concurrent tombstone may supersede a greater
logical-clock edit only with the exact retained losing edit. These generic helpers
remain unexposed: strict native projection/relationships, encrypted snapshot and
photo API, durable application and conflict UI are still required.

Backend Actions run 38922430944 at bfa8df7 passed all 68 tests with zero
skips, including the real-Postgres private-sync cleanup/isolation test and
production-container validation. The test now performs the final deletion sweep
after generic notice delivery before expecting complete status, and closes pools
without forced database termination. No external database was changed.

## Encrypted snapshot storage boundary

`PrivateSnapshots` implements owner-scoped reads, exact mutation receipts and a
versioned compare-and-swap commit over encrypted objects. It is internal only: no
production HTTP route, complete production projector, real key/configuration or native upload calls
are connected. The dependency-injected HTTP adapter below is exercised only in tests. A required projection callback must eventually validate the exact
native library vocabulary and relationships; generic metadata validation is only
the synthetic test adapter. Asset references require completed, verified, owner-scoped photo metadata.
The internal owned-photo path is described below and remains unexposed. This remains an incomplete sync feature.

A short account-locked transaction reserves metadata and quota before object I/O.
The ciphertext is written create-only and read back with authenticated owner/object
context and plaintext checksum verification. A retry may adopt an existing valid
ciphertext with a different nonce, but cannot replace mismatched bytes or a cleanup
marker. A second transaction rechecks deletion, current version and the pending
operation before advancing the head. Conflicting reservations remain charged until
fenced cleanup. Exact old committed retries return their original receipt without
restoring an old head, even after its retired bytes are removed.

Reads verify stored ciphertext digest, authenticated metadata, canonical envelope
and current owner/head after I/O. Deletion during a read prevents returning that
read. A publishing suspension does not deny private-journal access; deletion does.
Owner/global storage and daily byte/count budgets include reserved/deleting objects,
with serialized quota reservations. Bounded owner operation receipts are retained;
there is no implicit receipt pruning or stale-operation reuse. Normal cleanup
retires abandoned uploads/old heads under leases and permanent empty markers.
Account deletion continues through the separately restricted lifecycle adapter.

New real-Postgres cases cover exact old replay, changed-body rejection, interrupted
upload/restart with different nonces, simultaneous commits, concurrent quotas,
failed cleanup accounting, tampered/substituted reads and deletion during reads or
paused uploads. Fresh Actions results are required for this increment. No generic
helper or storage service is exposed as working cloud backup.

## Resumable private photos (internal)

Photo bytes transfer in fixed 1 MiB chunks, at most 16 MiB per photo. Each
chunk has a separate random object identity, plaintext hash, authenticated owner
context and storage lease; retries preserve completed chunks. Bounded `photo-v1`
metadata uses the private operation ledger, so no authored bytes, file paths or
new schema tables are introduced. Exact asset IDs/claims and part hashes cannot
be changed on retry. Expired partial chunks can resume with fresh object IDs after
the old keys are fenced; reserved/deleting bytes remain charged until removed.

Finalization reassembles and checks the whole-photo SHA, verifies JPEG/PNG/WebP
format, bounds dimensions/pixels and forces a decode before marking complete.
Owner-scoped reads recheck account and asset state after I/O. Deletion during a
photo read prevents returning the bytes; account purge removes the same existing
private metadata/objects. Private backup preserves original photo bytes; public
publication continues through its independent EXIF-stripping canonicalizer.

Snapshots accept only complete assets belonging to the same owner, including
assets in retained conflict copies. The final commit rechecks ownership/completion.
An asset referenced by the current snapshot/recovery copies cannot be abandoned;
a concurrent head change forces a recheck. Explicit abandonment retires bytes for
the existing fenced cleanup and is retryable. No production endpoints, complete native
projection, runtime keys or native photo upload/apply are connected yet.


## Authenticated transport and portable projection checkpoint

An optional dependency-injected HTTP adapter exposes exact `/v1/sync` and private
photo routes only when a caller explicitly supplies both private services. Main
startup does not construct them. Authentication resolves the owner from the
existing bearer session before reading payloads. JSON and chunk lengths, encodings,
UTF-8, idempotency headers, route methods and global request capacity are bounded.
Responses are private/no-store; request logs contain route templates only. The
real-Postgres HTTP test uses real proof-bound authentication with an in-memory
fake mailer: no real message is sent. It covers session rejection, owner isolation,
CAS/retry, resumable photos, explicit abandonment and disabled routing.

`private-sync-projection-v1.json` declares portable fields independently of archive
import. All three pure validators consume the same contract and twenty shared
cases. Paths become explicit owned asset IDs. Settings, recordings, probe
assignment activation, delivery occurrences, timers and rule enabled/status fields
never enter the portable rows. Unknown fields and broken ownership graphs reject
the entire projection. Android validates strict JSON syntax before its permissive
org.json decoder; all clients reject malformed embedded JSON. Tests also project
a real-shaped journal, preserve its input and reject missing photo mappings.
A new encrypted-storage test exercises the strict validator without synthetic
metadata-only payloads. Local typecheck/unit results are not native/DB Actions
proof; the fresh checkpoint must pass those gates.

This contract is explicitly `releaseReady: false`. Companion document codecs are
not implemented and fail the whole projection with an unsupported-data error;
nothing is silently omitted. Native transaction application, reference semantics
inside serialized documents, durable mutation/outbox state, inactive remote-rule
adoption, branch storage, conflict recovery and two-device verification remain
required. No production route, cloud-backup status, account activation or native
upload flow is enabled by this checkpoint.

Prior backend Actions at `2a94068` passed 83 tests with zero skips, including real
Postgres snapshot/photo races and production-image checks (run 38936939647).
