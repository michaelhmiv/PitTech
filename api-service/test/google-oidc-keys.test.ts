import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { APPLE_KEYS_ENDPOINT, GOOGLE_CERTS_ENDPOINT, GoogleOidcKeyCache, googleSigningKeys } from '../src/google-oidc-keys.ts';
import { verifyOidcBearer } from '../src/subscriptions-google.ts';
import { NOW, oidcAudience, oidcEmail, oidcPublicJwk, signOidc } from './billing-fixtures.ts';

test('the real Google JWKS shape authenticates a signed push and expires at its HTTP cache lifetime', async () => {
  let now = NOW;
  const calls: { url: unknown; redirect: unknown }[] = [];
  const keys = new GoogleOidcKeyCache(async (url, init) => {
    calls.push({ url, redirect: init?.redirect });
    return Response.json({ keys: [oidcPublicJwk] }, { headers: { 'Cache-Control': 'public, max-age=10', Age: '2' } });
  }, () => now);
  await keys.refresh();
  assert.deepEqual(calls, [{ url: 'https://www.googleapis.com/oauth2/v3/certs', redirect: 'error' }]);
  assert.equal(verifyOidcBearer(signOidc(), keys, oidcAudience, now, oidcEmail).email, oidcEmail);
  now += 7999;
  assert.equal(keys.has('synthetic-oidc-key'), true);
  now += 1;
  assert.equal(keys.get('synthetic-oidc-key'), undefined);
  assert.throws(() => verifyOidcBearer(signOidc(), keys, oidcAudience, now, oidcEmail), { code: 'notification_rejected' });
});

test('successful key rotation replaces retired identities; rejected refresh never extends the last good keys', async () => {
  let now = NOW, status = 200;
  let body: unknown = { keys: [oidcPublicJwk] };
  const keys = new GoogleOidcKeyCache(async () => Response.json(body, { status, headers: { 'Cache-Control': 'max-age=10' } }), () => now);
  await keys.refresh();
  now += 5000;
  body = { keys: [oidcPublicJwk, { ...oidcPublicJwk, kid: 'second', alg: 'none' }] };
  await assert.rejects(keys.refresh(), /OIDC signing keys unavailable/);
  assert.equal(keys.has('synthetic-oidc-key'), true);
  assert.equal(keys.has('second'), false);
  status = 503;
  await assert.rejects(keys.refresh(), /OIDC signing keys unavailable/);
  now += 5000;
  assert.equal(keys.has('synthetic-oidc-key'), false);
  const replacement = generateKeyPairSync('rsa', { modulusLength: 2048 }).publicKey.export({ format: 'jwk' });
  body = { keys: [{ ...replacement, kid: 'replacement', alg: 'RS256', use: 'sig' }] }; status = 200;
  await keys.refresh();
  assert.equal(keys.has('replacement'), true);
  assert.equal(keys.has('synthetic-oidc-key'), false);
});

test('untrusted key shapes, private keys, duplicates and weak RSA keys fail closed', () => {
  const weak = generateKeyPairSync('rsa', { modulusLength: 1024 }).publicKey.export({ format: 'jwk' });
  for (const value of [
    null, {}, { keys: [] }, { keys: Array(17).fill(oidcPublicJwk) },
    { keys: [oidcPublicJwk, oidcPublicJwk] },
    { keys: [{ ...oidcPublicJwk, d: 'private-key-must-not-arrive' }] },
    { keys: [{ ...oidcPublicJwk, kid: '' }] }, { keys: [{ ...oidcPublicJwk, alg: 'ES256' }] },
    { keys: [{ ...oidcPublicJwk, use: 'enc' }] },
    { keys: [{ ...weak, kid: 'weak', alg: 'RS256', use: 'sig' }] },
  ]) assert.throws(() => googleSigningKeys(value), /OIDC signing keys unavailable/);
});

test('the cache is parameterized by issuer endpoint with the Google default; unknown endpoints fail closed', async () => {
  const seen: unknown[] = [];
  const apple = new GoogleOidcKeyCache(async url => { seen.push(url); return Response.json({ keys: [oidcPublicJwk] }, { headers: { 'Cache-Control': 'max-age=10' } }); }, Date.now, APPLE_KEYS_ENDPOINT);
  await apple.refresh();
  assert.deepEqual(seen, [APPLE_KEYS_ENDPOINT]);
  assert.equal(apple.has('synthetic-oidc-key'), true);
  assert.throws(() => new GoogleOidcKeyCache(fetch, Date.now, 'https://attacker.invalid/certs'), /Unknown OIDC key endpoint/);
  assert.equal(GOOGLE_CERTS_ENDPOINT, 'https://www.googleapis.com/oauth2/v3/certs');
});

test('oversized responses and stale cache headers cannot install keys; excessive lifetimes are capped', async () => {
  let now = NOW;
  const oversized = new GoogleOidcKeyCache(async () => new Response('x'.repeat(65537)));
  await assert.rejects(oversized.refresh(), /OIDC signing keys unavailable/);
  assert.equal(oversized.has('synthetic-oidc-key'), false);
  const staleHeaders: Record<string, string>[] = [{ 'Cache-Control': 'max-age=0' }, { 'Cache-Control': 'max-age=5', Age: '5' }, { Age: '-1' }];
  for (const headers of staleHeaders) {
    const keys = new GoogleOidcKeyCache(async () => Response.json({ keys: [oidcPublicJwk] }, { headers }));
    await assert.rejects(keys.refresh(), /OIDC signing keys unavailable/);
  }
  const keys = new GoogleOidcKeyCache(async () => Response.json({ keys: [oidcPublicJwk] }, { headers: { 'Cache-Control': 'max-age=86400' } }), () => now);
  await keys.refresh(); now += 3600000;
  assert.equal(keys.has('synthetic-oidc-key'), false);
});
