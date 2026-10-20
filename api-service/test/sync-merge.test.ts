import {test}from'node:test';import assert from'node:assert/strict';import {mergeSync,syncRecords,type SyncRecord}from'../src/sync-merge.ts';
const alice='aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa',bob='bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb';
const note=(id:string,title:string,counter=1,clientId=alice):SyncRecord=>({recordType:'events',recordId:id,version:{counter,clientId},deleted:false,payload:{id,title,occurredAtUtcMillis:1000},assetIds:[]});
const tomb=(r:SyncRecord):SyncRecord=>({...r,version:{counter:2,clientId:bob},deleted:true,payload:null,assetIds:[]});
test('independent notes append; logical clocks and device ties ignore phone wall-clock skew',()=>{
 const base=note('one','Base'),a=note('two','Alice',2),b=note('three','Bob',2,bob);const merged=mergeSync([base],[base,a],[base,b]);assert.equal(merged.records.length,3);assert.equal(merged.conflicts.length,0);assert.equal(merged.nextCounter,3);
 const editA=note('one','Alice',2),editB=note('one','Bob',2,bob);editA.payload!.occurredAtUtcMillis=253402300799999;const forward=mergeSync([base],[editA],[editB]);assert.deepEqual(forward,mergeSync([base],[editB],[editA]));assert.equal(forward.records[0].payload!.title,'Bob');assert.equal(forward.conflicts[0].loser.payload!.title,'Alice');forward.records[0].payload!.title='Later local edit';assert.equal(forward.conflicts[0].winner.payload!.title,'Bob');
});
test('offline edits cannot resurrect deletion and the losing text remains recoverable',()=>{
 const base=note('one','Base'),deleted=tomb(base),edit=note('one','Offline edit',500);const result=mergeSync([base],[edit],[deleted]);assert.equal(result.records[0].deleted,true);assert.equal(result.nextCounter,501);assert.equal(result.conflicts[0].loser.payload!.title,'Offline edit');assert.equal(result.conflicts[0].reason,'delete_edit');assert.deepEqual(result,mergeSync([base],[deleted],[edit]));assert.throws(()=>mergeSync([deleted],[note('one','Restore',600)],[deleted]));assert.equal(mergeSync([base],[base],[deleted]).conflicts.length,0);
});
test('immutable sample identity collisions block commits; distinct recipe revisions remain branches',()=>{
 const a={...note('sample','A',2),recordType:'readings'},b={...note('sample','B',3,bob),recordType:'readings'};const result=mergeSync([],[a],[b]);assert.equal(result.blocked,true);assert.equal(result.conflicts[0].reason,'immutable_identity');assert.equal(mergeSync([a],[a],[b]).blocked,true);assert.equal(mergeSync([a],[b],[b]).blocked,true);
 const branches=mergeSync([],[{...a,recordId:'revision-one',recordType:'recipeRevisions'}],[{...b,recordId:'revision-two',recordType:'recipeRevisions'}]);assert.equal(branches.records.length,2);assert.equal(branches.blocked,false);
});
test('changed bytes require a new stamp, deletion requires a tombstone, and malformed metadata fails closed',()=>{
 const base=note('one','Base');assert.throws(()=>mergeSync([base],[note('one','Changed')],[base]));assert.throws(()=>mergeSync([],[base],[note('one','Changed')]));assert.throws(()=>mergeSync([base],[],[base]));assert.throws(()=>syncRecords([base,base]));assert.throws(()=>syncRecords([{...base,recordType:'credentials'}]));assert.throws(()=>syncRecords([{...base,deleted:true}]));assert.throws(()=>syncRecords([{...base,version:{counter:Number.MAX_SAFE_INTEGER,clientId:alice}}]));
});
test('equal content at different versions does not invent a conflict and outputs cannot mutate inputs',()=>{
 const a=note('one','Same',2),b=note('one','Same',3,bob),before=JSON.stringify([a,b]);const result=mergeSync([],[a],[b]);assert.equal(result.conflicts.length,0);assert.equal(result.records[0].version.counter,3);result.records[0].payload!.title='Local review';assert.equal(JSON.stringify([a,b]),before);
});

test('shared native vectors are identical files and match the backend merge in both directions',async()=>{
 const {readFile}=await import('node:fs/promises');const bytes=await readFile(new URL('../../contracts/fixtures/sync-merge-v1.json',import.meta.url),'utf8');
 assert.equal(bytes,await readFile(new URL('../../app/src/test/resources/sync-merge-v1.json',import.meta.url),'utf8'));assert.equal(bytes,await readFile(new URL('../../ios/PitTechTests/Fixtures/sync-merge-v1.json',import.meta.url),'utf8'));
 const vectors=JSON.parse(bytes);assert.equal(vectors.contractVersion,1);assert.equal(vectors.cases.length,16);
 for(const c of vectors.cases)if(c.rejected)assert.throws(()=>mergeSync(c.base,c.local,c.remote));else{assert.deepEqual(mergeSync(c.base,c.local,c.remote),c.expected,c.name);assert.deepEqual(mergeSync(c.base,c.remote,c.local),c.expected,c.name);}
});

test('draft sync schema accepts actual merge vectors and rejects implicit deletion and unknown record kinds',async()=>{
 const {Ajv2020}=await import('ajv/dist/2020.js'),formats=await import('ajv-formats'),{readFile}=await import('node:fs/promises');const ajv=new Ajv2020({strict:true,strictTypes:false});formats.default.default(ajv);const validate=ajv.compile(JSON.parse(await readFile(new URL('../../contracts/sync.schema.json',import.meta.url),'utf8')));
 const vectors=JSON.parse(await readFile(new URL('../../contracts/fixtures/sync-merge-v1.json',import.meta.url),'utf8'));
 for(const c of vectors.cases)if(c.expected){const envelope={contractVersion:1,baseVersion:0,mutationId:alice,clientId:alice,records:c.expected.records,conflicts:c.expected.conflicts};assert.equal(validate(envelope),true,JSON.stringify(validate.errors));}
 const envelope={contractVersion:1,baseVersion:0,mutationId:alice,clientId:alice,records:[note('composite:note','Private text')],conflicts:[]};assert.equal(validate(envelope),true);
 assert.equal(validate({...envelope,records:[{...envelope.records[0],deleted:true}]}),false);assert.equal(validate({...envelope,records:[{...envelope.records[0],recordType:'credentials'}]}),false);
});
