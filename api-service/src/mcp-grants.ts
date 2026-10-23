import { randomBytes, randomUUID } from 'node:crypto';
import type { Pool } from 'pg';
import type { AuthSecrets } from './auth-credentials.ts';
import type { EntitlementState } from './subscriptions.ts';
import type { StoreEnvironment } from './subscriptions-apple.ts';

/**
 * MCP connection grants (issue #114, slice M1).
 *
 * A grant is one AI-assistant connection to one account, created only from
 * trusted first-party UI. The token is shown once at creation; only a
 * domain-separated keyed hash is stored (AuthSecrets, purpose 'mcp-token'),
 * so a database leak yields no usable tokens and tokens cannot be replayed
 * as native session credentials. A grant is never a principal on its own:
 * every authentication re-checks revocation, expiry AND the account's
 * current Pro entitlement, so refund/revocation/grace-end takes effect on
 * the next call, not at some cached login. Grant management itself
 * (create/list/revoke) is exercised through native account sessions by the
 * caller — this module mints nothing for an MCP principal.
 */

export const MCP_SCOPES = [
  'library:read', 'library:write', 'library:delete', 'media:read', 'media:write',
  'planning:write', 'device_actions:request', 'exports:read', 'imports:write',
  'community:read',
] as const;
export type McpScope = (typeof MCP_SCOPES)[number];
const SCOPE_SET: ReadonlySet<string> = new Set(MCP_SCOPES);

export const MCP_TOKEN_PREFIX = 'ptm_';
const TOKEN_PATTERN = /^ptm_[A-Za-z0-9_-]{43}$/;
export const MCP_MAX_GRANT_TTL_MS = 365 * 24 * 60 * 60 * 1000;
const LAST_USED_THROTTLE_MS = 60 * 1000;

export interface McpGrantRecord {
  id: string;
  userId: string;
  nickname: string;
  tokenHash: string;
  scopes: McpScope[];
  createdAt: number;
  expiresAt: number;
  revokedAt: number | null;
  lastUsedAt: number | null;
}

/** Grant metadata safe to show the owner: never the hash, never a token. */
export type McpGrantSummary = Omit<McpGrantRecord, 'tokenHash' | 'userId'>;

export interface McpPrincipal {
  grantId: string;
  userId: string;
  scopes: readonly McpScope[];
}

export interface McpGrantStore {
  insert(grant: McpGrantRecord): Promise<void>;
  byId(id: string): Promise<McpGrantRecord | undefined>;
  byTokenHash(tokenHash: string): Promise<McpGrantRecord | undefined>;
  listForUser(userId: string): Promise<McpGrantRecord[]>;
  revoke(id: string, userId: string, at: number): Promise<boolean>;
  revokeAll(userId: string, at: number): Promise<number>;
  touchLastUsed(id: string, at: number): Promise<void>;
}

interface GrantRow {
  id: string; user_id: string; nickname: string; token_hash: string;
  scopes: string[]; created_at: string; expires_at: string;
  revoked_at: string | null; last_used_at: string | null;
}
const toRecord = (row: GrantRow): McpGrantRecord => ({
  id: row.id, userId: row.user_id, nickname: row.nickname, tokenHash: row.token_hash,
  scopes: row.scopes.filter((s): s is McpScope => SCOPE_SET.has(s)),
  createdAt: Number(row.created_at), expiresAt: Number(row.expires_at),
  revokedAt: row.revoked_at === null ? null : Number(row.revoked_at),
  lastUsedAt: row.last_used_at === null ? null : Number(row.last_used_at),
});

export class PostgresMcpGrantStore implements McpGrantStore {
  private pool: Pool;
  constructor(pool: Pool) { this.pool = pool; }
  async insert(grant: McpGrantRecord) {
    await this.pool.query(
      'INSERT INTO auth.mcp_grants(id,user_id,nickname,token_hash,scopes,created_at,expires_at,revoked_at,last_used_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)',
      [grant.id, grant.userId, grant.nickname, grant.tokenHash, grant.scopes, grant.createdAt, grant.expiresAt, grant.revokedAt, grant.lastUsedAt]);
  }
  async byId(id: string) {
    const row = (await this.pool.query('SELECT * FROM auth.mcp_grants WHERE id=$1', [id])).rows[0] as GrantRow | undefined;
    return row ? toRecord(row) : undefined;
  }
  async byTokenHash(tokenHash: string) {
    const row = (await this.pool.query('SELECT * FROM auth.mcp_grants WHERE token_hash=$1', [tokenHash])).rows[0] as GrantRow | undefined;
    return row ? toRecord(row) : undefined;
  }
  async listForUser(userId: string) {
    const rows = (await this.pool.query('SELECT * FROM auth.mcp_grants WHERE user_id=$1 ORDER BY created_at DESC', [userId])).rows as GrantRow[];
    return rows.map(toRecord);
  }
  async revoke(id: string, userId: string, at: number) {
    const result = await this.pool.query(
      'UPDATE auth.mcp_grants SET revoked_at=$3 WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL', [id, userId, at]);
    return (result.rowCount ?? 0) > 0;
  }
  async revokeAll(userId: string, at: number) {
    const result = await this.pool.query(
      'UPDATE auth.mcp_grants SET revoked_at=$2 WHERE user_id=$1 AND revoked_at IS NULL', [userId, at]);
    return result.rowCount ?? 0;
  }
  async touchLastUsed(id: string, at: number) {
    await this.pool.query('UPDATE auth.mcp_grants SET last_used_at=$2 WHERE id=$1', [id, at]);
  }
}

