import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { makeServer } from '../src/http.ts';
import { ApiError } from '../src/core.ts';
import { Relay } from '../src/relay.ts';
import type { Authentication } from '../src/auth.ts';
import { AuthSecrets, credential } from '../src/auth-credentials.ts';
import { McpGrants, type McpGrantRecord, type McpGrantStore } from '../src/mcp-grants.ts';
import { MemoryRepository, budgets } from './helpers.ts';

const NOW = 1_800_000_000_000;

class MemoryGrantStore implements McpGrantStore {
  rows = new Map<string, McpGrantRecord>();
  async insert(g: McpGrantRecord) { this.rows.set(g.id, structuredClone(g)); }
  async byId(id: string) { const g = this.rows.get(id); return g ? structuredClone(g) : undefined; }
  async byTokenHash(h: string) { for (const g of this.rows.values()) if (g.tokenHash === h) return structuredClone(g); }
  async listForUser(userId: string) { return [...this.rows.values()].filter(g => g.userId === userId).map(g => structuredClone(g)); }
  async revoke(id: string, userId: string, at: number) { const g = this.rows.get(id); if (!g || g.userId !== userId || g.revokedAt !== null) return false; g.revokedAt = at; return true; }
  async revokeAll(userId: string, at: number) { let n = 0; for (const g of this.rows.values()) if (g.userId === userId && g.revokedAt === null) { g.revokedAt = at; n++; } return n; }
  async touchLastUsed(id: string, at: number) { const g = this.rows.get(id); if (g) g.lastUsedAt = at; }
  async rotateTokenHash(id: string, expectedHash: string, newHash: string) { const g = this.rows.get(id); if (!g || g.tokenHash !== expectedHash || g.revokedAt !== null) return false; g.tokenHash = newHash; return true; }
  async revokeFamily(familyId: string, at: number) { let n = 0; for (const g of this.rows.values()) if (g.familyId === familyId && g.revokedAt === null) { g.revokedAt = at; n++; } return n; }
  retired = new Map<string, string>();
  async retireHash(tokenHash: string, familyId: string) { this.retired.set(tokenHash, familyId); }
  async retiredFamily(tokenHash: string) { return this.retired.get(tokenHash); }
}

async function setup(t: { after: (fn: () => void) => void }) {
  const store = new MemoryGrantStore();
  const access = randomBytes(32).toString('base64url'), userId = randomUUID(), otherId = randomUUID();
  let pro = true;
  const auth = { user: async (token: unknown) => { if (token !== access) throw new ApiError(401, 'authentication_required'); return { id: userId, email: 'mcp-owner@example.com' }; } } as unknown as Authentication;
  const grants = new McpGrants(store, new AuthSecrets(credential(), credential()), { state: async () => pro ? 'active' : undefined }, () => NOW);
  const storage = { put: async () => {}, get: async () => Buffer.alloc(0), delete: async () => {} };
  const server = makeServer(new Relay(new MemoryRepository(), storage, budgets, () => NOW), { ipKey: 'x'.repeat(32), uploadsEnabled: false, auth, mcp: grants });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  return { store, grants, base, access, userId, otherId, setPro: (v: boolean) => { pro = v; }, bearer: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + access } };
}
type Setup = Awaited<ReturnType<typeof setup>>;

test('grant routes require a native session; an MCP token is not a session', async t => {
  const s: Setup = await setup(t);
  assert.equal((await fetch(s.base + '/v1/me/mcp/grants')).status, 401);
  assert.equal((await fetch(s.base + '/v1/me/mcp/grants', { headers: { Authorization: 'Bearer ' + randomBytes(32).toString('base64url') } })).status, 401);
  // Mint a real grant, then prove its token cannot manage grants.
  const created = await fetch(s.base + '/v1/me/mcp/grants', { method: 'POST', headers: s.bearer, body: JSON.stringify({ nickname: 'ChatGPT', scopes: ['library:read'] }) });
  assert.equal(created.status, 201);
  const { token } = await created.json() as { token: string };
  assert.equal((await fetch(s.base + '/v1/me/mcp/grants', { headers: { Authorization: 'Bearer ' + token } })).status, 401);
  assert.equal((await fetch(s.base + '/v1/me/mcp/grants', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token }, body: '{}' })).status, 401);
});

