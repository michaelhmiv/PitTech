import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { privateProjectionContract, privateLibraryEnvelope, projectPrivateArchive } from '../src/sync-projection.ts';
const read = (path: string) => readFile(new URL('../../' + path, import.meta.url), 'utf8');
const fixture = JSON.parse(await read('contracts/fixtures/private-sync-projection-cases-v1.json'));
const clientId = 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa';
function envelope(rows: Record<string, unknown>[]) { return { contractVersion: 1, baseVersion: 0, mutationId: clientId, clientId, records: rows.map(r => ({ ...r, deleted: false, version: { counter: 1, clientId } })), conflicts: [] }; }
test('shared portable projection vectors reject credentials, activation, malformed fields and broken ownership', () => {
  assert.equal(fixture.contractVersion, 1); assert.equal(fixture.cases.length, 32);
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

test('actual Android and iOS archive core records preserve typed fields and recipe snapshots', async () => {
  const { default: yauzl } = await import('yauzl'); const { randomUUID } = await import('node:crypto');
  const archiveData = async (bytes: Buffer) => new Promise<Buffer>((resolve, reject) => {
    yauzl.fromBuffer(bytes, { lazyEntries: true }, (error, zip) => {
      if (error || !zip) { reject(error); return; }
      zip.on('error', reject); zip.on('end', () => reject(Error('Fixture data missing')));
      zip.on('entry', entry => {
        if (entry.fileName !== 'data/pittech.json') { zip.readEntry(); return; }
        assert.ok(entry.uncompressedSize < 32 * 1024 ** 2);
        zip.openReadStream(entry, (err, stream) => {
          if (err || !stream) { reject(err); zip.close(); return; }
          void (async () => { const chunks: Buffer[] = []; for await (const b of stream) chunks.push(Buffer.from(b)); zip.close(); resolve(Buffer.concat(chunks)); })().catch(reject);
        });
      }); zip.readEntry();
    });
  });
  for (const path of ['ios/PitTechTests/Fixtures/android-meal-v9.zip', 'ios/PitTechTests/Fixtures/android-guide-meal-v10.zip', 'app/src/androidTest/assets/cross-platform/native-meal-v9.zip', 'app/src/androidTest/assets/cross-platform/native-guide-meal-v10.zip']) {
    const bytes = await archiveData(await readFile(new URL('../../' + path, import.meta.url)));
    const archive = JSON.parse(bytes.toString('utf8'));
    // These fixtures contain unsupported saved-document kinds. Explicitly test
    // only their independent core graph; full-library projection must still fail.
    assert.throws(() => projectPrivateArchive(archive));
    archive.companionRecords = []; archive.companionPhotos = [];
    const assets: Record<string, string> = {};
    for (const type of ['photos', 'recipeAssets']) for (const row of archive[type] ?? []) assets[type + '|' + row.id] = randomUUID();
    const result = projectPrivateArchive(archive, assets);
    for (const type of ['cooks', 'recipes', 'recipeRevisions', 'readings', 'dishes', 'events', 'reminderRules']) assert.equal(result.filter(r => r.recordType === type).length, archive[type].length, path + ':' + type);
    assert.ok(result.every(r => !Object.hasOwn(r.payload, 'archivePath')));
  }
});
