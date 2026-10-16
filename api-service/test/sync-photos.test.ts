import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { photoMetadata, PRIVATE_PHOTO_CHUNK_BYTES } from '../src/sync-photos.ts';
const valid = () => ({ kind: 'photo-v1', assetId: randomUUID(), sha256: 'a'.repeat(64), bytes: PRIVATE_PHOTO_CHUNK_BYTES + 23, mimeType: 'image/jpeg', count: 2, parts: [{ index: 0, objectId: randomUUID(), sha256: 'b'.repeat(64), bytes: PRIVATE_PHOTO_CHUNK_BYTES, active: true }] });
test('private photo metadata is bounded, exact and excludes extra credential fields', () => {
  assert.deepEqual(photoMetadata(valid()).parts.length, 1);
  for (const input of [{ ...valid(), token: 'synthetic' }, { ...valid(), bytes: '12' }, { ...valid(), bytes: 16 * 1024 ** 2 + 1 }, { ...valid(), mimeType: 'image/svg+xml' }, { ...valid(), count: 1 }]) assert.throws(() => photoMetadata(input));
});
test('private photo chunk metadata rejects repeated identities, wrong lengths and out-of-range indices', () => {
  const v = valid(); assert.throws(() => photoMetadata({ ...v, parts: [v.parts[0], v.parts[0]] }));
  for (const part of [{ ...v.parts[0], index: 2 }, { ...v.parts[0], index: 1 }, { ...v.parts[0], bytes: 0 }, { ...v.parts[0], active: 'true' }, { ...v.parts[0], key: 'arbitrary/path' }]) assert.throws(() => photoMetadata({ ...v, parts: [part] }));
  const parsed = photoMetadata(v); parsed.parts[0].sha256 = 'c'.repeat(64); assert.equal(v.parts[0].sha256, 'b'.repeat(64));
});
