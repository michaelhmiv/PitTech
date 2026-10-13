# PitTech telemetry engineering report — 2026-10-13

The user authorized completion and merging on 2026-10-13. PRs #68–#72 have now merged
into main through GitHub; no app has been distributed. Public telemetry enablement remains
off. PR #75 remains a verification branch and will not be merged; its test fixes are on #74.
Final merge results and exact CI evidence will be recorded after verification.

The original `latestObservation` compilation failure is repaired in the foundation and
shared recorder. The five Android Beta connections now share persistent setup, source
selection, food/channel mapping, cook recording, source health, graphs and probe reminders.
Native iOS counterparts are separately documented in draft #87. Packaging isolation has
passed an actual APK/AAB/dependency audit. This is development evidence; no connector is
declared production-ready or endorsed by a manufacturer.

## Scope and branch reconciliation

The feature branches incorporate current `main` at
`713ec8c77d50a85046c3f35a1ce95983ce531573`, including the adaptive meal/recipe work and
the subsequent iOS asset-catalog fix. The Room schema remains version 10. No destructive
migration or replacement cook database was introduced. Existing manual logging, photos,
recipe composition, source assignments and cross-platform archives remain part of the
regression suites.

Each fix is on its owning feature branch. The combined branch copies the complete provider
and runtime implementation; public isolation lives on #76. These stacked PRs depend on
#68 and the connection UX/runtime in #74, so a standalone provider PR is not by itself the
complete application experience.

