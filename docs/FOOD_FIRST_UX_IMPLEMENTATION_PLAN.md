# Food-first cook experience: PR implementation plan

## Outcome

Make an ordinary multi-food cook quick to monitor and log. Live begins with a simple grill layout and named food cards; Timeline begins with a readable cook story. Rich preparation, recording, results, and source details remain accessible when needed.

One PR against `main`, developed on `feature/food-first-cook-experience`. Android is the primary implementation target. Preserve portable archives, existing cook identities, original photos, telemetry provenance, physical timing, and the native iOS reader's compatibility.

## Implementation

1. **Food cards and contextual actions.** Put foods on the grill, preparing foods, and removed/resting/holding foods in clear groups. Show assigned probes, a useful temperature or explicitly labeled last reading, physical phase, and last meaningful action. Tapping a food opens Note, Photo, Temp, Food on/Remove/Rest/Hold/Serve, probe assignment, and detailed editing with that food already selected. Keep multiple probes per food and normal use without a probe.
2. **Probe movement and correction.** Expose assignment directly from a food/probe action. Keep interval history and existing reading IDs. Support an actual move time bounded to the current assignment interval, reattribute only the affected controller readings, and preserve earlier assignments. Never display the preceding food's measurement as a new food's current temperature. Manual spot checks retain their own food association.
3. **Readable Timeline.** Default to Cook story; offer Notes, Actions, Photos, Temps, Grill & probes, and Everything, combined with an independent food filter. Remember selections per cook, retain sort and jump-to-latest behavior, and reveal a chart-selected entry even when filters would hide it. Keep combined notes intact. Include setup/gallery photos that have no event association without manufacturing database events.
4. **Visual hierarchy.** Show setpoint/device entries as compact rows, photos as substantial previews, and notes/actions as readable content. Keep source, elapsed/recorded times, complete temperature context, and filenames in entry/photo details. Preserve all raw records and export contents.
5. **Fast entry and completion.** Make food selection visible in the unified note composer. Show a temperature value first; reveal measurement name, type, unit, and corrected times on demand. Keep durable drafts, multi-photo attachment, captions, retry, edit, delete, and undo behavior. Add food with a name first and optional detail afterward. Finish the session with a short confirmation; results remain independently editable.
6. **Surrounding screens.** Place the active cook before new-cook/setup content on Home. Default equipment and units sensibly, condense preparation/ingredient and edit forms, and keep connection/settings explanations available on demand. Put useful cook history, outcomes, and comparisons ahead of aggregate record counts in Insights. Retain existing chart controls and per-food filtering.

## Acceptance and validation

- A representative session contains a butt, several shoulder steaks, and potatoes added later; each food retains independent actions and timing.
- Reassigning a probe now or at a valid earlier time preserves old food history, updates only the relevant automatic samples, and does not move manual measurements. A newly assigned food waits for a new sample.
- Timeline filters combine food and content correctly, including multi-food notes, global grill changes, linked temperatures/photos, and standalone setup photos. Filters survive reopening and invalid/deleted food selections recover to All foods.
- Photos and meaningful actions remain readable at large text; compact telemetry rows expose complete detail on demand.
- Logging, editing, drafts, attachment retry, export/restore, legacy cook review, recording recovery, and finish/results workflows retain their existing behavior.
- Run focused JVM/domain checks and Room integration/UI regressions, then the existing complete Android CI lanes: API 36 at font scales 1.15 and 1.5 (required), API 37 (existing advisory), packaging and stopped/live upgrade recovery.
- Inspect emulator evidence for the redesigned Live, Timeline, and composer surfaces; fix functional or layout failures before merge. Check PR review feedback and merge the tested head SHA only.
- After merge, verify the main checks and the existing Firebase Dev distribution workflow.

## Delivery record

Implementation and validation are tracked in [PR #59](https://github.com/michaelhmiv/PitTech/pull/59). The [PR checks](https://github.com/michaelhmiv/PitTech/pull/59/checks) contain JVM reports, emulator logs/screenshots at both required font scales, packaging, and prior-version upgrade evidence. The PR description records the final tested commit and merge verification.

The redesign is native Android. No physical grill commands or private cook photographs are added to the repository; synthetic fixtures cover multi-food timing, probe movement, combined notes, and setup photos. Hardware behavior continues through the existing read-only recording integration.
