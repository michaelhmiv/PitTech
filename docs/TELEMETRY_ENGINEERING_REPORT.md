# PitTech telemetry engineering report — 2026-10-13

The user authorized completion and merging on 2026-10-13. The feature merge set is
#68–#74, #76 and #87; the linked PRs retain the actual GitHub merge records. PR #75 remains
unmerged verification, with its test fixes owned by #74. Public telemetry enablement is off,
and no Firebase, Play, TestFlight or App Store distribution was initiated. Required Android
API 36, native iOS and public packaging results are recorded below with exact code snapshots.

The original `latestObservation` compilation failure is repaired in the foundation and
shared recorder. The five Android Beta connections now share persistent setup, source
selection, food/channel mapping, cook recording, source health, graphs and probe reminders.
Native iOS counterparts are separately documented in #87. Packaging isolation has
passed an actual APK/AAB/dependency audit. This is development evidence; no connector is
declared production-ready or endorsed by a manufacturer.

## Scope and branch reconciliation

The feature branches preserve the pre-telemetry main snapshot
`713ec8c77d50a85046c3f35a1ce95983ce531573`, including adaptive meals/recipes (#67) and
the iOS asset-catalog fix (#86). The Room schema remains version 10. No destructive
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
| [#73](https://github.com/michaelhmiv/PitTech/pull/73) | Official SDK v3.5.4, Bluetooth onboarding, saved identity, raw/instant/derived Celsius readings, MeatNet routing, bounded radio/service leases and persisted session/sequence log anchors | Android software capability work passed the recorded JVM, emulator and artifact checks. Transfers are limited to 20,000 records and completed before recovery advances. Physical compatibility, repeater behavior and battery tests remain open. |
| [#74](https://github.com/michaelhmiv/PitTech/pull/74) | Unified Devices Beta UI, source/food mappings, live channel health, on-log sampling, independent observation jobs, opt-in foreground recorder, bounded retries/history, lifecycle/removal and persistent restart state | Implemented and verified Beta runtime. The required API 36 matrix passed at both font scales. One missing on-log sensor is isolated from other sensors on the same connection. Physical background/battery/vendor restrictions remain open. |
| [#75](https://github.com/michaelhmiv/PitTech/pull/75) | Combined Android compile, JVM, required API 36 font scales, process-death, upgrade and UI matrix | Required API 36 verification completed; never merge. API 37 retains its documented system-crash advisory result. Real providers remain unverified. |
| [#76](https://github.com/michaelhmiv/PitTech/pull/76) | Legacy client source-set isolation, source/compiled-artifact/dependency gates, notices, privacy draft and dated release evidence | Packaging implementation verified on the audited code snapshot below. Public Beta enablement remains off while provider/hardware/terms gates are open. |
| [#87](https://github.com/michaelhmiv/PitTech/pull/87) | Native five-provider Beta adapters, HA OAuth/WS, official Combustion SDK 1.0.0 raw and derived readings, local recording/mappings/archives, on-log/source isolation and foreground reminders | Native software is separate from Android. The complete Simulator workflow including the raw-sensor update passed. iOS collection remains foreground-only. The pinned SDK exposes neither bounded log-request nor scanner-stop controls; native BLE history/repeater mode stays unavailable pending that SDK capability and hardware validation. |

## Recording and connection behavior

The phone connects directly to the selected manufacturer, user-operated Home Assistant server
or Bluetooth probe. Home Assistant is optional. No manufacturer community integration is
bundled, and no PitTech account, telemetry relay or centralized credential store is required.
The existing Railway privacy site identifies the HA OAuth client; the phone intercepts the
exact callback. The existing Railway privacy and feedback services track main and may rebuild automatically
on merges. No new service, database, telemetry relay or infrastructure configuration was created.

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
the sequence-to-receipt estimate still needs measurement. Decoded observations are also capped
at 20,000 per window so the shared saturation splitter bounds memory and retains the remaining
range. Discovery does not establish fresh telemetry: the official service replays cached
advertisements. Live normal readings require a matching connected normal-status log record;
instant-read values use the official ProbeScanner's newly received, non-replayed frames.

Nine Android Combustion JVM cases replace four passive-scanner cases, one normalization case
was added, and two instrumented no-backup anchor persistence/corruption cases are included in
the complete telemetry group. The final release artifact contains 217 JVM tests with zero
failures/errors/skips, and the regular instrumented telemetry group contains 23 passing cases. Native adds two raw/derived/stale
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
The merged SDK head is `d7c7de1164942c271d195c7373e46479e5b488d7`. Its source is identical
to the SDK verified in the final release snapshot below. Native head
`fe0c175561800610f47d6704e6af43d25eeb11d8` passed its complete Simulator workflow; its
native application/test/workflow files are identical to the final release snapshot.

Runtime close now joins the cancelled root job before client/database teardown. An actual
API 37 run exposed the prior Room observer race, independently of the emulator system crash.
Foreground requests now share an atomic pending start and recheck current visibility and
service ownership on Main; repeated UI/automatic requests cannot queue duplicate starts or
replay a visible-state event after the UI is hidden. The new regression is part of the
23-case telemetry group.

The process-death setup requires a cold launch after instrumentation has exited, actual
foreground-service state and a completed Home transition. A recovery-branch run on
`1c4476e5` still encountered Android's denied sticky-service restart after the deliberate
kill ([38478523164](https://github.com/michaelhmiv/PitTech/actions/runs/38478523164)).
That failure is retained. The runner was strengthened to establish a new committed Room
reading while hidden before killing the process; a reading from the visible launch is not
sufficient. This changes test setup only, with the changed-PID, restored-reading,
foreground-service and hidden-UI assertions retained. Subsequent feature commits retain
exactly the verified Android/iOS application sources from `f7a322be`. Test setup was also
repaired after inspecting actual failures: keyboard dismissal now targets the window with
focus (including a dialog above the Activity), and the runtime fixture explicitly requests
collection on resume as CooksViewModel does, rather than relying on a brief StateFlow event
that can be conflated. State waits use the same generous bound as other emulator fixtures.
No test case or assertion was removed.

On report snapshot `3ead9587`, the strengthened real process-death setup and all 23 regular
telemetry cases passed at both API 36 fonts. Standard-font job
[115586516394](https://github.com/michaelhmiv/PitTech/actions/runs/38481992516/job/115586516394)
passed the complete suite. Large-font job
[115586516664](https://github.com/michaelhmiv/PitTech/actions/runs/38481992516/job/115586516664)
failed later when Espresso selected the unfocused Activity during recipe-dialog keyboard
dismissal; that failed full-job result is retained. Native Simulator
[38481992562](https://github.com/michaelhmiv/PitTech/actions/runs/38481992562), unsigned AAB
[38481992559](https://github.com/michaelhmiv/PitTech/actions/runs/38481992559) and public artifact
audit [38481992519](https://github.com/michaelhmiv/PitTech/actions/runs/38481992519) all passed.
The fixture-only synchronization/window corrections are on #74 and copied to #75/#76.

Native/release branches were restacked onto owning feature history to keep the unmerged #75
head out of the feature merge ancestry. Original histories remain on
`feature/telemetry-native-pre-merge-20261013` and
`feature/telemetry-release-pre-merge-20261013`. A local merge of main plus #74/#87/#76
reproduces the complete release tree; no meal/recipe or iOS asset fix is reverted.
A publication helper had temporarily attached main history to an older scoped draft tree;
those draft trees were restored before further merging. Publication now checks merged
provider/foundation paths and accepts only parents already merged into the local tree.
Local synchronization retains committed checkpoints and working/index files. Main's
completed provider merges were unaffected.

## Test evidence

Results below identify exact verified code snapshots. Earlier partial failed runs are retained
as diagnostic evidence. GitHub artifacts contain unit XML, emulator
logs, screenshots, process/service evidence and native xcresult diagnostics for 14 days.

| Check | Verified result and exact evidence |
|---|---|
| Compiler and schema | Debug, instrumented, Release and Dev builds pass; the missing persisted watermark method is repaired. Room remains v10 with no destructive migration. |
| Final Android JVM tests | **217 passed, 0 failures/errors/skips**, parsed from 48 XML reports in the API 36 font 1.5 artifact on `f7a322befd4ed5aa25efccc3b80ac1e2c8a763f1`, [38478609566](https://github.com/michaelhmiv/PitTech/actions/runs/38478609566), artifact [11856873455](https://github.com/michaelhmiv/PitTech/actions/runs/38478609566/artifacts/11856873455), SHA-256 `a0a5908598671ffcda0cd632600deb31cca7cc2279650dfb70f80e8f3b7f3c97`. Includes nine bounded/fresh SDK adapter cases. |
| Final required API 36 matrix | **Both complete required font-scale jobs passed** on `f7a322be`: [font 1.15 / 115575391823](https://github.com/michaelhmiv/PitTech/actions/runs/38478609566/job/115575391823), [font 1.5 / 115575391513](https://github.com/michaelhmiv/PitTech/actions/runs/38478609566/job/115575391513). Includes telemetry, real process death, active-cook upgrade, archives, meals/recipes, reminders, main UI, screenshots and retained internal regressions. |
| Final instrumented telemetry | **23 regular cases + two separately staged process-death cases passed** at both required font scales. Regular coverage includes Room/archives/roles (8), encrypted token lifecycle (3), persisted Combustion anchors (2), bounded recovery (3), runtime/removal/source isolation/coalesced starts (5), foreground-service lifecycle (1) and unified connection UI (1). |
| Real process-death evidence | Large-font artifact: PID **3127 → 3592**, committed Room receipt **1791931487401 → 1791931494531**, foreground service and no resumed PitTech activity, then `OK (1 test)` in persisted validation. The runner replaces its target process, so the restored receipt is established before instrumentation reattaches. A force-stop is used only to cold-start after setup; the tested death is an actual `run-as kill -9`. No exported service or shell bypass is added. |
| Integration verification | `d053f8a718e06eb72db6c533c53e61129fa2ef33` is tree-identical to recovery `1c4476e5`: **both required API 36 font jobs passed**, [38478564607](https://github.com/michaelhmiv/PitTech/actions/runs/38478564607), and unsigned AAB [38478564610](https://github.com/michaelhmiv/PitTech/actions/runs/38478564610) passed. #75 is retained unmerged. |
| Final public Android audit | Public source, actual unsigned APK/AAB DEX and resolved release dependencies **passed** on `f7a322be`, [38478609278](https://github.com/michaelhmiv/PitTech/actions/runs/38478609278). Thirty-four legacy transport/diagnostic files are confined to internal Debug/Dev source sets. |
| Final unsigned AAB | `f7a322be`: **passed**, [38478609190](https://github.com/michaelhmiv/PitTech/actions/runs/38478609190). Build only; no Play upload. |
| API 37 advisory | Final release [115575391819](https://github.com/michaelhmiv/PitTech/actions/runs/38478609566/job/115575391819) retains **INSTRUMENTATION_ABORTED: System has crashed**, after the new foreground-start regression passes. Prior retained system logs identify `mapper.ranchu` and `system_server` graphics/TaskSnapshot crashes. This is an advisory failure, not an app-test pass. |
| Final native iOS | `fe0c1755`: **complete Simulator workflow passed**, [38473221518](https://github.com/michaelhmiv/PitTech/actions/runs/38473221518). Identical native sources also passed on final release `f7a322be`, [38478609227](https://github.com/michaelhmiv/PitTech/actions/runs/38478609227). Debug and local ad-hoc Simulator builds, unsigned public binary/notices audit, compatibility tests, 15 native telemetry cases, three UI flows, portable recipe fixture and iPhone/iPad screenshot steps are included. App Store Connect upload was **skipped**. |
| Visual review | Android Devices Beta and HA UI reviewed from passing large-font artifacts. Native Beta and HA screenshots reviewed from native evidence artifact [11854225594](https://github.com/michaelhmiv/PitTech/actions/runs/38473221518/artifacts/11854225594); manifest has three UI cases/eight images with no failure associations. Root Dynamic Type screenshots do not establish physical maximum system-font or modal-font behavior. |
| Historical baseline | Combined `67ad1a03` had **211 JVM /20 regular telemetry cases** plus two staged cases and both full required API 36 fonts, [38461410874](https://github.com/michaelhmiv/PitTech/actions/runs/38461410874). Earlier native `a0507915` passed the complete Simulator workflow with 13 telemetry cases/three UI flows, [38453820728](https://github.com/michaelhmiv/PitTech/actions/runs/38453820728). These remain baseline evidence; they are not substituted for current results. |
| Local tooling | **14 audit/policy regression cases passed**: read-only policy, release-note validation, source/dependency/compiled isolation, legacy endpoint detection and production cleartext. `bash -n` and `git diff --check` pass. |
| Physical accounts/hardware | **Not performed.** No supplied vendor account, confirmed Flame Boss TLS endpoint, accessible real HA installation or supported thermometer hardware was available. Fixtures do not establish these results. |

Canceled superseded runs are diagnostic history and are not counted as passing checks. The
recovery-branch denied restart above and prior native Keychain failure are likewise retained;
local Simulator identity/entitlement and real Keychain save/replace/removal coverage repaired
the native setup. The final passing workflow uses local ad-hoc signing, with no distribution
certificate.

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
as unrestricted commercial permission. The privacy-site disclosure is included in the source merge. Existing Railway services
can rebuild main automatically; no new infrastructure is required.

Before public enablement, validate the new Combustion Android capability work on hardware; obtain
the secure Flame Boss broker/port/ACL evidence; run real HA/OAuth/Recorder and cloud-account
flows; compare readings/units against supported hardware; measure Bluetooth/background
battery behavior; validate device lock and credential invalidation on physical devices;
retain applicable service/branding permission evidence; and inspect the intended public
store binary and reviewer demonstration. No App Review approval, service partnership,
manufacturer endorsement or public-release readiness is claimed.
