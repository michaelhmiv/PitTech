# Changelog

Important, user-visible changes to PitTech are recorded here. GitHub Releases provide version-specific technical details; Google Play and App Store update descriptions are separately reviewed and platform-specific.

This file begins with a truthful **development baseline** rather than inventing releases or historical version tags.

## [Unreleased]

### Added
- A short welcome the first time you open PitTech (issue #187): one screen with the PitTech pig, a sentence on what the app does, and three quick rows — live cook tracking (connect a thermometer or log temps by hand), recipes, playbooks & reminders, and Share & Discover. "Start cooking" takes you to Meals, and the screen also offers backup restore and thermometer connection up front. It shows once and never again; a pending crash report still shows first.

### Changed
- One reminder system, one vocabulary (issue #172): the quick timer, Reminders rules and their notifications now all speak the same language — "Log it" records what you did, "Snooze 10 min" asks again in ten minutes, and "Dismiss" ends a reminder without recording anything. The check-in that pops up when a timer fires offers all three explicitly, and simply closing it (or pressing Back) no longer snoozes it for an hour behind your back — the reminder just stays there until you choose. When a reminder was set from a milestone in a previous cook, its check-in and notification offer to record that very action in one tap, and "Record an action" starts from the action that actually fired instead of always starting at Wrap. The Wrap button (and the other phase buttons) on a food card now records the action the moment you tap it, with "Add note" offered afterwards instead of a form that recorded nothing until a second Save. Clock-time reminders are set with date and time pickers rather than typing a date, tapping a reminder notification opens that reminder's own check-in, and tapping a timer notification no longer throws away where you were in another cook.
- The cook's Live tab is re-ranked for a cook in progress (issue #172): a Live Zone poster at the top shows elapsed time, pit and setpoint, the latest temperature for every dish, and your targets; pending reminders sit right under it with their Log now button; and the old "Cook tools" pile is now labeled panels in cook order — Guidance, Reminders, Alerts, Checklist, Timers, Targets, Results, and Export & share — each headed with its current state (for example "Alerts · 1 needs attention") so nothing is hidden without a summary. Record results and Pause recording are plain buttons at the bottom of the tab instead of living inside the collapsed tools, and the alerts and playbook tools each appear once instead of in two places. Nothing has been removed; preparation details and cook details are still there under their own panels.
- Night Service arrives on Meals first (issue #172): the whole app now uses the dark Night Service appearance on every screen — there is no separate light appearance — and the Meals tab leads with your live cook as a large, easy-to-read card showing the pit temperature (or elapsed time), food temperature, and set time at a glance. The PitTech pig stays as a small mark at the top of Meals instead of a large picture, a first (empty) Meals screen now explains the cook log and offers backup restore on iPhone as well as Android, and Settings now simply states the Night Service appearance instead of offering a theme picker. Nothing you could do before has moved or gone away.

### Changed
- Alerts and Devices get out of your way mid-cook (issue #172). Tapping a cook-alert notification now opens that cook with the Alerts sheet up and the firing alert highlighted, instead of landing on the cook with the alert nowhere in sight; the Live Zone strip on the cook shows how many alerts need attention and names the ones you have acknowledged, so acknowledging — from the notification or the sheet — is confirmed right on Live; and "Send test alert" now shows its feedback inside the app instead of posting a real high-priority notification. The Devices sheet is one three-step flow — Connect, Attach, Assign — the same from a cook and from More, on Android and iPhone: probe-assignment edits are kept as drafts if you leave mid-edit and come back, the Monitor temperatures switch explains itself when a connection is not attached yet ("Attach this connection to the cook first"), and "No devices found" only appears once discovery has actually finished instead of flashing while the app is still looking.

### Fixed
- The first-run empty Meals card on iOS is now its own accessibility container, so assistive tech (and the app's UI tests) can find the card as a distinct element instead of only hearing its combined text.
- Home Assistant sign-in (issue #172): the round trip now makes a single token exchange instead of authorizing and then reconnecting, shows progress while it connects, and has a Cancel button on Android to match iPhone. If the sign-in page tries to wander off your server, the navigation is blocked and the sheet says so; if the connection fails — including a certificate problem — the sheet shows what happened with the server address and keeps the form you filled in, instead of closing silently or failing the whole flow.
- Draft Android and iOS subscription refresh/restore keeps the server-verified store environment and an account-bound offline cache. Switching accounts or receiving a late purchase response cannot reuse another account’s Pro access or purchase-management reference.
- Android's unified-note UI test waits for the meal controls to finish loading before tapping the temperature shortcut.
- Draft online billing uses Google's documented subscription lookup/acknowledgement routes and refreshed signing keys, and accepts renewal notifications only from the configured verified push identity. Store setup and live verification remain required before purchases are available.
- A failed private-sync apply restores existing photo bytes instead of deleting the original file during rollback on either platform.
- Draft private sync offers an account-bound choice to reconcile while the app is open, after explicit library attachment. Remote Finish/deletion cancels that meal's local timers, recording and notifications.
- Draft private sync on Android and iOS accepts manual-temperature corrections while preserving automatic measurements and recoverable conflicting edits.
- Offline recipe branches keep their original contents and parent links when two devices save the same version number. Branch choices are labeled separately; Android upgrades its local database without replacing cooking history.
- Android cookbook editing dismisses the keyboard, reveals its private editing preview and keeps the publishing flow reachable at enlarged text sizes.

### Added
- iOS post-launch draft: prepare optional Sign in with Apple as an additional account authenticator alongside email code/link sign-in, with attach-while-signed-in from account settings. Android provider sign-in follows separately for parity; account access stays disabled until the complete release is ready.
- Android post-launch draft: prepare optional Sign in with Google as an additional account authenticator alongside email code/link sign-in, with attach-while-signed-in from account settings. iOS Sign in with Apple is its counterpart; account access stays disabled until the complete release is ready.
- Android and iOS online build profiles keep staging sign-in links, sessions, cloud retries, community caches and subscription state separate from production. Shared backend/native checks inspect the generated test-app identities and links; online release flags remain off until deployed acceptance is complete.
- A private one-off database bootstrap prepares reviewed migrations and separate restricted API, lifecycle and sync logins, with real-Postgres checks for idempotency and unexpected privileges. This prepares online deployment; it does not enable accounts by itself.
- PitTech Pro personal views on both clients, behind the existing subscription gate: compare your recorded cooks of a cut, see the attempt you rated best, review timing across your recorded cuts and weights, note wrap and rest differences, and align recorded temperature curves at Food on or Wrap — all computed on-device from your own library, working offline, with honest notes wherever cooks are missing weights, anchors, or ratings.
- PitTech Pro Year in Smoke recap builder on both clients: pick a period, choose exactly which cooks and sections appear, preview the exact shareable card, then share it explicitly. Unselected cooks never appear, and including a cook in a recap never publishes anything.
- PitTech Pro customization on both clients: share-card themes, contrast-verified chart palettes including a color-vision-safe set, restrained named-profile presentation, and app-icon variants — every look previewed before applying, with a readable free fallback when Pro ends that keeps your saved exports and recorded cooks untouched.
- Draft PitTech Pro paywall and subscription gating on both clients: contextual paywall at Pro entry points (personal analysis, advanced curves, Pro themes, More) with store-localized monthly pricing, Restore purchases, store subscription management, and privacy/terms links; StoreKit 2 and Play Billing purchase flows verified server-side against the PitTech account; entitlement-state UI (entitled, paywall, neutral loading, expired free fallback) with zero ad requests under entitlement. Purchases stay gated on account sign-in and the store product ID stays owner-configured; the Pro views behind these gates land in slice 18.
- Draft iOS and Android private-sync merge foundation: retain conflicting edits and deletion markers consistently. Sync transport and backup remain unavailable while implementation and verification continue.
- Draft iOS and Android account deletion: review server removal separately from the retained device journal, confirm explicitly, and recover an interrupted request after sign-out. Accepted requests also clear private account-bound device metadata with recoverable cleanup. Local-journal removal and remaining lifecycle verification are still open; account/community access stays disabled.
- Draft iOS and Android community controls: privately report a publication or publisher, recover an interrupted report, check its status, and block or unblock publishers without changing your saved journal. Community access remains disabled pending verification.
- Backend and web draft: prepare reviewed community publishing, private reports and blocks, and recoverable server account deletion that preserves other cooks’ independent copies. Native lifecycle controls and release verification remain in progress; account/community access stays disabled.
- Draft Android publishing fix: keep inherited free-text ingredient quantities when starting another cook from a saved recipe. iOS already preserves this information.
- Draft iOS and Android community work: review public-copy terms before publishing, compare a saved recipe with its shared source, and preserve fractional recorded ratings. Community controls remain disabled.
- Draft iOS and Android owner editing: preview a private editing copy of your publication, revise instructions, and confirm a new public revision while preserving recorded results and source credit. Community controls remain disabled.
- Draft iOS and Android publishing: review selected public content and credit, recover interrupted uploads, preview cookbook visibility changes, and confirm publication removal. These controls remain disabled pending the complete community phase.
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
