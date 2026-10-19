import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  CommunityAggregates, COMMUNITY_METRICS_V1, COMMUNITY_METRICS_VERSION,
  MemoryJobRunStore,
} from '../src/community-aggregation.ts';
import { COMMUNITY_TAXONOMY_V1 } from '../src/community-taxonomy.ts';
import type { CommunityMeasurement } from '../src/community-projection.ts';

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const NOW = Date.UTC(2026, 9, 18, 12);
const production = { cutoffMillis: NOW, taxonomy: COMMUNITY_TAXONOMY_V1, metrics: COMMUNITY_METRICS_V1 };

type Contribution = {
  itemId: string; revisionId: string; status: string; collectedAtMillis: number;
  attemptId: string; dishId: string; foodType: string; cut: string | null;
  weightGrams: number | null; method: string | null; heatingMillis: number | null;
  finalTemp: { value: number; unit: '°C' | '°F' } | null;
  foodOnOffsetMillis?: number | null; wrapAtOffsetMillis?: number | null; spritzed?: boolean;
  measurements?: CommunityMeasurement[]; gaps?: { fromMillis: number; toMillis: number }[];
};
const base = (index: number, overrides: Partial<Contribution> = {}): Contribution => ({
  itemId: id(1000 + index), revisionId: id(2000 + index), status: 'active',
  collectedAtMillis: NOW - 1000, attemptId: id(3000 + index), dishId: id(4000 + index),
  foodType: 'Pork', cut: 'Shoulder', weightGrams: 3855, method: 'Smoke',
  heatingMillis: 27600000, finalTemp: { value: 100, unit: '°C' },
  foodOnOffsetMillis: 0, wrapAtOffsetMillis: null, spritzed: false,
  measurements: [], gaps: [],
  ...overrides,
});
const metric = (built: { figures: readonly { metricId: string; summary: unknown }[] }, metricId: string): Record<string, unknown> =>
  built.figures.find((f) => f.metricId === metricId)!.summary as Record<string, unknown>;

test('community median heating time pins hand-computed median and IQR', () => {
  const raw = Array.from({ length: 10 }, (_, i) => base(i, { heatingMillis: (i + 1) * 3600000 }));
  const built = new CommunityAggregates(new MemoryJobRunStore(), () => NOW).build(raw, production);
  const summary = metric(built, 'median-heating-time-v1');
  // Values 1..10h: median (5+6)/2 = 5.5h, p25 = 3.25h, p75 = 7.75h, IQR = 4.5h.
  assert.deepEqual(summary, {
    n: 10, medianHeatingMillis: 19800000, p25HeatingMillis: 11700000, p75HeatingMillis: 27900000,
    iqrMillis: 16200000, minHeatingMillis: 3600000, maxHeatingMillis: 36000000,
  });
  const figure = built.figures.find((f) => f.metricId === 'median-heating-time-v1')!;
  assert.equal(figure.metricVersion, COMMUNITY_METRICS_VERSION);
  assert.equal(figure.n, 10);
  assert.equal(figure.status, 'visible');
});

test('community curve percentiles pin hand-computed p10/p50/p90 with coverage', () => {
  const raw = Array.from({ length: 10 }, (_, i) => base(i, {
    measurements: [
      { offsetMillis: 1000, role: 'pit', valueCelsius: 100 + i, quality: 'measured' },
      { offsetMillis: 2000, role: 'pit', valueCelsius: 100 + i, quality: 'measured' },
      { offsetMillis: 1000, role: 'food', valueCelsius: 50 + i, quality: 'measured' },
      { offsetMillis: 2000, role: 'food', valueCelsius: 50 + i, quality: 'measured' },
    ],
  }));
  const built = new CommunityAggregates(new MemoryJobRunStore(), () => NOW).build(raw, production);
  const summary = metric(built, 'pit-food-curve-percentiles-v1') as {
    n: number; kind: string; intervals: { pit: { n: number; coverage: number; p10: number; p50: number; p90: number }; food: { n: number; coverage: number; p10: number; p50: number; p90: number } }[];
  };
  assert.equal(summary.n, 10);
  assert.equal(summary.kind, 'percentile-bands');
  assert.equal(summary.intervals.length, 1);
  // Pit medians 100..109: p10 = 100.9, p50 = 104.5, p90 = 108.1.
  assert.deepEqual(summary.intervals[0].pit, { n: 10, coverage: 1, p10: 100.9, p50: 104.5, p90: 108.1 });
  assert.deepEqual(summary.intervals[0].food, { n: 10, coverage: 1, p10: 50.9, p50: 54.5, p90: 58.1 });
});

