# PitTech App Store metadata (English U.S.)

Prepared from the current iOS source on October 12, 2026. This copy distinguishes the current iOS optional read-only beta telemetry connectors from Android-only controller features.

| Field | Draft | Limit |
|---|---|---:|
| Name | PitTech | 30 characters |
| Subtitle | Barbecue Cook Journal | 30 characters |
| Promotional text | Plan cooks, save recipes, and record notes, photos, and temperatures you enter—even offline. | 170 characters |
| Category | Food & Drink | App Store Connect choice |
| Support URL | https://github.com/michaelhmiv/PitTech/issues | URL |
| Marketing URL | https://github.com/michaelhmiv/PitTech | URL |
| Privacy policy URL | https://pittech-privacy-production.up.railway.app/ | App Store Connect app-level field |
| Keywords | barbecue,smoker,BBQ,grill,cook log,temperature,meat,recipe,smoking,food journal,thermometer | 100 characters |

## Description

Keep every barbecue cook in one place.

PitTech is an offline-first cook journal for planning, logging, and learning from your cooks. Create a cook, organize dishes, and add notes, photos, and temperature readings as the cook unfolds. Charts show the values you recorded.

• Track preparation, cooking, resting, and finish milestones.
• Keep notes and photos with each cook.
• Enter temperatures manually and view them on a chart.
• Save recipes and compare them with earlier cooks.
• Set activity timers and optional local reminders.
• Export or restore a ZIP backup in the Files app.

Your cook library stays in PitTech's local app storage. No account is required. Core cook logging works offline. Network access is used for Google's consent and advertising services when available.

Optional beta, read-only temperature connections are available for supported Combustion thermometers, Home Assistant, FireBoard, Flame Boss, and MEATER services. Connections require compatible hardware/accounts and user setup; coverage varies. Automatic monitoring may pause when iOS backgrounds the app. PitTech does not control grill setpoints, ignition, or equipment configuration.

## App Review notes

No account or sign-in is required. Cook logging, saved recipes, and charts are available offline. Temperature readings can be entered manually or received from optional beta read-only connections to compatible equipment and services, including Bluetooth thermometers and user-authorized vendor clouds. These integrations are not required for core offline logging. Photos can be selected with Apple's system photo picker or taken with the camera. Reminders are local notifications scheduled on this device after permission is granted. Google's User Messaging Platform refreshes consent information, and Google Mobile Ads may request ads when consent allows. The app does not send cook records or readings to PitTech servers.

## Listing and review follow-up

- The current App Store Connect category is Food & Drink. The current App Information subtitle is within Apple's 30-character limit.
- Keep the existing manual release selection.
- App Review contact name, phone, and email must be entered by the owner in App Store Connect.
- Capture current iPhone and iPad screenshots from the simulator workflow; review them for legibility before adding them to the listing.
- Confirm App Privacy disclosures against the final Xcode privacy report and resolved SDK manifests before submission.
