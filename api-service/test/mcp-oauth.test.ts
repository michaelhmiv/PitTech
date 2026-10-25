import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { makeServer } from '../src/http.ts';
import { ApiError } from '../src/core.ts';
import { Relay } from '../src/relay.ts';
import type { Authentication } from '../src/auth.ts';
import { AuthSecrets, credential } from '../src/auth-credentials.ts';
import { McpGrants, type McpGrantRecord, type McpGrantStore } from '../src/mcp-grants.ts';
import { McpOAuth, type McpOAuthCodeRecord, type McpOAuthPendingRecord, type McpOAuthStore } from '../src/mcp-oauth.ts';
import { MemoryRepository, budgets } from './helpers.ts';

const NOW = 1_800_000_000_000;
const CLIENT = { id: 'chatgpt', name: 'ChatGPT', redirectUris: ['https://chatgpt.com/connector/callback'] };
const VERIFIER = 'verifier-' + 'a'.repeat(40);
const CHALLENGE = createHash('sha256').update(VERIFIER).digest('base64url');

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
class MemoryOAuthStore implements McpOAuthStore {
  codes = new Map<string, McpOAuthCodeRecord>(); pendings = new Map<string, McpOAuthPendingRecord>();
  async insertCode(c: McpOAuthCodeRecord) { this.codes.set(c.codeHash, structuredClone(c)); }
  async codeByHash(h: string) { const c = this.codes.get(h); return c ? structuredClone(c) : undefined; }
  async markCodeUsed(h: string, at: number) { const c = this.codes.get(h); if (!c || c.usedAt !== null) return false; c.usedAt = at; return true; }
  async insertPending(p: McpOAuthPendingRecord) { this.pendings.set(p.userCodeHash, structuredClone(p)); }
  async pendingByUserCodeHash(h: string) { const p = this.pendings.get(h); return p ? structuredClone(p) : undefined; }
  async pendingByPollHash(h: string) { for (const p of this.pendings.values()) if (p.pollHash === h) return structuredClone(p); }
  async approvePending(h: string, userId: string, codeHash: string) { const p = this.pendings.get(h); if (!p || p.approvedUserId !== null) return false; p.approvedUserId = userId; p.approvedCodeHash = codeHash; return true; }
  async pruneExpired(at: number) { for (const [k, p] of this.pendings) if (p.expiresAt <= at) this.pendings.delete(k); }
}

function fixture() {
  let now = NOW;
  const grantStore = new MemoryGrantStore(), oauthStore = new MemoryOAuthStore();
  const secrets = new AuthSecrets(credential(), credential());
  const grants = new McpGrants(grantStore, secrets, { state: async () => 'active' }, () => now);
  const oauth = new McpOAuth(oauthStore, grants, secrets, [CLIENT], () => now);
  return { grantStore, oauthStore, secrets, grants, oauth, setNow: (v: number) => { now = v; } };
}
const authz = (over: Partial<Parameters<McpOAuth['startAuthorization']>[0]> = {}) => ({
  clientId: 'chatgpt', redirectUri: CLIENT.redirectUris[0], responseType: 'code',
  codeChallenge: CHALLENGE, codeChallengeMethod: 'S256', scope: 'library:read media:read', state: 'state-1', ...over,
});

test('authorize validation: unknown client, unregistered redirect, plain PKCE, bad scopes all fail closed', async () => {
  const f = fixture();
  await assert.rejects(f.oauth.startAuthorization(authz({ clientId: 'evil' })), /Unknown OAuth client/);
  await assert.rejects(f.oauth.startAuthorization(authz({ redirectUri: 'https://evil.example/cb' })), /Redirect URI/);
  await assert.rejects(f.oauth.startAuthorization(authz({ codeChallengeMethod: 'plain' })), /S256/);
  await assert.rejects(f.oauth.startAuthorization(authz({ responseType: 'token' })), /response_type/);
  await assert.rejects(f.oauth.startAuthorization(authz({ scope: 'library:read admin:all' })), /Invalid scope/);
  await assert.rejects(f.oauth.startAuthorization(authz({ scope: 'library:read library:read' })), /Invalid scope/);
  const pending = await f.oauth.startAuthorization(authz());
  assert.match(pending.userCode, /^[A-Z2-9]{4}-[A-Z2-9]{4}$/);
  assert.equal(pending.client.id, 'chatgpt');
  assert.deepEqual(pending.scopes, ['library:read', 'media:read']);
});

