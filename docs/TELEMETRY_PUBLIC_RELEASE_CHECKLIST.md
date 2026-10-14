# Telemetry public release validation — 2026-10-13

The owner authorized enabling all implemented connections in store builds on
2026-10-13. Android Release and iOS Release now expose the five read-only Beta
providers, collection and probe reminders. Automated compilation and fixtures do
not establish physical device compatibility or a manufacturer's endorsement.
The validation items below remain recorded limitations, not feature switches.

| Source | Implemented Android Beta | Remaining validation or capability gap |
|---|---|---|
| Combustion | Official SDK v3.5.4, permissions/saved serial, raw/instant/derived measurements, MeatNet, bounded SDK service leases and persisted sequence-anchored logs | Automated checks passed as recorded in the engineering report; physical probe/repeater, timing and battery tests remain. Sessions above 20,000 records cannot be transferred through the bounded public API path. |
| Home Assistant | Per-server configuration, OAuth/refresh/revoke or advanced token, REST discovery/snapshot/history, multiplexed WebSocket, durable bounded recovery | Real user-operated local/remote installation; Recorder enabled; authorization callback and account revocation under actual server configuration. |
| FireBoard | Encrypted login/token, device/channel discovery, shared account budget and 429 cooldown, explicit session chart history with unique channel labels | Actual account/model schemas, C/F readings and session mapping. Duplicate labels cannot be safely mapped and are omitted. |
| Flame Boss | Manufacturer login, authenticated hostname-verified TLS MQTT, subscription-only public cook topic, reconnect and bounded snapshot | Manufacturer-confirmed TLS host/port and topic ACL; real account/device. No guessed default broker; private topics and historical data are unsupported. |
| MEATER | Encrypted login/token, shared account polling and failed-request budget, multi-device internal/ambient/observed target samples | Actual account/device and Bluetooth/cloud bridge. The published API supplies no history. |

The unified Android Devices screen supports food/chamber/ambient/other roles, simultaneous
sources, explicit monitoring, on-log-only reads, provider-constrained sampling and opt-in
foreground collection. Room schema remains version 10; telemetry uses existing record tables.
Source pauses, assignment history, removal and cook completion preserve archived data.

## Packaging

Legacy proprietary transports, provisioning and diagnostics reside under `app/src/internal`.
Only debug and Dev Kotlin source sets include that directory. Public release uses the
non-network compatibility monitor in `app/src/release`; historical models remain readable.
The gate checks public source sets, compiled APK/AAB DEX and the resolved release dependency
graph. Production cleartext is disabled. Hiding a route is not accepted as isolation.
New telemetry is enabled in public release. The source, compiled-artifact and
dependency isolation checks continue to reject legacy controller clients.

Full Combustion MIT text ships in Android assets. Native iOS notices include Combustion MIT,
Swift Collections and NordicDFU terms. Inspect the resolved dependency versions before release.
NordicDFU is an unused transitive SDK capability; PitTech exposes no firmware operation.

## Platform limits

Android foreground collection displays an ongoing notification and pause action, uses a
bounded wake lease, and stops when the cook is paused/finished or monitoring is disabled.
Android can still stop the process. Supported histories recover later; unsupported providers
leave a gap. Force-stop blocks Android restarts until the user opens the app. Battery effects
and vendor-specific background behavior need physical testing.

iOS counterparts are reviewed separately in PR #87. The iOS public configuration
includes Devices, collection lifecycle hooks and connected-probe reminders.
Native Beta monitoring is foreground-only; no entitlement is used
to imply continuous cloud polling while suspended. Home Assistant requires HTTPS on iOS.
The pinned iOS SDK lacks a bounded log-request API and scanner-stop control, so native BLE
history/repeater mode remains unavailable. The Simulator cannot verify Bluetooth, MQTT vendor
permissions or background hardware behavior.

## Release evidence

See [APP_REVIEW_CONNECTION_EVIDENCE.md](APP_REVIEW_CONNECTION_EVIDENCE.md) for dated terms,
privacy boundaries and the actual iOS release distinction. A public API and SDK license do not
by themselves establish unrestricted service or trademark permission. Ordinary development
continues with these compatibility and service-evidence items recorded.

For each provider, retain applicable service evidence, verify on physical
hardware and accounts, review the intended store binary and privacy disclosures,
and record the exact build SHA, dependency graph and tests. These checks do not
disable the implemented connections. No build is submitted or distributed by
merging these PRs. Firebase, Play and App Store Connect use manual release actions.
