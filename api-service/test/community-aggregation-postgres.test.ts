import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { Pool } from 'pg';
import { migrate } from '../src/migrate.ts';
import {
  CommunityAggregates, MemoryJobRunStore, PostgresCommunityStore,
  STUB_METRICS_NON_AUTHORITATIVE, loadRawContributions,
} from '../src/community-aggregation.ts';
import { STUB_TAXONOMY_NON_AUTHORITATIVE } from '../src/community-taxonomy.ts';

const gate = { skip: !process.env.TEST_DATABASE_URL && process.env.REQUIRE_DB_TESTS !== '1' };
test('real Postgres community aggregation isolates private sync, serves the allowlisted projection and refreshes after removal', gate, async () => {
  const source = process.env.TEST_DATABASE_URL; assert.ok(source);
  const base = new URL(source); assert.match(base.pathname, /^\/pittech_test[a-z_\d]*$/i);
  const control = new Pool({ connectionString: source }), database = 'pittech_test_community_' + randomUUID().replaceAll('-', '');
  await control.query(`CREATE DATABASE ${database}`); base.pathname = '/' + database;
  const connectionString = base.toString();
  const admin = new Pool({ connectionString, max: 5 });
  const pool = new Pool({ connectionString, max: 5, options: '-c role=pittech_community_aggregation_runtime' });
  const now = Date.UTC(2026, 9, 18, 12);
  try {
    await migrate(admin); await migrate(admin);
    const grant = await readFile(new URL('../operations/community-aggregation-runtime-role.sql', import.meta.url), 'utf8');
    await admin.query(grant); await admin.query(grant);
    for (const sql of [
      'SELECT * FROM private_sync.accounts',
      'SELECT * FROM auth.users',
      'SELECT * FROM relay.packs',
      'SELECT * FROM publications.items',
      'SELECT * FROM publications.revisions',
      'SELECT * FROM moderation.operators',
      'CREATE TABLE community_aggregation.forbidden(id integer)',
      'TRUNCATE community_aggregation.job_runs',
    ]) await assert.rejects(pool.query(sql), { code: '42501' });
    const author = randomUUID();
    await admin.query('INSERT INTO auth.users VALUES($1,$2,$3)', [author, 'synthetic-community@example.com', now]);
    const story = (attempt: string) => ({
      attemptId: attempt, foodOnOffsetMillis: 0, foodOffOffsetMillis: 27600000,
      channels: [{ id: 'food-1', role: 'food', label: 'Pork' }],
      measurements: [
        { offsetMillis: 0, channelId: 'food-1', role: 'food', valueCelsius: 8, quality: 'manual' },
        { offsetMillis: 27600000, channelId: 'food-1', role: 'food', valueCelsius: 93, quality: 'manual' },
      ],
      milestones: [], gaps: [], verdict: { rating: 5, cookAgain: true, notes: 'Synthetic verdict, never aggregated' },
    });
    const publish = async (index: number, status = 'active') => {
      const item = randomUUID(), revision = randomUUID(), object = randomUUID(), dish = randomUUID(), attempt = randomUUID();
      const payload = {
        contractVersion: 1, kind: 'recipe', identityMode: 'anonymous', title: `Synthetic pork ${index}`,
        recipes: [{
          id: dish, revisionId: revision, title: `Synthetic pork ${index}`, description: '',
          foodType: 'Pork', cut: 'Shoulder', weightGrams: 3855, method: 'Smoke',
          ingredients: [],
          instructions: [{ id: randomUUID(), title: 'Editable text must never aggregate', details: 'synthetic', action: null, suggestedOffsetMillis: null, method: null }],
          tags: [], story: story(attempt), assets: [], source: null,
        }],
        source: null, measuredAttemptId: null, heroAssetId: null,
      };
      await admin.query('INSERT INTO publications.items VALUES($1,$2,NULL,NULL,$3,$4,$4)', [item, author, status, now]);
      await admin.query('INSERT INTO publications.objects VALUES($1,$2,$3,$4,$5,$6,$7,\'active\',$8,$9,$10)',
        [object, author, randomUUID(), 'a'.repeat(64), `community-test/${object}.pittech`, 'b'.repeat(64), 128, randomUUID(), now + 300000, now]);
      await admin.query('INSERT INTO publications.revisions VALUES($1,$2,1,$3,$4,$5,$6)', [revision, item, object, 'anonymous', payload, now]);
      await admin.query('UPDATE publications.items SET current_revision_id=$2 WHERE id=$1', [item, revision]);
      return { item, revision, attempt };
    };
    const items = [];
    for (let i = 0; i < 10; i++) items.push(await publish(i));
    await publish(10, 'unpublished');
    const rows = await pool.query('SELECT * FROM community_aggregation.eligible_attempts()');
    assert.equal(rows.rows.length, 10);
    assert.deepEqual(Object.keys(rows.rows[0]).sort(),
      ['attempt_id', 'collected_at', 'cut', 'dish_id', 'food_type', 'item_id', 'method', 'revision_id', 'story', 'weight_grams']);
    assert.ok(!JSON.stringify(rows.rows).includes('Editable text must never aggregate'));
    const options = { cutoffMillis: now, taxonomy: STUB_TAXONOMY_NON_AUTHORITATIVE, metrics: STUB_METRICS_NON_AUTHORITATIVE };
    const raw = await loadRawContributions(pool);
    assert.equal(raw.length, 10);
    const aggregates = new CommunityAggregates(new MemoryJobRunStore(), () => now);
    const first = aggregates.build(raw, options);
    assert.ok(first.figures.length > 0);
    assert.ok(first.figures.every((figure) => figure.n === 10 && figure.status === 'visible'));
    assert.equal(aggregates.build(await loadRawContributions(pool), options).bytes, first.bytes);
    const store = new PostgresCommunityStore(pool);
    const recorded = await store.record(first.entry);
    assert.equal(recorded.replayed, false);
    assert.equal((await store.list()).length, 1);
    const replay = await store.record(first.entry);
    assert.equal(replay.replayed, true);
    assert.equal(replay.run.runId, recorded.run.runId);
    await admin.query('UPDATE publications.items SET status=$2 WHERE id=$1', [items[0].item, 'unpublished']);
    const cutoff = now + 60000;
    const second = new CommunityAggregates(new MemoryJobRunStore(), () => cutoff)
      .build(await loadRawContributions(pool), { ...options, cutoffMillis: cutoff });
    assert.ok(second.figures.every((figure) => figure.n === 9 && figure.status === 'insufficient_data'));
    assert.notEqual(second.outputSha256, first.outputSha256);
    const refreshed = await store.record(second.entry);
    assert.equal(refreshed.replayed, false);
    const runs = await store.list();
    assert.equal(runs.length, 2);
    assert.equal(runs[1].eligibleCount, 9);
    assert.equal(runs[1].cutoffMillis, cutoff);
    assert.deepEqual(runs[1].metricVersions, [
      { metricId: 'stub-heating-presence', metricVersion: 'stub-non-authoritative-v0' },
      { metricId: 'stub-temp-presence', metricVersion: 'stub-non-authoritative-v0' },
    ]);
  } finally {
    try { await pool.end(); } finally {
      try { await admin.end(); } finally {
        try { await control.query(`DROP DATABASE ${database}`); } finally {
          await control.end();
        }
      }
    }
  }
});
