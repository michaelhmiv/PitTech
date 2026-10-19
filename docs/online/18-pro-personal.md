# Slice 18 — Pro personal analysis, customization, and Year in Smoke

Replaces the slice-16 placeholder behind the Pro gates with the real Pro
views on both clients: personal analysis over the local library, advanced
curve comparison, a scoped Year in Smoke / periodic recap builder, and the
curated customization set (share-card themes, accessible chart palettes,
restrained profile presentation, app-icon variants). Native-only plus docs:
the backend this slice consumes is done and untouched.

## 1. Placement map

Access flows through the existing slice-16 machinery only
(`ProSubscriptionCoordinator` / `SubscriptionManager` + the shared paywall
presentation). There is no second gate: every view below renders from the
`.pro` / `PRO` destination.

| Entry | iOS destination | Android destination |
|---|---|---|
| Insights → Personal analysis | `ProPersonalAnalysisView` | `ProPersonalAnalysisDialog` |
| Insights → Advanced curves | `ProAdvancedCurvesView` | `ProAdvancedCurvesDialog` |
| Insights → Community benchmarks | `CommunityBenchmarksView` (slice 17, unchanged) | `CommunityBenchmarksDialog` (unchanged) |
| Share-card preview → Pro themes | `ProCustomizationView` | `ProCustomizationDialog` |
| More → PitTech Pro | `ProMoreHubView` (hub to the four destinations + recap) | `ProMoreHubDialog` |
| Recap (from analysis or hub) | `ProRecapBuilderView` (pushed, not a new gate entry) | `ProRecapDialog` (dialog step, not a new gate entry) |

The recap deliberately adds no `ProPlacement` case: it is a step inside the
entitled analysis and hub surfaces, so the slice-16 contract is unchanged.
Personal entry points never depend on community state — thin community cells
neither block nor shame them (proven by
`testCommunityInsufficientDataDoesNotBlockPersonalEntryAtBothTextSizes`).

## 2. Personal analysis (local, entitlement-gated)

### Questions and their v1-calculation sources

All four questions read `InsightReport` (`calculationVersion` 1) computed on
device over the local library, plus cook titles joined from the same library
(the v1 archive carries no titles). No publishing, no account session beyond
what slice 16 requires for entitlement, no network: offline Pro access via
the verified cache works.

| Question | Section | v1 sources |
|---|---|---|
| How do my cooks of a cut compare? | Cut groups, one card per recorded cut | `heatingMillis`, `weightValue`/`weightUnit`, `methods`, channel `units` per dish |
| Which recorded attempt did I rate best? | Best-rated attempt | Declared `rating` only (max wins; rating ties keep cook order); `cookAgain`, `hasResultNotes` echoed |
| How does timing change across my cuts and weights? | Timing groups | Per-cut median + range over recorded `heatingMillis`; weight coverage counts |
| Where do wrap/rest methods differ? | Wrap and rest differences | `wrapAt`/`foodOnAt` offsets, `restMillis`, `holdMillis` per dish |

Advanced curves add explicit alignment: the user picks the anchor (Food on
or Wrap) and which measured channels to compare. Points are recorded
readings only, normalized to °F so °C and °F cooks compare directly
(70 °C renders as 158 °F). Dish-scoped channels align to their dish's
anchor; cook-scoped (pit) channels align to the cook's earliest recorded
anchor across dishes. Channels whose cook lacks the anchor are excluded
with a note — never estimated.

### Honesty rules (tested)

- Coverage limitations sit next to every result (counts of dishes with
  heating time, weights, ratings, wraps, rests, holds).
- Missing anchors/weights stay missing. v1 reports `0` (not null) for a
  measured phase with no intervals; a zero span renders as "not recorded"
  and stays out of figures.
- Personal bests use the user's declared rating/criterion; the word
  "rated" appears and the word "score" never does.
- No causal or safe-doneness language anywhere (mirror of the slice-15/17
  wording guards, plus a carve-out so the required "color-vision safe"
  palette name passes).
