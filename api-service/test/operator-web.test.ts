import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Script, createContext } from 'node:vm';
import { once } from 'node:events';
import { randomUUID, webcrypto } from 'node:crypto';
import { operatorPage } from '../src/lifecycle-web.ts';
import { makeServer } from '../src/http.ts';
import { Relay } from '../src/relay.ts';
import type { AccountDeletion } from '../src/account-deletion.ts';
import { MemoryRepository, budgets } from './helpers.ts';

const uuidPattern = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;

test('operator page validates its nonce and carries it on script and style', () => {
  const nonce = 'b'.repeat(32), html = operatorPage(nonce);
  assert.ok(!html.includes('__NONCE__'));
  assert.ok(html.includes(`<style nonce="${nonce}">`));
  assert.ok(html.includes(`<script nonce="${nonce}">`));
  assert.ok(!html.includes('localStorage'));
  assert.ok(!html.includes('sessionStorage'));
  assert.throws(() => operatorPage('"><script>'));
  assert.throws(() => operatorPage('short'));
});

test('operator page is served only when the lifecycle surface is configured, with hardening headers', async t => {
  const repository = new MemoryRepository(), storage = { put: async () => {}, get: async () => Buffer.alloc(0), delete: async () => {} };
  const plain = makeServer(new Relay(repository, storage, budgets), { ipKey: 'x'.repeat(32), uploadsEnabled: false });
  plain.listen(0, '127.0.0.1'); await once(plain, 'listening');
  t.after(() => { plain.closeAllConnections(); plain.close(); });
  const bare = `http://127.0.0.1:${(plain.address() as { port: number }).port}`;
  assert.equal((await fetch(bare + '/operator')).status, 404);
  const gated = makeServer(new Relay(repository, storage, budgets), { ipKey: 'x'.repeat(32), uploadsEnabled: false, deletion: {} as AccountDeletion });
  gated.listen(0, '127.0.0.1'); await once(gated, 'listening');
  t.after(() => { gated.closeAllConnections(); gated.close(); });
  const base = `http://127.0.0.1:${(gated.address() as { port: number }).port}`;
  const response = await fetch(base + '/operator');
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type')!, /text\/html/);
  const html = await response.text(), nonce = /<script nonce="([a-f0-9]{32})">/.exec(html)![1];
  const csp = response.headers.get('content-security-policy')!;
  for (const directive of [`script-src 'nonce-${nonce}'`, `style-src 'nonce-${nonce}'`, `connect-src 'self'`, `form-action 'none'`, `frame-ancestors 'none'`, `base-uri 'none'`]) assert.ok(csp.includes(directive), `missing ${directive}`);
  assert.equal(response.headers.get('x-robots-tag'), 'noindex, nofollow');
  assert.match(response.headers.get('cache-control')!, /private, no-store/);
  assert.equal((await fetch(base + '/operator', { method: 'POST' })).status, 404);
});

type Stub = {
  tag: string; value: string; checked: boolean; hidden: boolean; disabled: boolean;
  textContent: string; src: string; alt: string; children: Stub[]; firstChild: Stub | null;
  appendChild: (child: Stub) => Stub; removeChild: (child: Stub) => Stub;
  addEventListener: (event: string, listener: () => Promise<void> | void) => void;
  [key: string]: unknown;
};
function mount(html: string, fetchImpl: (path: string, options: { body?: string; headers: Record<string, string> }) => Promise<unknown>) {
  const source = /<script nonce="[a-f0-9]+">([\s\S]*?)<\/script>/.exec(html)![1], script = new Script(source);
  const innerHTMLWrites: string[] = [], storageTouches: string[] = [];
  const element = (tag: string): Stub => {
    const children: Stub[] = [];
    const el = {
      tag, value: '', checked: false, hidden: false, disabled: false, textContent: '', src: '', alt: '', children, firstChild: null as Stub | null,
      appendChild(child: Stub) { children.push(child); el.firstChild = children[0]; return child; },
      removeChild(child: Stub) { const index = children.indexOf(child); if (index >= 0) children.splice(index, 1); el.firstChild = children[0] ?? null; return child; },
      addEventListener(event: string, listener: () => Promise<void> | void) { (el as Record<string, unknown>)['on' + event] = listener; },
    } as Stub;
    Object.defineProperty(el, 'childElementCount', { get: () => children.length });
    Object.defineProperty(el, 'innerHTML', { get: () => '', set: () => { innerHTMLWrites.push(tag); } });
    return el;
  };
  const ids = ['token', 'clearToken', 'loadContent', 'loadProfiles', 'loadReports', 'contentList', 'profileList', 'reportList',
    'moreContent', 'moreProfiles', 'moreReports', 'detail', 'dTitle', 'dMeta', 'dBody', 'dAssets',
    'cItemId', 'cRevisionId', 'cAction', 'cNote', 'cReviewed', 'cReviewedIds', 'cViolatingIds', 'decideContent',
    'pReviewId', 'pAction', 'pNote', 'pReviewed', 'decideProfile', 'sAlias', 'sSuspended', 'sNote', 'decidePublisher',
    'rReportId', 'rStatus', 'rNote', 'decideReport', 'message'];
  const elements = new Map<string, Stub>(ids.map(id => [id, element(id === 'contentList' || id === 'profileList' || id === 'reportList' ? 'ul' : 'div')]));
  elements.get('detail')!.hidden = true; elements.get('cAction')!.value = 'approve';
  elements.get('pAction')!.value = 'approve'; elements.get('rStatus')!.value = 'reviewing';
  const buttons = ['clearToken', 'loadContent', 'loadProfiles', 'loadReports', 'moreContent', 'moreProfiles', 'moreReports', 'decideContent', 'decideProfile', 'decidePublisher', 'decideReport'].map(id => elements.get(id)!);
  const document = {
    getElementById: (id: string) => elements.get(id)!,
    createElement: (tag: string) => element(tag),
    querySelectorAll: () => buttons,
  };
  const blobs: string[] = [];
  const context = createContext({
    document, crypto: webcrypto, AbortSignal, TextEncoder,
    URL: { createObjectURL: () => { const url = 'blob:operator-' + blobs.length; blobs.push(url); return url; }, revokeObjectURL: () => {} },
    localStorage: { getItem: () => { storageTouches.push('localStorage'); return null; }, setItem: () => { storageTouches.push('localStorage'); } },
    sessionStorage: { getItem: () => { storageTouches.push('sessionStorage'); return null; }, setItem: () => { storageTouches.push('sessionStorage'); } },
    fetch: fetchImpl,
  });
  script.runInContext(context);
  return {
    elements, innerHTMLWrites, storageTouches, blobs,
    click: async (id: string) => { await (elements.get(id)!['onclick'] as () => Promise<void>)(); },
  };
}

