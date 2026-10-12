# PitTech universal temperature monitoring: engineering roadmap

**Status:** planning specification. No new manufacturer integration is implemented by this document. PR 0 is limited to release readiness, documentation and disabling the old Wi-Fi configuration path.

## Final product decision

Use **two complementary, optional paths**:

1. **Direct manufacturer Beta connections** when a manufacturer offers a documented interface and the terms/authorization permit PitTech to distribute a commercial third-party client. Priority candidates: Combustion, FireBoard, Flame Boss, and MEATER. These are **not all cleared** today.
2. **Home Assistant Beta** for users who already operate their own Home Assistant installations. It is a full-featured, brand-neutral bridge for user-selected temperature sensors exposed by their HA instance. It is not a universal replacement for direct connections and does not require users of direct integrations to install HA.

All five planned integrations present the same device onboarding, read-only label, normalized channels, monitoring state and cook-history behavior. PitTech does not bundle or host third-party Home Assistant integrations or a Home Assistant server.

**GrillirG decision:** do not develop or publish a direct Prime Polaris/GrillirG integration. The existing experimental adapter does not become the sixth planned connector. Preserve past GrillirG-derived cooking history and isolate legacy proprietary integration code from public release artifacts. A user may independently connect GrillirG to their own Home Assistant installation, and PitTech may read the selected resulting temperature sensors through the standard Home Assistant API.

## Source-independent recording architecture (PR 1)

Replace the one-connection-per-cook assumption without disrupting local-first manual logging. Separate the following identities:

- **Connection:** an authenticated manufacturer account/device link or Home Assistant instance.
- **Source device:** the specific physical grill or thermometer, or a logical HA device.
- **Channel:** chamber temperature, meat-probe temperature, ambient, observed setpoint, battery, status, etc.
- **Cook attachment:** the channels selected for a particular cook and their assignment periods to dishes.

Normalized samples preserve source/device/channel IDs, received time, trustworthy observed time, source unit, canonical temperature, quality and availability state, and source-specific duplicate identity. Retain original timestamps during historical import. Do not manufacture missing measurements, backdate a current snapshot, silently fill outages, or create duplicate cook events.

Support multiple sources and probes in one cook. Preserve previous cooking records, note/photo associations, probe reassignment history, recordings and ZIP export/import compatibility. Migration tests must cover old data without requiring the historical manufacturer adapter to be present.

Define a **read-only** connector interface with capabilities for authentication, discovery, observation subscriptions, bounded snapshots, optional documented backfill and disconnect. No arbitrary HTTP/RPC/MQTT commands or controller mutation methods. Each source defines rates, supported metrics, history availability and background needs.

## Direct manufacturer adapters (PRs 2, 4, 5, 6)

| PR | Integration | Transport | Intended support | Public-release authorization gate |
|---|---|---|---|---|
| **2** | Combustion Inc. | Official MIT-licensed Kotlin BLE SDK | Live multi-sensor temperatures; verified onboard history recovery; reconnection | MIT notices, tested physical hardware/firmware, Android BLE/background policy |
| **4** | FireBoard | Official REST API | User-account device/channel discovery, temperatures, documented sessions/history | Review current terms for commercial application use and comply with rates/auth |
| **5** | Flame Boss | Manufacturer-documented MQTT | Subscription to permitted real-time temperature topics; stable probe identities | Verify broker/ACL behavior and third-party commercial distribution terms |
| **6** | MEATER | Public cloud REST | Current cloud-visible internal and ambient probe values | Review public beta API terms, commercial-use scope and cloud availability requirements |

An officially published API is only a **technical** prerequisite. Ship each adapter individually after permissions/terms and model-specific validation are complete. If rights are uncertain, keep the adapter out of production until resolved. Do not present any of these as automatically approved.

## Home Assistant Beta: full-featured universal bridge (PR 3)

### Customer experience

