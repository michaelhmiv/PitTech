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

class FakeJournal {
  entries = new Map<string, { mutationId: string; tool: string; before: never; after: never; afterVersion: number; undoneAt: number | null; undoneBy: string | null }>();
  async record(entry: { userId: string; mutationId: string; tool: string; before: never; after: never; afterVersion: number; createdAt: number }) {
    this.entries.set(entry.mutationId, { mutationId: entry.mutationId, tool: entry.tool, before: entry.before, after: entry.after, afterVersion: entry.afterVersion, undoneAt: null, undoneBy: null });
  }
  async find(_userId: string, mutationId: string) { return this.entries.get(mutationId); }
  async markUndone(_userId: string, mutationId: string, undoneBy: string, at: number) { const e = this.entries.get(mutationId); if (e) { e.undoneAt = at; e.undoneBy = undoneBy; } }
}

async function setup(writesEnabled = true) {
  const grants = new McpGrants(new MemoryGrantStore(), new AuthSecrets(credential(), credential()), { state: async () => 'active' }, () => NOW);
  const snapshots = new FakeSnapshots();
  const journal = new FakeJournal();
  const library = new PrivateLibraryService(grants, snapshots, { writer: snapshots, writesEnabled, clock: () => NOW, journal });
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
  return { grants, snapshots, library, server, principal, call, list, journal };
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

test('notes: create stamps source mcp, update edits, delete tombstones links too', async () => {
  const s: Setup = await setup();
  const created = await s.call('create_meal', { title: 'Note cook' });
  const mealId = (created.structuredContent as unknown as { mealId: string }).mealId;
  const note = await s.call('create_note', { mealId, title: 'Wrapped', details: 'Butcher paper', idempotencyKey: KEY });
  assert.equal(note.isError, false, note.content?.[0]?.text);
  const noteId = (note.structuredContent as unknown as { noteId: string }).noteId;
  const stored = s.snapshots.envelope!.records.find(r => r.recordType === 'events')!;
  assert.equal((stored.payload as { source: string }).source, 'mcp');
  const listed = await s.call('list_notes', { mealId });
  assert.deepEqual((listed.structuredContent as unknown as { notes: { id: string }[] }).notes.map(n => n.id), [noteId]);
  const updated = await s.call('update_note', { noteId, title: 'Wrapped at 165' });
  assert.equal(updated.isError, false);
  const fetched = await s.call('get_note', { noteId });
  assert.equal((fetched.structuredContent as unknown as { note: { title: string } }).note.title, 'Wrapped at 165');
  const wrongDish = await s.call('create_note', { mealId, title: 'X', dishId: 'no-such-dish' });
  assert.equal(wrongDish.isError, true);
  const deleted = await s.call('delete_note', { noteId });
  assert.equal(deleted.isError, false);
  assert.equal((await s.call('get_note', { noteId })).isError, true);
});

test('recipes: create writes a codec-shaped snapshot; update adds an immutable revision; archive + delete behave', async () => {
  const s: Setup = await setup();
  const created = await s.call('create_recipe', {
    name: 'Weekend ribs', tags: ['pork'],
    dish: { name: 'Spare ribs', foodType: 'pork', ingredients: [{ name: 'Yellow mustard', amountText: '2', amountUnit: 'tbsp' }] },
    instructions: [{ title: 'Season', details: 'Mustard binder' }, { title: 'Smoke', durationMinutes: 300 }],
    targets: [{ type: 'internal', value: 203, unit: '°F' }],
    idempotencyKey: KEY,
  });
  assert.equal(created.isError, false, created.content?.[0]?.text);
  const { recipeId, revisionId } = created.structuredContent as unknown as { recipeId: string; revisionId: string };
  const fetched = await s.call('get_recipe', { recipeId });
  const snap = (fetched.structuredContent as unknown as { currentRevision: { snapshot: Record<string, unknown> } }).currentRevision.snapshot;
  assert.equal(snap.version, 1);
  const draft = snap.draft as { dishes: { name: string; ingredients: { name: string; amount: string }[] }[] };
  assert.equal(draft.dishes[0].name, 'Spare ribs');
  assert.equal(draft.dishes[0].ingredients[0].amount, '2');
  const instructions = snap.instructions as { title: string; durationMillis: number | null }[];
  assert.equal(instructions[1].durationMillis, 300 * 60000);
  // Update: new revision, old one untouched, omitted content inherited.
  const updated = await s.call('update_recipe', { recipeId, instructions: [{ title: 'Season' }, { title: 'Smoke', durationMinutes: 300 }, { title: 'Rest', durationMinutes: 30 }], changeNotes: 'added rest' });
  assert.equal(updated.isError, false, updated.content?.[0]?.text);
  const upd = updated.structuredContent as unknown as { revisionId: string; revisionNumber: number };
  assert.equal(upd.revisionNumber, 2);
  assert.notEqual(upd.revisionId, revisionId);
  const revisions = await s.call('list_recipe_revisions', { recipeId });
  const revs = (revisions.structuredContent as unknown as { revisions: { id: string; isCurrent: boolean }[] }).revisions;
  assert.equal(revs.length, 2);
  assert.equal(revs.find(r => r.isCurrent)?.id, upd.revisionId);
  const after = await s.call('get_recipe', { recipeId });
  const snap2 = (after.structuredContent as unknown as { currentRevision: { snapshot: Record<string, unknown> } }).currentRevision.snapshot;
  assert.equal((snap2.draft as { dishes: unknown[] }).dishes.length, 1, 'dish inherited on partial update');
  assert.equal((snap2.instructions as unknown[]).length, 3);
  // Archive hides from default search; delete removes for real.
  const archived = await s.call('archive_recipe', { recipeId });
  assert.equal(archived.isError, false);
  const searched = await s.call('search_recipes', { query: 'Weekend' });
  assert.equal((searched.structuredContent as unknown as { total: number }).total, 0);
  const deleted = await s.call('delete_recipe', { recipeId });
  assert.equal(deleted.isError, false);
  assert.equal((await s.call('get_recipe', { recipeId })).isError, true);
});

test('registry surface: duplicate meal setup, save dish as recipe, duplicate recipe, favorite', async () => {
  const s: Setup = await setup();
  const created = await s.call('create_meal', { title: 'Sunday pork', dishes: [{ name: 'Butt', foodType: 'pork' }] });
  const mealId = (created.structuredContent as unknown as { mealId: string; dishIds: string[] }).mealId;
  const dishId = (created.structuredContent as unknown as { dishIds: string[] }).dishIds[0];
  const dup = await s.call('duplicate_meal_setup', { mealId });
  assert.equal(dup.isError, false, dup.content?.[0]?.text);
  const dupMealId = (dup.structuredContent as unknown as { mealId: string }).mealId;
  assert.notEqual(dupMealId, mealId);
  const dupMeal = await s.call('get_meal', { mealId: dupMealId });
  assert.equal((dupMeal.structuredContent as unknown as { dishes: unknown[] }).dishes.length, 1);
  const saved = await s.call('save_dish_as_recipe', { dishId });
  assert.equal(saved.isError, false, saved.content?.[0]?.text);
  const recipeId = (saved.structuredContent as unknown as { recipeId: string }).recipeId;
  const recipe = await s.call('get_recipe', { recipeId });
  assert.equal(recipe.isError, false);
  const snap = (recipe.structuredContent as unknown as { currentRevision: { snapshot: { draft: { dishes: { name: string }[] } } } }).currentRevision.snapshot;
  assert.equal(snap.draft.dishes[0].name, 'Butt');
  const fav = await s.call('set_recipe_favorite', { recipeId, favorite: true });
  assert.equal(fav.isError, false);
  const copy = await s.call('duplicate_recipe', { recipeId });
  assert.equal(copy.isError, false, copy.content?.[0]?.text);
  const copyId = (copy.structuredContent as unknown as { recipeId: string }).recipeId;
  assert.notEqual(copyId, recipeId);
  const copyRevs = await s.call('list_recipe_revisions', { recipeId: copyId });
  assert.equal((copyRevs.structuredContent as unknown as { revisions: unknown[] }).revisions.length, 1);
  const restored = await s.call('restore_recipe', { recipeId });
  assert.equal(restored.isError, false);
});

test('composition: compose_meal mixes recipe + plain dishes with source links; add_recipe_to_meal appends', async () => {
  const s: Setup = await setup();
  const recipe = await s.call('create_recipe', {
    name: 'House rub ribs',
    dish: { name: 'Spare ribs', foodType: 'pork', ingredients: [{ name: 'House rub', amountText: '3', amountUnit: 'tbsp' }] },
    targets: [{ type: 'internal', value: 203, unit: '°F' }],
  });
  const recipeId = (recipe.structuredContent as unknown as { recipeId: string }).recipeId;
  const composed = await s.call('compose_meal', { title: 'Saturday', dishes: [{ recipeId }, { name: 'Beans', foodType: 'side' }] });
  assert.equal(composed.isError, false, composed.content?.[0]?.text);
  const mealId = (composed.structuredContent as unknown as { mealId: string }).mealId;
  const meal = await s.call('get_meal', { mealId });
  const dishes = (meal.structuredContent as unknown as { dishes: { name: string; ingredients: { name: string }[]; targets: unknown[] }[] }).dishes;
  assert.equal(dishes.length, 2);
  const ribDish = dishes.find(d => d.name === 'Spare ribs')!;
  assert.equal(ribDish.ingredients[0].name, 'House rub');
  assert.equal(ribDish.targets.length, 1);
  const stored = s.snapshots.envelope!.records;
  const link = stored.find(r => r.recordType === 'dishRecipeSources');
  assert.ok(link, 'source link written');
  assert.equal((link!.payload as { recipeId: string }).recipeId, recipeId);
  const added = await s.call('add_recipe_to_meal', { mealId, recipeId });
  assert.equal(added.isError, false, added.content?.[0]?.text);
  const after = await s.call('get_meal', { mealId });
  assert.equal((after.structuredContent as unknown as { dishes: unknown[] }).dishes.length, 3);
});

test('companions + reminder rules: upsert/list/delete with per-kind scope enforcement', async () => {
  const s: Setup = await setup();
  const meal = await s.call('create_meal', { title: 'Planned cook' });
  const mealId = (meal.structuredContent as unknown as { mealId: string }).mealId;
  const plan = await s.call('upsert_companion', { kind: 'plan', title: 'Saturday plan', mealId, payload: { draft: { dishes: [] }, dishIds: [] } });
  // setup() principal lacks planning:write → the per-kind gate must refuse.
  assert.equal(plan.isError, true);
  assert.match(plan.content[0].text, /planning:write/);
  const planner: McpPrincipal = { grantId: 'g', userId: 'user-1', scopes: ['library:read', 'library:write', 'planning:write'] };
  const plan2 = await s.call('upsert_companion', { kind: 'plan', title: 'Saturday plan', mealId, payload: { draft: { dishes: [] }, dishIds: [] } }, planner);
  assert.equal(plan2.isError, false, plan2.content?.[0]?.text);
  const companionId = (plan2.structuredContent as unknown as { companionId: string }).companionId;
  const checklist = await s.call('upsert_companion', { kind: 'checklist', title: 'Prep', mealId, payload: { items: [] } });
  assert.equal(checklist.isError, false, checklist.content?.[0]?.text);
  const listed = await s.call('list_companions', { mealId });
  assert.equal((listed.structuredContent as unknown as { total: number }).total, 2);
  const badKind = await s.call('upsert_companion', { kind: 'recordings', title: 'X', payload: {} }, planner);
  assert.equal(badKind.isError, true);
  const rule = await s.call('upsert_reminder_rule', { mealId, kind: 'temperature', title: 'Probe alert', triggerConfig: { type: 'temperature', threshold: 203 } }, planner);
  assert.equal(rule.isError, false, rule.content?.[0]?.text);
  const mismatch = await s.call('upsert_reminder_rule', { mealId, kind: 'temperature', title: 'X', triggerConfig: { type: 'timer' } }, planner);
  assert.equal(mismatch.isError, true);
  const rules = await s.call('list_reminder_rules', { mealId });
  assert.equal((rules.structuredContent as unknown as { total: number }).total, 1);
  const del = await s.call('delete_companion', { companionId }, planner);
  assert.equal(del.isError, false);
});

test('undo: reverses a create exactly, refuses after drift, and the undo itself can be undone (redo)', async () => {
  const s: Setup = await setup();
  const created = await s.call('create_meal', { title: 'Undo me' });
  const mealId = (created.structuredContent as unknown as { mealId: string }).mealId;
  const createMutation = [...s.journal.entries.keys()][0];
  // Drift: a later edit builds on the create → undo must refuse.
  const driftEdit = await s.call('update_meal', { mealId, smokerName: 'Changed' });
  assert.equal(driftEdit.isError, false, driftEdit.content?.[0]?.text);
  const refused = await s.call('undo_private_operation', { mutationId: createMutation });
  assert.equal(refused.isError, true);
  assert.match(refused.content[0].text, /no longer safe/);
  // Undo the update first (its own journal entry), then the create is safe.
  const mutations = [...s.journal.entries.keys()];
  const updateMutation = mutations[mutations.length - 1];
  const undoUpdate = await s.call('undo_private_operation', { mutationId: updateMutation });
  assert.equal(undoUpdate.isError, false, undoUpdate.content?.[0]?.text);
  const undoCreate = await s.call('undo_private_operation', { mutationId: createMutation });
  assert.equal(undoCreate.isError, false, undoCreate.content?.[0]?.text);
  const gone = await s.call('get_meal', { mealId });
  assert.equal(gone.isError, true);
  // Redo: undo the undo-of-create using ITS receipt mutation id.
  const undoCreateReceipt = (undoCreate.structuredContent as unknown as { receipt: { mutationId: string } }).receipt.mutationId;
  const redo = await s.call('undo_private_operation', { mutationId: undoCreateReceipt });
  assert.equal(redo.isError, false, redo.content?.[0]?.text);
  // The meal returns as a copy under a fresh identity (sync never
  // resurrects a tombstoned id); the result reports the mapping.
  const copies = (redo.structuredContent as unknown as { restoredAsCopies: { from: string; to: string }[] }).restoredAsCopies;
  const newMealId = copies.find(c => c.from === mealId)!.to;
  assert.notEqual(newMealId, mealId);
  const stillGone = await s.call('get_meal', { mealId });
  assert.equal(stillGone.isError, true);
  const back = await s.call('get_meal', { mealId: newMealId });
  assert.equal(back.isError, false);
  assert.equal((back.structuredContent as unknown as { meal: { title: string } }).meal.title, 'Undo me');
});
