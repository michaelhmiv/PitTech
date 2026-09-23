# Cook Capture and Export Specification

## Purpose

A PitTech cook is a portable, editable record of what was cooked, how it was prepared, what happened during the cook, and how it turned out. It must be useful for a quick weekend log and still hold enough detail for serious comparisons later.

Smoking guides commonly cover the cut and weight, trimming, binders and seasonings, marinades or injections, wood and pit settings, probe placement, spritzing, wrapping, resting, and doneness checks. PitTech should make those details available without making a user fill out a long recipe form. The field set below is informed by real cooking workflows; it is a logging model, not a cooking prescription.

## Product rules

- **Fast first, detail when wanted.** Start a cook and add a dish in a few taps. Every other detail can be added later.
- **One cook can contain several dishes.** A pork shoulder and a pan of beans can share a cook session while keeping dish-specific probes, prep, events, and results.
- **Optional means optional.** Do not block a cook because the user does not know the cut, weight, seasoning quantity, or exact time.
- **Keep structured and free-form entry together.** Offer useful fields and common event buttons, plus a plain note/custom field for anything the schema does not cover.
- **Use progressive disclosure.** Show a short dish form first. Put preparation, targets, and advanced details under clearly named sections such as “Preparation” and “More details.”
- **Make repeat cooks quicker.** Let users reuse their own saved dish/prep templates and ingredient lists. Templates remain local and exportable.
- **Keep raw data and interpretation separate.** Never overwrite a sensor sample with a calculated or corrected value. Keep derived metrics labeled with their method and inputs.
- **Keep safety goals separate from tenderness preferences.** A user-recorded food-safety target and a pitmaster’s preferred texture/finish target are different kinds of data. PitTech records them separately and does not treat a preferred barbecue finish as a food-safety guarantee.
- **No account required.** Cook records, photos, templates, and exports belong to the local library and work without internet.

## What users can record

All fields in this section are optional unless a user explicitly chooses to require a field in a saved personal template.

### Cook session

| Group | Fields |
|---|---|
| Identity | Cook name/title; cook ID; optional recipe or personal template name |
| Time | Started, finished, and paused/resumed times; time zone; event time and entry time kept separately |
| Equipment | Grill/smoker, controller/logger, fuel type, wood or pellet type/blend, rack/location, and notes about setup |
| Conditions | Outdoor temperature, weather, wind, and other conditions, entered manually or supplied by a connected source if available |
| Session notes | General notes, photos, and custom fields |

### Dish and meat

| Group | Fields |
|---|---|
| What it is | Dish name; protein/food type (beef, pork, poultry, fish, wild game, vegetables, other); cut (brisket, shoulder, ribs, loin, etc.); optional subtype or custom name |
| Size | Weight and unit; number of pieces; thickness or dimensions when useful |
| Starting condition | Refrigerated, frozen, thawed, or other; bone-in/boneless; grade/brand/source if the user wants to remember it |
| Placement | Rack or grill area; orientation; dish-to-probe assignment |
| Plan | Optional planned start, target, serving time, or personal recipe/template |

### Preparation and ingredients

Preparation should support distinct stages so a user can remember what was done and when.

| Stage | Example fields |
|---|---|
| Trim and shape | Fat cap, trimming notes, scoring, tying, trimming time, before/after weight |
| Binder | Ingredient/product, brand, amount and unit, application time, notes |
| Rub or seasoning | One row per ingredient/product; ingredient name, brand, amount and unit, applied time, custom blend name, notes |
| Brine or marinade | Method, ingredients, amounts and units, start/end time or duration, notes |
| Injection | Ingredients, amounts and units, injection time, optional amount per weight, notes |
| Other preparation | Stuffing, glaze, sauce, spritz liquid, or any custom preparation step |

Users should be able to save a seasoning blend or preparation as a reusable personal item. Do not assume all ingredients are dry rubs: retain a stage/type such as binder, rub, brine, marinade, injection, spritz, wrap addition, glaze, or custom.

### Cook timeline and actions

A cook timeline is the chronological record. Common actions should be one-tap choices, with dish, time, and optional notes/photos available on each entry.

- Prep started or completed; meat on; probe inserted or moved; lid opened
- Pit setpoint changed; pit reached temperature; fuel added
- Spritz or baste, including liquid and optional interval
- Flip, rotate, or move to another rack/heat source
- Stall noticed; wrap or unwrap, including time, material, and optional liquids/fats/seasonings added
- Probe check, including exact temperature and probe/location
- Moved to oven, grill, cooler, or holding setup
- Removed from heat; rest or hold started/ended, with wrap and holding temperature if known
- Carved, served, or finished; leftovers/outcome
- Custom event, note, photo, or correction

