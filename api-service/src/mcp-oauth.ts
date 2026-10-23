import { createHash, randomBytes } from 'node:crypto';
import type { Pool } from 'pg';
import type { AuthSecrets } from './auth-credentials.ts';
import { MCP_SCOPES, McpGrants, type McpScope } from './mcp-grants.ts';

/**
 * MCP OAuth (issue #114, slice M1b). PitTech accounts live in the native
 * apps — there is no web password session to consent with — so the
 * authorization step is an in-app approval:
 *
 *   1. The client (ChatGPT, Muse) opens /oauth/authorize with PKCE S256.
 *      The server validates the registered client and its exact redirect
 *      URI, then shows a short user code: "approve this in PitTech".
 *   2. The owner approves the code in the app (native session, grant
 *      routes). Approval creates the OAuth grant and a single-use
 *      authorization code bound to the PKCE challenge.
 *   3. The browser page polls; once approved it redirects to the client's
 *      redirect URI with the code. The client exchanges code + verifier
 *      at /oauth/token for the grant's first token.
 *   4. Refresh is rotation: presenting the current token at
 *      /oauth/token (grant_type=refresh_token) retires its hash and
 *      issues the next one. Replaying a retired hash revokes the whole
 *      family — the standard theft signal.
 *
 * Clients are deployment configuration, never open registration: an
 * unregistered client id or a redirect URI that is not an exact match
 * fails before any page is rendered, and errors never redirect to an
 * unvalidated URI.
 */

export interface McpOAuthClient { id: string; name: string; redirectUris: string[] }

export interface McpOAuthCodeRecord {
  codeHash: string; grantId: string; userId: string; clientId: string;
  redirectUri: string; codeChallenge: string; createdAt: number;
  expiresAt: number; usedAt: number | null;
}
export interface McpOAuthPendingRecord {
  userCodeHash: string; pollHash: string; clientId: string; redirectUri: string;
  oauthState: string | null; codeChallenge: string; scopes: McpScope[];
  createdAt: number; expiresAt: number;
  approvedUserId: string | null; approvedCodeHash: string | null;
}
export interface McpOAuthStore {
  insertCode(code: McpOAuthCodeRecord): Promise<void>;
  codeByHash(codeHash: string): Promise<McpOAuthCodeRecord | undefined>;
  markCodeUsed(codeHash: string, at: number): Promise<boolean>;
  insertPending(pending: McpOAuthPendingRecord): Promise<void>;
  pendingByUserCodeHash(hash: string): Promise<McpOAuthPendingRecord | undefined>;
  pendingByPollHash(hash: string): Promise<McpOAuthPendingRecord | undefined>;
  approvePending(userCodeHash: string, userId: string, codeHash: string): Promise<boolean>;
  pruneExpired(at: number): Promise<void>;
}

interface CodeRow {
  code_hash: string; grant_id: string; user_id: string; client_id: string;
  redirect_uri: string; code_challenge: string; created_at: string;
  expires_at: string; used_at: string | null;
}
interface PendingRow {
  user_code_hash: string; poll_hash: string; client_id: string; redirect_uri: string;
  oauth_state: string | null; code_challenge: string; scopes: string[];
  created_at: string; expires_at: string; approved_user_id: string | null;
  approved_code_hash: string | null;
}
const SCOPE_SET: ReadonlySet<string> = new Set(MCP_SCOPES);
const toCode = (row: CodeRow): McpOAuthCodeRecord => ({
  codeHash: row.code_hash, grantId: row.grant_id, userId: row.user_id, clientId: row.client_id,
  redirectUri: row.redirect_uri, codeChallenge: row.code_challenge,
  createdAt: Number(row.created_at), expiresAt: Number(row.expires_at),
  usedAt: row.used_at === null ? null : Number(row.used_at),
});
const toPending = (row: PendingRow): McpOAuthPendingRecord => ({
  userCodeHash: row.user_code_hash, pollHash: row.poll_hash, clientId: row.client_id,
  redirectUri: row.redirect_uri, oauthState: row.oauth_state, codeChallenge: row.code_challenge,
  scopes: row.scopes.filter((s): s is McpScope => SCOPE_SET.has(s)),
  createdAt: Number(row.created_at), expiresAt: Number(row.expires_at),
  approvedUserId: row.approved_user_id, approvedCodeHash: row.approved_code_hash,
});

