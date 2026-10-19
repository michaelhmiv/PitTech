import { ApiError } from './core.ts';

// Slice 17 backend-prep lane: the eligible-attempt projection.
//
// This module is the SINGLE choke point between public publication data and the
// community aggregation job. The job never reads raw tables: every contribution
// passes through projectEligibleAttempts, which enforces eligibility, resolves
// derived artifacts to their original measured-attempt identity, deduplicates,
// and normalizes temperatures to Celsius. Anything that cannot be projected is
// either excluded (normal eligibility) or throws (malformed or forbidden input).
//
// Structural forbidden-input guarantees:
// - The input types below have no fields for editable recipe instructions and
//   no fields for private-sync records. Exact-shape validation rejects unknown
//   keys, so smuggling those sources through this boundary throws fail-closed.
// - This module imports only core.ts. It never imports sync, auth, relay,
//   publication-pack, or lifecycle modules, and its SQL lives behind the
//   allowlisted community_aggregation.eligible_attempts() projection (see the
//   aggregation module), which selects no instruction fields.
// - Story verdicts/ratings never enter the projected output: there is no author
//   ranking from inferred quality anywhere in this lane.

export type TemperatureUnit = '°C' | '°F';

// Community v1 measured inputs carried for the real summarizers. All
// temperatures are Celsius-only; offsets are story-relative milliseconds.
// Measurements and gaps come from the public story's frozen arrays, sorted
// deterministically; verdicts/ratings never enter these shapes.
export type CommunityMeasurement = {
  readonly offsetMillis: number;
  readonly role: 'food' | 'pit';
  readonly valueCelsius: number;
  readonly quality: 'measured' | 'manual' | 'recovered';
};
export type CommunityGap = { readonly fromMillis: number; readonly toMillis: number };

// Narrow contribution shape accepted at the projection boundary. Deliberately
// minimal: original measured-attempt identity, dish identity, publication
// status for eligibility, bucketing descriptors, and stub metric fields whose
// meanings stay unpinned (see the aggregation module). No instructions, no
// verdicts, no private-library content.
export type RawContribution = {
  readonly itemId: string;
  readonly revisionId: string;
  readonly status: string;
  readonly collectedAtMillis: number;
  readonly attemptId: string;
  readonly dishId: string;
  readonly foodType: string;
  readonly cut: string | null;
  readonly weightGrams: number | null;
  readonly method: string | null;
  readonly heatingMillis: number | null;
  readonly finalTemp: { readonly value: number; readonly unit: TemperatureUnit } | null;
  // V1 measured inputs for the real summarizers. Optional on input so the
  // framework's own fixtures (which predate v1) still validate; the projection
  // fills defaults (null/false/empty) when absent. Production rows from
  // extractRawContributions always carry these from the story.
  readonly foodOnOffsetMillis?: number | null;
  readonly wrapAtOffsetMillis?: number | null;
  readonly spritzed?: boolean;
  readonly measurements?: readonly CommunityMeasurement[];
  readonly gaps?: readonly CommunityGap[];
};

// Projected output: normalized, deduplicated, Celsius-only. Downstream code
// sees no units, no statuses, no collection structure, and no forbidden source.
export type EligibleAttempt = {
  readonly key: string;
  readonly attemptId: string;
  readonly dishId: string;
  readonly foodType: string;
  readonly cut: string | null;
  readonly weightGrams: number | null;
  readonly method: string | null;
  readonly heatingMillis: number | null;
  readonly finalTempCelsius: number | null;
  readonly foodOnOffsetMillis: number | null;
  readonly wrapAtOffsetMillis: number | null;
  readonly spritzed: boolean;
  readonly measurements: readonly CommunityMeasurement[];
  readonly gaps: readonly CommunityGap[];
};

