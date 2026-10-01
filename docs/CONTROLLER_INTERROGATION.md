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

A follow-up Windows-hosted BLE probe found one matching family advertisement among four to eight nearby BLE devices (RSSI about -85 to -94 dBm). The host is Windows 11 Pro, build 26200, with an Intel Wireless Bluetooth adapter (driver 22.230.0.2). It connected directly without requesting OS pairing, discovered the same four services and seven characteristics, and successfully read all five readable characteristics; the value fingerprints matched report #32. This shows direct GATT access can work separately from Windows Settings pairing. The `0000ff01` vendor characteristic advertises write and notify properties, but no vendor write or notification subscription was attempted. No live temperature field or command semantics have been identified. The weak signal and brief sessions do not establish reliable range or connection stability.

Further read-only characterization found the same five successful reads in three short GATT sessions, with stable fingerprints. In one active polling session, five full passes (25 reads) completed before the connection closed; a separate timed check closed at about 7.5 seconds both while idle and while repeatedly reading `0000ff02` (eight reads succeeded before disconnect). Across the repeated passes, the data remained unchanged: the Device Name was 13 bytes, Appearance was `0`, both Generic Attribute feature octets were `0x00`, and the vendor read was a stable 10-byte opaque value. Its 10-byte fingerprint matches the advertisement's `000002ff` service-data fingerprint. A later fresh session again connected and read all five values successfully. Safe descriptor reads found only the standard Client Characteristic Configuration descriptors: the vendor notification channel `0000ff01` reported notifications disabled, and the Generic Attribute Service Changed descriptor reported indications enabled. The latter state's origin is unknown; the probe did not request notification setup or issue a descriptor write. No Device Information service (firmware/model/manufacturer fields) or standard temperature service was present in the discovered tree. This only describes the exposed GATT tree; vendor semantics remain unknown.

