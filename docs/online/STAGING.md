# Isolated Railway staging

Updated October 20, 2026. The **PitTech Staging** foundation is now provisioned
and running in its separate project. Production's Feedback Relay and Privacy
services were not changed. Automatic PR deployments remain disabled; the API
and one-off bootstrap are pinned to the tested source below.

| Resource | ID / observed configuration |
|---|---|
| Project | `5437a627-75bc-4750-9677-c2bcc63d82b8` |
| Environment | `66718bed-3e9f-4372-88d9-3e3969a3b59f` (`staging`) |
| Applied patch | `57a0ad2f-188f-4139-855f-39ec60cb12b4`; 77 reviewed non-destructive additions, applied October 20 |
| API | `51a318b0-73a9-4637-bae9-298b2ddf7634`; source `5d246b2f68bb18c4f4ee73fc54c5516a7344da6c`, `api-service/Dockerfile`, `/healthz`; one `iad` replica |
| API deployment | `bffac66c-4456-47e0-94df-fb57bb49a503`; observed `SUCCESS` |
| Health endpoint | `https://pittechapi-staging.up.railway.app/healthz`; actual HTTPS response `200`, `{"status":"ok"}` |
| Private bootstrap | `37ec1f42-86f7-48c3-ace8-881f262a8188`; same pinned image, one `iad` replica; `node --max-old-space-size=256 src/bootstrap.ts`; restart `NEVER`; no domain |
| Bootstrap deployment | `3e7c7ea5-c39d-4cdc-853c-7f40c436d959`; observed `SUCCESS`, sanitized log `runtime_bootstrap_ready` |
| Postgres | `e197d93d-3d7d-40a9-a92f-825e3909ce3a`; `postgres:16`, private only; one `iad` replica |
| Postgres deployment | `16863bf5-5306-4d74-ac22-58c3ba66c0dd`; observed `SUCCESS` |
| Database volume | `2e413ebe-4e90-4636-9583-ce063cd79b7a`; 1,024 MB at `/var/lib/postgresql/data` |
| Private bucket | `e48016ca-1b62-439d-b883-1e9619659793`; `PitTechRelayPacks`, provisioned in `iad` |
| Object connection | Provider endpoint `https://t3.storageapi.dev`, SDK region `auto`, virtual-host URLs; values confirmed after provisioning |

## What the deployment proves

Backend Actions [39449305667](https://github.com/michaelhmiv/PitTech/actions/runs/39449305667)
passes at the deployed SHA: 178 tests, zero failures/skips, strict typecheck,
real Postgres 16 and production-container checks. This includes Google provider
routing/JWKS/notification identity and bootstrap role/idempotency tests.

The private bootstrap applied the eight existing versioned migrations and the
reviewed operations SQL, revoked PUBLIC schema creation, created independent
restricted API/lifecycle/sync logins, and actually connected with all three
runtime passwords before logging readiness. API startup then successfully
queried its relay schema before serving its health endpoint. Runtime passwords,
auth/lifecycle/sync keys and private DB/object connection values exist only in
Railway's secret variables/references. The API has no administrator URL.

Reference variables establish Postgres → bootstrap → API ordering when the
staged batch is applied. A later independent deployment does not rerun that
batch ordering; keep migration/runtime readiness explicit. The bootstrap is an
initial private job, not a periodic worker or API runtime credential source
that may be published. Idempotent reruns do not rotate existing passwords.
See `api-service/operations/BOOTSTRAP.md` for the command and privilege checks.

## Features still gated

`RELAY_UPLOADS_ENABLED`, `AUTH_ENABLED`, `ACCOUNT_LIFECYCLE_ENABLED`,
`PUBLIC_API_ENABLED`, `PRIVATE_SYNC_ENABLED`, `SUBSCRIPTIONS_ENABLED` and
`COMMUNITY_AGGREGATION_ENABLED` remain false. The health response does not prove
account sign-in, sync, publishing, store purchases or object write/delete
permissions. No Firebase release has been distributed for this rollout.

The API and Postgres have ceilings of one vCPU and 1,000,000,000 bytes RAM per
replica. The one-off bootstrap has a 256 MB JavaScript heap limit; the connector
rejected setting its container CPU/RAM limits, so no container ceiling is claimed
for that job. Conservative relay test caps remain 1 GiB stored, 128 MiB/day and
ten uploads/day. Prepared sync limits are 128 MiB/owner, 512 MiB globally,
128 MiB/day, 200 uploads/day and 1,000 owner operations. These are ceilings,
not measured production capacity or pricing commitments.

## Remaining operational acceptance

- Repair `auth-staging.pittech.app` DNS. Resend's October 20 read shows DKIM and
  SPF MX/TXT failed and tracking CNAME not started; `auth.pittech.app` is verified.
  Use a separate domain-scoped staging sending key, sender and HTTPS origin.
- Attach and verify the intended staging API/public origins, TLS, association
  files and both native staging identities. A generated Railway health domain
  is not installed deep-link or native staging evidence.
- Verify private object create/read/replay/delete behavior, ingress/proxy trust,
  hosting log redaction, expiry/revocation/deletion, restart/lease recovery,
  scheduled cleanup, backup/restore, key rotation and budget alerts.
- Finish real store products/credentials/notifications and native sandbox/test
  package checks. Complete account/community/sync/deletion acceptance before
  enabling the matching native feature configuration.

The owner authorized the online deployment and Firebase test distribution on
October 20. Missing inputs, live verification and blocked console capabilities
remain prerequisites; another general deployment confirmation is not required.
See issue #123 and `20-online-enablement.md` for the complete delivery sequence.
