import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { digest } from '../src/core.ts';
import { canonicalPublication } from '../src/publication-pack.ts';

test('actual Android Actions publication archive passes canonical validation and retains the selected frozen public story',async()=>{
  const fixture=JSON.parse(await readFile(new URL('../../contracts/fixtures/native-publication-android-v1.json',import.meta.url),'utf8'));
  const server=JSON.parse(await readFile(new URL('../../contracts/fixtures/public-native-v1.json',import.meta.url),'utf8'));
  const bytes=Buffer.from(fixture.archiveBase64,'base64');assert.equal(digest(bytes),fixture.archiveSha256);
  const result=await canonicalPublication(bytes);assert.equal(result.sha256,fixture.canonicalSha256);
  assert.equal(result.payload.kind,'recipe');assert.equal(result.payload.identityMode,'anonymous');assert.equal(result.payload.recipes.length,1);
  assert.equal(result.payload.source!.itemId,server.detail.id);assert.equal(result.payload.source!.revisionId,server.detail.revisionId);
  assert.deepEqual(result.payload.recipes[0].story,server.detail.payload.recipes[0].story);
  assert.equal(result.payload.recipes[0].assets.length,1);
});
