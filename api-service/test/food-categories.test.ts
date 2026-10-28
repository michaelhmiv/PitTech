import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { FOOD_CATEGORIES, DEFAULT_FOOD_CATEGORY_ID, isFoodCategoryId, normalizeFoodCategory } from '../src/food-categories.ts';
import { canonicalPublication, type PublicationPayload } from '../src/publication-pack.ts';
import { writeZip } from '../src/zip.ts';

const archive = (payload: PublicationPayload) => writeZip(new Map([['publication.json', Buffer.from(JSON.stringify(payload))]]));

const root = new URL('../../contracts/', import.meta.url);
const taxonomy = async () => JSON.parse(await readFile(new URL('fixtures/food-categories-v1.json', root), 'utf8')) as { version: number; categories: { id: string; label: string; examples: string[] }[] };
const fixturePayload = async () => JSON.parse(await readFile(new URL('fixtures/anonymous-pork.json', root), 'utf8')) as PublicationPayload;

test('food taxonomy: the shared file is the approved list — 12 categories, alphabetical, Other pinned last', async () => {
  const file = await taxonomy();
  assert.equal(file.version, 1);
  assert.deepEqual(file.categories.map((c) => c.label), [
    'Apps & Snacks', 'Beef', 'Chicken', 'Desserts', 'Lamb & Game', 'Pork',
    'Sausage & Links', 'Seafood', 'Sides', 'Turkey', 'Veggies', 'Other',
  ]);
  const ids = file.categories.map((c) => c.id);
  assert.equal(new Set(ids).size, 12, 'category ids are unique');
  for (const c of file.categories) {
    assert.match(c.id, /^[a-z][a-z-]*$/, 'ids are stable slugs');
    assert.ok(c.examples.length > 0 && c.examples.every((e) => typeof e === 'string' && e.length > 0), `${c.id} carries picker examples`);
  }
  // The server's view is the file itself, never a second hand-kept copy.
  assert.deepEqual(FOOD_CATEGORIES.map((c) => c.id), ids);
  assert.deepEqual(FOOD_CATEGORIES.map((c) => c.label), file.categories.map((c) => c.label));
});

test('food taxonomy: the publication contract enum is exactly the shared list, so off-list categories are rejected', async () => {
  const file = await taxonomy();
  const schema = JSON.parse(await readFile(new URL('publication.schema.json', root), 'utf8'));
  assert.deepEqual(schema.properties.category.enum, file.categories.map((c) => c.id));
  assert.ok(!schema.required.includes('category'), 'category stays optional so pre-taxonomy publications remain valid');

  const base = await fixturePayload();
  assert.equal(base.category, undefined, 'the legacy fixture carries no category');
  await canonicalPublication(archive(base)); // legacy payload still validates
  for (const id of file.categories.map((c) => c.id)) {
    const payload = structuredClone(base); payload.category = id;
    const accepted = await canonicalPublication(archive(payload));
    assert.equal(accepted.payload.category, id);
  }
  for (const bad of ['barbecue', 'Beef', 'pork ribs', '']) {
    const payload = structuredClone(base); payload.category = bad;
    await assert.rejects(canonicalPublication(archive(payload)), { code: 'invalid_publication' }, `category ${JSON.stringify(bad)} must be rejected, not stored`);
  }
});

test('food taxonomy: read-path normalization treats missing or unknown stored categories as Other', () => {
  assert.equal(DEFAULT_FOOD_CATEGORY_ID, 'other');
  assert.equal(normalizeFoodCategory(undefined), 'other');
  assert.equal(normalizeFoodCategory(null), 'other');
  assert.equal(normalizeFoodCategory('nonsense'), 'other');
  assert.equal(normalizeFoodCategory('beef'), 'beef');
  assert.ok(isFoodCategoryId('sausage-links'));
  assert.ok(!isFoodCategoryId('sausage'));
  assert.ok(!isFoodCategoryId(''));
});
