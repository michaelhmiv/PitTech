// Slice 17: community taxonomy v1 (production) plus the framework stub.
//
// The prep lane defined only the bucket interface; this lane pins the reviewed
// versioned food/cut groupings and weight-band boundaries. The stub stays for
// the framework's own unit tests and must never be presented as analysis.
export type BucketInput = {
  readonly foodType: string;
  readonly cut: string | null;
  readonly weightGrams: number | null;
  readonly method: string | null;
  // Optional equipment context where the eligible-attempt projection carries
  // it. Absent in v1 (the projection carries no equipment field), so production
  // keys have three segments; a future projection that carries equipment gets a
  // fourth segment without widening existing buckets.
  readonly equipment?: string | null;
};

export type CommunityTaxonomy = {
  // Version of the food/cut/method grouping rules. Recorded on every figure and
  // every job run so a taxonomy change is always visible in the outputs.
  readonly taxonomyVersion: string;
  // Version of the weight-band boundaries, tracked alongside the taxonomy.
  readonly bandVersion: string;
  // Stable bucket key for one eligible attempt, or null when the attempt cannot
  // be bucketed (it is then counted as unbucketed, never silently dropped).
  bucket(input: BucketInput): string | null;
};

/** Non-authoritative test stub. NOT a food/cut taxonomy: it groups by raw
 * food-type text only, deliberately ignores cut/weight/method, and must never
 * be presented as community analysis. The production job uses
 * COMMUNITY_TAXONOMY_V1; the job records whichever versions it runs with. */
export const STUB_TAXONOMY_NON_AUTHORITATIVE: CommunityTaxonomy = {
  taxonomyVersion: 'stub-non-authoritative-v0',
  bandVersion: 'stub-bands-non-authoritative-v0',
  bucket(input) {
    const food = input.foodType.trim().toLowerCase();
    if (!food) return null;
    return `stub:${food}`;
  },
};

export const COMMUNITY_TAXONOMY_VERSION = 'community-taxonomy-v1';
export const COMMUNITY_BAND_VERSION = 'community-bands-v1';

// Production taxonomy groups. Keyed on (food type, cut) pairs from the echoed
// `cut`/`foodType` vocabulary the codebase actually records: foodType is the
// native dropdown (Beef, Pork, Poultry, Seafood, Wild game, Vegetables, Other,
// plus free-text variants like beef/chicken/turkey); cut is free text echoed
// exactly (Whole packer, Baby back, Boston butt, Brisket point, Shoulder, ...).
// Every named group requires a non-blank cut that matches its patterns: a null
// or unrecognized cut goes to `other`, never silently merged into a named group.
export const COMMUNITY_TAXONOMY_GROUPS_V1 = [
  'beef-brisket',
  'pork-butt-shoulder',
  'pork-spare-ribs',
  'pork-baby-back-ribs',
  'pork-loin',
  'chicken-whole',
  'chicken-pieces',
  'turkey-whole',
  'other',
] as const;
export type CommunityTaxonomyGroupV1 = (typeof COMMUNITY_TAXONOMY_GROUPS_V1)[number];

// Weight-band boundaries in grams (integers). Per cut family, small/medium/
// large plus unknown (null weight). Lb/kg equivalents are documented in
// docs/online/17-community.md; 1 lb = 453.59237 g.
export type WeightBandV1 = 'small' | 'medium' | 'large' | 'unknown';
type BandBounds = { readonly smallMaxG: number; readonly mediumMaxG: number };
const BANDS: Record<string, BandBounds> = {
  brisket: { smallMaxG: 4536, mediumMaxG: 7257 },
  porkButt: { smallMaxG: 2722, mediumMaxG: 4536 },
  ribs: { smallMaxG: 1134, mediumMaxG: 1814 },
  porkLoin: { smallMaxG: 1361, mediumMaxG: 2268 },
  chickenWhole: { smallMaxG: 1361, mediumMaxG: 2268 },
  chickenPieces: { smallMaxG: 907, mediumMaxG: 1814 },
  turkey: { smallMaxG: 5443, mediumMaxG: 8165 },
  other: { smallMaxG: 2000, mediumMaxG: 6000 },
};
export const COMMUNITY_WEIGHT_BANDS_V1: Record<CommunityTaxonomyGroupV1, BandBounds> = {
  'beef-brisket': BANDS.brisket,
  'pork-butt-shoulder': BANDS.porkButt,
  'pork-spare-ribs': BANDS.ribs,
  'pork-baby-back-ribs': BANDS.ribs,
  'pork-loin': BANDS.porkLoin,
  'chicken-whole': BANDS.chickenWhole,
  'chicken-pieces': BANDS.chickenPieces,
  'turkey-whole': BANDS.turkey,
  other: BANDS.other,
};