An event can apply to the whole cook or to one or more dishes. Store **when it happened** separately from **when it was entered** so a late note can be placed correctly without changing other events.

### Targets and results

- Store a food-safety target separately from a personal tenderness, texture, or serving target.
- For each target, record the target value, unit, scope (dish/probe), type, and optional source or explanation.
- Let users record final temperature, total cook time, rest time, and whether the meat passed a probe/tenderness check.
- Offer optional ratings for bark, tenderness, juiciness, smoke, seasoning, and overall result.
- Include “What would you change next time?” as a plain note.
- Do not force users to give a numerical rating or turn a subjective impression into a measured fact.

### Photos and attachments

- Allow multiple photos per cook or dish: raw meat, preparation, probe placement, smoker setup, cook progress, bark, finished/cut result, or anything else.
- Each photo can have a caption, captured time, dish association, and timeline event association.
- Keep original image files in the local library. Do not use photos as evidence for automatically calculated measurements unless the user explicitly enters or confirms those values.
- Include original images in the complete archive. The Excel workbook lists photo IDs, captions, timestamps, and relative archive paths; it does not embed large images by default.

### Device and probe data

- Support manual readings and readings from compatible controllers/loggers.
- Record each sample’s timestamp, sensor/probe ID and name, assigned dish when known, measurement type, value, unit, source, and quality/status.
- Distinguish pit/ambient temperature, food-probe temperature, setpoint, and manually entered temperature.
- Record sensor reassignment, disconnection, stale periods, missing samples, and device events without presenting missing data as measured values.
- Preserve original sensor precision and timestamps. Any smoothing, resampling, stall detection, or rate-of-rise calculation is a separately labeled derived result.

## Suggested entry flow

1. Tap **Start cook**.
2. Optionally name the cook and select a smoker.
3. Tap **Add dish** and enter a simple name or cut; optionally add type and weight.
4. Start logging. Add prep, rub ingredients, photos, targets, and notes whenever convenient.
5. During the cook, use visible actions such as **Meat on**, **Spritz**, **Wrap**, **Temperature**, **Photo**, and **Note**.
6. Finish the cook and optionally record rest, result, and ratings.

Templates may suggest fields that are useful for a chosen cut, but should never prescribe a method, target temperature, or duration. Users can skip the suggestions.

## Export and portability

Put export and restore in **Settings** and on a completed cook’s menu. Offer plain choices:

1. **Excel workbook (.xlsx)** — for opening, sorting, filtering, charting, and comparing.
2. **Complete archive (.zip)** — for full-fidelity backup, moving to another device, or using the data in another tool.
3. **CSV files** — for a user who wants a particular table, such as all temperature readings.

The same choices should work for one cook or the entire local library. Show the number of cooks, samples, and photos included before saving or sharing.

### Excel workbook

Use normal worksheets with header rows, filters, frozen top rows, Excel tables, and numeric cells for numeric data. No merged data headers, hidden required columns, or unexplained abbreviations.

| Sheet | One row per | Main contents |
|---|---|---|
| Cook Summary | Cook | Name, start/end, duration, smoker, dishes, outcome, notes, data-quality summary |
| Dishes | Dish | Cook ID, food type, cut, weight and unit, starting condition, placement |
| Ingredients | Ingredient use | Cook/dish, prep stage, ingredient/product, brand, amount and unit, applied time |
| Timeline | Event | Cook/dish, event type, occurred time, entered time, source, notes |
| Readings | Sensor sample | Cook/dish, UTC and local time, probe, measurement type, numeric value, unit, source, quality |
| Targets and Results | Target or result | Target type, target/result value and unit, scope, source/explanation |
| Devices and Probes | Device or probe | Device identity, probe identity/name, role, assignment, capability |
| Photos | Photo | Photo ID, cook/dish/event, caption, capture time, archive path |
| Derived Metrics | Calculated metric | Metric, value/unit, method/version, input time range, source record IDs |

Temperature readings use a **long/tidy table**: one row per probe sample, rather than a separate column for every probe. This makes the workbook usable when a cook has different probe counts and makes filtering and analysis straightforward. Include simple trend charts when a cook is selected; the underlying Readings sheet remains the source for exact values.

