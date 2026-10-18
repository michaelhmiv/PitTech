import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import {
  CommunityAggregates, COMMUNITY_ALGORITHM_VERSION, ELIGIBLE_ATTEMPTS_SQL,
  MemoryJobRunStore, STUB_METRICS_NON_AUTHORITATIVE,
} from '../src/community-aggregation.ts';
import {
  dedupeKey, extractRawContributions, projectEligibleAttempts, rawContribution,
  storyStubFields, toCelsius, type TemperatureUnit,
} from '../src/community-projection.ts';
import { STUB_TAXONOMY_NON_AUTHORITATIVE } from '../src/community-taxonomy.ts';

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const NOW = Date.UTC(2026, 9, 18, 12);
type FixtureContribution = {
  itemId: string; revisionId: string; status: string; collectedAtMillis: number;
  attemptId: string; dishId: string; foodType: string; cut: string | null;
  weightGrams: number | null; method: string | null; heatingMillis: number | null;
  finalTemp: { value: number; unit: TemperatureUnit } | null;
};
type ContributionOverrides = {
  itemId?: string; revisionId?: string; status?: string; collectedAtMillis?: number;
  attemptId?: string; dishId?: string; foodType?: string; cut?: string | null;
  weightGrams?: number | null; method?: string | null; heatingMillis?: number | null;
  finalTemp?: { value: number; unit: TemperatureUnit } | null;
};
const contribution = (index: number, overrides: ContributionOverrides = {}): FixtureContribution => ({
  itemId: id(1000 + index), revisionId: id(2000 + index), status: 'active',
  collectedAtMillis: NOW - 1000, attemptId: id(3000 + index), dishId: id(4000 + index),
  foodType: 'Pork', cut: 'Shoulder', weightGrams: 3855, method: 'Smoke',
  heatingMillis: 27600000, finalTemp: { value: 100, unit: '°C' },
  ...overrides,
});
const options = {
  cutoffMillis: NOW, taxonomy: STUB_TAXONOMY_NON_AUTHORITATIVE, metrics: STUB_METRICS_NON_AUTHORITATIVE,
};

test('community repeat runs over the same snapshot are byte-identical', () => {
  const raw = Array.from({ length: 12 }, (_, i) => contribution(i));
  const first = new CommunityAggregates(new MemoryJobRunStore(), () => NOW).build(raw, options);
  const second = new CommunityAggregates(new MemoryJobRunStore(), () => NOW + 3600000).build(structuredClone(raw), options);
  assert.equal(first.bytes, second.bytes);
  assert.equal(first.outputSha256, second.outputSha256);
  assert.deepEqual(first.figures, second.figures);
  assert.equal(first.inputSha256, second.inputSha256);
  const nextDay = new CommunityAggregates(new MemoryJobRunStore(), () => NOW + 86400000).build(raw, options);
  assert.equal(nextDay.figures.length, first.figures.length);
  assert.ok(nextDay.figures.every((figure) => figure.computedDate === '2026-10-19'));
  assert.ok(first.figures.every((figure) => figure.computedDate === '2026-10-18'));
  assert.deepEqual(
    nextDay.figures.map(({ computedDate: _next, ...rest }) => rest),
    first.figures.map(({ computedDate: _first, ...rest }) => rest));
});

