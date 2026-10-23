import { test } from 'node:test';
import assert from 'node:assert/strict';
import { McpGrants, type McpGrantRecord, type McpGrantStore, type McpPrincipal } from '../src/mcp-grants.ts';
import { AuthSecrets, credential } from '../src/auth-credentials.ts';
import { PrivateLibraryService, type SnapshotReader } from '../src/mcp-library.ts';
import { McpServer } from '../src/mcp-server.ts';
import type { SyncEnvelope } from '../src/sync-envelope.ts';

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

const rec = (recordType: string, payload: Record<string, unknown>, deleted = false) =>
  ({ recordType, recordId: String(payload.id ?? payload.cookId ?? payload.readingId), deleted, payload, assetIds: [], version: { counter: 1, clientId: 'c' } });
const T0 = 1_799_000_000_000;
function libraryEnvelope() {
  return {
    contractVersion: 1, baseVersion: 7, mutationId: 'm', clientId: 'phone',
    records: [
      rec('cooks', { id: 'cook-1', title: 'Saturday brisket', status: 'completed', startedAtUtcMillis: T0, endedAtUtcMillis: T0 + 3600000, startedTimeZoneId: 'America/New_York', smokerName: 'Offset', createdAtUtcMillis: T0, updatedAtUtcMillis: T0 }),
      rec('cooks', { id: 'cook-2', title: 'Weeknight ribs', status: 'active', startedAtUtcMillis: T0 + 86400000, endedAtUtcMillis: null, startedTimeZoneId: 'America/New_York', smokerName: 'Pellet', createdAtUtcMillis: T0, updatedAtUtcMillis: T0 }),
      rec('cooks', { id: 'cook-gone', title: 'Deleted cook', status: 'completed', startedAtUtcMillis: T0, startedTimeZoneId: 'UTC', createdAtUtcMillis: T0, updatedAtUtcMillis: T0 }, true),
      rec('dishes', { id: 'dish-1', cookId: 'cook-1', name: 'Brisket flat', foodType: 'beef', createdAtUtcMillis: T0, updatedAtUtcMillis: T0 }),
      rec('ingredients', { id: 'ing-1', cookId: 'cook-1', dishId: 'dish-1', stage: 'prep', name: 'SPG rub', createdAtUtcMillis: T0 }),
      rec('targets', { id: 'tgt-1', cookId: 'cook-1', dishId: 'dish-1', targetType: 'internal', value: 203, unit: 'F', scope: 'dish', createdAtUtcMillis: T0 }),
      rec('targets', { id: 'tgt-2', cookId: 'cook-1', dishId: null, targetType: 'pit', value: 250, unit: 'F', scope: 'cook', createdAtUtcMillis: T0 }),
      rec('events', { id: 'note-1', cookId: 'cook-1', dishId: 'dish-1', eventType: 'note', title: 'Wrapped in butcher paper', occurredAtUtcMillis: T0 + 1000, recordedAtUtcMillis: T0 + 1000, timeZoneId: 'America/New_York', source: 'manual', createdAtUtcMillis: T0, updatedAtUtcMillis: T0 }),
      rec('events', { id: 'note-2', cookId: 'cook-1', dishId: null, eventType: 'milestone', title: 'Bark set', occurredAtUtcMillis: T0 + 2000, recordedAtUtcMillis: T0 + 2000, timeZoneId: 'America/New_York', source: 'manual', createdAtUtcMillis: T0, updatedAtUtcMillis: T0 }),
      rec('recipes', { id: 'rec-1', name: 'Competition Brisket', description: 'My best brisket', tagsJson: '["beef","competition"]', currentRevisionId: 'rev-2', createdAtUtcMillis: T0, updatedAtUtcMillis: T0 + 5000, archivedAtUtcMillis: null, favorite: true }),
      rec('recipes', { id: 'rec-2', name: 'Old Rib Rub', description: 'retired', tagsJson: '[]', currentRevisionId: 'rev-3', createdAtUtcMillis: T0, updatedAtUtcMillis: T0 + 1000, archivedAtUtcMillis: T0 + 9000, favorite: false }),
      rec('recipeRevisions', { id: 'rev-1', recipeId: 'rec-1', parentRevisionId: null, revisionNumber: 1, snapshotSchemaVersion: 1, snapshotJson: '{"steps":["season"]}', createdAtUtcMillis: T0, changeNotes: 'first' }),
      rec('recipeRevisions', { id: 'rev-2', recipeId: 'rec-1', parentRevisionId: 'rev-1', revisionNumber: 2, snapshotSchemaVersion: 1, snapshotJson: '{"steps":["season","wrap at 165"]}', createdAtUtcMillis: T0 + 5000, changeNotes: 'added wrap' }),
      rec('recipeRevisions', { id: 'rev-3', recipeId: 'rec-2', parentRevisionId: null, revisionNumber: 1, snapshotSchemaVersion: 1, snapshotJson: '{}', createdAtUtcMillis: T0, changeNotes: 'first' }),
    ],
    conflicts: [],
  } as unknown as SyncEnvelope;
}