/** The entitlement question M1 answers, isolated so M2 reuses it verbatim. */
export interface McpEntitlementLookup {
  state(userId: string): Promise<EntitlementState | undefined>;
}

/** Pro while the store entitlement is active or in its grace period — the
 * same states the native entitlement cache reports as entitled. Everything
 * else (pending, retry, paused, expired, cancelled, refunded, revoked,
 * none, unknown) denies MCP access. */
export function mcpProAccess(state: EntitlementState | 'unknown' | undefined): boolean {
  return state === 'active' || state === 'grace';
}

/** Adapts the subscriptions service without reimplementing billing: reads
 * the same verified entitlement cache the native entitlement route serves. */
export class SubscriptionsEntitlementLookup implements McpEntitlementLookup {
  private subscriptions: { cache(accountId: string, environment: StoreEnvironment): Promise<{ state: EntitlementState | 'unknown' }> };
  private environment: StoreEnvironment;
  constructor(subscriptions: { cache(accountId: string, environment: StoreEnvironment): Promise<{ state: EntitlementState | 'unknown' }> },
    environment: StoreEnvironment = 'production') { this.subscriptions = subscriptions; this.environment = environment; }
  async state(userId: string) {
    const record = await this.subscriptions.cache(userId, this.environment);
    return record.state === 'unknown' ? undefined : record.state;
  }
}

const summary = (grant: McpGrantRecord): McpGrantSummary => ({
  id: grant.id, nickname: grant.nickname, scopes: [...grant.scopes],
  createdAt: grant.createdAt, expiresAt: grant.expiresAt,
  revokedAt: grant.revokedAt, lastUsedAt: grant.lastUsedAt,
});

export class McpGrants {
  private store: McpGrantStore; private secrets: AuthSecrets;
  private entitlement: McpEntitlementLookup; private now: () => number;
  constructor(store: McpGrantStore, secrets: AuthSecrets,
    entitlement: McpEntitlementLookup, now: () => number = Date.now) {
    this.store = store; this.secrets = secrets; this.entitlement = entitlement; this.now = now;
  }

  /** Creates a grant and returns the token exactly once. Validation is
   * strict: scopes must be a non-empty duplicate-free subset of MCP_SCOPES
   * (no public-write, billing, credential or hardware scope exists in the
   * set at all), nickname 1..80 chars after trimming, TTL within the cap. */
  async create(userId: string, nickname: string, scopes: readonly string[], ttlMs: number):
    Promise<{ grant: McpGrantSummary; token: string }> {
    const name = nickname.trim();
    if (!name || name.length > 80) throw new Error('Invalid grant nickname');
    if (!scopes.length || scopes.length > MCP_SCOPES.length) throw new Error('Invalid grant scopes');
    const unique = [...new Set(scopes)];
    if (unique.length !== scopes.length || unique.some(s => !SCOPE_SET.has(s))) throw new Error('Invalid grant scopes');
    if (!Number.isSafeInteger(ttlMs) || ttlMs <= 0 || ttlMs > MCP_MAX_GRANT_TTL_MS) throw new Error('Invalid grant expiry');
    const createdAt = this.now();
    const token = MCP_TOKEN_PREFIX + randomBytes(32).toString('base64url');
    const grant: McpGrantRecord = {
      id: randomUUID(), userId, nickname: name,
      tokenHash: this.secrets.hash('mcp-token', token),
      scopes: unique as McpScope[], createdAt, expiresAt: createdAt + ttlMs,
      revokedAt: null, lastUsedAt: null,
    };
    await this.store.insert(grant);
    return { grant: summary(grant), token };
  }

  async list(userId: string): Promise<McpGrantSummary[]> {
    return (await this.store.listForUser(userId)).map(summary);
  }

  /** Owner-scoped revoke: a grant id from another account is a no-op miss,
   * never an error that leaks its existence. */
  async revoke(userId: string, grantId: string): Promise<boolean> {
    return this.store.revoke(grantId, userId, this.now());
  }

  async revokeAll(userId: string): Promise<number> {
    return this.store.revokeAll(userId, this.now());
  }

  /**
   * Resolves a presented token to a principal, or undefined. Every denial
   * is the same undefined: malformed, unknown, revoked, expired, and
   * entitlement-lost are indistinguishable to the caller. Entitlement is
   * checked on every call (issue #114 section 5: revocation applies to
   * every subsequent data call, upload chunk, queued action and replay —
   * and Pro loss disables MCP, never the user's own records).
   */
  async authenticate(token: string): Promise<McpPrincipal | undefined> {
    if (typeof token !== 'string' || !TOKEN_PATTERN.test(token)) return undefined;
    const grant = await this.store.byTokenHash(this.secrets.hash('mcp-token', token));
    if (!grant || grant.revokedAt !== null) return undefined;
    const now = this.now();
    if (grant.expiresAt <= now) return undefined;
    if (!mcpProAccess(await this.entitlement.state(grant.userId))) return undefined;
    if (grant.lastUsedAt === null || now - grant.lastUsedAt >= LAST_USED_THROTTLE_MS) {
      await this.store.touchLastUsed(grant.id, now);
    }
    return { grantId: grant.id, userId: grant.userId, scopes: grant.scopes };
  }
}

/** Scope gate for tool handlers (M2): a principal either holds the scope or
 * the call does not happen. Read/write/delete/media are separate scopes on
 * purpose — a read-only connection cannot be talked into a write. */
export function hasScope(principal: McpPrincipal, scope: McpScope): boolean {
  return principal.scopes.includes(scope);
}
