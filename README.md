# PitTech

**Keep the whole cook in one place.**

PitTech is an open-source barbecue cooking companion. Record what you cook, track different foods in the same session, add photos and temperature readings, and look back at what worked.

Built for backyard cooks who want a useful cooking journal—not another required account.

## What you can do

- **Track a complete cook:** plan, prepare, cook, rest, and review multiple foods in one session.
- **Log the details:** add notes, photos, temperatures, ingredients, cooking milestones, and results without cluttering the timeline.
- **See your progress:** explore temperature charts, filter activity, and review each food's history.
- **Learn from previous cooks:** save editable recipes and playbooks, compare sessions, and retain lessons for next time.
- **Plan and remember:** use preparation checklists, cooking reminders, and optional alerts where supported.
- **Keep your data:** cooking records are stored locally; export or restore them using supported file formats.

Feature availability varies by platform and build. The Android app has the broader feature set; the native iOS app is still being developed and does not currently support automatic grill connections.

## Optional temperature connections

PitTech's long-term goal is **read-only temperature monitoring**: automatically recording supported thermometer and grill readings without changing the equipment's operation.

- Connections are optional. Manual cooking logs work without a device or internet connection.
- Experimental controller diagnostics are limited to development/test builds. Public Android builds do not currently advertise validated connected-grill support.
- **Home Assistant Beta** will be an optional bridge for owners of self-hosted Home Assistant installations. It can read user-selected temperature sensors, including sensors supplied by user-installed community integrations. PitTech does not bundle or operate those integrations.
- A legacy experimental GrillirG monitor exists in development builds, but **no direct GrillirG connector is planned for public release**. Existing cook history will be preserved, and unsupported manufacturer code must be excluded from public artifacts.
- **Direct Beta integrations:** Combustion, FireBoard, Flame Boss, and MEATER are candidates for manufacturer-supported access; each must pass its own licensing/terms, device-compatibility, security, and testing checks before release. Home Assistant is the fifth planned connection. None is currently advertised as verified or publicly available.
- **PitTech is not a grill controller.** It must not ignite, shut down, change setpoints, configure Wi-Fi or firmware, or otherwise manipulate cooking equipment. Always use the manufacturer's controls and safety guidance.

See [Connection principles](docs/CONNECTION_POLICY.md) and the [unified telemetry implementation roadmap](docs/UNIVERSAL_TELEMETRY_ROADMAP.md) for the read-only contract, historical-data preservation, Home Assistant setup requirements, and direct API release gates.

## Platforms and availability

**Android:** The primary application is built with Kotlin, Jetpack Compose, and Room. Google Play closed testing and Firebase development distribution are separate channels; installation access depends on the current test program.

**iOS:** A native SwiftUI application is under development, with a smaller feature set. It does not currently include the Devices screen or controller monitoring. Do not assume Android and iOS features are identical.

There is no PitTech account requirement for ordinary cook logging.

## Your data and privacy

Cook records live in app-private storage on your device. Import, export, and sharing are user-directed. Optional cloud integrations and advertising may contact external services; consult the [privacy policy](https://pittech-privacy-production.up.railway.app/) for current platform-specific details.

## For developers

Build Android with JDK 17 and the Android SDK configured:

~~~bash
./gradlew testDebugUnitTest assembleDebug
~~~

Run the Android emulator/instrumentation checks with `scripts/run-emulator-ui-tests.sh` and the documented SDK image. The [Android checks workflow](.github/workflows/android-build.yml) is the CI reference.

- [Contributing](CONTRIBUTING.md)
- [Security and private vulnerability reporting](SECURITY.md)
- [Changelog](CHANGELOG.md)
- [Releases and store update notes](docs/RELEASE_PROCESS.md)
- [Product and cook-experience documentation](docs/COOK_EXPERIENCE.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

PitTech source code is offered under the [Apache License, Version 2.0](LICENSE). The PitTech name, logo, and enumerated branding artwork are reserved separately; see [Branding and asset rights](BRANDING.md). Third-party materials retain their own notices and terms. See `NOTICE` and `third_party/`.

## Support

Use [GitHub Issues](https://github.com/michaelhmiv/PitTech/issues) for bugs and feature requests; please remove account information, device identifiers, Wi-Fi details, and personal records from reports. For a security vulnerability, use the private route in [SECURITY.md](SECURITY.md), **not** a public issue.

PitTech is independently developed and is not endorsed by grill or thermometer manufacturers.