async function setup(synced = true) {
  const grants = new McpGrants(new MemoryGrantStore(), new AuthSecrets(credential(), credential()), { state: async () => 'active' }, () => NOW);
  const snapshots: SnapshotReader = {
    status: async () => synced ? { version: 7, updatedAt: NOW - 1000 } : { version: 0, updatedAt: null },
    read: async () => ({ version: synced ? 7 : 0, envelope: synced ? libraryEnvelope() : null }),
  };
  const server = new McpServer(new PrivateLibraryService(grants, snapshots));
  const principal: McpPrincipal = { grantId: 'g', userId: 'user-1', scopes: ['library:read'] };
  const call = async (name: string, args: Record<string, unknown> = {}) => {
    const response = await server.handle(principal, { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name, arguments: args } });
    const result = (response as { result: { isError: boolean; structuredContent?: Record<string, unknown>; content: { text: string }[] } }).result;
    return result;
  };
  return { server, principal, call };
}

test('list_meals: newest first, deleted excluded, dish counts, status filter, pagination', async () => {
  const s = await setup();
  const all = await s.call('list_meals');
  assert.equal(all.isError, false);
  const content = all.structuredContent as unknown as { meals: { id: string; dishCount: number }[]; total: number; snapshotVersion: number };
  assert.deepEqual(content.meals.map(m => m.id), ['cook-2', 'cook-1']);
  assert.equal(content.meals[1].dishCount, 1);
  assert.equal(content.total, 2);
  assert.equal(content.snapshotVersion, 7);
  const cooking = await s.call('list_meals', { status: 'active' });
  assert.deepEqual((cooking.structuredContent as unknown as { meals: { id: string }[] }).meals.map(m => m.id), ['cook-2']);
  const first = await s.call('list_meals', { limit: 1 });
  const firstContent = first.structuredContent as unknown as { nextCursor: string | null };
  assert.ok(firstContent.nextCursor);
  const second = await s.call('list_meals', { limit: 1, cursor: firstContent.nextCursor });
  assert.deepEqual((second.structuredContent as unknown as { meals: { id: string }[] }).meals.map(m => m.id), ['cook-1']);
  const badLimit = await s.call('list_meals', { limit: 500 });
  assert.equal(badLimit.isError, true);
});

test('get_meal composes dishes with ingredients and splits cook-level targets', async () => {
  const s = await setup();
  const result = await s.call('get_meal', { mealId: 'cook-1' });
  const content = result.structuredContent as unknown as {
    dishes: { id: string; ingredients: unknown[]; targets: { id: string }[] }[];
    targets: { id: string }[]; noteCount: number;
  };
  assert.equal(content.dishes.length, 1);
  assert.equal(content.dishes[0].ingredients.length, 1);
  assert.deepEqual(content.dishes[0].targets.map(t => t.id), ['tgt-1']);
  assert.deepEqual(content.targets.map(t => t.id), ['tgt-2']);
  assert.equal(content.noteCount, 2);
  const missing = await s.call('get_meal', { mealId: 'nope' });
  assert.equal(missing.isError, true);
  assert.match(missing.content[0].text, /No meal with id nope/);
});

test('notes: list is newest first and get returns the full event', async () => {
  const s = await setup();
  const list = await s.call('list_notes', { mealId: 'cook-1' });
  assert.deepEqual((list.structuredContent as unknown as { notes: { id: string }[] }).notes.map(n => n.id), ['note-2', 'note-1']);
  const one = await s.call('get_note', { noteId: 'note-1' });
  assert.equal((one.structuredContent as unknown as { note: { title: string } }).note.title, 'Wrapped in butcher paper');
});

test('recipes: search excludes archived by default, matches name/tags; get parses the current revision snapshot', async () => {
  const s = await setup();
  const all = await s.call('search_recipes', {});
  assert.deepEqual((all.structuredContent as unknown as { recipes: { id: string }[] }).recipes.map(r => r.id), ['rec-1']);
  const withArchived = await s.call('search_recipes', { includeArchived: true });
  assert.equal((withArchived.structuredContent as unknown as { total: number }).total, 2);
  const byTag = await s.call('search_recipes', { query: 'COMPETITION' });
  assert.equal((byTag.structuredContent as unknown as { total: number }).total, 1);
  const recipe = await s.call('get_recipe', { recipeId: 'rec-1' });
  const content = recipe.structuredContent as unknown as { currentRevision: { revisionNumber: number; snapshot: { steps: string[] } } };
  assert.equal(content.currentRevision.revisionNumber, 2);
  assert.deepEqual(content.currentRevision.snapshot.steps, ['season', 'wrap at 165']);
  const revisions = await s.call('list_recipe_revisions', { recipeId: 'rec-1' });
  const revContent = revisions.structuredContent as unknown as { revisions: { id: string; isCurrent: boolean }[] };
  assert.deepEqual(revContent.revisions.map(r => [r.id, r.isCurrent]), [['rev-2', true], ['rev-1', false]]);
});

test('reads on a never-synced account fail with the fix, not an empty library', async () => {
  const s = await setup(false);
  const result = await s.call('list_meals');
  assert.equal(result.isError, true);
  assert.match(result.content[0].text, /No synced library yet/);
});

test('tools/list now carries the full read catalog', async () => {
  const s = await setup();
  const response = await s.server.handle(s.principal, { jsonrpc: '2.0', id: 1, method: 'tools/list' });
  const names = (response as { result: { tools: { name: string }[] } }).result.tools.map(t => t.name);
  assert.deepEqual(names, ['get_connection_status', 'get_capabilities', 'list_meals', 'get_meal', 'list_notes', 'get_note', 'list_companions', 'get_companion', 'list_reminder_rules', 'list_sharing_drafts', 'get_sharing_draft', 'search_recipes', 'get_recipe', 'list_recipe_revisions', 'get_temperature_history', 'get_cook_summary', 'compare_cooks', 'get_personal_insights', 'build_private_recap', 'get_sync_status']);
});
