import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { digest } from '../src/core.ts';
import { canonicalPublication } from '../src/publication-pack.ts';

for(const platform of ['android','ios'])test(`actual ${platform} Actions publication archive passes canonical validation and retains the selected frozen public story`,async()=>{
  const source=await readFile(new URL(`../../contracts/fixtures/native-publication-${platform}-v1.json`,import.meta.url),'utf8');if(platform==='android')assert.equal(source,await readFile(new URL('../../app/src/androidTest/assets/native-publication-android-v1.json',import.meta.url),'utf8'));
  const fixture=JSON.parse(source);
  const server=JSON.parse(await readFile(new URL('../../contracts/fixtures/public-native-v1.json',import.meta.url),'utf8'));
  const bytes=Buffer.from(fixture.archiveBase64,'base64');assert.equal(digest(bytes),fixture.archiveSha256);
  const result=await canonicalPublication(bytes);assert.equal(result.sha256,fixture.canonicalSha256);
  assert.equal(result.payload.kind,'recipe');assert.equal(result.payload.identityMode,'anonymous');assert.equal(result.payload.recipes.length,1);
  assert.equal(result.payload.source!.itemId,server.detail.id);assert.equal(result.payload.source!.revisionId,server.detail.revisionId);
  assert.deepEqual(result.payload.recipes[0].story,server.detail.payload.recipes[0].story);
  assert.equal(result.payload.recipes[0].assets.length,1);
});

test('actual native owner update preserves public identities and frozen facts while revising instructions',async()=>{
  const fixture=JSON.parse(await readFile(new URL('../../contracts/fixtures/native-owned-update-android-v1.json',import.meta.url),'utf8'));
  const original=JSON.parse(await readFile(new URL('../../contracts/fixtures/native-publication-android-v1.json',import.meta.url),'utf8'));
  const bytes=Buffer.from(fixture.archiveBase64,'base64');assert.equal(digest(bytes),fixture.archiveSha256);
  const update=await canonicalPublication(bytes),source=await canonicalPublication(Buffer.from(original.archiveBase64,'base64'));assert.equal(update.sha256,fixture.canonicalSha256);
  const a=source.payload.recipes[0],b=update.payload.recipes[0];assert.equal(b.id,a.id);assert.deepEqual(b.source,a.source);assert.deepEqual(update.payload.source,source.payload.source);
  assert.deepEqual(b.story,a.story);assert.equal(b.instructions[0].id,a.instructions[0].id);assert.equal(b.assets[0].id,a.assets[0].id);
  assert.equal(b.description,'My revised instructions');assert.equal(b.method,'Smoke');assert.equal(b.instructions[0].details,'Change the method, not the recorded result.');
});
