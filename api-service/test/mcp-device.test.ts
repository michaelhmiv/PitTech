import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { ApiError } from '../src/core.ts';
import { digest } from '../src/core.ts';
import { stable } from '../src/pack.ts';
import { McpGrants, type McpGrantRecord, type McpGrantStore, type McpPrincipal } from '../src/mcp-grants.ts';
import { AuthSecrets, credential } from '../src/auth-credentials.ts';
import { PrivateLibraryService, type SnapshotReadWriter } from '../src/mcp-library.ts';
import { McpServer } from '../src/mcp-server.ts';
import { syncTransition, type SyncEnvelope } from '../src/sync-envelope.ts';
import { privateLibraryEnvelope } from '../src/sync-projection.ts';
import {
  actionClassOf, validateDeviceAction,
  type McpDeviceCommandRecord, type McpDeviceRecord, type McpDeviceStore,
} from '../src/mcp-device.ts';
import { makeServer } from '../src/http.ts';
import { Relay } from '../src/relay.ts';
import type { Authentication } from '../src/auth.ts';
import { MemoryRepository, budgets } from './helpers.ts';

const NOW = 1_800_000_000_000;

class MemoryGrantStore implements McpGrantStore {
  rows = new Map<string, McpGrantRecord>(); retired = new Map<string, string>();
  async insert(g: McpGrantRecord) { this.rows.set(g.id, structuredClone(g)); }
  async byId(id: string) { const g = this.rows.get(id); return g ? structuredClone(g) : undefined; }
  async byTokenHash(h: string) { for (const g of this.rows.values()) if (g.tokenHash === h) return structuredClone(g); }
  async listForUser(userId: string) { return [...this.rows.values()].filter(g => g.userId === userId).map(g => structuredClone(g)); }
  async revoke(id: string, userId: string, at: number) { const g = this.rows.get(id); if (!g || g.userId !== userId || g.revokedAt !== null) return false; g.revokedAt = at; return true; }
  async revokeAll(userId: string, at: number) { let n = 0; for (const g of this.rows.values()) if (g.userId === userId && g.revokedAt === null) { g.revokedAt = at; n++; } return n; }
  async touchLastUsed(id: string, at: number) { const g = this.rows.get(id); if (g) g.lastUsedAt = at; }
  async rotateTokenHash(id: string, expectedHash: string, newHash: string) { const g = this.rows.get(id); if (!g || g.tokenHash !== expectedHash || g.revokedAt !== null) return false; g.tokenHash = newHash; return true; }
  async revokeFamily(familyId: string, at: number) { let n = 0; for (const g of this.rows.values()) if (g.familyId === familyId && g.revokedAt === null) { g.revokedAt = at; n++; } return n; }
  async retireHash(tokenHash: string, familyId: string) { this.retired.set(tokenHash, familyId); }
  async retiredFamily(tokenHash: string) { return this.retired.get(tokenHash); }
}
class FakeSnapshots implements SnapshotReadWriter {
  version = 0; envelope: SyncEnvelope | null = null;
  private receipts = new Map<string, { hash: string; receipt: { version: number; mutationId: string; sha256: string } }>();
  async read() { return { version: this.version, envelope: this.envelope ? structuredClone(this.envelope) : null }; }
  async status() { return { version: this.version, updatedAt: this.version ? NOW : null }; }
  async receiptFor(_user: string, mutationId: string) { return this.receipts.get(mutationId)?.receipt; }
  async put(_user: string, value: unknown) {
    const next = privateLibraryEnvelope(value);
    const hash = digest(Buffer.from(stable(next)));
    const prior = this.receipts.get(next.mutationId);
    if (prior) { if (prior.hash !== hash) throw new ApiError(409, 'retry_content_changed'); return structuredClone(prior.receipt); }
    if (next.baseVersion !== this.version) throw new ApiError(409, 'sync_version_changed');
    syncTransition(this.envelope ?? undefined, next);
    this.envelope = structuredClone(next);
    this.version = next.baseVersion + 1;
    const receipt = { version: this.version, mutationId: next.mutationId, sha256: hash };
    this.receipts.set(next.mutationId, { hash, receipt });
    return structuredClone(receipt);
  }
}
class MemoryDeviceStore implements McpDeviceStore {
  devices = new Map<string, McpDeviceRecord>();
  commands = new Map<string, McpDeviceCommandRecord>();
  private key = (userId: string, deviceId: string) => `${userId}|${deviceId}`;
  async upsertDevice(userId: string, device: McpDeviceRecord) {
    const prior = this.devices.get(this.key(userId, device.deviceId));
    const merged = { ...device, createdAt: prior?.createdAt ?? device.createdAt };
    this.devices.set(this.key(userId, device.deviceId), structuredClone(merged));
    return structuredClone(merged);
  }
  async listDevices(userId: string) { return [...this.devices.values()].filter(d => this.key(userId, d.deviceId).startsWith(userId)).map(d => structuredClone(d)); }
  async deviceFor(userId: string, deviceId: string) { const d = this.devices.get(this.key(userId, deviceId)); return d ? structuredClone(d) : undefined; }
  async removeDevice(userId: string, deviceId: string, at: number) {
    for (const c of this.commands.values()) {
      if (c.userId === userId && c.deviceId === deviceId && c.state === 'queued_for_device') { c.state = 'canceled'; c.resolvedAt = at; c.receipt = { outcome: 'canceled', detail: 'device unregistered' }; }
    }
    return this.devices.delete(this.key(userId, deviceId));
  }
  async touchDevice(userId: string, deviceId: string, at: number) { const d = this.devices.get(this.key(userId, deviceId)); if (d) { d.lastSeenAt = at; d.updatedAt = at; } }
  async createCommand(record: McpDeviceCommandRecord) { this.commands.set(record.id, structuredClone(record)); }
  async commandFor(userId: string, id: string) { const c = this.commands.get(id); return c && c.userId === userId ? structuredClone(c) : undefined; }
  async listCommands(userId: string, state?: string) {
    return [...this.commands.values()].filter(c => c.userId === userId && (state === undefined || c.state === state))
      .sort((a, b) => b.createdAt - a.createdAt).slice(0, 100).map(c => structuredClone(c));
  }
  async cancelCommand(userId: string, id: string, at: number) {
    const c = this.commands.get(id);
    if (!c || c.userId !== userId || (c.state !== 'saved' && c.state !== 'queued_for_device')) return false;
    c.state = 'canceled'; c.resolvedAt = at; c.receipt = { outcome: 'canceled', detail: 'canceled by request' };
    return true;
  }
  async expireStale(userId: string, now: number) {
    for (const c of this.commands.values()) {
      if (c.userId === userId && (c.state === 'saved' || c.state === 'queued_for_device') && c.expiresAt <= now) { c.state = 'expired'; c.resolvedAt = now; }
    }
  }
  async claim(userId: string, deviceId: string, classes: readonly string[], now: number, limit: number) {
    await this.expireStale(userId, now);
    const out: McpDeviceCommandRecord[] = [];
    for (const c of [...this.commands.values()].sort((a, b) => a.createdAt - b.createdAt)) {
      if (out.length >= limit) break;
      if (c.userId !== userId || c.deviceId !== deviceId || c.state !== 'queued_for_device' || c.expiresAt <= now) continue;
      if (!classes.includes(actionClassOf(c.action))) continue;
      if (c.claimedAt !== null && c.claimedAt > now - 600_000) continue;
      c.claimedAt = now; c.claimedBy = deviceId;
      out.push(structuredClone(c));
    }
    return out;
  }
  async resolve(userId: string, deviceId: string, commandId: string, receipt: Record<string, unknown>, at: number) {
    const c = this.commands.get(commandId);
    if (!c || c.userId !== userId || c.deviceId !== deviceId || c.claimedBy !== deviceId || c.state !== 'queued_for_device') return false;
    const outcome = receipt.outcome;
    if (outcome !== 'applied' && outcome !== 'needs_attention' && outcome !== 'failed') return false;
    c.state = outcome; c.resolvedAt = at; c.receipt = structuredClone(receipt);
    return true;
  }
}

