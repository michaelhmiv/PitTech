import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { makeServer } from '../src/http.ts';
import { Relay } from '../src/relay.ts';
import { AuthSecrets, credential } from '../src/auth-credentials.ts';
import { McpGrants, type McpGrantRecord, type McpGrantStore } from '../src/mcp-grants.ts';
import { PrivateLibraryService, type SnapshotReader } from '../src/mcp-library.ts';
import { McpServer } from '../src/mcp-server.ts';
import { MemoryRepository, budgets } from './helpers.ts';
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

const envelope = (records: unknown[]) => ({ contractVersion: 1, baseVersion: 3, mutationId: 'm', clientId: 'c', records, conflicts: [] }) as unknown as SyncEnvelope;
const rec = (recordType: string, deleted = false) => ({ recordType, recordId: randomUUID(), deleted, payload: {}, assetIds: [], version: { counter: 1, clientId: 'c' } });

async function setup(t: { after: (fn: () => void) => void }, opts: { synced?: boolean; pro?: boolean } = {}) {
  const store = new MemoryGrantStore();
  let pro = opts.pro !== false;
  const grants = new McpGrants(store, new AuthSecrets(credential(), credential()), { state: async () => pro ? 'active' : undefined }, () => NOW);
  const synced = opts.synced !== false;
  const snapshots: SnapshotReader = {
    status: async () => synced ? { version: 3, updatedAt: NOW - 60000 } : { version: 0, updatedAt: null },
    read: async () => ({ version: synced ? 3 : 0, envelope: synced ? envelope([rec('meal'), rec('meal'), rec('recipe'), rec('meal', true)]) : null }),
  };
  const server = new McpServer(new PrivateLibraryService(grants, snapshots));
  const storage = { put: async () => {}, get: async () => Buffer.alloc(0), delete: async () => {} };
  const http = makeServer(new Relay(new MemoryRepository(), storage, budgets, () => NOW),
    { ipKey: 'x'.repeat(32), uploadsEnabled: false, mcp: grants, mcpServer: server, oauthIssuer: 'https://api-staging.pittech.app' });
  http.listen(0, '127.0.0.1'); await once(http, 'listening');
  t.after(() => { http.closeAllConnections(); http.close(); });
  const base = `http://127.0.0.1:${(http.address() as { port: number }).port}`;
  const { token } = await grants.create('user-1', 'ChatGPT', ['library:read', 'media:read'], 90 * 86400000);
  const rpc = async (message: unknown, authToken: string | null = token) => {
    const res = await fetch(base + '/mcp', { method: 'POST', headers: { 'Content-Type': 'application/json', ...(authToken ? { Authorization: 'Bearer ' + authToken } : {}) }, body: JSON.stringify(message) });
    return { status: res.status, headers: res.headers, body: res.status === 202 ? null : await res.json() as Record<string, unknown> };
  };
  return { grants, base, token, rpc, setPro: (v: boolean) => { pro = v; } };
}
type Setup = Awaited<ReturnType<typeof setup>>;

test('transport: unauthenticated and lapsed-Pro calls are the same 401 with discovery pointer', async t => {
  const s: Setup = await setup(t);
  const noAuth = await s.rpc({ jsonrpc: '2.0', id: 1, method: 'ping' }, null);
  assert.equal(noAuth.status, 401);
  assert.match(noAuth.headers.get('www-authenticate') ?? '', /oauth-protected-resource/);
  const garbage = await s.rpc({ jsonrpc: '2.0', id: 1, method: 'ping' }, 'ptm_' + 'x'.repeat(43));
  assert.equal(garbage.status, 401);
  s.setPro(false);
  const lapsed = await s.rpc({ jsonrpc: '2.0', id: 1, method: 'ping' });
  assert.equal(lapsed.status, 401, 'entitlement is re-checked on every MCP call');
  const meta = await fetch(s.base + '/.well-known/oauth-protected-resource');
  assert.equal(meta.status, 200);
  const metaBody = await meta.json() as { resource: string; authorization_servers: string[] };
  assert.equal(metaBody.resource, 'https://api-staging.pittech.app/mcp');
  assert.deepEqual(metaBody.authorization_servers, ['https://api-staging.pittech.app']);
  const get = await fetch(s.base + '/mcp', { headers: { Authorization: 'Bearer ' + s.token } });
  assert.equal(get.status, 405); assert.equal(get.headers.get('allow'), 'POST');
});

