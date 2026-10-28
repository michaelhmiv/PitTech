# Flowing Slice 0 — iOS phase-2 parity audit & sync-misreport finding

**Program:** Flowing build — UX evaluation repairs (issue #243).
**Slice:** 0 — Reconcile & verify.
**Baseline:** `main` @ `5c8ae97`. Audited 2026-10-28 by direct code read on
that head; the Android reference for every item is PR #240 (commit `e33c080`).
**Method:** each phase-2 item was located in the Android diff, then searched
for its counterpart in `ios/PitTech/` on main. Verdicts are **Present**,
**Partial**, or **Absent**, with the files that prove them. Every gap is
assigned to Slice 2 or Slice 3 per the build spec's slice table.

## 1. Parity findings

### 1.1 Brand strip — **Partial → Slice 2**

- *Android (PR #240):* `PitTechBrandStrip()` (`CookScreens.kt:862`) — the
  Ember mascot (`R.drawable.pittech_smoker`, 36dp) beside the "PitTech"
  wordmark in the banner face with the ember tick — rendered by the shell on
  **every tab root** (`PitTechApp`, `CookScreens.kt`), brand only, never a
  page title.
- *iOS:* no brand-strip component exists (`TurnInComponents.swift` has none;
  no `BrandStrip`/wordmark symbol anywhere in `ios/PitTech/`). The Meals root
  alone carries the older DD-1 lockup (`ContentView.swift:186–197`,
  `CookListView`): an SF `flame.fill` glyph in a rounded accent tile plus
  `Text("PitTech").font(.headline)`, tagged `meals-lockup`. The other roots
  have no brand mark at all: Recipes leads with its promise line
  (`SavedRecipesView.swift`), Discover with a meta label and headline
  (`PublicDiscoveryView.swift:14–15`), Insights with the totals band
  (`ContentView.swift`, `InsightsView`), More with a section title
  (`LibrarySurfaces.swift`, `MoreView`).
- *Gap:* build the phase-2 strip (mascot + display-face wordmark + tick) as
  a shared component on all five tab roots; the DD-1 lockup is superseded.

### 1.2 Start-a-cook FAB — **Absent → Slice 2**

- *Android (PR #240):* an `ExtendedFloatingActionButton` labelled "Start a
  cook" overlays the Meals board (`CookScreens.kt:482`); the board's
  in-flow start button was kept, same flow behind both.
- *iOS:* no floating action button exists on any root. Starting a cook is
  the in-flow, full-width **Start meal** button inside the Meals scroll
  content (`ContentView.swift`, `CookListView`), opening the same
  `StartCookSheet` (`ContentView.swift:295`). The function exists; the
  floating, always-reachable pattern does not.
- *Gap:* add the FAB on the Meals board, opening the existing start sheet;
  keep the in-flow button, mirroring Android.

### 1.3 Slim home ad — **Present (platform-equivalent), no gap**

- *Android (PR #240):* the 408dp native card became a 108dp small-template
  row on the same unit, slot height reserved while loading, placement
  unchanged (finished list only, never the active zone)
  (`PitTechNativeAdPlacement.kt`).
- *iOS:* `NativeAdCard.swift:47–61` fixes the card at **126pt in both the
  loaded and loading states** — the placeholder reserves the identical
  height, so nothing shifts — with a compact row layout (58pt icon,
  headline/body/CTA stack, `NativeAdCard.swift:96–140`). Placement matches
  Android's rule: it renders only inside the completed-cooks list
  (`ContentView.swift:284`, after the third finished cook), and the live
  zone's own contract states nothing, ads included, renders inside or above
  it (`ContentView.swift`, `LiveZoneCard`).
- The iOS card was already slim; the residual differences (custom native
  layout instead of a small template, 126pt instead of 108dp) are
  platform-appropriate. **No slice work.**

### 1.4 Live pit/probe side-by-side pairing — **Partial → Slice 2**

- *Android (PR #240):* on the cook's Live screen (`CookDetailScreen.kt`),
  pit and probe are two equal columns in one row — each with its meta
  label, trend chip, scoreboard value, and reference line (`SET …` /
  `TARGET …`) — with the progress bar and "° TO GO" below.
- *iOS:* every element exists on the Live screen but not the composition.
  In `CookDetailView.swift` (`liveZone`, ~lines 228–310) the pit gets the
  scoreboard block (label, trend chip, `SET` mono line) while probe values
  render as smaller per-dish rows underneath (mono readout size; only the
  lead dish carries the progress bar and target line). On the Meals board,
  `LiveZoneCard` (`ContentView.swift:312–379`) shows the pit alone at
  scoreboard size, with the food temperature demoted to a mono meta line.
- *Gap:* recompose the Live screen's readouts into the equal side-by-side
  pair (label + trend + reference line each, progress below), and pair the
  Meals live-zone card the same way. Slice 2 owns the Live board.

### 1.5 Recipes list/grid views, added dates, newest-first — **Absent → Slice 3**

- *Android (PR #240):* a persisted List/Grid segmented toggle on the
  Recipes tab; list rows with an inline photo thumbnail; photo-forward grid
  tiles; the library sorted newest-added first
  (`sortedByDescending { it.createdAtUtcMillis }`); "Added <date>" on rows,
  tiles, and the detail (`SavedRecipesLibrary.kt`).
- *iOS:* `SavedRecipesView.swift` is a single scroll column of full-width
  cards (`SavedRecipeCardContent`: 150pt hero photo, name, meta,
  "Saved recipe · version N"). There is no layout toggle, no persisted
  layout preference, and no grid anywhere in the app (no `LazyVGrid` in
  `ios/PitTech/`). The shown list preserves stored library order — no
  `createdAt` sort — and no "Added" date is rendered in the library or on
  `SavedRecipeDetailView`.
- *Data readiness:* the date already exists — recipe headers are created
  with `createdAtUtcMillis` and keep it across updates
  (`RecipeStore.swift:127`). This is a presentation-only gap; no migration.

### 1.6 Automatic recipe-name differentiators — **Absent → Slice 3**

- *Android (PR #240):* `differentiatedRecipeName()`
  (`domain/RecipeNames.kt`) — when a name is taken, pre-fill
  `"<name> · <Mon d>"`, with a `(2)`, `(3)`… counter for same-day repeats —
  applied in the recipe editor when saving a dish as a recipe, user-editable,
  unit-tested (`RecipeNamesTest.kt`).
- *iOS:* no equivalent exists (no differentiator symbol or logic in app or
  test sources). "Save as new recipe" keeps the dish's raw name into the
  editor (`MealRecipeActions.swift:24`; the name field is the snapshot's
  name, `NativeRecipeEditor.swift:130`), and Duplicate pre-fills
  `"<name> copy"` (`SavedRecipesView.swift:299–304`) — a different rule that
  carries no date and does not match the library's added-date story.
- *Gap:* port the Android rule (date suffix, same-day counter, pre-fill
  only, never blocking) to both iOS save paths, with unit tests mirroring
  `RecipeNamesTest`.

### 1.7 Recipe story on the recipe detail — **Partial → Slice 3**

- *Android (PR #240):* the recipe detail gained the source cook's story
  from the snapshot's frozen observations (`SavedRecipesLibrary.kt`):
  "Cooked <date>" (from `smokingAnchorUtcMillis`), "Ran <duration>",
  "Rated x / 5", and the recorded timeline with the temperature measured at
  each step — plus an "Open the cook" action when the source meal is still
  in history.
- *iOS:* `SavedRecipeDetailView` (`SavedRecipesView.swift:216–313`) has the
  scoreboard cells (pit temp / serves / target), the public-source credit,
  the results note, the photo gallery, and — the one story element present —
  an **Open source meal** link when the source cook exists
  (`SavedRecipesView.swift:~291`), the counterpart of Android's "Open the
  cook". Missing: the frozen-observation story itself — no cooked
  date/duration/rating block and no observations timeline is rendered.
- *Data readiness:* the snapshot already carries everything the story
  needs — `observations`, `smokingAnchorUtcMillis`, and `rating`
  (`RecipeModels.swift:26–27`) — and the editor already lists observations
  (`NativeRecipeEditor.swift:209–211`). Presentation-only gap.
- *Note:* this is also spec element 6.1–6.3's iOS delta; Slice 3 builds
  only this delta, per the spec's reconciliation table.

### Assignment summary

| # | Item | Verdict | Slice |
|---|---|---|---|
| 1.1 | Brand strip | Partial (Meals-only DD-1 lockup; strip absent) | 2 |
| 1.2 | Start-a-cook FAB | Absent (in-flow button only) | 2 |
| 1.3 | Slim home ad | Present (platform-equivalent) | — |
| 1.4 | Live pit/probe pairing | Partial (elements present, composition stacked) | 2 |
| 1.5 | Recipes list/grid + added dates + order | Absent (data ready) | 3 |
| 1.6 | Name differentiators | Absent | 3 |
| 1.7 | Recipe story on detail | Partial (data ready; "Open source meal" present) | 3 |

## 2. Sync-misreport finding

**The Oct 27 report:** enabling private sync on the Pixel failed with
"Retry needed / could not reach PitTech" while staging's health endpoint
returned 200 throughout. The sign-in blocker behind it (the More-tab
crash-sheet hijack) was fixed in PR #236. This section traces the current
code path on `main` @ `5c8ae97` (Android, the platform that failed) and
records what remains.

### What now distinguishes auth from reachability — verified in code

- **Signed-out is an auth state, presented as one.** The "Turn on private
  sync" card renders only when signed in, and the Sync now button is
  disabled unless signed in (`AccountScreen.kt`: the card's
  `signedIn && sync.enabled` condition; the button's
  `enabled = !state.busy && signedIn && sync.enabled`). The status row for
  a signed-out user reads "Sign in to start private sync — Nothing is
  uploaded while you are signed out…" (`StateCopy.kt`, `SyncStatusCopy`).
  The coordinator's `attempt()` returns without any network work when
  there is no session user (`PrivateSyncCoordinator.kt:194–196`), and
  `publishBind` falls back to the default "Sync is off" presentation when
  signed out (`PrivateSyncCoordinator.kt:557–562`).
- **401 is distinguished.** `PrivateSyncHttpError` maps 401 to "Sign in
  again to reconnect private sync. Your local journal is unchanged."
  (`PrivateSyncClient.kt:21`), and `authorized()` first spends one silent
  token refresh and retry before surfacing it
  (`PrivateSyncCoordinator.kt:316–326`).
- **Genuine transport failure is "waiting", not "retry".** An OkHttp
  `onFailure` (DNS/connect/timeout) becomes outcome `waiting` —
  "Waiting for connection. Your changes stay on this device."
  (`PrivateSyncClient.kt:84,102`; `PrivateSyncCoordinator.kt` `fetch()`).
- **Other enumerated statuses are truthful:** 409 (conflict), 413 (size
  limit), 422 (cannot apply safely), 429 (busy) each have specific copy
  (`PrivateSyncClient.kt:22–25`).
- **Staging agrees today (service-level check, 2026-10-28):**
  `GET https://api-staging.pittech.app/healthz` returns 200, and
  `GET /v1/sync` with no token is answered by the server with its
  `authentication_required` error — the 401 path above
  (`api-service/src/sync-http.ts:46`). The server is reachable and the
  auth answer is the distinguished one.

### The surviving defect: unenumerated HTTP statuses are reported as "could not reach PitTech"

`PrivateSyncHttpError`'s copy table (`PrivateSyncClient.kt:19–28`)
enumerates only 401/409/413/422/429. **Every other HTTP status falls into
the `else` branch: "Private sync could not reach PitTech. Your local
journal is unchanged."** The coordinator publishes that message verbatim
as the detail under outcome `retry` — status **"Retry needed"** — in both
`fetch()` and `commit()` (`PrivateSyncCoordinator.kt`, the
`PrivateSyncHttpError` catches). Server answers that land in the `else`
bucket today, none of them reachability failures:

- **503 `sync_busy`** — the server's own capacity response
  (`api-service/src/sync-http.ts:48`). The server was reached and said
  "busy"; the app says "could not reach".
- **400 / 415** — server-side request rejections (idempotency key, body,
  content-type; `sync-http.ts:52–65`): a client/server contract failure
  reported as reachability.
- **404** — the sync routes not mounted on a deployment (they exist only
  when `PRIVATE_SYNC_ENABLED` is on, `api-service/src/config.ts:107`).
- **3xx** — the client refuses redirects (`followRedirects(false)`,
  `PrivateSyncClient.kt:47`); `httpError()` even tags the code
  `redirect_rejected` internally (`PrivateSyncClient.kt:125–135`), but the
  user still gets the reachability sentence.

This path reproduces the Oct 27 symptom pair exactly — "Retry needed" plus
"could not reach PitTech" while a health endpoint returns 200 (health is a
different route; it proves the host answered, not that sync did). Which
specific status fired that evening cannot be determined from code, and
this audit pulled no server logs for it: the finding is the path, not a
retro-diagnosis.

**iOS does not have the defect:** its coordinator maps 401 to the sign-in
copy and every other HTTP status to "Private sync needs a retry." — a
server answer is never called unreachable — and reserves the waiting state
for `URLError` (`NativePrivateSyncCoordinator.swift:300–301`). The Android
fix below also restores cross-platform copy parity.

### Recorded as a Slice 2 fix item

Rework `PrivateSyncHttpError`'s default: a server answer is never "could
not reach PitTech". Concretely — give 503 its own busy copy (mirroring
429), give the remaining unenumerated statuses a truthful generic (the
server answered with an error; include the status), keep the `IOException`
→ waiting/reachability wording and the 401 copy unchanged, and cover the
mapping with unit tests per status class. Slice 2 owns it: it is the
Live/board + status-copy slice, and its E1–E3 work is the same
honest-status theme.

### Verification limit

This finding is verified at **code level** (both clients, the API service)
plus the **staging-service level** (the live checks above). No physical
device sign-in or sync enable was performed for this audit; the end-to-end
device walk against staging remains the owner device pass in the spec's
acceptance step (§8), unchanged.

## 3. Companion deliverables in this PR

- **#232 — Info.plist:** `LSSupportsOpeningDocumentsInPlace` is now
  declared, value **false** (`ios/PitTech/Info.plist`). The app is a
  Cook Pack document handler (`CFBundleDocumentTypes`, Viewer/Owner), but
  every incoming file — `.onOpenURL` file URLs and `.fileImporter` picks —
  is *imported*: `store.receiveArchive(at:)` copies its contents into the
  library, the source document is never edited or written back, and the
  app ships no document browser. `false` is the truthful declaration (iOS
  hands the app a copy, which is exactly its import semantics) and it
  resolves the ITMS-90737 "Missing Document Configuration" warning from
  build 561.
- **Fixture corpus:** `contracts/fixtures/pace-stall-corpus-v1.json` —
  five recorded-cook shapes (canonical rain-dip brisket, 2h40m foil-stall
  brisket, fast chicken, overnight slow-creep brisket, sparse hand-logged
  butt) in the shared archive vocabulary, each with an `expected` oracle
  block; `contracts/fixtures/pace-stall-corpus-v1.README.md` states each
  shape's expected engine behavior for Slice 1's backtest. Consumable by
  both platforms' domain tests the repo's way: Android test resources
  already include all of `contracts/fixtures/`, and the file is registered
  as a `PitTechTests` bundle resource in `ios/project.yml`.
