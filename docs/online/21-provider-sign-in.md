# Provider sign-in — Sign in with Apple (iOS) and Sign in with Google (Android)

Implementation plan for issue #123, from the owner's 2026-10-21 decision.
Not yet implemented. Email code/link sign-in (PR 08/09) stays as the recovery
and cross-platform bridge; provider sign-in is an additional authenticator on
the same account, never a parallel account system. This slice lands before
staging activation: staging acceptance step 2 expands to cover it (see
Sequencing). No provider button ships in a build whose deployment cannot
verify that provider's tokens.

## 1. Backend

### Endpoints

New routes in `api-service/src/http.ts`, added to the existing auth route
block so they inherit its contract unchanged: HTTPS JSON only, 8 KiB body
bound, `private, no-store`, the shared 60-attempt/IP/hour relay bucket, the
auth concurrency cap, and the `ApiError` shape
(`{error:{code,message},requestId}`, generic messages only — no provider
diagnostics, no token or email in logs or error text).

| Method | Route | Caller | Input / result |
|---|---|---|---|
| POST | `/v1/auth/provider/challenges` | Signed-out, or signed-in (attach) | `{provider, idempotencyId}` plus the caller's proof challenge (below); 202 with challenge UUID and 900-second lifetime. With an access bearer, the challenge is bound to that account. |
| POST | `/v1/auth/provider/verify` | Signed-out | Challenge UUID, originating device's `verifier`, provider `idToken`, UUID `idempotencyId`; the standard `AuthSession` response, identical to `/v1/auth/verify`. |
| POST | `/v1/me/identities` | Signed-in | Same exchange body as provider verify, against a challenge that was created bound; links the identity to the session's account and returns the updated identity list. No new session is issued. |
| GET | `/v1/me` | Signed-in | Existing `{id, email}` plus an additive `identities: [{provider}]` field. Existing native parsers ignore unknown fields. |
| POST | `/v1/auth/apple/notifications` | Apple | `{signedPayload}` JWS; always 200 after signature verification and durable receipt, including for unknown subjects (see Notifications). |

### Proof-bound exchange (why two steps)

Provider sign-in reuses the PR 08 device-binding contract instead of a bare
token POST, so a leaked identity token is not directly exchangeable:

1. The client generates 32 random bytes as canonical unpadded base64url —
   the existing generators (`NativeAccountProtocol.verifier()` /
   `AccountProtocol.verifier()`) — and computes the SHA-256 hex of the UTF-8
   string (`proof()`, locked by `contracts/fixtures/auth-native-v1.json`).
2. Challenge creation stores only the proof hash, the provider, an optional
   bound account, and a 15-minute expiry (`AUTH.challengeMs`), with the same
   at-most-five-attempts counter semantics as email challenges.
3. The client starts the provider flow with the proof hash as the nonce
   (Apple `ASAuthorization` request nonce; Google `GetGoogleIdOption`
   nonce). The provider's identity token therefore carries
   `nonce == proofChallenge`.
4. Verify presents the raw `verifier` plus the identity token. The server
   requires `proofHash(verifier)` to equal both the stored proof hash and
   the token's `nonce` claim, then validates the token (below). Consumption,
   the 30-second encrypted exchange-recovery envelope bound to the original
   secret and operation UUID, and exact-retry semantics copy
   `Authentication.verify` — including committing failed-attempt counters
   and never rolling them back with the 401.

The email `auth.challenges` table cannot be reused: `email`, `code_hash` and
`link_hash` are `NOT NULL` and there is no email before verification. New
migration `008_provider_auth.sql` (shipped migrations are never rewritten):

- `auth.provider_challenges(id, provider, proof_hash, user_id NULL,
  created_at, expires_at, attempts, consumed_at, exchange_id,
  exchange_envelope, exchange_expires_at)` — mirrors the exchange columns of
  `auth.challenges`.
