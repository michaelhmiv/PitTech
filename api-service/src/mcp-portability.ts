import { readFile } from 'node:fs/promises';
import { Ajv2020 } from 'ajv/dist/2020.js';
import type { Pool } from 'pg';
import { digest } from './core.ts';
import { stable } from './pack.ts';
import { privateProjectionContract } from './sync-projection.ts';
import type { SyncRecord } from './sync-merge.ts';

/** MCP portability (issue #114, M5): private import/export/share-card
 * artifacts. Exports and rendered cards are owned, expiring results —
 * never public links, never the publication or relay paths. Import
 * previews bind the projected rows, the snapshot version they were
 * projected against, and the archive's content hash; apply re-checks
 * all three, so a stale preview can never write. */

export type PortableRow = { recordType: string; recordId: string; payload: Record<string, unknown>; assetIds: string[] };

export interface McpExportRecord {
  id: string; userId: string; kind: 'archive' | 'share_card';
  scope: Record<string, unknown>; payload: Record<string, unknown>;
  counts: Record<string, number>; createdAt: number; expiresAt: number;
}
export interface McpImportPreviewRecord {
  id: string; userId: string; grantId: string; contentHash: string;
  baseVersion: number; rows: PortableRow[]; summary: Record<string, unknown>;
  createdAt: number; expiresAt: number; consumedAt: number | null;
}
export interface McpPortabilityStore {
  createExport(record: McpExportRecord): Promise<void>;
  exportFor(userId: string, id: string): Promise<McpExportRecord | undefined>;
  createPreview(record: McpImportPreviewRecord): Promise<void>;
  previewFor(userId: string, id: string): Promise<McpImportPreviewRecord | undefined>;
  consumePreview(userId: string, id: string, at: number): Promise<boolean>;
}

export class PostgresMcpPortabilityStore implements McpPortabilityStore {
  private pool: Pool;
  constructor(pool: Pool) { this.pool = pool; }
  async createExport(record: McpExportRecord): Promise<void> {
    await this.pool.query(
      'INSERT INTO auth.mcp_exports(id, user_id, kind, scope, payload, counts, created_at, expires_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8)',
      [record.id, record.userId, record.kind, JSON.stringify(record.scope), JSON.stringify(record.payload), JSON.stringify(record.counts), record.createdAt, record.expiresAt]);
  }
  async exportFor(userId: string, id: string): Promise<McpExportRecord | undefined> {
    const row = (await this.pool.query(
      'SELECT id, kind, scope, payload, counts, created_at, expires_at FROM auth.mcp_exports WHERE user_id=$1 AND id=$2',
      [userId, id])).rows[0] as { id: string; kind: 'archive' | 'share_card'; scope: Record<string, unknown>; payload: Record<string, unknown>; counts: Record<string, number>; created_at: string; expires_at: string } | undefined;
    if (!row) return undefined;
    return { id: row.id, userId, kind: row.kind, scope: row.scope, payload: row.payload, counts: row.counts, createdAt: Number(row.created_at), expiresAt: Number(row.expires_at) };
  }
  async createPreview(record: McpImportPreviewRecord): Promise<void> {
    await this.pool.query(
      'INSERT INTO auth.mcp_import_previews(id, user_id, grant_id, content_hash, base_version, rows, summary, created_at, expires_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)',
      [record.id, record.userId, record.grantId, record.contentHash, record.baseVersion, JSON.stringify(record.rows), JSON.stringify(record.summary), record.createdAt, record.expiresAt]);
  }
  async previewFor(userId: string, id: string): Promise<McpImportPreviewRecord | undefined> {
    const row = (await this.pool.query(
      'SELECT id, grant_id, content_hash, base_version, rows, summary, created_at, expires_at, consumed_at FROM auth.mcp_import_previews WHERE user_id=$1 AND id=$2',
      [userId, id])).rows[0] as { id: string; grant_id: string; content_hash: string; base_version: string; rows: PortableRow[]; summary: Record<string, unknown>; created_at: string; expires_at: string; consumed_at: string | null } | undefined;
    if (!row) return undefined;
    return {
      id: row.id, userId, grantId: row.grant_id, contentHash: row.content_hash, baseVersion: Number(row.base_version),
      rows: row.rows, summary: row.summary, createdAt: Number(row.created_at), expiresAt: Number(row.expires_at),
      consumedAt: row.consumed_at === null ? null : Number(row.consumed_at),
    };
  }
  async consumePreview(userId: string, id: string, at: number): Promise<boolean> {
    const result = await this.pool.query(
      'UPDATE auth.mcp_import_previews SET consumed_at=$3 WHERE user_id=$1 AND id=$2 AND consumed_at IS NULL',
      [userId, id, at]);
    return (result.rowCount ?? 0) > 0;
  }
}

/** The inverse of projectPrivateArchive: sync payloads back to the
 * portable archive (format 10) the apps import — reverse the field
 * aliases, re-stringify the JSON document fields, and lay the rows out
 * under their record-type names beside schemaVersion/preferences. */
