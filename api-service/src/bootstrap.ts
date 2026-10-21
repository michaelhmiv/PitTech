import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { Pool, type PoolClient } from 'pg';
import { isCredential } from './auth-credentials.ts';
import { migrate } from './migrate.ts';

export const runtimeLogins = Object.freeze({
  api: { name: 'pittech_api_login', roles: ['pittech_relay_runtime', 'pittech_auth_runtime', 'pittech_publications_runtime', 'pittech_billing_runtime', 'pittech_community_aggregation_runtime'] },
  lifecycle: { name: 'pittech_lifecycle_login', roles: ['pittech_lifecycle_runtime'] },
  sync: { name: 'pittech_sync_login', roles: ['pittech_private_sync_runtime'] },
});
export type RuntimePasswords = Record<keyof typeof runtimeLogins, string>;
const operations = ['relay', 'auth', 'publications', 'lifecycle', 'private-sync', 'billing', 'community-aggregation'];

async function login(client: PoolClient, name: string, roles: string[], password: string) {
  const old = (await client.query('SELECT oid,rolcanlogin,rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls,rolinherit FROM pg_roles WHERE rolname=$1', [name])).rows[0];
  if (old) {
    if (!old.rolcanlogin || old.rolsuper || old.rolcreatedb || old.rolcreaterole || old.rolreplication || old.rolbypassrls || !old.rolinherit) throw new Error('Unsafe existing runtime login');
    const memberships = (await client.query('SELECT parent.rolname,m.admin_option,m.inherit_option FROM pg_auth_members m JOIN pg_roles parent ON parent.oid=m.roleid WHERE m.member=$1', [old.oid])).rows;
    if (memberships.some(row => !roles.includes(row.rolname) || row.admin_option || !row.inherit_option)) throw new Error('Unexpected runtime membership');
    const owned = await client.query('SELECT 1 FROM pg_class WHERE relowner=$1 UNION ALL SELECT 1 FROM pg_namespace WHERE nspowner=$1 UNION ALL SELECT 1 FROM pg_database WHERE datdba=$1 LIMIT 1', [old.oid]);
    if (owned.rowCount) throw new Error('Runtime login owns database objects');
    const direct = await client.query('SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(c.relacl) a WHERE a.grantee=$1 UNION ALL SELECT 1 FROM pg_namespace n CROSS JOIN LATERAL aclexplode(n.nspacl) a WHERE a.grantee=$1 UNION ALL SELECT 1 FROM pg_proc p CROSS JOIN LATERAL aclexplode(p.proacl) a WHERE a.grantee=$1 LIMIT 1', [old.oid]);
    if (direct.rowCount) throw new Error('Unexpected direct runtime privileges');
  } else {
    // Names are fixed above and credentials are canonical base64url. Never log
    // this statement or raw database errors, which can contain credentials.
    await client.query(`CREATE ROLE ${name} LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '${password}'`);
  }
  // Existing credentials are deliberately not rotated by an idempotent rerun.
  await client.query(`GRANT ${roles.join(', ')} TO ${name}`);
}

/** Administrator-only one-off setup. No administrator URL is given to the API
 * or cleanup service. Migrations retain their original names and hashes. */
export async function bootstrapRuntime(pool: Pool, passwords: RuntimePasswords) {
  if (!passwords || Object.keys(passwords).sort().join(',') !== 'api,lifecycle,sync' || !Object.values(passwords).every(isCredential) || new Set(Object.values(passwords)).size !== 3) throw new Error('Independent runtime credentials required');
  if ((pool.options.max ?? 10) < 2) throw new Error('Bootstrap requires two database connections');
  const client = await pool.connect();
  try {
    await client.query('SELECT pg_advisory_lock(890020)');
    await migrate(pool);
    await client.query('REVOKE CREATE ON SCHEMA public FROM PUBLIC');
    for (const operation of operations) await client.query(await readFile(new URL(`../operations/${operation}-runtime-role.sql`, import.meta.url), 'utf8'));
    const roleNames = Object.values(runtimeLogins).flatMap(definition => definition.roles);
    const inherited = await client.query('SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname=ANY($1::text[]) LIMIT 1', [roleNames]);
    if (inherited.rowCount) throw new Error('Unexpected runtime role inheritance');
    await client.query('BEGIN');
    try {
      for (const [kind, definition] of Object.entries(runtimeLogins)) await login(client, definition.name, definition.roles, passwords[kind as keyof RuntimePasswords]);
      await client.query('COMMIT');
    } catch (error) { await client.query('ROLLBACK'); throw error; }
  } catch (error) {
    await client.query('ROLLBACK'); throw error;
  } finally {
    try { await client.query('SELECT pg_advisory_unlock(890020)'); } finally { client.release(); }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  let pool: Pool | undefined;
  try {
    const url = process.env.BOOTSTRAP_DATABASE_URL;
    if (process.env.BOOTSTRAP_CONFIRM !== 'create-runtime-logins' || !url || !['postgres:', 'postgresql:'].includes(new URL(url).protocol)) throw new Error('Bootstrap configuration required');
    const passwords = { api: process.env.BOOTSTRAP_API_PASSWORD!, lifecycle: process.env.BOOTSTRAP_LIFECYCLE_PASSWORD!, sync: process.env.BOOTSTRAP_SYNC_PASSWORD! };
    pool = new Pool({ connectionString: url, max: 3, connectionTimeoutMillis: 10000, statement_timeout: 30000 });
    pool.on('error', () => console.error('runtime_bootstrap_connection_failed'));
    await bootstrapRuntime(pool, passwords);
    for (const [kind, definition] of Object.entries(runtimeLogins)) {
      const connection = new URL(url); connection.username = definition.name; connection.password = passwords[kind as keyof RuntimePasswords];
      const runtime = new Pool({ connectionString: connection.toString(), max: 1, connectionTimeoutMillis: 10000 });
      runtime.on('error', () => console.error('runtime_bootstrap_connection_failed'));
      try { const row = (await runtime.query('SELECT current_user')).rows[0]; if (row.current_user !== definition.name) throw new Error('Runtime login verification failed'); }
      finally { await runtime.end(); }
    }
    console.info('runtime_bootstrap_ready');
  } catch { console.error('runtime_bootstrap_failed'); process.exitCode = 1; }
  finally { await pool?.end(); }
}
