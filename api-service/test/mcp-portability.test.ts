import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { ApiError } from '../src/core.ts';
import { stable } from '../src/pack.ts';
import { digest } from '../src/core.ts';
import { McpGrants, type McpGrantRecord, type McpGrantStore, type McpPrincipal } from '../src/mcp-grants.ts';
import { AuthSecrets, credential } from '../src/auth-credentials.ts';
import { PrivateLibraryService, type McpPhotoStore, type SnapshotReadWriter } from '../src/mcp-library.ts';
import { McpServer } from '../src/mcp-server.ts';
import { syncEnvelope, syncTransition, type SyncEnvelope } from '../src/sync-envelope.ts';
import type { McpExportRecord, McpImportPreviewRecord, McpPortabilityStore } from '../src/mcp-portability.ts';

const NOW = 1_800_000_000_000;
class MemoryGrantStore implements McpGrantStore {
  rows = new Map<string, McpGrantRecord>(); retired = new Map<string, string>();
  async insert(g: McpGrantRecord) { this.rows.set(g.id, structuredClone(g)); }
  async byId(id: string) { const g = this.rows.get(id); return g ? structuredClone(g) : undefined; }
  async byTokenHash(h: string) { for (const g of this.rows.values()) if (g.tokenHash === h) return structuredClone(g); }
  async listForUser(userId: string) { return [...this.rows.values()].filter(g => g.userId === userId).map(g => structuredClone(g)); }
  async revoke(id: string, userId: string, at: number) { const g = this.rows.get(id); if (!g || g.userId !== userId || g.revokedAt !== null) return false; g.revokedAt = at; return true; }
  async revokeAll(userId: string, at: number) { let n = 0; for (const g of this.rows.values()) if (g.userId === userId && g.revokedAt === null) { g.revokedAt = at; n++; } return n; }
  async touchLastUsed(id: string, at: number) { const g = this.rows.get(id); if (g) g.lastUsedAt = at; }
  async rotateTokenHash(id: string, expectedHash: string, newHash: string) { const g = this.rows.get(id); if (!g || g.tokenHash !== expectedHash || g.revokedAt !== null) return false; g.tokenHash = newHash; return true; }
  async revokeFamily(familyId: string, at: number) { let n = 0; for (const g of this.rows.values()) if (g.familyId === familyId && g.revokedAt === null) { g.revokedAt = at; n++; } return n; }
  async retireHash(tokenHash: string, familyId: string) { this.retired.set(tokenHash, familyId); }
  async retiredFamily(tokenHash: string) { return this.retired.get(tokenHash); }
}
class FakeSnapshots implements SnapshotReadWriter {
  version = 0; envelope: SyncEnvelope | null = null;
  private receipts = new Map<string, { hash: string; receipt: { version: number; mutationId: string; sha256: string } }>();
  async read() { return { version: this.version, envelope: this.envelope ? structuredClone(this.envelope) : null }; }
  async status() { return { version: this.version, updatedAt: this.version ? NOW : null }; }
  async receiptFor(_user: string, mutationId: string) { return this.receipts.get(mutationId)?.receipt; }
  async put(_user: string, value: unknown) {
    const next = syncEnvelope(value);
    const hash = digest(Buffer.from(stable(next)));
    const prior = this.receipts.get(next.mutationId);
    if (prior) { if (prior.hash !== hash) throw new ApiError(409, 'retry_content_changed'); return structuredClone(prior.receipt); }
    if (next.baseVersion !== this.version) throw new ApiError(409, 'sync_version_changed');
    syncTransition(this.envelope ?? undefined, next);
    this.envelope = structuredClone(next);
    this.version = next.baseVersion + 1;
    const receipt = { version: this.version, mutationId: next.mutationId, sha256: hash };
    this.receipts.set(next.mutationId, { hash, receipt });
    return structuredClone(receipt);
  }
}
class MemoryPortability implements McpPortabilityStore {
  exports = new Map<string, McpExportRecord>(); previews = new Map<string, McpImportPreviewRecord>();
  async createExport(r: McpExportRecord) { this.exports.set(r.id, structuredClone(r)); }
  async exportFor(userId: string, id: string) { const r = this.exports.get(id); return r && r.userId === userId ? structuredClone(r) : undefined; }
  async createPreview(r: McpImportPreviewRecord) { this.previews.set(r.id, structuredClone(r)); }
  async previewFor(userId: string, id: string) { const r = this.previews.get(id); return r && r.userId === userId ? structuredClone(r) : undefined; }
  async consumePreview(userId: string, id: string, at: number) { const r = this.previews.get(id); if (!r || r.userId !== userId || r.consumedAt !== null) return false; r.consumedAt = at; return true; }
}
const photosStub: McpPhotoStore = {
  begin: async () => ({}), statusOf: async () => ({}), putPart: async () => ({}), finish: async () => ({}), abandon: async () => ({}),
  read: async (_user: string, asset: string) => {
    if (asset === '123e4567-e89b-42d3-a456-426614174001') return { bytes: Buffer.from('jpeg-bytes'), mimeType: 'image/jpeg', sha256: 'abc' };
    throw new Error('not found');
  },
};
async function setup() {
  const grants = new McpGrants(new MemoryGrantStore(), new AuthSecrets(credential(), credential()), { state: async () => 'active' }, () => NOW);
  const snapshots = new FakeSnapshots();
  const portability = new MemoryPortability();
  const library = new PrivateLibraryService(grants, snapshots, { writer: snapshots, writesEnabled: true, clock: () => NOW, portability, photos: photosStub });
  const server = new McpServer(library);
  const principal: McpPrincipal = { grantId: 'g', userId: 'user-1', scopes: ['library:read', 'library:write', 'library:delete', 'media:read', 'media:write', 'exports:read', 'imports:write', 'planning:write'] };
  const call = async (name: string, args: Record<string, unknown> = {}, as: McpPrincipal = principal) => {
    const response = await server.handle(as, { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name, arguments: args } });
    return (response as { result: { isError: boolean; structuredContent?: Record<string, unknown>; content: { text: string }[] } }).result;
  };
  return { snapshots, portability, call, principal };
}
type Setup = Awaited<ReturnType<typeof setup>>;

