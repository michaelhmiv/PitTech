# Operations runbooks — backup/restore, key rotation, cleanup

Written October 22, 2026, from procedures actually executed against the
isolated staging stack during the issue #123 activation. Production
adopts the same procedures with production values when its stack is
built.

## Backup and restore (Postgres)

Railway's managed volume backups (manual snapshots and schedules) are
**Pro-plan only**; the workspace is Hobby. Until that changes, backups
are logical dumps produced by a one-shot runner service:

1. The staging Postgres is a bare `postgres:16` image service. It
   publishes **no `DATABASE_URL`** of its own; consumers compose one
   from the `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB`
   service references plus the private domain
   (`RAILWAY_PRIVATE_DOMAIN`). A `${{Postgres.DATABASE_URL}}` reference
   renders empty — do not use it.
2. Dump/restore tooling must match the server major version (16).
   A PostgreSQL 17 `pg_dump` emits `transaction_timeout`, which a 16
   server rejects on restore; the drill caught exactly this.
3. Railway image-service start commands run as a single space-split
   exec with no shell and no variable expansion — a raw `pg_dump`
   start command never receives the URL. Use a small Dockerfile whose
   entrypoint script expands `$DATABASE_URL` in a real shell.
4. Drill procedure (proven 2026-10-22): stand up a throwaway service
   from the drill image with the composed `DATABASE_URL` and a scratch
   volume at `/data`; run the dump (`pg_dump` → file on the volume,
   48 tables, ~97 KB at activation-time data volume); then restore
   into a scratch database **on the same server**, compare per-table
   row counts from `pg_stat_user_tables` between source and restored
   schemas, and drop the scratch database. Counts matched for all 48
   tables. Delete the runner service afterward; the volume holding
   the dump is retained in the staging project
   (`backup-drill-dumper-volume`) as the restore-point artifact.
5. Cadence: repeat the drill before any production data migration and
   after any schema migration lands; a dump is only a backup once a
   restore from it has been observed.

Object storage (relay packs, publication and sync objects) lives in
the project bucket; the database holds the authoritative index, so a
database restore plus the retained bucket is the recovery unit.

## Key rotation

All rotations follow the same order: **create the new credential,
install it, verify the behavior it unlocks, then revoke the old one.**
Never paste a secret value into chat, logs, fixtures or the repo.

| Family | Effect of rotation | Procedure |
|---|---|---|
| `RATE_HMAC_KEY` | None user-visible; rate-limit buckets re-key. This is the safe rehearsal rotation. | Replace the value, redeploy, confirm 429 behavior still fires under load. |
| `AUTH_HASH_KEY` / `AUTH_ENCRYPTION_KEY` | **Invalidates every outstanding challenge and session by design** (hash/encryption domains change). Users simply sign in again. | Generate a fresh pair (each a 32-byte base64url credential, hash ≠ encryption), install together with no flag change, redeploy once, re-run the email sign-in proof. |
| `LIFECYCLE_HASH_KEY` / `LIFECYCLE_ENCRYPTION_KEY` | Invalidates in-flight lifecycle envelopes (deletion/recovery flows restart). | Same shape as auth; never share values with the auth pair. |
| Private sync keys | Versioned map — zero-downtime. | Add a new `keyId` entry to `PRIVATE_SYNC_ENCRYPTION_KEYS` (values distinct from every auth/lifecycle key), flip `PRIVATE_SYNC_CURRENT_KEY_ID`, redeploy; remove the old entry only after objects written under it have expired or been re-encrypted. |
| Resend sending key | None if overlapped. | Create the new domain-scoped key, install as `RESEND_API_KEY`, redeploy, prove delivery with a real sign-in, then revoke the old key. |
| Store credentials (ASC `.p8`, Play SA JSON) | None if overlapped; a revoked-too-early key fails lazily at the first store call, not at boot. | Create the new key, install, exercise a real store path (RTDN processing / reconcile / purchase verify), then revoke the old. ASC `.p8` files are one-time downloads — install from the downloaded file and delete local copies once verified present. |
| GitHub feedback relay | Installation tokens are minted per-request; rotating the App private key orphans nothing. | Generate a new App private key, install as `GITHUB_APP_PRIVATE_KEY`, verify a real submission files as the app bot, then delete the old key in the App settings. |

## Cleanup scheduling (no external sweeper)

Cleanup runs inside the API process: a 60-second master sweep drives
relay pack expiry, publication cleanup, the account-deletion sweep,
store reconciliation and abandoned private-sync upload reclamation
(bounded per tick); a 15-second timer delivers the leased auth mail
outbox while auth is enabled. Because the sweeps are in-process,
**no external sweeper service is required** while the API is resident.
The one-shot `npm run sweep` entrypoint remains for manual runs (for
example immediately after a bulk revocation) and prints
`{removed: n}`. Any instruction elsewhere to "run sweep every ≥5 min"
applies only to a deployment shape where the API process is not
resident, which staging and production do not use.

## Activation rollback

Activation landed as one variable change plus one deployment per
layer, in the order auth → lifecycle → sync → subscriptions → public →
community → relay uploads. Rollback is the exact reverse: unset the
flag (store credentials and keys may stay installed — they are inert
while their flag is off), redeploy, and confirm the layer's routes
return to the generic 404 while `/healthz` stays 200. The fully-off
state was verified before activation and is the known-good floor.
