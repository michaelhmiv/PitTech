# Third-party connection evidence — reviewed 2026-10-13

This record describes implementation and unresolved release gates. It is not an endorsement,
legal clearance, App Store approval, or evidence of a completed hardware demonstration.

## Dated primary evidence

| Source | Primary documentation and terms | Finding for intended public release |
|---|---|---|
| Combustion | [Android SDK v3.5.4](https://github.com/combustion-inc/combustion-android-ble/tree/v3.5.4), [iOS SDK 1.0.0](https://github.com/combustion-inc/combustion-ios-ble/tree/1.0.0), [site terms](https://combustion.inc/policies/terms-of-service) | Both SDK repository LICENSE files grant MIT software rights, including commercial use with retained notices. Hardware interoperability and brand presentation still need review. Site shopping terms do not replace the SDK license. |
| Home Assistant | [authorization](https://developers.home-assistant.io/docs/auth_api/), [REST](https://developers.home-assistant.io/docs/api/rest/), [WebSocket](https://developers.home-assistant.io/docs/websocket/) | Users operate and authorize their own server. PitTech uses states/history and state_changed subscriptions. No services, control commands or manufacturer community plugins are bundled. Real server/OAuth validation remains necessary. |
| FireBoard | [Cloud API](https://docs.fireboard.io/app/app-api/), [terms](https://www.fireboard.com/terms/) (page last modified 2025-04-11) | API v1 documents devices and session charts, requires User-Agent, and imposes account limits. Terms §1 provide revocable personal/business access; §2.3 restrict advertising/trademark uses, and §4 restrict redistribution. Resolve application/service and promotional rights before public enablement. |
| Flame Boss | [manufacturer MQTT article](https://support.flameboss.com/support/solutions/articles/14000129040-connect-to-flame-boss-cloud-mqtt-broker), [privacy](https://www.flameboss.com/privacy-policy/) | The article documents login and public cook frames. It does not establish verified TLS broker/port, private topic rights or unrestricted commercial app permission. Affiliate terms are not MQTT API terms. Account-specific service terms and secure broker/ACL evidence remain open. |
| MEATER | [published REST API](https://github.com/apption-labs/meater-cloud-public-rest-api), [terms](https://meater.com/terms-of-use), [privacy](https://meater.com/privacy-policy) | Published Beta API supports cloud-backed device snapshots and recommends two requests/minute; no history endpoint is documented. Terms §§1–3 limit use and commercial exploitation. Record clarification or specific applicable permission for the intended public app before enabling it. |

These are engineering observations of the linked documents, not a determination of legal
rights. No written partnership, vendor approval, special account entitlement or physical
validation was supplied. API publication does not override service terms. Do not delay
ordinary authorized development solely because a separate partnership has not been obtained.

## Data flow and privacy

Connections go directly from the phone to the user's selected Home Assistant HTTPS server,
manufacturer REST service, authenticated TLS MQTT broker, or nearby Bluetooth thermometer.
There is no PitTech telemetry relay, account proxy or centrally stored vendor credential.
The existing Railway privacy site identifies the HA OAuth client; the phone intercepts its
exact callback before the authorization code is sent to that site. Railway hosts no telemetry.

Passwords are transient UI input and go only to the fixed manufacturer authentication endpoint.
Android account tokens are AES-GCM encrypted with Android Keystore in no-backup storage.
iOS tokens/HA refresh sessions use device-only Keychain accessibility after first unlock.
Selections/configurations remain on the phone and are excluded from backup and cook archives.
Cook readings, source timestamps and role/assignment history persist locally. Archives contain
hashed local source identities and readings, not tokens/passwords or dedicated raw provider
device-ID fields. Exported device/channel display names can still identify equipment or contain
an entity identifier when a provider supplies no friendly name; an archive is not anonymous.
Forgetting a connection retains historical cooks. HA refresh-token revocation is attempted;
when offline, users should also revoke the original token at their server. Advanced tokens and
other vendors may require revocation in the vendor account. Separate Google ads and explicit
feedback data flows retain their existing disclosures; telemetry is not added to ad or feedback
requests automatically.

Android local HTTP is an explicit development-only option, guarded against public-address
resolution and redirects. It exposes its token on that LAN; production networking is HTTPS/TLS.
iOS HA supports HTTPS only, including user-operated LAN servers with a trusted certificate.

## Actual iOS binary distinction

Draft PR #87 supplies native REST/HA authorization and WebSocket, Flame Boss TLS MQTT, and
Combustion official iOS SDK adapters, local recording, mappings and archive compatibility.
The Devices UI and collection hooks are Debug-only. Public Release currently advertises no
connected-device support. Android work does not establish iOS support or hardware results.
The native iOS SDK adapter can read derived core/surface/ambient. It does not implement
anchored probe-log recovery; specific repeater and background BLE behavior remain unvalidated. Cloud collection is explicitly foreground-only;
there is no persistent background polling entitlement. Manufacturer MQTT is limited to the
configured public cook topic and has no PUBLISH method.

Before a connected public iOS binary exists, retain an unsigned release artifact audit,
resolved package versions/notices, current privacy disclosures, a reproducible reviewer demo,
actual account/hardware evidence and the applicable service permissions. Apple's
[Guideline 5.2.2](https://developer.apple.com/app-store/review/guidelines/#intellectual-property)
requires permission under third-party service terms and evidence when requested. No submission,
TestFlight action or upload is performed as part of this work.
