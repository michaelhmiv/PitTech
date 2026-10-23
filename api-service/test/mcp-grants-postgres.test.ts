import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { Pool } from 'pg';
import { migrate } from '../src/migrate.ts';
import { AuthSecrets, credential } from '../src/auth-credentials.ts';
import { McpGrants, PostgresMcpGrantStore } from '../src/mcp-grants.ts';

// Real-Postgres lifecycle for MCP grants (issue #114, M1), gated exactly
// like the other *-postgres suites: skipped without TEST_DATABASE_URL,
// required in CI via REQUIRE_DB_TESTS.
const gate = { skip: !process.env.TEST_DATABASE_URL && process.env.REQUIRE_DB_TESTS !== '1' };

test('mcp grants postgres lifecycle', gate, async () => {
  const source = process.env.TEST_DATABASE_URL; assert.ok(source);
  const base = new URL(source); assert.match(base.pathname, /^\/pittech_test[a-z_\d]*$/i);
  const pool = new Pool({ connectionString: source, max: 2 });
  try {
    await migrate(pool);
    const email = `mcp-${randomUUID()}@example.invalid`;
    const userId = (await pool.query(
      'INSERT INTO auth.users(id,email,created_at) VALUES($1,$2,$3) RETURNING id',
      [randomUUID(), email, Date.now()])).rows[0].id as string;
    const secrets = new AuthSecrets(credential(), credential());
    const grants = new McpGrants(new PostgresMcpGrantStore(pool), secrets,
      { state: async () => 'active' }, () => Date.now());

    const { grant, token } = await grants.create(userId, 'ChatGPT', ['library:read', 'library:write'], 86_400_000);
    // The stored row holds the keyed hash, never the token.
    const row = (await pool.query('SELECT token_hash FROM auth.mcp_grants WHERE id=$1', [grant.id])).rows[0];
    assert.equal(row.token_hash, secrets.hash('mcp-token', token));
    assert.ok(!JSON.stringify(row).includes(token));

    const principal = await grants.authenticate(token);
    assert.equal(principal?.userId, userId);
    assert.deepEqual(principal?.scopes, ['library:read', 'library:write']);
    const used = (await pool.query('SELECT last_used_at FROM auth.mcp_grants WHERE id=$1', [grant.id])).rows[0];
    assert.ok(Number(used.last_used_at) > 0);

    assert.equal(await grants.revoke(userId, grant.id), true);
    assert.equal(await grants.authenticate(token), undefined);

    // Deleting the account removes its grants (explicit deletion coverage
    // in account-deletion plus the ON DELETE CASCADE backstop).
    const again = await grants.create(userId, 'Muse', ['community:read'], 86_400_000);
    await pool.query('DELETE FROM auth.users WHERE id=$1', [userId]);
    assert.equal((await grants.list(userId)).length, 0);
    assert.equal(await grants.authenticate(again.token), undefined);
  } finally { await pool.end(); }
});
