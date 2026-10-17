# Anonymous Cook Pack relay

Draft PR 06 for issue #89. This service is not deployed. Native clients do not
upload yet; PR 07 supplies their explicit preview, owner-capability storage,
code/link/QR and import UI. Existing feedback and privacy services remain intact.

Node 22.23.3 executes the erasable TypeScript directly. Locked dependencies and
strict typechecking are separate checks. The Docker build uses the repository
root as context: `docker build -f api-service/Dockerfile .`.

## API

| Method | Route | Authorization and result |
|---|---|---|
| POST | `/packs` | Binary Cook Pack, `Content-Type: application/octet-stream`, UUID `Idempotency-Key`, `Authorization: Bearer <owner capability>`; 201 receipt |
| GET | `/packs/:code` | Download capability in code; exact accepted bytes, SHA-256 header; unknown/expired/revoked code gives the same 404 |
| POST | `/packs/:code/renew` | Independent owner capability; 180 days from explicit renewal |
| DELETE | `/packs/:code` | Independent owner capability; immediate unavailability and durable deletion queue; 204 |
| GET | `/healthz` | Process health; startup first verifies the migrated relay table |

The client generates **32 CSPRNG bytes** encoded as canonical 43-character
base64url before uploading and keeps them in Keychain/Keystore, separately from
the code and the pack. The server stores only SHA-256 of this capability. Reuse
the capability, UUID idempotency key and original file when retrying; changed
content returns 409. A receipt contains `code`, `expiresAt` (ISO UTC), `sha256`,
`bytes`, `kind` and `dishes`. It never returns an owner secret or a bucket URL.
PR 07 must persist the receipt before discarding its pending-upload state.

Codes encode eight unbiased CSPRNG bytes as `XXXX-XXXX-XXXX-XXXX` hexadecimal:
64 actual bits. Case, pasted whitespace and hyphens normalize; other characters
fail. Five collision retries occur inside durable budget reservations. Codes
grant download only. There is no listing endpoint. Reads never extend expiry.
JSON errors follow `{ "error": { "code": "...", "message": "..." }, "requestId": "..." }`.
429/503 include a retry hint; clients retain drafts and file/image sharing.

## Validation and storage boundaries

Uploads are restricted to archive v10 / Cook Pack v1. The shared schema, closed
record/snapshot/story allowlists and cross-record ownership checks reject full
backups, unrelated dishes/assets, active automation, device identifiers,
credentials and unselected media. Public note text already enabled in the
preview remains included. Whole-meal draft notes cannot enter recipe snapshots.
No URL is fetched from submitted content.

Limits are 64 MiB compressed/expanded, 16 MiB per entry and 1,000 entries.
Classic single-disk ZIP headers, local/central names, nonoverlapping ranges,
CRC32, actual inflated lengths, encryption/symlink/path restrictions and entry
limits are checked before acceptance. One validation worker runs per process,
with a 15-second deadline and 256 MiB JavaScript heap limit. At most two data
requests run concurrently. HTTP headers, upload size and request time are bounded.

The served ZIP is rebuilt from validated manifest/JSON and selected JPEGs.
Uploaded README/CSV text, comments and extra ZIP fields are discarded. Images
must decode as single JPEGs, at most 4096 × 4096; orientation is applied and each
derivative is re-encoded without EXIF/GPS/IPTC/XMP/thumbnails. Captions and exact
measurements remain in JSON. Original local files are unchanged. SHA/size in the
receipt describe **accepted canonical bytes**, which can differ from the upload.
Full-backup limits remain unchanged.

Postgres uses only the `relay` schema. It has no account/publication/private-sync
tables or routes. Global reservations serialize using a transaction advisory
lock. Daily upload counters survive object deletion, failed uploads and process
restarts. Expired/revoked/abandoned bytes stay reserved until object deletion
has succeeded. Cleanup claims have five-minute leases and tolerate restarts or
temporary object-store failures. Incomplete uploads expire after 15 minutes.