The [manufacturer's P7 Pro Wi-Fi manual](https://ifire-tech.com/assets/pdf/P7PRO-W.pdf) says a controller is visible in Bluetooth scan results while in network-configuration mode; selecting it connects and proceeds to Wi-Fi password setup. The app-control guide describes chamber and meat-probe readings/settings after setup. Static analysis of the vendor app below confirms that the BLE surface we observed is used for onboarding. The exact grill model remains unconfirmed. The earlier hardware interrogations used scan, connect, service discovery, characteristic reads, and descriptor reads only. They did not request pairing or notification setup, or issue any characteristic or descriptor write.

## GrillirG Control APK: static protocol findings (2026-09-30)

This section is based on offline analysis of GrillirG Control Android package `com.primepolaris.grillirgcontrol`, version 2.0.0 (version code 76), listed on [Google Play](https://play.google.com/store/apps/details?id=com.primepolaris.grillirgcontrol) and [APKPure](https://apkpure.net/grillirg-control/com.primepolaris.grillirgcontrol/download). Google Play provides the official listing but no direct APK download, so the XAPK came from APKPure. The downloaded XAPK SHA-256 is `5d2273f0e2a75adfe37efb9c613402e11f2271542d249fe5e0ea7b493b731906`; it matched the APKPure listing. `apksigner` verified v2/v3 signatures, and the signer SHA-1 `36bff80af828ac06fd27da77983e48b79b234dae` matched the Google Play app-signing fingerprint APKPure lists. This verifies the package signature against that published fingerprint, but does not make APKPure an official distribution source. The app targets Android API 36 and requests Bluetooth scan/connect/advertise, location, and network-state permissions. The APK, decompiler output, and temporary analysis files were kept outside the repository. No APK was installed or run, and no controller was connected during this static analysis.

### BLE onboarding protocol

The app scans without a service filter for up to 30 seconds and selects advertisements whose local name contains `iFireTech`. It prefers service data under short UUID `02ff` to derive the app's device ID; if service data is missing, it falls back to reading characteristic `ff02` in service `00fe`. This exactly matches the previously observed `000000fe`/`0000ff02` readable characteristic and the 10-byte `000002ff` advertisement data. The app then connects directly with BLE GATT and builds its UUID map from characteristic properties. The setup screen calls GATT `connect` and `retrieveServices`; I found no call to the BLE library's `createBond` method in this app setup flow. This is consistent with direct GATT access working on Windows even though Windows Settings pairing failed.

After connecting, the app sends a connection-verification frame to a writable characteristic. Its frame format is `AB version command length payload CRC8 AA`; the CRC-8 uses polynomial `0x07` over command, length, and payload. The verification frame is version 1, command 5, payload `A5`. This is an application-level BLE write: it was found in the APK source, not sent by PitTech or by this analysis.

The Wi-Fi setup screen uses the same frame protocol on the `00ff` service's `ff01` write/notify characteristic. It requests nearby Wi-Fi networks with version 1, command 1, payload `[01]`. Response command 2 contains network records with signal/authentication/cipher/channel information, BSSID, and SSID. Selecting a network submits command 3. For open networks, the payload contains the SSID, BSSID, and authentication mode. For secured networks, the payload additionally contains AES-CBC/PKCS#7 ciphertext and a random 16-byte IV for the entered password. The encryption key is embedded in the app bundle; its literal value is intentionally omitted here. A command 4 response reports Wi-Fi connection success or failure. On success the app proceeds to account-based device registration and binding.

The APK reveals no BLE temperature-read command. PitTech has an experimental, explicitly started onboarding flow that mirrors the verified vendor Wi-Fi setup commands: it requests and displays the controller's Wi-Fi scan, then asks for a separate confirmation before sending credentials. The credentials are encrypted in the vendor-compatible format, transmitted locally over BLE, and not uploaded by PitTech. A scan and credential submission have now been observed against one controller, but the controller's two-byte command-4 replies are not yet understood. Wi-Fi association, account binding, live temperature reads, and grill controls remain unverified.

### Android controller exchange observation (2026-10-01)

In a user-provided on-device trace, PitTech connected over GATT, negotiated MTU 517, enabled notifications, received 15 distinct Wi-Fi network records plus the scan-complete marker, and received successful GATT acknowledgements for all four credential-write chunks. After credential submission, the controller sent three valid command-4 frames with two-byte payloads. PitTech currently recognizes one-byte command-4 status payloads, so it could not interpret those replies and remained in `WAITING_FOR_WIFI`. All 19 notifications parsed as valid protocol frames; there were no malformed outer frames or Wi-Fi record parse failures. This establishes that the controller responds after the credential write, but the response meaning and whether it joined Wi-Fi remain unknown. The diagnostic update records only positions of bytes matching known status codes; arbitrary response payload contents remain excluded.

### Windows live GATT validation (2026-09-30)

A local Windows BLE client found the `iFireTech` advertisement (RSSI -83 dBm), connected directly without Windows Settings pairing, discovered the expected four services and seven characteristics, and successfully read all five readable characteristics. The ten-byte `0000ff02` device value had the same fingerprint recorded in the earlier read-only sessions. The client then sent only the vendor connection-check command and Wi-Fi scan request, subscribed to `0000ff01`, and received 15 command-2 network records followed by the scan-complete marker. Record payloads were 18 to 32 bytes (24 to 38 bytes including the protocol frame); the scan included WPA2-family, WPA2/WPA3, and open networks. SSIDs and BSSIDs were deliberately omitted from the log. No Wi-Fi credential command was sent, and no controller configuration was changed. This confirms that Settings pairing is unnecessary and that the controller returns complete Wi-Fi scan frames over GATT.

The live scan also showed that the protocol's signal/RSSI bytes cannot safely be displayed as dBm: the observed unsigned values were positive. PitTech now labels those fields without assigning unverified units. Its Android GATT session requests MTU 247 and requires at least MTU 54 before service discovery so the longest supported 32-byte SSID record (51-byte full frame plus the ATT header) can arrive as one notification.

### After onboarding: cloud data and controls

The vendor app's normal device screen does not appear to control the grill over BLE. It subscribes to authenticated Socket.IO events `deviceRealTimeData` and `deviceEvent`, keyed by the cloud device ID, and also calls authenticated REST endpoints such as `/dms/queryDeviceRealTimeData` and `/das/queryDeviceCurrentWorkingData`. Its control actions submit fields including `furnace_temp_setting`, `smoke_mode_and_smoke_level`, and `device_switch` through authenticated `POST /dms/deviceFunctionSettings`. Device binding also uses an authenticated REST endpoint. Therefore, setting up the grill in the official app would put it on Wi-Fi and bind it to the vendor service; it would not make this PC automatically see the phone's BLE traffic, nor did the decompiled app reveal a local BLE temperature/control protocol. The backend may relay commands to the grill, but that relay protocol is not present in the APK.

### Cloud API alternative

A separate community Home Assistant integration reports controlling GrillirG devices through the existing Prime Polaris cloud API. Its reverse-engineered notes describe email one-time-code login, authenticated state reads, and a control endpoint; the integration author reports live testing on a Pit Boss A10 controller, and another user reports success on an iFire GrillirG P7X.5-PRO-WiFi controller. See the [API notes](https://github.com/kingchddg901/ha-prime-polaris/blob/main/docs/api.md) and [compatibility report](https://community.home-assistant.io/t/prime-polaris-home-assistant-integration-for-grillirg-cloud-pellet-grills-pit-boss-wifi-and-likely-other-rebrands/1008805). This is evidence that PitTech may be able to read and control a compatible, already-bound grill using the user's vendor account. It is not a self-hosted cloud and it does not authenticate to the Bluetooth device; it depends on Prime Polaris authentication and servers, and the exact controller model from report #32 has not been confirmed against this integration.

The manufacturer manual documents Wi-Fi setup and controller-side manual operation, but I found no supported setting for a custom cloud hostname or credentials. A truly independent cloud would need either controller firmware that can be configured to trust and contact that service, or a local gateway that speaks the controller's network protocol and relays data outward. The APK and available manual do not establish that either is possible. For fastest PitTech control, testing the existing OEM cloud API against the exact grill model is currently a more concrete lead than inventing a replacement cloud. A future capture of the official app's onboarding traffic would require a capture on the Android device (or app-side instrumentation) and would include credential-provisioning writes; it cannot be passively recovered from the absent controller or from this PC alone.

## Verification

PitTech's experimental setup flow has JVM protocol tests for framing, CRC, Wi-Fi scan parsing, password payload layout, and ATT-sized frame splitting. A Compose UI test is written to verify the confirmation gate before credential submission. The debug app and instrumentation-test APK compile, and all JVM unit tests pass. A local API 36 emulator run started the existing `PitTechUserFlowsTest` class; two tests passed, then the emulator app process aborted during the third cook-creation flow and the remaining instrumentation tests did not run. The new setup Compose test still needs an isolated emulator run. The direct Windows GATT read and Wi-Fi scan succeeded, but Android credential provisioning remains untested.

A controller is not officially supported merely because it resembles a known model or protocol. A verified profile is added only after physical hardware testing proves discovery, connection, state reads, and any supported control operations are reliable.

Until then, the controller remains unverified and the UI offers Submit controller for support after automatic interrogation.

## References

The protocol approach is informed by the [current dknowles2/pytboss implementation](https://github.com/dknowles2/pytboss), [Mongoose OS RPC over BLE GATT documentation](https://mongoose-os.com/docs/mongoose-os/userguide/ble/rpc-over-ble.md), [Mongoose OS RPC inventory and introspection documentation](https://mongoose-os.com/docs/mongoose-os/howtos/rpc-list.md), [Mongoose OS Config service documentation](https://mongoose-os.com/docs/mongoose-os/api/rpc/rpc-service-config.md), [Mongoose OS configuration over BLE GATT documentation](https://mongoose-os.com/docs/mongoose-os/userguide/ble/config-over-ble.md), and [Mongoose OS debug service documentation](https://mongoose-os.com/docs/mongoose-os/api/net/bt-service-debug.md). PitTech's Kotlin implementation is independent. The existing Apache-2.0 attribution for pytboss remains in THIRD_PARTY_NOTICES.md and LICENSES/APACHE-2.0.txt.
