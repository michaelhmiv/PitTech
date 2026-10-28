import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { mkdtemp, readFile, readdir, rm, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import proxyaddr from 'proxy-addr';
import { get } from 'node:http';
import { makeServer } from '../src/http.ts';
import { digest } from '../src/core.ts';
import { Relay } from '../src/relay.ts';
import { FileStorage } from '../src/storage.ts';
import { MemoryRepository, budgets, golden } from './helpers.ts';

test('IPv4-mapped IPv6 proxy subnets do not trust addresses outside their subnet', () => {
  const trust = proxyaddr.compile(['::ffff:192.0.2.0/120']);
  assert.equal(trust('192.0.2.10', 0), true); assert.equal(trust('::ffff:192.0.2.10', 0), true);
  assert.equal(trust('203.0.113.10', 0), false); assert.equal(trust('::ffff:203.0.113.10', 0), false);
  assert.equal(proxyaddr.compile(['::ffff:127.0.0.1/8'])('203.0.113.10', 0), false);
});

test('HTTP validates a real native pack in its worker, supports idempotent download and owner-only renew/revoke, and redacts logs', async t => {
  const root = await mkdtemp(join(tmpdir(), 'relay-http-')); t.after(() => rm(root, { force: true, recursive: true }));
  const repository = new MemoryRepository(), relay = new Relay(repository, new FileStorage(root), budgets), logs: unknown[] = [];
  const server = makeServer(relay, { ipKey: 'x'.repeat(32), uploadsEnabled: true, tempRoot: join(root, 'uploads'), log: row => logs.push(row) });
  server.listen(0, '127.0.0.1'); await once(server, 'listening'); t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`, owner = randomBytes(32).toString('base64url'), key = randomUUID();
  const headers = { 'Content-Type': 'application/octet-stream', 'Authorization': `Bearer ${owner}`, 'Idempotency-Key': key };
  const input = new Uint8Array(await readFile(golden('native')));
  const uploaded = await fetch(base + '/packs', { method: 'POST', headers, body: input }); assert.equal(uploaded.status, 201);
  const receipt = await uploaded.json() as { code: string; sha256: string; bytes: number };
  const retry = await fetch(base + '/packs', { method: 'POST', headers, body: input }); assert.equal(retry.status, 201); assert.deepEqual(await retry.json(), receipt);
  assert.deepEqual(await readdir(join(root, 'uploads')), []);
  const download = await fetch(base + '/packs/' + receipt.code); assert.equal(download.status, 200); assert.equal(download.headers.get('x-content-sha256'), receipt.sha256);
  assert.equal((await download.arrayBuffer()).byteLength, receipt.bytes); assert.match(download.headers.get('cache-control')!, /no-store/);
  assert.equal((await fetch(base + '/packs')).status, 404);
  const wrong = { Authorization: `Bearer ${randomBytes(32).toString('base64url')}` };
  assert.equal((await fetch(base + `/packs/${receipt.code}/renew`, { method: 'POST', headers: wrong })).status, 404);
  assert.equal((await fetch(base + `/packs/${receipt.code}/renew`, { method: 'POST', headers })).status, 200);
  const malformed = await fetch(base + '/packs', { method: 'POST', headers: { ...headers, 'Idempotency-Key': randomUUID() }, body: 'private invalid content' }); assert.equal(malformed.status, 422);
  assert.equal((await fetch(base + '/packs/' + receipt.code, { method: 'DELETE', headers })).status, 204);
  assert.equal((await fetch(base + '/packs/' + receipt.code)).status, 404);
  const log = JSON.stringify(logs); for (const privateValue of [owner, receipt.code, '127.0.0.1', 'private invalid content']) assert.ok(!log.includes(privateValue));
});
test('rate limits count unknown codes and failed upload attempts; untrusted forwarding cannot evade limits', async t => {
  const repository = new MemoryRepository(), relay = new Relay(repository, { put: async () => {}, get: async () => Buffer.alloc(0), delete: async () => {} }, budgets);
  const server = makeServer(relay, { ipKey: 'x'.repeat(32), uploadsEnabled: true }); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); }); const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  for (let i = 0; i < 121; i++) assert.equal((await fetch(base + '/packs/0000-0000-0000-0000', { headers: { 'X-Forwarded-For': `203.0.113.${i}` } })).status, i < 120 ? 404 : 429);
  for (let i = 0; i < 21; i++) assert.equal((await fetch(base + '/packs', { method: 'POST', headers: { 'X-Forwarded-For': `198.51.100.${i}` } })).status, i < 20 ? 401 : 429);
});
test('configured proxy walks from the socket to the nearest untrusted address', async t => {
  const repository = new MemoryRepository(), relay = new Relay(repository, { put: async () => {}, get: async () => Buffer.alloc(0), delete: async () => {} }, budgets);
  const server = makeServer(relay, { ipKey: 'x'.repeat(32), uploadsEnabled: true, trustedProxies: ['loopback'] }); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); }); const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  for (let i = 0; i < 21; i++) assert.equal((await fetch(base + '/packs', { method: 'POST', headers: { 'X-Forwarded-For': `192.0.2.${i}, 203.0.113.9` } })).status, i < 20 ? 401 : 429);
  assert.equal(repository.rates.size, 1);
});
test('pausing uploads leaves download and owner revocation available', async t => {
  const body = Buffer.from('synthetic private object');
  const repository = new MemoryRepository(), relay = new Relay(repository, { put: async () => {}, get: async () => body, delete: async () => {} }, budgets);
  const server = makeServer(relay, { ipKey: 'x'.repeat(32), uploadsEnabled: false }); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); }); const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  assert.equal((await fetch(base + '/packs', { method: 'POST' })).status, 503);
  assert.equal((await fetch(base + '/packs/0000-0000-0000-0000')).status, 404);
  const hash = digest(body), owner = randomBytes(32).toString('base64url');
  const r = await relay.upload('unused', { kind: 'recipe', dishes: 1, bytes: body.length, sha256: hash }, owner, randomUUID());
  assert.equal((await fetch(base + '/packs/' + r.code)).status, 200);
  assert.equal((await fetch(base + '/packs/' + r.code, { method: 'DELETE', headers: { Authorization: 'Bearer ' + owner } })).status, 204);
});

test('slow downloads retain their concurrency slots until the response closes', async t => {
  const body = Buffer.alloc(8 * 1024 ** 2), repository = new MemoryRepository();
  const relay = new Relay(repository, { put: async () => {}, get: async () => body, delete: async () => {} }, budgets);
  const r = await relay.upload('unused', { kind: 'recipe', dishes: 1, bytes: body.length, sha256: digest(body) }, randomBytes(32).toString('base64url'), randomUUID());
  const server = makeServer(relay, { ipKey: 'x'.repeat(32), uploadsEnabled: false }); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); }); const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  for (let i = 0; i < 2; i++) await new Promise<void>((resolve, reject) => {
    const client = get(base + '/packs/' + r.code, response => { response.pause(); t.after(() => { response.destroy(); client.destroy(); }); resolve(); });
    client.on('error', reject);
  });
  assert.equal((await fetch(base + '/packs/' + r.code)).status, 503);
});

test('HTTP serves the first-party Ember logo that transactional email references', async t => {
  const root = await mkdtemp(join(tmpdir(), 'relay-brand-')); t.after(() => rm(root, { force: true, recursive: true }));
  const server = makeServer(new Relay(new MemoryRepository(), new FileStorage(root), budgets), { ipKey: 'x'.repeat(32), uploadsEnabled: false, tempRoot: join(root, 'uploads') });
  server.listen(0, '127.0.0.1'); await once(server, 'listening'); t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  const logo = await fetch(base + '/brand/ember-logo.png');
  assert.equal(logo.status, 200); assert.equal(logo.headers.get('content-type'), 'image/png');
  assert.match(logo.headers.get('cache-control')!, /public/);
  const bytes = new Uint8Array(await logo.arrayBuffer());
  assert.deepEqual([...bytes.subarray(0, 4)], [0x89, 0x50, 0x4e, 0x47]);
  assert.equal(bytes.byteLength, (await stat(new URL('../web/ember-logo.png', import.meta.url))).size);
});
