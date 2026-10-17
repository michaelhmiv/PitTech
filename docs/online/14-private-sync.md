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

Native library projection, per-record mutation tracking, the authenticated
API, resumable photo transfer, durable account-bound outboxes, conflict
review/restore UI and inactive rule import are implemented on both clients
(see the native-completion section below) but await Actions proof. Verified
lifecycle purge integration, supported background opportunities, full
two-device Android↔iOS and real Postgres ownership/race evidence remain open.
No backup success or completed sync claim is made by this draft.

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
import. All three pure validators consume the same contract and thirty-two shared
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
partial: checklist, equipment, fuel, preparation combo, reference and playbook
field formats are declared. Plans, alerts, serving plans, saved sharing documents,
recipe attempts and guide presentation still fail the whole projection with an unsupported-data error;
nothing is silently omitted. Native transaction application, reference semantics
inside serialized documents, durable mutation/outbox state, inactive remote-rule
adoption, branch storage, conflict recovery and two-device verification remain
required. No production route, cloud-backup status, account activation or native
upload flow is enabled by this checkpoint.

Prior backend Actions at `2a94068` passed 83 tests with zero skips, including real
Postgres snapshot/photo races and production-image checks (run 38936939647).


Legacy iOS companion timestamp aliases and string-encoded JSON payloads normalize
only at the local projection boundary. Conflicting aliases reject; transport accepts
only canonical fields and decoded objects. Finite fractional native timestamps are
preserved; logical clocks, schema versions and ordinals remain safe integers.
`monitor_status` is explicitly device-local telemetry state and never becomes a
sync record. Named unsupported authored document kinds still stop the full snapshot.
Document ownership rules distinguish globally saved templates/equipment from
cook-owned checklists/references and verify fuel scope and available source dishes.
Unknown nested fields, duplicate checklist identities, empty required names, invalid
quantities and cross-cook note scopes reject in the shared cases.

A backend test projects independent core graphs from four actual native archive
fixtures (Android and iOS v9/v10), retaining recipe snapshots, readings and record
counts. New native tests perform projection after each platform imports the real
opposite-platform v10 archive, retain unsupported saved documents in the journal,
and leave reminder activation unchanged. Those tests explicitly cover a core subset;
they do not claim full-library support or safe transaction application.

At `3dad210`, backend Actions 38939930673 passed 89/89 tests, zero skips,
including authenticated HTTP ownership/retry/photo transfer and strict projection
through encrypted storage. Fresh native/full-Postgres evidence remains required
for the companion-format follow-up.

## Full portable vocabulary and opt-in production wiring

The portable projection now covers every authored document kind: `plan`,
`alert`, `upcoming` and `serve_goal` serving plans, `sharing_draft`,
`collection_revision` and `recipe_reference` saved sharing documents,
`recipe_attempt` attempts and `guide_presentation` guides join the existing
checklist, equipment, preparation-combo, reference, playbook and fuel codecs.
Ownership follows the established rules (globally saved templates/equipment
versus cook-owned documents); unknown fields reject the whole projection and
nothing is silently omitted. The shared cases grew from 32 to 64 vectors with
positive and negative coverage per newly supported kind. The only remaining
exclusion is `monitor_status`, which is device-local telemetry rather than
authored data: the archive boundary drops it while the wire validator still
rejects it as an unknown kind. Settings, recordings, probe-assignment
activation, delivery occurrences, timers and rule enabled/status fields still
never enter portable rows, and synced definitions still arrive inactive.
`guide_presentation` is covered by the contract, all three validators and the
real-archive full-library tests, but has no dedicated shared-case vectors yet.
All four contract copies stay byte-identical and `releaseReady` stays `false`.

Production construction is wired behind an explicit opt-in:
`PRIVATE_SYNC_ENABLED=true` in `createRuntime`, off by default with unchanged
behavior when unset. Enabling requires authentication, a dedicated
`PRIVATE_SYNC_DATABASE_URL` pool carrying the private-sync runtime role (never
the main or lifecycle pool), separately provisioned
`PRIVATE_SYNC_CURRENT_KEY_ID` plus `PRIVATE_SYNC_ENCRYPTION_KEYS` (a JSON
object mapping key id to base64url key: 1–3 keys, current present, any reuse
of auth/lifecycle key values rejected), the runtime's existing `Storage`
adapter, and five `PRIVATE_SYNC_LIMIT_*` quotas defaulting to the
photo-capable values from the sync storage tests. `PrivateSnapshots` is
constructed with the strict `privateLibraryEnvelope` projector rather than
the synthetic test adapter, and `PrivatePhotos` shares the same
pool/storage/encryption/limits; both are served as `options.sync`. Startup
verifies the private tables against the sync pool and closes that pool in
`runtime.close()`; the lifecycle purger path is unchanged. Any missing or
invalid piece fails startup closed.

