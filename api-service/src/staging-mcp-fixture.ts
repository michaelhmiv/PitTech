import { randomUUID } from 'node:crypto';
import type { Pool } from 'pg';
import { createRuntime } from './config.ts';
import { MCP_SCOPES } from './mcp-grants.ts';

/**
 * One-off staging fixture for the MCP acceptance pass (issue #114).
 * Mirrors the staging-readiness probe's safety pattern: it runs only
 * inside the staging Railway environment, only with an explicit
 * confirmation variable, and only against the staging online
 * environment. It is never wired into the API server.
 *
 * Actions (MCP_FIXTURE_ACTION):
 * - `setup` (default): idempotently create the two labeled fixture
 *   accounts, revoke their previous fixture grants, mint a fresh
 *   all-scopes grant for each, and print each grant token exactly
 *   once to stdout (the one-off service's deploy log, readable only
 *   by the project owner; the service is removed after the pass).
 *   Also proves the entitlement wiring by authenticating each token
 *   through the real McpGrants path.
 * - `check-owner`: report whether the owner account named by
 *   MCP_FIXTURE_OWNER_EMAIL exists on staging, its account id, and
 *   its store entitlement states (values only — no secrets).
 * - `delete-b`: acceptance row 12 against fixture account B — a real
 *   MCP write, then the provider-initiated deletion path, a refused
 *   post-deletion authentication, the deletion sweep, and residue
 *   counts for B plus an untouched check for account A.
 *
 * The fixture accounts are database identities only
 * (@staging.pittech.app is not a mail domain); they exist so the
 * acceptance pass never runs destructive steps on a real account.
 */

const STAGING_RAILWAY_ENVIRONMENT_ID = '66718bed-3e9f-4372-88d9-3e3969a3b59f';
const FIXTURE_ACCOUNTS = [
  { label: 'A', email: 'mcp-acceptance-a@staging.pittech.app' },
  { label: 'B', email: 'mcp-acceptance-b@staging.pittech.app' },
] as const;
const GRANT_TTL_MS = 7 * 24 * 60 * 60 * 1000;

function assert(value: unknown, message: string): asserts value { if (!value) throw new Error(message); }
function step(name: string) { console.info('fixture_step_' + name); }

type Runtime = ReturnType<typeof createRuntime>;

async function ensureAccount(pool: Pool, email: string): Promise<string> {
  const row = (await pool.query<{ id: string }>(
    'INSERT INTO auth.users(id,email,created_at) VALUES($1,$2,$3) ON CONFLICT(email) DO UPDATE SET email=EXCLUDED.email RETURNING id',
    [randomUUID(), email, Date.now()])).rows[0];
  return row.id;
}

async function freshGrant(runtime: Runtime, userId: string): Promise<string> {
  assert(runtime.mcp, 'MCP unavailable');
  await runtime.mcp.revokeAll(userId);
  const { token } = await runtime.mcp.create(userId, 'MCP acceptance fixture', MCP_SCOPES, GRANT_TTL_MS);
  return token;
}

async function setup(runtime: Runtime, pool: Pool) {
  assert(runtime.mcp, 'MCP unavailable');
  for (const account of FIXTURE_ACCOUNTS) {
    const userId = await ensureAccount(pool, account.email);
    const token = await freshGrant(runtime, userId);
    const principal = await runtime.mcp.authenticate(token);
    assert(principal, 'fixture grant did not authenticate for ' + account.label);
    step('account_' + account.label.toLowerCase() + '_ready');
    console.info('FIXTURE_ACCOUNT_' + account.label + '=' + userId);
    console.info('FIXTURE_GRANT_' + account.label + '=' + token);
  }
  step('setup_complete');
}

async function checkOwner(runtime: Runtime, pool: Pool) {
  const email = process.env.MCP_FIXTURE_OWNER_EMAIL;
  assert(email, 'MCP_FIXTURE_OWNER_EMAIL required');
  const row = (await pool.query<{ id: string; created_at: string }>('SELECT id, created_at FROM auth.users WHERE email=$1', [email])).rows[0];
  if (!row) { console.info('FIXTURE_OWNER_ACCOUNT=missing'); step('check_owner_complete'); return; }
  console.info('FIXTURE_OWNER_ACCOUNT=present');
  console.info('FIXTURE_OWNER_ACCOUNT_ID=' + row.id);
  if (runtime.subscriptions) {
    for (const environment of ['sandbox', 'production'] as const) {
      const cache = await runtime.subscriptions.cache(row.id, environment);
      console.info('FIXTURE_OWNER_ENTITLEMENT_' + environment.toUpperCase() + '=' + cache.state);
    }
  }
  step('check_owner_complete');
}

