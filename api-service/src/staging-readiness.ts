import { createRuntime } from './config.ts';
import { Pool } from 'pg';
import { S3Storage } from './storage.ts';
import { DeleteObjectCommand } from '@aws-sdk/client-s3';
import { randomUUID } from 'node:crypto';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Private, one-off foundation probe. Never prints credentials, object keys or provider diagnostics.
let runtime: ReturnType<typeof createRuntime> | undefined, directory: string | undefined, step = 'configuration';
const extraPools: Pool[] = [], objects: string[] = [];
function assert(value: unknown): asserts value { if (!value) throw new Error('Probe check failed'); }
async function denied(work: () => Promise<unknown>, messages: string[]) {
  try { await work(); } catch (error) { assert(error instanceof Error && messages.includes(error.message)); return; }
  throw new Error('Expected denial');
}
try {
  assert(process.env.RAILWAY_ENVIRONMENT_ID === '66718bed-3e9f-4372-88d9-3e3969a3b59f');
  assert(process.env.READINESS_CONFIRM === 'verify-staging-foundation');
  for (const flag of ['RELAY_UPLOADS_ENABLED', 'AUTH_ENABLED', 'ACCOUNT_LIFECYCLE_ENABLED', 'PUBLIC_API_ENABLED', 'PRIVATE_SYNC_ENABLED', 'SUBSCRIPTIONS_ENABLED', 'COMMUNITY_AGGREGATION_ENABLED']) assert(process.env[flag] !== 'true');
  runtime = createRuntime(); assert(runtime.relay.storage instanceof S3Storage);
  const lifecycle = new Pool({ connectionString: process.env.LIFECYCLE_DATABASE_URL, max: 1, connectionTimeoutMillis: 5000, statement_timeout: 5000 });
  const sync = new Pool({ connectionString: process.env.PRIVATE_SYNC_DATABASE_URL, max: 1, connectionTimeoutMillis: 5000, statement_timeout: 5000 });
  extraPools.push(lifecycle, sync);
  for (const pool of extraPools) pool.on('error', () => console.error('readiness_database_connection_failed'));
  for (const { label, pool, name, table, forbidden } of [
    { label: 'api', pool: runtime.pool, name: 'pittech_api_login', table: 'relay.packs', forbidden: 'moderation.operators' },
    { label: 'lifecycle', pool: lifecycle, name: 'pittech_lifecycle_login', table: 'moderation.deletions', forbidden: 'billing.transactions' },
    { label: 'sync', pool: sync, name: 'pittech_sync_login', table: 'private_sync.accounts', forbidden: 'auth.users' },
  ]) {
    step = 'database_' + label;
    assert((await pool.query('SELECT current_user')).rows[0].current_user === name);
    await pool.query('SELECT 1 FROM ' + table + ' LIMIT 1');
    let rejected = false;
    try { await pool.query('SELECT 1 FROM ' + forbidden + ' LIMIT 1'); }
    catch (error) { assert((error as { code?: string }).code === '42501'); rejected = true; }
    assert(rejected); await pool.query('SELECT 1'); console.info(step + '_ready');
  }
  directory = await mkdtemp(join(tmpdir(), 'pittech-staging-readiness-'));
  const first = join(directory, 'first'), different = join(directory, 'different');
  const bytes = Buffer.from('PitTech isolated staging storage readiness, no account data.');
  await writeFile(first, bytes, { mode: 0o600 }); await writeFile(different, Buffer.from('Different probe body'), { mode: 0o600 });
  const storage = runtime.relay.storage;
  for (const namespace of ['relay', 'publications', 'private-sync']) {
    const key = namespace + '/' + randomUUID() + '.pittech'; objects.push(key);
    step = namespace + '_create_read'; await storage.put(key, first); assert((await storage.get(key)).equals(bytes));
    step = namespace + '_private';
    const endpoint = new URL(process.env.S3_ENDPOINT!); endpoint.hostname = process.env.S3_BUCKET + '.' + endpoint.hostname; endpoint.pathname = '/' + key;
    const response = await fetch(endpoint, { redirect: 'error', signal: AbortSignal.timeout(10000) });
    await response.body?.cancel(); assert(response.status === 403 || response.status === 404);
    if (namespace !== 'relay') {
      step = namespace + '_replay_fence'; await storage.put(key, first);
      await denied(() => storage.put(key, different), ['Object already exists']); assert((await storage.get(key)).equals(bytes));
    }
    step = namespace + '_delete'; await storage.delete(key);
    if (namespace !== 'relay') {
      await denied(() => storage.get(key), ['Unavailable object']);
      await denied(() => storage.put(key, first), ['Unavailable object', 'Object already exists']);
      await storage.delete(key);
    } else {
      let missing = false;
      try { await storage.get(key); } catch (error) { assert((error as { $metadata?: { httpStatusCode?: number } }).$metadata?.httpStatusCode === 404); missing = true; }
      assert(missing);
    }
    console.info(namespace + '_storage_ready');
  }
  console.info('staging_foundation_readiness_ready');
} catch { console.error(JSON.stringify({ code: 'staging_foundation_readiness_failed', step })); process.exitCode = 1; }
finally {
  if (runtime?.relay.storage instanceof S3Storage) {
    // Only this process's fresh random probe keys are removed. Real create-only deletion fences are never removed.
    for (const key of objects) {
      try { await runtime.relay.storage.client.send(new DeleteObjectCommand({ Bucket: runtime.relay.storage.bucket, Key: key }), { abortSignal: AbortSignal.timeout(10000) }); }
      catch { console.error('readiness_probe_cleanup_deferred'); process.exitCode = 1; }
    }
  }
  await Promise.allSettled(extraPools.map(pool => pool.end()));
  if (runtime) await runtime.close();
  if (directory) await rm(directory, { recursive: true, force: true });
}
