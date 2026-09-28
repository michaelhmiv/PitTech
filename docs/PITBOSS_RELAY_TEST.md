# Controller Bluetooth discovery and relay test

This experimental test path is available only in PitTech debug and Firebase Dev builds. The regular release build keeps the existing Devices placeholder.

## Discovery and support status

1. PitTech scans BLE advertisements in the foreground for 12 seconds. It lists nearby BLE devices, not only devices with a Pit Boss-looking name, so a controller with an unknown name can still be investigated.
2. The app reads the advertised name and, when permission allows, the Bluetooth address. It retains distinct advertisement records plus repeat counts, RSSI ranges, service and solicitation UUIDs, manufacturer/service data, raw bytes, and Bluetooth scan metadata.
3. A profile appears as verified only after the team has confirmed it works on physical hardware and adds its profile to the explicit verified-profile registry. That registry is empty at the start of this test; protocol guesses do not count as approval.
4. For an unverified device, the user can review a diagnostic report and submit it directly through the existing anonymous feedback relay. The relay creates a public GitHub issue in the PitTech repository. No GitHub account is needed.

Each report contains only the selected device's Bluetooth data; nearby devices are not included. A Bluetooth address can uniquely identify a device. The UI previews the full report and explains that the issue is public before the user submits it. An empty-scan report contains scan metadata only. The scanner keeps at most 100 devices and 80 distinct advertisement variants per device in memory; repeat packets are summarized by count, time range, and RSSI range, and omitted counts appear in the issue.

## Pit Boss relay probe

If a selected name looks like a Pit Boss relay ID, the user can test the vendor WebSocket at `socket.dansonscorp.com/to/{device_id}`. The app sends only `RPC.Ping` and displays redacted responses. The controller must already be online through Wi-Fi setup in the Pit Boss app. This does not yet connect to BLE GATT or provision Wi-Fi credentials.

The protocol approach is informed by the public observations in [dknowles2/pytboss](https://github.com/dknowles2/pytboss) and the [Home Assistant Pit Boss integration](https://github.com/dknowles2/ha-pitboss). The pytboss project is licensed under Apache-2.0; its license text is included at [LICENSES/APACHE-2.0.txt](../LICENSES/APACHE-2.0.txt). The Kotlin implementation is independent and does not include pytboss source.

## Test steps

1. Turn on the controller and keep the phone nearby.
2. In **Devices**, tap **Scan nearby** and allow Nearby devices access.
3. Find the controller by its advertised name or signal strength. If it is unverified, tap **Review diagnostics**, inspect the full report, and tap **Submit public report** to create the issue.
4. If the controller has a Pit Boss relay ID candidate and is already online in the Pit Boss app, tap **Test relay**.
5. Record whether the relay accepted the connection, returned an RPC response, or delivered controller status frames.

No scan report is uploaded unless the user reviews and submits it. The report includes the selected controller's advertisement details and any redacted relay messages, not cook records, photos, or data from other nearby devices.
