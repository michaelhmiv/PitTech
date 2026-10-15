# PR 08 — Passwordless backend and Resend migration

Draft source for issue #89. `AUTH_ENABLED` defaults false. No live account,
email delivery, native account flow or deployment is claimed by this draft.

## API contract

All credential requests use HTTPS JSON, no cookies, redirects or CORS grants.
Bodies are bounded to 8 KiB. Responses are private/no-store. Errors never return
provider diagnostics. The existing trusted-ingress policy and a durable
60-attempt/IP/hour budget cover authentication routes.

| Method | Route | Input / result |
|---|---|---|
| POST | `/v1/auth/challenges` | Normalized email, UUID `idempotencyId`, SHA-256 hex `proofChallenge`; 202 with opaque challenge UUID and 900-second lifetime |
| POST | `/v1/auth/verify` | Challenge UUID, originating device's `verifier`, UUID `idempotencyId`, exactly one six-digit `code` or 32-byte base64url `token`; access/refresh session |
| POST | `/v1/auth/refresh` | Refresh credential and UUID `idempotencyId`; rotated credentials within the same absolute session lifetime |
| POST | `/v1/auth/logout` | Refresh credential; naturally idempotent family revocation, 204 |
| GET | `/v1/me` | Access bearer; current account UUID and email, owner-only |
| GET | `/auth` | Static scanner-safe instructions; never consumes a link or signs in |

The native client generates 32 random bytes as canonical unpadded base64url
before requesting a challenge. `proofChallenge` hashes the **UTF-8 verifier
string**, identically on both platforms. The verifier stays on that device.
An email opened on another device supplies the six-digit code for entry into
the requesting app. Both alternatives share one consumption state, expire in
15 minutes, and allow at most five code attempts. Resend is limited to once per
minute and ten challenges per email per UTC day. A separately configured global
daily mail budget is mandatory. Existing/new accounts receive the same response;
account creation occurs only after a valid exchange. Email addresses currently
use case-insensitive ASCII identity, preserving plus tags; malformed addresses,
control characters and Unicode addresses are rejected rather than ambiguously
normalized.

Successful exchange issues random 256-bit opaque access and refresh credentials.
Access lasts ten minutes; session lifetime is an absolute 30 days. Refresh
rotation invalidates the previous access credential. Reuse of an old refresh
credential with a new operation UUID revokes the entire session family. Exact
retries of a successful exchange/rotation have a 30-second encrypted recovery
window, bound to the original secret and operation UUID. Native clients must
persist that UUID before sending, serialize refresh, and retain the pending
operation through interrupted responses. Expired recovery requires sign-in.

## Storage and delivery

`auth` is a separate schema; no public, sync or relay reads expose it. Code and
email-rate identities use keyed, domain-separated hashes. Tokens are hashed;
no plaintext session credentials are persisted. Outbox/recovery payloads use
AES-256-GCM with purpose-bound authentication and a different encryption key.
Both auth keys must be distinct canonical 32-byte base64url secrets, separate
for staging and production. Rotating these keys requires a planned invalidation
of challenges/sessions and removal of pending envelopes, not a blind variable
change. Account emails remain private database records; host encryption,
retention and log controls require operational verification.

A durable leased outbox retries failed provider requests with the same Resend
idempotency key and exact email bytes. Delivery clears its encrypted envelope;
expired challenges and sessions are pruned. A process restart recovers leases.
GET/prefetch never consumes anything. The token is in a URL fragment, so it is
not sent in HTTP paths. The email has accessible HTML and plain text, a six-digit
code and link, no tracking pixel, and no external content. Delivery uses only
`https://api.resend.com/emails`, rejects redirects, has a deadline, and discards
provider response bodies. No arbitrary sender/return URL is accepted.

`operations/auth-runtime-role.sql` grants explicit runtime tables; migration and
relay-schema access are excluded. An API login can inherit both narrow runtime
roles when the combined service is intentionally enabled. Neither administrator
nor migrator credentials belong in the API or its cleanup workers.

## Resend migration state — October 15

`auth.pittech.app` was created in the connected account, ID
`5a3168c5-b22b-42c9-b0d3-d9be49906f0f`. Tracking is disabled and TLS enforced.
It is unverified; its returned DNS records have not been applied. No email has
been sent and no API key created. `auth-staging.pittech.app` is reserved in the
adapter contract but has not been created or verified. Use separate sending-only
keys scoped to each verified domain. Existing Sportfolio domains/keys remain
until verified PitTech replacement delivery succeeds; unrelated apps are outside
this migration.

## Acceptance still required

Actions must pass real-Postgres role isolation, durable attempts, concurrent
consumption, delivery leases, retry/replay, expiry, HTTP bounds/redaction and
all existing relay gates. Local tests are not database proof. Verify actual
DNS, sender authentication, tracking settings, bounce/complaint handling,
suppression policy, least-privilege keys, host logs, budget alerts and delivery
before enabling auth. PR 09 supplies both native account/session flows. Account
deletion, public-content purge and private-data removal remain a launch gate in
PR 13; accounts/social/sync ship as one completed phase, never with deletion or
platform parity deferred. All PRs remain draft; no merge/store submission.