test('full flow: approve in app → poll reveals code once → PKCE exchange mints the first token', async () => {
  const f = fixture();
  const pending = await f.oauth.startAuthorization(authz());
  assert.deepEqual(await f.oauth.pollStatus(pending.pollToken), { status: 'pending' });
  assert.equal(await f.oauth.approve('user-9', 'ZZZZ-ZZZZ'), false);
  assert.equal(await f.oauth.approve('user-9', pending.userCode.toLowerCase().replace('-', ' ')), true, 'user code is case/space/dash tolerant');
  assert.equal(await f.oauth.approve('user-9', pending.userCode), false, 'already approved');
  const status = await f.oauth.pollStatus(pending.pollToken);
  assert.equal(status.status, 'approved');
  if (status.status !== 'approved') return;
  assert.equal(status.redirectUri, CLIENT.redirectUris[0]); assert.equal(status.state, 'state-1');
  assert.deepEqual(await f.oauth.pollStatus(pending.pollToken), { status: 'expired' }, 'code reveal is consumed by the first poll');
  const exchanged = await f.oauth.exchangeCode({ code: status.code, clientId: 'chatgpt', redirectUri: CLIENT.redirectUris[0], codeVerifier: VERIFIER });
  assert.ok(exchanged);
  assert.equal(exchanged.token_type, 'Bearer'); assert.equal(exchanged.scope, 'library:read media:read');
  assert.ok(exchanged.expires_in > 89 * 86400);
  assert.equal(await f.oauth.exchangeCode({ code: status.code, clientId: 'chatgpt', redirectUri: CLIENT.redirectUris[0], codeVerifier: VERIFIER }), undefined, 'codes are single-use');
  const principal = await f.grants.authenticate(exchanged.access_token);
  assert.ok(principal); assert.equal(principal.userId, 'user-9');
  const grant = await f.grants.byId(principal.grantId);
  assert.equal(grant?.clientId, 'chatgpt'); assert.equal(grant?.familyId, grant?.id);
  assert.equal(grant?.nickname, 'ChatGPT');
});

test('describe shows client + scopes for the approval confirmation and never approves', async () => {
  const f = fixture();
  const pending = await f.oauth.startAuthorization(authz());
  const described = await f.oauth.describe(pending.userCode.toLowerCase().replace('-', ' '));
  assert.ok(described, 'user code is case/space/dash tolerant, like approve');
  assert.equal(described.clientName, 'ChatGPT');
  assert.deepEqual(described.scopes, ['library:read', 'media:read']);
  assert.equal(described.expiresAt, pending.expiresAt);
  assert.deepEqual(await f.oauth.pollStatus(pending.pollToken), { status: 'pending' }, 'describing changed no state');
  assert.equal(await f.oauth.describe('ZZZZ-ZZZZ'), undefined, 'unknown code');
  assert.equal(await f.oauth.describe('not a code'), undefined, 'malformed code');
  assert.equal(await f.oauth.approve('user-9', pending.userCode), true);
  assert.equal(await f.oauth.describe(pending.userCode), undefined, 'already-approved codes describe as nothing');
  const second = await f.oauth.startAuthorization(authz());
  f.setNow(NOW + 16 * 60 * 1000);
  assert.equal(await f.oauth.describe(second.userCode), undefined, 'expired codes describe as nothing');
});

test('exchange rejects wrong verifier, wrong client and wrong redirect identically', async () => {
  const f = fixture();
  const pending = await f.oauth.startAuthorization(authz());
  await f.oauth.approve('user-1', pending.userCode);
  const status = await f.oauth.pollStatus(pending.pollToken);
  if (status.status !== 'approved') throw new Error('expected approval');
  const base = { code: status.code, clientId: 'chatgpt', redirectUri: CLIENT.redirectUris[0], codeVerifier: VERIFIER };
  assert.equal(await f.oauth.exchangeCode({ ...base, codeVerifier: 'wrong-' + 'b'.repeat(40) }), undefined);
  assert.equal(await f.oauth.exchangeCode({ ...base, clientId: 'other' }), undefined);
  assert.equal(await f.oauth.exchangeCode({ ...base, redirectUri: 'https://chatgpt.com/other' }), undefined);
  assert.ok(await f.oauth.exchangeCode(base), 'the genuine exchange still works after failed attempts');
});