const itemId = randomUUID(), revisionId = randomUUID(), assetId = randomUUID(), secondItemId = randomUUID();
const hostileTitle = '<img src=x onerror="alert(1)">Tasty <script>alert(2)</script>';
function queueFetch(requests: { path: string; method: string; body?: Record<string, unknown>; headers: Record<string, string> }[]) {
  return async (path: string, options: { body?: string; headers: Record<string, string> }) => {
    const body = options.body ? JSON.parse(options.body) as Record<string, unknown> : undefined;
    requests.push({ path, method: options.body ? 'POST' : 'GET', body, headers: options.headers });
    if (path === '/v1/operator/queue/content') return { ok: true, json: async () => ({ items: [{ id: itemId, itemId, revisionId, reason: 'source_takedown', title: hostileTitle, kind: 'recipe', identityMode: 'anonymous' }], nextCursor: secondItemId }) };
    if (path === `/v1/operator/queue/content?cursor=${secondItemId}`) return { ok: true, json: async () => ({ items: [{ id: secondItemId, itemId: secondItemId, revisionId, reason: 'changed_content', title: 'Second dish', kind: 'recipe', identityMode: 'named' }], nextCursor: null }) };
    if (path.startsWith('/v1/operator/items/') && !path.includes('/assets/')) return {
      ok: true, json: async () => ({
        itemId, revisionId, publisherAlias: 'alias-7',
        payload: { title: hostileTitle, kind: 'recipe', recipes: [{ title: '<b>dish</b>', description: 'Slow <i>smoke</i>', ingredients: [{ name: 'brisket', amount: '2 <kg>' }], instructions: [{ title: 'Rest', details: 'Wait <em>now</em>' }] }] },
        assets: [{ id: assetId, url: `/v1/operator/items/${itemId}/assets/${assetId}`, caption: 'hero <&> shot' }],
      }),
    };
    if (path.includes('/assets/')) return { ok: true, blob: async () => ({ type: 'image/jpeg', size: 4 }) };
    if (path === '/v1/operator/content') return { ok: true, json: async () => ({ itemId: body!.itemId, revisionId: body!.revisionId, status: 'taken_down' }) };
    throw new Error('Unexpected operator request ' + path);
  };
}

test('operator queues load with the bearer token, paginate by cursor and never mutate on read', async () => {
  const html = operatorPage('c'.repeat(32));
  const requests: { path: string; method: string; body?: Record<string, unknown>; headers: Record<string, string> }[] = [];
  const page = mount(html, queueFetch(requests));
  assert.equal(requests.length, 0, 'Opening the page caused network activity');
  page.elements.get('token')!.value = 'operator-token-value';
  await page.click('loadContent');
  assert.equal(requests.length, 1);
  assert.equal(requests[0].path, '/v1/operator/queue/content');
  assert.equal(requests[0].method, 'GET');
  assert.equal(requests[0].headers.Authorization, 'Bearer operator-token-value');
  assert.equal(page.elements.get('moreContent')!.hidden, false);
  await page.click('moreContent');
  assert.equal(requests.length, 2);
  assert.equal(requests[1].path, `/v1/operator/queue/content?cursor=${secondItemId}`);
  assert.equal(requests[1].method, 'GET');
  assert.equal(page.elements.get('contentList')!.children.length, 2);
  assert.equal(page.elements.get('moreContent')!.hidden, true);
  assert.equal(page.storageTouches.length, 0);
});