async function residue(pool: Pool, userId: string) {
  const count = async (sql: string) => Number(((await pool.query<{ n: string }>(sql, [userId])).rows[0] ?? { n: '0' }).n);
  return {
    users: await count('SELECT count(*)::text AS n FROM auth.users WHERE id=$1'),
    grants: await count('SELECT count(*)::text AS n FROM auth.mcp_grants WHERE user_id=$1'),
    journal: await count('SELECT count(*)::text AS n FROM auth.mcp_operation_journal WHERE user_id=$1'),
    exports: await count('SELECT count(*)::text AS n FROM auth.mcp_exports WHERE user_id=$1'),
    syncAccounts: await count('SELECT count(*)::text AS n FROM private_sync.accounts WHERE user_id=$1'),
    syncOperations: await count('SELECT count(*)::text AS n FROM private_sync.operations WHERE user_id=$1'),
  };
}

async function deleteB(runtime: Runtime, pool: Pool) {
  assert(runtime.mcp && runtime.mcpServer && runtime.deletion, 'fixture runtime incomplete');
  const b = FIXTURE_ACCOUNTS[1], a = FIXTURE_ACCOUNTS[0];
  const userB = await ensureAccount(pool, b.email);
  const userA = await ensureAccount(pool, a.email);
  const token = await freshGrant(runtime, userB);
  const principal = await runtime.mcp.authenticate(token);
  assert(principal, 'fixture grant did not authenticate for B');
  // A real MCP write through the real server handler, so the deletion
  // races a library that actually has MCP state in it.
  const write = await runtime.mcpServer.handle(principal, {
    jsonrpc: '2.0', id: 1, method: 'tools/call',
    params: { name: 'create_meal', arguments: { title: 'MCP acceptance row-12 meal', dishes: [{ name: 'Fixture brisket', foodType: 'beef' }] } },
  });
  assert(write && !('error' in write), 'fixture MCP write failed');
  const result = (write as { result?: { isError?: boolean } }).result;
  assert(!result?.isError, 'fixture MCP write returned an error');
  step('b_write_applied');
  const before = await residue(pool, userB);
  console.info('FIXTURE_B_RESIDUE_BEFORE=' + JSON.stringify(before));
  const receipt = await runtime.deletion.requestForAppleEvent({ operationId: randomUUID(), userId: userB });
  console.info('FIXTURE_B_DELETION_STATE=' + receipt.state);
  step('b_deletion_requested');
  const refused = await runtime.mcp.authenticate(token);
  console.info('FIXTURE_B_POST_DELETION_AUTH=' + (refused ? 'STILL_AUTHENTICATES' : 'refused'));
  for (let i = 0; i < 4; i++) await runtime.deletion.sweepDeletion();
  step('b_deletion_swept');
  const after = await residue(pool, userB);
  console.info('FIXTURE_B_RESIDUE_AFTER=' + JSON.stringify(after));
  const job = (await pool.query<{ state: string }>('SELECT state FROM moderation.deletions WHERE user_id=$1 ORDER BY created_at DESC LIMIT 1', [userB])).rows[0];
  console.info('FIXTURE_B_DELETION_JOB=' + (job?.state ?? 'missing'));
  const grantsA = Number(((await pool.query<{ n: string }>("SELECT count(*)::text AS n FROM auth.mcp_grants WHERE user_id=$1 AND revoked_at IS NULL", [userA])).rows[0]).n);
  console.info('FIXTURE_A_ACTIVE_GRANTS=' + grantsA);
  step('delete_b_complete');
}

let runtime: Runtime | undefined;
try {
  assert(process.env.RAILWAY_ENVIRONMENT_ID === STAGING_RAILWAY_ENVIRONMENT_ID, 'staging Railway environment required');
  assert(process.env.MCP_FIXTURE_CONFIRM === 'run-staging-mcp-fixture', 'MCP_FIXTURE_CONFIRM required');
  assert(process.env.ONLINE_ENVIRONMENT === 'staging', 'staging online environment required');
  runtime = createRuntime();
  const action = process.env.MCP_FIXTURE_ACTION ?? 'setup';
  if (action === 'setup') await setup(runtime, runtime.pool);
  else if (action === 'check-owner') await checkOwner(runtime, runtime.pool);
  else if (action === 'delete-b') await deleteB(runtime, runtime.pool);
  else throw new Error('Unknown MCP_FIXTURE_ACTION');
} catch (error) {
  console.error('staging_mcp_fixture_failed: ' + (error instanceof Error ? error.message : 'unknown'));
  process.exitCode = 1;
} finally {
  if (runtime) await runtime.close();
}