- **Setup:** "Connect Home Assistant (Beta)" clearly states an existing user-operated HA server is required; the HA mobile companion app by itself cannot host integrations. No PitTech-hosted HA subscription or account required.
- **Discovery:** allow user-approved local HA discovery where supported and a manually entered local/remote HTTPS URL. Support Home Assistant's documented authorization flow and safe credential storage. Provide readable failures for expired credentials, bad certificates, inaccessible local-only addresses and unreachable servers.
- **Devices and entities:** discover temperature entities and organize by HA device, friendly name, entity domain/device class and units, without relying on vendor-specific names. Offer a searchable list and show the exact source/role before attaching.
- **Mappings:** remember which sensor is a pit chamber, which is a food probe, etc. Allow changing assignments without rewriting earlier observations. Support multiple HA devices and other direct sources in the same cook.
- **Other readings:** permit status, observed setpoints, battery and ETA as display-only metadata when users explicitly select such supported entities. Never translate an observed setpoint into a command.
- **Live measurements:** use documented HA WebSocket state events. Fetch a REST snapshot at connection/start/reconnect so the screen has initial readings. Show freshness, last update, HA availability and source identity; unchanged values do not necessarily mean a disconnected sensor.
- **Historical synchronization:** use HA Recorder/history endpoints where available. Track per-entity sync watermarks, paginate bounded periods, deduplicate on entity and observation time, preserve HA state-change semantics, distinguish trustworthy observation time from receipt time, and flag history gaps. HA may record state changes rather than fixed-interval samples; history retention depends on the user's configuration.
- **Reliability:** reconnect with backoff; handle local versus remote access, app backgrounding and Android foreground-service policy. The self-hosted HA recorder can retain updates while the phone is offline if HA and the underlying integration continue running.
- **Security:** request only the scopes/credentials HA actually supports; never assert granular read-only user permissions without verifying their behavior. Implement PitTech allowlists for GET/history/state subscriptions and never invoke Home Assistant `call_service` or device-control APIs. Store URLs and tokens securely, redact logs, and avoid arbitrary URLs/private-network requests outside the authorized user-configured HA endpoint.
- **Onboarding clarity:** a simple Beta explanation, "requires your own Home Assistant," vendor-neutral device selection, and a small advanced section for entity IDs, local network troubleshooting and Recorder configuration.
- **Testing:** test against a simulated HA REST/WebSocket server, a real HA instance, a standard built-in thermometer integration and a community sensor integration. Validate unavailable/unknown states, unit changes, restored entity IDs, Recorder absence, DST/timezones, reconnects, duplicates, and historical backfill across prolonged phone disconnection.

### Home Assistant integration responsibility

PitTech distributes and maintains **its own HA client only**. Users set up HA and any upstream plugins themselves. An existing community integration is neither bundled, rehosted, maintained nor presented as an officially endorsed PitTech provider. Publishing a link to a third-party integration does not establish manufacturer authorization; public help should avoid promising compatibility with unsupported hardware.

## Read-only rule for every connector

No ignition, shutdown, setpoint change, smoke/fan/auger adjustment, Wi-Fi provisioning, reset, firmware update or generic arbitrary controller commands. Outbound packets are permitted only for authentication, subscriptions and observation. Even if HA or a vendor SDK exposes write capabilities, PitTech does not.

Connection state never blocks manual cook completion, logging or export.

## Remaining implementation sequence

| PR | Deliverables |
|---|---|
| **0** | Public README, Apache-2.0 license/branding reservation, changelog and real release notes, privacy alignment, disable legacy controller Wi-Fi provisioning; this roadmap |
| **1** | Neutral telemetry/recording data model, multi-source schema migration, existing history preservation, compile-time read-only connector API, source registry |
| **2** | Combustion direct BLE Beta adapter |
| **3** | Robust Home Assistant Beta connector: onboarding, discovery, mapping, WebSocket, REST snapshot, Recorder history/backfill |
| **4** | FireBoard direct REST Beta adapter, terms-gated |
| **5** | Flame Boss direct MQTT Beta adapter, terms-gated |
| **6** | MEATER direct cloud Beta adapter, terms-gated |
| **7** | Shared lifecycle/reconnection/rate budgets, background capture, bounded offline history synchronization and power behavior |
| **8** | Hardware/HA compatibility testing, public release packaging isolation, privacy/security review, per-adapter authorization gates, Beta UX and reviewed changelog |

PRs 2–6 may be worked on separately once PR 1 is stable. Order release according to readiness; all five need not ship simultaneously.

## Explicit exclusions

- No direct GrillirG/Prime Polaris public connector or port of its proprietary cloud integration to the new adapter framework.
- No bundled, managed, or cloud-hosted Home Assistant instance or third-party HA plugin marketplace.
- No reliance on Home Assistant as a requirement for manual logging or for direct manufacturer connections.
- No claim that a user-installed HA integration removes the upstream manufacturer's access terms.
- No changing equipment state through any protocol.
- No iOS parity claims until relevant functionality is implemented and tested separately.

## Acceptance gates

1. The direct manufacturer connectors and HA use the same Beta labeling, pairing state, normalized samples, cook charts and reminders.
2. Native and HA sources may coexist in one cook; data never crosses accounts or cooks.
3. A new release retains legacy GrillirG cook data even though its direct adapter is absent from the public build.
4. CI tests block any mutation operation entering public connectors and validate all source decoders, rate limits, retries, stale states and migrations.
5. Public artifacts exclude legacy proprietary client/provisioning code at the binary/source-set level; a hidden screen or flag is not sufficient.
6. Manufacturer permissions, source limitations and supported hardware are documented for each public connection.
