import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { ApiError } from '../src/core.ts';
import { AuthSecrets, credential } from '../src/auth-credentials.ts';
import { McpGrants, type McpGrantRecord, type McpGrantStore, type McpPrincipal } from '../src/mcp-grants.ts';
import { PrivateLibraryService, type McpPhotoStore, type SnapshotReadWriter } from '../src/mcp-library.ts';
import { McpServer } from '../src/mcp-server.ts';
import { syncEnvelope, syncTransition, type SyncEnvelope } from '../src/sync-envelope.ts';
import { stable } from '../src/pack.ts';
import { digest } from '../src/core.ts';

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
    this.envelope = structuredClone(next); this.version = next.baseVersion + 1;
    const receipt = { version: this.version, mutationId: next.mutationId, sha256: hash };
    this.receipts.set(next.mutationId, { hash, receipt });
    return structuredClone(receipt);
  }
}
/** In-memory chunked photo store honoring the native protocol shape. */
class FakePhotos implements McpPhotoStore {
  uploads = new Map<string, { bytes: number; mimeType: string; sha256: string; parts: Map<number, Buffer>; complete: boolean }>();
  async begin(_user: string, input: unknown) {
    const value = input as { assetId: string; bytes: number; mimeType: string; sha256: string };
    if (!this.uploads.has(value.assetId)) this.uploads.set(value.assetId, { ...value, parts: new Map(), complete: false });
    return this.statusOf(_user, value.assetId);
  }
  async statusOf(_user: string, asset: string) {
    const up = this.uploads.get(asset); if (!up) throw new ApiError(404, 'private_photo_missing');
    return { assetId: asset, sha256: up.sha256, bytes: up.bytes, chunkBytes: 1024 ** 2, receivedParts: [...up.parts.keys()].sort((a, b) => a - b), complete: up.complete };
  }
  async putPart(user: string, asset: string, index: number, bytes: Buffer) {
    const up = this.uploads.get(asset); if (!up) throw new ApiError(404, 'private_photo_missing');
    up.parts.set(index, Buffer.from(bytes)); return this.statusOf(user, asset);
  }
  async finish(user: string, asset: string) {
    const up = this.uploads.get(asset); if (!up) throw new ApiError(404, 'private_photo_missing');
    const whole = Buffer.concat([...up.parts.entries()].sort((a, b) => a[0] - b[0]).map(([, b]) => b));
    if (whole.length !== up.bytes || createHash('sha256').update(whole).digest('hex') !== up.sha256) throw new ApiError(422, 'private_photo_invalid');
    up.complete = true; return this.statusOf(user, asset);
  }
  async read(_user: string, asset: string) {
    const up = this.uploads.get(asset); if (!up?.complete) throw new ApiError(404, 'private_photo_missing');
    const bytes = Buffer.concat([...up.parts.entries()].sort((a, b) => a[0] - b[0]).map(([, b]) => b));
    return { bytes, mimeType: up.mimeType, sha256: up.sha256 };
  }
  async abandon(_user: string, asset: string) { this.uploads.delete(asset); return { abandoned: true }; }
}

test('photo lifecycle: chunked upload → attach → view → caption → remove', async () => {
  const grants = new McpGrants(new MemoryGrantStore(), new AuthSecrets(credential(), credential()), { state: async () => 'active' }, () => NOW);
  const snapshots = new FakeSnapshots(); const photos = new FakePhotos();
  const library = new PrivateLibraryService(grants, snapshots, { writer: snapshots, writesEnabled: true, clock: () => NOW, photos });
  const server = new McpServer(library);
  const principal: McpPrincipal = { grantId: 'g', userId: 'user-1', scopes: ['library:read', 'library:write', 'media:read', 'media:write'] };
  const call = async (name: string, args: Record<string, unknown> = {}) => {
    const response = await server.handle(principal, { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name, arguments: args } });
    return (response as { result: { isError: boolean; structuredContent?: Record<string, any>; content: { type: string; text?: string; data?: string; mimeType?: string }[] } }).result;
  };
  const meal = await call('create_meal', { title: 'Photo cook' });
  const mealId = meal.structuredContent!.mealId as string;
  const image = Buffer.from('fake-jpeg-bytes-for-the-bark-photo');
  const sha256 = createHash('sha256').update(image).digest('hex');
  const begun = await call('begin_photo_upload', { bytes: image.length, mimeType: 'image/jpeg', sha256 });
  assert.equal(begun.isError, false, begun.content?.[0]?.text);
  const assetId = begun.structuredContent!.assetId as string;
  const chunk = await call('upload_photo_chunk', { assetId, index: 0, dataBase64: image.toString('base64') });
  assert.equal(chunk.isError, false);
  // Attaching before completion must fail: the store has no committed asset.
  const early = await call('attach_photo', { assetId, mealId });
  assert.equal(early.isError, true);
  const done = await call('complete_photo_upload', { assetId });
  assert.equal(done.isError, false);
  assert.equal(done.structuredContent!.upload.complete, true);
  const attached = await call('attach_photo', { assetId, mealId, caption: 'Bark at wrap' });
  assert.equal(attached.isError, false, attached.content?.[0]?.text);
  const photoId = attached.structuredContent!.photoId as string;
  const record = snapshots.envelope!.records.find(r => r.recordType === 'photos')!;
  assert.deepEqual(record.assetIds, [assetId], 'record carries its asset reference');
  const listed = await call('list_photos', { mealId });
  assert.equal(listed.structuredContent!.total, 1);
  const viewed = await call('get_photo', { photoId });
  assert.equal(viewed.isError, false);
  assert.equal(viewed.content[0].type, 'image');
  assert.equal(Buffer.from(viewed.content[0].data!, 'base64').toString(), image.toString());
  const captioned = await call('update_photo', { photoId, caption: 'Bark, sliced' });
  assert.equal(captioned.isError, false);
  const removed = await call('remove_photo', { photoId });
  assert.equal(removed.isError, false);
  assert.equal((await call('list_photos', { mealId })).structuredContent!.total, 0);
});
