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

## Native lane: paywall placement map

The paywall is contextual within advanced Insights, customization, and More.
It appears at the entry points of Pro-only views, never as a popup, and no
purchase popup interrupts Live or Finish — ever (asserted in tests on both
platforms).

| Entry point | Surface | Preview shown |
|---|---|---|
| Personal analysis | Insights → "Personal analysis · Pro" | How pork cooks compare, best-rated recorded attempt, timing shifts across recorded cuts and weights |
| Advanced curves | Insights → "Advanced curves · Pro" | Recorded cooks aligned at Food on and Wrap; measured source selection |
| Pro themes | Share-card preview → "Pro themes · Pro" (customization) | Pro card themes, chart palettes, app icons; preview before applying |
| PitTech Pro | More → "PitTech Pro" | Full preview: ad-free, personal analysis, advanced curves, themes |

Each entry opens one gate sheet/dialog whose destination follows the verified
entitlement state. The free-feature inventory in `15-insights.md` §1 is the
do-not-touch list: every tool inventoried there stays free, signed-out, and
offline. This lane adds paid views and gates; it never removes or paywalls an
existing free tool.

The paywall screen carries: a practical preview of what Pro unlocks, precise
monthly billing text rendered verbatim from the store's localized product
data (no placeholder pricing, no hardcoded price), Restore purchases, Manage
subscription (store deep link), privacy and terms links, and a clear close
action.

## Native lane: purchase-flow design

One monthly PitTech Pro product, settled target US $3. No annual plan, no web
checkout. The product ID comes from the approved configuration path and is
owner-gated: Android `-PpittechProProductId` →
`BuildConfig.PITTECH_PRO_PRODUCT_ID`; iOS `PITTECH_PRO_PRODUCT_ID` →
`Info.plist` `PITTECHProProductID` (empty until the owner creates the
product; the Release value is supplied the same way as the live AdMob IDs,
never committed). An empty product ID is a graceful not-configured state:
no crash, no fake price, no purchase button.

- iOS: StoreKit 2 (`Product.products`, `purchase()`, `Transaction.updates`,
  `Transaction.currentEntitlements`). Android: Play Billing 9
  (`queryProductDetailsAsync`, `launchBillingFlow`,
  `queryPurchasesAsync`, acknowledge-after-verification, pending-purchase
  support). Both platforms sit behind a store-client protocol so tests use a
  stub with localized product data instead of touching a store.
- Purchases bind to the verified PitTech account (this lane's binding
  rules). The client sends the store transaction to the backend for
  server-side verification (`/v1/me/purchases/verify`, `restore`,
  `release`, `/v1/me/entitlement/reconcile`); the client never sets
  entitlement from a local purchase callback alone. Store transactions stay
  unfinished/unacknowledged until the server responds, so the store
  redelivers after a failure instead of losing the purchase.
- Pending store states show pending copy and never unlock; user cancellation
  dismisses with a "nothing was charged" notice; restore re-sends every
  current store entitlement and re-establishes the same account entitlement;
  a binding conflict shows the server's recovery text with a re-check and a
  holder release path. If the entitlement cache says the account is covered,
  the paywall shows the entitled state and never a second purchase button.
- A signed-out account sees the paywall preview with a sign-in requirement;
  no store purchase starts without a verified account. Because account entry
  stays disabled until the accounts/social/sync/deletion phase passes, the
  paywall honestly says accounts arrive with community recipes and private
  sync, and Subscribe stays gated on sign-in.
- Account deletion copy already states that subscriptions are managed in
  the store, not in PitTech (both native deletion reviews link the store
  subscription manager), without blocking immediate deletion.
- Privacy declarations are unchanged: StoreKit is a system framework and
  Play Billing adds no ad/tracking SDK, so `PrivacyInfo.xcprivacy`, the
  privacy site, and the store declarations need no new entries for this
  lane. The terms links point at the platform subscription terms (Apple's
  standard EULA, Google Play terms) until PitTech publishes its own
  subscription terms in the store slice.

## Native lane: entitlement-state UI matrix

| State | UI |
|---|---|
| Verified entitled | The Pro view opens (a slice-18 placeholder until the real views land); zero ad requests via the same gate state |
| Verified not entitled | Contextual paywall with preview |
| Unknown/loading | Neutral loading state — never a flash of ads and never a flash of the paywall for a previously entitled user |
| Expired (verified ended states) | Free fallback: local records, exported files, and all free capabilities keep working; previously generated Pro exports are not destroyed |

The last verified cache record persists on-device (SharedPreferences /
UserDefaults) so a recently verified subscriber keeps Pro offline for the
server's bounded window; nothing the server did not verify is ever granted,
and a sign-out clears the cache so accounts never share entitlement. An
active cook is never interrupted by an entitlement transition: refreshes are
silent, the paywall opens only from explicit taps, and Live/Finish contain
no paywall presentation code.

Native tests: `ProPaywallTests` (17 unit tests) + `ProPaywallUITests`
(7 UI tests at both text sizes) on iOS; JVM `ProPaywallTest` (16),
`SubscriptionManagerTest` (14), `ProGateTest` (7), and
`SubscriptionClientTest` (2), plus `ProPaywallTest` (6 stub-driven UI tests),
`ProPaywallContentTest` (8 purchase-flow UI tests),
`ProPaywallFlowTest` (4 real-navigation tests), and
`ProPaywallNoInterruptTest` (full Live start-to-finish) on Android, with
the companion selector at `OK (45 tests)`.
Zero-ad-requests-under-entitlement and the no-flash rules are proven via
the `AdRequestMonitor` instrumentation hook, and the no-interrupt rule via
the `ProPaywallPresentation` recorder (empty after Live + Finish).

## What remains

Owner-gated: store product creation and identifiers (then the Release
product-ID values on both clients), App Store Server API credentials, Play
service account and RTDN topic wiring, sandbox purchase evidence,
TestFlight/closed-testing passes, and full-chain Apple X.509 fixtures from
live sandbox notifications (unit tests pin generated keys and cover chain
rejection paths). Slice 18 fills the Pro views behind these gates with the
real personal-analysis, advanced-curve, recap, and Pro-theme content, and
the store slice publishes PitTech-hosted subscription terms. Runtime
evidence (Actions runs, staging verification) is still required; this draft
was written without a local build.