- No zero-value dashboard: zero-cook libraries get an honest empty state
  ("No recorded cooks yet" + what will unlock); one-cook libraries get the
  available sections plus a "record a second cook" note instead of cut
  comparisons.
- No free tool from the slice-15 inventory is paywalled or degraded, and
  no new metric changes an existing free chart's meaning (the free
  comparison helper output is pinned on the same fixture).

## 3. Year in Smoke / periodic recap

- Built from the local library with user-controlled scope: period (cook
  years in UTC + "All recorded cooks"), per-cook inclusion toggles, section
  toggles (recorded totals, recorded milestones, best recorded rating),
  and an optional note.
- Full preview precedes any share affordance: the builder shows the exact
  rendered 1080 × 1350 PNG plus the card slots, coverage, and privacy
  notes. The share action exists only on the preview and is an explicit
  user step (`ShareLink` on iOS, a chooser `ACTION_SEND` on Android).
- The card reuses the share-card v1 content schema and the existing native
  renderers (hero `none`): photo-free cards render, long titles/notes
  truncate in the image only, milestones cap at 5 with an honest
  "showing 5 of 6" note. Recap rendering resolves the entitlement-effective
  theme and palette.
- Privacy: only selected cooks contribute (unselected cooks, milestones,
  and stats are excluded — asserted with values); recap inclusion changes
  no publication state and never uploads; rendering works offline up to
  the explicit share action.

## 4. Customization

Curated inventory (ids and colors pinned by
`contracts/fixtures/pro-personal-v1.json`, mirrored in both clients):

- **Share-card themes (4):** Classic (free default), Ember, Sage night,
  Parchment. The theme set extends share-card validation; the renderers
  resolve theme + palette ids to paint with Classic fallback for unknown
  ids. Applied themes render through the share-card preview and the recap
  builder via entitlement-resolved overrides that never mutate the draft.
- **Chart palettes (4):** Classic, Color-vision safe, High contrast, Sage
  night chart. Every color is contrast-verified by test (≥ 3.0 against
  its intended background; theme text colors ≥ 4.5). Palettes apply to
  rendered cards and to the in-app Advanced curves series.
- **Profile presentation (restrained):** three accent options + standard /
  compact cookbook layout, for named public cookbooks only. Anonymous
  content always renders the unstyled classic treatment — no accent, no
  Pro link — regardless of stored selection or entitlement (pure-function
  contract, tested in every combination).
- **App icons (3):** Classic, Ember, Midnight.
  - iOS: `UIApplication.setAlternateIconName` with the `ProEmber` /
    `ProMidnight` names declared in `CFBundleAlternateIcons`
    (`ios/PitTech/Info.plist`, a checked-in source — the generated
    project is untouched and `project.yml` needs no change). Final icon
    art is owner-gated (see remains list); until it ships, applying a Pro
    icon keeps the primary icon with an honest notice, and the stored
    selection is retained.
  - Android: launcher `activity-alias` mechanism (the platform-supported
    approach; there was no established in-repo pattern). `MAIN`/`LAUNCHER`
    moved from `MainActivity` to three aliases (Classic enabled by
    default, Ember/Midnight disabled), all targeting `MainActivity`;
    deep links and share/archive intents stay on the activity so icon
    selection never affects routing. Selection toggles exactly one alias
    via `PackageManager.setComponentEnabledSetting`. Variant art reuses
    the shipped foreground/monochrome drawables on Ember/Midnight
    backgrounds.

Every customization is preview-then-apply. Preferences persist in the
existing per-platform stores (iOS: CookStore library preferences, the same
store as theme mode and units; Android: `pittech-preferences`, the same
file) and never modify measured data. Expired or unsupported entitlement
resolves to the readable free fallback: Pro looks stop applying, stored
selections are retained (resubscribing restores them), prior exports are
never destroyed, and an active cook is never interrupted.

