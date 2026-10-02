# GrillirG cloud monitor

Available in debug and Firebase Dev builds under **Devices → Wi-Fi monitor**. Bluetooth discovery remains a separate tab. The controller must already be on Wi-Fi and bound to, or shared with, a GrillirG account. This path does not depend on Android pairing or the unfinished BLE credential/status exchange.

## Physical test

1. Install the new Firebase Dev APK and open Devices → Wi-Fi monitor.
2. Enter the email used in GrillirG, tap Send sign-in code, then enter the six-digit email code. The account's device list supplies the device ID; no manually entered grill ID is needed.
3. A single grill is selected automatically. If several are returned, select the intended grill.
4. Compare chamber/probe readings, unit, setpoint and operating status with the physical controller and vendor app. Watch online status and fetch ages while changing the physical controller's temperature setting.
5. Turn the phone's internet off/on, leave and return to the Devices screen, and test a controller Wi-Fi interruption. Verify retained readings are labelled old/cached and monitoring recovers. Backend reachability and the vendor's grill-online report are separate facts.
6. Review connection details and, if needed, review and submit the connection report through the existing public GitHub feedback relay.

User authentication and a live physical controller are required to verify this particular hardware. CI uses synthetic responses and fake UI engines; a passing build is not hardware verification. If the account has no devices, check account ownership or the accepted sharing invitation in GrillirG. Session displacement (`-10108`) is surfaced explicitly. A separately shared account can allow simultaneous clients when the vendor enforces session limits.

## Protocol and scope

Fixed HTTPS origin: `https://api.prime-polaris.com/api`. This independent Kotlin adapter uses observations in [ha-prime-polaris API notes](https://github.com/kingchddg901/ha-prime-polaris/blob/main/docs/api.md), cross-checked against PitTech's [GrillirG APK investigation](CONTROLLER_INTERROGATION.md). No third-party integration source is copied into the app.

The transport accepts an enum of five operations only:

| Operation | POST path | Authentication |
| --- | --- | --- |
| Explicit email code | `/email/m2s/send/verify/code` | None |
| Explicit sign-in | `/auth/m2s/email/login` | None |
| Account's devices | `/dms/queryDeviceListByUserId` | Bearer token |
| Online/running status | `/dms/queryDeviceStatus` | Bearer token |
| Controller observations | `/dms/queryDeviceRealTimeData` | Bearer token |

All HTTP methods are POST because that is the vendor protocol; the last three operations are observational. The adapter contains no grill-control, binding/unbinding, provisioning, OTA, or push-token-registration request. It does not subscribe as the vendor FCM client or replace the phone's push token. The normal production release's existing feature gate remains in place.

Responses must have a valid `respCode=10000` envelope and the expected data structure. HTTP errors, malformed responses, missing fields, expired/invalid tokens, displaced sessions and rate limits have distinct diagnostics. Null/missing temperatures do not become zero. Numeric strings are accepted; booleans, non-finite numbers, fractional enum values and temperature sentinels outside -100…1000 are excluded. `tempUnit=0` means Fahrenheit and `1` means Celsius; an absent/unknown unit stays unknown. `onlineStatus=0` means online; a missing status is unknown. Unknown statuses are displayed numerically without invented meanings.

## Lifecycle and resilience

A single coroutine poller performs status and readings requests serially every 15 seconds while PitTech is STARTED or an attached cook has an active recording service. Leaving the app pauses monitoring only when no cook is recording. Selecting another device, refreshing and signing out cancel the old poller; active cook recording locks device selection. HTTP cancellation follows coroutine cancellation, and cancelled completions cannot publish readings. Selecting a different grill clears the previous grill's readings. A partial status-endpoint failure still permits a readings request without inventing an online flag. A status rate limit defers the second request.

Incomplete polls back off to 30, 60 and 120 seconds. Numeric `Retry-After` is honored within a five-minute cap. HTTP timeouts and response sizes are bounded. Redirects and implicit HTTP retries are disabled, including for code requests. Expired/displaced sessions stop monitoring and require explicit sign-in; no automatic email codes or login loops occur.

Readings are retained on this screen, with their fetch time, and labelled old after 45 seconds, after a failed readings request, or when the vendor reports the grill offline. The device's actual sample timestamp is unknown: recent cloud receipt does not establish that the controller supplied fresh data. Up to 120 typed observations and 80 safe request events are kept in memory in the shared monitor. Attached cooks persist their own readings and context through [connected cook recording](CONTROLLER_COOK_RECORDING.md). Without an attached recording session, visiting Devices does not add readings to a cook.

## Credentials and diagnostics

Only the sign-in token, its decoded expiry when available, and selected device ID are persisted. The session is encrypted using Android Keystore AES-GCM in no-backup storage; no plaintext fallback exists. Email and OTP stay in the current form, and the code clears on submission. Sign out removes the local credential rather than issuing a vendor-account logout.

The public report is assembled from typed facts: operation names, HTTP/API codes, request duration, bounded numeric observations, recognized field names, omitted-field counts and alarm counts. It excludes email, codes, bearer tokens, headers, raw JSON, device IDs, user-defined device names, Wi-Fi identities and alarm contents. Submission requires the user to preview the report and tap Submit report. Unknown response field values never enter public diagnostics.

## Verification

JVM tests cover request/auth shapes, device discovery, malformed envelopes, nullable readings, units/sentinels, token expiry, session displacement, rate limits, privacy, stale-state policy, partial endpoint failure, cancellation, recovery, multi-device selection and sign out. Instrumentation tests cover explicit sign-in, offline labels, report preview/cancel/submit, recovery and encrypted-session round-trip/tamper rejection. The five new instrumentation tests run before the existing app flows on both API 36 and API 37 in CI. The repository's existing Android 17 graphics-related advisory policy is unchanged.