test('create returns the token exactly once; list shows metadata only', async t => {
  const s: Setup = await setup(t);
  const created = await fetch(s.base + '/v1/me/mcp/grants', { method: 'POST', headers: s.bearer, body: JSON.stringify({ nickname: '  My ChatGPT  ', scopes: ['library:read', 'media:read'], expiresInDays: 30 }) });
  assert.equal(created.status, 201);
  const body = await created.json() as { grant: { nickname: string; scopes: string[]; expiresAt: number; revokedAt: null }, token: string };
  assert.match(body.token, /^ptm_[A-Za-z0-9_-]{43}$/);
  assert.equal(body.grant.nickname, 'My ChatGPT');
  assert.equal(body.grant.expiresAt, NOW + 30 * 86400000);
  assert.equal(body.grant.revokedAt, null);
  const listed = await fetch(s.base + '/v1/me/mcp/grants', { headers: { Authorization: 'Bearer ' + s.access } });
  assert.equal(listed.status, 200);
  const listBody = await listed.json() as { grants: unknown[] };
  assert.equal(listBody.grants.length, 1);
  assert.ok(!JSON.stringify(listBody).includes(body.token), 'list must never echo the token');
  assert.ok(!JSON.stringify(listBody).includes('tokenHash'));
});

test('create validation: unknown fields, bad scopes, bad expiry are one 422', async t => {
  const s: Setup = await setup(t);
  const post = (body: unknown) => fetch(s.base + '/v1/me/mcp/grants', { method: 'POST', headers: s.bearer, body: JSON.stringify(body) });
  assert.equal((await post({ nickname: 'x', scopes: ['library:read'], admin: true })).status, 422);
  assert.equal((await post({ nickname: 'x', scopes: ['admin:all'] })).status, 422);
  assert.equal((await post({ nickname: 'x', scopes: ['library:read', 'library:read'] })).status, 422);
  assert.equal((await post({ nickname: '   ', scopes: ['library:read'] })).status, 422);
  assert.equal((await post({ nickname: 'x', scopes: [] })).status, 422);
  assert.equal((await post({ nickname: 'x', scopes: ['library:read'], expiresInDays: 0 })).status, 422);
  assert.equal((await post({ nickname: 'x', scopes: ['library:read'], expiresInDays: 366 })).status, 422);
  assert.equal((await post({ nickname: 'x', scopes: ['library:read'], expiresInDays: 1.5 })).status, 422);
  assert.equal((await post({ scopes: ['library:read'] })).status, 422);
  // Default expiry is 90 days when expiresInDays is omitted.
  const ok = await post({ nickname: 'x', scopes: ['library:read'] });
  assert.equal(ok.status, 201);
  const { grant } = await ok.json() as { grant: { expiresAt: number } };
  assert.equal(grant.expiresAt, NOW + 90 * 86400000);
});

test('revoke one and revoke all; foreign or unknown ids are the same 404', async t => {
  const s: Setup = await setup(t);
  const mk = async (nickname: string) => {
    const r = await fetch(s.base + '/v1/me/mcp/grants', { method: 'POST', headers: s.bearer, body: JSON.stringify({ nickname, scopes: ['library:read'] }) });
    return (await r.json()) as { grant: { id: string }, token: string };
  };
  const a = await mk('A'), b = await mk('B');
  const del = (id: string) => fetch(s.base + '/v1/me/mcp/grants/' + id, { method: 'DELETE', headers: { Authorization: 'Bearer ' + s.access } });
  assert.equal((await del(randomUUID())).status, 404);
  assert.equal((await del(a.grant.id)).status, 204);
  assert.equal((await del(a.grant.id)).status, 404); // already revoked: same indistinguishable answer
  // Revoked grant no longer authenticates data calls.
  assert.equal(await s.grants.authenticate(a.token), undefined);
  assert.ok(await s.grants.authenticate(b.token));
  const all = await fetch(s.base + '/v1/me/mcp/grants', { method: 'DELETE', headers: { Authorization: 'Bearer ' + s.access } });
  assert.equal(all.status, 200); assert.deepEqual(await all.json(), { revoked: 1 });
  assert.equal(await s.grants.authenticate(b.token), undefined);
});

test('grant management stays available after Pro lapses, including creation rules', async t => {
  const s: Setup = await setup(t);
  const created = await fetch(s.base + '/v1/me/mcp/grants', { method: 'POST', headers: s.bearer, body: JSON.stringify({ nickname: 'ChatGPT', scopes: ['library:read'] }) });
  const { grant } = await created.json() as { grant: { id: string } };
  s.setPro(false);
  const listed = await fetch(s.base + '/v1/me/mcp/grants', { headers: { Authorization: 'Bearer ' + s.access } });
  assert.equal(listed.status, 200);
  const listBody = await listed.json() as { grants: { id: string }[] };
  assert.equal(listBody.grants[0].id, grant.id, 'the connection is still listed for management after lapse');
  assert.equal((await fetch(s.base + '/v1/me/mcp/grants/' + grant.id, { method: 'DELETE', headers: { Authorization: 'Bearer ' + s.access } })).status, 204);
});
