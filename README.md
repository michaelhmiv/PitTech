# PitTech

Android-first cook logging, monitoring, and analysis for serious home smokers.

PitTech is organized around the cook record. A cook brings together multiple dishes, grill and probe readings, notes, photos, and actions in one timeline, then makes the data easy to review and export.

## Product decisions

- **Android first.** Use conventional Android navigation and controls, with a later iOS path kept in mind.
- **Pit Boss Austin XL first.** Initial hardware validation target: Austin XL model PB1000XLW1 and the proposed 80015/CAT-02-PG Wi-Fi/Bluetooth controller. Compatibility and remote command support are not yet verified.
- **No PitTech account.** Users can start, log, review, export, and restore cooks without signing in.
- **Local-first cook records.** The phone’s local database is the source of truth. Core logging and review work without internet. Records remain portable through export and import.
- **Remote monitoring and control are first-version requirements.** They require a working internet path at the grill and phone. The exact controller route must pass a hardware and protocol validation spike before support is claimed.
- **PIN pairing, not an account.** Remote access uses a short-lived, single-use pairing PIN with attempt limits and revocation. The PIN authorizes a device; it is not a permanent login password.
- **Multi-channel cooking.** Support multiple dishes and at least four probe channels as a first-class product requirement.
- **Brand-neutral direction.** Keep controller and logger integrations behind device adapters; Pit Boss is the first validation target.

## Audience and experience

Design for a smoker who wants to get a cook started quickly, read temperatures at a glance, record what happened, and learn from the result. Use plain labels, consistent navigation, large readable values, and visible actions. No account wall, hidden gesture-only controls, neon styling, or unnecessary setup steps.

Use the supplied monochrome PitTech logo as the visual anchor. A restrained charcoal, white, and warm accent palette is a starting point; provide strong contrast and comfortable use outdoors and at night.

## Navigation

Use four clearly labeled phone destinations:

1. **Cooks** — active cook, recent cooks, and Start cook.
2. **Insights** — trends and comparisons across completed cooks.
3. **Devices** — connect grills and loggers, name probes, and assign probes to dishes.
4. **Settings** — export, import/restore, display preferences, and remote-device management.

A cook has three consistent sections:

- **Live** — current grill/probe readings, connection freshness, alerts, and supported controls.
- **Timeline** — chronological record of manual entries and controller events.
- **Charts** — full sensor history, overlays, and per-cook analysis.

## Page requirements

### Cooks

- Show an active cook prominently, including title, elapsed time, dishes, and connection state.
- Keep Start cook easy to find.
- Let a user begin with manual logging; device connection is optional.
- Show recent finished cooks as simple, scannable cards.
- Support more than one dish in a cook.

### Live

- Show grill setpoint and pit temperature separately.
- Show each probe’s large current value, assigned dish, target if set, source, and last update time.
- Plot multiple selected channels together and show timeline events on the graph.
- Make stale or disconnected readings visibly different from live readings.
- Show every remote command as pending until the controller confirms success or failure.
- Provide quick actions for logging an event, note, or photo.

### Timeline

- Present events chronologically from cook start to finish.
- Include user entries, controller-reported events, and meaningful automatic milestones in the same timeline, with a clear source label.
- Record notes, photos, dish assignment, event type, occurred time, and entry time.
- Include common actions such as meat on, spritz, wrap, temperature check, remove, rest, and finish, plus custom events.
- Allow add, edit, correct-time, and delete actions. Provide undo after deletion.
- Store occurred time separately from entry time so correcting a historical time does not create a duplicate event or shift unrelated events.
- Preserve device provenance for automatic samples. If a user corrects or deletes an automatic reading, update charts and analysis accordingly.
- Store the full sensor stream. In the readable timeline, group dense samples into expandable time blocks; provide a way to inspect individual samples and the full-resolution chart without losing data.
- Do not auto-scroll away from an older entry while the user is reading it. Offer a clear Jump to latest action when new records arrive.

### Charts and analysis

Initial per-cook analysis should include:

- Pit temperature compared with setpoint over time.
- Each food probe’s temperature curve and rate of rise.
- Stalls or slow-rise periods, with the detected interval visible on the chart.
- Event overlays for actions such as wrapping, spritzing, and setpoint changes.
- Missing, stale, or questionable data shown as gaps or quality labels rather than interpolated as if measured.
- Export of the complete readings and timeline.

Later analysis can add cook comparisons and finish estimates. Derived findings should link back to the underlying readings and communicate uncertainty.

### Insights

- Compare cooks by dish/cut, weight, grill, and method when those details are recorded.
- Show which sessions and data support a comparison.
- Do not imply that a cook change caused an outcome when the record only shows correlation.
- Keep raw cook records available regardless of whether a user opens Insights.

### Devices and remote access

