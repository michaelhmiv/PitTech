# Grill provider compatibility and implementation

Research checked 2026-10-02. The two new runtime adapters are **experimental Dev/Debug integrations with hardware confirmation pending**. Community protocol evidence is not PitTech hardware validation. This work used published primary source code, protocol notes, and official documentation; it did not log into anyone's account, send traffic to a live grill, capture a customer's credentials, or operate a grill.

## What is built

| Provider shown in Devices | User setup | Read-only collection | Cook integration |
|---|---|---|---|
| GrillirG | Existing email-code account, grill already added in GrillirG | Existing Polaris HTTP reads | Existing behavior and encrypted session remain compatible |
| Pit Boss / Louisiana Grills | A provisioned Dansons Wi-Fi controller's exact current ID; optional controller password | Encrypted WebSocket relay; only PB.GetTime and PB.GetState | Chamber, observed target, up to four stable probe slots, dish assignments, copied log/photo context, graphs, local exports |
| Traeger WiFIRE | Grill paired in Traeger; owner account email/password | Account discovery, signed AWS MQTT subscription, fixed status request 90 | Same cook workflow; connected accessories keep physical channel identity |

Choose the **controller platform** actually in use. A Pit Boss grill retrofitted with a GrillirG controller belongs under GrillirG. A brand name, printed model number, Bluetooth advertisement, or successful HTTP login alone does not prove Wi-Fi telemetry support.

Starting a cook remains a local PitTech action. Attaching or resuming recording locks both provider and grill. One active recording collects into one active cook; probe assignment intervals preserve each sample's original dish. Camera/photo timestamps and copied temperature context use the existing persistence workflow. No grill power, heat, mode, fan, target, recipe, firmware, or Wi-Fi configuration operations are exposed.

## Primary evidence for the priority adapters

### Pit Boss / Louisiana

