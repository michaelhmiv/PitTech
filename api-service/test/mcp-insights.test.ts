import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AuthSecrets, credential } from '../src/auth-credentials.ts';
import { McpGrants, type McpGrantRecord, type McpGrantStore, type McpPrincipal } from '../src/mcp-grants.ts';
import { PrivateLibraryService, type SnapshotReader } from '../src/mcp-library.ts';
import { McpServer } from '../src/mcp-server.ts';
import type { SyncEnvelope } from '../src/sync-envelope.ts';
import type { SyncRecord } from '../src/sync-merge.ts';

const NOW = 1_800_000_000_000; const T0 = 1_799_000_000_000;
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
const rec = (recordType: string, payload: Record<string, unknown>, key = 'id'): SyncRecord => ({
  recordType: recordType as SyncRecord['recordType'], recordId: String(payload[key]), deleted: false,
  payload, assetIds: [], version: { counter: 1, clientId: 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa' },
});
function library(withBenchmarks = false) {
  const records: SyncRecord[] = [
    rec('cooks', { id: 'cook-1', status: 'completed', title: 'Brisket day', smokerName: 'Offset', startedAtUtcMillis: T0, endedAtUtcMillis: T0 + 8 * 3600000, createdAtUtcMillis: T0, updatedAtUtcMillis: T0 }),
    rec('dishes', { id: 'dish-1', cookId: 'cook-1', name: 'Packer', foodType: 'beef', createdAtUtcMillis: T0, updatedAtUtcMillis: T0 }),
    rec('readings', { id: 'r1', cookId: 'cook-1', dishId: 'dish-1', probeName: 'Probe 1', measurementType: 'internal', value: 150, unit: '°F', measuredAtUtcMillis: T0 + 600000, recordedAtUtcMillis: T0 + 600000 }),
    rec('readings', { id: 'r2', cookId: 'cook-1', dishId: 'dish-1', probeName: 'Probe 1', measurementType: 'internal', value: 170, unit: '°F', measuredAtUtcMillis: T0 + 1200000, recordedAtUtcMillis: T0 + 1200000 }),
    rec('readings', { id: 'r3', cookId: 'cook-1', dishId: 'dish-1', probeName: 'Probe 1', measurementType: 'internal', value: 203, unit: '°F', measuredAtUtcMillis: T0 + 7200000, recordedAtUtcMillis: T0 + 7200000 }),
    rec('results', { id: 'res-1', cookId: 'cook-1', dishId: 'dish-1', resultType: 'rating', numericValue: 9, textValue: null, unit: null, notes: 'best yet', recordedAtUtcMillis: T0 + 8 * 3600000 }),
    rec('events', { id: 'ev-1', cookId: 'cook-1', dishId: null, eventType: 'note', title: 'Wrapped', occurredAtUtcMillis: T0, recordedAtUtcMillis: T0, timeZoneId: 'UTC', source: 'manual', createdAtUtcMillis: T0, updatedAtUtcMillis: T0 }),
  ];
  const envelope: SyncEnvelope = { contractVersion: 1, baseVersion: 0, mutationId: '123e4567-e89b-42d3-a456-426614174099', clientId: 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa', records, conflicts: [] };
  const reader: SnapshotReader = {
    read: async () => ({ version: 4, envelope: structuredClone(envelope) }),
    status: async () => ({ version: 4, updatedAt: NOW }),
  };
  const grants = new McpGrants(new MemoryGrantStore(), new AuthSecrets(credential(), credential()), { state: async () => 'active' }, () => NOW);
  const service = new PrivateLibraryService(grants, reader, withBenchmarks
    ? { benchmarks: { latestFigures: async () => ({ run: { runId: 'run-1' }, figures: [{ bucket: 'beef', metricId: 'duration', n: 42 }] }) } }
    : {});
  return { server: new McpServer(service), service };
}
const principal: McpPrincipal = { grantId: 'g', userId: 'user-1', scopes: ['library:read', 'community:read'] };
async function call(server: McpServer, name: string, args: Record<string, unknown> = {}) {
  const response = await server.handle(principal, { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name, arguments: args } });
  return (response as { result: { isError: boolean; structuredContent?: Record<string, any>; content: { text: string }[] } }).result;
}

test('insights: reduced history buckets, summary peaks + duration, insights totals, recap text', async () => {
  const { server } = library();
  const history = await call(server, 'get_temperature_history', { mealId: 'cook-1' });
  assert.equal(history.isError, false, history.content?.[0]?.text);
  const intervals = history.structuredContent!.intervals as { samples: number; max: number }[];
  assert.equal(intervals.length, 3);
  assert.equal(Math.max(...intervals.map(i => i.max)), 203);
  const summary = await call(server, 'get_cook_summary', { mealId: 'cook-1' });
  assert.equal(summary.structuredContent!.meal.durationMinutes, 480);
  assert.equal(summary.structuredContent!.dishes[0].peakByType.internal, 203);
  assert.equal(summary.structuredContent!.noteCount, 1);
  const insights = await call(server, 'get_personal_insights');
  assert.equal(insights.structuredContent!.totals.meals, 1);
  assert.equal(insights.structuredContent!.averageCompletedCookHours, 8);
  assert.equal(insights.structuredContent!.topRated[0].value, 9);
  const recap = await call(server, 'build_private_recap', { mealId: 'cook-1' });
  assert.match(recap.structuredContent!.recap as string, /Brisket day/);
  assert.match(recap.structuredContent!.recap as string, /internal peaked at 203/);
  const compare = await call(server, 'compare_cooks', { mealIds: ['cook-1', 'cook-1'] });
  assert.equal((compare.structuredContent!.cooks as unknown[]).length, 2);
});

test('benchmarks tool exists only when a source is wired, and serves figures', async () => {
  const without = library(false);
  const listWithout = await without.server.handle(principal, { jsonrpc: '2.0', id: 1, method: 'tools/list' });
  assert.ok(!(listWithout as { result: { tools: { name: string }[] } }).result.tools.some(t => t.name === 'get_community_benchmarks'));
  const { server } = library(true);
  const result = await call(server, 'get_community_benchmarks', { bucket: 'beef' });
  assert.equal(result.isError, false);
  assert.equal((result.structuredContent!.figures as unknown[]).length, 1);
});

test('community: search/get pass through; save pins a private copy with credit, no public side effects', async () => {
  const grants = new McpGrants(new MemoryGrantStore(), new AuthSecrets(credential(), credential()), { state: async () => 'active' }, () => NOW);
  const writer = {
    version: 0, envelope: null as SyncEnvelope | null,
    async read() { return { version: this.version, envelope: this.envelope ? structuredClone(this.envelope) : null }; },
    async status() { return { version: this.version, updatedAt: null }; },
    async receiptFor() { return undefined; },
    async put(_user: string, value: unknown) {
      const next = value as SyncEnvelope; this.envelope = next; this.version = next.baseVersion + 1;
      return { version: this.version, mutationId: next.mutationId, sha256: 'x' };
    },
  };
  let discoverInput: { category?: string } | undefined;
  const community = {
    discover: async (input: { category?: string }) => { discoverInput = input; return { items: [{ id: 'item-1' }], nextCursor: null }; },
    get: async () => ({
      revisionId: 'rev-public-1', credit: { displayName: 'Smokey Joe' },
      payload: { recipes: [{ version: 1, name: 'Public ribs', description: '', tags: [], draft: { title: 'Public ribs', smoker: '', setpoint: '', unit: '°F', notes: '', fuel: '', wood: '', dishes: [{ name: 'Ribs', foodType: 'pork', cut: '', weight: '', weightUnit: 'lb', startingCondition: null, boneIn: null, placement: '', grade: '', thickness: '', prep: '', ingredients: [] }] }, instructions: [], observations: [], targets: [], resultNotes: '', rating: null, smokingAnchorUtcMillis: null, preferredPitTemperatureF: null, servings: '', cookAgain: null, publicSource: null }] },
    }),
  };
  const service = new PrivateLibraryService(grants, writer, { writer: writer as never, writesEnabled: true, clock: () => NOW, community });
  const server = new McpServer(service);
  const writerPrincipal: McpPrincipal = { grantId: 'g', userId: 'user-1', scopes: ['library:read', 'library:write', 'community:read'] };
  const callAs = async (name: string, args: Record<string, unknown> = {}) => {
    const response = await server.handle(writerPrincipal, { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name, arguments: args } });
    return (response as { result: { isError: boolean; structuredContent?: Record<string, any>; content: { text: string }[] } }).result;
  };
  const found = await callAs('search_community_recipes', { query: 'ribs' });
  assert.equal(found.isError, false);
  const foundByCategory = await callAs('search_community_recipes', { query: 'ribs', category: 'beef' });
  assert.equal(foundByCategory.isError, false);
  assert.equal(discoverInput?.category, 'beef', 'the category filter must reach the community source');
  const saved = await callAs('save_community_recipe', { itemId: 'item-1' });
  assert.equal(saved.isError, false, saved.content?.[0]?.text);
  const revision = writer.envelope!.records.find(r => r.recordType === 'recipeRevisions')!;
  const snapshot = JSON.parse((revision.payload as { snapshotJson: string }).snapshotJson) as { publicSource: { itemId: string; revisionId: string; displayName: string } };
  assert.deepEqual(snapshot.publicSource, { itemId: 'item-1', revisionId: 'rev-public-1', displayName: 'Smokey Joe', removed: false, relativeTiming: true });
});