- Identify the connected controller/logger and its supported capabilities.
- Let users name probes and assign them to dishes.
- Use direct local communication when available.
- For away-from-home access, test whether the controller can use the existing remote path or requires a PitTech relay or local bridge.
- Pair remote devices with a short-lived, single-use PIN. Allow the owner to view paired devices and revoke access.
- Keep cook history local and exportable. Any relay should carry only what remote live monitoring and control require; document its data handling before implementation.
- Show controller reachability and reading age clearly. Remote commands require an explicit result from the controller.

## Data ownership and portability

- Do not require an account to use the app.
- Keep the local database as the canonical source for cook records.
- Provide per-cook and full-library export and restore.
- Provide a familiar Excel workbook (.xlsx), standalone CSV tables, and a complete standard ZIP archive with CSV, structured JSON, original photos, and a data dictionary. Preserve raw readings, explicit units, stable IDs, timestamps, and source information for analysis and restore.
- Make import/export understandable without technical setup.
- If remote access uses a service, keep it separate from the local cook library and disclose exactly what is transmitted and retained.

## Android standards to follow

- Use a Material 3 navigation bar with four labeled primary destinations on phone layouts.
- Meet Android’s recommended minimum 48 dp touch target for interactive controls; make frequent cook actions larger where practical.
- Support system font scaling, accessible names, strong text contrast, and non-color-only distinctions on charts.
- Keep the UI driven by the local data source; network services update local state rather than becoming the only way to read a cook.
- Use TLS for client/server communication and minimize data transmitted.
- Make PINs short-lived, single-use, attempt-limited, and revocable; never treat a reusable short PIN as a user password.

## First validation gates

1. Verify read and write capabilities for the target Austin XL controller, including whether setpoint changes are supported.
2. Verify how remote communication works when the phone is away from home, and whether a vendor relay, PitTech relay, or local bridge is required.
3. Demonstrate local cook logging and export with multiple dishes and at least four probes.
4. Demonstrate a cook timeline that interleaves manual events and automatically sourced data without duplicate entries or misleading timestamps.
5. Test stale data, disconnections, reconnects, failed commands, PIN expiration, PIN reuse, and PIN attempt limits.

Remote access is a product requirement, but compatibility is not yet a verified hardware capability.

## Platform references

- [Material 3 navigation bar](https://m3.material.io/components/navigation-bar/overview)
- [Android accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/apps)
- [Android offline-first architecture](https://developer.android.com/topic/architecture/data-layer/offline-first)
- [Android network security guidance](https://developer.android.com/develop/connectivity/network-ops/connecting)
- [OWASP Multifactor Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html)
- [NIST SP 800-63B](https://pages.nist.gov/800-63-4/sp800-63b.html)

## Android app foundation

The Android app uses Jetpack Compose for the phone UI and Room as the local source of truth. The feature work on `codex/non-controller-features` builds on the cook-entry foundation already on `main`. Cook records and picked photos stay in app-private storage, so the log is available offline and needs no account.

Users can start and edit a cook, add multiple dishes, and record meat type and cut, weight, starting condition, bone-in status, placement, grade or source, thickness, preparation items with brands and amounts, notes, and photos. Each cook has Live, Timeline, and Charts views. The timeline supports manual events, notes, and temperature readings with add, edit, and delete/undo actions. Cook records also include targets, finish details, rest time, overall and detailed ratings, and notes about what to change next time.

Insights summarize completed cooks and allow comparisons across selected cooks. The app exports a multi-sheet Excel workbook, a temperature-readings CSV, or a complete ZIP archive containing structured JSON, record-level CSV files, and original photos. ZIP restore previews the contents and skips duplicate cook IDs. Temperature and weight preferences affect new entries; saved values retain their units. Grill-controller setup and away-from-home monitoring are deferred until the controller hardware arrives; cooks can be logged and charted manually in the meantime.

### Build and test

- Open this repository in Android Studio with JDK 17 and Android SDK 37 installed, or use `./gradlew` from the repository root.
- Run `./gradlew testDebugUnitTest assembleDebug` to run the current input-validation tests and build the debug APK.
- For an automated emulator run, build the app and test APKs with `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`, then run `bash scripts/run-emulator-ui-tests.sh` from the repository root with the Android SDK installed. The script boots a clean emulator with larger system text and checks cook entry, timeline editing, manual readings, results, export/restore, crash recovery, and persisted data after relaunch.
- GitHub Actions runs this emulator flow on pull requests and pushes to `main`: API 36 is blocking and API 37 is advisory. The evidence artifact includes emulator screenshots, the post-restart accessibility hierarchy, instrumentation output, and logcat. Successful builds also attach a debug APK for 14 days.


## Product design

- [Detailed Android UI/UX plan](docs/UI_UX_PLAN.md)
- [Cook data capture and export specification](docs/COOK_DATA_AND_EXPORT_SPEC.md)
