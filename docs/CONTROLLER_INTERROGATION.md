# Controller interrogation

This experimental path is available only in PitTech debug and Firebase Dev builds. Production continues to use the normal Devices placeholder until physical hardware profiles are verified.

## Design principle

PitTech treats a newly discovered controller as unknown. Bluetooth discovery is evidence gathering, not an assumption that the device uses a Pit Boss cloud relay.

The automatic flow is:

1. scan nearby BLE advertisements without filtering to a vendor-specific name;
2. select the controller;
3. connect directly over BLE and inventory the GATT surface;
4. read every characteristic marked readable, subject only to a high sanity cap and the overall session timeout;
5. identify known protocol families from service/characteristic evidence;
6. when Mongoose OS RPC-over-GATT is present, establish that transport directly and run the explicitly allowlisted observational probe;
7. build a stable capability fingerprint and compare it with the verified-controller registry;
8. if the controller is not verified, let the user review a sanitized report and submit it to the existing GitHub issue relay.

## Transport writes versus controller mutation

Mongoose RPC-over-GATT requires BLE writes to carry the request length and JSON-RPC request. Notifications also require the usual CCCD write. PitTech therefore does not equate every BLE write with a controller mutation.

The automatic probe may perform transport-level writes only when they are required to enable RPC/debug notifications or carry an explicitly allowlisted observational RPC request.

The automatic probe does not execute controller-setting or unknown RPC methods. It never automatically sends operations for ignition, temperature targets, motors/auger/primer, credentials, Wi-Fi provisioning, password changes, configuration writes, filesystem writes, reboot, firmware/OTA, MCU commands, or unknown vendor methods.

## Mongoose protocol detection

PitTech recognizes the documented Mongoose OS RPC service by its GATT structure rather than the advertised Bluetooth name.

Known RPC GATT UUIDs:

- service: 5f6d4f53-5f52-5043-5f53-56435f49445f
- data: 5f6d4f53-5f52-5043-5f64-6174615f5f5f
- TX control: 5f6d4f53-5f52-5043-5f74-785f63746c5f
- RX control: 5f6d4f53-5f52-5043-5f72-785f63746c5f

PitTech also detects the Mongoose debug and configuration GATT services when present.

## Automatic observational RPCs

The initial allowlist includes RPC.Ping, RPC.List, RPC.ListEx, RPC.Describe, Sys.GetInfo, PB.GetFirmwareVersion, PBL.GetLoaderVersion, and narrowly scoped Config.Get calls for non-secret transport capability keys such as HTTP enable/listen state and Bluetooth enable/config state.

RPC.List/RPC.ListEx are used to inventory what the physical controller actually exposes. RPC.Describe is used for bounded introspection of discovered methods without executing those methods.

The RPC planner has an explicit safety classifier. Unknown methods are inventoried but not executed. Known mutation/sensitive-read families are blocked from automatic execution.

## Diagnostics and privacy

The local probe can observe advertisements, GATT structure and values, Mongoose RPC responses, method inventory, descriptions, and bounded debug notifications. Nothing is uploaded automatically.

Before a controller report can be submitted:

- the permanent Bluetooth address is removed from the public representation;
- a per-session device identifier is generated instead;
- password/passphrase/psw/secret/token/authorization/SSID/BSSID/username/email/certificate/private-key-like fields are redacted;
- protocol responses and debug text pass through the sanitizer;
- the user previews the full generated report;
- the user explicitly chooses to submit the public GitHub issue.

The report includes a stable capability fingerprint derived from non-secret discovery/protocol evidence so later captures can be compared without publishing the Bluetooth address.

## Verification

A controller is not officially supported merely because it resembles a known model or protocol. A verified profile is added only after physical hardware testing proves discovery, connection, state reads, and any supported control operations are reliable.

Until then, the controller remains unverified and the UI offers Submit controller for support after automatic interrogation.

## References

The protocol approach is informed by the public dknowles2/pytboss implementation and Mongoose OS RPC/GATT documentation. PitTech's Kotlin implementation is independent. The existing Apache-2.0 attribution for pytboss remains in THIRD_PARTY_NOTICES.md and LICENSES/APACHE-2.0.txt.