- `auth.provider_identities(user_id REFERENCES auth.users ON DELETE CASCADE,
  provider, subject_key, email, created_at,
  PRIMARY KEY (provider, subject_key), UNIQUE (user_id, provider))`.
  `subject_key` is `AuthSecrets.hash('provider/' + provider, sub)` — the
  existing domain-separated keyed-hash pattern (`auth-credentials.ts`);
  raw subjects are never stored. `email` is the provider-reported address at
  link time, informational only; it is never an identity key and never
  overwrites `auth.users.email`.
- `operations/auth-runtime-role.sql` gains explicit grants for both tables;
  no administrator or migrator access moves into the API.

Session issuance factors the user/session block out of
`Authentication.verify` (`tokens()` already exists): provider verify and
email verify end in the same `auth.sessions` + `auth.session_tokens` rows,
the same absolute 30-day session lifetime, 10-minute access credentials,
rotation and family-revocation rules. Provider verify performs the same
locking discipline as email verify (`lockAccount` on the resolved account,
advisory lock on the email key for creation) and the same
`account_lifecycle.deleting` rejection in verify, refresh and `user()`.

### Token verification (`api-service/src/auth-providers.ts`, new)

- **Key material.** `googleSigningKeys()` in `google-oidc-keys.ts` already
  validates a JWKS document of exactly the shape both providers publish
  (RSA, RS256, `use=sig`, bounded `n`/`e`, no private fields, ≤16 keys).
  It is reused unchanged. `GoogleOidcKeyCache` hardcodes the Google certs
  endpoint, so the cache is parameterized by endpoint (Google default) and
  instantiated per issuer:
  `https://www.googleapis.com/oauth2/v3/certs` (Google, the same endpoint
  the RTDN path already trusts) and `https://appleid.apple.com/auth/keys`
  (Apple). Atomic replacement, cache-control-derived lifetime and
  fail-closed expiry semantics carry over. Tests inject the cache's existing
  `fetch` parameter or pin generated keys, as in
  `test/google-oidc-keys.test.ts` and `test/billing-fixtures.ts`.
- **What is not reused.** `verifyOidcBearer` (RTDN) pins an expected service
  -account email claim and is not an identity-token validator. The StoreKit
  path in `subscriptions-apple.ts` is ES256 with an `x5c` chain — Apple
  identity tokens are RS256 against the JWKS above, so that verifier does
  not apply to sign-in. (It does apply to Apple notifications, below.)
- **Claims, both providers.** Signature against the cached key by `kid`;
  `alg` exactly RS256; `exp` in the future and `iat` not beyond the existing
  5-minute future tolerance used by `verifyOidcBearer`; `nonce` equal to
  the challenge proof hash; `sub` present and bounded. Any failure is the
  existing `ApiError(401, 'sign_in_unavailable')` — one code for every
  provider failure mode, matching email verify.
- **Apple.** `iss` exactly `https://appleid.apple.com`. `aud` is the bundle
  ID of the app that requested authorization, checked against the
  deployment's configured audience set (see Config). Apple only guarantees
  the `email` claim on the first authorization for the app; later tokens
  may omit it, and may carry `is_private_email` for a private-relay address.
- **Google.** `iss` is `https://accounts.google.com` or
  `accounts.google.com` (the pair `verifyOidcBearer` already accepts).
  `aud` is the server (web) OAuth client ID, never an Android client ID.
  Account creation additionally requires an `email` claim with
  `email_verified == true`.

### Account creation, and the email constraint

`auth.users.email` is `NOT NULL UNIQUE` — it is today's account key, the
deletion/recovery contact, and the lifecycle lock identity. Provider-first
creation therefore requires a provider-verified email at the first
exchange: Apple's first-authorization `email` claim, or Google's verified
email claim. That email becomes `auth.users.email`. Consequences, stated as
rules rather than options:

