# Changelog

Important, user-visible changes to PitTech are recorded here. GitHub Releases provide version-specific technical details; Google Play and App Store update descriptions are separately reviewed and platform-specific.

This file begins with a truthful **development baseline** rather than inventing releases or historical version tags.

## [Unreleased]

### Added
- Draft community work: public recipe and cookbook links open native previews on both platforms; saved variations keep their pinned source after upstream edits or removal. Community access remains disabled pending the complete phase.
- Android and iOS post-launch draft: browse community recipes without signing
  in, inspect selected photos and recorded measurements, and preview recipes
  before saving your own copy or starting a new meal. Community access remains
  disabled until the complete release is ready.
- Backend post-launch draft: prepare public recipe discovery, deliberate
  publishing, named or anonymous credit, independent forks and likes. Public
  services stay disabled until the complete community and privacy features are ready.
- Android and iOS post-launch draft: prepare optional email sign-in, secure
  session recovery and sign-out while keeping the local cookbook. Account
  access stays disabled until community recipes, private sync and deletion are ready.
- iOS draft: submit a shared code or link from the keyboard and dismiss the
  keyboard to reach import actions and errors at enlarged text.
- Backend post-launch draft: prepare optional email code/link sign-in, secure
  session rotation and sign-out. Account screens and verified email delivery
  are still pending; authentication remains disabled by default.
- Android and iOS post-launch draft: preview and upload selected Cook Packs by
  code, retry or cancel transfers, manage owned codes, and open shared links
  through a selective import preview. Web fallback and app-link association
  handlers are included; production domain/device verification is still required.
- Android and iOS post-launch draft: prepare an anonymous Cook Pack code relay
  with explicit owner management, scoped pack validation and expiry. Sharing by
  code remains unavailable in the apps until the client and release gates pass.
- Android and iOS: Preview and share PNG cards for selected recipes or meals, with an explicitly chosen photo/chart, optional card note and recorded verdict. Canceling preserves the sharing draft.
- Android and iOS: One Show guide setting across Live, Timeline and Charts, with per-dish source scope, recorded reference measurements and optional channel matching. Hiding the guide keeps reminders active.
- Android and iOS: Choose whether to include a finished recorded meal in a Cook Pack; recipients can add it to history separately from saving recipes.
- Share selected recipes or meal collections as Cook Pack files, and preview individual recipes before saving them on Android and iOS. Packs own their guide data and location-free photos. iOS full-backup restore uses bounded disk staging and the shared Android size limits.
- Readings CSV and Excel workbooks are available on iOS. Both platforms write bounded worksheet files, preserve long text in a Long Values sheet and keep distinct probe channels separate in summaries.
- Optional dish ratings and cook-again choices, independently editable sharing drafts and saved meal collections on Android and iOS. Drafts own their reference photos and leave the original meal unchanged.
- Meals with independent dishes, editable saved recipe revisions and combinations drawn from different previous meals (Android and iOS).
- Flexible preparation notes, ingredients and persistent task timers, with separate previous-cook references.
- Optional time and historical-action reminders, plus connected-probe thresholds on Android and iOS.
- Optional read-only Beta connections for Home Assistant, Combustion, FireBoard, Flame Boss and MEATER in Google Play, TestFlight and App Store builds. Android supports opted-in foreground-service monitoring; iOS monitoring runs while the app is open.
- A public-facing project overview, contribution and security-reporting guidance, and a documented release-note process.
- The Apache-2.0 source-code license, separately reserved branding asset guidance, and third-party notice references.
- A five-integration roadmap approving all four manufacturer SDK/API candidates and the optional user-operated Home Assistant bridge for development, with separate public-release terms validation. Legacy GrillirG cook histories remain supported without a public direct connector.

### Changed
- iOS: Hold briefly to scrub the temperature chart, so ordinary vertical swipes can scroll the meal page. The temperature slider remains available.
- Both apps now use Meals, Recipes, Discover, Insights and More. Devices and settings live under More, with device access during a cook. Recipe filters are quieter and meal selection is explicit; iOS gains matching light/dark surfaces and a reviewed feedback flow.
- Live dish cards show recorded stages, readings, recent actions and enabled reminders. Finishing offers selected recipe saves or leaving without saving.
- Clarified the product direction: observational, read-only temperature monitoring only; no grill or Wi-Fi configuration.
- Connected release documentation to Google Play and Firebase distribution.

### Fixed
- Large sharing drafts reopen on Android using bounded SQLite reads, preserving every recorded sample and the existing archive format.
- Corrected the repository's iOS privacy-policy source to match the currently published iOS behavior.

## Development baseline — October 12, 2026 (not a tagged release)

Recent merged changes represented in the current Android development history include:

- Multi-food live cook cards and a less cluttered, food-scoped timeline ([#59](https://github.com/michaelhmiv/PitTech/pull/59)).
- Unified notes, photos and temperature logging with expanded charts and active-cook update recovery ([#56](https://github.com/michaelhmiv/PitTech/pull/56)).
- A more reliable Finish cook action ([#57](https://github.com/michaelhmiv/PitTech/pull/57)).
- Saved meal recipe revisions and portable recipe assets ([#60](https://github.com/michaelhmiv/PitTech/pull/60)).

This section documents development history only. It does **not** imply a public GitHub Release or that all features are available on iOS.

[Unreleased]: https://github.com/michaelhmiv/PitTech/commits/main/
