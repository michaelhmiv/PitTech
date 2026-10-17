import { Pool } from 'pg';
import { readFile, readdir } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { digest } from './core.ts';

export async function migrate(pool: Pool) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN'); await client.query('SELECT pg_advisory_xact_lock(890000)');
    await client.query('CREATE SCHEMA IF NOT EXISTS relay');
    await client.query('CREATE TABLE IF NOT EXISTS relay.schema_migrations(name text PRIMARY KEY,sha256 text NOT NULL)');
    const root = new URL('../migrations/', import.meta.url);
    for (const name of (await readdir(root)).filter(n => /^\d+_[a-z_]+\.sql$/.test(n)).sort()) {
      const sql = await readFile(new URL(name, root), 'utf8'), sha = digest(sql);
      const prior = (await client.query('SELECT sha256 FROM relay.schema_migrations WHERE name=$1', [name])).rows[0];
      if (prior && prior.sha256 !== sha) throw new Error('Migration changed');
      if (!prior) { await client.query(sql); await client.query('INSERT INTO relay.schema_migrations VALUES($1,$2)', [name, sha]); }
    }
    await client.query('COMMIT');
  } catch (error) { await client.query('ROLLBACK'); throw error; } finally { client.release(); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const url = process.env.MIGRATION_DATABASE_URL ?? (process.env.NODE_ENV !== 'production' ? process.env.DATABASE_URL : undefined);
  if (!url) { console.error('migration_configuration_required'); process.exitCode = 1; }
  else { const pool = new Pool({ connectionString: url, connectionTimeoutMillis: 5000 }); try { await migrate(pool); console.info('migrations_applied'); } catch { console.error('migration_failed'); process.exitCode = 1; } finally { await pool.end(); } }
}