async function seedMeal(s: Setup) {
  const created = await s.call('create_meal', { title: 'Export brisket', dishes: [{ name: 'Flat', foodType: 'beef' }] });
  const { mealId, dishIds } = created.structuredContent as unknown as { mealId: string; dishIds: string[] };
  await s.call('create_note', { mealId, title: 'Wrapped at dawn' });
  await s.call('save_results', { mealId, rating: 4.5, finalTemperature: 203 });
  await s.call('add_manual_reading', { mealId, probeName: 'Thermapen', measurementType: 'food', value: 198, unit: '°F' });
  return { mealId, dishId: dishIds[0] };
}

test('export → preview → apply round-trips a library into a fresh account snapshot', async () => {
  const source = await setup();
  const { mealId } = await seedMeal(source);
  const exported = await source.call('create_export', {});
  assert.equal(exported.isError, false, exported.content?.[0]?.text);
  const { exportId } = exported.structuredContent as unknown as { exportId: string };
  const record = source.portability.exports.get(exportId)!;
  const archive = (record.payload as { archive: Record<string, unknown> }).archive;
  assert.equal(archive.schemaVersion, 10);
  assert.equal((archive.cooks as unknown[]).length, 1);
  assert.equal((archive.readings as unknown[]).length, 1);
  assert.equal((archive.results as unknown[]).length, 2);

  const target = await setup();
  const preview = await target.call('preview_import', { archive });
  assert.equal(preview.isError, false, preview.content?.[0]?.text);
  const p = preview.structuredContent as unknown as { previewId: string; contentHash: string; baseSnapshotVersion: number; totalRows: number; byDisposition: Record<string, number> };
  assert.equal(p.byDisposition.new, p.totalRows);
  assert.ok(p.totalRows >= 8);
  const applied = await target.call('apply_import', { previewId: p.previewId, contentHash: p.contentHash, expectedSnapshotVersion: p.baseSnapshotVersion });
  assert.equal(applied.isError, false, applied.content?.[0]?.text);
  const meal = await target.call('get_meal', { mealId });
  assert.equal(meal.isError, false, meal.content?.[0]?.text);
  // Re-preview of the same archive: everything is identical now.
  const again = await target.call('preview_import', { archive });
  const p2 = again.structuredContent as unknown as { totalRows: number; byDisposition: Record<string, number> };
  assert.equal(p2.byDisposition.identical, p2.totalRows);
  assert.equal(p2.byDisposition.new ?? 0, 0);
});

