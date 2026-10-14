# Changelog

Important, user-visible changes to PitTech are recorded here. GitHub Releases provide version-specific technical details; Google Play and App Store update descriptions are separately reviewed and platform-specific.

This file begins with a truthful **development baseline** rather than inventing releases or historical version tags.

## [Unreleased]

### Added
- Optional dish ratings and cook-again choices, independently editable sharing drafts and saved meal collections on Android and iOS. Drafts own their reference photos and leave the original meal unchanged.
- Meals with independent dishes, editable saved recipe revisions and combinations drawn from different previous meals (Android and iOS).
- Flexible preparation notes, ingredients and persistent task timers, with separate previous-cook references.
- Optional time and historical-action reminders, plus connected-probe thresholds on Android and iOS.
- Optional read-only Beta connections for Home Assistant, Combustion, FireBoard, Flame Boss and MEATER in Google Play, TestFlight and App Store builds. Android supports opted-in foreground-service monitoring; iOS monitoring runs while the app is open.
- A public-facing project overview, contribution and security-reporting guidance, and a documented release-note process.
- The Apache-2.0 source-code license, separately reserved branding asset guidance, and third-party notice references.
- A five-integration roadmap approving all four manufacturer SDK/API candidates and the optional user-operated Home Assistant bridge for development, with separate public-release terms validation. Legacy GrillirG cook histories remain supported without a public direct connector.

### Changed
- Both apps now use Meals, Recipes, Discover, Insights and More. Devices and settings live under More, with device access during a cook. Recipe filters are quieter and meal selection is explicit; iOS gains matching light/dark surfaces and a reviewed feedback flow.
- Live dish cards show recorded stages, readings, recent actions and enabled reminders. Finishing offers selected recipe saves or leaving without saving.
- Clarified the product direction: observational, read-only temperature monitoring only; no grill or Wi-Fi configuration.
- Connected release documentation to Google Play and Firebase distribution.

### Fixed
- Corrected the repository's iOS privacy-policy source to match the currently published iOS behavior.

## Development baseline — October 12, 2026 (not a tagged release)

Recent merged changes represented in the current Android development history include:

- Multi-food live cook cards and a less cluttered, food-scoped timeline ([#59](https://github.com/michaelhmiv/PitTech/pull/59)).
- Unified notes, photos and temperature logging with expanded charts and active-cook update recovery ([#56](https://github.com/michaelhmiv/PitTech/pull/56)).
- A more reliable Finish cook action ([#57](https://github.com/michaelhmiv/PitTech/pull/57)).
- Saved meal recipe revisions and portable recipe assets ([#60](https://github.com/michaelhmiv/PitTech/pull/60)).

This section documents development history only. It does **not** imply a public GitHub Release or that all features are available on iOS.

[Unreleased]: https://github.com/michaelhmiv/PitTech/commits/main/