const invalid = () => new ApiError(422, 'invalid_community_contribution');
const forbidden = () => new ApiError(422, 'forbidden_community_input');
const conflict = () => new ApiError(422, 'community_identity_conflict');
const uuid = (value: unknown): value is string =>
  typeof value === 'string' && /^[a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(value);

// Known-sensitive keys get their own error code so tests can prove the guard
// names the forbidden source. Every other unknown key is still rejected.
const FORBIDDEN_KEYS = new Set([
  'instructions', 'instructionText', 'syncRecords', 'syncEnvelope', 'syncRows',
  'privateLibrary', 'privateRows', 'privateSync', 'syncPayload',
  // 'verdict' is deliberately absent: storyStubFields tolerates the story-level
  // verdict key for shape compatibility but structurally validates and never
  // extracts it, so verdicts/ratings cannot influence the projection.
]);
const RAW_KEYS_REQUIRED = ['itemId', 'revisionId', 'status', 'collectedAtMillis', 'attemptId',
  'dishId', 'foodType', 'cut', 'weightGrams', 'method', 'heatingMillis', 'finalTemp'].sort();
// V1 measured inputs are optional on the boundary so pre-v1 fixtures still
// validate; production rows always carry them. Unknown keys still throw.
const RAW_KEYS_OPTIONAL = ['foodOnOffsetMillis', 'wrapAtOffsetMillis', 'spritzed', 'measurements', 'gaps'].sort();
const RAW_KEYS_ALLOWED = new Set([...RAW_KEYS_REQUIRED, ...RAW_KEYS_OPTIONAL]);
const KNOWN_STATUSES = new Set(['active', 'pending_review', 'unpublished', 'taken_down', 'purged']);

// Recorded spritz/mop/spray milestone actions counted by wrap-spritz
// prevalence. Exact normalized action match only; free-text mentions without a
// spritz action do not count, so the figure stays deterministic.
export const COMMUNITY_SPRITZ_ACTIONS_V1: readonly string[] = [
  'mop', 'mopped', 'mopping', 'spray', 'sprayed', 'spraying', 'spritz', 'spritzed', 'spritzing',
];

// Temperature conventions follow the codebase: Cook Pack measurements carry an
// explicit '°C'/'°F' unit (pack.ts) and public stories record valueCelsius
// (publication.schema.json). The projection boundary normalizes to Celsius so
// attempts recorded in °F and °C bucket together without unit confusion.
export function toCelsius(value: number, unit: TemperatureUnit): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw invalid();
  if (unit !== '°C' && unit !== '°F') throw invalid();
  const celsius = unit === '°F' ? (value - 32) * 5 / 9 : value;
  if (celsius < -100 || celsius > 1000) throw invalid();
  return celsius;
}

function text(value: unknown, max: number): string {
  if (typeof value !== 'string' || value.length > max) throw invalid();
  return value;
}

function nullableText(value: unknown, max: number): string | null {
  if (value === null) return null;
  return text(value, max);
}

const MEASUREMENT_INPUT_KEYS = ['offsetMillis', 'quality', 'role', 'valueCelsius'].sort();
const GAP_INPUT_KEYS = ['fromMillis', 'toMillis'].sort();
const offsetOrNull = (value: unknown): value is number | null =>
  value === null || (Number.isSafeInteger(value) && (value as number) >= -604800000 && (value as number) <= 2592000000);

function contributionMeasurements(value: unknown): CommunityMeasurement[] {
  if (!Array.isArray(value) || value.length > 250000) throw invalid();
  const out: CommunityMeasurement[] = [];
  for (const raw of value) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw invalid();
    const m = raw as Record<string, unknown>;
    if (JSON.stringify(Object.keys(m).sort()) !== JSON.stringify(MEASUREMENT_INPUT_KEYS)) throw invalid();
    if (!Number.isSafeInteger(m.offsetMillis) || (m.offsetMillis as number) < -604800000 || (m.offsetMillis as number) > 2592000000) throw invalid();
    if (m.role !== 'food' && m.role !== 'pit') throw invalid();
    if (m.quality !== 'measured' && m.quality !== 'manual' && m.quality !== 'recovered') throw invalid();
    if (typeof m.valueCelsius !== 'number' || !Number.isFinite(m.valueCelsius)) throw invalid();
    toCelsius(m.valueCelsius, '°C');
    out.push({ offsetMillis: m.offsetMillis as number, role: m.role, valueCelsius: m.valueCelsius, quality: m.quality });
  }
  out.sort((a, b) => a.offsetMillis - b.offsetMillis || (a.role < b.role ? -1 : a.role > b.role ? 1 : 0)
    || a.valueCelsius - b.valueCelsius || (a.quality < b.quality ? -1 : a.quality > b.quality ? 1 : 0));
  return out;
}