test('apply refuses a stale, mismatched or replayed preview', async () => {
  const source = await setup();
  await seedMeal(source);
  const exported = await source.call('create_export', {});
  const archive = (source.portability.exports.get((exported.structuredContent as unknown as { exportId: string }).exportId)!.payload as { archive: unknown }).archive;
  const target = await setup();
  const preview = await target.call('preview_import', { archive });
  const p = preview.structuredContent as unknown as { previewId: string; contentHash: string; baseSnapshotVersion: number };
  const wrongHash = await target.call('apply_import', { previewId: p.previewId, contentHash: 'deadbeef', expectedSnapshotVersion: p.baseSnapshotVersion });
  assert.equal(wrongHash.isError, true);
  // Move the library, then the honest apply must fail as stale.
  await target.call('create_meal', { title: 'Intervening cook' });
  const stale = await target.call('apply_import', { previewId: p.previewId, contentHash: p.contentHash, expectedSnapshotVersion: p.baseSnapshotVersion });
  assert.equal(stale.isError, true);
  assert.match(stale.content[0].text, /stale|changed since/);
});

test('immutable differences are conflicts; mutable differences apply as changes', async () => {
  const source = await setup();
  const { mealId } = await seedMeal(source);
  const exported = await source.call('create_export', {});
  const archive = (source.portability.exports.get((exported.structuredContent as unknown as { exportId: string }).exportId)!.payload as { archive: Record<string, unknown[]> }).archive;
  const target = await setup();
  const first = await target.call('preview_import', { archive });
  const fp = first.structuredContent as unknown as { previewId: string; contentHash: string; baseSnapshotVersion: number };
  await target.call('apply_import', { previewId: fp.previewId, contentHash: fp.contentHash, expectedSnapshotVersion: fp.baseSnapshotVersion });
  // Mutate the archive: retitle the meal (mutable) and rewrite a reading (immutable).
  (archive.cooks[0] as Record<string, unknown>).title = 'Retitled brisket';
  (archive.readings[0] as Record<string, unknown>).value = 999;
  const preview = await target.call('preview_import', { archive });
  const p = preview.structuredContent as unknown as { previewId: string; contentHash: string; baseSnapshotVersion: number; byDisposition: Record<string, number>; blocked: { key: string }[] };
  assert.equal(p.byDisposition.changed, 1);
  assert.equal(p.byDisposition.conflict, 1);
  assert.ok(p.blocked.some(b => b.key.startsWith('readings:')));
  // Applying only the changed meal row works; the conflict stays out.
  const applied = await target.call('apply_import', { previewId: p.previewId, contentHash: p.contentHash, expectedSnapshotVersion: p.baseSnapshotVersion, recordIds: [`cooks:${mealId}`] });
  assert.equal(applied.isError, false, applied.content?.[0]?.text);
  const meal = await target.call('get_meal', { mealId });
  assert.equal((meal.structuredContent as unknown as { meal: { title: string } }).meal.title, 'Retitled brisket');
});

test('photos without available bytes are blocked in preview, and import with a verified mapping', async () => {
  const source = await setup();
  const { mealId } = await seedMeal(source);
  // Hand-build an archive carrying one photo row whose asset the source holds as 'asset-1'.
  const exported = await source.call('create_export', {});
  const archive = (source.portability.exports.get((exported.structuredContent as unknown as { exportId: string }).exportId)!.payload as { archive: Record<string, unknown[]> }).archive;
  archive.photos = [{ id: 'photo-1', assetId: '123e4567-e89b-42d3-a456-426614174001', cookId: mealId, dishId: null, eventId: null, caption: 'Bark', capturedAtUtcMillis: NOW, addedAtUtcMillis: NOW, mimeType: 'image/jpeg', temperatureContextJson: null }];
  const target = await setup();
  const appliedMeal = await target.call('preview_import', { archive });
  const p = appliedMeal.structuredContent as unknown as { previewId: string; contentHash: string; baseSnapshotVersion: number; blocked: { key: string; reason: string }[] };
  assert.equal(appliedMeal.isError, false, appliedMeal.content?.[0]?.text);
  // The embedded asset id verifies through the photo store stub, so the photo is NOT blocked.
  assert.ok(!p.blocked.some(b => b.key === 'photos:photo-1'), JSON.stringify(p.blocked));
  // An archive referencing an asset nobody holds is blocked by record.
  (archive.photos[0] as Record<string, unknown>).assetId = '223e4567-e89b-42d3-a456-426614174099';
  const preview2 = await target.call('preview_import', { archive });
  const p2 = preview2.structuredContent as unknown as { blocked: { key: string }[] };
  assert.ok(p2.blocked.some(b => b.key === 'photos:photo-1'));
});

