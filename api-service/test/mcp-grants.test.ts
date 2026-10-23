import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { AuthSecrets, credential } from '../src/auth-credentials.ts';
import {
  McpGrants, hasScope, mcpProAccess, type McpEntitlementLookup, type McpGrantRecord, type McpGrantStore,
} from '../src/mcp-grants.ts';
import type { EntitlementState } from '../src/subscriptions.ts';

class MemoryGrantStore implements McpGrantStore {
  grants = new Map<string, McpGrantRecord>();
  touches = 0;
  async insert(grant: McpGrantRecord) { this.grants.set(grant.id, structuredClone(grant)); }
  async byId(id: string) { const g = this.grants.get(id); return g ? structuredClone(g) : undefined; }
  async byTokenHash(hash: string) { const g = [...this.grants.values()].find(g => g.tokenHash === hash); return g ? structuredClone(g) : undefined; }
  async listForUser(userId: string) { return [...this.grants.values()].filter(g => g.userId === userId).map(g => structuredClone(g)); }
  async revoke(id: string, userId: string, at: number) { const g = this.grants.get(id); if (!g || g.userId !== userId || g.revokedAt !== null) return false; g.revokedAt = at; return true; }
  async revokeAll(userId: string, at: number) { let n = 0; for (const g of this.grants.values()) if (g.userId === userId && g.revokedAt === null) { g.revokedAt = at; n++; } return n; }
  async touchLastUsed(id: string, at: number) { this.touches++; const g = this.grants.get(id); if (g) g.lastUsedAt = at; }
  async rotateTokenHash(id: string, expectedHash: string, newHash: string) { const g = this.grants.get(id); if (!g || g.tokenHash !== expectedHash || g.revokedAt !== null) return false; g.tokenHash = newHash; return true; }
  async revokeFamily(familyId: string, at: number) { let n = 0; for (const g of this.grants.values()) if (g.familyId === familyId && g.revokedAt === null) { g.revokedAt = at; n++; } return n; }
  retired = new Map<string, string>();
  async retireHash(tokenHash: string, familyId: string) { this.retired.set(tokenHash, familyId); }
  async retiredFamily(tokenHash: string) { return this.retired.get(tokenHash); }
}

const NOW = Date.UTC(2026, 9, 22, 12);
function fixture(state: EntitlementState | undefined = 'active') {
  let now = NOW;
  const store = new MemoryGrantStore();
  const secrets = new AuthSecrets(credential(), credential());
  const entitlement: McpEntitlementLookup = { state: async () => state };
  const grants = new McpGrants(store, secrets, entitlement, () => now);
  return { store, secrets, grants, setNow: (v: number) => { now = v; }, setState: (s: EntitlementState | undefined) => { entitlement.state = async () => s; } };
}

test('create returns the token once and stores only its keyed hash', async () => {
  const f = fixture();
  const { grant, token } = await f.grants.create('user-1', '  ChatGPT on my phone  ', ['library:read'], 86_400_000);
  assert.match(token, /^ptm_[A-Za-z0-9_-]{43}$/);
  assert.equal(grant.nickname, 'ChatGPT on my phone');
  const stored = await f.store.byId(grant.id);
  assert.ok(stored);
  assert.equal(stored.tokenHash, f.secrets.hash('mcp-token', token));
  assert.notEqual(stored.tokenHash, createHash('sha256').update(token).digest('hex'));
  // Summaries never carry the hash.
  assert.equal('tokenHash' in grant, false);
  assert.deepEqual((await f.grants.list('user-1')).map(g => g.id), [grant.id]);
});

