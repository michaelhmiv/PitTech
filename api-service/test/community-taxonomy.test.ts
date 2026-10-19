import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  COMMUNITY_TAXONOMY_V1, COMMUNITY_TAXONOMY_VERSION, COMMUNITY_BAND_VERSION,
  COMMUNITY_WEIGHT_BANDS_V1, STUB_TAXONOMY_NON_AUTHORITATIVE,
} from '../src/community-taxonomy.ts';
import { projectEligibleAttempts } from '../src/community-projection.ts';

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const NOW = Date.UTC(2026, 9, 18, 12);
const bucket = COMMUNITY_TAXONOMY_V1.bucket.bind(COMMUNITY_TAXONOMY_V1);

test('community taxonomy v1 pins reviewed versions and keeps the stub non-authoritative', () => {
  assert.equal(COMMUNITY_TAXONOMY_V1.taxonomyVersion, 'community-taxonomy-v1');
  assert.equal(COMMUNITY_TAXONOMY_V1.bandVersion, 'community-bands-v1');
  assert.equal(COMMUNITY_TAXONOMY_VERSION, 'community-taxonomy-v1');
  assert.equal(COMMUNITY_BAND_VERSION, 'community-bands-v1');
  assert.ok(STUB_TAXONOMY_NON_AUTHORITATIVE.taxonomyVersion.includes('non-authoritative'));
  assert.ok(STUB_TAXONOMY_NON_AUTHORITATIVE.bandVersion.includes('non-authoritative'));
});

test('community taxonomy golden buckets separate pork loin from pork butt', () => {
  const butt = bucket({ foodType: 'Pork', cut: 'Boston butt', weightGrams: 3855, method: 'Smoke' });
  const shoulder = bucket({ foodType: 'pork', cut: 'Shoulder', weightGrams: 3855, method: 'smoke' });
  const loin = bucket({ foodType: 'Pork', cut: 'Pork loin', weightGrams: 3855, method: 'Smoke' });
  const tenderloin = bucket({ foodType: 'Pork', cut: 'Tenderloin', weightGrams: 1000, method: 'Grill' });
  assert.ok(butt?.startsWith('pork-butt-shoulder|'));
  assert.equal(butt, shoulder);
  assert.ok(loin?.startsWith('pork-loin|'));
  assert.notEqual(butt, loin);
  // Tenderloin cooks differently from loin roast: never merged into the loin group.
  assert.ok(tenderloin?.startsWith('other|'));
  assert.notEqual(loin, tenderloin);
});

test('community taxonomy golden buckets keep rib families distinct', () => {
  const spare = bucket({ foodType: 'Pork', cut: 'Spare ribs', weightGrams: 1500, method: 'Smoke' });
  const stLouis = bucket({ foodType: 'Pork', cut: 'St. Louis ribs', weightGrams: 1500, method: 'Smoke' });
  const baby = bucket({ foodType: 'Pork', cut: 'Baby back ribs', weightGrams: 1200, method: 'Smoke' });
  const loinBack = bucket({ foodType: 'Pork', cut: 'Loin back ribs', weightGrams: 1200, method: 'Smoke' });
  assert.ok(spare?.startsWith('pork-spare-ribs|'));
  assert.equal(spare, stLouis);
  assert.ok(baby?.startsWith('pork-baby-back-ribs|'));
  assert.notEqual(spare, baby);
  // "Loin back ribs" without "baby" is ambiguous: other, never silently loin.
  assert.ok(loinBack?.startsWith('other|'));
});

test('community taxonomy brisket 12 lb and 14 lb share a band', () => {
  const twelve = bucket({ foodType: 'Beef', cut: 'Whole packer', weightGrams: 5443, method: 'Smoke' });
  const fourteen = bucket({ foodType: 'beef', cut: 'Brisket', weightGrams: 6350, method: 'smoke' });
  assert.equal(twelve, fourteen);
  assert.ok(twelve?.startsWith('beef-brisket|medium|'));
  const small = bucket({ foodType: 'Beef', cut: 'Brisket flat', weightGrams: 4000, method: 'Smoke' });
  const large = bucket({ foodType: 'Beef', cut: 'Brisket', weightGrams: 8000, method: 'Smoke' });
  assert.ok(small?.startsWith('beef-brisket|small|'));
  assert.ok(large?.startsWith('beef-brisket|large|'));
});

test('community taxonomy chicken and turkey groupings require explicit cuts', () => {
  const whole = bucket({ foodType: 'Poultry', cut: 'Whole chicken', weightGrams: 1800, method: null });
  const pieces = bucket({ foodType: 'poultry', cut: 'Thighs', weightGrams: 1200, method: null });
  const generic = bucket({ foodType: 'Poultry', cut: 'Chicken', weightGrams: 1500, method: null });
  const turkey = bucket({ foodType: 'Turkey', cut: 'Whole turkey', weightGrams: 7000, method: 'Roast' });
  const turkeyPoultry = bucket({ foodType: 'Poultry', cut: 'Turkey', weightGrams: 7000, method: 'Roast' });
  const breast = bucket({ foodType: 'Turkey', cut: 'Breast', weightGrams: 2500, method: 'Roast' });
  assert.ok(whole?.startsWith('chicken-whole|'));
  assert.ok(pieces?.startsWith('chicken-pieces|'));
  assert.notEqual(whole, pieces);
  assert.ok(generic?.startsWith('other|'));
  assert.ok(turkey?.startsWith('turkey-whole|'));
  assert.equal(turkey, turkeyPoultry);
  assert.ok(breast?.startsWith('other|'));
});