## 5. Entitlement-state matrix (slice-16 machinery, reused exactly)

| State | Personal views |
|---|---|
| Entitled (incl. offline via verified cache) | Real analysis, curves, recap builder, customization |
| Non-Pro / signed-out | Existing contextual paywall with preview; no Pro figures or content rendered; no second-subscription offer when the cache says covered |
| Unknown / loading | Neutral loading; no flash of paywall or Pro content; previously entitled users see no paywall |
| Expired | Free fallback: Classic rendering, free tools intact, recorded cooks/exports untouched, nothing already exported deleted |

Entitled users trigger zero ad requests (the slice-16 ad gate; asserted
again in this slice's state tests, not reimplemented).

## 6. Tests

Shared fixture `contracts/fixtures/pro-personal-v1.json` (empty / single /
many libraries with fixed timestamps, customization inventory with exact
hex, recap scopes with expected slots) follows the slice-17 pattern:
canonical copy plus byte-identical `app/src/main/assets/` and
`app/src/test/resources/` copies; iOS consumes the canonical file via a
`project.yml` test-target entry. Backend copy-identity enforcement is
untouched in this native-only slice (see remains list); natives assert the
contract version and pin the inventory.

- iOS unit (`ProPersonalTests`, 21 tests): empty/sparse states, cut
  comparison values, median/range timing, rating-led bests, missing-data
  honesty, v1 reuse + free-meaning preservation, Food on / Wrap curve
  alignment with °C→°F normalization and anchor-less exclusion, recap
  scope exclusion + schema + purity, preview-apply persistence, expired
  fallback with retained selections, contrast thresholds, anonymous
  projection, icon-name mapping, theme fallback, offline-cache state
  matrix, wording guards.
- iOS UI (`ProPersonalUITests`, 11 tests × both text sizes): real
  questions rendered, curve alignment + source selection, theme/palette/
  icon preview-apply-persist, recap preview-before-share, honest empty
  state, paywall without Pro content, neutral loading, expired fallback,
  community-insufficient non-blocking, rendered-wording sweep, Live/Finish
  no-interrupt.
- Android JVM (`ProPersonalTest`, 21 tests): same meanings and values as
  the iOS unit suite (icon-alias mapping and theme-set validation in
  place of the iOS renderer/StoreKit-adjacent checks).
- Android instrumented (`ProPersonalTest`, 10 tests): stub-driven
  entitled analysis/curves/themes flows with exact lines, recap scope →
  preview → share affordance, empty state, paywall/loading/expired
  states, themed photo-free long-text render with pixel assertion +
  Classic fallback, icon apply/failure persistence;
  (`ProPersonalFlowTest`, 3 tests): real-navigation paywall, free-tool
  integrity, Live/Finish no-interrupt. Companion selector extended;
  expected count 57 → 70.
- Existing placeholder assertions updated to the real views (the slice's
  definition of done, not a weakening): the previously asserted
  no-second-offer, no-flash, no-interrupt, and fallback properties are
  kept and the absence lists now also cover the new Pro surfaces.

## 7. Remains list (honest)

- Store/sandbox purchase evidence is owner-gated (unchanged from
  slice 16): the purchase path is proven with stubbed products and
  scripted verification, never a live transaction.
- PitTech-hosted subscription terms still need owner review before the
  paywall links leave draft (unchanged from slice 16).
- iOS alternate-icon art is owner-gated: names are declared and the
  apply/fallback path is implemented and tested, but the Pro icon image
  sets ship separately; see §4.
- Backend byte-identity enforcement for `pro-personal-v1.json` is
  deferred: `api-service/` is frozen for this native-only slice, so the
  fixture copies are byte-identical by construction with native
  contract-version assertions; add the copy check alongside the existing
  insight-contract test when the backend reopens (stage 19 at the
  latest).
- The full thorough gate runs once at stage 19; this slice lands on the
  QUICK gate (backend suite, unit tests, compile builds both platforms).
  Actions is the proof for both lanes.
