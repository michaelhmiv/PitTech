# PitTech telemetry engineering report — 2026-10-13

All changes remain in draft pull requests. No PR was merged, no direct update to `main`
was made, and no Firebase, Play, TestFlight or App Store distribution was initiated.
PR #75 is a verification branch and must never be merged.

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
| [#73](https://github.com/michaelhmiv/PitTech/pull/73) | Official SDK v3.5.4, Bluetooth permission onboarding, saved probe identity, passive raw/instant Celsius measurements, bounded/duty-cycled scans, cancellation and unavailable-radio handling | **Partially implemented.** The passive Android adapter does not expose derived core/surface metadata, MeatNet repeaters or anchored probe-log recovery. Physical compatibility/battery tests also remain open. |
| [#74](https://github.com/michaelhmiv/PitTech/pull/74) | Unified Devices Beta UI, source/food mappings, live channel health, on-log sampling, independent observation jobs, opt-in foreground recorder, bounded retries/history, lifecycle/removal and persistent restart state | Implemented runtime with verification in progress below. One missing on-log sensor is isolated from other sensors on the same connection. Physical background/battery/vendor restrictions remain open. |
| [#75](https://github.com/michaelhmiv/PitTech/pull/75) | Combined Android compile, JVM, required API 36 font scales, process-death, upgrade and UI matrix | Verification only; never merge. A passing build is not a real-provider end-to-end demonstration. |
| [#76](https://github.com/michaelhmiv/PitTech/pull/76) | Legacy client source-set isolation, source/compiled-artifact/dependency gates, notices, privacy draft and dated release evidence | Packaging implementation verified on the audited code snapshot below. Public Beta enablement remains off while provider/hardware/terms gates are open. |
| [#87](https://github.com/michaelhmiv/PitTech/pull/87) | Native five-provider Beta adapters, HA OAuth/WS, official Combustion SDK 1.0.0 derived readings, local recording/mappings/archives, on-log/source isolation and foreground probe reminders | Native software is separate from Android. The complete Simulator workflow passed as recorded below. iOS collection is foreground-only; anchored BLE history is not implemented; repeater and physical background behavior are unverified. |

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

## Test evidence

Results below distinguish completed runs from current regression reruns. The final snapshot
will be updated after the required jobs finish; a partial failed workflow is not a full pass. GitHub artifacts contain unit XML, emulator
logs, screenshots, process/service evidence and native xcresult diagnostics for 14 days.

| Check | Verified result and exact evidence |
|---|---|
| Original Android compiler/AAB blocker | Fixed. Debug, instrumented, Release and Dev compilation succeeded after the recorder/DAO reconciliation. |
| Android JVM tests | **211 passed, 0 failures/errors**, from artifact XML on combined `9b9079544b7c83c245db18d2a3894a2b777f1c80`, [38453663012](https://github.com/michaelhmiv/PitTech/actions/runs/38453663012). Includes 54 telemetry/provider policy and transport tests. The current change is only the shell result-count/screenshot harness. |
| Current combined unsigned AAB | Current combined `67ad1a03a64274487c182fb9fe6887ea8628c813` rerun: [38461411065](https://github.com/michaelhmiv/PitTech/actions/runs/38461411065), pending. Previous synchronized public-release AAB passed as recorded below. Compilation only; no Play upload. |
| API 36 fonts 1.15 / 1.5 | Both required jobs on `9b907954` passed the **20-test telemetry group**, staged process-death assertions, five legacy cloud tests, eleven multi-provider tests and eighteen connected-cook tests. The workflow then rejected the successful eighteen-test summary because its shell checked both 18 and 19. That contradictory guard was repaired without removing a test. Full matrix rerunning on `67ad1a03`, [38461410874](https://github.com/michaelhmiv/PitTech/actions/runs/38461410874). |
| Telemetry instrumented coverage | **20 passed at both API 36 font scales**: Room recording/archives/roles (8), encrypted token lifecycle (3), bounded/interrupted/saturated recovery (3), runtime restore/removal/source isolation (4), foreground-service lifecycle (1), unified onboarding UI (1). Two additional staged tests also passed; shell artifacts show changed process ID, a committed post-restart reading and foreground service with no resumed PitTech activity. |
| Process-death test repair | Captures a changed PID, a new committed Room watermark, a live foreground service and no resumed PitTech activity before the validation runner attaches. The debug fixture publishes only a committed Room watermark, after emission/throttling; a received but uncommitted frame cannot satisfy the shell. The runner itself replaces the target process, so its assertions inspect persisted post-restart Room data. Setup now cold-starts after instrumentation exits to avoid launching the process that the runner is still killing. No service was exported and no shell service bypass was added. |
| API 37 advisory | [Job 115490706180](https://github.com/michaelhmiv/PitTech/actions/runs/38453663012/job/115490706180) passed both staged restart assertions and eighteen telemetry cases, then the system crashed during foreground-service lifecycle case 19. Saved logcat identifies `mapper.ranchu` asserting `!rcEnc->featureInfo()->hasReadColorBufferDma`, followed by `system_server` SIGABRT in `TaskSnapshotPer`. Earlier preview runs also crashed graphics/RegionSampling. This is retained as an advisory failure, not an app-test pass. |
| Public Android isolation | Source, actual unsigned APK/AAB DEX and resolved dependencies **passed** on synchronized release `4cace73f053daf711be7228d83a835c49f67cc48`, [38453854339](https://github.com/michaelhmiv/PitTech/actions/runs/38453854339). Its unsigned AAB also **passed**, [38453854384](https://github.com/michaelhmiv/PitTech/actions/runs/38453854384). |
| Audit regression tests | **14 passed** locally: read-only policy, release-note validation, source/dependency/compiled isolation, legacy endpoint detection and developer-vs-production cleartext. `bash -n` and `git diff --check` also pass. |
| Native verified baseline | `e653f0fb13689b281978bb9a81f5f30b2b1b070d`: **30 unit tests (11 telemetry) + 2 UI tests passed**, Debug/unsigned public Simulator builds and binary/notices audit passed, iPhone/iPad screenshot steps passed; [38436937104](https://github.com/michaelhmiv/PitTech/actions/runs/38436937104). |
| Native subsequent regression | `c5ee8484cbc405be6a7521675df472aa51bb1652`: 30/31 unit tests and 2/2 UI passed, with the new sensor-isolation test failing on credential save in the unsigned Simulator. Local ad-hoc Simulator identity/entitlement and a real Keychain replacement/removal test were added; no distribution certificate is used. |
| Current native rerun | `a0507915a8b512a3e6c65ab848530e22679cb888`: **complete Simulator workflow passed**, [38453820728](https://github.com/michaelhmiv/PitTech/actions/runs/38453820728): Debug build, local ad-hoc signing, unsigned public build/binary/notices audit, native compatibility/UI tests, portable recipe fixture and iPhone/iPad screenshots. The passing test target includes 13 native telemetry cases, including real Simulator Keychain save/replace/removal and missing-sensor isolation, plus three UI flows. Beta connection/HA screenshots were visually reviewed. The App Store Connect upload job was **skipped**. |
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

Before public enablement, complete the remaining Combustion Android capability work; obtain
the secure Flame Boss broker/port/ACL evidence; run real HA/OAuth/Recorder and cloud-account
flows; compare readings/units against supported hardware; measure Bluetooth/background
battery behavior; validate device lock and credential invalidation on physical devices;
retain applicable service/branding permission evidence; and inspect the intended public
store binary and reviewer demonstration. No App Review approval, service partnership,
manufacturer endorsement or public-release readiness is claimed.
