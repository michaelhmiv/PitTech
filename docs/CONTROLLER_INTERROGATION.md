# Controller interrogation

This experimental path is available only in PitTech debug and Firebase Dev builds. Production continues to use the normal Devices placeholder until physical hardware profiles are verified.

## Design principle

PitTech treats a newly discovered controller as unknown. Bluetooth discovery is evidence gathering, not an assumption that the device uses a Pit Boss cloud relay.

The automatic flow is:

1. scan nearby BLE advertisements without filtering to a vendor-specific name;
2. run Android adapter discovery and merge results by Bluetooth address, recording which path found each candidate;
3. select the controller;
4. connect directly over BLE and inventory the GATT surface;
5. inventory every service, characteristic, and descriptor, and attempt every safe readable characteristic up to the reported 256-read bound and overall session timeout;
6. sequentially enable up to eight non-protocol notification channels, observe them together for up to 10 seconds, then disable them; Mongoose RPC/debug channels are reserved for their protocol-specific session;
7. identify known protocol families from service/characteristic evidence;
8. when Mongoose OS RPC-over-GATT is present, establish that transport directly and run the explicitly allowlisted observational probe;
9. build a stable capability fingerprint and compare it with the verified-controller registry;
10. if the controller is not verified, let the user review a sanitized report and submit it to the existing GitHub issue relay.

Each generic notification observation is limited to 64 events, 512 bytes per event, 16 KiB retained across the session, and at most eight characteristics. The report records event counts, payload changes, byte counts, duration/frequency, and failures; raw notification payloads are not included.

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
The Mongoose configuration service's value characteristic is deliberately not read: it returns the currently selected configuration value and could disclose an SSID, password, or other credential.

## Automatic observational RPCs

The initial allowlist includes RPC.Ping, RPC.List, RPC.ListEx, RPC.Describe, Sys.GetInfo, PB.GetFirmwareVersion, PBL.GetLoaderVersion, and one narrowly scoped Config.Get call for the documented non-secret `http.enable` boolean. `RPC.ListEx` is used if `RPC.List` reports that the method is unavailable. PitTech does not read a full configuration section or local listen address.

RPC.List/RPC.ListEx are used to inventory what the physical controller actually exposes. RPC.Describe is used for bounded introspection of discovered methods without executing those methods.

The RPC planner has an explicit safety classifier. Unknown methods are inventoried but not executed. Known mutation/sensitive-read families are blocked from automatic execution.

## Diagnostics and privacy

The local probe can observe advertisements, GATT structure and values, bounded generic notification statistics, Mongoose RPC responses, method inventory, descriptions, and debug notifications retained only during a 15-second window. Nothing is uploaded automatically.

Before a controller report can be submitted:

- the permanent Bluetooth address is removed from the public representation;
- a per-session device identifier is generated instead;
- arbitrary advertised names are withheld; recognized Pit Boss/Mongoose family names retain only the family label;
- raw advertisement, manufacturer, service-data, and GATT payload bytes are withheld and replaced with byte counts and SHA-256 fingerprints;
- printable GATT values, protocol responses, scan errors, and debug text pass through the centralized sanitizer;
- password/passwd/passphrase/psw/secret/token/authorization/SSID/BSSID/username/email/certificate/private-key/serial-like fields, Bluetooth addresses, and private LAN IP addresses are redacted;
- the user previews the full generated report;
- the user explicitly chooses to submit the public GitHub issue.

The report includes a stable capability fingerprint derived from non-secret discovery/protocol evidence so later captures can be compared without publishing the Bluetooth address. Firmware/system responses and RPC inventories remain visible after sanitization because they are especially useful for adding support.

## GitHub support intake

Use the **Controller support** issue form for users who cannot share a chat. Ask for the controller brand/model, PitTech and Android versions, the last Bluetooth stage that worked, what they expected, what happened, and reproducible steps. Capture exact error text and any separate tests from Windows or another phone, including the OS version and whether the test was scanning, pairing in system settings, connecting over BLE, or reading GATT services. If the in-app Controller diagnostics flow is available, users can submit its reviewed report and link the generated issue instead of copying the report into a second issue.

### Interpreting a pairing failure

