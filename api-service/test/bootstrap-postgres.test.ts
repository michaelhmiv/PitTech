import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { Pool } from 'pg';
import { bootstrapRuntime, runtimeLogins, type RuntimePasswords } from '../src/bootstrap.ts';
import { credential } from '../src/auth-credentials.ts';

test('bootstrap rejects missing, malformed and reused runtime credentials before connecting', async () => {
  const pool = { connect: () => { throw new Error('Must not connect'); } } as unknown as Pool;
  const secret = credential();
  for (const passwords of [
    {}, { api: secret, lifecycle: secret, sync: credential() },
    { api: secret, lifecycle: credential(), sync: 'not-a-canonical-credential' },
    { api: secret, lifecycle: credential(), other: credential() },
  ]) await assert.rejects(bootstrapRuntime(pool, passwords as RuntimePasswords), /Independent runtime credentials required/);
});

const gate = { skip: !process.env.TEST_DATABASE_URL && process.env.REQUIRE_DB_TESTS !== '1' };
test('real Postgres bootstrap creates reusable isolated logins and refuses privilege escalation', gate, async () => {
  const source = process.env.TEST_DATABASE_URL; assert.ok(source);
  const base = new URL(source); assert.match(base.pathname, /^\/pittech_test[a-z_\d]*$/i);
  const control = new Pool({ connectionString: source });
  const database = 'pittech_test_bootstrap_' + randomUUID().replaceAll('-', '');
  await control.query(`CREATE DATABASE ${database}`); base.pathname = '/' + database;
  const admin = new Pool({ connectionString: base.toString(), max: 4 });
  const passwords = { api: credential(), lifecycle: credential(), sync: credential() };
  const pools: Pool[] = [];
  try {
    // Reproduce a database with the old PUBLIC schema-create default.
    await admin.query('GRANT CREATE ON SCHEMA public TO PUBLIC');
    await bootstrapRuntime(admin, passwords);
    const migrations = (await admin.query('SELECT name,sha256 FROM relay.schema_migrations ORDER BY name')).rows;
    assert.equal(migrations.length, 9);
    await bootstrapRuntime(admin, passwords);
    assert.deepEqual((await admin.query('SELECT name,sha256 FROM relay.schema_migrations ORDER BY name')).rows, migrations);
    const runtime = {} as Record<keyof typeof runtimeLogins, Pool>;
    for (const [kind, definition] of Object.entries(runtimeLogins)) {
      const url = new URL(base); url.username = definition.name; url.password = passwords[kind as keyof RuntimePasswords];
      const pool = new Pool({ connectionString: url.toString(), max: 1 }); pools.push(pool);
      runtime[kind as keyof typeof runtime] = pool;
      assert.equal((await pool.query('SELECT current_user')).rows[0].current_user, definition.name);
      const privileges = (await pool.query('SELECT rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls FROM pg_roles WHERE rolname=current_user')).rows[0];
      assert.ok(Object.values(privileges).every(value => value === false));
      for (const sql of ['CREATE TABLE public.forbidden(id integer)', 'SELECT 1 FROM relay.schema_migrations']) await assert.rejects(pool.query(sql), { code: '42501' });
    }
    for (const table of ['relay.packs', 'auth.users', 'publications.items', 'billing.entitlements', 'community_aggregation.job_runs']) await runtime.api.query(`SELECT 1 FROM ${table} LIMIT 1`);
    await runtime.lifecycle.query('SELECT 1 FROM moderation.deletions LIMIT 1');
    await runtime.lifecycle.query('SELECT 1 FROM private_sync.accounts LIMIT 1');
    await runtime.sync.query('SELECT 1 FROM private_sync.accounts LIMIT 1');
    for (const sql of ['SELECT 1 FROM private_sync.accounts', 'SELECT 1 FROM moderation.operators', 'SET ROLE pittech_lifecycle_runtime']) await assert.rejects(runtime.api.query(sql), { code: '42501' });
    await assert.rejects(runtime.sync.query('SELECT 1 FROM auth.users'), { code: '42501' });
    await assert.rejects(runtime.lifecycle.query('SELECT 1 FROM billing.transactions'), { code: '42501' });

    await admin.query('ALTER ROLE pittech_api_login CREATEDB');
    await assert.rejects(bootstrapRuntime(admin, passwords), /Unsafe existing runtime login/);
    await admin.query('ALTER ROLE pittech_api_login NOCREATEDB');
    await admin.query('GRANT pittech_lifecycle_runtime TO pittech_api_login');
    await assert.rejects(bootstrapRuntime(admin, passwords), /Unexpected runtime membership/);
    await admin.query('REVOKE pittech_lifecycle_runtime FROM pittech_api_login');
    await admin.query('GRANT SELECT ON private_sync.accounts TO pittech_api_login');
    await assert.rejects(bootstrapRuntime(admin, passwords), /Unexpected direct runtime privileges/);
    await admin.query('REVOKE SELECT ON private_sync.accounts FROM pittech_api_login');
    await admin.query('CREATE SCHEMA bootstrap_owned AUTHORIZATION pittech_api_login');
    await assert.rejects(bootstrapRuntime(admin, passwords), /Runtime login owns database objects/);
    await admin.query('DROP SCHEMA bootstrap_owned');
    await bootstrapRuntime(admin, passwords);
  } finally {
    await Promise.all(pools.map(pool => pool.end()));
    for (const definition of Object.values(runtimeLogins)) {
      if ((await admin.query('SELECT 1 FROM pg_roles WHERE rolname=$1', [definition.name])).rowCount) {
        await admin.query(`DROP OWNED BY ${definition.name}`);
        await admin.query(`DROP ROLE ${definition.name}`);
      }
    }
    await admin.end(); await control.query(`DROP DATABASE ${database}`); await control.end();
  }
});