function contributionGaps(value: unknown): CommunityGap[] {
  if (!Array.isArray(value) || value.length > 1000) throw invalid();
  const out: CommunityGap[] = [];
  for (const raw of value) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw invalid();
    const g = raw as Record<string, unknown>;
    if (JSON.stringify(Object.keys(g).sort()) !== JSON.stringify(GAP_INPUT_KEYS)) throw invalid();
    if (!Number.isSafeInteger(g.fromMillis) || !Number.isSafeInteger(g.toMillis)) throw invalid();
    const from = g.fromMillis as number, to = g.toMillis as number;
    if (from < -604800000 || from > 2592000000 || to < -604800000 || to > 2592000000 || to < from) throw invalid();
    out.push({ fromMillis: from, toMillis: to });
  }
  out.sort((a, b) => a.fromMillis - b.fromMillis || a.toMillis - b.toMillis);
  const deduped: CommunityGap[] = [];
  for (const gap of out) {
    const last = deduped[deduped.length - 1];
    if (!last || last.fromMillis !== gap.fromMillis || last.toMillis !== gap.toMillis) deduped.push(gap);
  }
  return deduped;
}

export function rawContribution(value: unknown): RawContribution {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid();
  const row = value as Record<string, unknown>;
  for (const key of Object.keys(row)) if (FORBIDDEN_KEYS.has(key)) throw forbidden();
  for (const key of Object.keys(row)) if (!RAW_KEYS_ALLOWED.has(key)) throw invalid();
  for (const key of RAW_KEYS_REQUIRED) if (!(key in row)) throw invalid();
  if (!uuid(row.itemId) || !uuid(row.revisionId)) throw invalid();
  if (typeof row.status !== 'string' || !KNOWN_STATUSES.has(row.status)) throw invalid();
  if (!Number.isSafeInteger(row.collectedAtMillis) || (row.collectedAtMillis as number) < 0) throw invalid();
  if (!uuid(row.attemptId) || !uuid(row.dishId)) throw invalid();
  const foodType = text(row.foodType, 160);
  const cut = nullableText(row.cut, 160);
  const method = nullableText(row.method, 160);
  const weight = row.weightGrams;
  if (weight !== null && (typeof weight !== 'number' || !Number.isFinite(weight) || weight <= 0 || weight > 1000000)) throw invalid();
  const heating = row.heatingMillis;
  if (heating !== null && (!Number.isSafeInteger(heating) || (heating as number) < 0)) throw invalid();
  const temp = row.finalTemp;
  if (temp !== null) {
    if (!temp || typeof temp !== 'object' || Array.isArray(temp)) throw invalid();
    const entry = temp as Record<string, unknown>;
    if (JSON.stringify(Object.keys(entry).sort()) !== JSON.stringify(['unit', 'value'])) throw invalid();
    toCelsius(entry.value as number, entry.unit as TemperatureUnit);
  }
  if ('foodOnOffsetMillis' in row && !offsetOrNull(row.foodOnOffsetMillis)) throw invalid();
  if ('wrapAtOffsetMillis' in row && !offsetOrNull(row.wrapAtOffsetMillis)) throw invalid();
  if ('spritzed' in row && typeof row.spritzed !== 'boolean') throw invalid();
  // Normalize v1 arrays deterministically so direct fixtures and story-derived
  // rows project identically. A fresh object is returned; the caller's input
  // is never mutated.
  const normalized: Record<string, unknown> = { ...row };
  if ('measurements' in row) normalized.measurements = contributionMeasurements(row.measurements);
  if ('gaps' in row) normalized.gaps = contributionGaps(row.gaps);
  return normalized as unknown as RawContribution;
}

