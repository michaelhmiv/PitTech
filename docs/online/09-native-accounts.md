# PR 09 — Native account sessions

Both native clients implement the PR 08 proof-bound email code/link contract.
Account entry points remain disabled by a source constant in both release and
Debug builds. Isolated automation injects a memory vault and local test API;
there is no test switch that contacts a live account service. The complete
accounts/social/sync/deletion phase must pass before enabling these entry points.

| Behavior | Android | iOS |
|---|---|---|
| Email request and six-digit verification | Native Compose form | Native SwiftUI form |
| Link on originating device | Strict HTTPS origin/path/fragment; explicit confirmation | Same; universal link activity and URL intake |
| Email opened elsewhere | Enter code on requesting device | Same |
| Resend | 60-second cooldown; new challenge/proof invalidates earlier email | Same |
| Interrupted request | Persist exact operation/proof/secret before sending; restart retries it | Same |
| Session storage | AES-256-GCM via non-exportable Android Keystore, AtomicFile in noBackupFilesDir | Device-only, non-synchronizing Keychain item |
| Refresh | Mutex serialization, same UUID through ambiguous response | Single-flight task, same UUID through ambiguous response |
| Expiry / rejected refresh | Remove active account; local library retained | Same |
| Offline sign-out | Atomically clear active account; one encrypted revocation retries before next sign-in | Same |
| Automatic code suggestions | Email code entered/pasted in native field; no SMS permission | System one-time-code suggestion when available; platform-appropriate |
| Normal / enlarged text | Scrollable native controls, 48 dp minimum actions | Form reflows; keyboard dismissal and native controls |

No account object touches Room, CookStore, library archives, relay owner storage,
public content, or sync. Sign-out retains the local journal. It is not deletion:
PR 13 must supply account deletion before launch. The server's 30-second exact
retry recovery window remains a bound: an unrecoverable exchange or refresh
requires a new sign-in. Challenges are pruned from device storage after 15
minutes on restart. Failed device storage prevents outbound sign-in requests.
Network errors retain exact pending operations; they never silently generate a
new refresh UUID. No email/code/token/provider response is logged or returned
in error text. All credential transport uses the fixed HTTPS API origin,
no redirects/cookies/cache and an 8 KiB JSON response bound.

The shared `auth-native-v1.json` fixture is consumed by both native unit suites
and the backend. It fixes proof hashing over the UTF-8 verifier string, canonical
base64url, permitted link origin and email normalization. ASCII validation occurs
before lowercasing, so Unicode case-folding cannot alias an ASCII account.
Apple association output includes `/auth`; actual signed installed-link routing
still requires staging/domain association and physical-device evidence.

## Verification

Draft source only until Actions proves native builds/tests. Native tests exercise
lost responses across restart, wrong-code correction, proof-bound link receipt,
serialized refresh, rejection, offline logout, secure storage and disabled gates.
Native UI tests use local synthetic responses, never Resend or a deployed API,
and capture signed-in/out views in the required normal/enlarged text lanes.
Real email delivery, installed links, staging origin/namespace configuration,
background/locked-device behavior and full Phase 2 release remain open gates.