test('create rejects invalid nickname, scopes and expiry', async () => {
  const f = fixture();
  await assert.rejects(f.grants.create('u', '   ', ['library:read'], 1000));
  await assert.rejects(f.grants.create('u', 'x'.repeat(81), ['library:read'], 1000));
  await assert.rejects(f.grants.create('u', 'ok', [], 1000));
  await assert.rejects(f.grants.create('u', 'ok', ['library:read', 'library:read'], 1000));
  await assert.rejects(f.grants.create('u', 'ok', ['library:publish'], 1000));
  await assert.rejects(f.grants.create('u', 'ok', ['admin'], 1000));
  await assert.rejects(f.grants.create('u', 'ok', ['library:read'], 0));
  await assert.rejects(f.grants.create('u', 'ok', ['library:read'], 366 * 86_400_000));
});

test('authenticate resolves a valid token to its principal and scopes', async () => {
  const f = fixture();
  const { grant, token } = await f.grants.create('user-9', 'Muse', ['library:read', 'media:read'], 86_400_000);
  const principal = await f.grants.authenticate(token);
  assert.deepEqual(principal, { grantId: grant.id, userId: 'user-9', scopes: ['library:read', 'media:read'] });
  assert.equal(hasScope(principal!, 'library:read'), true);
  assert.equal(hasScope(principal!, 'library:write'), false);
});

test('authenticate denies malformed, unknown, revoked and expired tokens identically', async () => {
  const f = fixture();
  const a = await f.grants.create('u', 'a', ['library:read'], 60_000);
  const b = await f.grants.create('u', 'b', ['library:read'], 60_000);
  assert.equal(await f.grants.authenticate('not-a-token'), undefined);
  assert.equal(await f.grants.authenticate('ptm_' + 'A'.repeat(43)), undefined);
  await f.grants.revoke('u', a.grant.id);
  assert.equal(await f.grants.authenticate(a.token), undefined);
  f.setNow(NOW + 60_001);
  assert.equal(await f.grants.authenticate(b.token), undefined);
});

test('revoke is owner-scoped and revokeAll fences every connection', async () => {
  const f = fixture();
  const a = await f.grants.create('owner', 'a', ['library:read'], 86_400_000);
  const b = await f.grants.create('owner', 'b', ['library:read'], 86_400_000);
  const other = await f.grants.create('someone-else', 'c', ['library:read'], 86_400_000);
  assert.equal(await f.grants.revoke('someone-else', a.grant.id), false);
  assert.ok(await f.grants.authenticate(a.token));
  assert.equal(await f.grants.revokeAll('owner'), 2);
  assert.equal(await f.grants.authenticate(a.token), undefined);
  assert.equal(await f.grants.authenticate(b.token), undefined);
  assert.ok(await f.grants.authenticate(other.token));
});

test('entitlement is re-checked on every authentication', async () => {
  const f = fixture('active');
  const { token } = await f.grants.create('u', 'a', ['library:read'], 86_400_000);
  assert.ok(await f.grants.authenticate(token));
  f.setState('grace'); assert.ok(await f.grants.authenticate(token));
  f.setState('expired'); assert.equal(await f.grants.authenticate(token), undefined);
  f.setState('refunded'); assert.equal(await f.grants.authenticate(token), undefined);
  f.setState('revoked'); assert.equal(await f.grants.authenticate(token), undefined);
  f.setState(undefined); assert.equal(await f.grants.authenticate(token), undefined);
  f.setState('active'); assert.ok(await f.grants.authenticate(token));
});

test('pro access states are exactly active and grace', () => {
  const states: (EntitlementState | 'unknown' | undefined)[] = ['none', 'pending', 'active', 'grace', 'retry', 'paused', 'expired', 'cancelled', 'refunded', 'revoked', 'unknown', undefined];
  assert.deepEqual(states.filter(mcpProAccess), ['active', 'grace']);
});

test('last-used updates are throttled', async () => {
  const f = fixture();
  const { token } = await f.grants.create('u', 'a', ['library:read'], 86_400_000);
  await f.grants.authenticate(token); await f.grants.authenticate(token);
  assert.equal(f.store.touches, 1);
  f.setNow(NOW + 61_000);
  await f.grants.authenticate(token);
  assert.equal(f.store.touches, 2);
});
