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
const RAW_KEYS = ['itemId', 'revisionId', 'status', 'collectedAtMillis', 'attemptId',
  'dishId', 'foodType', 'cut', 'weightGrams', 'method', 'heatingMillis', 'finalTemp'].sort();
const KNOWN_STATUSES = new Set(['active', 'pending_review', 'unpublished', 'taken_down', 'purged']);

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

export function rawContribution(value: unknown): RawContribution {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid();
  const row = value as Record<string, unknown>;
  for (const key of Object.keys(row)) if (FORBIDDEN_KEYS.has(key)) throw forbidden();
  if (JSON.stringify(Object.keys(row).sort()) !== JSON.stringify(RAW_KEYS)) throw invalid();
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
  return row as unknown as RawContribution;
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
    for (const other of ordered.slice(1)) {
      const otherCelsius = other.finalTemp === null ? null : toCelsius(other.finalTemp.value, other.finalTemp.unit);
      if (other.heatingMillis !== heating || otherCelsius !== celsius) throw conflict();
    }
    attempts.push({
      key, attemptId: first.attemptId, dishId: first.dishId,
      foodType: first.foodType, cut: first.cut, weightGrams: first.weightGrams, method: first.method,
      heatingMillis: heating, finalTempCelsius: celsius,
    });
  }
  return attempts;
}

// Story shape accepted from the allowlisted SQL projection. Only measured
// fields are read; channels/milestones/gaps/verdicts are tolerated for shape
// compatibility but never extracted, so verdicts/ratings cannot influence the
// projection (no author ranking from inferred quality). Anything outside the
// known story keys is rejected; known-sensitive keys are forbidden outright.
const STORY_KEYS = ['attemptId', 'channels', 'foodOffOffsetMillis', 'foodOnOffsetMillis',
  'gaps', 'measurements', 'milestones', 'verdict'].sort();
const MEASUREMENT_KEYS = ['channelId', 'offsetMillis', 'quality', 'role', 'valueCelsius'].sort();
const offset = (value: unknown): value is number | null =>
  value === null || (Number.isSafeInteger(value) && (value as number) >= -604800000 && (value as number) <= 2592000000);

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
    return rawContribution({
      itemId: row.item_id, revisionId: row.revision_id, status: 'active',
      collectedAtMillis: collected, attemptId: row.attempt_id, dishId: row.dish_id,
      foodType: row.food_type, cut: row.cut, weightGrams: row.weight_grams, method: row.method,
      heatingMillis: fields.heatingMillis, finalTemp: fields.finalTemp,
    });
  });
}
