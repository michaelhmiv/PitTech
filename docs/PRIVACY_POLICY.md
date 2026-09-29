# PitTech Privacy Policy

Last updated: September 28, 2026

This policy describes how PitTech for Android (package com.pittech) handles information. PitTech is a barbecue cook logging app published by Sportfolio on Google Play.

## Information stored on your device

PitTech stores the cook information you choose to enter. This may include cook and dish names, meat type and cut, weight, preparation and ingredients, notes, dates and times, temperature readings, grill and probe names, ratings, and results.

If you select photos, PitTech copies them into the app's private storage on your device. The app may also save a crash diagnostic locally. That report can include a reference code, time, error summary, exception details, thread name, stack trace, and Android process-exit details.

PitTech has no sign-in or online sync. Cook records and photos stay on your device unless you export or share them. Crash reports are saved locally and are not sent automatically. The app does not use a separate analytics or automatic crash-reporting service. In experimental debug and Firebase Dev controller-test builds, BLE scan observations, GATT inventory, protocol responses, bounded passive-notification statistics, and bounded controller debug observations are held in memory for the current controller-test screen session and are not uploaded automatically. Raw controller interrogation data is not persisted as a controller history.

## Feedback sent through PitTech

If you choose **Report a problem** or **Request a feature** in Settings, PitTech sends the title and description you enter plus the PitTech version, Android version, and device model to PitTech's feedback relay hosted on Railway. For a problem report, you can also choose to include the most recent crash report saved on your phone. In experimental debug and Firebase Dev controller-test builds, selecting a controller can automatically inventory its BLE/GATT surface, observe up to eight non-protocol notification channels for up to 10 seconds, and, when a recognized protocol is detected, issue explicitly allowlisted observational requests. Passive notification capture retains bounded counts and change statistics; raw notification payloads are not included in the public report. Some protocols require BLE characteristic or descriptor writes to carry an observational request or enable notifications; these transport-level writes are distinct from controller-setting operations. The automatic probe does not intentionally execute controller-setting, ignition, temperature-target, motor, credential, Wi-Fi provisioning, password, configuration-write, filesystem-write, reboot, firmware/OTA, MCU-command, or unknown RPC operations. It does not read the Mongoose configuration GATT value characteristic because that can disclose the currently selected setting. Nothing is submitted automatically. Before a controller report is submitted, PitTech removes the permanent Bluetooth address, withholds arbitrary advertised names, replaces raw advertisement/manufacturer/service-data/GATT payloads with byte counts and SHA-256 fingerprints, and sanitizes printable values, protocol responses, and debug text. Password/passwd/passphrase/psw/secret/token/authorization/SSID/BSSID/username/email/certificate/private-key/serial-like fields are redacted. The report is previewed before submission and includes only the controller you selected plus app-owned test events. Sanitized printable protocol and controller-state responses may still reveal implementation details, and the resulting GitHub issue is public.

The relay creates an issue in the public PitTech GitHub repository, so no GitHub account is required to submit feedback. The resulting issue can be viewed by anyone. Do not include personal or sensitive information in feedback. PitTech does not add cook records or photos to feedback submissions. Railway processes the network request used to reach the relay, and GitHub hosts the resulting public issue. Their handling of information is governed by their respective privacy terms and GitHub's [Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-privacy-statement).

## Advertising and Google Mobile Ads

PitTech may display one native Google ad in completed cook history after at least four cooks are complete and only when no cook is active. Ads may not always be available.

When PitTech checks privacy choices or requests an ad, Google Mobile Ads and Google's User Messaging Platform may process information such as the device's IP address, ad interactions (including app launches, taps, and video views), diagnostics and performance information, and device or account identifiers such as the Advertising ID and App Set ID. Google may use and share this information for advertising, analytics, and fraud prevention. Google encrypts this information in transit.

PitTech does not include cook records, notes, ingredients, temperature readings, or photos in ad requests. Those remain on your device unless you export or share them. Google's handling of information processed by its advertising and consent services is described in [Google's Privacy Policy](https://policies.google.com/privacy).

When a privacy choice is required, PitTech uses Google's consent form before requesting ads. Where required, the Settings screen provides a Privacy options control so you can revisit your choices.

## Exporting and sharing

PitTech uses Android's system photo and document pickers when you choose images or files. You can export cook information as an Excel workbook, CSV, or ZIP archive and restore a ZIP archive. Exported files are written to the destination you choose.

If you open or share a file with another app or service, that recipient may receive a copy and handle it under its own privacy policy. PitTech does not control separate copies or the recipient's practices.

## Retention and deletion

Cook records and photos remain in PitTech's private app storage until you delete them in the app, clear PitTech's app data in Android settings, or uninstall PitTech. Android app-data backup is disabled. Exported files and copies shared with other apps remain at their chosen destinations until you delete them there.

A locally saved crash report stays on your phone until a newer report replaces it, you delete it in Settings, or you clear PitTech's app data or uninstall the app. If you submit feedback, the relay does not maintain a separate feedback database; the created public GitHub issue is retained under GitHub's retention and deletion policies. Railway may retain operational request logs under its service policies. Google handles information collected through its advertising and consent services under its own policies and controls.

## Security

PitTech stores cook records, photos, and crash diagnostics in Android's private app storage. Cook records and photos are not uploaded by the feedback flow. Feedback is transmitted over HTTPS to PitTech's Railway-hosted relay and then to GitHub. A crash report is included only if you select it for a problem report. Controller diagnostic data is included only after you review and submit the report. The public-report representation removes the selected controller's permanent Bluetooth address, withholds arbitrary advertised names and raw advertisement/GATT payload bytes, redacts private LAN addresses and credential-like values, and can include service metadata, payload lengths and hashes, sanitized printable GATT previews, sanitized protocol/RPC responses, sanitized bounded debug observations, capability fingerprints, firmware/device metadata, and timestamped app-owned test events. The relay keeps its GitHub credential on the server and does not place that credential in the Android app. The app does not claim separate encryption of its local database or exported files. Protect access to your device with its screen lock and other security settings.

## Children and intended audience

PitTech is intended for adults who want to record barbecue cooks. Its declared Google Play target age is 18 and older, and it is not designed for children. PitTech does not ask for a user's age.

## Changes to this policy

If PitTech's data practices change, this policy will be updated. The updated date appears at the top of this page.

## Contact

For privacy or support questions about PitTech, email [sportfolioholdings@gmail.com](mailto:sportfolioholdings@gmail.com).