// Dedupe identity scheme: original measured-attempt ID + dish identity.
//
// Each physical cook keeps its original measured-attempt identity for life.
// Derived artifacts resolve back to it before bucketing:
// - public fork: retains the source story's attempt ID (a fork is not a second
//   measured cook) and the same dish identities; resolves to the source key.
// - saved copy republished unchanged: pins the source revision and retains the
//   attempt ID; resolves to the source key.
// - recipe revision: owner edits create a new revision but keep the attempt
//   identity (recorded facts stay frozen); only the current revision is
//   projected, and it resolves to the same key.
// - repeated collection membership: the same dish pinned in several collections
//   (or twice) carries the same attempt/dish pair; membership is not a sample.
// - genuine subsequent cook: "share my actual cook" mints a NEW attempt ID, so
//   a second cook of the same dish is a second attempt even for the same dish.
//
// Within one dedupe group the representative is the lexicographically smallest
// (itemId, revisionId), and measured fields must agree: recorded facts are
// frozen, so disagreement is a data-integrity conflict, never silently merged.
export function dedupeKey(attemptId: string, dishId: string): string {
  return `${attemptId.toLowerCase()}\0${dishId.toLowerCase()}`;
}

export function projectEligibleAttempts(input: readonly unknown[], cutoffMillis: number): EligibleAttempt[] {
  if (!Number.isSafeInteger(cutoffMillis) || cutoffMillis < 0) throw invalid();
  const groups = new Map<string, RawContribution[]>();
  for (const raw of input) {
    const row = rawContribution(raw);
    if (row.status !== 'active') continue;
    if (row.collectedAtMillis > cutoffMillis) continue;
    const key = dedupeKey(row.attemptId, row.dishId);
    const group = groups.get(key) ?? [];
    group.push(row);
    groups.set(key, group);
  }
  const attempts: EligibleAttempt[] = [];
  for (const [key, group] of [...groups.entries()].sort(([a], [b]) => (a < b ? -1 : 1))) {
    const ordered = [...group].sort((a, b) =>
      a.itemId < b.itemId ? -1 : a.itemId > b.itemId ? 1 : (a.revisionId < b.revisionId ? -1 : 1));
    const first = ordered[0];
    const heating = first.heatingMillis;
    const celsius = first.finalTemp === null ? null : toCelsius(first.finalTemp.value, first.finalTemp.unit);
    const firstFoodOn = first.foodOnOffsetMillis ?? null;
    const firstWrap = first.wrapAtOffsetMillis ?? null;
    const firstSpritzed = first.spritzed ?? false;
    const firstMeasurements = [...(first.measurements ?? [])];
    const firstGaps = [...(first.gaps ?? [])];
    const firstMeasurementsJson = JSON.stringify(firstMeasurements);
    const firstGapsJson = JSON.stringify(firstGaps);
    for (const other of ordered.slice(1)) {
      const otherCelsius = other.finalTemp === null ? null : toCelsius(other.finalTemp.value, other.finalTemp.unit);
      if (other.heatingMillis !== heating || otherCelsius !== celsius) throw conflict();
      // V1 measured inputs are frozen facts too: same attempt+dish must carry
      // the same story-derived values, or the group conflicts instead of merging.
      const otherFoodOn = other.foodOnOffsetMillis ?? null;
      const otherWrap = other.wrapAtOffsetMillis ?? null;
      const otherSpritzed = other.spritzed ?? false;
      const otherMeasurementsJson = JSON.stringify([...(other.measurements ?? [])]);
      const otherGapsJson = JSON.stringify([...(other.gaps ?? [])]);
      if (otherFoodOn !== firstFoodOn || otherWrap !== firstWrap || otherSpritzed !== firstSpritzed
        || otherMeasurementsJson !== firstMeasurementsJson || otherGapsJson !== firstGapsJson) throw conflict();
    }
    attempts.push({
      key, attemptId: first.attemptId, dishId: first.dishId,
      foodType: first.foodType, cut: first.cut, weightGrams: first.weightGrams, method: first.method,
      heatingMillis: heating, finalTempCelsius: celsius,
      foodOnOffsetMillis: firstFoodOn, wrapAtOffsetMillis: firstWrap, spritzed: firstSpritzed,
      measurements: firstMeasurements, gaps: firstGaps,
    });
  }
  return attempts;
}

