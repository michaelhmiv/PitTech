# PR 10 — Public publication data and API

Draft backend for explicit public recipes, collections and measured attempts.
Disabled by default. No native publishing/discovery or deployed public service
is claimed. PRs 11–13 supply both native consumers, explicit publication UI,
moderation, publisher blocking and account deletion before this phase can ship.

`publications` owns profiles, items, immutable revisions, likes, independently
owned object reservations, idempotent operations and durable budgets. Neither
public reads nor its restricted database role can read auth, relay or private
sync schemas. A combined API login inherits only the explicitly selected runtime
roles; administrators and migration credentials never belong in the app.

| Request | Contract |
|---|---|
| GET `/v1/discover?sort=liked\|forked&food=&q=&cursor=` | Signed-out browsing; bounded filters and 20-item keyset pages. Ranking uses likes/forks, with deterministic UUID tie-breaks |
| GET `/v1/recipes/:id` | Current active revision, allowlisted payload, display credit, media URLs and counts. No author account UUID/email |
| GET `/v1/recipes/:id/assets/:assetId` | Selected current-revision photo only; rechecks active visibility after storage access |
| POST `/v1/recipes` | Authenticated explicit publication archive; Idempotency-Key UUID |
| PUT `/v1/recipes/:id` | Author only; Idempotency-Key plus exact revision UUID in If-Match; appends a revision |
| DELETE `/v1/recipes/:id` | Author only, same headers; hides all original reads immediately |
| POST `/v1/recipes/:id/fork` | Explicit **public** fork, body `publish:true`, `expectedRevisionId`, `identityMode`; Idempotency-Key. Private Save never calls it |
| POST / DELETE `/v1/recipes/:id/like` | Authenticated, idempotent membership; Idempotency-Key |
| GET `/v1/profiles/:handle?cursor=` | Opt-in public profile; named posts only; anonymous publications cannot expose the owning handle |
| GET / PUT `/v1/me/profile` | Owner profile settings; private by default; PUT uses Idempotency-Key |
| GET `/v1/me/recipes?cursor=` | Authenticated owner management, including anonymous and unpublished items |

## Publication archive and media

An archive contains `publication.json` matching `contracts/publication.schema.json`
and exactly the referenced selected attachments. It is distinct from a Cook
Pack and a full backup. The ZIP reader retains existing Cook Pack rules by
explicit mode; a publication cannot become a valid relay backup by changing its
filename. Limits remain 64 MiB compressed/expanded, 16 MiB per entry and 1,000
entries, with an 8 MiB public JSON bound and 20 megapixel image input bound.
Duplicate IDs/paths, missing hero/channel/attempt references, unexpected fields,
extra entries, checksum errors and unsafe archives are rejected before storage.

A worker with no inherited credentials validates and canonicalizes the archive.
Selected JPEG/PNG/WebP files are oriented, bounded and re-encoded without
EXIF/GPS/XMP or thumbnails. The original local photos remain unchanged. Hashes
are recalculated for accepted bytes. Uploaded display-credit strings are removed;
actual attribution comes from verified source records and visibility rules.
No remote URL is fetched. Workers have a 15-second deadline and 256 MiB JS heap;
only one relay/public upload or public fork occupies the process upload slot.

Private object storage keys use a separate publications prefix. Reservations
precede storage writes, preserve exact retries and count failed/interrupted
writes until leased cleanup confirms deletion. Global and per-user daily writes,
bytes and retained-object capacity are bounded. Runtime configuration requires
separate publication caps and a total cap covering relay plus publication budgets.
Mutation operations also have durable account/global daily limits.

Forks copy bytes into independently owned objects. They pin a source revision
and retain the measured attempt ID; a fork is not a second measured cook. Updates
do not silently pull upstream changes. Unpublish makes source credit a removed
reference, while other users' independently owned copies remain usable. Public
source display credit is derived at read time using the pinned revision's identity
mode, so an originally anonymous source cannot become named through a later edit.

Unpublish hides originals; immutable revisions remain stored and budgeted until
explicit purge. PR 13 must purge account-owned revisions/media/identity/private
data and leave only non-identifying source references before account launch.
It must also implement takedown review propagation and blocking/filtering.

## Runtime and verification gates

`PUBLIC_API_ENABLED` defaults false. Enabling requires auth plus explicit
moderation/deletion readiness configuration. Those readiness flags must not be
set until PR 13 is implemented and verified. No Railway variable or staged source
was changed by this draft. Required publication settings are
`PUBLIC_STORAGE_MAX_BYTES`, `PUBLIC_DAILY_BYTES`, `PUBLIC_DAILY_WRITES` and
`TOTAL_OBJECT_STORAGE_MAX_BYTES`; both storage allocations must fit the total.

Local Node 22 strict typechecking and tests pass: 30 passed, three database cases
skipped locally. Actions must run all database cases with zero skips. New tests
cover canonical photos, archive isolation, semantic references, restricted roles,
anonymous profile isolation, exact retries, ownership/revision conflicts, pinned
lineage, independent fork storage, failed-write cleanup and actual HTTP public
reads/log redaction. Native consumers, moderation/deletion, physical staging,
load/storage cleanup, host logs, spend alerts and the complete phase remain gates.
