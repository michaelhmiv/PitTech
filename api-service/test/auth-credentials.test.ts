import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AUTH, AuthSecrets, credential, emailAddress, equalHash, isCredential, proofHash } from '../src/auth-credentials.ts';
import { ResendAuthMailer, signInEmail, type SignInMail } from '../src/auth-email.ts';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';

test('auth credentials: canonical entropy, keyed code hashes, separated authenticated encrypted envelopes', () => {
  const pepper = credential(), key = credential(), secrets = new AuthSecrets(pepper, key);
  assert.throws(() => new AuthSecrets(pepper, pepper)); assert.throws(() => new AuthSecrets('short', key));
  const token = credential(); assert.equal(isCredential(token), true); assert.equal(isCredential(token + '='), false);
  assert.equal(isCredential(token.slice(0, -1) + '!'), false); assert.throws(() => proofHash('short'));
  assert.equal(equalHash(proofHash(token), proofHash(token)), true); assert.equal(equalHash('invalid', 'invalid'), false);
  assert.notEqual(secrets.hash('code/id-one', '123456'), secrets.hash('code/id-two', '123456'));
  assert.notEqual(secrets.hash('email', 'cook@example.com'), secrets.hash('ip', 'cook@example.com'));
  const payload = { email: 'cook@example.com', code: '123456', token }, sealed = secrets.seal('mail/id-one', payload);
  assert.equal(sealed.includes(payload.code), false); assert.equal(sealed.includes(payload.email), false);
  assert.deepEqual(secrets.open('mail/id-one', sealed), payload);
  assert.throws(() => secrets.open('mail/id-two', sealed)); assert.throws(() => new AuthSecrets(credential(), credential()).open('mail/id-one', sealed));
  const tampered = Buffer.from(sealed, 'base64url'); tampered[tampered.length - 1] ^= 1;
  assert.throws(() => secrets.open('mail/id-one', tampered.toString('base64url')));
  for (let i = 0; i < 100; i++) assert.match(secrets.code(), /^\d{6}$/);
  assert.equal(AUTH.challengeMs, 900_000); assert.equal(AUTH.attempts, 5);
});
test('email canonicalization rejects header injection, malformed addresses and ambiguous Unicode', () => {
  assert.equal(emailAddress(' Cook+test@Example.COM '), 'cook+test@example.com');
  const native = JSON.parse(readFileSync(new URL('../../contracts/fixtures/auth-native-v1.json', import.meta.url), 'utf8'));
  assert.equal(proofHash(native.verifier), native.proof);
  for (const [input, expected] of native.emails) assert.equal(emailAddress(input), expected);
  for (const input of native.invalidEmails) assert.throws(() => emailAddress(input));
  for (const value of [null, {}, 'a@b', '.a@example.com', 'a..b@example.com', 'a@-bad.com', 'a@example..com', 'a@example.com\r\nBcc: victim@example.com', 'cook@exämple.com', 'a'.repeat(65) + '@example.com']) assert.throws(() => emailAddress(value));
});
test('Resend adapter sends accessible code/link alternatives with fixed origin, idempotency, no redirects and redacted failures', async () => {
  const mail: SignInMail = { email: 'synthetic@example.com', challengeId: randomUUID(), code: '012345', token: credential() };
  const email = signInEmail(mail, 'https://pittech.app'); assert.ok(email.text.includes(mail.code)); assert.ok(email.html.includes('lang="en"'));
  assert.ok(email.text.includes('/auth#challenge=')); assert.ok(!email.html.includes('<script')); assert.ok(!email.html.includes('<img'));
  assert.throws(() => signInEmail(mail, 'https://attacker.invalid'));
  const calls: { url: string; init: RequestInit }[] = [];
  const fake = (async (url, init) => { calls.push({ url: String(url), init: init! }); return new Response('{}', { status: 200 }); }) as typeof fetch;
  const sender = new ResendAuthMailer('re_synthetic_test_only', 'signin@auth.pittech.app', 'https://pittech.app', fake);
  await sender.send(mail); await sender.send(mail);
  assert.equal(calls[0].url, 'https://api.resend.com/emails'); assert.equal(calls[0].init.redirect, 'error');
  const headers = calls[0].init.headers as Record<string, string>;
  assert.equal(headers['Idempotency-Key'], 'pittech-auth/' + mail.challengeId); assert.equal(calls[0].init.body, calls[1].init.body);
  assert.equal(JSON.parse(calls[0].init.body as string).to[0], mail.email);
  assert.throws(() => new ResendAuthMailer('re_synthetic', 'signin@auth.pittech.app', 'https://staging.pittech.app', fake));
  const failure = new ResendAuthMailer('re_synthetic', 'signin@auth.pittech.app', 'https://pittech.app', (async () => { throw new Error(mail.email + mail.token); }) as typeof fetch);
  await assert.rejects(failure.send(mail), e => (e as Error).message === 'email_delivery_deferred' && !String(e).includes(mail.email));
});