- A returning Apple token with no `email` claim and no existing
  `(apple, sub)` identity cannot create an account → 401
  `sign_in_unavailable`, and the client directs the user to email sign-in,
  then attach (section 2). No schema relaxation: making `email` nullable
  would break the lifecycle, deletion-notice and challenge flows that key
  off it.
- An Apple private-relay address (`…@privaterelay.appleid.com`) is a real,
  deliverable address and is stored as the account email as-is. Lifecycle
  and deletion mail already goes through Resend from the verified project
  sender; relay forwarding state changes arrive as Apple notifications
  (below). Nothing in the account, sync or publication layers may treat a
  relay address as second-class, and clients never display it differently.
- If the provider-verified email already belongs to an existing account,
  the exchange does **not** merge (section 2).

### Apple server-to-server notifications

Apple posts Sign in with Apple events (account-delete, consent-revoked,
email-enabled, email-disabled) as a signed JWS `signedPayload` to the URL
registered in the developer portal. `POST /v1/auth/apple/notifications`:

- Verifies the JWS with `verifyAppleJws` in chain mode against the Apple
  roots — the one place the StoreKit verifier does apply. The roots are
  loaded when provider sign-in is configured, not only under
  `SUBSCRIPTIONS_ENABLED` as today (see Config). Pinned-key mode remains
  the test configuration.
- Dedupes durably on the event's unique identifier before acting, then
  answers 200. Unknown subjects are a no-op 200: the endpoint never reveals
  whether an identity exists.
- `account-delete` and `consent-revoked`: the linked account enters the
  PR 13 lifecycle exactly as a native deletion request leaves it — a
  `moderation.deletions` job the existing sweep consumes,
  `auth.account_lifecycle.deleting` set, sessions and challenges revoked,
  publications hidden, originals/private data purged under the same fences,
  independent forks surviving. One difference is forced by the channel:
  there is no initiating device, so no proof/receipt capability is issued;
  the job is created by a new `AccountDeletion` entry point that performs
  `request()`'s account-side effects with a server-generated operation ID
  derived from the Apple event ID (which is also the dedupe key). The
  sweep's purge/finish steps then remove the `auth.users` row, and
  `provider_identities` rows cascade from it.
- `email-enabled` / `email-disabled`: updates only the informational
  `provider_identities.email` reachability for the relay address. No
  account state changes.
- The endpoint is registered only when Apple provider sign-in is configured
  **and** `ACCOUNT_LIFECYCLE_ENABLED` is on; console registration of the
  notification URL happens after that deployment state exists (checklist
  order matters).

### Config (`config.ts`, fail-closed as today)

Provider sign-in rides `AUTH_ENABLED` — no separate backend flag. Accounts
ship as one phase (PR 08/13); a flag that let email accounts run while
provider exchanges 401 would be incoherent. When `AUTH_ENABLED=true`:

- `AUTH_APPLE_AUDIENCES` — comma-separated bundle IDs accepted as `aud`.
  Staging deployment: `com.pittech.staging,com.pittech`. The second entry
  is required, not sloppy: the approved store-sandbox build keeps the
  registered `com.pittech` identity while talking to the staging API, so
  its tokens name `com.pittech`. Production deployment: `com.pittech`
  only.
- `AUTH_GOOGLE_WEB_CLIENT_ID` — the server OAuth client ID used as the
  Google `aud`. A public identifier, held in env for symmetry with the
  `PRO_*` store config, not a secret.
- Apple roots for notification verification are loaded from the existing
  `PRO_APPLE_ROOTS` value when present; if Apple sign-in is configured
  without billing, the same variable is required on its own.
- Key-cache refresh joins startup and the existing OIDC refresh timer
  pattern in `main.ts` whenever auth is enabled — today that refresh only
  exists when subscriptions are enabled, which provider sign-in must not
  depend on.