export function buildPortableArchive(rows: SyncRecord[]): Record<string, unknown> {
  const root: Record<string, unknown> = { schemaVersion: 10, preferences: null };
  const live = rows.filter(r => !r.deleted && r.payload);
  for (const [type, spec] of Object.entries(privateProjectionContract.recordTypes)) {
    const list = live.filter(r => r.recordType === type).map(r => {
      const out = structuredClone(r.payload) as Record<string, unknown>;
      for (const field of spec.parseJsonFields ?? []) {
        if (out[field] !== undefined && typeof out[field] !== 'string') out[field] = JSON.stringify(out[field]);
      }
      for (const [local, portable] of Object.entries(spec.aliases ?? {})) {
        if (Object.hasOwn(out, portable)) { out[local] = out[portable]; delete out[portable]; }
      }
      return out;
    });
    const key = spec.key;
    list.sort((a, b) => String(a[key]) < String(b[key]) ? -1 : String(a[key]) > String(b[key]) ? 1 : 0);
    root[type] = list;
  }
  return root;
}

export type ExportScope = { type: 'library' } | { type: 'meal'; mealId: string } | { type: 'recipe'; recipeId: string };

const ofCook = (row: SyncRecord, mealId: string) => (row.payload as Record<string, unknown>)?.cookId === mealId;

/** Row selection for scoped exports, mirroring the per-cook history
 * scoping the apps use (CookPackHistory): a meal export carries the
 * whole cook subtree; a recipe export carries the recipe, its
 * revisions and its owned assets. */
export function scopeArchiveRows(rows: SyncRecord[], scope: ExportScope): SyncRecord[] {
  const live = rows.filter(r => !r.deleted && r.payload);
  if (scope.type === 'library') return live;
  if (scope.type === 'meal') {
    const dishes = live.filter(r => r.recordType === 'dishes' && ofCook(r, scope.mealId));
    const dishIds = new Set(dishes.map(r => r.recordId));
    const readings = live.filter(r => r.recordType === 'readings' && ofCook(r, scope.mealId));
    const readingIds = new Set(readings.map(r => r.recordId));
    const events = live.filter(r => r.recordType === 'events' && ofCook(r, scope.mealId));
    const eventIds = new Set(events.map(r => r.recordId));
    const probeIds = new Set(readings.map(r => String((r.payload as Record<string, unknown>).probeId)));
    return live.filter(r => {
      const p = r.payload as Record<string, unknown>;
      switch (r.recordType) {
        case 'cooks': return r.recordId === scope.mealId;
        case 'dishes': return p.cookId === scope.mealId;
        case 'ingredients': case 'dishRecipeSources': return dishIds.has(String(p.dishId));
        case 'events': case 'readings': case 'targets': case 'results': case 'photos':
        case 'lifecycles': case 'reminderRules': case 'companionRecords': return p.cookId === scope.mealId;
        case 'probes': return probeIds.has(r.recordId) || p.cookId === scope.mealId;
        case 'logReadingLinks': return readingIds.has(r.recordId) && eventIds.has(String(p.eventId));
        case 'targetHistory': return p.cookId === scope.mealId || dishIds.has(String(p.dishId));
        default: return false;
      }
    });
  }
  const revisions = live.filter(r => r.recordType === 'recipeRevisions' && (r.payload as Record<string, unknown>).recipeId === scope.recipeId);
  const revisionIds = new Set(revisions.map(r => r.recordId));
  return live.filter(r => {
    const p = r.payload as Record<string, unknown>;
    switch (r.recordType) {
      case 'recipes': return r.recordId === scope.recipeId;
      case 'recipeRevisions': return p.recipeId === scope.recipeId;
      case 'recipeAssets': return p.recipeId === scope.recipeId || revisionIds.has(String(p.revisionId));
      case 'dishRecipeSources': return p.recipeId === scope.recipeId;
      default: return false;
    }
  });
}

export const contentHashOf = (archive: unknown): string => digest(Buffer.from(stable(archive)));

/** Disposition of one projected import row against the current
 * library. `changed` rows are only writable for mutable record types;
 * immutable content (readings, recipe revisions) that differs is a
 * conflict, and a tombstoned identity can never be restored. */
export type ImportDisposition = 'new' | 'identical' | 'changed' | 'conflict' | 'restore_blocked';
const IMMUTABLE_TYPES = new Set(['readings', 'recipeRevisions']);
export function importDisposition(row: PortableRow, current: SyncRecord | undefined): ImportDisposition {
  if (!current) return 'new';
  if (current.deleted) return 'restore_blocked';
  const same = stable({ payload: current.payload, assetIds: [...current.assetIds].sort() }) ===
    stable({ payload: row.payload, assetIds: [...row.assetIds].sort() });
  if (same) return 'identical';
  return IMMUTABLE_TYPES.has(row.recordType) ? 'conflict' : 'changed';
}

const shareCardSchema = JSON.parse(await readFile(new URL('../../contracts/share-card.schema.json', import.meta.url), 'utf8')) as Record<string, unknown>;
const validateShareCard = new Ajv2020({ strict: false }).compile(shareCardSchema);

