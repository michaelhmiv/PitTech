# Cook logging, preparation and charts

## Logging

**Log** opens one editor. **Temp**, **Photo**, Food on, Spritz, Wrap, Remove, Rest, Hold and Ready to serve are shortcuts into that editor with the relevant fields filled in. A note can contain a description, multiple original photos and captions, multiple manual food/pit readings, an action, dish scope and an actual time.

Saving or editing keeps one note ID and one timeline card. A manual reading belongs to its note through `log_reading_links`; it remains available to charts and exports. Automatic telemetry stays in the sensor stream. Existing separate entries remain readable and are never combined by timestamp proximity.

Drafts and staged photos stay on the phone when the app backgrounds or relaunches. Keep draft closes the editor; Discard removes the draft. A failed attachment keeps the same draft for retry. Android can save the note's other information while retaining a failed attachment draft; iOS commits the note once its attachments are available. Editing does not request another on-log controller snapshot. Deleting a note removes its owned readings and detaches its photos into the cook gallery; undo restores the same IDs and associations.

## Preparation and physical timing

A new cook starts **Preparing**. **Already cooking** records the actual Food on time. Preparation can begin the day before, and preheat can overlap it. Preparation notes, ingredients and a persistent checklist are available before Food on.

Each dish has an independent physical phase and cooking, rest and hold duration. Food on starts heating, Wrap continues it, Remove ends it, Rest starts its own interval, Hold starts another, and Ready to serve marks the dish done. Returning food to heat after removal, rest or hold accumulates another cooking interval. Simultaneous heating intervals count once in equipment cooking hours. Adding another dish leaves that dish in preparation until its own Food on action; earlier whole-cook milestones retain their original dish scope.

Recording and guidance pause controls do not pause physical food time. Finishing the cook is an explicit session action. Backdated action corrections recalculate intervals. New phase entries, edits and deletions must leave a valid physical sequence. Removing a dish retains its note history without turning its milestones or target history into whole-cook anchors.

Older cooks keep their original start/pause timing basis until the user chooses **Use actual Food on timing**. Original session timestamps remain saved. Importing an older archive also retains this legacy timing basis.

## Charts

The X-axis shows elapsed time or local clock date/time; elapsed charts can include negative preparation time before Food on. The Y-axis has numeric ticks and the selected °F/°C unit. Stable channel identities include cook, probe, device, dish, measurement and source; equal or renamed display names do not merge unrelated curves.

Use channel toggles, Only, zoom, pan, reset, Follow latest and the scrubber to inspect the record. Scrubbing reports actual stored sample values and times. Manual measurements are discrete points. Invalid readings, connection gaps and missing periodic samples split automatic curves. No values are backfilled or interpolated across an outage; reduced plots preserve normalized extrema, and inspection uses original samples.

Notes, photos and actions appear at their actual time. Dense annotations retain every note ID; open an entry to navigate to its exact timeline card. Dish phase bands, current/reference targets and recorded target/setpoint history provide context. Historical targets begin at their effective time; a dashed current target is explicitly a reference. Recorded trends require enough accepted, contiguous data and do not predict completion times. Sparse manual measurements do not produce a trend across long gaps.

Compare matching food or pit channels from completed cooks, aligned to scoped Food on or Wrap actions. Saved references retain channel identities. Legacy references resolve a display name only when it uniquely identifies a channel; otherwise select one explicitly.

## Updates and portability

Android package replacement reconciles eligible periodic recording without requiring an activity launch. The cook ID, notes, photos, manual temperatures, original phase times, probe mapping, controller binding and cadence remain saved. Paused, stopped, completed and on-log sessions stay in their chosen state. If the foreground service cannot restart, recording is paused with an actionable notice. An outage remains a visible gap. See [update recovery verification](COOK_UPDATE_RECOVERY.md).

Room migration 6→7 adds nullable note metadata and explicit reading links, lifecycle, draft and target-history tables without rewriting old logs. Portable archive version 6 includes note metadata, links, lifecycle and target history in JSON, CSV and workbook exports. Local unfinished drafts are excluded. Archive versions 1–5 remain supported. Import validates relationships and ID ownership before committing; restored recording, controller credentials and Android automation remain inactive until explicitly reattached or enabled. Native iOS preserves the portable data and original photo bytes; its current app remains manual logging without grill integration.

## Verification

The Android workflow runs JVM math/domain tests, generated-schema migration validation, note/photo retry and ownership integration tests, phase persistence tests, chart/editor UI tests, existing device/recording/guidance/preparation/user-flow suites, stopped-APK upgrades and a live foreground recording upgrade. Required API 36 lanes cover font scales 1.15 and 1.5. The existing Android 17 emulator lane is advisory because its system graphics stack can crash.

The iOS Simulator workflow compiles native views and runs compatibility, atomic editing, durable draft/photo retry, delete/undo, independent dish timing, relaunch, archive/checklist/reference and graph math tests. Simulator cleanup precedes sequential iPhone and iPad screenshots. Unsigned release and Play bundle builds verify packaging; signed App Store distribution remains the existing separately requested workflow.