function setup() {
  let now = NOW;
  const grants = new McpGrants(new MemoryGrantStore(), new AuthSecrets(credential(), credential()), { state: async () => 'active' }, () => now);
  const snapshots = new FakeSnapshots();
  const device = new MemoryDeviceStore();
  const library = new PrivateLibraryService(grants, snapshots, { writer: snapshots, writesEnabled: true, clock: () => now, device });
  const server = new McpServer(library);
  const principal: McpPrincipal = {
    grantId: 'g', userId: 'user-1',
    scopes: ['library:read', 'library:write', 'library:delete', 'planning:write', 'device_actions:request'],
  };
  const call = async (name: string, args: Record<string, unknown> = {}, as: McpPrincipal = principal) => {
    const response = await server.handle(as, { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name, arguments: args } });
    return (response as { result: { isError: boolean; structuredContent?: Record<string, unknown>; content: { text: string }[] } }).result;
  };
  const list = async (as: McpPrincipal = principal) => {
    const response = await server.handle(as, { jsonrpc: '2.0', id: 1, method: 'tools/list', params: {} });
    return ((response as { result: { tools: { name: string }[] } }).result.tools).map(t => t.name);
  };
  return { device, call, list, principal, setNow: (v: number) => { now = v; }, now: () => now };
}
type Setup = ReturnType<typeof setup>;