test('community curve resampling drops sparse intervals and gap-hit intervals without interpolating', () => {
  // Nine attempts share interval 0 after one gap-hit drops; three reach
  // interval 1; prep readings (negative elapsed) are excluded.
  const dense = (i: number, withSecond: boolean): Contribution => base(i, {
    measurements: [
      { offsetMillis: 1000, role: 'food' as const, valueCelsius: 60, quality: 'measured' as const },
      { offsetMillis: 2000, role: 'food' as const, valueCelsius: 61, quality: 'measured' as const },
      ...(withSecond ? [
        { offsetMillis: 901000, role: 'food' as const, valueCelsius: 70, quality: 'measured' as const },
        { offsetMillis: 902000, role: 'food' as const, valueCelsius: 71, quality: 'measured' as const },
      ] : []),
      { offsetMillis: -5000, role: 'food' as const, valueCelsius: 20, quality: 'measured' as const },
    ],
    gaps: i === 0 ? [{ fromMillis: 1500, toMillis: 1500 }] : [],
  });
  const raw = Array.from({ length: 10 }, (_, i) => dense(i, i < 3));
  const built = new CommunityAggregates(new MemoryJobRunStore(), () => NOW).build(raw, production);
  const summary = metric(built, 'pit-food-curve-percentiles-v1') as {
    n: number; intervals: { startElapsedMillis: number; food: { n: number; p50: number | null } }[];
  };
  // Attempt 0 drops interval 0 (gap) but stays eligible via interval 1, so the
  // figure itself is visible (n=10); interval 0 has 9 contributors (suppressed
  // bands) and interval 1 has 3 (also suppressed).
  assert.equal(summary.n, 10);
  assert.equal(summary.intervals.length, 2);
  assert.equal(summary.intervals[0].food.n, 9);
  assert.equal(summary.intervals[0].food.p50, null);
  assert.equal(summary.intervals[1].startElapsedMillis, 900000);
  assert.equal(summary.intervals[1].food.n, 3);
  assert.equal(summary.intervals[1].food.p50, null);
});

test('community stall summary pins prevalence and medians while excluding sparse attempts', () => {
  const stalled = (i: number): Contribution => {
    const measurements: CommunityMeasurement[] = [];
    for (let k = 0; k < 8; k++) {
      measurements.push({ offsetMillis: k * 900000, role: 'food', valueCelsius: 70 + i + (k % 2 ? 0.5 : -0.5), quality: 'measured' });
    }
    return base(i, { measurements });
  };
  const rising = (i: number): Contribution => {
    const measurements: CommunityMeasurement[] = [];
    for (let k = 0; k < 8; k++) {
      measurements.push({ offsetMillis: k * 900000, role: 'food', valueCelsius: 60 + k * 2, quality: 'measured' });
    }
    return base(i, { measurements });
  };
  const sparse = (i: number): Contribution => base(i, {
    measurements: [
      { offsetMillis: 0, role: 'food', valueCelsius: 65, quality: 'manual' },
      { offsetMillis: 3600000, role: 'food', valueCelsius: 66, quality: 'manual' },
      { offsetMillis: 7000000, role: 'food', valueCelsius: 65.5, quality: 'manual' },
    ],
  });
  // Six stalled (medians 70..75), four rising-but-dense (assessable, no stall),
  // two sparse manual (excluded from n entirely).
  const raw = [
    ...Array.from({ length: 6 }, (_, i) => stalled(i)),
    ...Array.from({ length: 4 }, (_, i) => rising(100 + i)),
    sparse(200), sparse(201),
  ];
  const built = new CommunityAggregates(new MemoryJobRunStore(), () => NOW).build(raw, production);
  const figure = built.figures.find((f) => f.metricId === 'stall-summary-v1')!;
  assert.equal(figure.n, 10);
  assert.equal(figure.status, 'visible');
  const summary = figure.summary as {
    n: number; nStalled: number; prevalence: number;
    startElapsedMillis: { median: number }; durationMillis: { median: number }; tempCelsius: { p25: number; median: number; p75: number };
  };
  assert.equal(summary.n, 10);
  assert.equal(summary.nStalled, 6);
  assert.equal(summary.prevalence, 0.6);
  assert.equal(summary.startElapsedMillis.median, 0);
  assert.equal(summary.durationMillis.median, 7200000);
  // Stall temps 70..75 (R-7): median 72.5, p25 71.25, p75 73.75.
  assert.deepEqual(summary.tempCelsius, { p25: 71.25, median: 72.5, p75: 73.75 });
});

