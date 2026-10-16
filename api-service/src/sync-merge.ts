import {ApiError,digest} from './core.ts';
import {stable} from './pack.ts';
import {uuid} from './lifecycle-contract.ts';
export type SyncRecord={recordType:string;recordId:string;version:{counter:number;clientId:string};deleted:boolean;payload:Record<string,unknown>|null;assetIds:string[]};
export type SyncConflict={id:string;winner:SyncRecord;loser:SyncRecord;reason:'edit_edit'|'delete_edit'|'immutable_identity'};
export const SYNC_RECORD_TYPES=['cooks','dishes','ingredients','events','readings','targets','results','devices','probes','photos','recipes','recipeRevisions','recipeAssets','companionRecords','companionPhotos','mealPresets','dishRecipeSources','lifecycles','targetHistory','logReadingLinks','reminderRules'];
const immutable=new Set(['readings','recipeRevisions']);
const invalid=(code='invalid_sync_records')=>new ApiError(422,code);
const key=(r:SyncRecord)=>r.recordType+'|'+r.recordId;
const content=(r:SyncRecord)=>stable({deleted:r.deleted,payload:r.payload,assetIds:r.assetIds});
const equal=(a:SyncRecord,b:SyncRecord)=>stable(a)===stable(b);
const order=(a:SyncRecord,b:SyncRecord)=>a.version.counter-b.version.counter||(a.version.clientId<b.version.clientId?-1:a.version.clientId>b.version.clientId?1:0);
function bounded(value:unknown,depth=0):void{
 if(depth>24||typeof value==='number'&&!Number.isFinite(value)||typeof value==='string'&&value.length>16*1024**2)throw invalid();
 if(Array.isArray(value)){if(value.length>100000)throw invalid();for(const v of value)bounded(v,depth+1);}
 else if(value&&typeof value==='object')for(const [k,v]of Object.entries(value)){if(['__proto__','constructor','prototype'].includes(k))throw invalid();bounded(v,depth+1);}
}
/** Merge metadata validation only. Private projection and relational validation remain mandatory. */
export function syncRecords(input:unknown):SyncRecord[]{
 if(!Array.isArray(input)||input.length>100000||Buffer.byteLength(stable(input))>32*1024**2)throw invalid();
 const seen=new Set<string>();
 return input.map(raw=>{
  if(!raw||typeof raw!=='object'||Array.isArray(raw))throw invalid();const r=raw as SyncRecord;
  if(Object.keys(r).sort().join(',')!=='assetIds,deleted,payload,recordId,recordType,version'||!SYNC_RECORD_TYPES.includes(r.recordType)||typeof r.recordId!=='string'||(r.recordId.length<1||r.recordId.length>200||/[^A-Za-z0-9:_-]/.test(r.recordId))||typeof r.deleted!=='boolean'||!r.version||Object.keys(r.version).sort().join(',')!=='clientId,counter'||!Number.isSafeInteger(r.version.counter)||r.version.counter<1||r.version.counter>=Number.MAX_SAFE_INTEGER||!uuid(r.version.clientId)||r.version.clientId.length!==36||r.version.clientId!==r.version.clientId.toLowerCase()||!Array.isArray(r.assetIds)||r.assetIds.length>100||r.assetIds.some(id=>!uuid(id)||id.length!==36||id!==id.toLowerCase())||new Set(r.assetIds).size!==r.assetIds.length)throw invalid();
  if(r.deleted?(r.payload!==null||r.assetIds.length!==0):(!r.payload||typeof r.payload!=='object'||Array.isArray(r.payload)))throw invalid();
  bounded(r.payload);if(Buffer.byteLength(stable(r.payload))>16*1024**2)throw invalid();if(seen.has(key(r)))throw invalid('duplicate_sync_record');seen.add(key(r));return structuredClone(r);
 });
}
/** Three-way merge from the acknowledged base; no phone wall-clock ordering or ordinary archive import.
 * Tombstones are not compacted. Restoring deleted content requires a new identity.
 * Persist winners AND conflict copies before acknowledging or clearing either original outbox.
 */
export function mergeSync(baseInput:unknown,localInput:unknown,remoteInput:unknown){
 const base=new Map(syncRecords(baseInput).map(r=>[key(r),r])),local=new Map(syncRecords(localInput).map(r=>[key(r),r])),remote=new Map(syncRecords(remoteInput).map(r=>[key(r),r]));
 const records:SyncRecord[]=[],conflicts:SyncConflict[]=[];
 const conflict=(winner:SyncRecord,loser:SyncRecord,reason:SyncConflict['reason'])=>conflicts.push({id:digest([winner.recordType,winner.recordId,reason,winner.version.counter,winner.version.clientId,loser.version.counter,loser.version.clientId].join('|')),winner:structuredClone(winner),loser:structuredClone(loser),reason});
 for(const id of [...new Set([...base.keys(),...local.keys(),...remote.keys()])].sort()){
  const old=base.get(id),a=local.get(id),b=remote.get(id);if(old&&(!a||!b))throw invalid('explicit_tombstone_required');
  for(const r of [a,b])if(old&&r&&!equal(r,old)){if(r.version.counter<=old.version.counter)throw invalid('sync_clock_not_advanced');if(old.deleted&&!r.deleted)throw invalid('sync_restore_requires_new_identity');}
  if(a&&b&&!equal(a,b)&&order(a,b)===0)throw invalid('sync_stamp_reused');
  if(old&&!old.deleted&&immutable.has(old.recordType)){
   const bad=[a!,b!].filter(r=>!r.deleted&&content(r)!==content(old));if(bad.length){records.push(old);for(const r of [...new Map(bad.map(r=>[stable(r),r])).values()].sort(order))conflict(old,r,'immutable_identity');continue;}
  }
  if(!a||!b){records.push((a??b)!);continue;}if(equal(a,b)){records.push(a);continue;}if(order(a,b)===0)throw invalid('sync_stamp_reused');
  const changedA=!old||!equal(a,old),changedB=!old||!equal(b,old);if(!changedA||!changedB){records.push(changedA?a:b);continue;}
  const deletion=a.deleted!==b.deleted,winner=deletion?(a.deleted?a:b):order(a,b)>0?a:b,loser=winner===a?b:a;records.push(winner);
  if(content(a)!==content(b))conflict(winner,loser,deletion?'delete_edit':immutable.has(winner.recordType)?'immutable_identity':'edit_edit');
 }
 syncRecords(records);let observed=0;for(const map of [base,local,remote])for(const r of map.values())observed=Math.max(observed,r.version.counter);
 if(observed>=Number.MAX_SAFE_INTEGER-1)throw invalid('sync_clock_exhausted');
 const result={records,conflicts,blocked:conflicts.some(c=>c.reason==='immutable_identity'),nextCounter:observed+1};
 if(Buffer.byteLength(stable(result))>64*1024**2)throw invalid('sync_review_capacity');return result;
}
