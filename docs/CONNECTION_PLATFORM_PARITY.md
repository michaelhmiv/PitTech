# Connection platform parity

The owner authorized all implemented connections in store builds on 2026-10-13.
Android Release/Google Play and iOS Release/TestFlight/App Store expose the same
five optional, read-only Beta providers. Availability does not depend on a debug
build. Debug-only fixtures and legacy controller tools remain isolated.

| Capability | Android | iOS | Decision |
|---|---|---|---|
| Home Assistant, Combustion, FireBoard, Flame Boss and MEATER setup | Enabled in every build | Enabled in every build | Parity: each implemented provider is available in store builds. |
| Source/food mappings, live readings, local recordings and archives | Available | Available | Parity; no archive/schema migration for store enablement. |
| On-log sampling, intervals, source removal and probe reminders | Available | Available | Parity; removing iOS Debug guards exposes probe reminders and all runtime lifecycle hooks. |
| Android opt-in background service / iOS app suspension | Private foreground service with ongoing notification and pause action | Collection while the app is foreground; supported history recovers on return | Platform-appropriate: iOS does not claim continuous cloud polling while suspended. |
| Combustion live/raw/derived temperatures | Official Android SDK 3.5.4 | Official iOS SDK 1.0.0 | Parity for implemented live channels. |
| Combustion bounded probe-log recovery and repeater mode | Bounded sequence-anchored recovery and SDK MeatNet route | Unavailable | Port: the pinned native SDK lacks bounded log-request/scanner-stop controls. This is a missing capability, not a Release feature switch; store enablement does not fabricate history. |
| Manufacturer-confirmed Flame Boss TLS broker configuration | User configuration required | User configuration required | Parity; both expose setup and use authenticated TLS. No default endpoint is invented. |
| Home Assistant public networking | HTTPS | HTTPS | Parity. Development-only Android local HTTP is not part of store availability. |
| Legacy direct GrillirG/Pit Boss/Traeger clients and controller diagnostics | Internal Debug/Dev only | Not exposed | Drop from public clients, consistent with the owner's earlier integration scope and the read-only equipment policy. |

Physical device/account validation and provider compatibility remain recorded in
[the release checklist](TELEMETRY_PUBLIC_RELEASE_CHECKLIST.md). These records do
not disable the implemented public connections. Store uploads and distribution
remain separate manual release actions.