type Payload = Record<string, unknown>;
const num = (v: unknown): number | undefined => typeof v === 'number' && Number.isFinite(v) ? v : undefined;
const str = (v: unknown): string | undefined => typeof v === 'string' ? v : undefined;

/** Builds a prepared share card (share-card v1) from a meal's synced
 * data — the same document the apps prepare on device, computed
 * deterministically on the server. Presentation only: it records no
 * facts the library does not already hold, and its relay stays null
 * (publishing is a separate, explicit first-party act). Throws a
 * plain Error with a user-facing message on unusable input. */
export function buildShareCard(input: {
  meal: Payload; dishes: Payload[]; readings: Payload[]; events: Payload[];
  results: Payload[]; photoCount: number;
}): Record<string, unknown> {
  const { meal, dishes, readings, events, results, photoCount } = input;
  if (!dishes.length) throw new Error('A share card needs at least one dish on the meal.');
  const started = num(meal.startedAtUtcMillis) ?? num(meal.createdAtUtcMillis) ?? 0;
  const dishName = (id: unknown) => dishes.find(d => d.id === id)?.name as string | undefined;
  // Curves: one per probe (max 6), x = minutes from cook start,
  // segments split on gaps over 30 minutes, ≤200 points per segment.
  const byProbe = new Map<string, Payload[]>();
  for (const r of readings) {
    if (num(r.value) === undefined || num(r.measuredAtUtcMillis) === undefined) continue;
    const key = str(r.probeName) ?? 'Probe';
    byProbe.set(key, [...(byProbe.get(key) ?? []), r]);
  }
  const curves = [...byProbe.entries()].slice(0, 6).map(([label, rows]) => {
    const sorted = [...rows].sort((a, b) => num(a.measuredAtUtcMillis)! - num(b.measuredAtUtcMillis)!);
    const segments: { x: number; y: number }[][] = [];
    let current: { x: number; y: number }[] = [];
    let last = -Infinity;
    for (const r of sorted) {
      const at = num(r.measuredAtUtcMillis)!;
      if (at - last > 30 * 60_000 && current.length) { segments.push(current); current = []; }
      current.push({ x: Math.round((at - started) / 6000) / 10, y: num(r.value)! });
      last = at;
    }
    if (current.length) segments.push(current);
    const thinned = segments.slice(0, 50).map(seg => {
      if (seg.length <= 200) return seg;
      const stride = Math.ceil(seg.length / 200);
      const out = seg.filter((_, i) => i % stride === 0);
      if (out[out.length - 1] !== seg[seg.length - 1]) out.push(seg[seg.length - 1]);
      return out;
    });
    return { label, segments: thinned };
  });
  const milestones = [...events]
    .sort((a, b) => (num(a.occurredAtUtcMillis) ?? 0) - (num(b.occurredAtUtcMillis) ?? 0))
    .filter(e => str(e.eventType) !== 'note')
    .slice(0, 5)
    .map(e => ({
      dish: (e.dishId ? dishName(e.dishId) : undefined) ?? '',
      title: str(e.title) ?? str(e.eventType) ?? 'Milestone',
      offsetMillis: started && num(e.occurredAtUtcMillis) !== undefined ? num(e.occurredAtUtcMillis)! - started : null,
    }));
  const stats: { label: string; value: string }[] = [];
  const ended = num(meal.endedAtUtcMillis);
  if (ended !== undefined && started) {
    const mins = Math.max(0, Math.round((ended - started) / 60000));
    stats.push({ label: 'Cook time', value: mins >= 60 ? `${Math.floor(mins / 60)}h ${String(mins % 60).padStart(2, '0')}m` : `${mins}m` });
  }
  const rating = results.find(r => r.resultType === 'overall_rating' && r.dishId == null);
  if (rating && num(rating.numericValue) !== undefined) stats.push({ label: 'Rating', value: `${num(rating.numericValue)} / 5` });
  const finalTemp = results.find(r => r.resultType === 'final_temperature' && r.dishId == null);
  if (finalTemp && num(finalTemp.numericValue) !== undefined) stats.push({ label: 'Final temp', value: `${num(finalTemp.numericValue)} ${str(finalTemp.unit) ?? '°F'}` });
  const unit = readings.map(r => str(r.unit)).find(u => u === '°F' || u === '°C') ?? '°F';
  const note = (str(meal.notes) ?? '').slice(0, 2000);
  const card: Record<string, unknown> = {
    version: 1,
    title: (str(meal.title) ?? 'PitTech cook').slice(0, 160),
    dishes: dishes.map(d => String(d.name ?? 'Dish')).slice(0, 20),
    hero: photoCount > 0 ? 'photo' : readings.length ? 'chart' : 'none',
    unit, curves, channelCount: byProbe.size, milestones, stats: stats.slice(0, 3),
    note, cropX: 0.5, cropY: 0.5, zoom: 1, theme: 'classic', relay: null,
  };
  if (!validateShareCard(card)) throw new Error('The card could not be prepared from this meal’s data.');
  return card;
}