const DEVICE_ID = '11111111-2222-4333-8444-555555555555';
async function registerPhone(s: Setup, classes: string[] = ['reminder', 'timer', 'preferences']) {
  await s.device.upsertDevice('user-1', {
    deviceId: DEVICE_ID, name: 'Pixel', platform: 'android', actionClasses: classes,
    lastSeenAt: s.now(), createdAt: s.now(), updatedAt: s.now(),
  });
}

test('device tools appear only with the device store and the scope', async () => {
  const s = setup();
  const names = await s.list();
  for (const name of ['list_my_devices', 'request_device_action', 'get_device_action', 'list_device_actions', 'cancel_device_action']) {
    assert.ok(names.includes(name), `missing ${name}`);
  }
  const noScope: McpPrincipal = { ...s.principal, scopes: ['library:read'] };
  const hidden = await s.list(noScope);
  assert.ok(!hidden.includes('request_device_action'), 'device tools must hide without device_actions:request');
});

test('the action enum is closed and per-action schemas are enforced', () => {
  assert.throws(() => validateDeviceAction('grill.ignite', {}), /Unsupported action/);
  assert.throws(() => validateDeviceAction('timer.start', { label: 'x', durationMinutes: 0 }), /between 1 and 1440/);
  assert.throws(() => validateDeviceAction('timer.start', { label: 'x', durationMinutes: 5, endpoint: 'https://evil' }), /Unknown parameter/);
  assert.throws(() => validateDeviceAction('preferences.update', {}), /at least one/);
  assert.throws(() => validateDeviceAction('preferences.update', { themeMode: 'neon' }), /themeMode must be/);
  assert.deepEqual(validateDeviceAction('collection.pause', {}), { action: 'collection.pause', params: {} });
  assert.deepEqual(validateDeviceAction('collection.set_cadence', { intervalSeconds: 120 }), { action: 'collection.set_cadence', params: { intervalSeconds: 120 } });
  assert.throws(() => validateDeviceAction('collection.set_cadence', { intervalSeconds: 90 }), /one of 15, 30, 60, 120 or 300/);
  assert.throws(() => validateDeviceAction('collection.set_cadence', { intervalMinutes: 2 }), /Unknown parameter/);
  assert.equal(actionClassOf('reminder.snooze'), 'reminder');
});

test('a request with no registered device is saved as an honest handoff, never "applied"', async () => {
  const s = setup();
  const listed = await s.call('list_my_devices');
  assert.equal((listed.structuredContent as { devices: unknown[] }).devices.length, 0);
  const requested = await s.call('request_device_action', { action: 'timer.start', params: { label: 'Rest', durationMinutes: 20 } });
  assert.equal(requested.isError, false, requested.content?.[0]?.text);
  const content = requested.structuredContent as unknown as { state: string; handoff: boolean };
  assert.equal(content.state, 'saved');
  assert.equal(content.handoff, true);
});

test('targeted request queues, the device claims it, and only its receipt marks it applied', async () => {
  const s = setup();
  await registerPhone(s);
  const requested = await s.call('request_device_action', { deviceId: DEVICE_ID, action: 'timer.start', params: { label: 'Wrap', durationMinutes: 45 } });
  assert.equal(requested.isError, false, requested.content?.[0]?.text);
  const { commandId, state } = requested.structuredContent as unknown as { commandId: string; state: string };
  assert.equal(state, 'queued_for_device');

  const other = await s.device.claim('user-1', randomUUID(), ['timer'], s.now(), 10);
  assert.equal(other.length, 0, 'a different device must not claim the command');
  const claimed = await s.device.claim('user-1', DEVICE_ID, ['reminder'], s.now(), 10);
  assert.equal(claimed.length, 0, 'a class the command is not in must not be claimed');
  const claim = await s.device.claim('user-1', DEVICE_ID, ['timer'], s.now(), 10);
  assert.equal(claim.length, 1);
  assert.equal(claim[0].id, commandId);

  const before = await s.call('get_device_action', { commandId });
  assert.equal((before.structuredContent as { state: string }).state, 'queued_for_device', 'claimed is not applied');

  assert.equal(await s.device.resolve('user-1', randomUUID(), commandId, { outcome: 'applied', at: s.now() }, s.now()), false, 'only the claiming device resolves');
  assert.equal(await s.device.resolve('user-1', DEVICE_ID, commandId, { outcome: 'applied', at: s.now(), result: { timerId: 't-1' } }, s.now()), true);
  const after = await s.call('get_device_action', { commandId });
  const view = after.structuredContent as unknown as { state: string; receipt: { outcome: string; result: { timerId: string } } };
  assert.equal(view.state, 'applied');
  assert.equal(view.receipt.result.timerId, 't-1');
});