// Story shape accepted from the allowlisted SQL projection. Measured fields,
// channel identities (for gap validation), milestone actions (wrap/spritz),
// and gap ranges are read; verdicts/ratings are validated for shape but never
// extracted, so no quality signal exists to rank by. Anything outside the known
// story keys is rejected; known-sensitive keys are forbidden outright.
const STORY_KEYS = ['attemptId', 'channels', 'foodOffOffsetMillis', 'foodOnOffsetMillis',
  'gaps', 'measurements', 'milestones', 'verdict'].sort();
const MEASUREMENT_KEYS = ['channelId', 'offsetMillis', 'quality', 'role', 'valueCelsius'].sort();
const offset = (value: unknown): value is number | null =>
  value === null || (Number.isSafeInteger(value) && (value as number) >= -604800000 && (value as number) <= 2592000000);

const MILESTONE_KEYS = ['action', 'id', 'offsetMillis', 'text'].sort();
const GAP_KEYS = ['channelId', 'fromMillis', 'toMillis'].sort();
const CHANNEL_KEYS = ['id', 'label', 'role'].sort();

type StoryMilestone = { id: string; offsetMillis: number; action: string };
type StoryChannel = { id: string; role: string };

function storyMilestones(value: unknown): StoryMilestone[] {
  if (!Array.isArray(value) || value.length > 1000) throw invalid();
  const out: StoryMilestone[] = [];
  for (const raw of value) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw invalid();
    const m = raw as Record<string, unknown>;
    if (JSON.stringify(Object.keys(m).sort()) !== JSON.stringify(MILESTONE_KEYS)) throw invalid();
    if (!uuid(m.id)) throw invalid();
    if (!Number.isSafeInteger(m.offsetMillis) || (m.offsetMillis as number) < -604800000 || (m.offsetMillis as number) > 2592000000) throw invalid();
    if (typeof m.action !== 'string' || (m.action as string).length > 80) throw invalid();
    if (typeof m.text !== 'string' || (m.text as string).length > 2000) throw invalid();
    out.push({ id: m.id as string, offsetMillis: m.offsetMillis as number, action: m.action as string });
  }
  return out;
}

function storyChannels(value: unknown): StoryChannel[] {
  if (!Array.isArray(value) || value.length > 32) throw invalid();
  const out: StoryChannel[] = [];
  for (const raw of value) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw invalid();
    const c = raw as Record<string, unknown>;
    if (JSON.stringify(Object.keys(c).sort()) !== JSON.stringify(CHANNEL_KEYS)) throw invalid();
    if (typeof c.id !== 'string' || (c.id as string).length > 80 || (c.id as string).length < 1) throw invalid();
    if (c.role !== 'food' && c.role !== 'pit') throw invalid();
    if (typeof c.label !== 'string' || (c.label as string).length > 160) throw invalid();
    out.push({ id: c.id as string, role: c.role as string });
  }
  return out;
}

function storyGaps(value: unknown, channels: readonly StoryChannel[]): CommunityGap[] {
  if (!Array.isArray(value) || value.length > 1000) throw invalid();
  const ids = new Set(channels.map((c) => c.id));
  const out: CommunityGap[] = [];
  for (const raw of value) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw invalid();
    const g = raw as Record<string, unknown>;
    if (JSON.stringify(Object.keys(g).sort()) !== JSON.stringify(GAP_KEYS)) throw invalid();
    if (typeof g.channelId !== 'string' || !ids.has(g.channelId as string)) throw invalid();
    if (!Number.isSafeInteger(g.fromMillis) || !Number.isSafeInteger(g.toMillis)) throw invalid();
    const from = g.fromMillis as number, to = g.toMillis as number;
    if (from < -604800000 || from > 2592000000 || to < -604800000 || to > 2592000000 || to < from) throw invalid();
    out.push({ fromMillis: from, toMillis: to });
  }
  out.sort((a, b) => a.fromMillis - b.fromMillis || a.toMillis - b.toMillis);
  return out.filter((gap, i, arr) => i === 0 || gap.fromMillis !== arr[i - 1].fromMillis || gap.toMillis !== arr[i - 1].toMillis);
}

