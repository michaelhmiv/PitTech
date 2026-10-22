import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { onlineEnvironment } from '../src/online-environment.ts';
import { association, landing, linkConfig } from '../src/web.ts';

const fixture = JSON.parse(await readFile(new URL('../../contracts/fixtures/online-environments-v1.json', import.meta.url), 'utf8')) as {
  profiles: { environment: string; apiOrigin: string; publicOrigin: string; linkScheme: string }[]; invalidEnvironments: string[];
};
test('server uses the same fixed environment origins and app link schemes as both native clients', () => {
  for (const profile of fixture.profiles) {
    const online = onlineEnvironment(profile.environment);
    assert.equal(online.apiOrigin, profile.apiOrigin);
    assert.equal(online.publicOrigin, profile.publicOrigin);
    assert.equal(online.linkScheme, profile.linkScheme);
  }
  for (const value of fixture.invalidEnvironments) assert.throws(() => onlineEnvironment(value));
  assert.equal(onlineEnvironment().environment, 'production');
});
test('staging association files select the intended test app and reject arbitrary or mixed identities', () => {
  const certificates = Array(32).fill('AB').join(':');
  const links = linkConfig({ ONLINE_ENVIRONMENT: 'staging', APPLE_APP_IDS: 'ABCDEFGHIJ.com.pittech.staging', ANDROID_APP_LINK_CERTIFICATES: certificates });
  const android = association('/.well-known/assetlinks.json', links);
  assert.ok(Array.isArray(android));
  assert.equal(android[0].target.package_name, 'com.pittech.dev');
  const apple = association('/.well-known/apple-app-site-association', links);
  assert.ok(apple && 'applinks' in apple);
  assert.deepEqual(apple.applinks.details[0].appIDs, ['ABCDEFGHIJ.com.pittech.staging']);
  const storeTest = linkConfig({ ONLINE_ENVIRONMENT: 'staging', ANDROID_APP_LINK_PACKAGE: 'com.pittech', ANDROID_APP_LINK_CERTIFICATES: certificates });
  assert.equal(storeTest.androidPackageName, 'com.pittech');
  const dual = association('/.well-known/assetlinks.json', linkConfig({ ONLINE_ENVIRONMENT: 'staging', ANDROID_APP_LINK_CERTIFICATES: certificates, ANDROID_APP_LINK_PACKAGE_2: 'com.pittech', ANDROID_APP_LINK_CERTIFICATES_2: Array(32).fill('CD').join(':') }));
  assert.ok(Array.isArray(dual));
  assert.equal(dual.length, 2);
  assert.equal(dual[0].target.package_name, 'com.pittech.dev');
  assert.deepEqual(dual[0].target.sha256_cert_fingerprints, [certificates]);
  assert.equal(dual[1].target.package_name, 'com.pittech');
  assert.throws(() => linkConfig({ ONLINE_ENVIRONMENT: 'staging', ANDROID_APP_LINK_PACKAGE_2: 'com.pittech' }));
  assert.throws(() => linkConfig({ ONLINE_ENVIRONMENT: 'production', APPLE_APP_IDS: 'ABCDEFGHIJ.com.pittech.staging' }));
  assert.throws(() => linkConfig({ ANDROID_APP_LINK_PACKAGE: 'com.pittech.dev' }));
  assert.throws(() => linkConfig({ ONLINE_ENVIRONMENT: 'staging', ANDROID_APP_LINK_PACKAGE: 'attacker.app' }));
  const now = Date.now();
  const page = landing('0123-4567-89AB-CDEF', { code: '0123-4567-89AB-CDEF', state: 'active', expiresAt: now + 1000, dishes: 1, kind: 'recipe' } as Parameters<typeof landing>[1], now, links);
  assert.match(page.html, /href="pittech-staging:\/\/share\//);
  assert.doesNotMatch(page.html, /href="pittech:\/\//);
});