test('community dedupe collapses forks, copies, revisions and re-collections but counts genuine repeats', () => {
  const attempt = id(3000), dish = id(4000);
  const base = {
    status: 'active', collectedAtMillis: NOW - 1000, attemptId: attempt, dishId: dish,
    foodType: 'Pork', cut: 'Shoulder', weightGrams: 3855, method: 'Smoke',
    heatingMillis: 27600000, finalTemp: { value: 100, unit: '°C' as TemperatureUnit },
  };
  const rows = [
    { ...base, itemId: id(100), revisionId: id(200) },
    { ...base, itemId: id(101), revisionId: id(201), cut: 'Butt' },
    { ...base, itemId: id(102), revisionId: id(202) },
    { ...base, itemId: id(100), revisionId: id(203) },
    { ...base, itemId: id(103), revisionId: id(204) },
  ];
  const collapsed = projectEligibleAttempts(rows, NOW);
  assert.equal(collapsed.length, 1);
  assert.equal(collapsed[0].key, dedupeKey(attempt, dish));
  assert.equal(collapsed[0].cut, 'Shoulder');
  assert.deepEqual(projectEligibleAttempts([...rows].reverse(), NOW), collapsed);
  const genuine = [...rows, { ...base, itemId: id(104), revisionId: id(205), attemptId: id(3001) }];
  assert.equal(projectEligibleAttempts(genuine, NOW).length, 2);
  assert.throws(() => projectEligibleAttempts(
    [...rows, { ...base, itemId: id(105), revisionId: id(206), heatingMillis: 1 }], NOW),
    { code: 'community_identity_conflict' });
  const aggregates = new CommunityAggregates(new MemoryJobRunStore(), () => NOW);
  const suppressed = aggregates.build(rows, options);
  assert.ok(suppressed.figures.length > 0);
  assert.ok(suppressed.figures.every((figure) => figure.n === 1 && figure.status === 'insufficient_data'));
});

test('community projection structurally rejects private sync records and recipe instructions', async () => {
  const valid = contribution(0);
  assert.throws(() => rawContribution({ ...valid, instructions: [{ title: 'Editable text must never aggregate' }] }),
    { code: 'forbidden_community_input' });
  assert.throws(() => rawContribution({ ...valid, syncRecords: [{ recordType: 'cooks', recordId: 'x' }] }),
    { code: 'forbidden_community_input' });
  assert.throws(() => rawContribution({ ...valid, syncEnvelope: {} }), { code: 'forbidden_community_input' });
  assert.throws(() => rawContribution({ ...valid, privateLibrary: {} }), { code: 'forbidden_community_input' });
  assert.throws(() => rawContribution({ recordType: 'cooks', recordId: 'x', payload: {} }),
    { code: 'invalid_community_contribution' });
  assert.throws(() => rawContribution({ ...valid, surprise: 1 }), { code: 'invalid_community_contribution' });
  assert.ok(ELIGIBLE_ATTEMPTS_SQL.includes('community_aggregation.eligible_attempts()'));
  for (const banned of ['private_sync', 'instructions', 'payload', 'SELECT *']) {
    assert.ok(!ELIGIBLE_ATTEMPTS_SQL.includes(banned), banned);
  }
  for (const name of ['community-taxonomy.ts', 'community-projection.ts', 'community-aggregation.ts']) {
    const source = await readFile(new URL('../src/' + name, import.meta.url), 'utf8');
    assert.ok(!source.includes('private_sync'), name);
    for (const line of source.split('\n')) {
      const match = /^import .* from '([^']+)'/.exec(line.trim());
      if (!match) continue;
      if (match[1] === 'pg') assert.ok(line.trim().startsWith('import type'), name);
      else if (match[1].startsWith('.')) {
        assert.ok(['./core.ts', './pack.ts', './community-projection.ts', './community-taxonomy.ts'].includes(match[1]),
          `${name}: ${match[1]}`);
      } else assert.ok(['node:crypto'].includes(match[1]), `${name}: ${match[1]}`);
    }
  }
  const story = (rating: number | null) => ({
    attemptId: id(3000), foodOnOffsetMillis: 0, foodOffOffsetMillis: 6000,
    channels: [{ id: 'food-1', role: 'food', label: 'Pork' }],
    measurements: [{ offsetMillis: 0, channelId: 'food-1', role: 'food', valueCelsius: 8, quality: 'manual' }],
    milestones: [], gaps: [], verdict: { rating, cookAgain: null, notes: rating === null ? '' : 'ignored' },
  });
  assert.deepEqual(storyStubFields(story(5)), storyStubFields(story(1)));
  assert.throws(() => storyStubFields({ ...story(5), instructions: [] }), { code: 'forbidden_community_input' });
  const viewRow = {
    item_id: id(100), revision_id: id(200), collected_at: NOW - 1000, dish_id: id(4000),
    attempt_id: id(3000), food_type: 'Pork', cut: 'Shoulder', weight_grams: 3855, method: 'Smoke', story: story(5),
  };
  assert.equal(extractRawContributions([viewRow]).length, 1);
  assert.throws(() => extractRawContributions([{ ...viewRow, instructions: [] }]), { code: 'forbidden_community_input' });
  assert.throws(() => extractRawContributions([{ ...viewRow, syncRecords: [] }]), { code: 'forbidden_community_input' });
});

