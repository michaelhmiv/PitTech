# Isolated Railway staging preparation

On October 15 the separate private **PitTech Staging** project was created in
Michael's existing workspace, following issue #89's staging-project requirement.
Automatic PR deploys are disabled. Everything below is **staged-create**, not
provisioned. There is no running instance, deployment, public endpoint or test
against production data.

| Resource | ID / configuration |
|---|---|
| Project | `5437a627-75bc-4750-9677-c2bcc63d82b8` |
| Environment | `66718bed-3e9f-4372-88d9-3e3969a3b59f` (`staging`) |
| Pending patch | `57a0ad2f-188f-4139-855f-39ec60cb12b4` |
| API | `51a318b0-73a9-4637-bae9-298b2ddf7634`; exact source commit `cfb06b052297ea5c6bca5d1402a852904b250b96`, `api-service/Dockerfile`, `/healthz`; one `iad` replica |
| Postgres | `e197d93d-3d7d-40a9-a92f-825e3909ce3a`; `postgres:16`, private only; one `iad` replica |
| Database volume | `2e413ebe-4e90-4636-9583-ce063cd79b7a`; 1,024 MB at `/var/lib/postgresql/data` |
| Private bucket | `e48016ca-1b62-439d-b883-1e9619659793`; `PitTechRelayPacks`, `iad` |

The API source is pinned to prevent later draft pushes deploying automatically.
Database bootstrap credentials and an independent rate-HMAC key were generated
and staged only in Railway, never in the repository. No admin database URL was
attached to the API. Uploads are off; trusted proxies empty. Conservative test
caps are 1 GiB stored, 128 MiB uploaded/day and ten uploads/day. These are test
ceilings, not measured production capacity or pricing commitments.

## Resolve before any apply

The volume's invalid legacy region override was removed through Railway's
staged-change review on October 15. Read-back confirms the override is absent,
the mount and 1,024 MB size remain, and the volume follows its attached
Postgres service in `iad`. No volume or data was deleted. The connector's
pending-volume limitation is resolved without applying the patch.

Both API and Postgres now have reviewed, staged ceilings of one vCPU and
1,000,000,000 bytes RAM per replica, verified through service configuration
read-back. These are bounded staging allocations, not measured production
requirements. The patch now contains 36 pending changes; no deployment exists.
It is still **not deploy-ready** because runtime credentials, migration/bootstrap,
private storage wiring and the operational gates below remain incomplete.

Finish database bootstrap and least-privilege migration/runtime role setup using
the reviewed operations SQL. Keep the admin/migrator credential out of API and
sweeper configurations. Supply private bucket connection references only after
provisioning is explicitly authorized; never route to a public database proxy or
public bucket. Add a separately pinned scheduled sweeper. The current API is
intentionally missing runtime database/storage credentials and fails closed.
Auth is absent from the pinned revision and must remain disabled until its own
gates pass.

Then verify actual ingress/proxy trust and host access-log redaction, DNS/TLS,
private storage permissions, worst-case memory/latency, restart/lease recovery,
expiry/revocation/deletion, budget alerts and current pricing. Prepare separate
native staging origins and capability namespaces before connecting an installed
app; current production-origin clients are not staging proof. Accepting staged
changes requires explicit deployment authorization; no such apply occurred.
