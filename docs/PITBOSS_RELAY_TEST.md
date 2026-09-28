# Controller Bluetooth discovery and relay test

This experimental test path is available only in PitTech debug and Firebase Dev builds. The regular release build keeps the existing Devices placeholder.

## Discovery and support status

1. PitTech scans BLE advertisements in the foreground for 12 seconds. It lists nearby BLE devices, not only devices with a Pit Boss-looking name, so a controller with an unknown name can still be investigated.
2. The app reads the advertised name and, when permission allows, the Bluetooth address. It retains distinct advertisement records plus repeat counts, RSSI ranges, service and solicitation UUIDs, manufacturer/service data, raw bytes, and Bluetooth scan metadata.
3. A profile appears as verified only after the team has confirmed it works on physical hardware and adds its profile to the explicit verified-profile registry. That registry is empty at the start of this test; protocol guesses do not count as approval.
4. For an unverified device, the user can review a diagnostic report and submit it directly through the existing anonymous feedback relay. The relay creates a public GitHub issue in the PitTech repository. No GitHub account is needed.

Each report contains only the selected device's Bluetooth data and app-owned test events; nearby devices are not included. A Bluetooth address can uniquely identify a device. The UI previews the full report and explains that the issue is public before the user submits it. An empty-scan report contains scan metadata and app-owned test events only. The scanner keeps at most 100 devices and 80 distinct advertisement variants per device in memory; repeat packets are summarized by count, time range, and RSSI range, and omitted counts appear in the issue.

## Read-only GATT inspection

After a scan finishes, select the controller entry and tap **Inspect BLE services**. PitTech makes a direct foreground LE GATT connection, records connection and service-discovery callbacks, lists the discovered services/characteristics/descriptors and their properties/permissions, then attempts sequential reads of up to 20 characteristics whose properties include READ. Each read has a five-second callback timeout; the overall inspection has a 90-second timeout. The UI lets you stop early.

PitTech does not explicitly bond, write characteristic values, or subscribe to notifications. A controller can reject a read or require authentication; those statuses are recorded. The report includes returned values as hex and, when printable, UTF-8. Review them carefully because a public issue may expose controller state. The full test-session log also records timestamps, scan/permission state, GATT callbacks, and relay state changes. The latest 80 app events are retained for the report; if older entries roll off, the issue includes an omission count. It is an app-owned diagnostic log, not Android's complete Logcat.

## Test workflow

1. Install the debug or Firebase Dev build. Leave the Pit Boss release app available for normal Wi-Fi setup if needed.
2. Power the controller, keep the phone close, and tap **Scan nearby**.
3. Select the controller entry. Tap **Inspect BLE services** and let the read-only inspection finish; stop it if it is still running after 90 seconds.
4. If a Pit Boss relay ID candidate appears and the controller has been set up online in the official app, tap **Test relay**.
5. Tap **Review diagnostics**, inspect the full preview (including raw advertisement/GATT values), and submit only if you are comfortable creating a public issue.
6. Send me the GitHub issue number or link. I can retrieve the report and use the service tree/values to determine the next connection step.

If GATT connect or reads fail, submit the report anyway; it still contains scan data, permission state, connection status, discovered services (if any), callback status codes, and failure details. If the controller's official app is actively connected, close its live controller screen and retry GATT inspection if the first connection times out. If PitTech crashes, relaunch it and submit the existing locally saved crash report through the normal problem-report flow. Nothing is sent automatically.

## Pit Boss relay probe

If a selected name looks like a Pit Boss relay ID, the user can test the vendor WebSocket at `socket.dansonscorp.com/to/{device_id}`. The app sends only `RPC.Ping` and displays redacted responses. The controller must already be online through Wi-Fi setup in the Pit Boss app. The GATT inspection is read-only; it does not provision Wi-Fi credentials or issue grill-control commands.

The protocol approach is informed by the public observations in [dknowles2/pytboss](https://github.com/dknowles2/pytboss) and the [Home Assistant Pit Boss integration](https://github.com/dknowles2/ha-pitboss). The pytboss project is licensed under Apache-2.0; its license text is included at [LICENSES/APACHE-2.0.txt](../LICENSES/APACHE-2.0.txt). The Kotlin implementation is independent and does not include pytboss source.
