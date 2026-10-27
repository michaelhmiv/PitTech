import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseMcpTestProEmails, TestProEntitlementLookup } from '../src/mcp-test-entitlement.ts';
import type { McpEntitlementLookup } from '../src/mcp-grants.ts';
import type { EntitlementState } from '../src/subscriptions.ts';

test('parse: unset and empty values yield no test accounts', () => {
  assert.deepEqual(parseMcpTestProEmails(undefined, 'production'), []);
  assert.deepEqual(parseMcpTestProEmails('', 'staging'), []);
  assert.deepEqual(parseMcpTestProEmails('  , ', 'production'), []);
});

test('parse: staging list is normalized and de-duplicated', () => {
  assert.deepEqual(
    parseMcpTestProEmails(' Owner@Example.com, fixture@example.com ,owner@example.com ', 'staging'),
    ['owner@example.com', 'fixture@example.com']);
});

test('parse: any configured account outside staging refuses the boot', () => {
  assert.throws(() => parseMcpTestProEmails('owner@example.com', 'production'), /staging-only/);
});

test('parse: malformed entries are rejected', () => {
  assert.throws(() => parseMcpTestProEmails('not-an-email', 'staging'), /Invalid MCP_TEST_PRO_EMAILS/);
  assert.throws(() => parseMcpTestProEmails('ok@example.com, bad@', 'staging'), /Invalid MCP_TEST_PRO_EMAILS/);
});

const inner = (state: EntitlementState | undefined): McpEntitlementLookup & { calls: number } => ({
  calls: 0,
  async state() { this.calls++; return state; },
});
const poolWith = (email: string | undefined, fail = false) => ({
  query: async () => {
    if (fail) throw new Error('database unavailable');
    return { rows: email === undefined ? [] : [{ email }] };
  },
});

test('lookup: a listed account answers active without touching the store lookup', async () => {
  const store = inner('none');
  const lookup = new TestProEntitlementLookup(poolWith('Owner@Example.com') as never, ['owner@example.com'], store);
  assert.equal(await lookup.state('user-1'), 'active');
  assert.equal(store.calls, 0);
});

test('lookup: an unlisted account delegates to the store lookup', async () => {
  const store = inner('grace');
  const lookup = new TestProEntitlementLookup(poolWith('someone@example.com') as never, ['owner@example.com'], store);
  assert.equal(await lookup.state('user-2'), 'grace');
  assert.equal(store.calls, 1);
});

test('lookup: an unknown account delegates to the store lookup', async () => {
  const store = inner(undefined);
  const lookup = new TestProEntitlementLookup(poolWith(undefined) as never, ['owner@example.com'], store);
  assert.equal(await lookup.state('user-3'), undefined);
  assert.equal(store.calls, 1);
});

test('lookup: a failed email resolution fails closed to the store lookup', async () => {
  const store = inner('none');
  const lookup = new TestProEntitlementLookup(poolWith('owner@example.com', true) as never, ['owner@example.com'], store);
  assert.equal(await lookup.state('user-4'), 'none');
  assert.equal(store.calls, 1);
});
