# PR 16 (backend lane) — Verified subscriptions, entitlements, and ad removal

Draft source for issue #89. `SUBSCRIPTIONS_ENABLED` defaults false. This lane
builds server-side store verification, the entitlement state machine,
notification handling, the versioned entitlement cache, and the native
ad-request gate. Paywall UI placement waits for slice 15's free-feature
inventory; store product configuration and sandbox evidence are owner-gated.

One monthly PitTech Pro product (settled target US $3). No annual plan, no web
checkout. Purchases bind to a verified PitTech account. **No client-driven
unlock path exists**: every entitlement decision traces to server-side store
verification or a verified signed notification.

## Verification design

`api-service/src/subscriptions-apple.ts` verifies StoreKit signed transactions
and App Store Server API status responses. Signatures validate against pinned
keys (tests) or an `x5c` certificate chain to trusted Apple roots (production:
`X509Certificate` validity, chain signatures, root fingerprint). Bundle ID,
the one monthly product ID, and the environment are checked on the verified
payload. The environment is read from the signed payload — never from a client
flag; a client hint that disagrees is rejected. Status lookups use an ES256
App Store Server API client with an explicit per-environment host; sandbox and
production never mix.

`api-service/src/subscriptions-google.ts` verifies purchases through
`purchases.subscriptionsv2` with a service-account client. The package name is
the service's own configuration, so app association holds by construction; the
product ID is validated on the response. Test-track purchases resolve to the
sandbox environment from the store's own `testPurchase` marker. Pending
acknowledgements are acknowledged during verification (Play acknowledgment
within its required lifecycle) and retried on every reconcile. RTDN pushes
authenticate via OIDC bearer (Google certificates, refreshed hourly; pinned
keys in tests), then validate the envelope, package, and subscription.

Verification records are `store-verification-v1`: store, transaction identity,
product, environment, purchase/expiry timestamps, auto-renew status,
revocation/cancellation markers, and a SHA-256 evidence reference. Raw proofs
and purchase tokens never enter logs. Deduplication by store
transaction/purchase identity means the same store transaction applied twice
never double-extends entitlement (`duplicate-transaction`).

## State machine

States: `none`, `pending`, `active`, `grace`, `retry` (billing retry / account
hold), `paused`, `expired`, `cancelled` (paid-through honored),
`refunded`, `revoked`. Unknown/loading is a first-class client state, distinct
from verified not-entitled. Transitions apply only from verified store
evidence (initial verification, notification reconciliation, account
reconcile); out-of-order verdicts are superseded by event time, table
violations are rejected and recorded, and a weaker verdict from a different
binding is shadowed.

```
none -> pending | active
pending -> pending | active | expired | cancelled
active -> active | grace | retry | paused | expired | cancelled | refunded | revoked
grace -> active | grace | retry | expired | cancelled | refunded | revoked
retry -> active | grace | retry | expired | cancelled | refunded | revoked
paused -> active | paused | expired | cancelled | refunded | revoked
cancelled -> active | expired | cancelled | refunded | revoked
expired | refunded | revoked -> active | <self>
```

Apple status codes map to active/cancelled (auto-renew off), expired, retry,
grace, revoked; Google states map to active/cancelled, grace, retry (on hold),
paused, expired. Apple `REFUND`/`REVOKE` notifications combine with the
authoritative status for the same transaction lineage; a stale refund for a
superseded transaction cannot move a renewed entitlement. Google `REVOKED`
maps to revoked when the status confirms a non-live subscription. Play has no
subscription refund state; refunded is reached on the Apple path and the voided
lineage is documented as a follow-up.

Binding: the first verified account owns a store transaction. A second account
claiming it receives an explicit open conflict record and recovery text; the
holder is untouched. Recovery is deterministic: the holder releases while
signed in (its live entitlement ends), or a binding whose holder entitlement
is terminal may be claimed through fresh verification; transfers resolve the
conflict as `transferred`. Restore re-verifies each proof and re-establishes
the same entitlement; one account's entitlement reads identically on Android
and iOS after sign-in. Local records, exports, and free capabilities stay
usable after expiry, and no transition interrupts an active cook.

