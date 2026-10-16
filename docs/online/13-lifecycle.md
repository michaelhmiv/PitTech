# PR 13 — Community lifecycle (in progress)

This phase is under implementation and is not release-ready. Production community
and account entry remain disabled. No deployment or store submission is included.
Issue #89's approved October 14 amendment supersedes its earlier anonymization
proposal: remove account identity, originals, private sync and media; independently
owned recipient copies/forks survive with a non-identifying removed-source stub.

Implementation boundaries:

- Per-publication random publisher aliases allow reporting/blocking without exposing
  anonymous account identity or correlating different anonymous publications.
  Signed-out blocks stay on the device; signed-in blocks persist privately. Bounded
  anonymous block aliases also filter direct detail/media/Cook Pack reads.
- All new or changed public content requires operator review in the initial corpus.
  A role-gated queue presents actual authored text and images. MIME/profanity checks
  do not count as image moderation. An operator cannot be created by a public user.
  Known violating asset hashes are blocked wherever served; direct forks get review
  flags, while legitimate independently owned variants are retained.
- Reports have bounded reasons/details, per-IP/session/global budgets and durable
  idempotent receipts. Moderator notes/audit identities never enter public payloads.
- Deletion persists a request bound to an initiating device proof, flags the account
  and revokes sessions atomically. Anonymous proof-based recovery can recover a lost
  receipt after revocation. A separate random status capability reveals progress
  only, without email/account identity or library content.
- Purging hides originals immediately, keeps non-identifying source stubs, and
  deletes original revisions/owned blobs under durable leases. Upload leases reduce contention; create-only writes and permanent empty
  storage markers prevent late uploads from restoring deleted bytes. Publication
  cleanup never removes these random-key markers. S3 cleanup fails closed if the
  bucket is versioned, suspended or its versioning state cannot be checked; original
  versions require a separately verified purge. Failed cleanup retains ownership
  and budget. Independent forks'
  objects are untouched. Private-sync cleanup must explicitly complete; a present
  private-sync namespace without its cleanup adapter fails closed.
- Only after owned objects/private data and pending notification identity are gone
  is a deletion marked complete. Provider backup/object-version retention requires
  documented staging/operator verification. No retention duration is invented here.
- Native deletion confirmation separates server deletion from local-journal choices.
  Keeping the journal preserves active local cooking; account switches never attach
  it silently to a new account. Store billing management cannot gate deletion.
- A browser request flow signs in explicitly with an email code and can request or
  recover deletion without reinstalling an app. GET/link opening never deletes.
- Transactional lifecycle notices use a durable encrypted outbox, deterministic
  event keys, bounded retries inside the provider's idempotency window and generic
  content. They contain no private reported text or exported history. No email has
  been sent by this implementation work. Reply-to remains the verified project
  contact, `sportfolioholdings@gmail.com`.

Required evidence: real Postgres role/ownership/race/purge tests, named/anonymous
signed-out reports and blocks, rejected admin escalation, manual review and asset
revocation, lost deletion responses, surviving independent forks, web request flow,
and both native large-text paths. All remain open until their Actions and visual
results are recorded. Phase 14 supplies actual private-sync cleanup and conflict
transport; this draft must not advertise a complete Phase 2 release.

Storage protocol references: [AWS conditional writes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html), [Railway bucket compatibility](https://docs.railway.com/storage-buckets#s3-compatibility). Railway currently documents no object versioning; conditional-write behavior and the versioning API still require a staging integration check. Do not infer provider conformance from an S3-compatible label. Never roll back to an unconditional publication writer while cleanup is running. Markers carry no authored payload, owner or email; provider-created metadata requires operational retention review, and their random keys must not be reused or garbage-collected without proving every old writer is fenced. Provider metadata charges/retention remain an operational review gate.

Runtime wiring: `ACCOUNT_LIFECYCLE_ENABLED` requires authentication, separate lifecycle hash/encryption keys and `LIFECYCLE_DATABASE_URL` with only the lifecycle runtime grants. Public API startup additionally requires the lifecycle instance and the existing moderation/deletion readiness gates. Production always uses review-required publishing. `/delete-account` uses nonce-restricted same-origin script, explicit email-code verification and a typed confirmation; browser access tokens remain in memory, while session storage holds only proof/receipt capabilities. GET never mutates account state. The UI and native flows still require visual and Actions evidence.

Native deletion work now includes device-only persisted proof/receipt state, exact
account-bound retries, one refresh on 401, anonymous recovery/status after session
revocation, and a typed in-app confirmation on both platforms. Deletion blocks
further publishing access, and recovery survives sign-out/restart. The journal and
local recording are untouched by these account operations. Both clients display
pending publication/profile review rather than claiming immediate publication.
Native unit tests cover interrupted responses, restart recovery, failed local saves, malformed receipts and same-body 401 retries. Both UI suites now exercise typed confirmation, recovery and progress at their required text sizes; these new tests are not yet verified in Actions. The
separate local-journal removal option, native reports/blocks, account-scoped outbox
cleanup and all lifecycle visual paths remain open gates.

The first database Actions run found an unnecessary user row lock requiring a
privilege absent from the cleanup role; the account advisory lock supplies the
required serialization. The second run completed original/private/media purge and
survivor checks, then hit a test-only expired access token after advancing the
clock. The survivor assertion now refreshes that separate user's session. A green
full database/container run is still required.
