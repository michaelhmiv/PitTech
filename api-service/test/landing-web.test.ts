import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtemp, rm, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { makeServer } from '../src/http.ts';
import { Relay } from '../src/relay.ts';
import { FileStorage } from '../src/storage.ts';
import { MemoryRepository, budgets } from './helpers.ts';

test('HTTP serves the landing page at the root and its assets, without shadowing the link routes', async t => {
  const root = await mkdtemp(join(tmpdir(), 'landing-web-')); t.after(() => rm(root, { force: true, recursive: true }));
  const fingerprint = Array.from({ length: 32 }, () => 'AB').join(':');
  const server = makeServer(new Relay(new MemoryRepository(), new FileStorage(root), budgets), {
    ipKey: 'x'.repeat(32), uploadsEnabled: false, tempRoot: join(root, 'uploads'),
    links: { appleAppIds: ['R42LWFBXBH.com.pittech'], androidCertificates: [fingerprint] },
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening'); t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;

  const page = await fetch(base + '/');
  assert.equal(page.status, 200); assert.match(page.headers.get('content-type')!, /text\/html/);
  const html = await page.text();
  assert.ok(html.includes('If it happened at the pit'), 'landing headline');
  assert.ok(html.includes('https://testflight.apple.com/join/bvq7vKfn'), 'TestFlight link');
  assert.ok(html.includes('https://play.google.com/store/apps/details?id=com.pittech'), 'Google Play link');
  assert.ok(html.includes('/screenshots/live-board.png') && html.includes('/assets/app-icon.png'), 'root-relative asset paths');

  for (const [path, file, type] of [
    ['/assets/app-icon.png', '../web/landing/app-icon.png', 'image/png'],
    ['/screenshots/live-board.png', '../web/landing/live-board.png', 'image/png'],
    ['/screenshots/timeline.png', '../web/landing/timeline.png', 'image/png'],
    ['/screenshots/history.png', '../web/landing/history.png', 'image/png'],
    ['/assets/fonts/oswald-600.ttf', '../web/landing/fonts/oswald-600.ttf', 'font/ttf'],
    ['/assets/fonts/barlow-400.ttf', '../web/landing/fonts/barlow-400.ttf', 'font/ttf'],
    ['/assets/fonts/ibm-plex-mono-500.ttf', '../web/landing/fonts/ibm-plex-mono-500.ttf', 'font/ttf'],
  ] as const) {
    const res = await fetch(base + path);
    assert.equal(res.status, 200, path); assert.equal(res.headers.get('content-type'), type, path);
    assert.equal((await res.arrayBuffer()).byteLength, (await stat(new URL(file, import.meta.url))).size, path);
  }

  // The routes the apex already carries keep their own owners.
  const aasa = await fetch(base + '/.well-known/apple-app-site-association');
  assert.equal(aasa.status, 200); assert.deepEqual((await aasa.json() as { applinks: { details: { appIDs: string[] }[] } }).applinks.details[0].appIDs, ['R42LWFBXBH.com.pittech']);
  const assetlinks = await fetch(base + '/.well-known/assetlinks.json');
  assert.equal(assetlinks.status, 200);
  const pack = await fetch(base + '/s/0000-0000-0000-0000');
  assert.equal(pack.status, 404); assert.ok((await pack.text()).includes('This code is unavailable'));
  const auth = await fetch(base + '/auth');
  assert.equal(auth.status, 200); assert.ok((await auth.text()).includes('Sign in to PitTech'));
  const missing = await fetch(base + '/no-such-page');
  assert.equal(missing.status, 404); assert.match(missing.headers.get('content-type')!, /application\/json/);
});