test('refresh rotates; replaying a retired token revokes the whole family', async () => {
  const f = fixture();
  const pending = await f.oauth.startAuthorization(authz());
  await f.oauth.approve('user-1', pending.userCode);
  const status = await f.oauth.pollStatus(pending.pollToken);
  if (status.status !== 'approved') throw new Error('expected approval');
  const first = await f.oauth.exchangeCode({ code: status.code, clientId: 'chatgpt', redirectUri: CLIENT.redirectUris[0], codeVerifier: VERIFIER });
  assert.ok(first);
  const second = await f.oauth.refresh({ refreshToken: first.refresh_token, clientId: 'chatgpt' });
  assert.ok(second); assert.notEqual(second.access_token, first.access_token);
  assert.equal(await f.grants.authenticate(first.access_token), undefined, 'rotated-out token is dead');
  assert.ok(await f.grants.authenticate(second.access_token));
  assert.equal(await f.oauth.refresh({ refreshToken: second.refresh_token, clientId: 'other' }), undefined);
  // Replay of the retired first token: theft signal → family revoked.
  assert.equal(await f.oauth.refresh({ refreshToken: first.refresh_token, clientId: 'chatgpt' }), undefined);
  assert.equal(await f.grants.authenticate(second.access_token), undefined, 'family revoked after retired-token replay');
});

test('revoke by token disconnects the OAuth grant; PATs are untouched by client revoke', async () => {
  const f = fixture();
  const { token: pat } = await f.grants.create('user-1', 'Manual token', ['library:read'], 86400000);
  const pending = await f.oauth.startAuthorization(authz());
  await f.oauth.approve('user-1', pending.userCode);
  const status = await f.oauth.pollStatus(pending.pollToken);
  if (status.status !== 'approved') throw new Error('expected approval');
  const exchanged = await f.oauth.exchangeCode({ code: status.code, clientId: 'chatgpt', redirectUri: CLIENT.redirectUris[0], codeVerifier: VERIFIER });
  assert.ok(exchanged);
  await f.oauth.revokeByToken(pat, 'chatgpt'); // PAT has no client: no-op
  assert.ok(await f.grants.authenticate(pat));
  await f.oauth.revokeByToken(exchanged.access_token, 'chatgpt');
  assert.equal(await f.grants.authenticate(exchanged.access_token), undefined);
});

test('expired pendings and codes fail closed', async () => {
  const f = fixture();
  const pending = await f.oauth.startAuthorization(authz());
  f.setNow(NOW + 16 * 60000);
  assert.equal(await f.oauth.approve('user-1', pending.userCode), false);
  assert.deepEqual(await f.oauth.pollStatus(pending.pollToken), { status: 'expired' });
});

async function httpSetup(t: { after: (fn: () => void) => void }) {
  const f = fixture();
  const access = randomBytes(32).toString('base64url'), userId = randomUUID();
  const auth = { user: async (token: unknown) => { if (token !== access) throw new ApiError(401, 'authentication_required'); return { id: userId, email: 'oauth@example.com' }; } } as unknown as Authentication;
  const storage = { put: async () => {}, get: async () => Buffer.alloc(0), delete: async () => {} };
  const server = makeServer(new Relay(new MemoryRepository(), storage, budgets, () => NOW),
    { ipKey: 'x'.repeat(32), uploadsEnabled: false, auth, mcp: f.grants, oauth: f.oauth, oauthIssuer: 'https://api-staging.pittech.app' });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  return { ...f, base, access, userId };
}
type HttpSetup = Awaited<ReturnType<typeof httpSetup>>;