test('operator detail renders authored text as text and fetches media with the bearer token', async () => {
  const html = operatorPage('d'.repeat(32));
  const requests: { path: string; method: string; body?: Record<string, unknown>; headers: Record<string, string> }[] = [];
  const page = mount(html, queueFetch(requests));
  page.elements.get('token')!.value = 'operator-token-value';
  await page.click('loadContent');
  const open = page.elements.get('contentList')!.children[0].children.find(child => child.tag === 'button')!;
  await (open['onclick'] as () => Promise<void>)();
  assert.ok(requests.every(request => request.method === 'GET'), 'Reads must never mutate');
  const detail = requests.find(request => request.path === `/v1/operator/items/${itemId}`);
  assert.equal(detail!.headers.Authorization, 'Bearer operator-token-value');
  assert.equal(page.elements.get('dTitle')!.textContent, hostileTitle);
  const bodyText = page.elements.get('dBody')!.children.map(child => child.textContent).join('\n');
  assert.ok(bodyText.includes('<b>dish</b>') && bodyText.includes('2 <kg>') && bodyText.includes('Wait <em>now</em>'));
  const media = requests.find(request => request.path.includes('/assets/'));
  assert.equal(media!.headers.Authorization, 'Bearer operator-token-value');
  const figure = page.elements.get('dAssets')!.children[0], image = figure.children.find(child => child.tag === 'img')!;
  assert.equal(image.src, page.blobs[0]);
  assert.ok(figure.children.find(child => child.tag === 'figcaption')!.textContent.includes('hero <&> shot'));
  assert.equal(page.innerHTMLWrites.length, 0, 'Authored content must never flow through innerHTML');
});

test('operator content decision posts exact fields with a fresh operation id and refreshes its queue', async () => {
  const html = operatorPage('e'.repeat(32));
  const requests: { path: string; method: string; body?: Record<string, unknown>; headers: Record<string, string> }[] = [];
  const page = mount(html, queueFetch(requests));
  page.elements.get('token')!.value = 'operator-token-value';
  await page.click('loadContent');
  const open = page.elements.get('contentList')!.children[0].children.find(child => child.tag === 'button')!;
  await (open['onclick'] as () => Promise<void>)();
  page.elements.get('cAction')!.value = 'takedown';
  page.elements.get('cNote')!.value = 'Stolen photo';
  page.elements.get('cReviewed')!.checked = true;
  page.elements.get('cReviewedIds')!.value = assetId;
  page.elements.get('cViolatingIds')!.value = assetId;
  const before = requests.length;
  await page.click('decideContent');
  const post = requests.find(request => request.path === '/v1/operator/content' && request.method === 'POST')!;
  assert.deepEqual(Object.keys(post.body!).sort(), ['action', 'itemId', 'note', 'operationId', 'reviewedAssetIds', 'reviewedText', 'revisionId', 'violatingAssetIds']);
  assert.equal(post.body!.itemId, itemId);
  assert.equal(post.body!.revisionId, revisionId);
  assert.equal(post.body!.action, 'takedown');
  assert.equal(post.body!.reviewedText, true);
  assert.deepEqual(post.body!.reviewedAssetIds, [assetId]);
  assert.deepEqual(post.body!.violatingAssetIds, [assetId]);
  assert.match(String(post.body!.operationId), uuidPattern);
  assert.equal(post.headers.Authorization, 'Bearer operator-token-value');
  const reloads = requests.slice(before).filter(request => request.path === '/v1/operator/queue/content' && request.method === 'GET');
  assert.equal(reloads.length, 1, 'A successful action refreshes its queue');
  await page.click('decideContent');
  const posts = requests.filter(request => request.path === '/v1/operator/content' && request.method === 'POST');
  assert.notEqual(posts[0].body!.operationId, posts[1].body!.operationId);
});

test('operator page reports API failures plainly and keeps the token out of storage', async () => {
  const html = operatorPage('f'.repeat(32));
  const requests: { path: string; method: string; headers: Record<string, string> }[] = [];
  const page = mount(html, async (path: string, options: { headers: Record<string, string> }) => {
    requests.push({ path, method: 'GET', headers: options.headers });
    return { ok: false, status: 403, json: async () => ({ error: { code: 'operator_required' } }) };
  });
  page.elements.get('token')!.value = 'wrong-token';
  await page.click('loadReports');
  assert.match(page.elements.get('message')!.textContent, /Request failed \(403: operator_required\)/);
  assert.equal(page.storageTouches.length, 0);
});