Do not treat "pairing failed" as a complete Bluetooth diagnosis. A scan finding an advertisement, pairing in the operating system's settings, opening a direct BLE/GATT connection, discovering services, reading characteristics, receiving notifications, and exchanging app commands are distinct steps. A failure in Windows Settings alone does not establish that the controller is absent or that PitTech cannot connect. Record the host OS/build, Bluetooth adapter if known, controller state, exact screen/error, and the furthest stage reached. Do not ask users to provision Wi-Fi or send undocumented vendor writes as a pairing workaround.

GitHub issues are public. The form reminds users to review reports and exclude Bluetooth addresses, serial numbers, Wi-Fi names/passwords, account details, and other identifying information. For GATT reads, public reports retain UUIDs, byte counts, and value fingerprints; arbitrary characteristic text is withheld. A recognized family name may be retained with its suffix removed.

## Current hardware lead (unverified)

The user-submitted [controller report #32](https://github.com/michaelhmiv/PitTech/issues/32) records a successful direct BLE connection and five successful GATT reads from an iFireTech-family device on a Pixel 8 Pro running Android 17. The GATT tree included standard Generic Access and Generic Attribute services plus vendor services `000000ff-0000-1000-8000-00805f9b34fb` and `000000fe-0000-1000-8000-00805f9b34fb`. The vendor write/notify characteristic was `0000ff01-0000-1000-8000-00805f9b34fb`; the readable characteristic was `0000ff02-0000-1000-8000-00805f9b34fb` under service `000000fe-0000-1000-8000-00805f9b34fb`. No Mongoose RPC service or RPC methods were found, and no notification payloads arrived during the bounded observation.

The report's initial read sequence succeeded, but the GATT session later disconnected with status 257 during passive notification observation. This does not establish stable connection behavior, temperature-read capability, or the meaning of the vendor characteristics. The inspection did not send an application payload to the vendor write characteristic; notification setup uses the CCCD only. Treat the opaque value read from the vendor service as private until its meaning is established.

iFire-Tech's [P7-PRO-WiFi manual](https://ifire-tech.com/assets/pdf/P7PRO-W.pdf) lists Austin XL compatibility and describes Bluetooth device pairing, Wi-Fi setup, real-time monitoring, and remote temperature adjustment. The GATT name identifies the iFireTech family but does not identify this exact controller model. Record this as a promising hardware lead, not a verified support profile. Do not attempt vendor-characteristic writes until their protocol and effects are known.

A follow-up Windows-hosted BLE probe found one matching family advertisement among eight nearby BLE devices (RSSI about -92 dBm). It connected directly without requesting OS pairing, discovered the same four services and seven characteristics, and successfully read all five readable characteristics; the value fingerprints matched report #32. This shows direct GATT access can work separately from Windows Settings pairing. The `0000ff01` vendor characteristic advertises write and notify properties, but no vendor write or notification subscription was attempted. No live temperature field or command semantics have been identified. The Windows build and adapter model were not captured, and the weak signal plus one short session do not establish reliable range or connection stability.

## Verification

A controller is not officially supported merely because it resembles a known model or protocol. A verified profile is added only after physical hardware testing proves discovery, connection, state reads, and any supported control operations are reliable.

Until then, the controller remains unverified and the UI offers Submit controller for support after automatic interrogation.

## References

The protocol approach is informed by the [current dknowles2/pytboss implementation](https://github.com/dknowles2/pytboss), [Mongoose OS RPC over BLE GATT documentation](https://mongoose-os.com/docs/mongoose-os/userguide/ble/rpc-over-ble.md), [Mongoose OS RPC inventory and introspection documentation](https://mongoose-os.com/docs/mongoose-os/howtos/rpc-list.md), [Mongoose OS Config service documentation](https://mongoose-os.com/docs/mongoose-os/api/rpc/rpc-service-config.md), [Mongoose OS configuration over BLE GATT documentation](https://mongoose-os.com/docs/mongoose-os/userguide/ble/config-over-ble.md), and [Mongoose OS debug service documentation](https://mongoose-os.com/docs/mongoose-os/api/net/bt-service-debug.md). PitTech's Kotlin implementation is independent. The existing Apache-2.0 attribution for pytboss remains in THIRD_PARTY_NOTICES.md and LICENSES/APACHE-2.0.txt.