test('community stall detection honors drift, gap, and density thresholds', () => {
  const plateau = (value: number, count = 8): CommunityMeasurement[] =>
    Array.from({ length: count }, (_, k) => ({ offsetMillis: k * 900000, role: 'food' as const, valueCelsius: value, quality: 'measured' as const }));
  const drifted = base(0, {
    measurements: Array.from({ length: 8 }, (_, k) => ({ offsetMillis: k * 900000, role: 'food' as const, valueCelsius: 60 + k, quality: 'measured' as const })),
  });
  const gapped = base(1, { measurements: plateau(70), gaps: [{ fromMillis: 1800000, toMillis: 1800000 }] });
  const sparse = base(2, {
    measurements: [
      { offsetMillis: 0, role: 'food', valueCelsius: 70, quality: 'manual' },
      { offsetMillis: 3600000, role: 'food', valueCelsius: 70.5, quality: 'manual' },
    ],
  });
  const raw = [drifted, gapped, sparse, ...Array.from({ length: 7 }, (_, i) => base(10 + i, { measurements: plateau(70) }))];
  const built = new CommunityAggregates(new MemoryJobRunStore(), () => NOW).build(raw, production);
  const figure = built.figures.find((f) => f.metricId === 'stall-summary-v1')!;
  // Drifted is assessable without stall; gapped and sparse are not assessable.
  assert.equal(figure.n, 8);
  assert.equal(figure.status, 'insufficient_data');
  assert.equal(figure.summary, null);
});

test('community wrap-spritz prevalence pins hand-computed shares without causation', () => {
  // Figures are per bucket and the bucket key carries the normalized method,
  // so one visible figure holds one method label; case/whitespace variants of
  // the same method share the bucket and the label.
  const raw = Array.from({ length: 10 }, (_, i) => base(i, {
    wrapAtOffsetMillis: i < 6 ? 3600000 : null,
    spritzed: i < 3,
    method: i < 4 ? 'Smoke' : i < 7 ? ' SMOKE ' : 'smoke',
  }));
  const built = new CommunityAggregates(new MemoryJobRunStore(), () => NOW).build(raw, production);
  const summary = metric(built, 'wrap-spritz-prevalence-v1') as {
    n: number; nWrapped: number; wrapPrevalence: number; nSpritzed: number; spritzPrevalence: number;
    methods: { label: string; n: number; prevalence: number }[]; nMethodRecorded: number; nMethodUnrecorded: number;
  };
  assert.deepEqual(summary, {
    n: 10, nWrapped: 6, wrapPrevalence: 0.6, nSpritzed: 3, spritzPrevalence: 0.3,
    methods: [
      { label: 'smoke', n: 10, prevalence: 1 },
    ],
    nMethodRecorded: 10, nMethodUnrecorded: 0,
  });
});