test('opt-in, unknown rule, finished meal, and expiry are all enforced', async () => {
  const s = setup();
  await registerPhone(s, ['timer']); // no 'reminder' class
  const meal = await s.call('create_meal', { title: 'Device brisket', dishes: [{ name: 'Flat', foodType: 'beef' }] });
  const { mealId } = meal.structuredContent as unknown as { mealId: string };
  const rule = await s.call('upsert_reminder_rule', {
    mealId, kind: 'time', title: 'Check the bark',
    triggerConfig: { type: 'time', offsetMillis: 3600000 },
  });
  assert.equal(rule.isError, false, rule.content?.[0]?.text);
  const { ruleId } = rule.structuredContent as unknown as { ruleId: string };

  const notOpted = await s.call('request_device_action', { deviceId: DEVICE_ID, action: 'reminder.activate', params: { ruleId } });
  assert.equal(notOpted.isError, true);
  assert.match(notOpted.content[0].text, /opted into|enable the class/i);

  const unknownRule = await s.call('request_device_action', { action: 'reminder.activate', params: { ruleId: randomUUID() } });
  assert.equal(unknownRule.isError, true);
  assert.match(unknownRule.content[0].text, /No reminder rule/);

  await registerPhone(s, ['timer', 'reminder', 'guidance']);
  const done = await s.call('transition_meal', { mealId, toStatus: 'completed' });
  assert.equal(done.isError, false, done.content?.[0]?.text);
  const revive = await s.call('request_device_action', { deviceId: DEVICE_ID, action: 'reminder.activate', params: { ruleId } });
  assert.equal(revive.isError, true);
  assert.match(revive.content[0].text, /finished meal/);

  const timed = await s.call('request_device_action', { deviceId: DEVICE_ID, action: 'timer.start', params: { label: 'Short', durationMinutes: 5 }, expiresInMinutes: 10 });
  const { commandId } = timed.structuredContent as unknown as { commandId: string };
  s.setNow(s.now() + 11 * 60_000);
  const expired = await s.call('get_device_action', { commandId });
  assert.equal((expired.structuredContent as { state: string }).state, 'expired');
  assert.equal((await s.device.claim('user-1', DEVICE_ID, ['timer'], s.now(), 10)).length, 0, 'expired work is never claimed');
});

test('cancel works before application and refuses after', async () => {
  const s = setup();
  await registerPhone(s);
  const requested = await s.call('request_device_action', { deviceId: DEVICE_ID, action: 'preferences.update', params: { themeMode: 'dark' } });
  const { commandId } = requested.structuredContent as unknown as { commandId: string };
  const canceled = await s.call('cancel_device_action', { commandId });
  assert.equal(canceled.isError, false, canceled.content?.[0]?.text);
  assert.equal((canceled.structuredContent as { state: string }).state, 'canceled');
  const again = await s.call('cancel_device_action', { commandId });
  assert.equal(again.isError, true);
});

// ---- HTTP surface: registration, claim, receipt, session discipline ----

async function httpSetup(t: { after: (fn: () => void) => void }) {
  const access = randomBytes(32).toString('base64url'), userId = randomUUID();
  const auth = { user: async (token: unknown) => { if (token !== access) throw new ApiError(401, 'authentication_required'); return { id: userId, email: 'owner@example.com' }; } } as unknown as Authentication;
  const grants = new McpGrants(new MemoryGrantStore(), new AuthSecrets(credential(), credential()), { state: async () => 'active' }, () => NOW);
  const device = new MemoryDeviceStore();
  const storage = { put: async () => {}, get: async () => Buffer.alloc(0), delete: async () => {} };
  const server = makeServer(new Relay(new MemoryRepository(), storage, budgets, () => NOW), { ipKey: 'x'.repeat(32), uploadsEnabled: false, auth, mcp: grants, device });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  return { base, access, userId, device, bearer: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + access } };
}

