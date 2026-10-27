import type { Pool } from 'pg';
import type { EntitlementState } from './subscriptions.ts';
import type { McpEntitlementLookup } from './mcp-grants.ts';

/**
 * Staging acceptance support (issue #114): accounts whose email is listed
 * in MCP_TEST_PRO_EMAILS are treated as PitTech Pro for MCP entitlement
 * checks. The store subscription products are deliberately parked in
 * DRAFT, so no store sandbox path can grant Pro to a staging account —
 * without this, no human or AI client can ever exercise MCP against
 * staging and the acceptance pass cannot run. The wiring lives in
 * config.ts and is reachable only when ONLINE_ENVIRONMENT=staging:
 * setting the variable in production refuses the boot (see
 * parseMcpTestProEmails). This is a test fixture, not a billing path:
 * it never writes entitlement records, and the real store lookup stays
 * the fallback for every unlisted account.
 */

const EMAIL_SHAPE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

/** Parses MCP_TEST_PRO_EMAILS. Returns the normalized, de-duplicated
 * list. A non-empty list outside staging is a boot error, not a silent
 * ignore: a leaked variable must never quietly grant Pro in production. */
export function parseMcpTestProEmails(raw: string | undefined, environment: 'production' | 'staging'): string[] {
  const emails = (raw ?? '').split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
  if (!emails.length) return [];
  if (environment !== 'staging') throw new Error('MCP_TEST_PRO_EMAILS is staging-only');
  if (emails.some(e => !EMAIL_SHAPE.test(e))) throw new Error('Invalid MCP_TEST_PRO_EMAILS');
  return [...new Set(emails)];
}

/** Entitlement lookup decorator: configured test accounts answer
 * 'active'; every other account delegates to the real store lookup.
 * The account's email is resolved per call from auth.users (the same
 * pool the grants service already uses). A failed resolution falls
 * through to the real lookup — the fixture fails closed. */
export class TestProEntitlementLookup implements McpEntitlementLookup {
  private pool: Pick<Pool, 'query'>;
  private emails: ReadonlySet<string>;
  private inner: McpEntitlementLookup;
  constructor(pool: Pick<Pool, 'query'>, emails: readonly string[], inner: McpEntitlementLookup) {
    this.pool = pool; this.emails = new Set(emails.map(e => e.toLowerCase())); this.inner = inner;
  }
  async state(userId: string): Promise<EntitlementState | undefined> {
    try {
      const row = (await this.pool.query('SELECT email FROM auth.users WHERE id=$1', [userId])).rows[0] as { email: string } | undefined;
      if (row && this.emails.has(row.email.toLowerCase())) return 'active';
    } catch { /* fail closed: delegate to the real lookup below */ }
    return this.inner.state(userId);
  }
}