/** V1 measured inputs derived from a public story. Wrap is the first `wrap`
 * milestone ordered by (offsetMillis, id) per the v1 wrapAt meaning; spritz is
 * an exact normalized-action match against COMMUNITY_SPRITZ_ACTIONS_V1.
 * Measurements and gaps are sorted/deduped deterministically. Verdicts are
 * validated for shape but never extracted. */
export function storyV1Fields(story: unknown): Pick<RawContribution,
  'foodOnOffsetMillis' | 'wrapAtOffsetMillis' | 'spritzed' | 'measurements' | 'gaps'> {
  if (!story || typeof story !== 'object' || Array.isArray(story)) throw invalid();
  const row = story as Record<string, unknown>;
  for (const key of Object.keys(row)) if (FORBIDDEN_KEYS.has(key)) throw forbidden();
  for (const key of Object.keys(row)) if (!STORY_KEYS.includes(key)) throw invalid();
  const channels = storyChannels(row.channels);
  const milestones = storyMilestones(row.milestones);
  const gaps = storyGaps(row.gaps, channels);
  if (!row.verdict || typeof row.verdict !== 'object' || Array.isArray(row.verdict)) throw invalid();
  if (!uuid(row.attemptId) || !offset(row.foodOnOffsetMillis) || !offset(row.foodOffOffsetMillis)) throw invalid();
  const on = row.foodOnOffsetMillis as number | null, off = row.foodOffOffsetMillis as number | null;
  if (on !== null && off !== null && off < on) throw invalid();
  if (!Array.isArray(row.measurements) || row.measurements.length > 250000) throw invalid();
  const measurements: CommunityMeasurement[] = [];
  for (const raw of row.measurements) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw invalid();
    const m = raw as Record<string, unknown>;
    if (JSON.stringify(Object.keys(m).sort()) !== JSON.stringify(MEASUREMENT_KEYS)) throw invalid();
    if (typeof m.channelId !== 'string' || (m.channelId as string).length > 80) throw invalid();
    if (!offset(m.offsetMillis) || m.offsetMillis === null) throw invalid();
    if (m.role !== 'food' && m.role !== 'pit') throw invalid();
    if (m.quality !== 'measured' && m.quality !== 'manual' && m.quality !== 'recovered') throw invalid();
    if (typeof m.valueCelsius !== 'number' || !Number.isFinite(m.valueCelsius)) throw invalid();
    toCelsius(m.valueCelsius, '°C');
    measurements.push({
      offsetMillis: m.offsetMillis as number, role: m.role,
      valueCelsius: m.valueCelsius, quality: m.quality,
    });
  }
  measurements.sort((a, b) => a.offsetMillis - b.offsetMillis || (a.role < b.role ? -1 : a.role > b.role ? 1 : 0)
    || a.valueCelsius - b.valueCelsius || (a.quality < b.quality ? -1 : a.quality > b.quality ? 1 : 0));
  const wraps = milestones
    .filter((m) => m.action.trim().toLowerCase() === 'wrap')
    .sort((a, b) => a.offsetMillis - b.offsetMillis || (a.id < b.id ? -1 : 1));
  const spritzed = milestones.some((m) =>
    (COMMUNITY_SPRITZ_ACTIONS_V1 as readonly string[]).includes(m.action.trim().toLowerCase()));
  return {
    foodOnOffsetMillis: on,
    wrapAtOffsetMillis: wraps.length ? wraps[0].offsetMillis : null,
    spritzed,
    measurements,
    gaps,
  };
}

/** Non-authoritative stub derivation from a public measured story. Slice 15's
 * calculation contract replaces these derivations with reviewed metric inputs;
 * the shape (nullable measured fields) is the stable part of this interface. */