test('device routes: register → claim → receipt over HTTP, owner session only', async t => {
  const s = await httpSetup(t);
  assert.equal((await fetch(s.base + '/v1/me/mcp/devices')).status, 401);
  const registered = await fetch(s.base + '/v1/me/mcp/devices', {
    method: 'POST', headers: s.bearer,
    body: JSON.stringify({ deviceId: DEVICE_ID, name: 'Pixel', platform: 'android', actionClasses: ['timer', 'reminder'] }),
  });
  assert.equal(registered.status, 200, await registered.clone().text());
  const badClass = await fetch(s.base + '/v1/me/mcp/devices', {
    method: 'POST', headers: s.bearer,
    body: JSON.stringify({ deviceId: DEVICE_ID, name: 'Pixel', platform: 'android', actionClasses: ['grill'] }),
  });
  assert.equal(badClass.status, 422, 'hardware classes do not exist to opt into');

  const command: McpDeviceCommandRecord = {
    id: randomUUID(), userId: s.userId, deviceId: DEVICE_ID, grantId: 'g', action: 'timer.start',
    params: { label: 'Rest', durationMinutes: 30 }, state: 'queued_for_device',
    createdAt: NOW, expiresAt: NOW + 900_000, claimedAt: null, claimedBy: null, resolvedAt: null, receipt: null,
  };
  await s.device.createCommand(command);
  const claim = await fetch(s.base + `/v1/me/mcp/devices/${DEVICE_ID}/claim`, { method: 'POST', headers: s.bearer, body: '{}' });
  assert.equal(claim.status, 200);
  const claimBody = await claim.json() as { commands: { id: string }[] };
  assert.deepEqual(claimBody.commands.map(c => c.id), [command.id]);

  const strangerClaim = await fetch(s.base + `/v1/me/mcp/devices/${randomUUID()}/claim`, { method: 'POST', headers: s.bearer, body: '{}' });
  assert.equal(strangerClaim.status, 404, 'an unregistered device id is unknown, not claimable');

  const receipt = await fetch(s.base + `/v1/me/mcp/devices/${DEVICE_ID}/receipt`, {
    method: 'POST', headers: s.bearer,
    body: JSON.stringify({ commandId: command.id, outcome: 'needs_attention', detail: 'Notifications are turned off for PitTech.' }),
  });
  assert.equal(receipt.status, 200);
  const history = await fetch(s.base + '/v1/me/mcp/device-commands', { headers: { Authorization: 'Bearer ' + s.access } });
  const historyBody = await history.json() as { commands: { id: string; state: string; receipt: { detail: string } }[] };
  assert.equal(historyBody.commands[0].state, 'needs_attention');
  assert.match(historyBody.commands[0].receipt.detail, /Notifications/);

  const unregistered = await fetch(s.base + `/v1/me/mcp/devices/${DEVICE_ID}`, { method: 'DELETE', headers: s.bearer });
  assert.equal(unregistered.status, 204);
  const listed = await fetch(s.base + '/v1/me/mcp/devices', { headers: { Authorization: 'Bearer ' + s.access } });
  assert.equal(((await listed.json()) as { devices: unknown[] }).devices.length, 0);
});

test('unregistering a device cancels its queued commands instead of transferring them', async t => {
  const s = await httpSetup(t);
  await fetch(s.base + '/v1/me/mcp/devices', {
    method: 'POST', headers: s.bearer,
    body: JSON.stringify({ deviceId: DEVICE_ID, name: 'Pixel', platform: 'android', actionClasses: ['timer'] }),
  });
  const command: McpDeviceCommandRecord = {
    id: randomUUID(), userId: s.userId, deviceId: DEVICE_ID, grantId: 'g', action: 'timer.start',
    params: { label: 'Rest', durationMinutes: 30 }, state: 'queued_for_device',
    createdAt: NOW, expiresAt: NOW + 900_000, claimedAt: null, claimedBy: null, resolvedAt: null, receipt: null,
  };
  await s.device.createCommand(command);
  await fetch(s.base + `/v1/me/mcp/devices/${DEVICE_ID}`, { method: 'DELETE', headers: s.bearer });
  assert.equal((await s.device.commandFor(s.userId, command.id))?.state, 'canceled');
});
