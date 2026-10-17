import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import sharp from 'sharp';
import { deflateRawSync } from 'node:zlib';
import { canonicalPack, stable } from '../src/pack.ts';
import { readZip, writeZip, crc32 } from '../src/zip.ts';
import { digest, LIMITS } from '../src/core.ts';
import { golden, alter } from './helpers.ts';

test('six archives produced by the actual Android and iOS writers preserve selected data and source bytes', async t => {
  for (const platform of ['android', 'native']) for (const kind of ['recipe', 'collection', 'cook']) await t.test(`${platform} ${kind}`, async () => {
    const input = await readFile(golden(platform, kind)), sha = digest(input), original = await readZip(input);
    const a = await canonicalPack(input), b = await canonicalPack(input), output = await readZip(a.bytes);
    assert.equal(a.summary.kind, kind); assert.equal(a.summary.sha256, b.summary.sha256); assert.equal(digest(input), sha);
    assert.equal(stable(JSON.parse(output.get('data/pittech.json')!.toString())), stable(JSON.parse(original.get('data/pittech.json')!.toString())));
    assert.ok(![...output.keys()].some(n => n.startsWith('csv/')));
    if (kind === 'cook') { const data = JSON.parse(output.get('data/pittech.json')!.toString()); assert.equal(data.readings[0].value, platform === 'native' ? 74.125 : 85); assert.equal(data.readings[0].unit, '°C'); }
  });
});
test('canonical output drops uploaded redundant text and all photo metadata', async () => {
  const input = await alter('native', async () => {});
  const files = await readZip(input); files.set('README.txt', Buffer.from('PRIVATE README SENTINEL')); files.set('csv/events.csv', Buffer.from('PRIVATE CSV SENTINEL'));
  const first = [...files.entries()].find(([name]) => name.startsWith('attachments/'))![1];
  const tagged = await sharp(first).withExif({ IFD0: { Artist: 'PRIVATE IDENTITY SENTINEL' }, IFD2: { UserComment: 'PRIVATE COMMENT' } }).jpeg().toBuffer();
  for (const name of files.keys()) if (name.startsWith('attachments/')) files.set(name, tagged);
  const result = await canonicalPack(writeZip(files)); assert.ok(!result.bytes.includes(Buffer.from('PRIVATE')));
  for (const [name, bytes] of await readZip(result.bytes)) if (name.startsWith('attachments/')) {
    const m = await sharp(bytes).metadata(); assert.equal(m.format, 'jpeg'); assert.equal(m.exif, undefined); assert.equal(m.xmp, undefined); assert.equal(m.iptc, undefined);
  }
});
test('forged scopes, credentials, private backups, unselected photos, dangling relations and hidden nested records are rejected', async () => {
  const changes: Parameters<typeof alter>[1][] = [
    d => { d.devices.push({ id: 'private-device' }); }, d => { d.preferences = { password: 'private' }; },
    (_d, m) => { m.cookPack.kind = 'recipe'; }, d => { d.cooks[0].status = 'active'; },
    d => { d.dishes[0].cookId = '00000000-0000-0000-0000-000000000000'; },
    d => { d.readings[0].measuredAtUtcMillis = 1e100; }, d => { d.recipes[0].currentRevisionId = 'bad'; },
    d => { d.companionRecords[0].payload.entries[0].story.credentials = 'private'; },
    d => { const e = d.companionRecords[0].payload.entries[0]; e.includeStory = false; },
    d => { const p = d.companionRecords[0].payload; p.entries[0].photoIds = []; p.heroPhotoId = null; },
    d => { const s = JSON.parse(d.recipeRevisions[0].snapshotJson); s.draft.notes = 'private whole-meal note'; d.recipeRevisions[0].snapshotJson = JSON.stringify(s); },
    d => { d.photos[0].archivePath = 'https://example.invalid/private.jpg'; },
    (_d, m) => { m.counts = { readings: 200 }; },
  ];
  for (const change of changes) await assert.rejects(canonicalPack(await alter('native', change)));
});
test('unsafe ZIP paths, duplicates, bad CRC, local-header mismatch, encryption, symlinks and declared limits fail', async () => {
  const input = await readFile(golden('native'));
  const files = await readZip(input); files.set('../secret', Buffer.from('x')); await assert.rejects(readZip(writeZip(files)));
  const duplicates = new Map([['README.txt', Buffer.from('a')], ['README.txq', Buffer.from('b')]]);
  await assert.rejects(readZip(Buffer.from(writeZip(duplicates).toString('latin1').replaceAll('README.txq', 'README.txt'), 'latin1')));
  const stored = writeZip(await readZip(input));
  const crc = Buffer.from(stored); crc[30 + crc.readUInt16LE(26)] ^= 1; await assert.rejects(readZip(crc));
  const local = Buffer.from(stored); local[30] ^= 1; await assert.rejects(readZip(local));
  const encrypted = Buffer.from(stored); encrypted.writeUInt16LE(0x801, 6); await assert.rejects(readZip(encrypted));
  const symlink = Buffer.from(stored), directory = symlink.readUInt32LE(symlink.length - 6); symlink.writeUInt32LE((0xA000 << 16) >>> 0, directory + 38); await assert.rejects(readZip(symlink));
  const tooMany = new Map(Array.from({ length: LIMITS.entries + 1 }, (_, i) => [`csv/${'a'.repeat(i + 1)}.csv`, Buffer.alloc(0)])); await assert.rejects(readZip(writeZip(tooMany)));
  const large = new Map([['README.txt', Buffer.alloc(LIMITS.entry + 1)], ['manifest.json', Buffer.from('{}')]]); await assert.rejects(readZip(writeZip(large)));
  // The compressed stream expands beyond its declared size, while header sizes stay below every cap.
  const original = writeZip(new Map([['README.txt', Buffer.from('x')], ['manifest.json', Buffer.from('{}')]]));
  const expanded = Buffer.alloc(32768), compressed = deflateRawSync(expanded), delta = compressed.length - 1;
  const forged = Buffer.concat([original.subarray(0, 40), compressed, original.subarray(41)]), end = forged.length - 22;
  const dir = original.readUInt32LE(original.length - 6) + delta; forged.writeUInt32LE(dir, end + 16);
  forged.writeUInt16LE(8, 8); forged.writeUInt32LE(crc32(expanded), 14); forged.writeUInt32LE(compressed.length, 18);
  forged.writeUInt16LE(8, dir + 10); forged.writeUInt32LE(crc32(expanded), dir + 16); forged.writeUInt32LE(compressed.length, dir + 20);
  forged.writeUInt32LE(original.readUInt32LE(original.readUInt32LE(original.length - 6) + 56 + 42) + delta, dir + 56 + 42);
  await assert.rejects(readZip(forged));
  await assert.rejects(readZip(input.subarray(0, input.length - 10)));
});
test('corrupt media and unrelated attachment bytes never enter storage', async () => {
  await assert.rejects(canonicalPack(await alter('native', (_d, _m, files) => { for (const name of files.keys()) if (name.startsWith('attachments/')) files.set(name, Buffer.from('not a JPEG')); })));
  await assert.rejects(canonicalPack(await alter('native', (_d, _m, files) => { files.set('attachments/00000000-0000-0000-0000-000000000000.jpg', Buffer.from('unused')); })));
});