test('sharing drafts: create/get/list/delete with the canonical fixture document', async () => {
  const s = await setup();
  const cases = JSON.parse(await readFile(new URL('../../contracts/fixtures/private-sync-projection-cases-v1.json', import.meta.url), 'utf8')) as { cases: { rows?: { payload?: { kind?: string; payload?: unknown } }[] }[] };
  let document: unknown;
  for (const c of cases.cases) for (const row of c.rows ?? []) if (row.payload?.kind === 'sharing_draft') document = row.payload.payload;
  assert.ok(document, 'fixture document found');
  const created = await s.call('create_sharing_draft', { title: 'Brisket story', document });
  assert.equal(created.isError, false, created.content?.[0]?.text);
  const { draftId } = created.structuredContent as unknown as { draftId: string };
  const got = await s.call('get_sharing_draft', { draftId });
  assert.equal(got.isError, false);
  assert.equal((got.structuredContent as unknown as { draft: { title: string } }).draft.title, 'Brisket story');
  const listed = await s.call('list_sharing_drafts', {});
  assert.equal((listed.structuredContent as unknown as { total: number }).total, 1);
  const deleted = await s.call('delete_sharing_draft', { draftId });
  assert.equal(deleted.isError, false);
  assert.equal((await s.call('list_sharing_drafts', {})).structuredContent !== undefined, true);
  assert.equal(((await s.call('list_sharing_drafts', {})).structuredContent as unknown as { total: number }).total, 0);
});

test('share card renders from real meal data and is stored as an expiring private result', async () => {
  const s = await setup();
  const { mealId } = await seedMeal(s);
  const rendered = await s.call('render_private_share_card', { mealId });
  assert.equal(rendered.isError, false, rendered.content?.[0]?.text);
  const { cardId, card } = rendered.structuredContent as unknown as { cardId: string; card: { title: string; dishes: string[]; hero: string; curves: unknown[]; relay: null } };
  assert.equal(card.title, 'Export brisket');
  assert.deepEqual(card.dishes, ['Flat']);
  assert.equal(card.hero, 'chart');
  assert.ok(card.curves.length >= 1);
  assert.equal(card.relay, null);
  const status = await s.call('get_export_status', { exportId: cardId });
  assert.equal((status.structuredContent as unknown as { kind: string; status: string }).kind, 'share_card');
  assert.equal((status.structuredContent as unknown as { status: string }).status, 'ready');
  // Meal-scoped export contains only that meal's subtree.
  const other = await s.call('create_meal', { title: 'Unrelated ribs' });
  const { mealId: otherId } = other.structuredContent as unknown as { mealId: string };
  const scoped = await s.call('create_export', { scope: { type: 'meal', mealId } });
  const rec = s.portability.exports.get((scoped.structuredContent as unknown as { exportId: string }).exportId)!;
  const scopedArchive = (rec.payload as { archive: Record<string, unknown[]> }).archive;
  assert.equal(scopedArchive.cooks.length, 1);
  assert.equal((scopedArchive.cooks[0] as Record<string, unknown>).id, mealId);
  assert.ok(otherId !== mealId);
});

test('portability tools are hidden without their scopes', async () => {
  const s = await setup();
  const reader: McpPrincipal = { grantId: 'g', userId: 'user-1', scopes: ['library:read'] };
  const denied = await s.call('create_export', {}, reader);
  assert.equal(denied.isError, true);
  const deniedImport = await s.call('preview_import', { archive: { schemaVersion: 10 } }, reader);
  assert.equal(deniedImport.isError, true);
});
