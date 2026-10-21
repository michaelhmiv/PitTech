import { test, after, type TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { credential } from '../src/auth-credentials.ts';
import { createRuntime } from '../src/config.ts';
import { privateLibraryEnvelope } from '../src/sync-projection.ts';
import { PrivatePhotos } from '../src/sync-photos.ts';
import { PrivateSnapshots, type SyncStorageLimits } from '../src/sync-snapshots.ts';
import { applePublicPem, appleConfig, googleConfig, oidcAudience, oidcEmail, oidcPublicJwk, rtdnBody, signOidc } from './billing-fixtures.ts';

const directory = await mkdtemp(join(tmpdir(), 'pittech-sync-config-'));
after(() => rm(directory, { recursive: true, force: true }));

const base: Record<string, string> = {
  RATE_HMAC_KEY: 'x'.repeat(32),
  DATABASE_URL: 'postgres://localhost:5432/pittech',
  STORAGE_MAX_BYTES: String(1024 ** 3),
  DAILY_UPLOAD_MAX_BYTES: String(1024 ** 3),
  DAILY_UPLOAD_MAX_COUNT: '1000',
  OBJECT_STORAGE: 'filesystem',
  OBJECT_DIRECTORY: directory,
  NODE_ENV: 'test',
  PORT: '3000',
  APPLE_APP_IDS: '',
  ANDROID_APP_LINK_CERTIFICATES: '',
  TRUSTED_PROXY_CIDRS: '',
};
const quiet: Record<string, string | undefined> = {
  ONLINE_ENVIRONMENT: undefined,
  ANDROID_APP_LINK_PACKAGE: undefined,
  APPLE_APP_STORE_URL: undefined,
  AUTH_ENABLED: undefined,
  AUTH_HASH_KEY: undefined,
  AUTH_ENCRYPTION_KEY: undefined,
  RESEND_API_KEY: undefined,
  AUTH_FROM: undefined,
  AUTH_PUBLIC_ORIGIN: undefined,
  AUTH_DAILY_EMAIL_LIMIT: undefined,
  PUBLIC_API_ENABLED: undefined,
  ACCOUNT_LIFECYCLE_ENABLED: undefined,
  LIFECYCLE_DATABASE_URL: undefined,
  LIFECYCLE_HASH_KEY: undefined,
  LIFECYCLE_ENCRYPTION_KEY: undefined,
  PRIVATE_SYNC_ENABLED: undefined,
  PRIVATE_SYNC_DATABASE_URL: undefined,
  PRIVATE_SYNC_CURRENT_KEY_ID: undefined,
  PRIVATE_SYNC_ENCRYPTION_KEYS: undefined,
  PRIVATE_SYNC_LIMIT_OWNER_BYTES: undefined,
  PRIVATE_SYNC_LIMIT_GLOBAL_BYTES: undefined,
  PRIVATE_SYNC_LIMIT_DAILY_BYTES: undefined,
  PRIVATE_SYNC_LIMIT_DAILY_UPLOADS: undefined,
  PRIVATE_SYNC_LIMIT_OWNER_OPERATIONS: undefined,
};
function withEnv(t: TestContext, vars: Record<string, string | undefined>) {
  const saved = new Map<string, string | undefined>();
  for (const [key, value] of Object.entries(vars)) {
    if (!saved.has(key)) saved.set(key, process.env[key]);
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
  t.after(() => {
    for (const [key, value] of saved) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  });
}
function enabled() {
  const secrets = { authHash: credential(), authEnc: credential(), keyA: credential(), keyB: credential() };
  const env: Record<string, string | undefined> = {
    ...quiet,
    ...base,
    AUTH_ENABLED: 'true',
    AUTH_HASH_KEY: secrets.authHash,
    AUTH_ENCRYPTION_KEY: secrets.authEnc,
    RESEND_API_KEY: 're_synthetic_test_key',
    AUTH_FROM: 'signin@auth.pittech.app',
    AUTH_PUBLIC_ORIGIN: 'https://pittech.app',
    AUTH_DAILY_EMAIL_LIMIT: '100',
    PRIVATE_SYNC_ENABLED: 'true',
    PRIVATE_SYNC_DATABASE_URL: 'postgres://localhost:5432/pittech_sync',
    PRIVATE_SYNC_CURRENT_KEY_ID: 'test-key-1',
    PRIVATE_SYNC_ENCRYPTION_KEYS: JSON.stringify({ 'test-key-1': secrets.keyA, 'test-key-2': secrets.keyB }),
  };
  return { env, secrets };
}
function swap(key: string, value: string | undefined, pattern: RegExp) {
  const saved = process.env[key];
  if (value === undefined) delete process.env[key];
  else process.env[key] = value;
  try { assert.throws(() => createRuntime(), pattern); }
  finally {
    if (saved === undefined) delete process.env[key];
    else process.env[key] = saved;
  }
}

test('enabled billing requires a configured push identity and refreshes the production JWKS adapter', async t => {
  withEnv(t, {
    ...enabled().env,
    SUBSCRIPTIONS_ENABLED: 'true',
    PRO_APPLE_BUNDLE_ID: appleConfig.bundleId, PRO_APPLE_PRODUCT_ID: appleConfig.productId,
    PRO_APPLE_TRUST_MODE: 'pinned', PRO_APPLE_PINNED_KEYS: JSON.stringify({ synthetic: applePublicPem }),
    PRO_APPLE_ISSUER_ID: 'synthetic-issuer', PRO_APPLE_KEY_ID: 'synthetic-key', PRO_APPLE_PRIVATE_KEY: 'unused-synthetic-key',
    PRO_GOOGLE_PACKAGE: googleConfig.packageName, PRO_GOOGLE_PRODUCT_ID: googleConfig.productId,
    PRO_GOOGLE_SERVICE_ACCOUNT: JSON.stringify({ client_email: 'synthetic@test.iam.gserviceaccount.com', private_key: 'unused-synthetic-key' }),
    PRO_GOOGLE_RTDN_AUDIENCE: oidcAudience, PRO_GOOGLE_RTDN_SERVICE_ACCOUNT_EMAIL: oidcEmail,
  });
  swap('PRO_GOOGLE_RTDN_SERVICE_ACCOUNT_EMAIL', undefined, /Missing configuration/);
  swap('PRO_GOOGLE_RTDN_SERVICE_ACCOUNT_EMAIL', 'not-a-push-service-account', /Invalid Pub\/Sub push identity/);
  const calls: string[] = [];
  t.mock.method(globalThis, 'fetch', async (url: unknown) => {
    calls.push(String(url));
    return Response.json({ keys: [oidcPublicJwk] }, { headers: { 'Cache-Control': 'max-age=3600' } });
  });
  const runtime = createRuntime();
  try {
    await runtime.refreshOidcKeys!();
    assert.deepEqual(calls, ['https://www.googleapis.com/oauth2/v3/certs']);
    await assert.rejects(runtime.subscriptions!.rtdnPush(
      rtdnBody('synthetic-wrong-push-identity', { packageName: googleConfig.packageName, testNotification: {} }),
      `Bearer ${signOidc({ email: 'wrong@synthetic.iam.gserviceaccount.com' }, Date.now())}`,
    ), { code: 'notification_rejected' });
  } finally { await runtime.close(); }
});

test('private sync stays disabled by default with no services or routes', async t => {
  withEnv(t, { ...quiet, ...base });
  const runtime = createRuntime();
  try {
    assert.equal(runtime.sync, undefined);
    assert.equal(runtime.syncPool, undefined);
    assert.equal(runtime.options.sync, undefined);
  } finally { await runtime.close(); }
});

test('private sync ignores sync settings unless explicitly enabled', async t => {
  withEnv(t, {
    ...quiet, ...base,
    PRIVATE_SYNC_ENABLED: 'false',
    PRIVATE_SYNC_DATABASE_URL: 'not-a-url',
    PRIVATE_SYNC_ENCRYPTION_KEYS: '{invalid',
    PRIVATE_SYNC_CURRENT_KEY_ID: 'x',
    PRIVATE_SYNC_LIMIT_OWNER_BYTES: 'nope',
  });
  const runtime = createRuntime();
  try {
    assert.equal(runtime.sync, undefined);
    assert.equal(runtime.syncPool, undefined);
    assert.equal(runtime.options.sync, undefined);
  } finally { await runtime.close(); }
});

test('private sync startup requires authentication', async t => {
  const { env } = enabled();
  withEnv(t, { ...env, AUTH_ENABLED: undefined });
  assert.throws(() => createRuntime(), /Accounts required/);
});

test('private sync startup requires a dedicated postgres URL', async t => {
  const { env } = enabled();
  withEnv(t, env);
  swap('PRIVATE_SYNC_DATABASE_URL', undefined, /Missing configuration/);
  swap('PRIVATE_SYNC_DATABASE_URL', 'https://example.com/db', /Invalid private sync database URL/);
});

test('private sync startup requires provisioned encryption keys', async t => {
  const { env, secrets } = enabled();
  withEnv(t, env);
  swap('PRIVATE_SYNC_ENCRYPTION_KEYS', undefined, /Missing configuration/);
  swap('PRIVATE_SYNC_ENCRYPTION_KEYS', '{invalid', /Invalid private sync encryption configuration/);
  swap('PRIVATE_SYNC_ENCRYPTION_KEYS', '"just-a-string"', /Invalid private sync encryption configuration/);
  swap('PRIVATE_SYNC_ENCRYPTION_KEYS', '["array"]', /Invalid private sync encryption configuration/);
  swap('PRIVATE_SYNC_ENCRYPTION_KEYS', JSON.stringify({ 'test-key-1': secrets.keyA, other: 42 }), /Invalid private sync encryption configuration/);
  swap('PRIVATE_SYNC_CURRENT_KEY_ID', undefined, /Missing configuration/);
  swap('PRIVATE_SYNC_CURRENT_KEY_ID', 'missing-key', /Private sync encryption configuration unavailable/);
  swap('PRIVATE_SYNC_ENCRYPTION_KEYS', JSON.stringify({ a: secrets.keyA, b: secrets.keyB, c: credential(), d: credential() }), /Private sync encryption configuration unavailable/);
});

test('private sync keys must differ from auth and lifecycle keys', async t => {
  const { env, secrets } = enabled();
  const lifecycleHash = credential(), lifecycleEnc = credential();
  withEnv(t, {
    ...env,
    ACCOUNT_LIFECYCLE_ENABLED: 'true',
    LIFECYCLE_DATABASE_URL: 'postgres://localhost:5432/pittech_lifecycle',
    LIFECYCLE_HASH_KEY: lifecycleHash,
    LIFECYCLE_ENCRYPTION_KEY: lifecycleEnc,
  });
  swap('PRIVATE_SYNC_ENCRYPTION_KEYS', JSON.stringify({ 'test-key-1': secrets.authHash, 'test-key-2': secrets.keyB }), /Separate private sync keys required/);
  swap('PRIVATE_SYNC_ENCRYPTION_KEYS', JSON.stringify({ 'test-key-1': secrets.authEnc }), /Separate private sync keys required/);
  swap('PRIVATE_SYNC_ENCRYPTION_KEYS', JSON.stringify({ 'test-key-1': lifecycleHash }), /Separate private sync keys required/);
  swap('PRIVATE_SYNC_ENCRYPTION_KEYS', JSON.stringify({ 'test-key-1': secrets.keyA, 'test-key-2': lifecycleEnc }), /Separate private sync keys required/);
});

test('private sync limits default to the photo-capable test values', async t => {
  const { env } = enabled();
  withEnv(t, env);
  const runtime = createRuntime();
  try {
    const sync = runtime.sync; assert.ok(sync);
    assert.deepEqual((sync.snapshots as unknown as { limits: SyncStorageLimits }).limits,
      { ownerBytes: 64 * 1024 ** 2, globalBytes: 128 * 1024 ** 2, dailyBytes: 256 * 1024 ** 2, dailyUploads: 200, ownerOperations: 100 });
    assert.deepEqual((sync.photos as unknown as { limits: SyncStorageLimits }).limits,
      { ownerBytes: 64 * 1024 ** 2, globalBytes: 128 * 1024 ** 2, dailyBytes: 256 * 1024 ** 2, dailyUploads: 200, ownerOperations: 100 });
  } finally { await runtime.close(); }
});

test('private sync honors explicit limits and rejects invalid overrides', async t => {
  const { env } = enabled();
  withEnv(t, {
    ...env,
    PRIVATE_SYNC_LIMIT_OWNER_BYTES: '11',
    PRIVATE_SYNC_LIMIT_GLOBAL_BYTES: '12',
    PRIVATE_SYNC_LIMIT_DAILY_BYTES: '13',
    PRIVATE_SYNC_LIMIT_DAILY_UPLOADS: '14',
    PRIVATE_SYNC_LIMIT_OWNER_OPERATIONS: '15',
  });
  const runtime = createRuntime();
  try {
    const sync = runtime.sync; assert.ok(sync);
    assert.deepEqual((sync.snapshots as unknown as { limits: SyncStorageLimits }).limits,
      { ownerBytes: 11, globalBytes: 12, dailyBytes: 13, dailyUploads: 14, ownerOperations: 15 });
  } finally { await runtime.close(); }
  for (const name of ['PRIVATE_SYNC_LIMIT_OWNER_BYTES', 'PRIVATE_SYNC_LIMIT_GLOBAL_BYTES', 'PRIVATE_SYNC_LIMIT_DAILY_BYTES', 'PRIVATE_SYNC_LIMIT_DAILY_UPLOADS', 'PRIVATE_SYNC_LIMIT_OWNER_OPERATIONS']) swap(name, '0', /Invalid limit/);
  swap('PRIVATE_SYNC_LIMIT_OWNER_BYTES', '-5', /Invalid limit/);
  swap('PRIVATE_SYNC_LIMIT_DAILY_UPLOADS', '1.5', /Invalid limit/);
  swap('PRIVATE_SYNC_LIMIT_GLOBAL_BYTES', 'abc', /Invalid limit/);
  swap('PRIVATE_SYNC_LIMIT_OWNER_OPERATIONS', '9007199254740992', /Invalid limit/);
});

test('private sync constructs production services on the dedicated pool with the strict projector', async t => {
  const { env } = enabled();
  withEnv(t, {
    ...env,
    ACCOUNT_LIFECYCLE_ENABLED: 'true',
    LIFECYCLE_DATABASE_URL: 'postgres://localhost:5432/pittech_lifecycle',
    LIFECYCLE_HASH_KEY: credential(),
    LIFECYCLE_ENCRYPTION_KEY: credential(),
  });
  const runtime = createRuntime();
  try {
    const sync = runtime.sync; assert.ok(sync);
    const pool = runtime.syncPool; assert.ok(pool);
    assert.ok(sync.snapshots instanceof PrivateSnapshots);
    assert.ok(sync.photos instanceof PrivatePhotos);
    assert.equal(runtime.options.sync, sync);
    assert.equal((sync.snapshots as unknown as { pool: unknown }).pool, pool);
    assert.equal((sync.photos as unknown as { pool: unknown }).pool, pool);
    assert.notEqual(pool, runtime.pool);
    assert.notEqual(pool, runtime.lifecyclePool);
    assert.equal((sync.snapshots as unknown as { storage: unknown }).storage, runtime.relay.storage);
    assert.equal((sync.photos as unknown as { storage: unknown }).storage, runtime.relay.storage);
    assert.equal((sync.snapshots as unknown as { project: unknown }).project, privateLibraryEnvelope);
    assert.equal((sync.snapshots as unknown as { encryption: unknown }).encryption, (sync.photos as unknown as { encryption: unknown }).encryption);
  } finally { await runtime.close(); }
});

test('staging runtime refuses production authentication origins before opening pools', t => {
  withEnv(t, { ...enabled().env, ONLINE_ENVIRONMENT: 'staging' });
  assert.throws(() => createRuntime(), /Authentication origin does not match online environment/);
  process.env.AUTH_PUBLIC_ORIGIN = 'https://staging.pittech.app';
  process.env.AUTH_FROM = 'signin@auth-staging.pittech.app';
  const runtime = createRuntime();
  t.after(() => runtime.close());
  assert.equal(runtime.options.links.online?.environment, 'staging');
  assert.equal(runtime.options.links.androidPackageName, 'com.pittech.dev');
});

test('provider sign-in stays off unless deployment audiences are configured', async t => {
  const { env } = enabled();
  withEnv(t, { ...env, AUTH_APPLE_AUDIENCES: undefined, AUTH_GOOGLE_WEB_CLIENT_ID: undefined });
  const runtime = createRuntime();
  try {
    assert.ok(runtime.auth);
    assert.equal(runtime.auth.providers, undefined);
    assert.equal(runtime.refreshProviderKeys, undefined);
  } finally { await runtime.close(); }
});

test('provider sign-in requires accounts and complete per-provider configuration', async t => {
  const { env } = enabled();
  withEnv(t, { ...env, AUTH_APPLE_AUDIENCES: 'app.pittech.synthetic', AUTH_GOOGLE_WEB_CLIENT_ID: undefined, PRO_APPLE_ROOTS: undefined, PRO_APPLE_TRUST_MODE: undefined });
  swap('AUTH_ENABLED', undefined, /Accounts required/);
  swap('PRO_APPLE_ROOTS', undefined, /Invalid Apple trust configuration/);
  swap('PRO_APPLE_ROOTS', 'not-json', /Invalid Apple trust configuration/);
  swap('PRO_APPLE_ROOTS', '[]', /Invalid Apple trust configuration/);
  swap('AUTH_APPLE_AUDIENCES', 'not a bundle id!', /Invalid provider configuration/);
  swap('AUTH_APPLE_AUDIENCES', 'app.pittech.synthetic,,app.pittech.synthetic.staging', /Invalid provider configuration/);
  swap('AUTH_GOOGLE_WEB_CLIENT_ID', 'not-a-client-id', /Invalid provider configuration/);
  swap('AUTH_GOOGLE_WEB_CLIENT_ID', 'synthetic-web-client.apps.googleusercontent.com.evil.invalid', /Invalid provider configuration/);
});

test('provider sign-in configures per-provider audiences, keys and refresh', async t => {
  withEnv(t, {
    ...enabled().env,
    AUTH_APPLE_AUDIENCES: 'app.pittech.synthetic.staging,app.pittech.synthetic,app.pittech.synthetic',
    AUTH_GOOGLE_WEB_CLIENT_ID: 'synthetic-web-client.apps.googleusercontent.com',
    PRO_APPLE_ROOTS: JSON.stringify(['synthetic-root']),
  });
  const calls: string[] = [];
  t.mock.method(globalThis, 'fetch', async (url: unknown) => {
    calls.push(String(url));
    return Response.json({ keys: [oidcPublicJwk] }, { headers: { 'Cache-Control': 'max-age=3600' } });
  });
  const runtime = createRuntime();
  try {
    assert.deepEqual(runtime.auth?.providers?.apple?.audiences, ['app.pittech.synthetic.staging', 'app.pittech.synthetic']);
    assert.equal(runtime.auth?.providers?.google?.webClientId, 'synthetic-web-client.apps.googleusercontent.com');
    assert.equal(runtime.auth?.providers?.deletion, undefined);
    await runtime.refreshProviderKeys!();
    assert.deepEqual(calls, ['https://appleid.apple.com/auth/keys', 'https://www.googleapis.com/oauth2/v3/certs']);
  } finally { await runtime.close(); }
});

test('provider notifications wire the lifecycle deletion entry only when accounts can delete', async t => {
  withEnv(t, {
    ...enabled().env,
    AUTH_APPLE_AUDIENCES: 'app.pittech.synthetic',
    AUTH_GOOGLE_WEB_CLIENT_ID: undefined,
    PRO_APPLE_ROOTS: JSON.stringify(['synthetic-root']),
    ACCOUNT_LIFECYCLE_ENABLED: 'true',
    LIFECYCLE_DATABASE_URL: 'postgres://localhost:5432/pittech_lifecycle',
    LIFECYCLE_HASH_KEY: credential(),
    LIFECYCLE_ENCRYPTION_KEY: credential(),
  });
  const runtime = createRuntime();
  try {
    assert.ok(runtime.auth?.providers?.apple);
    assert.equal(runtime.auth?.providers?.google, undefined);
    assert.equal(runtime.auth?.providers?.deletion, runtime.deletion);
  } finally { await runtime.close(); }
});