export class PostgresMcpOAuthStore implements McpOAuthStore {
  private pool: Pool;
  constructor(pool: Pool) { this.pool = pool; }
  async insertCode(code: McpOAuthCodeRecord) {
    await this.pool.query(
      'INSERT INTO auth.mcp_oauth_codes(code_hash,grant_id,user_id,client_id,redirect_uri,code_challenge,created_at,expires_at,used_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)',
      [code.codeHash, code.grantId, code.userId, code.clientId, code.redirectUri, code.codeChallenge, code.createdAt, code.expiresAt, code.usedAt]);
  }
  async codeByHash(codeHash: string) {
    const row = (await this.pool.query('SELECT * FROM auth.mcp_oauth_codes WHERE code_hash=$1', [codeHash])).rows[0] as CodeRow | undefined;
    return row ? toCode(row) : undefined;
  }
  async markCodeUsed(codeHash: string, at: number) {
    const result = await this.pool.query('UPDATE auth.mcp_oauth_codes SET used_at=$2 WHERE code_hash=$1 AND used_at IS NULL', [codeHash, at]);
    return (result.rowCount ?? 0) > 0;
  }
  async insertPending(pending: McpOAuthPendingRecord) {
    await this.pool.query(
      'INSERT INTO auth.mcp_oauth_pending(user_code_hash,poll_hash,client_id,redirect_uri,oauth_state,code_challenge,scopes,created_at,expires_at,approved_user_id,approved_code_hash) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)',
      [pending.userCodeHash, pending.pollHash, pending.clientId, pending.redirectUri, pending.oauthState, pending.codeChallenge, pending.scopes, pending.createdAt, pending.expiresAt, pending.approvedUserId, pending.approvedCodeHash]);
  }
  async pendingByUserCodeHash(hash: string) {
    const row = (await this.pool.query('SELECT * FROM auth.mcp_oauth_pending WHERE user_code_hash=$1', [hash])).rows[0] as PendingRow | undefined;
    return row ? toPending(row) : undefined;
  }
  async pendingByPollHash(hash: string) {
    const row = (await this.pool.query('SELECT * FROM auth.mcp_oauth_pending WHERE poll_hash=$1', [hash])).rows[0] as PendingRow | undefined;
    return row ? toPending(row) : undefined;
  }
  async approvePending(userCodeHash: string, userId: string, codeHash: string) {
    const result = await this.pool.query(
      'UPDATE auth.mcp_oauth_pending SET approved_user_id=$2, approved_code_hash=$3 WHERE user_code_hash=$1 AND approved_user_id IS NULL', [userCodeHash, userId, codeHash]);
    return (result.rowCount ?? 0) > 0;
  }
  async pruneExpired(at: number) {
    await this.pool.query('DELETE FROM auth.mcp_oauth_pending WHERE expires_at<=$1', [at]);
    await this.pool.query('DELETE FROM auth.mcp_oauth_codes WHERE expires_at<=$1 AND used_at IS NULL', [at]);
  }
}

const PENDING_TTL_MS = 15 * 60 * 1000;
const CODE_TTL_MS = 10 * 60 * 1000;
const GRANT_TTL_MS = 90 * 24 * 60 * 60 * 1000; // matches the PAT default the owner picks in-app
const USER_CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; // no 0/O/1/I/L

/** Plain-language scope labels for consent surfaces. Draft copy — the
 * owner reviews wording before any client ships with it. */
export const MCP_SCOPE_LABELS: Record<McpScope, string> = {
  'library:read': 'See your meals, recipes and logs',
  'library:write': 'Add and edit meals, recipes and logs',
  'library:delete': 'Delete meals, recipes and logs',
  'media:read': 'See your cook photos',
  'media:write': 'Add cook photos',
  'planning:write': 'Create and change cook plans',
  'device_actions:request': 'Ask your app to start device actions (you confirm in the app)',
  'exports:read': 'Download exports of your data',
  'imports:write': 'Import data into your library',
  'community:read': 'See community benchmarks',
};

export interface AuthorizationRequest {
  clientId: string; redirectUri: string; scope?: string; state?: string;
  codeChallenge: string; codeChallengeMethod: string; responseType: string;
}
export interface PendingAuthorization { userCode: string; pollToken: string; expiresAt: number; client: McpOAuthClient; scopes: McpScope[] }
export interface TokenResponse { access_token: string; refresh_token: string; token_type: 'Bearer'; expires_in: number; scope: string }

