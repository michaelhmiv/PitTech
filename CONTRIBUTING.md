# Contributing to PitTech

Thanks for helping make PitTech a practical, dependable barbecue cooking journal.

## Before changing code

1. Search existing issues and pull requests. For a major feature or data migration, open an issue outlining the user problem and intended behavior.
2. Make focused changes from current `main` on a feature branch.
3. Add or update tests for behavior, persistence, accessibility, and relevant failure paths.
4. Run `./gradlew testDebugUnitTest assembleDebug`. Changes to cook persistence or navigation should also pass Android emulator tests.
5. Open a pull request with the user impact, supported platforms, screenshots where useful, test evidence, and relevant data migration considerations.

## Release communication

Every user-visible change must include a short, plain-language release-note entry in the pull request and, where appropriate, `CHANGELOG.md`. Mark Android-only or iOS-only features accurately. Fixes that are not user-visible may be designated “No user-facing notes”.

Maintainers review and update the curated [Google Play What's New](distribution/play/whatsnew/whatsnew-en-US) and [iOS What's New](distribution/app-store/whats-new-en-US.txt) before a store submission. Merging a PR does **not** automatically publish a new store release.

## Read-only equipment policy

Device and cloud connectors may retrieve observations from authorized sources. They must not provide device-state mutation (setpoints, ignition, power, auger, fan, Wi-Fi credentials, firmware or configuration). Authentication and telemetry subscription packets are allowed only where required for observation. See [Connection policy](docs/CONNECTION_POLICY.md).

Do not submit manufacturer credentials, reverse-engineered secrets, private keys, device identifiers, or unredacted logs. New third-party integrations must have an appropriate authorization basis and a reviewed privacy impact.

## Dependencies, security and privacy

- Prefer minimal, maintained dependencies and document their licenses.
- Never put tokens or passwords in logs, crash reports, tests or repository fixtures.
- Preserve existing cook history and archive compatibility when changing Room models or exports.
- Report suspected vulnerabilities privately as described in [SECURITY.md](SECURITY.md), not in public issues.

## License

By contributing code or documentation, you agree to submit it under the project's [Apache-2.0 license](LICENSE), and confirm you have the rights needed to contribute it. Original third-party work retains its own licensing requirements. Changes to reserved logos, icons, or branding assets are reviewed separately; see [BRANDING.md](BRANDING.md).
