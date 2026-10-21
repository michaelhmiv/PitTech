import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createSign, generateKeyPairSync, randomUUID, type KeyObject } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { Pool } from 'pg';
import { migrate } from '../src/migrate.ts';
import { Authentication } from '../src/auth.ts';
import { AUTH, AuthSecrets, credential, proofHash } from '../src/auth-credentials.ts';
import type { SignInMail } from '../src/auth-email.ts';
import type { LifecycleMail } from '../src/lifecycle-email.ts';
import { AccountDeletion } from '../src/account-deletion.ts';
import { appleEventOperationId } from '../src/auth-providers.ts';
import { APPLE_KEYS_ENDPOINT, GOOGLE_CERTS_ENDPOINT, GoogleOidcKeyCache } from '../src/google-oidc-keys.ts';
import { makeServer } from '../src/http.ts';
import { Relay } from '../src/relay.ts';
import { FileStorage } from '../src/storage.ts';
import { MemoryRepository, budgets } from './helpers.ts';
import { appleTrust, signApple } from './billing-fixtures.ts';

// Synthetic provider identities for tests only. Real bundle IDs, client IDs
// and provider keys are deployment configuration, never committed here.
const NOW = Date.UTC(2026, 9, 21, 12);
const audiences = ['app.pittech.synthetic.staging', 'app.pittech.synthetic'];
const webClientId = 'synthetic-web-client.apps.googleusercontent.com';
const appleKeys = generateKeyPairSync('rsa', { modulusLength: 2048 });
const googleKeys = generateKeyPairSync('rsa', { modulusLength: 2048 });
const appleJwk = { ...appleKeys.publicKey.export({ format: 'jwk' }), kid: 'synthetic-apple-rs256', alg: 'RS256', use: 'sig' };
const googleJwk = { ...googleKeys.publicKey.export({ format: 'jwk' }), kid: 'synthetic-google-rs256', alg: 'RS256', use: 'sig' };
function signRs256(key: KeyObject, header: Record<string, unknown>, claims: Record<string, unknown>): string {
  const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const input = encode(header) + '.' + encode(claims);
  const signer = createSign('RSA-SHA256'); signer.update(input); signer.end();
  return input + '.' + signer.sign(key).toString('base64url');
}
const seconds = (now: number) => Math.floor(now / 1000);
function signAppleId(claims: Record<string, unknown>, now: number, header: Record<string, unknown> = {}): string {
  return signRs256(appleKeys.privateKey, { alg: 'RS256', kid: appleJwk.kid, typ: 'JWT', ...header },
    { iss: 'https://appleid.apple.com', aud: audiences[0], sub: 'synthetic-apple-sub', iat: seconds(now), exp: seconds(now) + 3600, ...claims });
}
function signGoogleId(claims: Record<string, unknown>, now: number, header: Record<string, unknown> = {}): string {
  return signRs256(googleKeys.privateKey, { alg: 'RS256', kid: googleJwk.kid, typ: 'JWT', ...header },
    { iss: 'https://accounts.google.com', aud: webClientId, sub: 'synthetic-google-sub', email_verified: true, iat: seconds(now), exp: seconds(now) + 3600, ...claims });
}
function signProviderEvent(type: string, sub: string, eventId: string, now: number, extra: Record<string, unknown> = {}, audience = audiences[0]): string {
  return signApple({ iss: 'https://appleid.apple.com', aud: audience, iat: seconds(now), jti: eventId, events: JSON.stringify({ type, sub, event_time: now, ...extra }) });
}

