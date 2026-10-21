# Initial private database bootstrap

Run this once in a dedicated private Railway bootstrap service using the same
reviewed API image. It applies the existing versioned migrations and operations
SQL, then creates three restricted logins. It never resets library tables or
rotates an existing login's password. The command verifies all three passwords
through actual runtime connections before reporting readiness.

Set these service variables through Railway's secret controls/references:

| Variable | Value |
|---|---|
| `BOOTSTRAP_CONFIRM` | `create-runtime-logins` |
| `BOOTSTRAP_DATABASE_URL` | Administrator connection on the private Postgres host; bootstrap service only |
| `BOOTSTRAP_API_PASSWORD` | Independent canonical 32-byte base64url credential |
| `BOOTSTRAP_LIFECYCLE_PASSWORD` | Different canonical 32-byte base64url credential |
| `BOOTSTRAP_SYNC_PASSWORD` | Different canonical 32-byte base64url credential |

Start command: `node src/bootstrap.ts`. Use no public domain or healthcheck,
and no restart loop/periodic schedule for this initial job. Retain only the
non-sensitive `runtime_bootstrap_ready` / `runtime_bootstrap_failed` outcome.
Never print variables or raw SQL/database errors to diagnose a failure.

After success, wire `DATABASE_URL` to `pittech_api_login`,
`LIFECYCLE_DATABASE_URL` to `pittech_lifecycle_login`, and
`PRIVATE_SYNC_DATABASE_URL` to `pittech_sync_login`, each with its corresponding
password on that private database. Administrator/migrator credentials must not
be present in the API or cleanup service. Runtime logins inherit only explicit
reviewed roles; unexpected memberships/direct grants/ownership/elevated role
attributes stop an idempotent rerun rather than normalizing unsafe access.

An existing password mismatch requires a separately reviewed coordinated
rotation; rerunning with a new password does not silently change the old login.
If any step fails, leave online flags disabled and use the bootstrap service's
isolated administrator context to inspect the cause without exposing secrets.
Actions exercises real Postgres 16 login, migration idempotency, role isolation,
and refusal of privilege escalation before deployment.
