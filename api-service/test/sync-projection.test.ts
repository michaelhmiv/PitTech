import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { privateProjectionContract, privateLibraryEnvelope, projectPrivateArchive } from '../src/sync-projection.ts';
const read = (path: string) => readFile(new URL('../../' + path, import.meta.url), 'utf8');
const fixture = JSON.parse(await read('contracts/fixtures/private-sync-projection-cases-v1.json'));
const clientId = 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa';
function envelope(rows: Record<string, unknown>[]) { return { contractVersion: 1, baseVersion: 0, mutationId: clientId, clientId, records: rows.map(r => ({ ...r, deleted: false, version: { counter: 1, clientId } })), conflicts: [] }; }
test('shared portable projection vectors reject credentials, activation, malformed fields and broken ownership', () => {
  assert.equal(fixture.contractVersion, 1); assert.equal(fixture.cases.length, 20);
  for (const c of fixture.cases) {
    if (c.rejected) assert.throws(() => privateLibraryEnvelope(envelope(c.rows)), c.name);
    else assert.equal(privateLibraryEnvelope(envelope(c.rows)).records.length, c.rows.length, c.name);
  }
  assert.equal(privateProjectionContract.releaseReady, false);
});
test('native archive projection preserves portable records and excludes paths, settings and activation', () => {
  const p = fixture.projection, before = JSON.stringify(p.archive);
  assert.deepEqual(projectPrivateArchive(p.archive, p.assets), p.expected);
  assert.equal(JSON.stringify(p.archive), before);
  assert.throws(() => projectPrivateArchive(p.archive));
  assert.throws(() => projectPrivateArchive({ ...p.archive, unknownCredentialStore: {} }, p.assets));
  const changed = structuredClone(p.archive); changed.cooks[0].credential = 'synthetic-placeholder';
  assert.throws(() => projectPrivateArchive(changed, p.assets));
});
test('strict contract and cases are byte-identical in both native bundles', async () => {
  const contract = await read('contracts/private-sync-projection-v1.json');
  assert.equal(contract, await read('app/src/main/assets/private-sync-projection-v1.json'));
  assert.equal(contract, await read('ios/Contracts/private-sync-projection-v1.json'));
  assert.equal(contract, await read('app/src/test/resources/private-sync-projection-v1.json'));
});