const gate = { skip: !process.env.TEST_DATABASE_URL && process.env.REQUIRE_DB_TESTS !== '1' };
test('real Postgres provider auth: proof-bound Apple/Google exchange, no silent merge, attach, receipts, lifecycle and HTTP redaction', gate, async () => {
  const source = process.env.TEST_DATABASE_URL; assert.ok(source);
  const base = new URL(source); assert.match(base.pathname, /^\/pittech_test[a-z_\d]*$/i);
  const control = new Pool({ connectionString: source }), database = 'pittech_test_provider_' + randomUUID().replaceAll('-', '');
  await control.query(`CREATE DATABASE ${database}`); base.pathname = '/' + database;
  const connectionString = base.toString(), admin = new Pool({ connectionString, max: 5 });
  const authPool = new Pool({ connectionString, max: 5, options: '-c role=pittech_auth_runtime' });
  const lifePool = new Pool({ connectionString, max: 5, options: '-c role=pittech_lifecycle_runtime' });
  const root = await mkdtemp(join(tmpdir(), 'pittech-provider-test-'));
  let now = NOW;
  try {
    await migrate(admin); await migrate(admin);
    for (const name of ['auth', 'lifecycle']) { const grants = await readFile(new URL(`../operations/${name}-runtime-role.sql`, import.meta.url), 'utf8'); await admin.query(grants); await admin.query(grants); }
    for (const sql of ['CREATE TABLE auth.forbidden(id integer)', 'TRUNCATE auth.provider_identities', 'DELETE FROM auth.provider_identities', 'SELECT 1 FROM relay.schema_migrations', 'SELECT 1 FROM moderation.deletions']) await assert.rejects(authPool.query(sql), { code: '42501' });
    await authPool.query('SELECT 1 FROM auth.provider_challenges LIMIT 1');
    await authPool.query('SELECT 1 FROM auth.apple_notification_receipts LIMIT 1');
    await assert.rejects(lifePool.query('INSERT INTO auth.provider_identities VALUES(gen_random_uuid(),\'apple\',repeat(\'a\',64),NULL,0)'), { code: '42501' });
    await assert.rejects(lifePool.query('SELECT email FROM auth.provider_identities LIMIT 1'), { code: '42501' });
    await assert.rejects(lifePool.query('SELECT 1 FROM auth.apple_notification_receipts LIMIT 1'), { code: '42501' });
    const secrets = new AuthSecrets(credential(), credential()), authSecrets = new AuthSecrets(credential(), credential());
    const signIn: SignInMail[] = [], notices: LifecycleMail[] = [];
    const storage = new FileStorage(root);
    const deletion = new AccountDeletion(lifePool, secrets, storage, { send: async mail => { notices.push(mail); } }, email => authSecrets.hash('email', email), () => now, { purge: async () => true });
    const seen: string[] = [];
    const appleCache = new GoogleOidcKeyCache(async url => { seen.push(String(url)); return Response.json({ keys: [appleJwk] }, { headers: { 'Cache-Control': 'max-age=3600' } }); }, () => now, APPLE_KEYS_ENDPOINT);
    const googleCache = new GoogleOidcKeyCache(async url => { seen.push(String(url)); return Response.json({ keys: [googleJwk] }, { headers: { 'Cache-Control': 'max-age=3600' } }); }, () => now, GOOGLE_CERTS_ENDPOINT);
    await appleCache.refresh(); await googleCache.refresh();
    assert.deepEqual(seen, [APPLE_KEYS_ENDPOINT, GOOGLE_CERTS_ENDPOINT]);
    const providers = { apple: { audiences, keys: appleCache, trust: appleTrust }, google: { webClientId, keys: googleCache }, deletion };
    const auth = new Authentication(authPool, authSecrets, { send: async mail => { signIn.push(mail); } }, 100, () => now, providers);
    const login = async (email: string) => {
      const verifier = credential(), request = await auth.start({ email, idempotencyId: randomUUID(), proofChallenge: proofHash(verifier) });
      await auth.deliverPending(); const mail = signIn.find(entry => entry.challengeId === request.challengeId)!; assert.ok(mail);
      return auth.verify({ challengeId: request.challengeId, verifier, code: mail.code, idempotencyId: randomUUID() });
    };
    const begin = async (provider: 'apple' | 'google', accessToken?: string) => {
      const verifier = credential(), proof = proofHash(verifier);
      const created = await auth.startProvider({ provider, idempotencyId: randomUUID(), proofChallenge: proof }, accessToken);
      assert.deepEqual(created, { challengeId: created.challengeId, expiresInSeconds: 900 });
      return { verifier, proof, id: created.challengeId };
    };
    const exchange = (id: string, verifier: string, idToken: string, idempotencyId = randomUUID()) =>
      auth.verifyProvider({ challengeId: id, verifier, idToken, idempotencyId });
    const sessions = async () => Number((await admin.query('SELECT count(*) FROM auth.sessions')).rows[0].count);

    // Apple happy path: first-authorization email creates the account and the
    // standard session; the subject persists only as a keyed hash.
    const first = await begin('apple');
    const appleToken = signAppleId({ sub: 'synthetic-apple-sub-1', nonce: first.proof, email: 'synthetic-apple@example.com' }, now);
    const appleSession = await exchange(first.id, first.verifier, appleToken);
    assert.equal(Date.parse(appleSession.sessionExpiresAt) - now, AUTH.sessionMs);
    assert.equal(Date.parse(appleSession.accessExpiresAt) - now, AUTH.accessMs);
    assert.deepEqual(await auth.user(appleSession.accessToken), { id: appleSession.userId, email: 'synthetic-apple@example.com', identities: [{ provider: 'apple' }] });
    const stored = (await admin.query('SELECT subject_key,email FROM auth.provider_identities WHERE user_id=$1', [appleSession.userId])).rows[0];
    assert.match(stored.subject_key, /^[a-f0-9]{64}$/); assert.ok(!stored.subject_key.includes('synthetic')); assert.equal(stored.email, 'synthetic-apple@example.com');
    const renewed = await auth.refresh(appleSession.refreshToken, randomUUID()); assert.equal(renewed.userId, appleSession.userId);
    // The staging deployment accepts both bundle IDs as the Apple audience.
    const second = await begin('apple');
    const sandboxSession = await exchange(second.id, second.verifier, signAppleId({ sub: 'synthetic-apple-sub-2', nonce: second.proof, aud: audiences[1], email: 'synthetic-sandbox@example.com' }, now));
    assert.equal((await auth.user(sandboxSession.accessToken)).email, 'synthetic-sandbox@example.com');
    // Google happy path, including the short issuer form.
    const third = await begin('google');
    const googleSession = await exchange(third.id, third.verifier, signGoogleId({ sub: 'synthetic-google-sub-1', nonce: third.proof, email: 'synthetic-google@example.com' }, now));
    assert.deepEqual((await auth.user(googleSession.accessToken)).identities, [{ provider: 'google' }]);
    const short = await begin('google');
    const shortSession = await exchange(short.id, short.verifier, signGoogleId({ sub: 'synthetic-google-sub-2', nonce: short.proof, iss: 'accounts.google.com', email: 'synthetic-google-short@example.com' }, now));
    assert.equal((await auth.user(shortSession.accessToken)).email, 'synthetic-google-short@example.com');

    // Every provider failure mode is one generic 401; attempts are committed.
    const defects: ((proof: string) => string)[] = [
      proof => signAppleId({ sub: 'synthetic-apple-bad', nonce: proof, email: 'synthetic-bad@example.com', aud: 'com.pittech.unconfigured' }, now),
      proof => signAppleId({ sub: 'synthetic-apple-bad', nonce: proof, email: 'synthetic-bad@example.com', iss: 'https://attacker.invalid' }, now),
      proof => signAppleId({ sub: 'synthetic-apple-bad', nonce: proof, email: 'synthetic-bad@example.com' }, now, { kid: 'unknown-key' }),
      proof => signAppleId({ sub: 'synthetic-apple-bad', nonce: proof, email: 'synthetic-bad@example.com' }, now, { alg: 'none' }),
      () => signAppleId({ sub: 'synthetic-apple-bad', nonce: proofHash(credential()), email: 'synthetic-bad@example.com' }, now),
      proof => signAppleId({ sub: 'synthetic-apple-bad', nonce: proof, email: 'synthetic-bad@example.com', exp: seconds(now) - 1 }, now),
      proof => signAppleId({ sub: 'synthetic-apple-bad', nonce: proof, email: 'synthetic-bad@example.com', iat: seconds(now) + 3600 }, now),
      proof => signGoogleId({ sub: 'synthetic-apple-bad', nonce: proof, email: 'synthetic-bad@example.com' }, now),
    ];
    for (const forge of defects) {
      const attempt = await begin('apple');
      await assert.rejects(exchange(attempt.id, attempt.verifier, forge(attempt.proof)), { code: 'sign_in_unavailable' });
    }
    const bad = await begin('apple');
    await assert.rejects(exchange(bad.id, bad.verifier, signAppleId({ sub: 'synthetic-apple-bad', nonce: bad.proof, email: 'synthetic-bad@example.com', aud: 'com.pittech.unconfigured' }, now)), { code: 'sign_in_unavailable' });
    assert.equal((await admin.query('SELECT attempts FROM auth.provider_challenges WHERE id=$1', [bad.id])).rows[0].attempts, 1);
    await assert.rejects(auth.verifyProvider({ challengeId: bad.id, verifier: credential(), idToken: signAppleId({ sub: 'synthetic-apple-bad', nonce: bad.proof }, now), idempotencyId: randomUUID() }), { code: 'sign_in_unavailable' });
    const expired = await begin('google');
    await assert.rejects(exchange(expired.id, expired.verifier, signGoogleId({ sub: 'synthetic-google-expired', nonce: expired.proof, email: 'synthetic-expired@example.com', email_verified: false }, now)), { code: 'sign_in_unavailable' });
    assert.equal((await admin.query('SELECT count(*) FROM auth.users WHERE email=$1', ['synthetic-expired@example.com'])).rows[0].count, '0');

    // Consumed challenges replay the exact exchange inside the recovery window
    // and reject everything else, including a changed token on the same proof.
    const replay = await begin('apple');
    const replayToken = signAppleId({ sub: 'synthetic-apple-replay', nonce: replay.proof, email: 'synthetic-replay@example.com' }, now);
    const replayId = randomUUID(), replayed = await exchange(replay.id, replay.verifier, replayToken, replayId);
    assert.deepEqual(await exchange(replay.id, replay.verifier, replayToken, replayId), replayed);
    await assert.rejects(exchange(replay.id, replay.verifier, replayToken), { code: 'sign_in_unavailable' });
    await assert.rejects(exchange(replay.id, replay.verifier, signAppleId({ sub: 'synthetic-apple-other', nonce: replay.proof, email: 'synthetic-replay@example.com' }, now), replayId), { code: 'sign_in_unavailable' });
    now += 31_000;
    await assert.rejects(exchange(replay.id, replay.verifier, replayToken, replayId), { code: 'sign_in_unavailable' });
    // Concurrent consumers race for one exchange; the loser sees a 401.
    const race = await begin('google');
    const raceToken = signGoogleId({ sub: 'synthetic-google-race', nonce: race.proof, email: 'synthetic-race@example.com' }, now);
    const raced = await Promise.allSettled([exchange(race.id, race.verifier, raceToken), exchange(race.id, race.verifier, raceToken)]);
    assert.equal(raced.filter(entry => entry.status === 'fulfilled').length, 1);
    // Five failures exhaust the challenge; the sixth attempt cannot recover it.
    const locked = await begin('apple');
    const lockedBad = signAppleId({ sub: 'synthetic-apple-locked', nonce: locked.proof, email: 'synthetic-locked@example.com', aud: 'com.pittech.unconfigured' }, now);
    for (let i = 0; i < AUTH.attempts; i++) await assert.rejects(exchange(locked.id, locked.verifier, lockedBad), { code: 'sign_in_unavailable' });
    assert.equal((await admin.query('SELECT attempts FROM auth.provider_challenges WHERE id=$1', [locked.id])).rows[0].attempts, 5);
    await assert.rejects(exchange(locked.id, locked.verifier, signAppleId({ sub: 'synthetic-apple-locked', nonce: locked.proof, email: 'synthetic-locked@example.com' }, now)), { code: 'sign_in_unavailable' });
    // Challenge creation is idempotent on its inputs and bound on demand.
    const repeatProof = proofHash(credential());
    const repeat = await auth.startProvider({ provider: 'google', idempotencyId: randomUUID(), proofChallenge: repeatProof });
    assert.deepEqual(await auth.startProvider({ provider: 'google', idempotencyId: repeat.challengeId, proofChallenge: repeatProof }), repeat);
    await assert.rejects(auth.startProvider({ provider: 'google', idempotencyId: repeat.challengeId, proofChallenge: proofHash(credential()) }), { code: 'sign_in_request_unavailable' });

    // No silent merge: a verified email matching an unlinked account is a 409
    // with no session, while the email authenticator keeps working.
    const carol = await login('synthetic-carol@example.com');
    const before = await sessions();
    const clash = await begin('apple');
    await assert.rejects(exchange(clash.id, clash.verifier, signAppleId({ sub: 'synthetic-apple-clash', nonce: clash.proof, email: 'synthetic-carol@example.com' }, now)), { code: 'identity_link_required' });
    assert.equal(await sessions(), before);
    assert.equal((await admin.query('SELECT attempts FROM auth.provider_challenges WHERE id=$1', [clash.id])).rows[0].attempts, 1);
    await assert.rejects(exchange(clash.id, clash.verifier, signAppleId({ sub: 'synthetic-apple-clash-2', nonce: clash.proof, email: 'synthetic-carol@example.com' }, now)), { code: 'identity_link_required' });
    assert.equal((await auth.user(carol.accessToken)).email, 'synthetic-carol@example.com');
    // A returning Apple token without an email claim and without a link cannot
    // create an account; the private relay address stores verbatim when present.
    const silent = await begin('apple');
    await assert.rejects(exchange(silent.id, silent.verifier, signAppleId({ sub: 'synthetic-apple-silent', nonce: silent.proof }, now)), { code: 'sign_in_unavailable' });
    const relayed = await begin('apple');
    const relaySession = await exchange(relayed.id, relayed.verifier, signAppleId({ sub: 'synthetic-apple-relay', nonce: relayed.proof, email: 'synthetic-relay@privaterelay.appleid.com' }, now));
    assert.equal((await auth.user(relaySession.accessToken)).email, 'synthetic-relay@privaterelay.appleid.com');

    // Attach while signed in links the identity without issuing a session;
    // the linked identity then signs in with the email claim irrelevant.
    const bob = await login('synthetic-bob@example.com');
    const bound = await begin('apple', bob.accessToken);
    assert.equal((await admin.query('SELECT user_id FROM auth.provider_challenges WHERE id=$1', [bound.id])).rows[0].user_id, bob.userId);
    const sessionsBeforeAttach = await sessions();
    const attached = await auth.attachIdentity(bob.accessToken, { challengeId: bound.id, verifier: bound.verifier, idToken: signAppleId({ sub: 'synthetic-apple-bob', nonce: bound.proof, email: 'synthetic-bob@example.com' }, now), idempotencyId: randomUUID() });
    assert.deepEqual(attached, { identities: [{ provider: 'apple' }] });
    assert.equal(await sessions(), sessionsBeforeAttach);
    assert.deepEqual((await auth.user(bob.accessToken)).identities, [{ provider: 'apple' }]);
    const linked = await begin('apple');
    const bobAgain = await exchange(linked.id, linked.verifier, signAppleId({ sub: 'synthetic-apple-bob', nonce: linked.proof }, now));
    assert.equal(bobAgain.userId, bob.userId);
    // Unbound challenges never attach, and foreign or duplicate links are 409s.
    const loose = await begin('apple');
    await assert.rejects(auth.attachIdentity(bob.accessToken, { challengeId: loose.id, verifier: loose.verifier, idToken: signAppleId({ sub: 'synthetic-apple-loose', nonce: loose.proof }, now), idempotencyId: randomUUID() }), { code: 'sign_in_unavailable' });
    const foreign = await begin('apple', carol.accessToken);
    await assert.rejects(auth.attachIdentity(carol.accessToken, { challengeId: foreign.id, verifier: foreign.verifier, idToken: signAppleId({ sub: 'synthetic-apple-bob', nonce: foreign.proof }, now), idempotencyId: randomUUID() }), { code: 'identity_link_required' });
    const duplicate = await begin('apple', bob.accessToken);
    await assert.rejects(auth.attachIdentity(bob.accessToken, { challengeId: duplicate.id, verifier: duplicate.verifier, idToken: signAppleId({ sub: 'synthetic-apple-bob-2', nonce: duplicate.proof }, now), idempotencyId: randomUUID() }), { code: 'identity_link_required' });
    const googleBound = await begin('google', bob.accessToken);
    assert.deepEqual(await auth.attachIdentity(bob.accessToken, { challengeId: googleBound.id, verifier: googleBound.verifier, idToken: signGoogleId({ sub: 'synthetic-google-bob', nonce: googleBound.proof, email: 'synthetic-bob@example.com' }, now), idempotencyId: randomUUID() }), { identities: [{ provider: 'apple' }, { provider: 'google' }] });

    // Apple notifications: bad signatures change nothing, unknown subjects are
    // a recorded no-op, email events update reachability, and delete events
    // enter the lifecycle and sweep to completion with identities cascading.
    const notify = (signedPayload: string) => auth.appleNotification({ signedPayload });
    await assert.rejects(notify(signProviderEvent('account-delete', 'synthetic-apple-bob', randomUUID(), now).slice(0, -4) + 'aaaa'), { code: 'notification_rejected' });
    await assert.rejects(notify(signApple({ iss: 'https://appleid.apple.com', aud: 'com.pittech.unconfigured', iat: seconds(now), jti: randomUUID(), events: JSON.stringify({ type: 'account-delete', sub: 'synthetic-apple-bob', event_time: now }) })), { code: 'notification_rejected' });
    assert.equal((await admin.query('SELECT count(*) FROM auth.apple_notification_receipts')).rows[0].count, '0');
    const ghostId = randomUUID();
    assert.deepEqual(await notify(signProviderEvent('consent-revoked', 'synthetic-apple-ghost', ghostId, now)), { outcome: 'recorded', note: 'unknown-subject' });
    assert.deepEqual(await notify(signProviderEvent('consent-revoked', 'synthetic-apple-ghost', ghostId, now)), { outcome: 'deduped', note: 'replay' });
    assert.equal((await admin.query('SELECT count(*) FROM moderation.deletions')).rows[0].count, '0');
    const relayId = randomUUID();
    assert.deepEqual(await notify(signProviderEvent('email-disabled', 'synthetic-apple-relay', relayId, now, { email: 'synthetic-relay@privaterelay.appleid.com' })), { outcome: 'applied', note: 'email-disabled' });
    assert.equal((await admin.query('SELECT email FROM auth.provider_identities WHERE user_id=$1', [relaySession.userId])).rows[0].email, 'synthetic-relay@privaterelay.appleid.com');
    assert.deepEqual(await notify(signProviderEvent('email-disabled', 'synthetic-apple-relay', relayId, now, { email: 'synthetic-relay@privaterelay.appleid.com' })), { outcome: 'deduped', note: 'replay' });
    assert.deepEqual(await notify(signProviderEvent('consent-reset', 'synthetic-apple-relay', randomUUID(), now)), { outcome: 'recorded', note: 'unsupported-event' });
    const target = await login('synthetic-target@example.com');
    const targetBound = await begin('google', target.accessToken);
    await auth.attachIdentity(target.accessToken, { challengeId: targetBound.id, verifier: targetBound.verifier, idToken: signGoogleId({ sub: 'synthetic-google-target', nonce: targetBound.proof, email: 'synthetic-target@example.com' }, now), idempotencyId: randomUUID() });
    const appleBound = await begin('apple', target.accessToken);
    await auth.attachIdentity(target.accessToken, { challengeId: appleBound.id, verifier: appleBound.verifier, idToken: signAppleId({ sub: 'synthetic-apple-target', nonce: appleBound.proof, email: 'synthetic-target@example.com' }, now), idempotencyId: randomUUID() });
    const deleteId = randomUUID();
    assert.deepEqual(await notify(signProviderEvent('account-delete', 'synthetic-apple-target', deleteId, now)), { outcome: 'applied', note: 'account-delete' });
    assert.deepEqual(await notify(signProviderEvent('account-delete', 'synthetic-apple-target', deleteId, now)), { outcome: 'deduped', note: 'replay' });
    assert.equal((await admin.query('SELECT deleting FROM auth.account_lifecycle WHERE user_id=$1', [target.userId])).rows[0].deleting, true);
    assert.equal((await admin.query('SELECT count(*) FROM auth.sessions WHERE user_id=$1', [target.userId])).rows[0].count, '0');
    assert.equal((await admin.query('SELECT count(*) FROM auth.provider_challenges WHERE user_id=$1', [target.userId])).rows[0].count, '0');
    await assert.rejects(auth.user(target.accessToken), { code: 'sign_in_unavailable' });
    await assert.rejects(auth.refresh(target.refreshToken, randomUUID()), { code: 'sign_in_unavailable' });
    const targetVerify = await begin('apple');
    await assert.rejects(exchange(targetVerify.id, targetVerify.verifier, signAppleId({ sub: 'synthetic-apple-target', nonce: targetVerify.proof }, now)), { code: 'sign_in_unavailable' });
    assert.equal((await admin.query('SELECT count(*) FROM moderation.deletions WHERE operation_id=$1', [appleEventOperationId(deleteId)])).rows[0].count, '1');
    await deletion.sweepDeletion();
    now += 60001; await deletion.sweepDeletion();
    assert.equal((await admin.query('SELECT 1 FROM auth.users WHERE id=$1', [target.userId])).rowCount, 0);
    assert.equal((await admin.query('SELECT 1 FROM auth.provider_identities WHERE user_id=$1', [target.userId])).rowCount, 0);
    assert.equal((await admin.query('SELECT 1 FROM auth.apple_notification_receipts WHERE event_id=$1', [deleteId])).rowCount, 1);
    const afterId = randomUUID();
    assert.deepEqual(await notify(signProviderEvent('account-delete', 'synthetic-apple-target', afterId, now)), { outcome: 'recorded', note: 'unknown-subject' });

    // Deployments without provider configuration refuse every provider call.
    const emailOnly = new Authentication(authPool, authSecrets, { send: async mail => { signIn.push(mail); } }, 100, () => now);
    await assert.rejects(emailOnly.startProvider({ provider: 'apple', idempotencyId: randomUUID(), proofChallenge: proofHash(credential()) }), { code: 'sign_in_unavailable' });
    await assert.rejects(emailOnly.verifyProvider({ challengeId: randomUUID(), verifier: credential(), idToken: 'synthetic', idempotencyId: randomUUID() }), { code: 'sign_in_unavailable' });
    await assert.rejects(emailOnly.attachIdentity(renewed.accessToken, { challengeId: randomUUID(), verifier: credential(), idToken: 'synthetic', idempotencyId: randomUUID() }), { code: 'sign_in_unavailable' });
    await assert.rejects(emailOnly.appleNotification({ signedPayload: 'synthetic' }), { status: 503 });
    const appleOnly = new Authentication(authPool, authSecrets, { send: async mail => { signIn.push(mail); } }, 100, () => now, { apple: providers.apple });
    await assert.rejects(appleOnly.startProvider({ provider: 'google', idempotencyId: randomUUID(), proofChallenge: proofHash(credential()) }), { code: 'sign_in_unavailable' });

    // HTTP: provider routes share the auth block contract and redact every
    // token, email, subject and verifier from logs and error text.
    const logs: unknown[] = [], relay = new Relay(new MemoryRepository(), new FileStorage(root), budgets, () => now);
    const server = makeServer(relay, { ipKey: 'synthetic-provider-rate-key', uploadsEnabled: false, auth, deletion, log: row => logs.push(row) });
    server.listen(0, '127.0.0.1'); await once(server, 'listening');
    try {
      const address = server.address(); assert.ok(address && typeof address !== 'string'); const origin = `http://127.0.0.1:${address.port}`;
      const post = (path: string, body: unknown, bearer?: string) => fetch(origin + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(bearer ? { Authorization: 'Bearer ' + bearer } : {}) }, body: JSON.stringify(body) });
      const httpVerifier = credential(), httpProof = proofHash(httpVerifier);
      const created = await post('/v1/auth/provider/challenges', { provider: 'google', idempotencyId: randomUUID(), proofChallenge: httpProof });
      assert.equal(created.status, 202); assert.equal(created.headers.get('cache-control'), 'private, no-store');
      const challenge = await created.json() as { challengeId: string };
      const verified = await post('/v1/auth/provider/verify', { challengeId: challenge.challengeId, verifier: httpVerifier, idToken: signGoogleId({ sub: 'synthetic-google-http', nonce: httpProof, email: 'synthetic-http@example.com' }, now), idempotencyId: randomUUID() });
      assert.equal(verified.status, 200);
      const httpSession = await verified.json() as { accessToken: string; refreshToken: string; userId: string };
      const me = await fetch(origin + '/v1/me', { headers: { Authorization: 'Bearer ' + httpSession.accessToken } });
      assert.equal(me.status, 200); assert.deepEqual(await me.json(), { id: httpSession.userId, email: 'synthetic-http@example.com', identities: [{ provider: 'google' }] });
      const clashBody = { provider: 'apple', idempotencyId: randomUUID(), proofChallenge: proofHash(credential()) };
      const clashChallenge = await (await post('/v1/auth/provider/challenges', clashBody)).json() as { challengeId: string };
      const clashing = await post('/v1/auth/provider/verify', { challengeId: clashChallenge.challengeId, verifier: httpVerifier, idToken: 'synthetic', idempotencyId: randomUUID() });
      assert.equal(clashing.status, 401); assert.ok(!(await clashing.text()).includes('synthetic-http'));
      const emailClash = await post('/v1/auth/provider/verify', { challengeId: (await (await post('/v1/auth/provider/challenges', { provider: 'apple', idempotencyId: randomUUID(), proofChallenge: httpProof })).json() as { challengeId: string }).challengeId, verifier: httpVerifier, idToken: signAppleId({ sub: 'synthetic-apple-http-clash', nonce: httpProof, email: 'synthetic-http@example.com' }, now), idempotencyId: randomUUID() });
      assert.equal(emailClash.status, 409);
      const emailClashText = await emailClash.text();
      assert.ok(emailClashText.includes('identity_link_required')); assert.ok(!emailClashText.includes('synthetic-http')); assert.ok(!emailClashText.includes('synthetic-apple-http-clash'));
      const boundChallenge = await (await post('/v1/auth/provider/challenges', { provider: 'apple', idempotencyId: randomUUID(), proofChallenge: httpProof }, httpSession.accessToken)).json() as { challengeId: string };
      const attachedResponse = await post('/v1/me/identities', { challengeId: boundChallenge.challengeId, verifier: httpVerifier, idToken: signAppleId({ sub: 'synthetic-apple-http', nonce: httpProof }, now), idempotencyId: randomUUID() }, httpSession.accessToken);
      assert.equal(attachedResponse.status, 200);
      assert.deepEqual(await attachedResponse.json(), { identities: [{ provider: 'apple' }, { provider: 'google' }] });
      const notified = await post('/v1/auth/apple/notifications', { signedPayload: signProviderEvent('email-enabled', 'synthetic-apple-http', randomUUID(), now, { email: 'synthetic-http@example.com' }) });
      assert.equal(notified.status, 200);
      const rejectedNotification = await post('/v1/auth/apple/notifications', { signedPayload: 'synthetic-malformed' });
      assert.equal(rejectedNotification.status, 401);
      assert.equal((await post('/v1/auth/provider/verify', { challengeId: challenge.challengeId, padding: 'x'.repeat(8192) })).status, 413);
      assert.equal((await post('/v1/auth/provider/challenges', { provider: 'apple', idempotencyId: randomUUID(), proofChallenge: httpProof }, 'malformed')).status, 401);
      const transcript = JSON.stringify(logs);
      assert.ok(!transcript.includes('synthetic-http')); assert.ok(!transcript.includes(httpVerifier)); assert.ok(!transcript.includes(httpSession.accessToken)); assert.ok(!transcript.includes('synthetic-apple-http'));
    } finally { server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve())); }
    // Email-only deployments register no provider routes at all.
    const bare = makeServer(relay, { ipKey: 'synthetic-provider-rate-key', uploadsEnabled: false, auth: emailOnly });
    bare.listen(0, '127.0.0.1'); await once(bare, 'listening');
    try {
      const address = bare.address(); assert.ok(address && typeof address !== 'string'); const origin = `http://127.0.0.1:${address.port}`;
      for (const path of ['/v1/auth/provider/challenges', '/v1/auth/provider/verify', '/v1/me/identities', '/v1/auth/apple/notifications'])
        assert.equal((await fetch(origin + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).status, 404);
    } finally { bare.closeAllConnections(); await new Promise<void>(resolve => bare.close(() => resolve())); }
    const exposed = makeServer(relay, { ipKey: 'synthetic-provider-rate-key', uploadsEnabled: false, auth });
    exposed.listen(0, '127.0.0.1'); await once(exposed, 'listening');
    try {
      const address = exposed.address(); assert.ok(address && typeof address !== 'string'); const origin = `http://127.0.0.1:${address.port}`;
      assert.equal((await fetch(origin + '/v1/auth/apple/notifications', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).status, 404);
      assert.equal((await fetch(origin + '/v1/auth/provider/challenges', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ provider: 'apple', idempotencyId: 'bad', proofChallenge: 'bad' }) })).status, 400);
    } finally { exposed.closeAllConnections(); await new Promise<void>(resolve => exposed.close(() => resolve())); }

    now += AUTH.sessionMs; await auth.prune();
    assert.equal((await admin.query('SELECT count(*) FROM auth.provider_challenges')).rows[0].count, '0');
    assert.equal((await admin.query('SELECT count(*) FROM auth.sessions')).rows[0].count, '0');
    assert.equal((await admin.query('SELECT count(*) FROM auth.apple_notification_receipts')).rows[0].count, '6');
  } finally {
    // Close every pool before dropping the isolated test database, in the
    // publications-suite order: a FORCE drop racing an open pool emits
    // uncaught pg errors that make a successful test appear to fail.
    try { await admin.end(); } finally {
      try { await authPool.end(); } finally {
        try { await lifePool.end(); } finally {
          try { await control.query(`DROP DATABASE ${database}`); } finally {
            await control.end();
            await rm(root, { recursive: true, force: true });
          }
        }
      }
    }
  }
});