test('community removal withdraws contributions and records a refresh', async () => {
  const store = new MemoryJobRunStore();
  const aggregates = new CommunityAggregates(store, () => NOW);
  const full = Array.from({ length: 10 }, (_, i) => contribution(i));
  const first = aggregates.build(full, options);
  assert.ok(first.figures.length > 0);
  assert.ok(first.figures.every((figure) => figure.n === 10 && figure.status === 'visible'));
  const recorded = await aggregates.record(first);
  assert.equal(recorded.replayed, false);
  const replay = await aggregates.record(aggregates.build(structuredClone(full), options));
  assert.equal(replay.replayed, true);
  assert.equal(replay.run.runId, recorded.run.runId);
  assert.equal((await store.list()).length, 1);
  const removed = full[9];
  const cutoff = NOW + 60000;
  const second = new CommunityAggregates(store, () => cutoff)
    .build(full.slice(0, 9), { ...options, cutoffMillis: cutoff });
  assert.ok(second.figures.every((figure) => figure.n === 9 && figure.status === 'insufficient_data'));
  assert.ok(!second.attempts.some((attempt) => attempt.attemptId === removed.attemptId));
  assert.notEqual(second.bytes, first.bytes);
  const refreshed = await aggregates.record(second);
  assert.equal(refreshed.replayed, false);
  const runs = await store.list();
  assert.equal(runs.length, 2);
  assert.ok(runs[0].cutoffMillis < runs[1].cutoffMillis);
  assert.equal(runs[1].eligibleCount, 9);
  assert.equal(runs[1].outputSha256, second.outputSha256);
});

test('community suppression hides n=9 and shows n=10 with n and compute date on every figure', () => {
  const aggregates = new CommunityAggregates(new MemoryJobRunStore(), () => NOW);
  const nine = aggregates.build(Array.from({ length: 9 }, (_, i) => contribution(i)), options);
  const ten = aggregates.build(Array.from({ length: 10 }, (_, i) => contribution(i)), options);
  assert.ok(nine.figures.length > 0);
  assert.equal(ten.figures.length, nine.figures.length);
  assert.ok(nine.figures.every((figure) => figure.n === 9 && figure.status === 'insufficient_data' && figure.summary === null));
  assert.ok(ten.figures.every((figure) => figure.n === 10 && figure.status === 'visible'));
  for (const figure of [...nine.figures, ...ten.figures]) {
    assert.equal(figure.computedDate, '2026-10-18');
    assert.equal(figure.cutoffMillis, NOW);
    assert.equal(typeof figure.n, 'number');
  }
});

test('community metric eligibility excludes missing fields from one metric only', () => {
  const aggregates = new CommunityAggregates(new MemoryJobRunStore(), () => NOW);
  const raw = Array.from({ length: 10 }, (_, i) => contribution(i, { finalTemp: i < 3 ? { value: 100, unit: '°C' } : null }));
  const built = aggregates.build(raw, options);
  const heating = built.figures.find((figure) => figure.metricId === 'stub-heating-presence')!;
  const temp = built.figures.find((figure) => figure.metricId === 'stub-temp-presence')!;
  assert.equal(heating.n, 10);
  assert.equal(heating.status, 'visible');
  assert.equal(temp.n, 3);
  assert.equal(temp.status, 'insufficient_data');
});

