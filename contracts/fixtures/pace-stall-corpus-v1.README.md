# Pace/stall corpus v1 — the Slice 1 backtest oracle

`pace-stall-corpus-v1.json` holds five recorded-cook **shapes** for the Flowing
program's pace & stall engine (build spec §4.1). They are hand-built curves in
the shared archive vocabulary — the same `cooks` / `dishes` / `events` /
`readings` rows the insights-calculation cases use — with anchor-defined,
cook-shaped profiles (real plateaus, a rain dip, a pellet dip), **not** real
user data and not sine waves. Each case carries an `expected` block: this file
states what that block means, so Slice 1's backtest has an oracle to pass or
fail against — including the spec §9 trust bar (a stated finish range must
contain the actual finish in ≥ 9 of 10 corpus cooks; stall detection must land
within ±20 min of the true stall start in every corpus brisket).

## How consumers load it

- **Android:** `app/build.gradle.kts` puts all of `contracts/fixtures/` on the
  unit-test resource path, so domain tests read `/pace-stall-corpus-v1.json`
  directly (the `InsightsCalculationsTest` pattern).
- **iOS:** the file is a `PitTechTests` bundle resource via `ios/project.yml`
  (the `insights-calculations-cases-v1.json` pattern).
- The engine does not exist yet (Slice 1). Until its tests land, this corpus is
  inert data plus this oracle — nothing in the apps reads it at runtime.

## Reading the rows

- Timestamps are UTC epoch millis; each cook row carries
  `startedTimeZoneId: "America/New_York"` and all wall-clock times below are
  that zone (EDT, UTC−4 — every corpus date is before the Nov 1, 2026
  fallback).
- Readings are 5-minute samples for controller cooks (`source:
  "controller_cloud"`), pit and food-probe series interleaved in one array;
  filter by `measurementType`. Values carry a small deterministic wobble
  (±2°F pit, ±0.4°F probe) so plateaus are flat-but-real, not ruler-straight.
- `expected.targetTempF` is the dish's finish target; `finishUtcMillis` is
  when the probe first reaches it (and, in these shapes, the `remove` event).
  `stallStartUtcMillis` / `stallEndUtcMillis` are the plateau's true edges:
  the probe inside the 150–170°F band with its slope at ~zero.

## The five shapes

### A — `canonical brisket — rain dip and a 1h50m stall` (Sat Oct 31, 2026)

The book's canonical Saturday brisket. On 3:10 AM, setpoint 250°F. Pit holds,
then **rain knocks it to 221°F at 11:05 AM**, recovering to ~248°F by 11:50 AM.
Probe enters the band late morning and **stalls 12:55–2:45 PM** plateaued at
~165°F; paper wrap at 2:00 PM (mid-stall); climbs out to **203°F at 4:45 PM**;
serve 6:00 PM.

Oracle: a stall is detected within ±20 min at both edges; the rain dip is a
pit event and must never be read as a stall signal (no stall may be reported
before the probe is in the band); a finish range stated from the last 30
minutes of readings at any point after the stall contains 4:45 PM.

### B — `brisket — 2h40m foil stall, 13h10m total` (Sat Oct 10, 2026)

The comparison corpus's foil anchor (the book's "Oct 12" brisket profile).
On 3:10 AM, setpoint 250°F, pit steady. **Stall 10:40 AM–1:20 PM (2h 40m)**
plateaued at ~161°F; foil wrap 11:20 AM, inside the stall; **203°F at
4:20 PM** — food-on to remove is exactly **13h 10m**.

Oracle: stall duration computes to 2h 40m (±20 min at each end); the stall's
end is the probe leaving the plateau, **not** the 11:20 AM wrap; total
duration computes to 13h 10m.

### C — `chicken halves — fast, no stall` (Wed Oct 21, 2026)

On 4:15 PM, setpoint 275°F, steady climb the whole way: 165°F at 5:50 PM,
off at 6:00 PM (1h 45m total). The probe crosses the 150–170°F band in the
final minutes, still climbing steeply.

Oracle: **no stall** — presence in the band is not a stall without a
sustained flat slope; a pace range is available early (dense readings, steady
slope) and contains the 5:50 PM finish.

### D — `overnight brisket — 5h slow-creep stall` (Fri Oct 16 → Sat Oct 17, 2026)

On 10:00 PM, setpoint 240°F. The probe reaches the band by 2:30 AM and
**creeps 156→163°F until 7:30 AM — five hours** of near-zero slope. A pellet
dip takes the pit to 228°F at 3:00 AM, recovering by 3:40 AM. Wrap at
7:30 AM; 203°F at 11:40 AM; serve 1:00 PM.

Oracle: the creep **is** a stall (in-band, slope under the floor, sustained)
— an engine that requires a ruler-flat probe fails this case; mid-stall, the
finish range widens or the basis reverts to the plan rather than sliding the
finish earlier on the creep's slope (the anti-jumpiness case); the 3:00 AM
pit dip does not end, restart, or split the stall.

### E — `boston butt — hand-logged, six readings, no controller` (Mon Oct 12, 2026)

The manual-mode case (the book's Spread 07 card C). Offset smoker, no
controller: **six hand readings across 9h 40m** — pit checks at 6:05 AM
(225°F), 8:00 AM (248°F), 10:30 AM (251°F), 12:15 PM (244°F), 2:00 PM
(249°F), and a single food-probe reading, **203°F at 3:45 PM**, which is the
finish. Spritz 10:30 AM, foil wrap 11:20 AM, serve 5:00 PM. All readings
carry `source: "manual"`.

Oracle: **no pace range is ever produced** — one probe reading cannot
support a slope, so the plan window is the only finish guidance and its basis
must say so; stall state is **unknown**, never "no stall" (there is no probe
series to judge); the six readings are still first-class data — they are the
cook's record, and the 203°F reading is the finish evidence.

## Changing this corpus

The trust bar (§9) is calibrated against these five shapes. Adding a shape
is welcome; changing an existing shape's anchors or its `expected` block
invalidates prior backtest results and must be called out in the PR that
does it, with the backtest re-run.
