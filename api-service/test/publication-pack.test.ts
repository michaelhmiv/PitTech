import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import sharp from 'sharp';
import { canonicalPublication, type PublicationPayload } from '../src/publication-pack.ts';
import { readZip, writeZip } from '../src/zip.ts';
import { digest } from '../src/core.ts';
import { publicationRoute } from '../src/publication-http.ts';
const fixture=async()=>JSON.parse(await readFile(new URL('../../contracts/fixtures/anonymous-pork.json',import.meta.url),'utf8')) as PublicationPayload;
export function archive(payload:PublicationPayload,photos=new Map<string,Buffer>()) {return writeZip(new Map([['publication.json',Buffer.from(JSON.stringify(payload))],...photos]));}
test('public archive validates explicit data and independently strips image metadata while preserving orientation',async()=>{
  const payload=await fixture(),id=randomUUID(),path=`attachments/${id}.jpg`;
  const photo=await sharp({create:{width:12,height:20,channels:3,background:'orange'}}).jpeg().withMetadata({orientation:6,exif:{IFD0:{Artist:'Private camera owner'}}}).toBuffer();
  payload.recipes[0].assets=[{id,path,mimeType:'image/jpeg',sha256:digest(photo),caption:'Explicit public caption'}];payload.heroAssetId=id;
  const original=Buffer.from(photo),accepted=await canonicalPublication(archive(payload,new Map([[path,photo]])));
  const files=await readZip(accepted.bytes,'publication'),image=files.get(path)!,metadata=await sharp(image).metadata();
  assert.equal(metadata.width,20);assert.equal(metadata.height,12);assert.equal(metadata.exif,undefined);assert.equal(metadata.xmp,undefined);assert.equal(metadata.orientation,undefined);
  assert.equal(accepted.payload.recipes[0].assets[0].sha256,digest(image));assert.deepEqual(photo,original);
  assert.equal(accepted.payload.recipes[0].story!.attemptId,payload.recipes[0].story!.attemptId);
});
test('publication validation rejects private backups, dangling media/channels, duplicates, unknown fields and forged photos',async()=>{
  const base=await fixture();
  const mutations:((p:PublicationPayload)=>void)[]=[
    p=>{(p as unknown as Record<string,unknown>).privateLibrary={email:'private@example.com'};},
    p=>{p.heroAssetId=randomUUID();},p=>{p.recipes[0].story!.measurements[0].channelId='missing';},
    p=>{p.recipes.push(structuredClone(p.recipes[0]));},p=>{p.measuredAttemptId=randomUUID();},
    p=>{p.recipes[0].assets=[{id:randomUUID(),path:'attachments/x.jpg',mimeType:'image/jpeg',sha256:'a'.repeat(64),caption:''}];}
  ];
  for(const mutate of mutations){const payload=structuredClone(base);mutate(payload);await assert.rejects(canonicalPublication(archive(payload)));}
  await assert.rejects(canonicalPublication(writeZip(new Map([['manifest.json',Buffer.from('{}')],['data/pittech.json',Buffer.from('{}')]]))));
  await assert.rejects(canonicalPublication(archive(base,new Map([['attachments/hidden.jpg',Buffer.from('hidden')]]))));
  const accepted=await canonicalPublication(archive(base));assert.equal(accepted.payload.identityMode,'anonymous');assert.equal((await readZip(accepted.bytes,'publication')).size,1);
  // Generalizing the ZIP parser must never make a publication valid as a relay Cook Pack.
  await assert.rejects(readZip(accepted.bytes));
});
test('public routes bound accepted shapes and keep dynamic IDs out of operational route labels',()=>{
  const id=randomUUID();assert.equal(publicationRoute(`/v1/recipes/${id}`,'GET'),'/v1/recipes/:id');
  assert.equal(publicationRoute(`/v1/recipes/${id}/assets/${randomUUID()}`,'GET'),'/v1/recipes/:id/assets/:assetId');
  assert.equal(publicationRoute('/v1/discover?sort=liked&q=private-search','GET'),'/v1/discover');
  for(const path of ['/v1/sync','/v1/recipes/../../auth','/v1/profiles/a%2fb',`/v1/recipes/${id}?owner=secret`])assert.equal(publicationRoute(path,'GET'),undefined);
});
