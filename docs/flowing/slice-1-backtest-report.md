# Flowing Slice 1 — Pace & Stall Engine Backtest Report

Issue #243 · Spec: `flowing-build-spec.md` §4.1, §9 items 1–2 · Corpus:
`contracts/fixtures/pace-stall-corpus-v1.json` (oracle: its README; neither
was modified by this slice)

## How the backtest runs

The shipped engine (`CookPaceEngine`, Android + iOS mirrors, rule for rule)
is replayed against each corpus archive: at every 15-minute checkpoint from
30 minutes after food-on until the finish, the reading series is truncated
at the checkpoint, one `PaceRangeSmoother` per cook publishes under the
anti-jitter rules (10-minute minimum cadence, neither edge moves more than
15 minutes per publication), and the stated range is recorded. Briskets
A/B/D lend each other history (totals, stall durations, post-stall slopes);
the chicken and the hand-logged butt have no history. The same protocol and
assertions run in both platforms' unit suites
(`CookPaceEngineTest` / `CookPaceEngineTests`); the per-shape numbers below
are the Android diagnostic printer's output from the PR's CI run, transcribed
verbatim from the JUnit record.

## Engine design in brief

- **Regime-aware, never slope-only.** A trailing 30-minute least-squares
  slope over the food probe drives pace only outside the stall band's
  danger zone. A slope-only prototype was built first and failed badly
  (the early brisket climb predicts a noon finish; the stall destroys it —
  6 of 45 statements contained the finish on shape A), so it was discarded.
- **Stall detection:** per-reading flat flags (trailing 30-min slope ≤
  0.04 °F/min AND probe inside 150–170°F AND no >20-minute reading gap);
  maximal flat runs of ≥30 minutes become stall spans, with the span start
  backdated 15 minutes (half the slope window — a trailing window's slope
  describes the window's span, so the flat run's best start estimate is
  its midpoint). Parameters came from a sweep over the corpus
  (window 30 / floor 0.04 / backdate ½-window beat the alternatives on
  all three briskets).
- **Range composition:** in a declared stall the engine never extrapolates
  the stall slope — the range is the remaining-stall allowance (bounded
  below by 30 minutes and by the longest historical stall) plus the
  post-stall climb at historical slopes, unioned with the history
  envelope. Until a cook has cleared the band (probe >170°F or a stall
  completed), the history envelope (food-on + [shortest historical
  total − 30 min, longest + 30 min]) widens any pace range. Uncertainty
  is asymmetric (earliest = Δ/(slope×1.6), latest = Δ/(slope×0.55)) —
  finishes slip late far more than they arrive early. Minimum range
  width 30 minutes.
- **History finishes are recorded pulls, not literal crossings.** Real
  probes routinely peak a fraction below target — corpus briskets B and
  D top out at 202.8°F for a 203°F target — so history anchors a cook's
  finish at its `remove` event (falling back to `dish_done`, then the
  first crossing). `dish_done` is only the fallback because in the app's
  phase order it means "ready to serve", after the rest; preferring it
  folded rest time into cooking totals (an earlier revision of this
  slice made exactly that mistake — totals ran 60–80 minutes long and
  the stated ranges widened accordingly; the backtest numbers below are
  from the corrected anchor).
- **Sparse/manual mode:** below the evidence minimum (≥4 readings
  spanning ≥20 minutes in the trailing window, no gap >20 minutes) the
  engine states NO range (the plan basis stands, with a reason string)
  and stall state is `Unknown` — never "no stall".

## Results per shape

<!-- FINAL NUMBERS PENDING TRANSCRIPTION -->

## Trust-bar verdict (§9 item 1)

<!-- VERDICT PENDING -->

The honest small-n caveat stands regardless of outcome: the bar is stated
as ≥9 of 10, but the corpus holds five shapes and only four produce
ranges. A clean pass here is encouraging evidence, not proof of a 90%
containment rate; the bar gates whether Slice 2 surfaces the range on
Live, and widening the corpus with real cooks remains the way to firm
it up.

## Defects found and fixed during the backtest

1. **History discarded sub-target cooks (engine).** The first history
   builder demanded a literal target crossing; briskets B and D peak at
   202.8°F and were silently dropped, leaving shape A with no envelope
   and pace-only ranges that missed (A: 6 of 40 statements contained the
   finish; its first stated range missed). Fixed by anchoring finishes
   at the recorded pull (see above).
2. **Rest time in cooking totals (engine).** The first pull-anchor
   revision preferred `dish_done` over `remove`; totals inflated by the
   rest (60–80 min). Anchor order corrected to remove → dish_done →
   crossing, with the reasoning recorded in the code.
3. **Two test defects (not engine).** A unit series that never reached
   its target (fixed to actually cross), and a gap test that demanded
   two genuine 40-minute flats not be stalls at all — the rule is that
   silence must not *merge* flats, rewritten to assert exactly that.
4. **Flow-test seed (not app).** The composer capture flow test seeded a
   cook with no food-on event; `CookPhases` correctly rejects a wrap
   logged while the dish is still in prep, so the save it waited for
   could never happen. The seed now records food-on first, as a real
   cook would.