test('community mixed Fahrenheit and Celsius attempts normalize at the projection boundary', () => {
  assert.equal(toCelsius(212, '°F'), 100);
  assert.equal(toCelsius(32, '°F'), 0);
  assert.equal(toCelsius(-40, '°F'), -40);
  assert.equal(toCelsius(100, '°C'), 100);
  assert.throws(() => toCelsius(2000, '°C'));
  const pairs: [number, number][] = [[32, 0], [68, 20], [212, 100], [-40, -40]];
  const fahrenheit = Array.from({ length: 10 }, (_, i) => contribution(i, { finalTemp: { value: pairs[i % 4][0], unit: '°F' } }));
  const celsius = Array.from({ length: 10 }, (_, i) => contribution(i, { finalTemp: { value: pairs[i % 4][1], unit: '°C' } }));
  const projected = projectEligibleAttempts(fahrenheit, NOW);
  assert.ok(projected.every((attempt) => typeof attempt.finalTempCelsius === 'number'));
  assert.ok(!JSON.stringify(projected).includes('°F'));
  const aggregates = new CommunityAggregates(new MemoryJobRunStore(), () => NOW);
  assert.equal(aggregates.build(fahrenheit, options).bytes, aggregates.build(celsius, options).bytes);
  const mixed = [...fahrenheit.slice(0, 5), ...celsius.slice(5)];
  const cell = aggregates.build(mixed, options).figures.find((figure) => figure.metricId === 'stub-temp-presence')!;
  assert.equal(cell.n, 10);
  assert.equal(cell.status, 'visible');
});

test('community outputs record cutoff and versions without pinning metric meanings', async () => {
  const aggregates = new CommunityAggregates(new MemoryJobRunStore(), () => NOW);
  const built = aggregates.build(Array.from({ length: 10 }, (_, i) => contribution(i)), options);
  const { run, replayed } = await aggregates.record(built);
  assert.equal(replayed, false);
  assert.equal(run.cutoffMillis, NOW);
  assert.equal(run.algorithmVersion, COMMUNITY_ALGORITHM_VERSION);
  assert.equal(run.taxonomyVersion, STUB_TAXONOMY_NON_AUTHORITATIVE.taxonomyVersion);
  assert.equal(run.bandVersion, STUB_TAXONOMY_NON_AUTHORITATIVE.bandVersion);
  assert.ok(run.taxonomyVersion.includes('non-authoritative'));
  assert.deepEqual(run.metricVersions, [
    { metricId: 'stub-heating-presence', metricVersion: 'stub-non-authoritative-v0' },
    { metricId: 'stub-temp-presence', metricVersion: 'stub-non-authoritative-v0' },
  ]);
  assert.equal(run.eligibleCount, 10);
  for (const figure of built.figures) {
    assert.equal(figure.algorithmVersion, COMMUNITY_ALGORITHM_VERSION);
    assert.equal(figure.taxonomyVersion, run.taxonomyVersion);
    assert.equal(figure.bandVersion, run.bandVersion);
    assert.equal(figure.cutoffMillis, NOW);
    assert.deepEqual(figure.summary, { pending: 'slice-15-calculation-contract' });
  }
  assert.throws(() => aggregates.build([], { ...options, metrics: [] }), { code: 'invalid_community_job' });
  assert.throws(() => aggregates.build([], { ...options, metrics: [STUB_METRICS_NON_AUTHORITATIVE[0], STUB_METRICS_NON_AUTHORITATIVE[0]] }),
    { code: 'invalid_community_job' });
});

test('community excludes non-active and past-cutoff contributions', () => {
  const rows = [
    contribution(0, { status: 'pending_review' }),
    contribution(1, { status: 'unpublished' }),
    contribution(2, { status: 'taken_down' }),
    contribution(3, { status: 'purged' }),
    contribution(4, { collectedAtMillis: NOW + 1 }),
    contribution(5),
  ];
  const attempts = projectEligibleAttempts(rows, NOW);
  assert.equal(attempts.length, 1);
  assert.equal(attempts[0].attemptId, id(3005));
  assert.throws(() => projectEligibleAttempts([contribution(6, { status: 'future_unknown' })], NOW),
    { code: 'invalid_community_contribution' });
});