The S3 adapter uses private randomly named keys, server credentials and proxied
downloads. It never grants public ACLs, exposes listing, or returns signed URLs.
Development filesystem storage writes private atomic files; production refuses
that adapter. S3 requests have deadlines. Download bytes are hash/length checked,
then availability is rechecked after I/O so a concurrent revocation takes effect.

Rate limits count attempts, including invalid uploads and unknown codes:
20 uploads/IP/UTC day, 120 downloads/IP/hour, 60 management attempts/IP/hour.
Stored rate identities are window-scoped HMACs, never raw addresses. Forwarded
addresses are trusted only across explicitly configured ingress CIDRs, walked
from the socket to the nearest untrusted hop. Default trust is empty. Never set
all addresses trusted. Verify Railway's actual ingress behavior in staging
before enabling uploads; default grouping behind one ingress can deny sharing.
Runtime dependencies pin the patched proxy parser; forwarding tests cover spoofing.

## Configuration and staging

Use `.env.example` as a name reference; it contains no credentials. Supply secrets
through the host secret store, never build arguments or committed files.
Provision isolated staging/production Postgres databases, runtime/migration roles,
private buckets and credentials. Runtime grants should cover only its relay
tables; migrations use `MIGRATION_DATABASE_URL` separately. Run `npm run migrate`
once as a release task. Applied migration checksums cannot silently change.

Required budgets (`STORAGE_MAX_BYTES`, `DAILY_UPLOAD_MAX_BYTES`,
`DAILY_UPLOAD_MAX_COUNT`) have no invented production defaults. Measure worst-case
pack/image memory, latency, storage and egress in isolated staging, obtain actual
Railway/bucket pricing, then record limits and alert thresholds before rollout.
The worker heap limit is **not** a measured process-memory claim. No resources,
domains or prices have been verified or provisioned by this draft.

`RELAY_UPLOADS_ENABLED` defaults false. Setting false pauses new uploads while
downloads and owner deletion remain available. An in-process minute sweeper
handles normal cleanup. Also schedule `npm run sweep` at least every five minutes
with the same private configuration so cleanup runs while web instances restart.
Sweep private upload directories and local partials older than 30 minutes;
object leases prevent deleting an active upload. Use bucket lifecycle cleanup
for incomplete multipart operations if the storage configuration introduces them.

Logs contain only request ID, allowlisted method, route template and status.
They exclude paths/codes, IPs, tokens, email, pack text, exceptions and bucket
keys. Configure ingress/access logs and error collection to the same standard;
application redaction does not establish host logging behavior.

## Verification and rollout blockers

Run `npm ci`, `npm run typecheck`, `npm test`, `npm run fixtures:check` and
`npm audit --omit=dev --audit-level=high` on Node 22.23.3. Real Postgres tests need
`TEST_DATABASE_URL` pointing to a disposable `pittech_test*` database.
`REQUIRE_DB_TESTS=1` fails rather than silently skipping absent database coverage.
Tests exercise six actual native writer packs, hostile archives and scopes,
canonical metadata stripping, capabilities, collision retries, HTTP workers,
IP/global budgets, idempotency, restart, expiry, renewal/revocation and cleanup.
Both native suites consume the server's canonical recorded-cook fixtures as
well as original app exports; native builds run only in Actions.

`.github/workflows/online-services.yml` is installed on this draft branch.
`ci-proposals/online-services.yml` and `online-services-workflow.patch` retain
the review copy and exact addition. The gate includes Node 22, real Postgres 16,
strict checks, audit, deterministic fixtures and production-container validation.
It has no production
credentials or deployments. Local smoke checks are not Actions verification.

Do not merge or deploy this draft until the backend workflow passes,
native canonical imports pass, actual private storage/restart/deletion
and proxy behavior pass in isolated staging, and the complete phase-1 parity
and privacy gates pass. All issue #89 PRs remain draft.