test('protocol: initialize negotiates, ping answers, unknown methods and batches fail properly', async t => {
  const s: Setup = await setup(t);
  const init = await s.rpc({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'test', version: '0' } } });
  assert.equal(init.status, 200);
  const initResult = (init.body as { result: { protocolVersion: string; serverInfo: { name: string } } }).result;
  assert.equal(initResult.protocolVersion, '2025-03-26');
  assert.equal(initResult.serverInfo.name, 'pittech');
  const initNew = await s.rpc({ jsonrpc: '2.0', id: 2, method: 'initialize', params: { protocolVersion: '2999-01-01' } });
  assert.equal((initNew.body as { result: { protocolVersion: string } }).result.protocolVersion, '2025-06-18');
  const ping = await s.rpc({ jsonrpc: '2.0', id: 3, method: 'ping' });
  assert.deepEqual((ping.body as { result: unknown }).result, {});
  const unknown = await s.rpc({ jsonrpc: '2.0', id: 4, method: 'resources/list' });
  assert.equal((unknown.body as { error: { code: number } }).error.code, -32601);
  const batch = await s.rpc([{ jsonrpc: '2.0', id: 1, method: 'ping' }]);
  assert.equal((batch.body as { error: { code: number } }).error.code, -32600);
  const notified = await s.rpc({ jsonrpc: '2.0', method: 'notifications/initialized' });
  assert.equal(notified.status, 202);
});

test('tools: list shows the M2 catalog; status tools answer from the snapshot', async t => {
  const s: Setup = await setup(t);
  const list = await s.rpc({ jsonrpc: '2.0', id: 1, method: 'tools/list' });
  const tools = (list.body as { result: { tools: { name: string }[] } }).result.tools;
  assert.deepEqual(tools.map(x => x.name), ['get_connection_status', 'get_capabilities', 'list_meals', 'get_meal', 'list_notes', 'get_note', 'list_companions', 'get_companion', 'list_reminder_rules', 'list_sharing_drafts', 'get_sharing_draft', 'list_photos', 'get_photo', 'search_recipes', 'get_recipe', 'list_recipe_revisions', 'get_temperature_history', 'get_cook_summary', 'compare_cooks', 'get_personal_insights', 'build_private_recap', 'get_sync_status']);
  const call = async (name: string) => s.rpc({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name, arguments: {} } });
  const conn = await call('get_connection_status');
  const connResult = (conn.body as { result: { structuredContent: { connection: { nickname: string; via: string } } } }).result;
  assert.equal(connResult.structuredContent.connection.nickname, 'ChatGPT');
  assert.equal(connResult.structuredContent.connection.via, 'personal_access_token');
  const sync = await call('get_sync_status');
  const syncResult = (sync.body as { result: { structuredContent: { hasSyncedLibrary: boolean; snapshotVersion: number; recordCounts: Record<string, number>; totalRecords: number } } }).result;
  assert.equal(syncResult.structuredContent.hasSyncedLibrary, true);
  assert.equal(syncResult.structuredContent.snapshotVersion, 3);
  assert.deepEqual(syncResult.structuredContent.recordCounts, { meal: 2, recipe: 1 });
  assert.equal(syncResult.structuredContent.totalRecords, 3);
  const caps = await call('get_capabilities');
  const capsResult = (caps.body as { result: { structuredContent: { limits: string[] } } }).result;
  assert.ok(capsResult.structuredContent.limits.some(line => line.includes('set a grill')));
  const missing = await call('delete_everything');
  assert.equal((missing.body as { result: { isError: boolean } }).result.isError, true);
});

test('tools: an account that never synced reads as no library, not an empty one', async t => {
  const s: Setup = await setup(t, { synced: false });
  const sync = await s.rpc({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'get_sync_status', arguments: {} } });
  const content = (sync.body as { result: { structuredContent: { hasSyncedLibrary: boolean; note: string } } }).result.structuredContent;
  assert.equal(content.hasSyncedLibrary, false);
  assert.match(content.note, /Nothing has synced/);
});

test('tools: a media-only grant sees exactly the photo reads; library tools stay denied', async t => {
  const s: Setup = await setup(t);
  const { token: mediaOnly } = await s.grants.create('user-2', 'Photos only', ['media:read'], 86400000);
  const list = await s.rpc({ jsonrpc: '2.0', id: 1, method: 'tools/list' }, mediaOnly);
  assert.deepEqual((list.body as { result: { tools: { name: string }[] } }).result.tools.map(x => x.name), ['list_photos', 'get_photo']);
  const call = await s.rpc({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'get_sync_status', arguments: {} } }, mediaOnly);
  const result = (call.body as { result: { isError: boolean; content: { text: string }[] } }).result;
  assert.equal(result.isError, true);
  assert.match(result.content[0].text, /does not include the access/);
});
