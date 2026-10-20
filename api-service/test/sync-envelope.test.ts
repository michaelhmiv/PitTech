import{test}from'node:test';import assert from'node:assert/strict';import{randomUUID}from'node:crypto';import{syncEnvelope,syncTransition,type SyncEnvelope}from'../src/sync-envelope.ts';import{mergeSync,type SyncRecord}from'../src/sync-merge.ts';
const alice='aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa',bob='bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb';
const row=(text:string,counter=1,clientId=alice):SyncRecord=>({recordType:'events',recordId:'note',version:{counter,clientId},deleted:false,payload:{title:text},assetIds:[]});
const envelope=(records:SyncRecord[],clientId=alice):SyncEnvelope=>({contractVersion:1,mutationId:randomUUID(),baseVersion:0,clientId,records,conflicts:[]});
test('manual corrections commit and retain concurrent edits while telemetry provenance stays immutable',()=>{
 const sample:SyncRecord={...row(''),recordType:'readings',payload:{id:'note',cookId:'meal',source:'manual',value:150,unit:'°F'}};
 const corrected:SyncRecord={...sample,version:{counter:2,clientId:alice},payload:{...sample.payload,value:160}};
 syncTransition(syncEnvelope(envelope([sample])),syncEnvelope(envelope([corrected])));
 const remote:SyncRecord={...corrected,version:{counter:3,clientId:bob},payload:{...sample.payload,value:165}};
 const merged=mergeSync([sample],[corrected],[remote]);
 const next=syncEnvelope({...envelope(merged.records,bob),conflicts:merged.conflicts});syncTransition(envelope([corrected]),next);
 assert.equal(next.conflicts[0].loser.payload!.value,160);
 for(const field of ['cookId','source','sourceDeviceId','timestampBasis','samplingIntervalMillis']){
  const forged:SyncRecord={...corrected,payload:{...corrected.payload,[field]:'forged'}};
  assert.throws(()=>syncTransition(envelope([sample]),syncEnvelope(envelope([forged]))),field);
 }
 const automatic={...sample,payload:{...sample.payload,source:'controller_cloud'}};
 assert.throws(()=>syncTransition(envelope([automatic]),syncEnvelope(envelope([corrected]))));
});
test('snapshot metadata rejects missing tombstones, device takeover and rewriting an immutable sample',()=>{
 const base=syncEnvelope(envelope([row('Base')]));syncTransition(base,syncEnvelope(envelope([row('Edit',2)])));
 assert.throws(()=>syncTransition(base,syncEnvelope(envelope([]))));assert.throws(()=>syncTransition(base,syncEnvelope(envelope([row('Edit',2,bob)]))));
 const sample={...row('Base'),recordType:'readings'},edit={...row('Changed',2),recordType:'readings'};assert.throws(()=>syncTransition(envelope([sample]),envelope([edit])));
 assert.throws(()=>syncEnvelope({...base,records:[{...base.records[0],recordId:'note\n'}]}));
});
test('a recovered concurrent deletion retains the exact losing edit despite its greater logical counter',()=>{
 const base=row('Base'),edited=row('Offline edit',500,bob),deleted:SyncRecord={...row('',2),deleted:true,payload:null};const merged=mergeSync([base],[deleted],[edited]);
 const next=syncEnvelope({...envelope(merged.records),conflicts:merged.conflicts});syncTransition(envelope([edited],bob),next);
 assert.throws(()=>syncTransition(envelope([edited],bob),{...next,conflicts:[]}));
 const corrupted=structuredClone(next);corrupted.conflicts[0].loser.payload!.title='Changed recovery bytes';assert.throws(()=>syncTransition(next,syncEnvelope(corrupted)));
});
test('dropping a recoverable conflict needs an explicit resolution of that existing reference',()=>{
 const base=row('Base'),merged=mergeSync([base],[row('Alice',2)],[row('Bob',2,bob)]),previous=syncEnvelope({...envelope(merged.records,bob),conflicts:merged.conflicts});
 assert.throws(()=>syncTransition(previous,{...previous,conflicts:[]}));syncTransition(previous,{...previous,conflicts:[],resolvedConflictIds:[previous.conflicts[0].id]});
 assert.throws(()=>syncTransition(previous,{...previous,conflicts:[],resolvedConflictIds:['f'.repeat(64)]}));
 const wrong=structuredClone(previous);wrong.conflicts[0].id='f'.repeat(64);assert.throws(()=>syncEnvelope(wrong));
});