test('HTTP: discovery metadata, approval page, approve + token round trip', async t => {
  const s: HttpSetup = await httpSetup(t);
  const meta = await (await fetch(s.base + '/.well-known/oauth-authorization-server')).json() as Record<string, unknown>;
  assert.equal(meta.issuer, 'https://api-staging.pittech.app');
  assert.equal(meta.token_endpoint, 'https://api-staging.pittech.app/oauth/token');
  assert.deepEqual(meta.code_challenge_methods_supported, ['S256']);
  const q = new URLSearchParams({ response_type: 'code', client_id: 'chatgpt', redirect_uri: CLIENT.redirectUris[0], scope: 'library:read', state: 'st', code_challenge: CHALLENGE, code_challenge_method: 'S256' });
  const badClient = await fetch(s.base + '/oauth/authorize?' + new URLSearchParams({ ...Object.fromEntries(q), client_id: 'evil' }));
  assert.equal(badClient.status, 400);
  const pageRes = await fetch(s.base + '/oauth/authorize?' + q);
  assert.equal(pageRes.status, 200); assert.match(pageRes.headers.get('content-type')!, /text\/html/);
  const html = await pageRes.text();
  const codeMatch = /[A-Z2-9]{4}-[A-Z2-9]{4}/.exec(html);
  assert.ok(codeMatch, 'approval page shows a user code');
  assert.match(html, /See your meals, recipes and logs/);
  // Approve through the session route, exactly as the app will.
  const approve = await fetch(s.base + '/v1/me/mcp/oauth/approve', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + s.access }, body: JSON.stringify({ userCode: codeMatch[0] }) });
  assert.equal(approve.status, 200);
  const pollMatch = /const poll="([^"]+)"/.exec(html);
  assert.ok(pollMatch);
  const status = await (await fetch(s.base + '/oauth/authorize/status?poll=' + encodeURIComponent(pollMatch[1]))).json() as { status: string; location?: string };
  assert.equal(status.status, 'approved');
  const location = new URL(status.location!);
  assert.equal(location.origin + location.pathname, CLIENT.redirectUris[0]);
  assert.equal(location.searchParams.get('state'), 'st');
  const code = location.searchParams.get('code')!;
  const form = new URLSearchParams({ grant_type: 'authorization_code', code, client_id: 'chatgpt', redirect_uri: CLIENT.redirectUris[0], code_verifier: VERIFIER });
  const tokenRes = await fetch(s.base + '/oauth/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: form });
  assert.equal(tokenRes.status, 200);
  const tokenBody = await tokenRes.json() as { access_token: string };
  assert.match(tokenBody.access_token, /^ptm_/);
  const refreshForm = new URLSearchParams({ grant_type: 'refresh_token', refresh_token: tokenBody.access_token, client_id: 'chatgpt' });
  const refreshed = await fetch(s.base + '/oauth/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: refreshForm });
  assert.equal(refreshed.status, 200);
  const badForm = new URLSearchParams({ grant_type: 'refresh_token', refresh_token: tokenBody.access_token, client_id: 'chatgpt' });
  const replay = await fetch(s.base + '/oauth/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: badForm });
  assert.equal(replay.status, 400); assert.deepEqual(await replay.json(), { error: 'invalid_grant' });
});

test('HTTP: describe powers the in-app approval confirmation and approves nothing', async t => {
  const s: HttpSetup = await httpSetup(t);
  const q = new URLSearchParams({ response_type: 'code', client_id: 'chatgpt', redirect_uri: CLIENT.redirectUris[0], scope: 'library:read media:read', state: 'st', code_challenge: CHALLENGE, code_challenge_method: 'S256' });
  const pageRes = await fetch(s.base + '/oauth/authorize?' + q);
  const html = await pageRes.text();
  const codeMatch = /[A-Z2-9]{4}-[A-Z2-9]{4}/.exec(html);
  assert.ok(codeMatch);
  const describe = (userCode: string, access?: string) => fetch(s.base + '/v1/me/mcp/oauth/describe', { method: 'POST', headers: { 'Content-Type': 'application/json', ...(access ? { Authorization: 'Bearer ' + access } : {}) }, body: JSON.stringify({ userCode }) });
  assert.equal((await describe(codeMatch[0])).status, 401, 'a native session is required');
  const ok = await describe(codeMatch[0], s.access);
  assert.equal(ok.status, 200);
  const body = await ok.json() as { clientName: string; scopes: string[]; expiresAt: number };
  assert.equal(body.clientName, 'ChatGPT');
  assert.deepEqual(body.scopes, ['library:read', 'media:read']);
  assert.ok(body.expiresAt > NOW);
  assert.equal((await describe('ZZZZ-ZZZZ', s.access)).status, 404, 'unknown codes are indistinguishable');
  // Describing approved nothing: the code still needs its own approve call.
  const approve = await fetch(s.base + '/v1/me/mcp/oauth/approve', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + s.access }, body: JSON.stringify({ userCode: codeMatch[0] }) });
  assert.equal(approve.status, 200);
  assert.equal((await describe(codeMatch[0], s.access)).status, 404, 'approved codes no longer describe');
});
