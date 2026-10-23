import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError } from '../src/core.ts';
import { stable } from '../src/pack.ts';
import { digest } from '../src/core.ts';
import { McpGrants, type McpGrantRecord, type McpGrantStore, type McpPrincipal } from '../src/mcp-grants.ts';
import { AuthSecrets, credential } from '../src/auth-credentials.ts';
import { PrivateLibraryService, MCP_WRITER_CLIENT_ID, type SnapshotReadWriter } from '../src/mcp-library.ts';
import { McpServer } from '../src/mcp-server.ts';
import { syncEnvelope, syncTransition, type SyncEnvelope } from '../src/sync-envelope.ts';

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

/** In-memory SnapshotReadWriter with PrivateSnapshots' contract: CAS on
 * baseVersion, durable receipts per mutationId, same-id/different-content
 * is retry_content_changed. Every put also runs the real envelope +
 * transition validators, so constructed envelopes are proven legal. */
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
    if (prior) {
      if (prior.hash !== hash) throw new ApiError(409, 'retry_content_changed');
      return structuredClone(prior.receipt);
    }
    if (next.baseVersion !== this.version) throw new ApiError(409, 'sync_version_changed');
    syncTransition(this.envelope ?? undefined, next);
    this.envelope = structuredClone(next);
    this.version = next.baseVersion + 1;
    const receipt = { version: this.version, mutationId: next.mutationId, sha256: hash };
    this.receipts.set(next.mutationId, { hash, receipt });
    return structuredClone(receipt);
  }
}

async function setup(writesEnabled = true) {
  const grants = new McpGrants(new MemoryGrantStore(), new AuthSecrets(credential(), credential()), { state: async () => 'active' }, () => NOW);
  const snapshots = new FakeSnapshots();
  const library = new PrivateLibraryService(grants, snapshots, { writer: snapshots, writesEnabled, clock: () => NOW });
  const server = new McpServer(library);
  const principal: McpPrincipal = { grantId: 'g', userId: 'user-1', scopes: ['library:read', 'library:write', 'library:delete'] };
  const call = async (name: string, args: Record<string, unknown> = {}, as: McpPrincipal = principal) => {
    const response = await server.handle(as, { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name, arguments: args } });
    return (response as { result: { isError: boolean; structuredContent?: Record<string, unknown>; content: { text: string }[] } }).result;
  };
  const list = async (as: McpPrincipal = principal) => {
    const response = await server.handle(as, { jsonrpc: '2.0', id: 1, method: 'tools/list' });
    return (response as { result: { tools: { name: string }[] } }).result.tools.map(x => x.name);
  };
  return { grants, snapshots, library, server, principal, call, list };
}
type Setup = Awaited<ReturnType<typeof setup>>;
const KEY = '123e4567-e89b-42d3-a456-426614174000';

test('write tools appear only when writes are enabled, and only with their scopes', async () => {
  const on = await setup(true);
  const names = await on.list();
  for (const tool of ['create_meal', 'update_meal', 'transition_meal', 'delete_meal', 'add_dish', 'update_dish', 'remove_dish']) assert.ok(names.includes(tool), tool);
  const reader: McpPrincipal = { grantId: 'g', userId: 'user-1', scopes: ['library:read'] };
  const readerNames = await on.list(reader);
  assert.ok(!readerNames.includes('create_meal'));
  const denied = await on.call('create_meal', { title: 'X' }, reader);
  assert.equal(denied.isError, true);
  const off = await setup(false);
  assert.ok(!(await off.list()).includes('create_meal'), 'flag off → tool not even listed');
});