## Notifications

Both handlers verify before touching the database, deduplicate (Apple
notification UUID, RTDN message ID), and treat the notification as a hint: all
entitlement-changing types trigger authoritative store-state retrieval, and
the converged state is recorded with a reconciliation event. Informational
types, test deliveries, and unknown (never-bound) purchases are recorded
without touching entitlements. Unsupported future store states fail closed
with `unsupported_store_state` and no grant. RTDN answers 200 once processed,
which is the Play acknowledgment. Missed notifications converge through
account reconcile and a bounded stale sweep (25-hour default in the minute
worker, 20 per run). Sandbox notifications never touch production entitlement
records and vice versa.

## Cache version and offline window

`GET /v1/me/entitlement` serves `entitlement-cache-v1`
(`contracts/entitlement-cache.schema.json`, fixture
`contracts/fixtures/entitlement-cache-v1.json`): environment, `verified`
flag, state (`unknown` when never verified), paid-through `verifiedUntil`,
`lastVerifiedAt`, `offlineValidUntil`, and `contractVersion`
`store-verification-v1`. Active/cancelled records project to expired once the
paid-through date passes. Recently verified active/grace records carry
`offlineValidUntil = lastVerifiedAt + 7 days` for bounded offline use;
reconcile on connectivity converges to the store with a recorded event.
Sandbox context is selected explicitly (`?environment=sandbox`); production
is the default.

## Ad-gating rule

`shouldRequestAds(entitlement, previouslyEntitled)`: entitled never requests;
verified-not-entitled requests; unknown holds for previously entitled
accounts and requests otherwise. Ad-free server states are active, grace,
retry, paused, and cancelled with an unexpired paid-through date; the durable
previously-entitled flag (SharedPreferences / UserDefaults) survives
restarts so unverified subscribers never see a flash of ads. The gate is
checked before **every** ad request: Android
`PitTechNativeAdPlacement` (plus the SDK-start consent path and the
`shouldShowCookListNativeAd` placement overload) and iOS `NativeAdLoader`
(plus `MobileAds` start and the `ContentView` placement condition). The
`AdRequestMonitor` hook records each allowed attempt so CI asserts zero
requests under entitlement.

## API routes

All purchase/cache routes require a verified account bearer. Notification
routes verify store signatures instead.

| Method | Route | Result |
|---|---|---|
| GET | `/v1/me/entitlement[?environment=]` | Versioned cache record, unknown included |
| POST | `/v1/me/purchases/verify` | Verified bind/apply or explicit conflict receipt |
| POST | `/v1/me/purchases/restore` | Per-proof outcomes, cross-platform |
| POST | `/v1/me/purchases/release` | Holder-only binding release |
| POST | `/v1/me/entitlement/reconcile` | Store re-verification and converged cache |
| POST | `/v1/billing/apple/notifications` | Verified ASN V2 ingest, deduped |
| POST | `/v1/billing/google/notifications` | Authenticated RTDN ingest, 200 acknowledges |

Billing tables live in a separate `billing` schema (migration 007) with a
narrow runtime role (`operations/billing-runtime-role.sql`): no relay, auth,
publication, sync, migration, DDL, or truncate access.

## What remains

Paywall UI placement and paid-screen code wait for slice 15's free-feature
inventory. Owner-gated: store product creation and identifiers, App Store
Server API credentials, Play service account and RTDN topic wiring, sandbox
purchase evidence, TestFlight/closed-testing passes, and full-chain Apple X.509
fixtures from live sandbox notifications (unit tests pin generated keys and
cover chain rejection paths). Runtime evidence (Actions runs, staging
verification) is still required; this draft was written without a local build.
