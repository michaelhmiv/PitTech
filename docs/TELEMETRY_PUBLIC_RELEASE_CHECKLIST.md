# PitTech telemetry public release acceptance gates

The five integrations are under development. Passing Android or JVM tests is not proof of
manufacturer authorization, model support, hardware interoperability or App Store approval.

| Source | Implemented transport/decoder | Needed for actual public Beta |
|---|---|---|
| Combustion | Official v3.5.4 Android BLE SDK, passive scanning, raw sensor conversion | Android permission UX, verified physical probe, background recovery, probe logs |
| Home Assistant | REST sensor discovery/snapshot/history and WebSocket events | Authentication onboarding, encrypted credentials, local/remote behavior, HA end-to-end fixture |
| FireBoard | Public REST device readings, shared 30-second cache | Real account/hardware, session mapping, token handling, HTTP 429 backoff |
| Flame Boss | MQTT topic frame decoder and public login; no actual broker session yet | Verified broker host, TLS and topic ACL; tested read-only subscription |
| MEATER | Public REST devices, 30-second cache and temperature decoding | Real account, cloud bridge availability, token storage, rate handling |

Current legacy Prime Polaris, Traeger and Pit Boss direct clients are still in common Android source.
Before any new connected-device feature ships publicly, exclude those legacy implementations
and their setup/control code from the distributed release artifact. Hide-only UI is insufficient.
Preserve all historical cook records without requiring any legacy provider client.

User-owned provider credentials must be encrypted on device; do not send raw vendor credentials
or cook histories to PitTech servers. Support multiple devices, distinguish sensor observed time
from phone receipt time, suppress fabricated missing readings, and document unsupported history.

Run the source audit in strict mode against the production packaging source tree as part of
public telemetry release review. A strict failure is an intentional release blocker, not an
invitation to suppress the check.

External service terms and trademark usage must be checked against the then-current policy.
A vendor's public API documents a technical integration route; do not describe it as blanket
unconditional commercial permission.