export class McpOAuth {
  private store: McpOAuthStore; private grants: McpGrants; private secrets: AuthSecrets;
  private clients: Map<string, McpOAuthClient>; private now: () => number;
  /** Authorization-code plaintexts live only here, between approve() and
   * the browser's one poll — the database stores just their hashes, so a
   * database leak yields no redeemable codes. A restart in that window
   * loses the reveal; the pending expires and the owner retries (the
   * grant stays listed in-app and revocable). */
  private revealedCodes = new Map<string, string>();
  constructor(store: McpOAuthStore, grants: McpGrants, secrets: AuthSecrets,
    clients: readonly McpOAuthClient[], now: () => number = Date.now) {
    this.store = store; this.grants = grants; this.secrets = secrets;
    this.clients = new Map(clients.map(c => [c.id, c])); this.now = now;
  }

  client(id: string): McpOAuthClient | undefined { return this.clients.get(id); }
  get clientList(): McpOAuthClient[] { return [...this.clients.values()]; }

  /** Validates an authorize request and, when valid, parks it as a
   * pending approval. Every validation failure is a plain Error the HTTP
   * layer renders WITHOUT redirecting (the redirect URI is only trusted
   * after the client check passes). */
  async startAuthorization(request: AuthorizationRequest): Promise<PendingAuthorization> {
    const client = this.clients.get(request.clientId);
    if (!client) throw new Error('Unknown OAuth client');
    if (!client.redirectUris.includes(request.redirectUri)) throw new Error('Redirect URI not registered for this client');
    if (request.responseType !== 'code') throw new Error('Only response_type=code is supported');
    if (request.codeChallengeMethod !== 'S256') throw new Error('PKCE S256 is required');
    if (!/^[A-Za-z0-9_-]{43,128}$/.test(request.codeChallenge)) throw new Error('Invalid code challenge');
    const requested = (request.scope ?? '').split(' ').filter(Boolean);
    const scopes = requested.length ? requested : ['library:read'];
    if (scopes.some(s => !SCOPE_SET.has(s)) || new Set(scopes).size !== scopes.length) throw new Error('Invalid scope');
    const now = this.now();
    const raw = Array.from(randomBytes(8), b => USER_CODE_ALPHABET[b % USER_CODE_ALPHABET.length]).join('');
    const userCode = raw.slice(0, 4) + '-' + raw.slice(4);
    const pollToken = randomBytes(32).toString('base64url');
    const pending: McpOAuthPendingRecord = {
      userCodeHash: this.secrets.hash('mcp-oauth-user-code', userCode),
      pollHash: this.secrets.hash('mcp-oauth-poll', pollToken),
      clientId: client.id, redirectUri: request.redirectUri,
      oauthState: request.state ?? null, codeChallenge: request.codeChallenge,
      scopes: scopes as McpScope[], createdAt: now, expiresAt: now + PENDING_TTL_MS,
      approvedUserId: null, approvedCodeHash: null,
    };
    await this.store.insertPending(pending);
    return { userCode, pollToken, expiresAt: pending.expiresAt, client, scopes: pending.scopes };
  }

  /** The owner approving a user code in the app: creates the grant and a
   * bound authorization code. Unknown, expired or already-approved codes
   * are the same false. */
  async approve(userId: string, userCodeRaw: string): Promise<boolean> {
    const userCode = userCodeRaw.trim().toUpperCase().replace(/[\s-]/g, '');
    if (!/^[A-Z2-9]{8}$/.test(userCode)) return false;
    const formatted = userCode.slice(0, 4) + '-' + userCode.slice(4);
    const pending = await this.store.pendingByUserCodeHash(this.secrets.hash('mcp-oauth-user-code', formatted));
    if (!pending || pending.approvedUserId !== null || pending.expiresAt <= this.now()) return false;
    const client = this.clients.get(pending.clientId);
    if (!client) return false;
    const grant = await this.grants.createOAuthGrant(userId, client.name, pending.scopes, GRANT_TTL_MS, client.id);
    const now = this.now();
    const codeValue = randomBytes(32).toString('base64url');
    const code: McpOAuthCodeRecord = {
      codeHash: this.secrets.hash('mcp-oauth-code', codeValue), grantId: grant.id, userId,
      clientId: pending.clientId, redirectUri: pending.redirectUri,
      codeChallenge: pending.codeChallenge, createdAt: now, expiresAt: now + CODE_TTL_MS, usedAt: null,
    };
    await this.store.insertCode(code);
    this.revealCode(code.codeHash, codeValue);
    const claimed = await this.store.approvePending(pending.userCodeHash, userId, code.codeHash);
    if (!claimed) return false; // lost a race; the minted code is bound to a grant the owner can revoke in-app
    return true;
  }