test('create_meal writes a legal envelope: writer stamps, base CAS, receipt; reads see it', async () => {
  const s: Setup = await setup();
  const created = await s.call('create_meal', { title: 'First MCP brisket', smokerName: 'Offset', dishes: [{ name: 'Flat', foodType: 'beef' }], idempotencyKey: KEY });
  assert.equal(created.isError, false, created.content?.[0]?.text);
  const content = created.structuredContent as unknown as { mealId: string; dishIds: string[]; receipt: { snapshotVersion: number; mutationId: string } };
  assert.equal(content.receipt.snapshotVersion, 1);
  assert.equal(content.receipt.mutationId, KEY);
  const stored = s.snapshots.envelope!;
  const cook = stored.records.find(r => r.recordType === 'cooks')!;
  assert.equal(cook.version.clientId, MCP_WRITER_CLIENT_ID);
  assert.equal(cook.version.counter, 1);
  assert.equal((cook.payload as { status: string }).status, 'active');
  const lifecycle = stored.records.find(r => r.recordType === 'lifecycles')!;
  assert.equal(lifecycle.recordId, content.mealId);
  const listed = await s.call('list_meals');
  assert.equal((listed.structuredContent as unknown as { total: number }).total, 1);
  // Identical keyed retry returns the original receipt, no duplicate.
  const replay = await s.call('create_meal', { title: 'First MCP brisket', smokerName: 'Offset', dishes: [{ name: 'Flat', foodType: 'beef' }], idempotencyKey: KEY });
  assert.equal(replay.isError, false);
  assert.equal((replay.structuredContent as unknown as { mealId: string }).mealId, content.mealId);
  assert.equal(s.snapshots.version, 1);
  // Same key = same operation: a same-key call with different content is
  // answered from the original receipt (flagged replayed), never applied.
  const changed = await s.call('create_meal', { title: 'Different', idempotencyKey: KEY });
  assert.equal(changed.isError, false);
  const changedContent = changed.structuredContent as unknown as { replayed: boolean; mealId: string };
  assert.equal(changedContent.replayed, true);
  assert.equal(changedContent.mealId, content.mealId);
  assert.equal(s.snapshots.version, 1);
  assert.equal((await s.call('list_meals')).structuredContent && (await s.call('list_meals')).isError, false);
});

test('update + transition honor expectedSnapshotVersion and the status machine', async () => {
  const s: Setup = await setup();
  const created = await s.call('create_meal', { title: 'Ribs' });
  const mealId = (created.structuredContent as unknown as { mealId: string }).mealId;
  const stale = await s.call('update_meal', { mealId, title: 'Ribs v2', expectedSnapshotVersion: 99 });
  assert.equal(stale.isError, true); assert.match(stale.content[0].text, /snapshot version 1, not 99/);
  const updated = await s.call('update_meal', { mealId, title: 'Ribs v2', expectedSnapshotVersion: 1 });
  assert.equal(updated.isError, false);
  assert.equal((updated.structuredContent as unknown as { receipt: { snapshotVersion: number } }).receipt.snapshotVersion, 2);
  const badJump = await s.call('transition_meal', { mealId, toStatus: 'active' });
  assert.equal(badJump.isError, true); // active → active is not a transition
  const paused = await s.call('transition_meal', { mealId, toStatus: 'paused' });
  assert.equal(paused.isError, false);
  const completed = await s.call('transition_meal', { mealId, toStatus: 'completed' });
  assert.equal(completed.isError, false);
  const meal = await s.call('get_meal', { mealId });
  const mealContent = meal.structuredContent as unknown as { meal: { status: string; endedAtUtcMillis: number } };
  assert.equal(mealContent.meal.status, 'completed');
  assert.equal(mealContent.meal.endedAtUtcMillis, NOW);
  const zombie = await s.call('transition_meal', { mealId, toStatus: 'active' });
  assert.equal(zombie.isError, true); assert.match(zombie.content[0].text, /cannot become active/);
});

test('dish add/update/remove cascade correctly; meal delete tombstones the whole tree', async () => {
  const s: Setup = await setup();
  const created = await s.call('create_meal', { title: 'Two-dish cook', dishes: [{ name: 'A', foodType: 'pork' }] });
  const mealId = (created.structuredContent as unknown as { mealId: string }).mealId;
  const added = await s.call('add_dish', { mealId, name: 'B', foodType: 'beef', weightValue: 4, weightUnit: 'lb' });
  assert.equal(added.isError, false);
  const dishB = (added.structuredContent as unknown as { dishId: string }).dishId;
  const updatedDish = await s.call('update_dish', { dishId: dishB, placement: 'top rack' });
  assert.equal(updatedDish.isError, false);
  const removed = await s.call('remove_dish', { dishId: dishB });
  assert.equal(removed.isError, false);
  const meal = await s.call('get_meal', { mealId });
  assert.equal((meal.structuredContent as unknown as { dishes: unknown[] }).dishes.length, 1);
  const deleted = await s.call('delete_meal', { mealId });
  assert.equal(deleted.isError, false);
  assert.ok((deleted.structuredContent as unknown as { tombstonedRecords: number }).tombstonedRecords >= 3);
  const after = await s.call('list_meals');
  assert.equal((after.structuredContent as unknown as { total: number }).total, 0);
  const gone = await s.call('get_meal', { mealId });
  assert.equal(gone.isError, true);
  // Every remaining record is a tombstone; the envelope stayed legal throughout.
  assert.ok(s.snapshots.envelope!.records.every(r => r.deleted));
});

test('a write against a never-synced library can create the first meal', async () => {
  const s: Setup = await setup();
  assert.equal(s.snapshots.version, 0);
  const created = await s.call('create_meal', { title: 'Genesis' });
  assert.equal(created.isError, false);
  assert.equal(s.snapshots.version, 1);
});
