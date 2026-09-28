# Pit Boss Bluetooth discovery and relay test

This is an experimental, read-only test path for PitTech's debug and Firebase Dev builds. The regular release build keeps the Devices placeholder.

## What it tests

1. PitTech scans nearby BLE advertisements and reads the controller's advertised local name. Pit Boss uses that name as the device ID, so the user does not need to look up or type a grill ID.
2. After the user selects a nearby controller, the app opens a WebSocket directly to the Pit Boss vendor relay at `socket.dansonscorp.com/to/{device_id}`.
3. The app sends one `RPC.Ping` and displays redacted relay responses and incoming controller status frames. No grill-control command is sent.

This only uses Bluetooth for discovery in this first slice; it does not yet provision Wi-Fi credentials over GATT. The controller must already be online in the Pit Boss app for the vendor-relay test.

The protocol approach is informed by the public observations in [dknowles2/pytboss](https://github.com/dknowles2/pytboss) and the Home Assistant Pit Boss integration. The pytboss project is licensed under Apache-2.0; its license text is included at [LICENSES/APACHE-2.0.txt](../LICENSES/APACHE-2.0.txt). The Kotlin probe is an independent implementation; it does not include the pytboss package.

## Test steps

1. Turn on the controller and keep the phone within Bluetooth range.
2. In PitTech's **Devices** tab, tap **Scan nearby** and grant Bluetooth scan permission.
3. Select the controller found by its Bluetooth name, then tap **Test relay**.
4. Record whether the relay accepted the connection, returned an RPC response, or delivered controller status frames.

The selected controller ID is saved in the app's private preferences. The connection and status frames pass through Pit Boss's vendor relay; PitTech does not proxy or store them on a PitTech server. The test does not collect the Pit Boss account password, and it redacts password-like fields before displaying incoming JSON.

## Limits of this first test

The BLE scan itself is not a GATT connection. It reads the broadcast identity and uses it to test the existing vendor relay. BLE control, Wi-Fi provisioning, local-network discovery, and model-specific status decoding can be added as separate transports after this relay behavior is measured on the controller in hand. A WebSocket handshake alone only proves the relay accepted a socket; an RPC response and controller status frame are stronger end-to-end evidence.
