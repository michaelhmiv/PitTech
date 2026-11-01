import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { canonicalPublication, type PublicationPayload, type PublicationSession } from '../src/publication-pack.ts';
import { dedupeKey, extractRawContributions, storyV1Fields } from '../src/community-projection.ts';
import { writeZip } from '../src/zip.ts';

// Publishable Cooks (issue #250): kind=cook makes a whole measured cook —
// session plus every dish's story on one shared clock — a first-class
// publication. These tests pin the contract rules and the aggregation
// behavior the issue requires (item 5: stories flow into community
// aggregation unchanged; inputs are validated but not extracted in v1).

const archive = (payload: PublicationPayload) => writeZip(new Map([['publication.json', Buffer.from(JSON.stringify(payload))]]));
const base = async () => JSON.parse(await readFile(new URL('../../contracts/fixtures/anonymous-pork.json', import.meta.url), 'utf8')) as PublicationPayload;

const session: PublicationSession = {
  smokerName: 'Offset smoker', fuelType: 'Pellets', woodOrPelletBlend: 'Oak',
  setpoint: { value: 250, unit: '°F' }, outdoorTemperatureValue: 61, outdoorTemperatureUnit: '°F',
  weatherNotes: 'Clear and calm', windNotes: '', notes: 'Game day cook',
  serveAtOffsetMillis: 39_600_000, durationMillis: 40_800_000,
};

async function cookPayload(): Promise<PublicationPayload> {
  const payload = await base();
  const second = structuredClone(payload.recipes[0]);
  second.id = '00000000-0000-4000-8000-000000000011';
  second.revisionId = '00000000-0000-4000-8000-000000000012';
  second.title = 'Smoked potatoes';
  second.story = structuredClone(payload.recipes[0].story);
  second.story!.attemptId = '00000000-0000-4000-8000-000000000014';
  payload.kind = 'cook';
  payload.session = structuredClone(session);
  payload.recipes = [payload.recipes[0], second];
  payload.recipes[0].story!.anchorOffsetMillis = 3_600_000;
  payload.recipes[0].story!.inputs = { wrapMaterial: 'butcher_paper', rub: 'House rub', spritz: true, spritzMedium: 'Apple juice' };
  payload.recipes[1].story!.anchorOffsetMillis = 5_400_000;
  payload.recipes[1].story!.inputs = null;
  return payload;
}

test('publishable cooks: a cook archive with a session and per-dish stories passes canonical validation intact', async () => {
  const payload = await cookPayload();
  const accepted = await canonicalPublication(archive(payload));
  assert.equal(accepted.payload.kind, 'cook');
  assert.deepEqual(accepted.payload.session, session);
  assert.equal(accepted.payload.measuredAttemptId, null);
  assert.equal(accepted.payload.recipes.length, 2);
  assert.equal(accepted.payload.recipes[0].story?.anchorOffsetMillis, 3_600_000);
  assert.deepEqual(accepted.payload.recipes[0].story?.inputs, { wrapMaterial: 'butcher_paper', rub: 'House rub', spritz: true, spritzMedium: 'Apple juice' });
  assert.equal(accepted.payload.recipes[1].story?.inputs, null);
});

test('publishable cooks: pre-cook payloads without a session stay valid (backward compatible)', async () => {
  const payload = await base();
  assert.equal(payload.session, undefined);
  const accepted = await canonicalPublication(archive(payload));
  assert.equal(accepted.payload.kind, 'recipe');
});

test('publishable cooks: the session exists exactly for kind=cook and a cook is fully measured', async () => {
  const noSession = await cookPayload(); noSession.session = null;
  await assert.rejects(canonicalPublication(archive(noSession)), { code: 'invalid_publication' }, 'cook without a session must be rejected');

  const sessionOnRecipe = await base(); sessionOnRecipe.session = structuredClone(session);
  await assert.rejects(canonicalPublication(archive(sessionOnRecipe)), { code: 'invalid_publication' }, 'a session on a recipe publication must be rejected');

  const measured = await cookPayload(); measured.measuredAttemptId = measured.recipes[0].story!.attemptId;
  await assert.rejects(canonicalPublication(archive(measured)), { code: 'invalid_publication' }, 'a cook never names a single measured attempt');

  const storyless = await cookPayload(); storyless.recipes[1].story = null;
  await assert.rejects(canonicalPublication(archive(storyless)), { code: 'invalid_publication' }, 'every dish in a cook carries its story; story-less dishes are excluded instead');
});

test('publishable cooks: session and story extras are bounds-checked', async () => {
  const badServe = await cookPayload(); badServe.session!.serveAtOffsetMillis = 2_592_000_001;
  await assert.rejects(canonicalPublication(archive(badServe)), { code: 'invalid_publication' });

  const badDuration = await cookPayload(); badDuration.session!.durationMillis = -1;
  await assert.rejects(canonicalPublication(archive(badDuration)), { code: 'invalid_publication' });

  const badAnchor = await cookPayload(); badAnchor.recipes[0].story!.anchorOffsetMillis = 2_592_000_001;
  await assert.rejects(canonicalPublication(archive(badAnchor)), { code: 'invalid_publication' });

  const badWrap = await cookPayload();
  (badWrap.recipes[0].story!.inputs as { wrapMaterial: string }).wrapMaterial = 'plastic';
  await assert.rejects(canonicalPublication(archive(badWrap)), { code: 'invalid_publication' }, 'wrap material is the recorded vocabulary, never free text');
});

test('publishable cooks: cook stories flow into aggregation exactly like attempt stories (issue item 5)', async () => {
  const payload = await cookPayload();
  const cookStory = payload.recipes[0].story!;
  const plainStory = structuredClone(cookStory);
  delete plainStory.anchorOffsetMillis; delete plainStory.inputs;
  // The v1 extraction is identical with and without the cook extras: the
  // session placement and lifted inputs change no metric in v1.
  assert.deepEqual(storyV1Fields(cookStory), storyV1Fields(plainStory));

  const now = Date.now();
  const row = {
    item_id: '00000000-0000-4000-8000-000000000101', revision_id: '00000000-0000-4000-8000-000000000102',
    collected_at: now - 1000, dish_id: '00000000-0000-4000-8000-000000000401',
    attempt_id: cookStory.attemptId, food_type: 'Pork', cut: 'Shoulder', weight_grams: 3855, method: 'Smoke',
    story: cookStory,
  };
  const contributions = extractRawContributions([row]);
  assert.equal(contributions.length, 1);
  assert.equal(contributions[0].attemptId, cookStory.attemptId);
  assert.equal(dedupeKey(contributions[0].attemptId, contributions[0].dishId), dedupeKey(cookStory.attemptId, row.dish_id),
    'dedupe stays attemptId + dishId; kind plays no part in eligibility');

  assert.throws(() => storyV1Fields({ ...cookStory, inputs: { wrapMaterial: 'plastic', rub: null, spritz: null, spritzMedium: null } }),
    { code: 'invalid_community_contribution' }, 'malformed inputs fail closed in the projection');
  assert.throws(() => storyV1Fields({ ...cookStory, inputs: { wrapMaterial: 'foil', rub: null, spritz: null } }),
    { code: 'invalid_community_contribution' }, 'inputs must carry the full recorded shape');
  assert.throws(() => storyV1Fields({ ...cookStory, anchorOffsetMillis: 2_592_000_001 }),
    { code: 'invalid_community_contribution' }, 'anchor offsets obey the shared offset bounds');
});