const normFood = (value: string): string => value.trim().toLowerCase();
const normCut = (value: string): string =>
  value.toLowerCase().replace(/[^a-z0-9]+/g, ' ').replace(/\s+/g, ' ').trim();
const normLabel = (value: string, max: number): string =>
  value.trim().toLowerCase().replace(/\s+/g, ' ').slice(0, max);

const PIECES_HINTS = ['thigh', 'breast', 'wing', 'drumstick', 'drum', 'leg quarter', 'quarter', 'pieces', 'parts'];
const hasPiecesHint = (cut: string): boolean => PIECES_HINTS.some((hint) => cut.includes(hint));

function groupFor(food: string, cut: string | null): CommunityTaxonomyGroupV1 {
  const cutNorm = cut === null ? '' : normCut(cut);
  const hasCut = cutNorm.length > 0;
  if (food === 'pork' && hasCut) {
    // Baby backs first: they contain "loin" in the alias "loin back ribs" and
    // must never fall through to the loin roast group.
    if (cutNorm.includes('baby back') || cutNorm.includes('babyback')) return 'pork-baby-back-ribs';
    if (cutNorm.includes('spare') || cutNorm.includes('st louis')) return 'pork-spare-ribs';
    if (cutNorm.includes('butt') || cutNorm.includes('shoulder') || cutNorm.includes('boston') || cutNorm.includes('picnic')) {
      return 'pork-butt-shoulder';
    }
    // Pork loin is a lean roast with a different cook from butt/shoulder: the
    // plan's hard rule keeps them separate. Tenderloin and rib aliases stay out.
    if (cutNorm.includes('loin') && !cutNorm.includes('tenderloin') && !cutNorm.includes('back rib') && !cutNorm.includes('baby')) {
      return 'pork-loin';
    }
    return 'other';
  }
  if (food === 'beef' && hasCut) {
    if (cutNorm.includes('brisket') || cutNorm.includes('packer') || cutNorm === 'point' || cutNorm === 'flat'
      || cutNorm.includes('brisket point') || cutNorm.includes('brisket flat')) return 'beef-brisket';
    return 'other';
  }
  if ((food === 'poultry' || food === 'chicken') && hasCut) {
    // A turkey cut under the poultry dropdown is a whole turkey unless it is
    // a pieces cut (breast/parts go to other, per the documented groupings).
    if (cutNorm.includes('turkey')) return hasPiecesHint(cutNorm) ? 'other' : 'turkey-whole';
    if (cutNorm.includes('whole')) return 'chicken-whole';
    if (hasPiecesHint(cutNorm)) return 'chicken-pieces';
    return 'other';
  }
  if (food === 'turkey' && hasCut) {
    if (hasPiecesHint(cutNorm)) return 'other';
    return 'turkey-whole';
  }
  return 'other';
}

function bandFor(group: CommunityTaxonomyGroupV1, weightGrams: number | null): WeightBandV1 {
  if (weightGrams === null) return 'unknown';
  const bounds = COMMUNITY_WEIGHT_BANDS_V1[group];
  if (weightGrams < bounds.smallMaxG) return 'small';
  if (weightGrams <= bounds.mediumMaxG) return 'medium';
  return 'large';
}

/** Production taxonomy v1. Bucket key: `group|band|method:<label>` with an
 * optional fourth `|equipment:<label>` segment when the projection carries
 * equipment. Missing method/equipment narrows to an explicit `unknown` segment
 * (or omits the equipment segment), never widening to a shared bucket. Blank
 * food is unbucketable (null); unknown cuts go to `other`. */
export const COMMUNITY_TAXONOMY_V1: CommunityTaxonomy = {
  taxonomyVersion: COMMUNITY_TAXONOMY_VERSION,
  bandVersion: COMMUNITY_BAND_VERSION,
  bucket(input) {
    const food = normFood(input.foodType);
    if (!food) return null;
    const weight = input.weightGrams;
    if (weight !== null && (typeof weight !== 'number' || !Number.isFinite(weight) || weight <= 0 || weight > 1000000)) {
      return null;
    }
    const group = groupFor(food, input.cut);
    const band = bandFor(group, weight);
    const methodRaw = input.method === null || input.method === undefined ? '' : normLabel(input.method, 100);
    const method = methodRaw ? `method:${methodRaw}` : 'method:unknown';
    let key = `${group}|${band}|${method}`;
    const equipmentRaw = input.equipment === null || input.equipment === undefined ? '' : normLabel(input.equipment, 80);
    if (equipmentRaw) key += `|equipment:${equipmentRaw}`;
    if (key.length > 320 || key.length < 1) return null;
    return key;
  },
};