test('community real metrics suppress n=9 and show n=10 with n and date on every figure', () => {
  const full = (i: number): Contribution => base(i, {
    measurements: [
      { offsetMillis: 1000, role: 'pit', valueCelsius: 120, quality: 'measured' },
      { offsetMillis: 2000, role: 'pit', valueCelsius: 121, quality: 'measured' },
      { offsetMillis: 1000, role: 'food', valueCelsius: 60, quality: 'measured' },
      { offsetMillis: 2000, role: 'food', valueCelsius: 61, quality: 'measured' },
    ],
  });
  const aggregates = new CommunityAggregates(new MemoryJobRunStore(), () => NOW);
  const nine = aggregates.build(Array.from({ length: 9 }, (_, i) => full(i)), production);
  const ten = aggregates.build(Array.from({ length: 10 }, (_, i) => full(i)), production);
  assert.ok(nine.figures.length > 0);
  assert.equal(ten.figures.length, nine.figures.length);
  for (const figure of nine.figures) {
    assert.equal(figure.status, 'insufficient_data');
    assert.equal(figure.summary, null);
    assert.equal(figure.computedDate, '2026-10-18');
  }
  for (const figure of ten.figures) {
    assert.equal(figure.status, 'visible');
    assert.notEqual(figure.summary, null);
    assert.equal(figure.computedDate, '2026-10-18');
  }
  // Heating and wrap carry the full cell n; curve carries its resampled n.
  const heating = ten.figures.find((f) => f.metricId === 'median-heating-time-v1')!;
  const wrap = ten.figures.find((f) => f.metricId === 'wrap-spritz-prevalence-v1')!;
  assert.equal(heating.n, 10);
  assert.equal(wrap.n, 10);
});

test('community real metrics exclude missing fields from one metric only', () => {
  const raw = Array.from({ length: 10 }, (_, i) => base(i, {
    heatingMillis: i < 10 ? 27600000 : null,
    foodOnOffsetMillis: i < 4 ? 0 : null,
    measurements: i < 4 ? [
      { offsetMillis: 1000, role: 'food', valueCelsius: 60, quality: 'measured' as const },
      { offsetMillis: 2000, role: 'food', valueCelsius: 61, quality: 'measured' as const },
    ] : [],
  }));
  const built = new CommunityAggregates(new MemoryJobRunStore(), () => NOW).build(raw, production);
  const heating = built.figures.find((f) => f.metricId === 'median-heating-time-v1')!;
  const curve = built.figures.find((f) => f.metricId === 'pit-food-curve-percentiles-v1')!;
  const wrap = built.figures.find((f) => f.metricId === 'wrap-spritz-prevalence-v1')!;
  assert.equal(heating.n, 10);
  assert.equal(heating.status, 'visible');
  assert.equal(curve.n, 4);
  assert.equal(curve.status, 'insufficient_data');
  assert.equal(wrap.n, 10);
  assert.equal(wrap.status, 'visible');
});

test('community real taxonomy and metrics keep repeat jobs byte-identical and units equal', () => {
  const raw = Array.from({ length: 10 }, (_, i) => base(i, {
    finalTemp: { value: [32, 68, 212, -40][i % 4], unit: '°F' },
  }));
  const celsius = Array.from({ length: 10 }, (_, i) => base(i, {
    finalTemp: { value: [0, 20, 100, -40][i % 4], unit: '°C' },
  }));
  const aggregates = new CommunityAggregates(new MemoryJobRunStore(), () => NOW);
  const first = aggregates.build(raw, production);
  const second = new CommunityAggregates(new MemoryJobRunStore(), () => NOW + 3600000).build(structuredClone(raw), production);
  assert.equal(first.bytes, second.bytes);
  assert.equal(new CommunityAggregates(new MemoryJobRunStore(), () => NOW).build(celsius, production).bytes, first.bytes);
  for (const figure of first.figures) {
    assert.equal(figure.taxonomyVersion, 'community-taxonomy-v1');
    assert.equal(figure.bandVersion, 'community-bands-v1');
    assert.equal(figure.metricVersion, 'community-metrics-v1');
  }
});
