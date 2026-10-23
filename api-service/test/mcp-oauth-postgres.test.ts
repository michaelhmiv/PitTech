import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { Pool } from 'pg';
import { migrate } from '../src/migrate.ts';
import { AuthSecrets, credential } from '../src/auth-credentials.ts';
import { McpGrants, PostgresMcpGrantStore } from '../src/mcp-grants.ts';
import { McpOAuth, PostgresMcpOAuthStore } from '../src/mcp-oauth.ts';

// Real-Postgres OAuth lifecycle (issue #114, M1b): migrations 009+010,
// pending approval, code exchange, rotation and retired-hash family
// revocation against the actual schema. Gated like the other
// *-postgres suites.
const gate = { skip: !process.env.TEST_DATABASE_URL && process.env.REQUIRE_DB_TESTS !== '1' };
const CLIENT = { id: 'chatgpt', name: 'ChatGPT', redirectUris: ['https://chatgpt.com/connector/callback'] };
const VERIFIER = 'verifier-' + 'c'.repeat(40);
const CHALLENGE = createHash('sha256').update(VERIFIER).digest('base64url');

test('mcp oauth postgres lifecycle', gate, async () => {
  const source = process.env.TEST_DATABASE_URL; assert.ok(source);
  const base = new URL(source); assert.match(base.pathname, /^\/pittech_test[a-z_\d]*$/i);
  const pool = new Pool({ connectionString: source, max: 2 });
  try {
    await migrate(pool);
    const userId = (await pool.query(
      'INSERT INTO auth.users(id,email,created_at) VALUES($1,$2,$3) RETURNING id',
      [randomUUID(), `mcp-oauth-${randomUUID()}@example.invalid`, Date.now()])).rows[0].id as string;
    const secrets = new AuthSecrets(credential(), credential());
    const grants = new McpGrants(new PostgresMcpGrantStore(pool), secrets, { state: async () => 'active' }, () => Date.now());
    const oauth = new McpOAuth(new PostgresMcpOAuthStore(pool), grants, secrets, [CLIENT], () => Date.now());

    const pending = await oauth.startAuthorization({
      clientId: 'chatgpt', redirectUri: CLIENT.redirectUris[0], responseType: 'code',
      codeChallenge: CHALLENGE, codeChallengeMethod: 'S256', scope: 'library:read', state: 's',
    });
    // Nothing about the user code or poll token is stored in plaintext.
    const pendingRows = (await pool.query('SELECT * FROM auth.mcp_oauth_pending')).rows;
    assert.ok(!JSON.stringify(pendingRows).includes(pending.userCode.replace('-', '')));
    assert.ok(!JSON.stringify(pendingRows).includes(pending.pollToken));

    assert.equal(await oauth.approve(userId, pending.userCode), true);
    const status = await oauth.pollStatus(pending.pollToken);
    assert.equal(status.status, 'approved');
    if (status.status !== 'approved') return;
    const exchanged = await oauth.exchangeCode({ code: status.code, clientId: 'chatgpt', redirectUri: CLIENT.redirectUris[0], codeVerifier: VERIFIER });
    assert.ok(exchanged);
    assert.ok(await grants.authenticate(exchanged.access_token));

    // The grant row records its client and founds its family.
    const grantRow = (await pool.query('SELECT client_id, family_id FROM auth.mcp_grants WHERE user_id=$1', [userId])).rows[0];
    assert.equal(grantRow.client_id, 'chatgpt');
    assert.ok(grantRow.family_id);

    const rotated = await oauth.refresh({ refreshToken: exchanged.refresh_token, clientId: 'chatgpt' });
    assert.ok(rotated);
    const retired = (await pool.query('SELECT count(*)::int AS n FROM auth.mcp_oauth_retired_hashes')).rows[0];
    assert.ok(retired.n >= 1, 'rotation retires the old hash');
    // Replaying the retired token revokes the family in the real schema.
    assert.equal(await oauth.refresh({ refreshToken: exchanged.refresh_token, clientId: 'chatgpt' }), undefined);
    assert.equal(await grants.authenticate(rotated.access_token), undefined);
  } finally { await pool.end(); }
});