Excel does not preserve time-zone offsets in its native date cells. Keep a readable local date/time and explicit UTC offset/time-zone column, alongside an ISO 8601 UTC timestamp, so times remain unambiguous after export.

A worksheet has a limit of 1,048,576 rows. Split very large readings across numbered Readings sheets when practical. Never silently omit or downsample raw records. If the workbook would exceed practical Excel limits, explain that and include every raw sample in the CSV files in the complete archive.

### CSV files

CSV exports should be UTF-8 with a header row and one row per record. Use stable column names, comma delimiters, quoted text where needed, numeric values without thousands separators, and an explicit unit column. Use ISO 8601 timestamps with a UTC value and a local timestamp/offset. Represent missing values as blank, never as zero. Do not round raw readings during export.

Recommended tables:

| File | Row grain |
|---|---|
| cooks.csv | One cook session |
| dishes.csv | One dish in a cook |
| ingredients.csv | One ingredient used at one preparation stage |
| events.csv | One timeline event |
| readings.csv | One sensor sample |
| targets.csv | One target |
| results.csv | One recorded outcome or rating |
| devices.csv | One device |
| probes.csv | One probe |
| photos.csv | One attachment and its relationships |
| derived_metrics.csv | One calculated metric with method and input range |

Use stable IDs in every table (cook_id, dish_id, event_id, probe_id, etc.) so a spreadsheet or analysis tool can join related rows. Keep categorical values understandable and include a schema/data dictionary in the archive.

### Complete archive (.zip)

The ZIP is the full-fidelity, restorable export. It should contain:

- A manifest.json with archive format and schema version, export time, app version, record counts, and included files.
- A short README.txt explaining the folder structure, field names, units, and how to restore or analyze the archive.
- CSV tables for cooks, dishes, ingredients, events, readings, targets, results, devices, probes, photos, and derived metrics.
- A structured JSON record for each cook, preserving relationships, optional/custom fields, full precision, and edit/source metadata.
- Original photos and attachments, connected by stable IDs and relative paths.
- A data dictionary/schema document and any migration/version notes needed to import a later archive.

Use standard ZIP, JSON, and CSV formats. The archive should not depend on a PitTech account or server and should be importable by PitTech after validation. Import must preview the number of cooks and attachments before merging or restoring.

### Timestamp, units, provenance, and data integrity

- Use a stable ID for every cook, dish, event, ingredient use, probe, sample, target, and photo.
- Preserve both occurred/measurement time and entry/recorded time when they differ.
- Store canonical timestamps in UTC and include the recorded local time with offset/time-zone for human context.
- Every numeric measurement has an explicit unit. Keep original entered values and units; normalized values for cross-cook comparison are additional fields, not replacements.
- Every automatic record identifies its originating controller/logger and sensor where available. Manual entries are labeled as manual.
- Keep measured/raw and derived/calculated data in separate tables or clearly distinguished columns.
- Preserve raw sample precision; record any analysis algorithm/version and time range for derived values.
- Keep deletions out of ordinary exports, but include relevant provenance for surviving records. If a user chooses an audit-style archive in the future, deleted-record history must be explicit and separate from the current cook view.

## Reference guides used to shape the fields

These are examples of the practical steps and details a smoker may want to remember. PitTech records a user’s own process; these guides do not define required fields or default recommendations.

- [Pit Boss: Easy Smoked Brisket](https://www.pitboss-grills.com/blogs/blog/easy-smoked-brisket)
- [Pit Boss: Classic Smoked Brisket](https://www.pitboss-grills.com/pages/recipes/classic-smoked-brisket)
- [Traeger: Smoked Pork Butt / Pulled Pork](https://www.traeger.com/learn/pulled-pork)
- [Traeger: Smoking Pork Loin](https://www.traeger.com/learn/how-to-smoke-pork-loin)
- [Traeger: Brisket Spritz](https://www.traeger.com/learn/brisket-spritz)
- [USDA FSIS: Thermometers when smoking meat](https://ask.fsis.usda.gov/article/Do-you-need-a-thermometer-when-smoking-meat)
- [USDA FSIS: Safe internal temperatures for meat and poultry](https://ask.fsis.usda.gov/article/What-is-a-safe-internal-temperature-for-cooking-meat-and-poultry)
- [Microsoft Support: Excel specifications and limits](https://support.microsoft.com/en-us/excel/excel-specifications-and-limits)
