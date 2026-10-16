import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { PUBLICATION_TERMS_VERSION,PUBLICATION_TERMS_TEXT } from '../src/publication-terms.ts';

test('both native clients display the shared versioned public-copy terms separately from public archives',async()=>{
  const fixture=JSON.parse(await readFile(new URL('../../contracts/fixtures/publication-terms-v1.json',import.meta.url),'utf8'));
  assert.deepEqual(fixture,{version:PUBLICATION_TERMS_VERSION,text:PUBLICATION_TERMS_TEXT});
  assert.equal(await readFile(new URL('../../contracts/fixtures/source-review-v1.json',import.meta.url),'utf8'),await readFile(new URL('../../app/src/test/resources/source-review-v1.json',import.meta.url),'utf8'));
  for(const path of ['ios/PitTech/PublicationTerms.swift','app/src/main/java/com/pittech/data/PublicationTerms.kt']){
    const source=await readFile(new URL('../../'+path,import.meta.url),'utf8');assert.ok(source.includes(JSON.stringify(fixture.version)));assert.ok(source.includes(JSON.stringify(fixture.text)));
  }
});
