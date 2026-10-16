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
Real key provisioning, configuration, database/object ownership, ciphertext
storage and rotation/re-encryption verification remain unimplemented gates.
No real keys or cloud resources have been created.

Ten identical shared cases cover independent notes, clock skew, recoverable
same-note conflicts, offline edit/deletion, immutable identity collisions,
concurrent recipe branches, equivalent content and rejected omissions/replays.
Backend unit checks pass locally; new native implementations/tests require
Actions. Existing lifecycle tests and native release gates remain independently
open in PR 13.

Still to implement: strict native library projection and per-record mutation
tracking; encrypted authenticated API and resumable photo transfer; durable
account-bound native outboxes/association; conflict review/restore UI; inactive
rule import; lifecycle purge adapter; supported background opportunities; full
two-device Android↔iOS and real Postgres ownership/race evidence. No backup success
or completed sync claim is made by this draft.
