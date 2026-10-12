# PitTech universal temperature monitoring: engineering roadmap

**Status:** all five planned connectors are approved for engineering and testing. None is implemented by this roadmap; PR 0 covers release-readiness foundations only.

## Final product decision

Use **two complementary, optional paths**:

1. **Direct manufacturer Beta connections** using the published SDK/API routes. Combustion, FireBoard, Flame Boss and MEATER are all approved for development and testing with user-owned hardware/accounts. Review applicable service terms and evidence before enabling each in a public build; this is not a blocker to implementing or testing the adapters.
2. **Home Assistant Beta** for users who already operate their own Home Assistant installations. It is a full-featured, brand-neutral bridge for user-selected temperature sensors exposed by their HA instance. It is not a universal replacement for direct connections and does not require users of direct integrations to install HA.

All five planned integrations present the same device onboarding, read-only label, normalized channels, monitoring state and cook-history behavior. PitTech does not bundle or host third-party Home Assistant integrations or a Home Assistant server.

**Local-first data:** users authorize their own manufacturer accounts/devices or self-hosted Home Assistant instances. PitTech connects directly from the phone, securely stores relevant access credentials on-device and locally records selected temperatures in the user's private cook history. There is no PitTech-hosted telemetry collector or account proxy, and records are not automatically uploaded to a PitTech server. Saying no data is logged at all would be inaccurate: on-device cook logging is the product's core function.

**Permission distinction:** local-only storage, opt-in access and read-only monitoring materially reduce data handling, but do not override applicable third-party terms. Development and testing are approved for all five; public shipping requires a source-backed terms/API evidence check, not necessarily a signed partnership.

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

Proceed with all four direct connector implementations and tests. For public release, document the publisher's official API/SDK, applicable then-current terms, user authorization path, read-only operations, model compatibility, and rate limits. Written approval is not categorically required if the published license/terms already grant the necessary rights. A material restriction must be resolved before shipping that particular adapter; do not imply historic or superseded documentation proves current permission.

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

All five connector PRs (2–6) are approved to begin after PR 1 stabilizes. They may be developed in parallel against the shared contract, but they ship independently after device tests, source-specific release evidence, security review and platform validation. The iOS implementation must be independently verified; current iOS does not yet expose a Devices integration.

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
6. The official source API/SDK, applicable terms, limits and supported hardware are documented for each public connector without misrepresenting authorization.
7. No PitTech-hosted credential proxy, cloud telemetry collector or automatic temperature upload is introduced.
8. The exact iOS or Android binary is tested separately and reviewed with accurate app-store disclosures.
