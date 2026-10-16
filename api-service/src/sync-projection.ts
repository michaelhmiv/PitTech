import { readFile } from 'node:fs/promises';
import { ApiError } from './core.ts';
import { syncEnvelope, type SyncEnvelope } from './sync-envelope.ts';
import type { SyncRecord } from './sync-merge.ts';
export const privateProjectionContract = JSON.parse(await readFile(new URL('../../contracts/private-sync-projection-v1.json', import.meta.url), 'utf8')) as Contract;
type Node = { ref?: string; nullable?: boolean; unsupported?: boolean; type?: string; min?: number; max?: number; maxKeys?: number; keyMax?: number; exclusiveMin?: number; nonBlank?: boolean; uniqueBy?: string; enum?: unknown[]; fields?: Record<string, Node>; required?: string[]; items?: Node; values?: Node; refJson?: string; any?: Node[]; select?: string; selectSibling?: string; cases?: Record<string, Node> };
type RecordSpec = { key: string; payload: Node; omit: string[]; aliases?: Record<string, string>; parseJsonFields?: string[]; references: { field: string; target: string; same?: Record<string, string>; parentOrdinal?: boolean }[] };
type DocumentRule = { scope: string; sameFields?: Record<string, string>; omit?: string[]; references?: { field: string; target: string; sameCook: string; allowMissing?: boolean }[] };
type Contract = { contractVersion: number; releaseReady: boolean; recordTypes: Record<string, RecordSpec>; definitions: Record<string, Node>; excludedRootFields: string[]; excludedDocumentKinds: string[]; documentRules: Record<string, DocumentRule> };
const invalid = () => new ApiError(422, 'invalid_private_library');
export function validatePrivateValue(value: unknown, descriptor: Node, depth = 0, parent?: Record<string, unknown>): void {
  if (depth > 40) throw invalid();
  const n = descriptor.ref ? privateProjectionContract.definitions[descriptor.ref] : descriptor;
  if (!n || n.unsupported) throw new ApiError(422, 'private_record_codec_unavailable');
  if (n.selectSibling) {
    const selected = n.cases?.[String(parent?.[n.selectSibling])];
    if (!selected) throw new ApiError(422, 'private_record_codec_unavailable');
    validatePrivateValue(value, selected, depth + 1); return;
  }
  if (value === null && n.nullable) return;
  if (n.any) { for (const candidate of n.any) { try { validatePrivateValue(value, candidate, depth + 1); return; } catch { /* Try the next explicitly declared shape. */ } } throw invalid(); }
  if (n.select) { if (!value || typeof value !== 'object') throw invalid(); const selected = n.cases?.[String((value as Record<string, unknown>)[n.select])]; if (!selected) throw invalid(); validatePrivateValue(value, selected, depth + 1); return; }
  if (n.enum && !n.enum.includes(value)) throw invalid();
  if (n.type === 'object') {
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid(); const o = value as Record<string, unknown>, fields = n.fields ?? {};
    if (Object.keys(o).some(k => !Object.hasOwn(fields, k)) || (n.required ?? []).some(k => !Object.hasOwn(o, k))) throw invalid();
    for (const [k, v] of Object.entries(o)) validatePrivateValue(v, fields[k], depth + 1, o);
  } else if (n.type === 'array') {
    if (!Array.isArray(value) || value.length < (n.min ?? 0) || value.length > (n.max ?? 100000) || !n.items) throw invalid(); for (const v of value) validatePrivateValue(v, n.items, depth + 1);
    if (n.uniqueBy && new Set(value.map(v => v[n.uniqueBy!])).size !== value.length) throw invalid();
  } else if (n.type === 'map') {
    if (!value || typeof value !== 'object' || Array.isArray(value) || !n.values) throw invalid();
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length > (n.maxKeys ?? 100000)) throw invalid();
    for (const [k, v] of entries) { if (k.length > (n.keyMax ?? 32768)) throw invalid(); validatePrivateValue(v, n.values, depth + 1); }
  } else if (n.type === 'string' || n.type === 'id' || n.type === 'uuid' || n.type === 'json') {
    if (typeof value !== 'string' || value.length > (n.max ?? 32768)) throw invalid();
    if (n.nonBlank && !value.trim()) throw invalid();
    if (n.type === 'id' && (!value.length || /[^A-Za-z0-9:_-]/.test(value))) throw invalid();
    if (n.type === 'uuid' && (value.length !== 36 || !/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value))) throw invalid();
    if (n.type === 'json') { let parsed: unknown; try { parsed = JSON.parse(value); } catch { throw invalid(); } validatePrivateValue(parsed, privateProjectionContract.definitions[n.refJson!], depth + 1); }
  } else if (n.type === 'number' || n.type === 'integer') {
    if (typeof value !== 'number' || !Number.isFinite(value) || n.type === 'integer' && !Number.isSafeInteger(value) || value < (n.min ?? -Number.MAX_SAFE_INTEGER) || value > (n.max ?? Number.MAX_SAFE_INTEGER) || n.exclusiveMin !== undefined && value <= n.exclusiveMin) throw invalid();
  } else if (n.type === 'boolean') { if (typeof value !== 'boolean') throw invalid(); }
  else throw invalid();
}
export function validatePrivateRows(rows: SyncRecord[]) {
  if (rows.length > 100000 || new Set(rows.map(r => r.recordType + '|' + r.recordId)).size !== rows.length) throw invalid();
  const live = new Map(rows.filter(r => !r.deleted).map(r => [r.recordType + '|' + r.recordId, r.payload!]));
  const mediaIds = new Set<string>();
  for (const r of rows) {
    if (r.deleted) continue;
    const spec = privateProjectionContract.recordTypes[r.recordType], p = r.payload!; if (!spec) throw invalid();
    validatePrivateValue(p, spec.payload); if (p[spec.key] !== r.recordId) throw invalid();
    if ('assetId' in (spec.payload.fields ?? {})) {
      if (r.assetIds.length !== 1 || r.assetIds[0] !== p.assetId || mediaIds.has(r.recordId)) throw invalid(); mediaIds.add(r.recordId);
    } else if (r.assetIds.length) throw invalid();
    for (const ref of spec.references) {
      const id = p[ref.field]; if (id === undefined || id === null) continue;
      const parent = live.get(ref.target + '|' + String(id)); if (!parent) throw invalid();
      for (const [self, target] of Object.entries(ref.same ?? {})) if (p[self] !== parent[target]) throw invalid();
      if (ref.parentOrdinal && !(Number(parent.revisionNumber) < Number(p.revisionNumber))) throw invalid();
    }
    if (r.recordType === 'logReadingLinks') { const reading = live.get('readings|' + p.readingId), event = live.get('events|' + p.eventId); if (!reading || !event || reading.cookId !== event.cookId || reading.source !== 'manual') throw invalid(); }
    if (r.recordType === 'reminderRules') { const trigger = JSON.parse(String(p.triggerConfig)); if (trigger.type !== p.kind || p.schemaVersion !== 1) throw invalid(); }
    if (r.recordType === 'events' && typeof p.noteMetadataJson === 'string') {
      for (const id of JSON.parse(p.noteMetadataJson).dishIds ?? []) if (live.get('dishes|' + id)?.cookId !== p.cookId) throw invalid();
    }
    if (r.recordType === 'companionRecords') {
      const rule = privateProjectionContract.documentRules[String(p.kind)], doc = p.payload as Record<string, unknown>;
      if (!rule || rule.scope === 'global' && p.cookId != null || rule.scope === 'cook' && typeof p.cookId !== 'string') throw invalid();
      for (const [docKey, rowKey] of Object.entries(rule.sameFields ?? {})) if ((doc[docKey] ?? null) !== (p[rowKey] ?? null)) throw invalid();
      for (const ref of rule.references ?? []) {
        const parent = live.get(ref.target + '|' + doc[ref.field]);
        if (!parent && ref.allowMissing) continue;
        if (!parent || parent.cookId !== (ref.sameCook === '$record' ? p.cookId : doc[ref.sameCook])) throw invalid();
      }
      const kind = String(p.kind);
      if (kind === 'plan') {
        const dishes = ((doc.draft as Record<string, unknown>).dishes ?? []) as unknown[], dishIds = (doc.dishIds ?? []) as unknown[];
        const stepIds = new Set(((doc.steps ?? []) as { id: unknown }[]).map(s => s.id));
        if (!Array.isArray(doc.dishIds) || dishIds.length !== dishes.length || new Set(dishIds).size !== dishIds.length) throw invalid();
        for (const id of dishIds) if (live.get('dishes|' + String(id))?.cookId !== p.cookId) throw invalid();
        for (const key of Object.keys((doc.progress ?? {}) as Record<string, unknown>)) if (!stepIds.has(key)) throw invalid();
      }
      if (kind === 'alert') {
        if (doc.dishId != null && live.get('dishes|' + String(doc.dishId))?.cookId !== p.cookId) throw invalid();
        if (doc.type === 'target' && !(typeof doc.targetF === 'number' && doc.targetF >= 32 && doc.targetF <= 600)) throw invalid();
        if (doc.type === 'pit_range' && !(typeof doc.lowF === 'number' && typeof doc.highF === 'number' && (doc.lowF as number) < (doc.highF as number))) throw invalid();
      }
      if (kind === 'upcoming' || kind === 'serve_goal') {
        const dishes = (((doc.book as Record<string, unknown>).draft as Record<string, unknown>).dishes ?? []) as unknown[];
        const schedules = (doc.schedules ?? []) as { dish: unknown; min: unknown; max: unknown }[];
        const order = (schedules.map(s => s.dish) as number[]).sort((a, b) => a - b);
        if (schedules.length !== dishes.length || !order.every((d, i) => d === i)) throw invalid();
        for (const s of schedules) if (!(Number(s.max) >= Number(s.min))) throw invalid();
      }
      if (kind === 'sharing_draft' || kind === 'collection_revision' || kind === 'recipe_reference') {
        const entries = (doc.entries ?? []) as Record<string, unknown>[];
        for (const e of entries) {
          const photoIds = (e.photoIds ?? []) as unknown[];
          if (new Set(photoIds).size !== photoIds.length) throw invalid();
          for (const id of photoIds) {
            const photo = live.get('companionPhotos|' + String(id));
            if (!photo || photo.recordId !== p.id || photo.action !== e.id) throw invalid();
          }
        }
        if (doc.heroPhotoId != null && !entries.some(e => e.included === true && ((e.photoIds ?? []) as unknown[]).includes(doc.heroPhotoId))) throw invalid();
      }
      if (kind === 'guide_presentation' && doc.dishIds != null) {
        const dishIds = doc.dishIds as unknown[];
        if (new Set(dishIds).size !== dishIds.length) throw invalid();
        for (const id of dishIds) if (live.get('dishes|' + String(id))?.cookId !== p.cookId) throw invalid();
      }
    }
  }
}
function stripCompanionOmit(payload: Record<string, unknown>) {
  const rule = privateProjectionContract.documentRules[String(payload.kind)];
  for (const path of rule?.omit ?? []) {
    const parts = path.split('.');
    let nodes: unknown[] = [payload.payload];
    for (let i = 0; i < parts.length - 1; i++) {
      const next: unknown[] = [];
      for (const node of nodes) {
        if (!node || typeof node !== 'object' || Array.isArray(node)) continue;
        const o = node as Record<string, unknown>;
        if (parts[i] === '*') next.push(...Object.values(o));
        else if (parts[i] in o) next.push(o[parts[i]]);
      }
      nodes = next;
    }
    const last = parts[parts.length - 1];
    for (const node of nodes) {
      if (!node || typeof node !== 'object' || Array.isArray(node)) continue;
      const o = node as Record<string, unknown>;
      if (last === '*') for (const k of Object.keys(o)) delete o[k];
      else delete o[last];
    }
  }
}
/** Testable native-archive boundary. Production accepts only the portable envelope. */
export function projectPrivateArchive(value: unknown, assets: Record<string, string> = {}) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid();
  const root = value as Record<string, unknown>, specs = privateProjectionContract.recordTypes;
  if (Object.keys(root).some(k => !Object.hasOwn(specs, k) && !privateProjectionContract.excludedRootFields.includes(k))) throw invalid();
  const rows: { recordType: string; recordId: string; payload: Record<string, unknown>; assetIds: string[] }[] = [];
  for (const [type, spec] of Object.entries(specs)) {
    if (root[type] === undefined) continue;
    if (!Array.isArray(root[type])) throw invalid();
    for (const raw of root[type]) {
      if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw invalid();
      const fields = spec.payload.fields ?? {}, source = structuredClone(raw) as Record<string, unknown>;
      if (type === 'companionRecords' && privateProjectionContract.excludedDocumentKinds.includes(String(source.kind))) continue;
      for (const [local, portable] of Object.entries(spec.aliases ?? {})) if (Object.hasOwn(source, local)) {
        if (Object.hasOwn(source, portable) && source[local] !== source[portable]) throw invalid();
        source[portable] = source[local]; delete source[local];
      }
      if (Object.keys(source).some(k => !Object.hasOwn(fields, k) && !spec.omit.includes(k))) throw invalid();
      for (const key of spec.parseJsonFields ?? []) if (typeof source[key] === 'string') {
        try { source[key] = JSON.parse(source[key]); } catch { throw invalid(); }
      }
      const payload = Object.fromEntries(Object.entries(source).filter(([k]) => Object.hasOwn(fields, k)));
      const recordId = source[spec.key]; if (typeof recordId !== 'string') throw invalid();
      if (Object.hasOwn(fields, 'assetId')) payload.assetId = assets[type + '|' + recordId];
      if (type === 'companionRecords') stripCompanionOmit(payload);
      rows.push({ recordType: type, recordId, payload, assetIds: typeof payload.assetId === 'string' ? [payload.assetId] : [] });
    }
  }
  validatePrivateRows(rows.map(r => ({ ...r, deleted: false, version: { counter: 1, clientId: 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa' } })));
  return rows.sort((a, b) => { const x = a.recordType + '|' + a.recordId, y = b.recordType + '|' + b.recordId; return x < y ? -1 : x > y ? 1 : 0; });
}
/** Explicit allowlist plus ownership graph, not a generic archive import. Companion
 * document codecs cover every authored kind; native durable apply stays an open gate. */
export function privateLibraryEnvelope(value: unknown): SyncEnvelope {
  const envelope = syncEnvelope(value); validatePrivateRows(envelope.records);
  for (const c of envelope.conflicts) for (const row of [c.winner, c.loser]) if (!row.deleted) {
    const spec = privateProjectionContract.recordTypes[row.recordType]; if (!spec) throw invalid(); validatePrivateValue(row.payload, spec.payload);
    if (row.payload?.[spec.key] !== row.recordId) throw invalid();
    if ('assetId' in (spec.payload.fields ?? {})) { if (row.assetIds.length !== 1 || row.assetIds[0] !== row.payload?.assetId) throw invalid(); }
    else if (row.assetIds.length) throw invalid();
  }
  return envelope;
}