export function storyStubFields(story: unknown): Pick<RawContribution, 'heatingMillis' | 'finalTemp'> {
  if (!story || typeof story !== 'object' || Array.isArray(story)) throw invalid();
  const row = story as Record<string, unknown>;
  for (const key of Object.keys(row)) if (FORBIDDEN_KEYS.has(key)) throw forbidden();
  for (const key of Object.keys(row)) if (!STORY_KEYS.includes(key)) throw invalid();
  // Bounds match publication.schema.json. Contents of the unread fields are
  // never inspected: they cannot affect the output by construction.
  if (!Array.isArray(row.channels) || row.channels.length > 32) throw invalid();
  if (!Array.isArray(row.milestones) || row.milestones.length > 1000) throw invalid();
  if (!Array.isArray(row.gaps) || row.gaps.length > 1000) throw invalid();
  if (!row.verdict || typeof row.verdict !== 'object' || Array.isArray(row.verdict)) throw invalid();
  if (!uuid(row.attemptId) || !offset(row.foodOnOffsetMillis) || !offset(row.foodOffOffsetMillis)) throw invalid();
  const on = row.foodOnOffsetMillis as number | null, off = row.foodOffOffsetMillis as number | null;
  if (on !== null && off !== null && off < on) throw invalid();
  if (!Array.isArray(row.measurements) || row.measurements.length > 250000) throw invalid();
  let food: { offsetMillis: number; valueCelsius: number } | null = null;
  for (const raw of row.measurements) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw invalid();
    const m = raw as Record<string, unknown>;
    if (JSON.stringify(Object.keys(m).sort()) !== JSON.stringify(MEASUREMENT_KEYS)) throw invalid();
    if (typeof m.channelId !== 'string' || (m.channelId as string).length > 80) throw invalid();
    if (!offset(m.offsetMillis) || m.offsetMillis === null) throw invalid();
    if (m.role !== 'food' && m.role !== 'pit') throw invalid();
    if (!['measured', 'manual', 'recovered'].includes(String(m.quality))) throw invalid();
    if (typeof m.valueCelsius !== 'number' || !Number.isFinite(m.valueCelsius)) throw invalid();
    toCelsius(m.valueCelsius, '°C');
    if (m.role === 'food') food = { offsetMillis: m.offsetMillis as number, valueCelsius: m.valueCelsius };
  }
  return {
    heatingMillis: on === null || off === null ? null : off - on,
    finalTemp: food === null ? null : { value: (food as { valueCelsius: number }).valueCelsius, unit: '°C' },
  };
}

// Exact row shape returned by community_aggregation.eligible_attempts().
// Snake-case fixed columns: any extra key (including smuggled instruction or
// private-library content) throws instead of being silently dropped.
const VIEW_KEYS = ['attempt_id', 'collected_at', 'cut', 'dish_id', 'food_type',
  'item_id', 'method', 'revision_id', 'story', 'weight_grams'].sort();

export function extractRawContributions(rows: readonly unknown[]): RawContribution[] {
  return rows.map((value) => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid();
    const row = value as Record<string, unknown>;
    for (const key of Object.keys(row)) if (FORBIDDEN_KEYS.has(key)) throw forbidden();
    if (JSON.stringify(Object.keys(row).sort()) !== JSON.stringify(VIEW_KEYS)) throw invalid();
    if (!uuid(row.item_id) || !uuid(row.revision_id)) throw invalid();
    const collected = typeof row.collected_at === 'string' ? Number(row.collected_at) : row.collected_at;
    if (!Number.isSafeInteger(collected) || (collected as number) < 0) throw invalid();
    if (!uuid(row.dish_id) || !uuid(row.attempt_id)) throw invalid();
    const story = row.story as Record<string, unknown> | null;
    if (!story || (story as { attemptId?: unknown }).attemptId !== row.attempt_id) throw invalid();
    const fields = storyStubFields(story);
    const v1 = storyV1Fields(story);
    return rawContribution({
      itemId: row.item_id, revisionId: row.revision_id, status: 'active',
      collectedAtMillis: collected, attemptId: row.attempt_id, dishId: row.dish_id,
      foodType: row.food_type, cut: row.cut, weightGrams: row.weight_grams, method: row.method,
      heatingMillis: fields.heatingMillis, finalTemp: fields.finalTemp,
      foodOnOffsetMillis: v1.foodOnOffsetMillis, wrapAtOffsetMillis: v1.wrapAtOffsetMillis,
      spritzed: v1.spritzed, measurements: v1.measurements, gaps: v1.gaps,
    });
  });
}