Audited [pytboss at 3017d7e](https://github.com/dknowles2/pytboss/tree/3017d7efc9cdd97efa5ed34ef126a4d2bf92466e), particularly [WebSocket transport](https://github.com/dknowles2/pytboss/blob/3017d7efc9cdd97efa5ed34ef126a4d2bf92466e/pytboss/wss.py), [read API](https://github.com/dknowles2/pytboss/blob/3017d7efc9cdd97efa5ed34ef126a4d2bf92466e/pytboss/api.py), [board catalogue](https://github.com/dknowles2/pytboss/blob/3017d7efc9cdd97efa5ed34ef126a4d2bf92466e/pytboss/grills.json), parser corrections and password codec. Also cross-checked [ha-pitboss](https://github.com/dknowles2/ha-pitboss).

The relay URL scopes requests to the entered controller. RPC replies are correlated by request ID and, when present, application/source IDs. Renaming changes the controller ID; its suffix keeps its exact case and is encoded as one URL path segment. No identifiers are guessed or enumerated. The optional password belongs to the controller, not the Pit Boss account. A successful state read is required before saving a connection.

The read parser uses static audited layouts for LBL, LFS, PBA, PBB, PBC, PBC2, PBD, PBE, PBG, PBL, PBL2, PBL3, PBM, PBM2, PBP, PBT, PBV, PBV2, PBVA and PBX1. Prefixes alone do not guarantee that a board has Wi-Fi or that a particular firmware uses the relay. PBVA/PBX1 deserve particular hardware verification because the catalogue includes experimental/unsupported models.

PitTech's implementation rejects empty/reset or truncated frames, preserves the 960 unavailable sentinel before conversion, and keeps native Celsius families distinct from families carrying Fahrenheit bytes. It takes chamber temperature from the temperature frame rather than overlapping status flags. PBVA's target alias is excluded from food probes. No vendor JavaScript or writable command catalogue runs in the app. Model-specific physical probe count still needs comparison with the connected grill; absent slots display unavailable.

The SDK also offers local BLE and optional HTTP RPC. HTTP is not universal: newer ESP-IDF families may have no HTTP server. This adapter deliberately uses the documented cloud relay for controllers already provisioned by their supported app.

### Traeger

Audited [go-traeger at 937e9da](https://github.com/bemeek-io/go-traeger/tree/937e9da8e7592d91af05ff89f7f1ed46bcc1534d), particularly [API documentation](https://github.com/bemeek-io/go-traeger/blob/937e9da8e7592d91af05ff89f7f1ed46bcc1534d/docs/api.md), auth, models, HTTP and MQTT implementations.

WiFIRE login uses the auth service, and users/self returns the account's confirmed grill identities. The cloud issues a time-limited signed WebSocket URL; telemetry arrives on prod/thing/update/{thingName}. The one commands request allowed by PitTech has a transport-pinned body containing **90**, the documented status read. Caller input cannot substitute a heat/control command. There is no application MQTT publish encoder.

The subscriber handles bounded MQTT framing, fragmented/coalesced packets, subscribe acknowledgements, QoS 0/1 delivery and acknowledgements. A continuous receiver drains the feed and retains the newest matching observation between cook polls. The authenticated topic must match the selected owned grill; an explicit envelope identity must also match. Published SDK fixtures omit that optional identity, so its absence does not break a valid topic-routed report. Closing, changing accounts or stopping monitoring tears down the socket; signed URLs renew before expiry.

Traeger units use the opposite numeric convention from GrillirG. Normalization handles that explicitly. Accessory channels p0–p3 retain physical probe identity regardless of array order. Disconnected accessories override cached legacy probe values; connected 0°C remains valid. Unknown/duplicate channels cannot be silently assigned to dishes.

Passwords are used only for explicit login. ID/refresh tokens are encrypted locally. Refresh uses the official [Cognito InitiateAuth contract](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_InitiateAuth.html) with the public client ID from the vendor-issued token. MFA/federated challenges and newer token-rotation flows have not been validated; unsupported authentication pauses for explicit reconnection. Normal TLS certificate and hostname verification remain enabled, including for MQTT.

## Other major manufacturers

These are **researched routes, not implemented PitTech providers**. Adding them requires a complete setup path and model/firmware fixtures before enabling a connection option.

| Maker/platform | Evidence and sync route | What blocks a universal login-only adapter |
|---|---|---|
| Weber Connect | [ProspectOre integration](https://github.com/ProspectOre/weber-connect-unofficial/tree/1148e9171aa94e81b8422230d29646afcc05d39d): authenticated cloud companion WebSocket after physically approved BLE setup. [Independent protocol notes](https://github.com/GabrielGoldsteinAnidea/weber_connect_ha/blob/main/docs/PROTOCOL.md) also describe cloud sessions. | Companion registration, physical approval and exact cloud association are required. An ordinary Weber email/password form would misrepresent that setup. Do not reuse unauthenticated status frames or captured shared credentials. |
| Masterbuilt Gravity / Kamado Joe connected grills | [lucvan/masterbuilt-gravity-ha](https://github.com/lucvan/masterbuilt-gravity-ha/tree/45f162e09248783010401cf45736dba16c2cbc4d) documents owner account discovery, CAS cloud shadow reads and cook history. Read-only operation does not need its separate IoT control/certificate path. | Model profiles differ. Freshness must use device field timestamps, because the cloud can refresh an envelope while serving old readings. Historical cook import needs explicit device/session attribution and should not backfill receipt-time gaps. |
| Camp Chef / compatible Cabela's or Kingsford controllers | [ha-campchef](https://github.com/doggkruse/ha-campchef/tree/e5e7886fca6781616e8b4c7920abf21fde5f4fe4) and [pycampchef](https://github.com/doggkruse/pycampchef): local BLE, capability discovery, push/poll fallback. | Bonding, range and firmware-specific GATT layouts matter. This research did not establish a complete supported cloud onboarding API. The community integration does not establish every Camp Chef model as compatible. |
| recteq | [homeassistant-recteq](https://github.com/pdugas/homeassistant-recteq/tree/75f0913a6438069e081b22008870cb0c5d10dd7b): local Tuya protocol through tinytuya. | Requires the owner's device ID, local key and LAN address; datapoints and multi-zone models need profiles. No verified universal vendor email/password setup was established here. |
| Green Mountain Grills | [gmg-local](https://github.com/y3klab/gmg-local/tree/5e3a028f45b02436bd22a525bb823d59d723b0ca): LAN UDP status read and decoded temperature fields. | Same-LAN access, expected response shape and firmware generation must be confirmed. The audited 52-byte protocol is not evidence for every Prime/Prime 2 controller or cloud mode. A status byte must not be mislabeled pellet level. |
| Broil King iQue | [broilking-homeassistant](https://github.com/bbirke/broilking-homeassistant/tree/cdfe304657212c5e79afd6f306a3eabc00b9db27): local Mongoose WebSocket RPC, read-only GetCurrentTemperatures with full reply. | Author verified one Regal Pellet 500 Pro (2024). Requires LAN address, unusual nested result decoding and unit/sentinel handling; other models are unverified. Writable generic command methods stay out of a PitTech adapter. |
| FireBoard / compatible FireBoard-equipped grills | [Published FireBoard API](https://docs.fireboard.io/app/app-api/): account token, devices, channels, timestamps and session history. [Yoder ACS documentation](https://docs.fireboard.io/yoder/yoder-overview/) identifies a controller ecosystem. | Public API is the strongest basis for a future integration, but compatibility follows the actual FireBoard device/account, not every Yoder or aftermarket grill. The documented budget is **17 calls per five minutes**: a two-read 15-second loop would exceed it. A dedicated slower cadence/budget is required. |
| Masterbuilt with different BLE firmware | [masterbuilt_smoker_connect](https://github.com/dipseth/masterbuilt_smoker_connect): locally authenticated BLE streaming, developed against Gravity 1150. | Firmware can have a different transport from the cloud-oriented Gravity 800 route. Single BLE ownership and owner-authorized handshake values need validation; a brand-wide guessed secret or protocol is inappropriate. |

**Implementation judgment:** FireBoard and Middleby's Masterbuilt/Kamado Joe cloud reads are the clearest next cloud candidates. GMG/Broil King are credible local-network candidates. Weber requires a dedicated pairing experience; recteq requires local-key setup; Camp Chef needs bonded BLE lifecycle work. This ordering is a product/engineering assessment of the evidence above, not a claim of completed compatibility.

## Shared behavior and verification boundaries

Provider credentials use separate Keystore aliases, authenticated encryption contexts and no-backup files. GrillirG's existing file/alias/context are unchanged. Passwords, refresh tokens, signed URLs, account identifiers, controller IDs and raw payloads do not enter public reports or archives. Normalized diagnostic fields and HTTP/API outcomes are provider aware; local device enumeration does not falsely mark the backend reachable.

Collection defaults to one minute, with 15-second, 30-second, two-minute, five-minute and logging-only options. Timed collection runs while the app is visible or an explicit cook foreground service owns it. Logging-only closes connections after user-triggered reads and has no idle recording service; the one-minute and slower presets close live sessions between reads, while 15/30-second mode keeps them open (including incoming Traeger publications and 25-second WebSocket keepalives). See [sampling and power analysis](RECORDING_POWER_AND_SAMPLING.md). Retries back off and honor rate-limit delay, including discovery after restoring a saved session during an outage. An active cook retains its provider and grill, including while the camera/activity backgrounds. After pausing, a different selected grill requires explicit reattachment; earlier samples keep their original device and dish attribution. Token rejection pauses recording; no automatic login or credential-request loop is added.

Times in stored cook data remain cloud receipt times. Traeger's supplied report timestamp, when present, is additionally checked: stale, repeated and out-of-order reports cannot refresh the receipt history. Pit Boss relay state has no established sensor timestamp, so freshness beyond receipt is unknown. Missing/unsupported units or channels stay unavailable. Temperature gaps remain gaps; no physical cook is started or ended from a telemetry operating code.

The test suite includes source-audited synthetic vectors for all 20 Pit Boss board families in both units; published sentinel/alias regressions; password-codec interoperability vectors; MQTT framing/QoS/correlation and burst draining; HTTP status-read pinning; owner discovery, authentication/refresh/rate-limit and lifecycle failures; provider locking and isolated histories. Android checks cover original encrypted-session compatibility, provider key separation, explicit sign-in UI, four-probe assignments, native timestamp/offline gaps, logs/photo snapshot persistence, and actual foreground service ownership for both providers. Existing cook/archive/upgrade tests remain required.

No physical Pit Boss or Traeger grill was available in this environment. Before treating a particular model as confirmed, compare all connected/unplugged channels and units with its supported app, test app background/camera/lock behavior, interrupt/recover the network and test a token/signed-URL renewal. Synthetic fixtures cannot establish overnight reliability or cover undocumented regional/model changes.

See [connected cook recording](CONTROLLER_COOK_RECORDING.md) and [packaged third-party notices](../third_party/README.md).
