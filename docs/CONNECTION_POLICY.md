# PitTech connection policy

This document defines the intended contract for every future temperature connector. A provider name or **Beta** label is not proof of device compatibility, vendor endorsement, or permission to access a proprietary service.

## Observation only

Permitted capabilities: discover a user-authorized source, authenticate, subscribe to readings, request current measurements, read documented historical samples, and disconnect.

Prohibited capabilities: ignition, shutdown, setpoint changes, fan/auger/motor controls, Wi-Fi provisioning, controller configuration, factory resets, firmware updates, or arbitrary RPC/HTTP/MQTT commands.

**Read-only refers to hardware state, not transport packet direction.** Authentication, WebSocket or MQTT subscriptions, Bluetooth notification setup, and documented observational RPCs may require outbound messages. Their effects must be reviewed, bounded and allowlisted.

No generic extension configuration may grant itself hardware-control privileges. Production connectors must expose observation-specific interfaces, not broad generic command dispatchers.

## Beta and release states

- **Development-only:** not marketed or exposed as public support; experimentation may still require consent and safe operating procedures.
- **Beta connection:** publicly available for a defined supported integration, clearly marked Beta and read-only, with documented limitations and recovery behavior.
- **Verified model:** a specific hardware/firmware combination was physically tested; this is model-specific and does not remove the Beta label unless the project explicitly graduates it.

Before releasing a new integration: review the vendor's terms and API authorization, implement read-only allowlists, test actual supported equipment, validate authentication and secret storage, test unit conversion and freshness gaps, verify rate limits, check background power effects, and confirm app-store disclosure.

## Existing experimental connection and planned connectors

**GrillirG / Prime Polaris:** PitTech already has a working experimental development/test cloud monitor with email-code login, device selection, read-only status requests, current temperature sampling, and cook recording. **Preserve the connector and users' stored cooking history** while migrating it to the shared telemetry API. Its diagnostic BLE Wi-Fi configuration action has been disabled; the new adapter must not reintroduce hardware changes. GrillirG is a sixth first-class integration in the [implementation roadmap](UNIVERSAL_TELEMETRY_ROADMAP.md).

**New candidates:** Combustion Inc. (Bluetooth SDK), Home Assistant (REST/WebSocket), FireBoard (cloud REST), Flame Boss (cloud MQTT), and MEATER (cloud REST) remain engineering candidates. All six must share the same Beta labeling and observation-only interface.

**Release rights are a separate gate.** The [GrillirG Control user agreement](https://www.prime-polaris.com/grillirg-terms.html) prohibits reverse engineering of its app. The applicability to PitTech's independent cloud client and authorization to distribute it need legal/terms review. Keeping a connector in development does not itself mean the manufacturer has authorized public use. A lack of cease-and-desist notice is not affirmative permission. **Do not delete the implementation merely because the review is unresolved**: maintain it for development and preservation of existing user data while deciding distribution eligibility.

**None should be advertised as production-supported merely by appearing in project documentation.**

Some APIs are documented but commercial third-party use or hardware compatibility remains unresolved. Such connectors stay out of production until those issues are resolved.

## User safety

Never depend on PitTech telemetry for food safety, safe grill operation, or unattended operation of heating equipment. Always use the manufacturer's controls, alarms, and guidance.
