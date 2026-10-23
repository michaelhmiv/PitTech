# Isolated Railway staging

Updated October 22, 2026, evening in New York. **The stack is fully
activated**: the API runs main `01ca9e64` with all seven online flags on
(auth + providers, lifecycle, private sync, subscriptions, public API,
community aggregation, relay uploads), verified step by step against the
live service — see the activation section in [STATUS.md](STATUS.md) and
the operational procedures in
[22-operations-runbooks.md](22-operations-runbooks.md). The resource
table below is the foundation-era record; deployment IDs there predate
the activation deploys.

Mail facts (superseding the foundation-era notes): `pittech.app` was
purchased through Railway Domains, so **Railway manages its DNS** (the
registrar of record is Railway's partner). The four Resend records for
`auth-staging.pittech.app` were added through the Railway API on
2026-10-22; the domain is Verified in Resend, a domain-scoped sending
key is installed, and real sign-in delivery is proven. Staging budgets
as activated: relay storage 1 GiB (daily upload 128 MiB / 10), public
storage 512 MiB under a 2 GiB combined object cap (public daily 64 MiB /
100 writes), private sync per the `PRIVATE_SYNC_LIMIT_*` values on the
service (owner 128 MiB / global 512 MiB / daily 128 MiB).

| Resource | Observed configuration |
|---|---|
| Project / environment | `5437a627-75bc-4750-9677-c2bcc63d82b8` / `66718bed-3e9f-4372-88d9-3e3969a3b59f` (`staging`) |
| API | `51a318b0-73a9-4637-bae9-298b2ddf7634`; source `9ee2e2d748271ed4f5bb26358dfbde700e5d0680`; `api-service/Dockerfile`; one `iad` replica |
| Latest API deployment | `22329c13-36bd-4bab-9a3a-debe941e4150`, `SUCCESS`; deployment metadata confirms `9ee2e2d` |
| API health | `https://api-staging.pittech.app/healthz` returned `200/ok`; deployment `2143a30f-0eae-4c74-a261-0cc1818b39dc` also recovered to `200/ok` after an explicit restart |
| Public origin | `https://staging.pittech.app`; valid DNS/TLS |
| Private bootstrap | `37ec1f42-86f7-48c3-ace8-881f262a8188`; initial tested source `5d246b2`; `node --max-old-space-size=256 src/bootstrap.ts`; restart `NEVER`; no domain |
| Bootstrap deployment | `3e7c7ea5-c39d-4cdc-853c-7f40c436d959`, `SUCCESS`; `runtime_bootstrap_ready` |
| Private readiness service | `fc44071f-a2f5-4c1a-8e70-694b6eb6a33e`; `node --max-old-space-size=256 src/staging-readiness.ts`; restart `NEVER`; no domain or administrator URL |
| Readiness deployment | `8ed0e6fd-5487-444c-9b37-33f112937d02`, `SUCCESS`; all seven expected readiness labels observed |
| Postgres | `e197d93d-3d7d-40a9-a92f-825e3909ce3a`; `postgres:16`; private only; one `iad` replica |
| Postgres deployment | `16863bf5-5306-4d74-ac22-58c3ba66c0dd`, `SUCCESS` |
| Database volume | `2e413ebe-4e90-4636-9583-ce063cd79b7a`; 1,024 MB at `/var/lib/postgresql/data` |
| Private bucket | `e48016ca-1b62-439d-b883-1e9619659793`; `PitTechRelayPacks`, `iad`; endpoint `https://t3.storageapi.dev`, region `auto`, virtual-host URLs |

## Observed foundation evidence

Backend [Actions 39513125120](https://github.com/michaelhmiv/PitTech/actions/runs/39513125120)
passed at the deployed source: 181 tests with no skips, strict typecheck,
real Postgres 16 and production-container checks. It covers Google provider
routing/acknowledgement/JWKS/notification identity, role/bootstrap idempotency
and the shared environment profile.

The bootstrap applied the eight versioned migrations and reviewed operations
SQL, revoked PUBLIC schema creation, created independent restricted
API/lifecycle/sync logins and connected with each runtime password. Existing
passwords are not silently rotated. Administrator credentials remain confined
to the private bootstrap service; the API and readiness service have none.
See `api-service/operations/BOOTSTRAP.md`.

The deployed probe logged `database_api_ready`, `database_lifecycle_ready`,
`database_sync_ready`, `relay_storage_ready`, `publications_storage_ready`,
`private-sync_storage_ready` and `staging_foundation_readiness_ready`. It
verified the expected runtime usernames, permitted and forbidden reads, private
object access, identical-body retries, changed-body rejection, provider
versioning checks, deletion and late-upload fences. It removed only its own
fresh synthetic probe keys. These are actual provider results, not only mocks.

Railway's readiness service config retains the `62017a3` pin while deployment
metadata reports `9ee2e2d`. The backend tree is identical between those commits.
Keep both observations; do not infer the executed commit from config alone.
The current API's deployment metadata independently confirms `9ee2e2d`.

## Native origins and association identities

`ONLINE_ENVIRONMENT=staging`, `AUTH_PUBLIC_ORIGIN=https://staging.pittech.app`
and `AUTH_FROM=signin@auth-staging.pittech.app` are configured. Both custom
hosts have valid TLS; their CNAME targets are `5w9pj6wa.up.railway.app`
(API) and `r1h17h7b.up.railway.app` (public web).

The staging Apple association includes `R42LWFBXBH.com.pittech.staging` and
the registered `R42LWFBXBH.com.pittech` store-sandbox identity. The latter
requires the explicit native StoreSandbox configuration; it does not change
the production app's associated-domain entitlement.

The live Android `assetlinks.json` returned 200 with `com.pittech.dev` and
the exact existing Firebase Dev upload certificate fingerprint:

`63:BA:B5:50:81:BA:92:FE:A8:81:7E:55:F0:F8:B2:C9:A8:3C:1D:C1:2A:E8:F0:CE:07:F0:6F:A5:59:5D:7E:2C`

The public certificate/evidence is retained in
[Actions 39524579064](https://github.com/michaelhmiv/PitTech/actions/runs/39524579064).
No private key was exported. Installed native-link verification is still
required. Play-signed store-sandbox builds additionally need the actual Play
app-signing certificate and an association that supports their registered
package alongside Dev; do not reuse the upload certificate as Play proof.

## Mail and store status

Name.com is authoritative for `pittech.app`. Public DNS returned NXDOMAIN for
all four required `auth-staging` records; Resend reports verification failed.
The secure DNS-account sign-in was not completed and will not be requested
again without an instruction to sign in. No staging sending key is installed.
`auth.pittech.app` remains verified; production sender credentials are not a
replacement for isolated staging mail. Real delivery is unverified.

[Catalog inventory 39525212355](https://github.com/michaelhmiv/PitTech/actions/runs/39525212355)
read both stores through existing CI credentials and confirmed empty catalogs.
Apple configuration then completed in
[Actions 39526241364](https://github.com/michaelhmiv/PitTech/actions/runs/39526241364):

| Apple field | Verified value |
|---|---|
| App / group | `6821518899` / `22498644` |
| Product / subscription | `pittech.pro.monthly` / `6825135995` |
| Group draft version | `11a44c93-51f5-47fb-a901-0c725df5627e` |
| Period / US price | `ONE_MONTH` / USD 3.00 |
| Payment / territory | `UPFRONT` for that one-month period / `USA` |
| State | `MISSING_METADATA`; no review submission |

English group/product metadata is present. Apple's `MONTHLY` plan type is a
12-month installment commitment, so it was not used for this month-to-month
product. Review metadata, server credentials/notifications and real sandbox
purchase/renewal/restore/revocation evidence remain open.

The Play credential can read the catalog but pricing conversion was rejected
with HTTP 403 `PERMISSION_DENIED` in
[Actions 39525750420](https://github.com/michaelhmiv/PitTech/actions/runs/39525750420).
No Play product was created and that denied operation was not retried through
another identity. Appropriate app-level catalog/pricing access is required.

## Feature gates, limits and remaining acceptance

`RELAY_UPLOADS_ENABLED`, `AUTH_ENABLED`, `ACCOUNT_LIFECYCLE_ENABLED`,
`PUBLIC_API_ENABLED`, `PRIVATE_SYNC_ENABLED`, `SUBSCRIPTIONS_ENABLED` and
`COMMUNITY_AGGREGATION_ENABLED` remain false. No online Firebase release has
been distributed. No store purchase or complete live account/sync flow is
claimed from the foundation probe or catalog setup.

The API/Postgres ceilings remain one vCPU and 1,000,000,000 bytes RAM per
replica. The private jobs use a 256 MB JavaScript heap limit. Relay caps remain
1 GiB stored, 128 MiB/day and ten uploads/day. Prepared sync caps remain
128 MiB/owner, 512 MiB globally, 128 MiB/day, 200 uploads/day and 1,000 owner
operations. These are configured ceilings, not measured capacity or prices.

Remaining acceptance includes actual sign-in delivery, complete account/
community/sync/photos/deletion flows, expiry and scheduled cleanup, lease
recovery, proxy/log-redaction checks, backup/restore, key rotation, budget
alerts, installed links and both stores' actual purchase lifecycle evidence.

The owner has authorized the rollout and Firebase distribution. Complete
available work without another general permission request; missing access
and verification remain technical prerequisites. See issue #123 and
`20-online-enablement.md`.