| PR | Software delivered | Technical status and remaining gate |
|---|---|---|
| [#68](https://github.com/michaelhmiv/PitTech/pull/68) | Read-only contract, strict normalization, scoped hashed identities, Room recording/watermarks, historical assignments, pause/removal events, durable recovery cursors, encrypted credential/profile storage | Implemented Beta foundation. Required emulator and active-cook upgrade verification are recorded below; device lock/key behavior still needs physical testing. |
| [#69](https://github.com/michaelhmiv/PitTech/pull/69) | MEATER account setup, multi-device snapshots, internal/ambient/observed target temperatures, shared token budget/cache, 429 cooldown and disconnect cleanup | Implemented software path. Real account/probe/bridge and intended service permissions are unverified. Published API supplies no history. |
| [#70](https://github.com/michaelhmiv/PitTech/pull/70) | User-owned HA origin validation, OAuth/refresh/revoke or advanced token, automatic temperature discovery, REST/Recorder history, authenticated multiplexed WebSocket, persistent mappings | Implemented software path. Actual server authorization, local/remote access, Recorder configuration and real sensor behavior are unverified. |
| [#71](https://github.com/michaelhmiv/PitTech/pull/71) | FireBoard login, channels and units, account-wide throttling, configured numeric session history, unique-label mapping and canonical history/live deduplication | Implemented software path. Account/model/session schemas, physical temperatures and intended service permissions are unverified. Duplicate chart labels are omitted rather than guessed. |
| [#72](https://github.com/michaelhmiv/PitTech/pull/72) | Manufacturer login, hostname-verified TLS MQTT, bounded initial subscription/snapshot, reconnect, public cook topic, channel/sentinel parsing | Conditional transport implemented. Still blocked on manufacturer-confirmed TLS host/port, account/device topic ACL and service permission evidence. No default endpoint was invented. Private topics/history are unsupported. |
| [#73](https://github.com/michaelhmiv/PitTech/pull/73) | Official SDK v3.5.4, Bluetooth onboarding, saved identity, raw/instant/derived Celsius readings, MeatNet routing, bounded radio/service leases and persisted session/sequence log anchors | Remaining Android software capability work is implemented and entering CI. Transfers are limited to 20,000 records and completed before recovery advances. Physical compatibility, repeater behavior and battery tests remain open. |
| [#74](https://github.com/michaelhmiv/PitTech/pull/74) | Unified Devices Beta UI, source/food mappings, live channel health, on-log sampling, independent observation jobs, opt-in foreground recorder, bounded retries/history, lifecycle/removal and persistent restart state | Implemented and verified Beta runtime. The required API 36 matrix passed at both font scales. One missing on-log sensor is isolated from other sensors on the same connection. Physical background/battery/vendor restrictions remain open. |
| [#75](https://github.com/michaelhmiv/PitTech/pull/75) | Combined Android compile, JVM, required API 36 font scales, process-death, upgrade and UI matrix | Required API 36 verification completed; never merge. API 37 retains its documented system-crash advisory result. Real providers remain unverified. |
| [#76](https://github.com/michaelhmiv/PitTech/pull/76) | Legacy client source-set isolation, source/compiled-artifact/dependency gates, notices, privacy draft and dated release evidence | Packaging implementation verified on the audited code snapshot below. Public Beta enablement remains off while provider/hardware/terms gates are open. |
| [#87](https://github.com/michaelhmiv/PitTech/pull/87) | Native five-provider Beta adapters, HA OAuth/WS, official Combustion SDK 1.0.0 raw and derived readings, local recording/mappings/archives, on-log/source isolation and foreground reminders | Native software is separate from Android. The previous complete Simulator workflow passed; the raw-sensor update is entering CI. iOS collection remains foreground-only. The pinned SDK exposes neither bounded log-request nor scanner-stop controls; native BLE history/repeater mode stays unavailable pending that SDK capability and hardware validation. |

## Recording and connection behavior

The phone connects directly to the selected manufacturer, user-operated Home Assistant server
or Bluetooth probe. Home Assistant is optional. No manufacturer community integration is
bundled, and no PitTech account, telemetry relay or centralized credential store is required.
The existing Railway privacy site identifies the HA OAuth client; the phone intercepts the
exact callback. No Railway deployment or infrastructure change was made.

Credentials are stored using Android Keystore AES-GCM in no-backup AtomicFiles or device-only
iOS Keychain after first unlock. Passwords remain transient login input. Connection removal
stops local collection and clears credentials while retaining cook history; remote HA
revocation is best-effort when reachable. The user may still need to revoke advanced/vendor
tokens in the original account. Credentials and configurations are excluded from cook
archives. Exported source/channel display names can identify equipment, including an HA
entity ID when no friendly name is available; an export is not anonymous.

Live/history event identities use canonical timestamps and stable local hashes. Overlapping
history windows are idempotent. Recovery cursors survive interruption and protect slower/new
channels. A source's original measurement time selects its historical role and food assignment.
Explicit pauses and finished cooks reject later ingestion. Providers without history leave
a gap. Raw BLE advertisements use explicitly identified phone receipt time; they do not
become historical samples merely because a connection was interrupted.

Channel health is evaluated per probe. A valid neighboring channel does not make an invalid
or stale food probe appear live or eligible for a reminder. Probe reminders require the
current role/assignment, a connected channel and a fresh eligible sample. Unsupported or
inactive imported rules remain inactive.

Android collection uses the existing private foreground recorder, a persistent notification,
pause action and bounded wake lease. It starts only for selected, opted-in active-cook sources.
On-log-only sampling does not require an idle foreground service. Provider account limits
override faster sampling preferences. Force-stop and ordinary process death are distinct:
Android cannot restart a force-stopped app until the user opens it. Real battery behavior and
OEM background restrictions have not been measured.

iOS makes no continuous background cloud polling claim. Native collection/reminders run
while the app is foreground; supported history is recovered after returning. HA requires
HTTPS, including trusted certificates for LAN servers. Android's explicit development-only
local HTTP option validates all resolved addresses and denies redirects/public hosts;
production cleartext remains disabled.

## Merge preparation and new capability work

The Android Combustion adapter now uses the official SDK's DeviceManager through a narrow
read-only facade. It exposes all eight raw sensors, instant-read, derived core/surface/ambient
channels and the documented MeatNet route. Discovery/status windows are at most eight seconds;
log transfer waits at most twelve seconds; leases are serialized across SDK singleton users,
cancelled on removal, and release the actual SDK service/radio between samples. The sampling
floor is thirty seconds. No power mode, prediction, alarm, food-safety, fan or DFU API is called.

Historical times are estimates anchored to a fresh connected status's session/sequence and
phone receipt, not probe-provided UTC. The anchor and sample period are persisted in a bounded
no-backup AtomicFile using hashed probe/session keys. Readings explicitly retain
`probe_sequence_anchor` as their timestamp basis. Overlap identities use session/sequence per
channel. Changed periods, stale/unconnected status, invalid epochs and clock discontinuities
reject history. SDK Date fallbacks are ignored. Incomplete or oversized sessions preserve the
recovery cursor for retry; the official public API cannot request only part of an oversized
session, so sessions larger than 20,000 records are not transferred. The physical accuracy of
the sequence-to-receipt estimate still needs measurement.

Eight Android Combustion JVM cases replace four passive-scanner cases, one normalization case
was added, and two instrumented no-backup anchor persistence/corruption cases are included in
the complete telemetry group. The previous 211-test result below remains a verified baseline;
new test totals will be recorded from CI, not inferred. Native adds two raw/derived/stale
sensor cases and keeps BLE history/repeater mode disabled: SDK 1.0.0 automatically requests
missing logs when connected, without a public bounded-request API or scanner-stop control.
See the pinned SDK [Probe source](https://github.com/combustion-inc/combustion-ios-ble/blob/1.0.0/Sources/CombustionBLE/Devices/Probe.swift)
and [DeviceManager source](https://github.com/combustion-inc/combustion-ios-ble/blob/1.0.0/Sources/CombustionBLE/DeviceManager.swift).

Firebase Dev distribution now requires a manual workflow dispatch, explicit confirmation and
successful Android checks for the exact main SHA. Play and App Store Connect remain manual.
Merging cannot itself distribute an app. Existing Railway privacy/feedback services already
track main and may rebuild automatically on merges; no new infrastructure or telemetry relay
is introduced. The privacy draft is part of the reviewed source change.

Foundation `e0ef50da` passed both required API 36 jobs and unsigned AAB before merging:
[38467172844](https://github.com/michaelhmiv/PitTech/actions/runs/38467172844),
[38467172982](https://github.com/michaelhmiv/PitTech/actions/runs/38467172982).
Current snapshots: Combustion `7f13c610`, runtime/test ownership `f447223f`, integration
`8768afac`. The final code tree `aa0e398e` has passed unsigned AAB and public source/APK/AAB/
dependency audit: [38469539519](https://github.com/michaelhmiv/PitTech/actions/runs/38469539519),
[38469539498](https://github.com/michaelhmiv/PitTech/actions/runs/38469539498). Full Android
and native UI jobs are still running at this checkpoint. Native has been restacked to
`d4e21a55` with the same native files; original native history is preserved on
`feature/telemetry-native-pre-merge-20261013`. #76 is being restacked without #75 history.
Final merge results and the required CI outcomes will replace this pending status.

## Test evidence

Results below identify exact verified code snapshots. Earlier partial failed runs are retained
as diagnostic evidence. GitHub artifacts contain unit XML, emulator
logs, screenshots, process/service evidence and native xcresult diagnostics for 14 days.

| Check | Verified result and exact evidence |
|---|---|
| Original Android compiler/AAB blocker | Fixed. Debug, instrumented, Release and Dev compilation succeeded after the recorder/DAO reconciliation. |
| Android JVM tests | **211 passed, 0 failures/errors**, from the passing API 36 font 1.5 artifact XML on combined `67ad1a03a64274487c182fb9fe6887ea8628c813`, [38461410874](https://github.com/michaelhmiv/PitTech/actions/runs/38461410874). Includes 54 telemetry/provider policy and transport tests. |
| Current combined unsigned AAB | Combined `67ad1a03a64274487c182fb9fe6887ea8628c813`: **passed**, [38461411065](https://github.com/michaelhmiv/PitTech/actions/runs/38461411065). Compilation only; no Play upload. |
| API 36 fonts 1.15 / 1.5 | **Both full required jobs passed** on `67ad1a03`, [38461410874](https://github.com/michaelhmiv/PitTech/actions/runs/38461410874): normal [115517227706](https://github.com/michaelhmiv/PitTech/actions/runs/38461410874/job/115517227706), large [115517227856](https://github.com/michaelhmiv/PitTech/actions/runs/38461410874/job/115517227856). Includes telemetry, real process death, upgrade/migration, archives, recipes, reminders, main UI, screenshots and internal regression groups. The prior contradictory 18/19 summary guard was repaired without removing tests. |
| Telemetry instrumented coverage | **20 passed at both API 36 font scales**: Room recording/archives/roles (8), encrypted token lifecycle (3), bounded/interrupted/saturated recovery (3), runtime restore/removal/source isolation (4), foreground-service lifecycle (1), unified onboarding UI (1). Two additional staged tests also passed; shell artifacts show changed process ID, a committed post-restart reading and foreground service with no resumed PitTech activity. |
| Process-death test repair | Captures a changed PID, a new committed Room watermark, a live foreground service and no resumed PitTech activity before the validation runner attaches. The debug fixture publishes only a committed Room watermark, after emission/throttling; a received but uncommitted frame cannot satisfy the shell. The runner itself replaces the target process, so its assertions inspect persisted post-restart Room data. Setup now cold-starts after instrumentation exits to avoid launching the process that the runner is still killing. No service was exported and no shell service bypass was added. |
| API 37 advisory | Latest combined [115517227531](https://github.com/michaelhmiv/PitTech/actions/runs/38461410874/job/115517227531) retains **INSTRUMENTATION_ABORTED: System has crashed**. The prior full saved diagnostic [115490706180](https://github.com/michaelhmiv/PitTech/actions/runs/38453663012/job/115490706180) identifies `mapper.ranchu` asserting `!rcEnc->featureInfo()->hasReadColorBufferDma`, then `system_server` SIGABRT in `TaskSnapshotPer`. Earlier preview runs also crashed graphics/RegionSampling. Advisory failure remains explicit; it is not counted as an app-test pass. |
| Public Android isolation | Source, actual unsigned APK/AAB DEX and resolved dependencies **passed** on synchronized release `3c712f003ca61241b27dde030d7fd966e0472cb5`, [38462767577](https://github.com/michaelhmiv/PitTech/actions/runs/38462767577). Its unsigned AAB also **passed**, [38462767864](https://github.com/michaelhmiv/PitTech/actions/runs/38462767864). Subsequent report-only commits do not change the verified application/test code. |
| Release branch API 36 | Both full required jobs also **passed** on audited release `3c712f00`, [38462767538](https://github.com/michaelhmiv/PitTech/actions/runs/38462767538): normal [115521910160](https://github.com/michaelhmiv/PitTech/actions/runs/38462767538/job/115521910160), large [115521910207](https://github.com/michaelhmiv/PitTech/actions/runs/38462767538/job/115521910207). API 37 retains the system-crash advisory failure. |
| Audit regression tests | **14 passed** locally: read-only policy, release-note validation, source/dependency/compiled isolation, legacy endpoint detection and developer-vs-production cleartext. `bash -n` and `git diff --check` also pass. |
| Native verified baseline | `e653f0fb13689b281978bb9a81f5f30b2b1b070d`: **30 unit tests (11 telemetry) + 2 UI tests passed**, Debug/unsigned public Simulator builds and binary/notices audit passed, iPhone/iPad screenshot steps passed; [38436937104](https://github.com/michaelhmiv/PitTech/actions/runs/38436937104). |
| Native subsequent regression | `c5ee8484cbc405be6a7521675df472aa51bb1652`: 30/31 unit tests and 2/2 UI passed, with the new sensor-isolation test failing on credential save in the unsigned Simulator. Local ad-hoc Simulator identity/entitlement and a real Keychain replacement/removal test were added; no distribution certificate is used. |
| Current native rerun | `a0507915a8b512a3e6c65ab848530e22679cb888`: **complete Simulator workflow passed**, [38453820728](https://github.com/michaelhmiv/PitTech/actions/runs/38453820728): Debug build, local ad-hoc signing, unsigned public build/binary/notices audit, native compatibility/UI tests, portable recipe fixture and iPhone/iPad screenshots. The passing test target includes 13 native telemetry cases, including real Simulator Keychain save/replace/removal and missing-sensor isolation, plus three UI flows. Beta connection/HA screenshots were visually reviewed; Android Beta and HA screenshots were also reviewed from the passing large-font artifact. The App Store Connect upload job was **skipped**. |
| Physical accounts/hardware | **Not performed.** No supplied vendor account, confirmed Flame Boss TLS endpoint, accessible real HA installation or supported thermometer hardware was available. Emulator fixtures do not establish these results. |

Earlier actual failures were repaired on their owning branches: missing Room watermark,
HA URL exception mismatch, manifest namespace, AGP 9 Kotlin internal source registration,
MQTT disconnect/concurrent socket ownership, live/history timestamp identity, notification
test permission, disabled-button semantics, native source/Keychain test setup and contradictory connected-cook summary counts. Tests were
retained, and unsupported history was not fabricated to make assertions pass.

## Public binary, notices and release gates

Thirty-four legacy proprietary transport/provisioning/diagnostic files were moved out of
common Android sources into the internal Debug/Dev source set. Release uses a non-network
compatibility monitor and preserves historical models. The audit examines public source
sets, compiled DEX, original legacy hosts and resolved dependency names; hiding UI alone is
not accepted. Public telemetry enablement remains off.

Native Release has no connected-device UI/hooks or background polling entitlement. Its
unsigned Simulator binary has its own legacy-string and bundled-notice audit. The official
SDKs contain capabilities such as configuration/DFU internally, but PitTech's new adapters
do not invoke equipment-control or firmware APIs. MQTT transmits only connection,
subscription, acknowledgement and keepalive frames; it has no PUBLISH/control method.

Full Combustion MIT notices are included. Native notices also retain NordicDFU BSD and
Swift Collections Apache/runtime-exception terms. Source licenses and service permissions
are separate. The dated [release evidence](APP_REVIEW_CONNECTION_EVIDENCE.md) records the
official manufacturer documentation and current terms; API publication alone is not treated
as unrestricted commercial permission. The privacy-site changes are a branch draft and
have not been deployed to Railway.

Before public enablement, validate the new Combustion Android capability work on hardware; obtain
the secure Flame Boss broker/port/ACL evidence; run real HA/OAuth/Recorder and cloud-account
flows; compare readings/units against supported hardware; measure Bluetooth/background
battery behavior; validate device lock and credential invalidation on physical devices;
retain applicable service/branding permission evidence; and inspect the intended public
store binary and reviewer demonstration. No App Review approval, service partnership,
manufacturer endorsement or public-release readiness is claimed.