test('community taxonomy unknown cuts go to other and blank food is unbucketed', () => {
  assert.ok(bucket({ foodType: 'Pork', cut: null, weightGrams: 2000, method: null })?.startsWith('other|'));
  assert.ok(bucket({ foodType: 'Beef', cut: 'Mystery cut', weightGrams: 2000, method: null })?.startsWith('other|'));
  assert.ok(bucket({ foodType: 'Vegetables', cut: 'Potatoes', weightGrams: 1000, method: null })?.startsWith('other|'));
  assert.ok(bucket({ foodType: 'Meat', cut: 'Brisket', weightGrams: 5000, method: null })?.startsWith('other|'));
  assert.equal(bucket({ foodType: '   ', cut: 'Brisket', weightGrams: 5000, method: null }), null);
  assert.equal(bucket({ foodType: 'Beef', cut: 'Brisket', weightGrams: -5, method: null }), null);
  assert.ok(bucket({ foodType: 'Beef', cut: 'Brisket', weightGrams: null, method: null })?.includes('|unknown|'));
});

test('community taxonomy missing method narrows to unknown and equipment extends the key', () => {
  const smoked = bucket({ foodType: 'Pork', cut: 'Shoulder', weightGrams: 3855, method: 'Smoke' });
  const unknown = bucket({ foodType: 'Pork', cut: 'Shoulder', weightGrams: 3855, method: null });
  const blank = bucket({ foodType: 'Pork', cut: 'Shoulder', weightGrams: 3855, method: '   ' });
  assert.ok(smoked?.endsWith('|method:smoke'));
  assert.ok(unknown?.endsWith('|method:unknown'));
  assert.equal(unknown, blank);
  assert.notEqual(smoked, unknown);
  const equipped = bucket({ foodType: 'Pork', cut: 'Shoulder', weightGrams: 3855, method: 'Smoke', equipment: 'Offset smoker' });
  assert.equal(equipped, `${smoked}|equipment:offset smoker`);
});

test('community taxonomy weight bands match the documented gram boundaries', () => {
  assert.deepEqual(COMMUNITY_WEIGHT_BANDS_V1['beef-brisket'], { smallMaxG: 4536, mediumMaxG: 7257 });
  assert.deepEqual(COMMUNITY_WEIGHT_BANDS_V1['pork-butt-shoulder'], { smallMaxG: 2722, mediumMaxG: 4536 });
  assert.deepEqual(COMMUNITY_WEIGHT_BANDS_V1['pork-spare-ribs'], { smallMaxG: 1134, mediumMaxG: 1814 });
  assert.deepEqual(COMMUNITY_WEIGHT_BANDS_V1['pork-loin'], { smallMaxG: 1361, mediumMaxG: 2268 });
  assert.deepEqual(COMMUNITY_WEIGHT_BANDS_V1['turkey-whole'], { smallMaxG: 5443, mediumMaxG: 8165 });
  // Boundary probes: smallMaxG is exclusive, mediumMaxG inclusive.
  const brisket = (grams: number): string | null => bucket({ foodType: 'Beef', cut: 'Brisket', weightGrams: grams, method: null });
  assert.ok(brisket(4535)?.includes('|small|'));
  assert.ok(brisket(4536)?.includes('|medium|'));
  assert.ok(brisket(7257)?.includes('|medium|'));
  assert.ok(brisket(7258)?.includes('|large|'));
});

test('community taxonomy buckets Fahrenheit and Celsius attempts together', () => {
  const contribution = (index: number, finalTemp: { value: number; unit: '°C' | '°F' }): unknown => ({
    itemId: id(1000 + index), revisionId: id(2000 + index), status: 'active',
    collectedAtMillis: NOW - 1000, attemptId: id(3000 + index), dishId: id(4000 + index),
    foodType: 'Pork', cut: 'Shoulder', weightGrams: 3855, method: 'Smoke',
    heatingMillis: 27600000, finalTemp,
  });
  const fahrenheit = projectEligibleAttempts([contribution(0, { value: 212, unit: '°F' })], NOW);
  const celsius = projectEligibleAttempts([contribution(0, { value: 100, unit: '°C' })], NOW);
  assert.equal(fahrenheit.length, 1);
  assert.equal(celsius.length, 1);
  assert.equal(COMMUNITY_TAXONOMY_V1.bucket(fahrenheit[0]), COMMUNITY_TAXONOMY_V1.bucket(celsius[0]));
});