No deployment has applied this wiring and no real keys
or cloud resources exist. The corrected full-library and new config/wiring
tests, including the authenticated-HTTP proof through `createRuntime`
services, require fresh Actions evidence before any release statement.

## Native completion: durable apply, outbox, conflict UI

Both clients now implement the full device side of this slice. Sync stays
disabled by default (`AccountProtocol.ENABLED` / `NativeAccountProtocol.enabled`
are `false`); nothing here turns it on for users. Local writes stay
authoritative and are never blocked: sync runs only on explicit `syncNow()`,
only for the current session account.

Android (`app/src/main/java/com/pittech/data/`, `ui/`, `CooksViewModel.kt`):
`PrivateSyncStore.kt` (durable account-bound journal),
`PrivateSyncProtocol.kt` (pure stamping/request/conflict helpers),
`PrivateSyncClient.kt` (GET/PUT `/v1/sync` + resumable photo routes over the
existing OkHttp/account-session idioms, 1 MiB chunks, no redirects or cookies),
`PrivateSyncLibrary.kt` (projection-only snapshot build, transactional Room
apply), `PrivateSyncCoordinator.kt` (409 fetch/merge/retry, resumable photos,
resolutions, honest presentation), additive delete-by-id DAO helpers in
`CookEntities.kt`, `CookExperienceData.kt`, `RecipeEntities.kt` and
`CookCompanionData.kt`, plus the `SyncSection`/`SyncConflictDialog` in
`AccountScreen.kt` wired through `CookScreens.kt` (`sync-status`, `sync-now`,
`sync-conflicts`, `sync-keep-mine/theirs/both-*`).

iOS (`ios/PitTech/`): `NativePrivateSyncStore.swift`,
`NativePrivateSync.swift`, `NativePrivateSyncClient.swift`,
`NativePrivateSyncLibrary.swift`, `NativePrivateSyncCoordinator.swift`,
`NativeSyncUITestSupport.swift` (offline DEBUG fixture), merge validators in
`PrivateSyncMerge.swift`, journal purge in `AccountLocalCleanup.swift`,
wiring in `PitTechApp.swift` and the sync section plus
`NativeSyncConflictReviewView` in `AccountView.swift`.

Durable-store schema (per account, device-only, never exported): stable
device UUID, last-acknowledged base snapshot + server version, logical
`maxCounter`, per-record mutation metadata (counter + device UUID),
pending-upload flag with exact `mutationId`/SHA-256 retry bytes stored in a
separate body file, retained conflict copies with resolution IDs and the
already-sent resolution list, per-photo part registry
(`assetId`/`sha256`/`bytes`/`mime`/`received`/`complete`), and the last
honest status (`lastSyncedAt`/`outcome`/`message`). An account switch or
sign-out cancels that account's pending upload bytes and never attaches the
journal to another account; cleanup purges body files first, then journals.

Transactional apply: snapshot build is projection-validator output only — a
library that fails projection fails the attempt with an honest status and
uploads nothing. Apply is merge(local, base, remote), then validation of the
merged rows through the projection/relational checks, then a single Room
transaction (Android `withTransaction`) or a single CookStore save (iOS)
covering deletes-before-upserts across all 21 record types; staged photo
bytes are cleaned up on failure, so a failed apply leaves the local library
byte-identical. Imported `reminderRules` arrive `enabled=false` /
`inactive` with no occurrences or delivery state; existing local
recording, probe-assignment, timer and occurrence state is preserved, and
apply never arms rules, starts recording, or schedules reminders.

Conflict review matches merge semantics: Keep mine replays the losing edit
under the same identity (or a new identity when a tombstone or immutable
rule forbids it), Use theirs drops the retained copy, Keep both restores
the losing edit as a new identity (disallowed for `logReadingLinks` and
`lifecycles`, whose review buttons are hidden). Dropping a server-known
copy sends its explicit resolution ID; locally generated conflicts the
server never saw drop without an unknown ID. Status UI shows exactly
`Last synced …`, `Waiting for connection`, `Retry needed`,
`Conflicts to review` or `Sync is off`; no success is shown for an
unacknowledged upload, and receipts must match
`mutationId`/`sha256`/`version == base+1` before the base advances.

New tests are listed in the slice summary; they cover durable restart,
account-switch cancellation, interrupted-photo resume, 409 merge/retry with
both edits preserved, transactional rollback, inactive rule import, exact
merge outcomes per conflict action, projection-failure blocking, and
offline conflict review at both required text sizes on both platforms.

Still honestly open: Actions proof for all of the above (the code was
written by construction against inspected APIs; no Android SDK or Xcode
build ran in the authoring sandbox), two-device Android↔iOS physical
evidence, staging verification including the `private_assets_unavailable`
422 reset path and photo SHA-mismatch rotation, and the full accounts /
social / sync / deletion enablement gate. No backup success claim is made
by this draft.
