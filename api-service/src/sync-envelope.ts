import{readFile}from'node:fs/promises';import{Ajv2020}from'ajv/dist/2020.js';import formats from'ajv-formats';import{ApiError,digest}from'./core.ts';import{stable}from'./pack.ts';import{syncRecords,manualReadingEdit,type SyncRecord,type SyncConflict}from'./sync-merge.ts';
export type SyncEnvelope={contractVersion:1;baseVersion:number;mutationId:string;clientId:string;records:SyncRecord[];conflicts:SyncConflict[];resolvedConflictIds?:string[]};
const ajv=new Ajv2020({strict:true,strictTypes:false});formats.default(ajv);const schema=ajv.compile(JSON.parse(await readFile(new URL('../../contracts/sync.schema.json',import.meta.url),'utf8')));
const invalid=(code='invalid_sync_envelope')=>new ApiError(422,code);
const key=(r:SyncRecord)=>r.recordType+'|'+r.recordId,equals=(a:unknown,b:unknown)=>stable(a)===stable(b);
const order=(a:SyncRecord,b:SyncRecord)=>a.version.counter-b.version.counter||(a.version.clientId<b.version.clientId?-1:a.version.clientId>b.version.clientId?1:0);
const content=(r:SyncRecord)=>stable({deleted:r.deleted,payload:r.payload,assetIds:r.assetIds});
/** Metadata validation only. No network endpoint may use this in place of strict library projection. */
export function syncEnvelope(value:unknown):SyncEnvelope{
 if(!schema(value)||Buffer.byteLength(stable(value))>32*1024**2)throw invalid();const e=structuredClone(value)as SyncEnvelope;e.records=syncRecords(e.records);
 const ids=new Set<string>();
 for(const c of e.conflicts){
  syncRecords([c.winner]);syncRecords([c.loser]);
  const id=digest([c.winner.recordType,c.winner.recordId,c.reason,c.winner.version.counter,c.winner.version.clientId,c.loser.version.counter,c.loser.version.clientId].join('|'));
  if(c.id!==id||ids.has(id)||key(c.winner)!==key(c.loser)||order(c.winner,c.loser)===0||content(c.winner)===content(c.loser))throw invalid();ids.add(id);
  if(c.reason==='delete_edit'){if(!c.winner.deleted||c.loser.deleted)throw invalid();}
  else {
   if(c.winner.deleted||c.loser.deleted)throw invalid();
   if(c.reason==='immutable_identity'){if(!['readings','recipeRevisions'].includes(c.winner.recordType))throw invalid();}
   else if((['readings','recipeRevisions'].includes(c.winner.recordType)&&!manualReadingEdit(c.winner,c.loser))||order(c.winner,c.loser)<=0)throw invalid();
  }
 }
 return e;
}
/** An explicit resolution list is the only way to remove an acknowledged recovery copy. */
export function syncTransition(previous:SyncEnvelope|undefined,next:SyncEnvelope){
 const old=new Map((previous?.records??[]).map(r=>[key(r),r])),rows=new Map(next.records.map(r=>[key(r),r]));
 const oldConflicts=new Map((previous?.conflicts??[]).map(c=>[c.id,c])),newConflicts=new Map(next.conflicts.map(c=>[c.id,c])),resolved=new Set(next.resolvedConflictIds??[]);
 for(const id of resolved)if(!oldConflicts.has(id)||newConflicts.has(id))throw invalid('sync_resolution_invalid');
 for(const [id,c]of oldConflicts)if(!newConflicts.has(id)?!resolved.has(id):!equals(c,newConflicts.get(id)))throw invalid('sync_conflict_copy_required');
 for(const id of old.keys())if(!rows.has(id))throw invalid('explicit_tombstone_required');
 const proof=(r:SyncRecord,prior:SyncRecord,reason:string)=>next.conflicts.some(c=>!oldConflicts.has(c.id)&&c.reason===reason&&equals(c.winner,r)&&equals(c.loser,prior));
 for(const r of rows.values()){
  const prior=old.get(key(r));if(prior&&equals(r,prior))continue;
  if(r.version.clientId!==next.clientId)throw invalid('sync_mutation_device_mismatch');
  if(!prior)continue;
  if(prior.deleted&&!r.deleted)throw invalid('sync_restore_requires_new_identity');
  if(order(r,prior)===0)throw invalid('sync_stamp_reused');
  if(!prior.deleted&&!r.deleted&&['readings','recipeRevisions'].includes(r.recordType)&&content(r)!==content(prior)&&!manualReadingEdit(prior,r))throw invalid('immutable_sync_identity');
  if(order(r,prior)<=0){if(!(r.deleted&&!prior.deleted&&proof(r,prior,'delete_edit')))throw invalid('sync_clock_not_advanced');}
  else if(r.version.counter===prior.version.counter&&content(r)!==content(prior)&&!proof(r,prior,r.deleted?'delete_edit':'edit_edit'))throw invalid('sync_conflict_copy_required');
 }
}