  /** Browser poll: pending, expired, or approved with the one-time code
   * reveal (consumed by this read). */
  async pollStatus(pollToken: string): Promise<{ status: 'pending' | 'expired' } | { status: 'approved'; redirectUri: string; code: string; state: string | null }> {
    const pending = await this.store.pendingByPollHash(this.secrets.hash('mcp-oauth-poll', pollToken));
    if (!pending || pending.expiresAt <= this.now()) return { status: 'expired' };
    if (pending.approvedUserId === null || pending.approvedCodeHash === null) return { status: 'pending' };
    const code = await this.store.codeByHash(pending.approvedCodeHash);
    if (!code) return { status: 'expired' };
    const revealed = this.revealedCodes.get(pending.approvedCodeHash);
    if (!revealed) return { status: 'expired' };
    this.revealedCodes.delete(pending.approvedCodeHash);
    return { status: 'approved', redirectUri: pending.redirectUri, code: revealed, state: pending.oauthState };
  }

  private revealCode(codeHash: string, codeValue: string) { this.revealedCodes.set(codeHash, codeValue); }

  /** Exchanges an authorization code (PKCE S256 verified) for the grant's
   * first token. All failures are the same undefined → invalid_grant. */
  async exchangeCode(input: { code: string; clientId: string; redirectUri: string; codeVerifier: string }): Promise<TokenResponse | undefined> {
    if (!/^[A-Za-z0-9_-]{43,128}$/.test(input.codeVerifier)) return undefined;
    const codeHash = this.secrets.hash('mcp-oauth-code', input.code);
    const code = await this.store.codeByHash(codeHash);
    if (!code || code.usedAt !== null || code.expiresAt <= this.now()) return undefined;
    if (code.clientId !== input.clientId || code.redirectUri !== input.redirectUri) return undefined;
    const challenge = createHash('sha256').update(input.codeVerifier).digest('base64url');
    if (challenge !== code.codeChallenge) return undefined;
    if (!await this.store.markCodeUsed(codeHash, this.now())) return undefined;
    const token = await this.grants.issueFirstToken(code.grantId);
    if (!token) return undefined;
    const grant = await this.grants.byId(code.grantId);
    if (!grant) return undefined;
    return { access_token: token, refresh_token: token, token_type: 'Bearer', expires_in: Math.max(0, Math.floor((grant.expiresAt - this.now()) / 1000)), scope: grant.scopes.join(' ') };
  }

  /** Refresh = rotation. Presenting a retired (rotated-out) token is
   * treated as theft: the whole family is revoked and the call fails.
   * Presenting the current token rotates it. Anything else fails the
   * same way. */
  async refresh(input: { refreshToken: string; clientId: string }): Promise<TokenResponse | undefined> {
    const family = await this.grants.retiredFamilyFor(input.refreshToken);
    if (family) { await this.grants.revokeFamily(family); return undefined; }
    const principal = await this.grants.authenticate(input.refreshToken);
    if (!principal) return undefined;
    const grant = await this.grants.byId(principal.grantId);
    if (!grant || grant.clientId !== input.clientId || grant.familyId === null) return undefined;
    const token = await this.grants.rotateToken(grant.id, input.refreshToken);
    if (!token) return undefined;
    return { access_token: token, refresh_token: token, token_type: 'Bearer', expires_in: Math.max(0, Math.floor((grant.expiresAt - this.now()) / 1000)), scope: grant.scopes.join(' ') };
  }

  /** Revocation endpoint semantics (RFC 7009): revoking an OAuth grant
   * by its current token. Unknown tokens are a silent success there. */
  async revokeByToken(token: string, clientId: string): Promise<void> {
    const principal = await this.grants.authenticate(token);
    if (!principal) return;
    const grant = await this.grants.byId(principal.grantId);
    if (!grant || grant.clientId !== clientId) return;
    await this.grants.revokeFamily(grant.familyId ?? grant.id);
  }

  async prune(): Promise<void> { await this.store.pruneExpired(this.now()); }
}
