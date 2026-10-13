# PitTech connection policy

All PitTech temperature connections are optional, labeled **Beta** until explicitly graduated, and **strictly observational**. A manufacturer name or documented endpoint does not establish that every device is compatible or that commercial third-party use is authorized.

## Allowed operations and prohibited operations

Approved operations: discover a user-authorized device or Home Assistant instance, authenticate to a documented interface, read or subscribe to sensor measurements, retrieve documented history, and disconnect.

**Never** expose operations to ignite, shut down, change temperature setpoints, change grill operating modes, alter fan/auger/motor settings, write Wi-Fi credentials, reset controllers, or modify firmware. Read-only does not mean no outbound packets: authenticated REST reads, MQTT/WebSocket subscriptions and Bluetooth notifications can require sending protocol messages. They must be validated, narrowly scoped and allowlisted.

Third-party Home Assistant integrations may themselves expose hardware controls. PitTech must only read selected sensors and must not call Home Assistant device-control services, even if the account would technically permit it.

## Supported integration strategies

**Direct manufacturer path:** All four planned public SDK/API connectors are approved for development and testing with a user's own device or account. Their documented interface and user authorization form the technical basis of the integration. A published endpoint does not override restrictions in the service terms; before releasing a public build, document the relevant applicable license/terms, credentials, model compatibility, rates and privacy controls. Written permission may be useful for ambiguous terms but is not categorically required.

Initial direct candidates:
- Combustion Inc.: official Android BLE SDK, MIT-licensed.
- FireBoard: documented cloud REST API; commercial usage to be clarified.
- Flame Boss: documented MQTT broker access; current terms and operations to be verified.
- MEATER: public cloud REST API; beta-service limitations and commercial terms to be verified.

**Local-only data handling:** users authorize connections to their own devices/services. PitTech stores selected temperatures and cook history on their phone rather than collecting that telemetry on PitTech servers; credentials are protected locally. No automatic cloud upload or PitTech telemetry relay is included. The absence of PitTech server logging does not mean the app does not create local cook records or that a provider's service terms cease to apply.

**User-operated Home Assistant path:** PitTech connects through Home Assistant's documented REST/WebSocket APIs to **the user's existing, self-hosted Home Assistant installation**. Users independently choose, install and maintain their Home Assistant integrations and grant PitTech access to selected temperature sensors. PitTech does not host Home Assistant installations, bundle manufacturer-specific Home Assistant integrations, distribute unofficial manufacturer cloud clients, or ask for users' manufacturer account credentials through this bridge.

A Home Assistant instance is needed, not just the mobile companion app. Community integrations may use unofficial manufacturer interfaces, and the user should consider their vendors' terms and the security risks of running third-party code. **Using Home Assistant is not a guarantee that every underlying connection is permitted or safe.** PitTech's own Home Assistant client remains observational.

## Existing experimental GrillirG implementation

The older Android code includes an experimental read-only Prime Polaris/GrillirG monitor. **No new direct GrillirG adapter will be built or shipped as a public integration.** Its existing cook records and probe assignments must remain readable after migration.

- The separate GrillirG Wi-Fi provisioning path was disabled in PR 0 because it changed equipment configuration.
- Before public telemetry release, exclude all unapproved proprietary controller client implementations and mutating/provisioning components from **production build artifacts**, not merely from navigation screens.
- Historical telemetry should remain readable independently of the presence of a connected-provider implementation.
- User-installed Home Assistant integrations can expose corresponding temperatures as ordinary sensor entities, with the caveats above. PitTech does not install or operate the upstream component.

## Standard Beta and release gates

A connection may be marked Beta only when it is included in the public artifact, has a documented compatibility/test matrix, uses observation-only operations, handles stale/missing data, protects credentials, respects rate/background limits, and has a truthful, source-backed review of the applicable API/SDK terms. Do not demand a signed partnership when the existing published terms are sufficient; do not ignore material restrictions.

Unsupported and experimental code may be retained in isolated development builds but must never be treated as approved public support solely because a product is open source.

## Safety

PitTech is a cooking journal and monitor, not a grill safety controller. Manufacturer controls and safety guidance remain authoritative. Missing measurements must be represented as stale or unavailable, not silently replaced by old values.