- Startup fails if either provider is half-configured (audiences without
  key refresh, web client ID without the route enabled natively is a client
  concern, not the server's). Email-only deployments set neither variable
  and register no provider routes.

## 2. Account linking model

One account, up to three authenticators: the email credential (the
account's `users.email`), one Apple identity, one Google identity
(`UNIQUE (user_id, provider)`).

- **Attach while signed in** is the only linking path. The signed-in user
  opens account settings, chooses the provider, and completes the
  proof-bound exchange against a challenge bound to their account
  (`POST /v1/me/identities`). Linking never happens as a side effect of
  sign-in.
- **No silent merge on email match.** A signed-out provider exchange whose
  verified email equals an existing account's email — but whose
  `(provider, sub)` is not linked to it — is refused with
  `ApiError(409, 'identity_link_required')` (message added to the `http.ts`
  messages map: sign in with the account's existing method, then add the
  provider). Email addresses change hands and provider email claims are
  point-in-time facts; the subject, not the address, is the identity.
- **Identity already linked elsewhere.** Attach or sign-in presenting an
  identity linked to a different account is refused 409
  `identity_link_required` with the same client guidance; the existing
  account is never exposed or hinted at beyond that.
- **Sign-in with a linked identity** resolves `subject_key` → account,
  applies the deleting check, and issues the standard session. The email
  claim (or its absence) is irrelevant on this path.
- **Cross-platform consequence.** A user who attaches Apple on iOS and
  Google on Android to the one account — in either order, via email
  sign-in on the second platform first — has one synced library, one
  entitlement holder and one deletion target. Subscription account-binding
  (PR 16) is unaffected: it binds store purchases to the account, not to
  an authenticator.
- Detach is out of scope for this slice. Deletion removes identity rows by
  cascade; an account always retains its email authenticator, so no
  lock-out state is reachable through provider flows.

## 3. iOS client

- **Flow.** `ASAuthorizationAppleIDController` with an
  `ASAuthorizationAppleIDProvider` request, `requestedScopes = [.email]`
  (PitTech accounts have no name field; the first-authorization name is not
  collected), `request.nonce = proofChallenge`. The identity token
  (UTF-8 string) goes to the provider exchange; the authorization code is
  discarded — native sign-in needs no server-side code exchange, client
  secret or Services ID.
- **Seam.** The `AuthenticationServices` call sits behind a protocol
  (`AppleAuthorizing`) injected into `NativeAccountCoordinator`, next to
  the existing `NativeAccountAPI` seam in `AccountClient.swift`. The
  coordinator gains `signInWithApple()` and `attachApple()` sharing the
  email flow's `perform` serialization, busy/message presentation and
  restart-safe state handling. The returned session is accepted through
  the same path `verify` uses and persisted by `NativeAccountVault`
  (device-only, non-synchronizing Keychain) — no new storage namespace.
  The pending-operation rule carries over: the challenge ID, verifier and
  operation UUID are persisted before the provider UI is presented, so a
  lost response retries the exact exchange.
- **UI.** `NativeAccountView` gains a "Continue with Apple" button in the
  signed-out section and an "Add Apple sign-in" row (with linked state
  from `GET /v1/me`) in the signed-in Account section, using Apple's
  provided button/style rather than a custom lookalike. Both text sizes,
  scrollable forms — the PR 09 presentation rules.
- **Entitlement and profiles.** Add
  `com.apple.developer.applesignin = [Default]` to
  `ios/PitTech/PitTech.entitlements` (currently Associated Domains only).
  The audience is the bundle ID, so profiles need no new configuration:
  production/Release is `com.pittech`, the Staging configuration in
  `ios/project.yml` is `com.pittech.staging`, and the store-sandbox build
  presents `com.pittech` — exactly the audience set the staging backend is
  configured to accept (section 1). CI profile creation must enable the
  Sign in with Apple capability on the App ID before minting the profile,
  mirroring how `app-store-ipa` already enables Associated Domains
  (AGENTS.md rule 2).
- **Tests.** No test contacts Apple. Unit tests inject a fake
  `AppleAuthorizing` (fixed token / cancellation / error) plus the Memory
  vault and fake API from `AccountUITestSupport.swift`, extending
  `PitTechTests/AccountTests.swift`: challenge persisted before
  presentation, nonce equals proof hash, lost-response retry, 409 guidance
  state, attach while signed in, rejection removes nothing. UI tests use
  the same injected fakes through the existing UI-test support entry
  point and capture the signed-out/signed-in provider states at both text
  sizes. Proof/nonce hashing is already pinned cross-platform by
  `auth-native-v1.json`; the fixture gains a provider case fixing the
  token-nonce/proof relation for both clients and the backend.

## 4. Android client

- **Flow.** Credential Manager (`androidx.credentials`) with
  `GetGoogleIdOption`: `setServerClientId(webClientId)`,
  `setNonce(proofChallenge)`, `setFilterByAuthorizedAccounts(false)` (first
  sign-in must be able to create the account). The returned Google ID
  token goes to the provider exchange. New dependencies:
  `androidx.credentials:credentials`,
  `androidx.credentials:credentials-play-services-auth`, and
  `com.google.android.libraries.identity.googleid:googleid` — added
  through `app/build.gradle.kts` with the public-artifact audit kept green
  (pinned versions, as with the existing Google SDKs).
- **Audience.** The token's `aud` is the **web** client ID; that is the
  value the backend checks (section 1). The Android OAuth clients exist
  only to bind packages/certificates to that web client's project and
  never appear in the token.
- **Seam.** Credential Manager sits behind a `GoogleIdProvider` interface
  injected into `AccountManager` (mirroring the iOS protocol and the
  existing `AccountAPI` fun-interface fake seam in `AccountClient.kt`).
  `AccountManager` gains `signInWithGoogle()` / `attachGoogle()` sharing
  its mutex serialization, pending-operation persistence in
  `AccountVault` (AES-256-GCM Keystore, `noBackupFilesDir`), presentation
  state and deletion interplay. No new storage; the session type is the
  existing `AccountSession`.
- **Build config.** The web client ID arrives as
  `BuildConfig.PITTECH_GOOGLE_WEB_CLIENT_ID`, set from a Gradle property /
  CI variable beside the existing `-PpittechOnlineEnvironment` /
  `-PpittechOnlineEnabled` inputs; empty means the Google button is not
  rendered. It is a public identifier, not a secret. Environment behavior
  is otherwise profile-driven exactly as today (`OnlineConfiguration`):
  Dev builds (`com.pittech.dev`) talk to staging, the registered package
  talks to production, store-sandbox builds keep `com.pittech` against
  staging.
- **OAuth clients and certificates** (project `848714330882`; register the
  SHA-1 of each certificate in the console — the console's Android field —
  deriving it from the same certificates whose SHA-256 values are quoted
  here and in `assetlinks.json`):
  - Android client for `com.pittech` with the **upload key** certificate
    and with the **Play app-signing** certificate. End-user devices run
    Google's re-signed build, so the app-signing certificate is the one
    that matters in production; the upload-key entry covers CI-installed
    builds. The app-signing fingerprint is read from Play Console → App
    signing; it is **not** the Dev certificate below, and it is still
    missing from the live `assetlinks.json` — the same gap tracked in
    issue #123.
  - Android client for `com.pittech.dev` with the CI upload-key
    certificate, SHA-256
    `63:BA:B5:50:81:BA:92:FE:A8:81:7E:55:F0:F8:B2:C9:A8:3C:1D:C1:2A:E8:F0:CE:07:F0:6F:A5:59:5D:7E:2C`
    (the Dev build type is signed with the release/upload key in CI).
  - The pure `debug` build type (`com.pittech.debug`, standard debug
    certificate) is a test-only vehicle that runs against injected fakes;
    register an Android client for it only if a manual staging smoke test
    on a debug build is ever wanted. Acceptance vehicles are Dev and the
    registered-package sandbox build.
- **Tests.** Unit tests (`AccountTest.kt` pattern) inject a fake
  `GoogleIdProvider` and fake `AccountAPI`: nonce/proof equality, token
  pass-through with no logging, cancellation leaves no pending state,
  lost-response exact retry, attach and 409 states. Instrumented/UI tests
  (`AccountUserFlowTest`, emulator script selectors) run only against the
  local test API with synthetic tokens — no live Google call, matching
  the PR 09 rule. Emulator expected-test counts in
  `scripts/run-emulator-ui-tests.sh` are updated in the same change.

## 5. Console and configuration checklist

Apple (developer portal / App Store Connect):

- [ ] Enable the **Sign in with Apple** capability on App ID
      `com.pittech` and on App ID `com.pittech.staging`. (No Services ID,
      no client secret, no private key: native-only sign-in. A key would
      be needed only for a future web flow, which is out of scope — the
      `/delete-account` browser flow stays email-code.)
- [ ] After the staging/production deployments have the notification
      endpoint live (section 1), set the **server-to-server notification
      URL** for the capability to
      `https://api-staging.pittech.app/v1/auth/apple/notifications` /
      `https://api.pittech.app/v1/auth/apple/notifications` per
      environment's App ID configuration.
- [ ] CI: confirm the `app-store-ipa` profile step enables the capability
      on the App ID before profile creation (AGENTS.md rule 2), for both
      bundle IDs' profiles.

Google Cloud / Firebase (project `848714330882`):

- [ ] Create the **web (server) OAuth client**; its client ID becomes
      `AUTH_GOOGLE_WEB_CLIENT_ID` (backend env) and
      `PITTECH_GOOGLE_WEB_CLIENT_ID` (Android build input).
- [ ] Create Android OAuth clients per section 4: `com.pittech` (upload
      key + Play app-signing certificates), `com.pittech.dev` (CI upload
      key).
- [ ] Confirm the OAuth consent screen names PitTech and requests only
      `openid email` — no profile scopes beyond the ID token defaults.

Repo / CI placement (no secrets anywhere in this slice — there are none
to store):

- Backend env (Railway, per environment): `AUTH_APPLE_AUDIENCES`,
  `AUTH_GOOGLE_WEB_CLIENT_ID`, `PRO_APPLE_ROOTS` if not already present
  for billing.
- GitHub Actions **vars** (public identifiers): the Google web client ID
  for Android profile builds, beside the existing AdMob vars.
  `online-profile-checks.yml` asserts the packaged per-profile result:
  iOS entitlements contain `applesignin` and the profile's bundle ID is in
  the deployment's audience set; the Android staging (Dev) artifact's
  `BuildConfig` carries the web client ID and the production artifact is
  checked the same way before any production enablement.
- `contracts/fixtures/online-environments-v1.json` gains the provider
  fields per profile (Apple audiences, Google web client ID presence) so
  backend and native expectations stay aligned.

## 6. Sequencing against issue #123, flags, privacy

- **Order.** This slice is implemented and merged behind the existing
  gates, then staging acceptance runs **before** any staging flag is
  enabled. Doc 20 walkthrough step 2 expands to: real email code/link
  (unchanged); Sign in with Apple on iOS against staging
  (`com.pittech.staging` audience, plus the store-sandbox build's
  `com.pittech` audience); Sign in with Google on Android Dev; attach the
  second provider while signed in on each platform; prove the same
  account on both platforms by the step-3 sync walkthrough; create one
  account with an Apple private-relay address and complete its lifecycle;
  exercise the 409 path by attempting a provider sign-in whose email
  matches the email-created test account. Production enablement repeats
  the audience/console steps for `com.pittech` only after staging
  acceptance, in the production phase of the rollout.
- **Flags.** Backend: rides `AUTH_ENABLED` (section 1); provider routes
  exist only when that deployment's provider config is complete. Clients:
  the existing profile gates (`PITTECH_ONLINE_ENABLED` /
  `PITTECHOnlineEnabled`, default off) still govern all account entry;
  provider buttons additionally require their per-provider build inputs
  (Apple entitlement present; Google web client ID non-empty), so a
  build can offer email-only sign-in against a deployment that has no
  provider config. No new flag is introduced on either side.
- **Privacy declarations** (AGENTS.md rule 5 — same change, not a
  follow-up): App Privacy and Play Data safety already gain account data
  (email address) from the #123 redo; provider sign-in adds that the
  email and an opaque provider identifier are received from Apple/Google
  when the user chooses that method, used for authentication only, never
  for tracking or advertising. `PrivacyInfo.xcprivacy` is checked against
  the newly linked Google ID / Credential Manager SDKs on Android's side
  by parity of declarations, and `privacy-site/index.html` gains one
  sentence naming both providers. Sign in with Apple itself is a
  first-party framework and adds no SDK.

## 7. Test plan

Backend (`online-services.yml`, real Postgres 16, strict typecheck):

- Synthetic JWKS/tokens in the `test/billing-fixtures.ts` idiom: generated
  RSA keypairs, tokens minted with `createSign`, key caches pinned or fed
  by injected `fetch`. Cases per provider: valid exchange creates the
  account and a standard session; wrong `iss`/`aud`/`kid`, expired token,
  nonce mismatch, replayed challenge, sixth attempt, consumed-challenge
  exact retry inside the recovery window, changed-body retry rejection.
- Linking: attach while signed in; identity linked to another account →
  409; verified-email match without link → 409 and no session; returning
  Apple token without email and without link → 401; with link → session.
  Private-relay creation stores the relay address verbatim.
- Lifecycle interplay: deleting account rejects provider verify/refresh;
  Apple notification with a valid chain-signed payload dedupes, flags the
  account and is swept to completion with forks surviving; unknown-subject
  and bad-signature payloads change nothing (bad signature is not a 200).
  New file `test/provider-auth-postgres.test.ts` beside
  `auth-postgres.test.ts`; role-isolation assertions cover the new tables.
- Fixture: `auth-native-v1.json` provider case consumed by backend and
  both native suites.

Native (existing suites, fakes only — no live Apple/Google call anywhere):

- iOS: `ios-build.yml` full unit/UI suite; `AccountTests.swift` additions
  per section 3; signed-out/signed-in provider captures at both text
  sizes.
- Android: `android-build.yml` API 36 gate lanes (unit + emulator);
  `AccountTest.kt` / `AccountUserFlowTest` additions per section 4, with
  the emulator script's expected counts updated in the same change.
- Profiles: `online-profile-checks.yml` per section 5; the public-artifact
  audit (`telemetry-public-release-audit.yml`) proves the new dependencies
  and no provider secret or test token enters a shipped artifact.

## 8. Risks and open questions for the owner

- Apple only guarantees the email claim on a user's **first**
  authorization for the app. A user whose first-ever sign-in happens on a
  build before this ships, or who revokes and returns, can hit the
  no-email/no-link 401 and be routed to email sign-in + attach. Accepted
  product behavior, or should first contact always be email? (Plan assumes
  accepted, with the 409/attach guidance as the remedy.)
- The Play app-signing certificate fingerprint is still unknown (same gap
  as `assetlinks.json`). Google sign-in on production-package builds
  cannot be verified until it is read from Play Console and registered.
- Sign in with Apple on the staging App ID (`com.pittech.staging`) gives
  staging testers a real Apple prompt against a non-store build —
  acceptable for TestFlight/internal staging, but the owner should
  confirm staging Apple sign-ins may use personal Apple IDs.
- Detach/unlink is deferred. If a user attaches the wrong Google account,
  the only remedy in this slice is account deletion. Confirm that is
  acceptable for launch, or detach becomes a small follow-up (it needs a
  "keep at least one working authenticator" rule, which email already
  satisfies).
