# Native tabular exports

Android and iOS export readings CSV and an Excel workbook for the local library or one meal.
The complete archive includes the shared human-readable CSV tables alongside authoritative JSON
and independently identified photos. Tabular export never changes the stored measurement unit,
precision, monitoring state, reminder state or source record.

The shared table set covers meals, dishes, ingredients, timeline, readings, targets/results,
devices/probes, photos, log/reading links, lifecycle/target history, recording/assignment history,
saved guidance, reference photos and reminders. The workbook adds meal and measured-channel summaries.
Numbers remain numeric cells; missing values remain empty. Dates include UTC plus named-zone/local
columns. CSV quotes commas, quotes and newlines and prefixes formula-like text. Workbook user text
uses literal inline strings rather than formulas.

Summaries use cook, dish, logical probe identity/name, measurement role and original unit.
Identically named food/pit channels or two independent probes are not combined. Method v2 reports
valid sample count, minimum, maximum and last measured value; it performs no interpolation.

Excel permits 1,048,576 rows and 32,767 characters per cell. Larger text is represented by a clearly
marked cell and ordered rows in **Long Values** (`Sheet`, `Cell`, `Part`, `Text`). Joining parts in
numeric order recovers the original value, including Unicode. Unsupported XML controls cause an
explicit export error, never silent removal.

Both writers stage a workbook before delivering it and write worksheets incrementally. Per-table
expanded output is bounded to 45 MiB, combined expanded output to 300 MiB, and compressed workbooks
to 350 MiB. A limit error requests a smaller scope or complete archive; measurements are not omitted.
Cook Packs retain their separately declared 64 MiB shared-file quota.

Native integration tests cover literal formula-like text, embedded quotes/newlines, fractional
Celsius values, numeric cell types, lossless Unicode chunks, channel separation and meal scoping.
Actual writer outputs are embedded at `fixtures/portable.xlsx` and `fixtures/readings.csv` in the
existing portable CI artifacts. Cross-platform fixture exchange and independent reader validation
must pass before PR03 is accepted.

Sources: [Excel limits](https://support.microsoft.com/en-us/excel/excel-specifications-and-limits)
and [SpreadsheetML cell types](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.spreadsheet.cell).
