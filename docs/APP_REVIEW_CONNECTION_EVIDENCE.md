# PitTech third-party connection evidence for App Review

**Status:** pre-release evidence checklist, not a manufacturer endorsement or assertion of blanket legal permission. Refresh each source and the applicable service terms at the time of submission.

## Data boundary

Users explicitly authorize PitTech to access their own grill/thermometer account, device or Home Assistant installation. PitTech connects directly from the user's phone and **stores selected temperature observations locally** in the user's private cooking journal. It does not automatically upload manufacturer credentials or cook telemetry to a PitTech-hosted server. Access is read-only; no controller power, setpoints, modes, Wi-Fi settings or firmware can be changed.

Apple Guideline 5.2.2 requires authorization under applicable third-party terms and evidence upon request: https://developer.apple.com/app-store/review/guidelines/#intellectual-property

## Source-backed evidence index

| Planned source | Primary publisher documentation | Review before shipping |
|---|---|---|
| Combustion | https://github.com/combustion-inc/combustion-ios-ble and https://github.com/combustion-inc/combustion-android-ble | Preserve SDK MIT attribution, validate hardware and platform APIs |
| FireBoard | https://docs.fireboard.io/app/app-api/ | Evaluate current API/service terms, rate limits and commercial use |
| Flame Boss | https://support.flameboss.com/support/solutions/articles/14000129040-connect-to-flame-boss-cloud-mqtt-broker | Confirm present MQTT availability and current third-party usage terms |
| MEATER | https://github.com/apption-labs/meater-cloud-public-rest-api | Check API beta conditions, user cloud bridge, rate limits and app usage terms |
| Home Assistant | https://developers.home-assistant.io/docs/auth_api/ and https://developers.home-assistant.io/docs/api/rest/ | Verify current authorization flows and permitted observational endpoints |

A dated API document is valuable technical evidence, but does not supersede later changes to a vendor's terms. A written commercial partnership is not assumed necessary if existing documentation and license actually permit the intended use. If authorization remains materially ambiguous, resolve it before enabling that particular connector in the public app.

## App Review submission note, when features are implemented

PitTech is a local-first barbecue cooking journal. Optional connected-device features let users authenticate to their own equipment or Home Assistant system and select temperature sensors. Device telemetry is recorded only on the user's phone as part of their cook log and is not relayed to a PitTech server. All manufacturer connections use published SDK/API interfaces and are observational: PitTech cannot change the device operating state. The shipping build includes only the integrations identified in its attached source-specific evidence records.

Do not submit this as a claim for an integration not present in the actual iOS binary. Apple reviewers may require functional demonstration or additional proof of terms permission.
