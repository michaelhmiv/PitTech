# Unified, observation-only telemetry — implementation roadmap

**Status:** engineering specification only. PR 0 supplies public-facing release foundations; this roadmap is **not** an implementation of the new integrations.

## Product rules

1. Every connection shown to users, including GrillirG, is visibly **Beta** until explicitly graduated per device model and firmware.
2. All sources are **read-only with respect to equipment state**. Authentication, approved telemetry subscriptions, and status requests may transmit packets, but no adapter may adjust temperature targets, ignition, fan/auger, firmware, Wi-Fi configuration, or other controller settings.
3. PitTech remains usable offline for local cook logging. A disconnected source never prevents finishing a cook or saving notes/photos.
4. Historical recordings, device identities, probe assignments, and existing GrillirG encrypted sessions must survive upgrades without requiring users to recreate cooks.
5. One cook may attach multiple observation sources and multiple channels without collisions. Preserve units, source identity, device-reported and receipt timestamps, validity/freshness, explicit gaps, and source-specific limits.
6. No source launches publicly until its hardware, compatibility, security, privacy, and applicable service authorization reviews pass. A Beta label or absence of a vendor complaint does not waive a contractual or store policy concern.

## Standard contract

Each adapter exposes discovery, user-authorized authentication, stream and/or snapshot observations, optional verified historical backfill, disconnect, connection health, and a fixed capability descriptor. Adapters **do not expose arbitrary commands or mutate-controller methods**. The host controls permission, rate budgets, secrets, battery lifecycle, and local persistence.

Normalized samples include a stable source ID, device ID, channel ID, measurement type, canonical temperature and original unit, observed-at time if trustworthy, received-at time, validity, and delivery/sequence identity. Manufacturer parsers own special sentinel values and epoch conversions. No nonexistent history should be fabricated.

## Six integration targets

| Connector | Transport | Authentication | Initial capabilities | Existing state | Public gate |
|---|---|---|---|---|---|
| **GrillirG (Prime Polaris)** | HTTPS JSON requests | Existing email-code sign-in and encrypted session | Grill/device selection; chamber, target observation and probes; timed/on-log recording | **Already implemented experimentally in Dev/Debug; preserve functionality** | Validate service terms, authorized cloud access, physical hardware, read-only requests, and app-store review |
| **Combustion Inc.** | Official Bluetooth LE Android SDK | Nearby-device authorization | Live multi-sensor probe measurements; SDK-supported historical download | New adapter | License attribution, hardware and Android background tests |
| **Home Assistant** | REST + WebSocket | OAuth on user-owned instance, optional scoped credential | User-selected temperature entities; streaming and explicit snapshot | New adapter | OAuth callback setup, HTTPS/local network policy, entity filtering, platform tests |
| **FireBoard** | Official cloud REST API | User-account token | Device/channel discovery, live readings, documented session backfill | New adapter | Current API rate budget, hardware validation, commercial-usage terms |
| **Flame Boss** | Documented cloud MQTT | Vendor authentication/token | Temperature topic subscription; smoker/pit probe readings | New adapter | Broker and topic/ACL validity, hardware tests, commercial-usage terms |
| **MEATER** | Public cloud REST API | User account JWT | Cloud-connected probes, internal and ambient temperature | New adapter | Service terms, cloud presence dependency, request limits, hardware/model tests |

An observed target/setpoint value is **display-only**; there is never a path to send an updated target to the equipment.

### GrillirG migration contract

The experimental provider currently uses `PrimePolarisApi`, `PrimePolarisMonitor`, `ConnectedGrillMonitor`, `CookTelemetryPolicy`, and `CookRecordingRepository`. The universal implementation must wrap or migrate these rather than erase them:

- Preserve current email-code authentication and encrypted token storage; never log credentials or request an account password.
- Map `furnaceTempMeasured`, `furnaceTempSetting`, and `probeP*Measured` to the normalized observational channel model. Do not mistake status/target metadata for an actual temperature reading.
- Keep physical probe channels stable across reconnects; retain old controller IDs only in encrypted storage and nonreversible local binding keys.
- Maintain the existing 15/30/60/120/300-second options and on-log sampling, subject to bounded retries, read-only operations and appropriate vendor limits.
- Test a real active cook after upgrade, with saved session restoration, probe/dish assignments, chart history, connection gaps, background service, and export/import compatibility.
- Remove all setup/configuration actions from the public connection path. PR 0 already hides and disables the obsolete GrillirG Wi-Fi provisioning action.
- Keep historical temperature records readable even if this connector is later disabled or removed from distribution.

### Vendor permission and disclosure

GrillirG's [published user agreement](https://www.prime-polaris.com/grillirg-terms.html) includes a reverse-engineering prohibition. It is not a public developer API license. PitTech may continue developing and validating its existing read-only connector, but a decision to distribute it needs an informed legal/terms and store review **before release**, not only after a manufacturer contacts the developer. If access becomes disallowed, disable distribution and preserve locally stored data. Nothing in this roadmap recommends bypassing access restrictions, evading review, or misrepresenting manufacturer endorsement.

## Updated PR order

- **PR 0:** public README, Apache-2.0 licensing, reserved branding, privacy consistency, change/release notes, safety cleanup.
- **PR 1:** universal telemetry contracts, source registry, normalized readings, multi-source Room migration, feature flags and adapter tests.
- **PR 2:** Combustion BLE SDK adapter.
- **PR 3:** Home Assistant REST/WebSocket adapter.
- **PR 4:** FireBoard REST adapter.
- **PR 5:** Flame Boss MQTT adapter.
- **PR 6:** MEATER REST adapter.
- **PR 7:** **GrillirG legacy-to-universal adapter**, encrypted session migration, active-cook continuity and verified read-only behavior.
- **PR 8:** per-source Android background lifecycle, power/rate limits, reconnections, offline gaps and backfill.
- **PR 9:** production artifact isolation, permission and privacy review, source/model hardware matrix, Android/instrumentation tests, beta UX, and individually approved release gates.

PRs 2–7 may be built independently after PR 1, but avoid parallel migration edits without a shared contract. The public release may include a subset of individually cleared connectors; excluding a connector from public builds must not erase past cook history or regress development support.

## Shared acceptance criteria

- All six use one registration and Beta-label UI path.
- Compile-time read-only API, request allowlists, no arbitrary command/publish/write capability for controller state.
- Provider mocks and adversarial tests for token leaks, schema drift, invalid units, stale data, over-polling, duplicate events, interrupted cooks, incorrect source associations and update recovery.
- Tests and data migrations pass before merging; public release uses a reviewed changelog and provider-specific compatibility disclosures.
- No claim of full authorization, model verification, or iOS parity without evidence.
