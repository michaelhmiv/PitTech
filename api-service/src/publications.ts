import { randomUUID } from 'node:crypto';
import { mkdir,mkdtemp,writeFile,rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import type { Pool, PoolClient } from 'pg';
import { ApiError, digest } from './core.ts';
import { readZip } from './zip.ts';
import { stable } from './pack.ts';
import type { Storage } from './storage.ts';
import type { PublicationPayload } from './publication-pack.ts';
import type { ValidatedPublication } from './publication-validation.ts';

const uuid=(value:unknown):value is string=>typeof value==='string'&&/^[a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(value);
const missing=()=>new ApiError(404,'publication_unavailable');
const retryChanged=()=>new ApiError(409,'retry_content_changed');
type Result={id:string;revisionId:string;revision:number;url:string};
type ObjectRow={id:string;owner_id:string;operation_id:string;request_hash:string;key:string;sha256:string;bytes:string;state:string;lease_id:string;lease_until:string};
type ItemRow={id:string;author_id:string|null;current_revision_id:string;parent_id:string|null;status:string;created_at:string};
type RevisionRow={id:string;item_id:string;revision_no:number;object_id:string;identity_mode:string;payload:PublicationPayload};
export type PublicationLimits={storageBytes:number;dailyBytes:number;dailyWrites:number};
export class Publications {
  private pool:Pool; private storage:Storage; private limits:PublicationLimits; clock:()=>number;
  constructor(pool:Pool,storage:Storage,limits:PublicationLimits,clock=Date.now) {
    if(Object.values(limits).some(n=>!Number.isSafeInteger(n)||n<1)) throw new Error('Publication budgets required');
    this.pool=pool;this.storage=storage;this.limits=limits;this.clock=clock;
  }
  private async tx<T>(body:(client:PoolClient)=>Promise<T>):Promise<T> {
    const client=await this.pool.connect();
    try { await client.query('BEGIN');const result=await body(client);await client.query('COMMIT');return result; }
    catch(error){await client.query('ROLLBACK');throw error;}finally{client.release();}
  }
  private async lock(c:PoolClient,key:string){await c.query('SELECT pg_advisory_xact_lock(hashtextextended($1,890010))',[key]);}
  private async operation(c:PoolClient,user:string,id:string,hash:string):Promise<unknown|undefined> {
    if(!uuid(user)||!uuid(id)) throw new ApiError(400,'operation_required');
    await this.lock(c,user+':'+id);
    const old=(await c.query('SELECT request_hash,response FROM publications.operations WHERE user_id=$1 AND operation_id=$2',[user,id])).rows[0];
    if(old&&old.request_hash!==hash)throw retryChanged();return old?.response;
  }
  private async record(c:PoolClient,user:string,id:string,hash:string,response:unknown){
    await this.lock(c,'publication-mutation-budget');const day=Math.floor(this.clock()/86400000);
    for(const [key,limit] of [[`mutation-global:${day}`,Math.min(10000,this.limits.dailyWrites*10)],[`mutation-user:${user}:${day}`,200]] as const){
      const updated=await c.query('INSERT INTO publications.budgets VALUES($1,1,0,$2) ON CONFLICT(key) DO UPDATE SET count=publications.budgets.count+1 WHERE publications.budgets.count<$3 RETURNING count',[key,(day+1)*86400000,limit]);
      if(!updated.rowCount)throw new ApiError(429,'rate_limited');
    }
    await c.query('INSERT INTO publications.operations VALUES($1,$2,$3,$4)',[user,id,hash,response]);
  }
  private async owner(c:PoolClient,user:string,id:string,base?:string){
    const row=(await c.query<ItemRow>('SELECT * FROM publications.items WHERE id=$1 FOR UPDATE',[id])).rows[0];
    if(!row||row.author_id!==user||row.status==='purged')throw missing();
    if(base!==undefined&&row.current_revision_id!==base)throw new ApiError(409,'revision_changed');
    return row;
  }
  private async active(c:PoolClient,id:string){
    const row=(await c.query<ItemRow>('SELECT * FROM publications.items WHERE id=$1 AND status=\'active\' FOR SHARE',[id])).rows[0];if(!row)throw missing();return row;
  }
  private async reserve(user:string,operation:string,hash:string,bytes:number,sha256:string) {
    return this.tx(async c=>{
      const prior=await this.operation(c,user,operation,hash);if(prior)return {response:prior as Result};
      await this.lock(c,'publication-budget');
      const old=(await c.query<ObjectRow>('SELECT * FROM publications.objects WHERE owner_id=$1 AND operation_id=$2 FOR UPDATE',[user,operation])).rows[0];
      if(old){if(old.request_hash!==hash||Number(old.bytes)!==bytes)throw retryChanged();if(old.state!=='uploading'||Number(old.lease_until)>this.clock())throw new ApiError(503,'publication_busy');}
      const id=old?.id??randomUUID(),lease=randomUUID(),now=this.clock();
      if(!old){
        const used=Number((await c.query('SELECT COALESCE(sum(bytes),0) AS n FROM publications.objects')).rows[0].n);
        if(used+bytes>this.limits.storageBytes)throw new ApiError(503,'publication_budget_exhausted');
        const day=Math.floor(now/86400000),expiry=(day+1)*86400000;
        for(const [key,maxCount,maxBytes] of [[`global:${day}`,this.limits.dailyWrites,this.limits.dailyBytes],[`user:${user}:${day}`,20,Math.min(this.limits.dailyBytes,128*1024**2)]] as const){
          const usage=(await c.query('SELECT count,bytes FROM publications.budgets WHERE key=$1',[key])).rows[0];
          if(Number(usage?.count??0)>=maxCount||Number(usage?.bytes??0)+bytes>maxBytes)throw new ApiError(429,'rate_limited');
          await c.query('INSERT INTO publications.budgets VALUES($1,1,$2,$3) ON CONFLICT(key) DO UPDATE SET count=publications.budgets.count+1,bytes=publications.budgets.bytes+$2',[key,bytes,expiry]);
        }
        await c.query('INSERT INTO publications.objects VALUES($1,$2,$3,$4,$5,$6,$7,\'uploading\',$8,$9,$10)',[id,user,operation,hash,`publications/${id}.pittech`,sha256,bytes,lease,now+300000,now]);
      }else await c.query('UPDATE publications.objects SET lease_id=$2,lease_until=$3 WHERE id=$1',[id,lease,now+300000]);
      return {object:{id,key:`publications/${id}.pittech`,lease}};
    });
  }
  /** The same submitted archive/expected revision always recovers the same result. Source display credits never come from the caller. */
  async publish(user:string,operation:string,validated:ValidatedPublication,file:string,update?:{id:string;base:string},parent?:{id:string;revisionId:string},forkRequestHash?:string):Promise<Result>{
    if(update&&(!uuid(update.id)||!uuid(update.base))||parent&&(!uuid(parent.id)||!uuid(parent.revisionId)))throw missing();
    const hash=forkRequestHash??digest(stable({kind:update?'update':'publish',sha256:validated.sha256,payloadHash:digest(stable(validated.payload)),update:update??null,parent:parent??null}));
    const reservation=await this.reserve(user,operation,hash,validated.bytes,validated.sha256);
    if(reservation.response)return reservation.response;
    const object=reservation.object!;
    await this.storage.put(object.key,file);
    return this.tx(async c=>{
      const prior=await this.operation(c,user,operation,hash);if(prior)return prior as Result;
      const currentObject=(await c.query<ObjectRow>('SELECT * FROM publications.objects WHERE id=$1 FOR UPDATE',[object.id])).rows[0];
      if(!currentObject||currentObject.state!=='uploading'||currentObject.lease_id!==object.lease)throw new ApiError(503,'publication_busy');
      let item:ItemRow|undefined;
      if(update){item=await this.owner(c,user,update.id,update.base);if(item.status==='taken_down')throw new ApiError(403,'publication_under_review');}
      const payload=structuredClone(validated.payload);
      const previous=item?(await c.query<RevisionRow>('SELECT * FROM publications.revisions WHERE id=$1',[item.current_revision_id])).rows[0].payload:null;
      const selectedRoot=parent?{itemId:parent.id,revisionId:parent.revisionId,removed:false}:payload.source;
      const parentId=item?.parent_id??selectedRoot?.itemId??null;
      if(parentId===update?.id || item&&selectedRoot?.itemId!==previous?.source?.itemId)throw new ApiError(422,'invalid_lineage');
      const validateSource=async(source:PublicationPayload['source'],old:PublicationPayload['source'])=>{
        if(!source)return null;
        if(!uuid(source.itemId)||!uuid(source.revisionId)||source.itemId===update?.id)throw new ApiError(422,'invalid_lineage');
        const p=(await c.query<ItemRow>('SELECT * FROM publications.items WHERE id=$1 FOR SHARE',[source.itemId])).rows[0];
        if(!p||p.status!=='active'){
          if(old?.itemId===source.itemId&&old.revisionId===source.revisionId)return {itemId:source.itemId,revisionId:source.revisionId,removed:true};
          throw missing();
        }
        if(p.current_revision_id!==source.revisionId&&!(old?.itemId===source.itemId&&old.revisionId===source.revisionId))throw new ApiError(409,'source_revision_changed');
        return {itemId:source.itemId,revisionId:source.revisionId,removed:false};
      };
      payload.source=await validateSource(selectedRoot,previous?.source??null);
      for(const r of payload.recipes){
        const old=previous?.recipes.find(p=>p.id===r.id)?.source??null;
        r.source=await validateSource(r.source??payload.source,old??previous?.source??null);
      }
      const id=item?.id??object.id,revisionId=randomUUID();
      const revision=item?Number((await c.query('SELECT max(revision_no) AS n FROM publications.revisions WHERE item_id=$1',[id])).rows[0].n)+1:1;
      if(!item)await c.query('INSERT INTO publications.items VALUES($1,$2,NULL,$3,\'active\',$4,$4)',[id,user,parentId,this.clock()]);
      await c.query('INSERT INTO publications.revisions VALUES($1,$2,$3,$4,$5,$6,$7)',[revisionId,id,revision,object.id,payload.identityMode,payload,this.clock()]);
      await c.query('UPDATE publications.items SET current_revision_id=$2,status=\'active\',updated_at=$3 WHERE id=$1',[id,revisionId,this.clock()]);
      await c.query('UPDATE publications.objects SET state=\'active\' WHERE id=$1',[object.id]);
      const result={id,revisionId,revision,url:`https://pittech.app/c/${id}`};await this.record(c,user,operation,hash,result);return result;
    });
  }
  /** Explicit public fork. Ordinary local Save never calls this endpoint. Stable measured attempt IDs are retained. */
  async fork(user:string,id:string,operation:string,base:string,identity:unknown){
    if(!uuid(id)||!uuid(base)||!['named','anonymous'].includes(String(identity)))throw new ApiError(422,'invalid_fork');
    const hash=digest(stable({kind:'fork',id,base,identity}));
    const prior=await this.tx(c=>this.operation(c,user,operation,hash));if(prior)return prior as Result;
    const selected=await this.tx(async c=>{const item=await this.active(c,id);if(item.current_revision_id!==base)throw new ApiError(409,'source_revision_changed');
      const revision=(await c.query<RevisionRow>('SELECT * FROM publications.revisions WHERE id=$1',[base])).rows[0];
      const object=(await c.query<ObjectRow>('SELECT * FROM publications.objects WHERE id=$1 AND state=\'active\'',[revision.object_id])).rows[0];if(!object)throw missing();return {payload:structuredClone(revision.payload),key:object.key,sha256:object.sha256};});
    const bytes=await this.storage.get(selected.key),payload=selected.payload;
    if(digest(bytes)!==selected.sha256)throw new ApiError(503,'media_unavailable');
    payload.identityMode=identity as 'named'|'anonymous';payload.source={itemId:id,revisionId:base,removed:false};
    for(const recipe of payload.recipes)recipe.source=payload.source;
    const root=join(tmpdir(),'pittech-api-uploads');await mkdir(root,{recursive:true,mode:0o700});
    const directory=await mkdtemp(join(root,'upload-'));
    try{const file=join(directory,'fork.pittech');await writeFile(file,bytes,{mode:0o600,flag:'wx'});
      return await this.publish(user,operation,{payload,sha256:digest(bytes),bytes:bytes.length},file,undefined,{id,revisionId:base},hash);
    }finally{await rm(directory,{recursive:true,force:true});}
  }
  async unpublish(user:string,id:string,operation:string,base:string){
    if(!uuid(id)||!uuid(base))throw missing();const hash=digest(stable({kind:'unpublish',id,base}));
    return this.tx(async c=>{const prior=await this.operation(c,user,operation,hash);if(prior)return prior;
      await this.owner(c,user,id,base);await c.query('UPDATE publications.items SET status=\'unpublished\',updated_at=$2 WHERE id=$1',[id,this.clock()]);
      const result={id,unpublished:true};await this.record(c,user,operation,hash,result);return result;});
  }
  async like(user:string,id:string,operation:string,liked:boolean){
    if(!uuid(id))throw missing();const hash=digest(stable({kind:'like',id,liked}));
    return this.tx(async c=>{const prior=await this.operation(c,user,operation,hash);if(prior)return prior;await this.active(c,id);
      if(liked)await c.query('INSERT INTO publications.likes VALUES($1,$2) ON CONFLICT DO NOTHING',[user,id]);else await c.query('DELETE FROM publications.likes WHERE user_id=$1 AND item_id=$2',[user,id]);
      const result={id,liked};await this.record(c,user,operation,hash,result);return result;});
  }
  private async credit(c:PoolClient,item:ItemRow,revision:RevisionRow){
    if(revision.identity_mode==='anonymous'||!item.author_id)return {displayName:'a PitTech cook'};
    const profile=(await c.query('SELECT handle,display_name,is_public FROM publications.profiles WHERE user_id=$1',[item.author_id])).rows[0];
    return {displayName:profile?.display_name||'a PitTech cook',...(profile?.is_public&&profile.handle?{profileUrl:`https://pittech.app/p/${profile.handle}`}:{} )};
  }
  private async safeSource(c:PoolClient,id:string,revisionId:string){
    const item=(await c.query<ItemRow>('SELECT * FROM publications.items WHERE id=$1',[id])).rows[0];
    if(!item||item.status!=='active')return {itemId:id,revisionId,removed:true};
    const revision=(await c.query<RevisionRow>('SELECT * FROM publications.revisions WHERE id=$1',[revisionId])).rows[0];
    if(!revision||revision.item_id!==id)return {itemId:id,revisionId,removed:true};
    return {itemId:id,revisionId:revision.id,removed:false,displayName:(await this.credit(c,item,revision)).displayName};
  }
  async get(id:string){if(!uuid(id))throw missing();return this.tx(async c=>{
    const item=await this.active(c,id),revision=(await c.query<RevisionRow>('SELECT * FROM publications.revisions WHERE id=$1',[item.current_revision_id])).rows[0];
    const payload=structuredClone(revision.payload);
    // Sources are rendered from current visibility; stale copied names never leak through revisions.
    if(payload.source)payload.source=await this.safeSource(c,payload.source.itemId,payload.source.revisionId);
    for(const recipe of payload.recipes)if(recipe.source)recipe.source=await this.safeSource(c,recipe.source.itemId,recipe.source.revisionId);
    return {id,revisionId:revision.id,revision:revision.revision_no,payload,credit:await this.credit(c,item,revision),
      media:payload.recipes.flatMap(r=>r.assets).map(a=>({id:a.id,url:`https://api.pittech.app/v1/recipes/${id}/assets/${a.id}`,sha256:a.sha256,mimeType:a.mimeType})),
      likes:Number((await c.query('SELECT count(*) AS n FROM publications.likes WHERE item_id=$1',[id])).rows[0].n),
      forks:Number((await c.query('SELECT count(*) AS n FROM publications.items WHERE parent_id=$1 AND status=\'active\'',[id])).rows[0].n)};
  });}
  async media(id:string,assetId:string){
    if(!uuid(id)||!uuid(assetId))throw missing();
    const selected=await this.tx(async c=>{const item=await this.active(c,id);const revision=(await c.query<RevisionRow>('SELECT * FROM publications.revisions WHERE id=$1',[item.current_revision_id])).rows[0];
      const asset=revision.payload.recipes.flatMap(r=>r.assets).find(a=>a.id===assetId);if(!asset)throw missing();
      const object=(await c.query<ObjectRow>('SELECT * FROM publications.objects WHERE id=$1 AND state=\'active\'',[revision.object_id])).rows[0];if(!object)throw missing();return {asset,key:object.key,sha256:object.sha256};});
    const archive=await this.storage.get(selected.key);if(digest(archive)!==selected.sha256)throw new ApiError(503,'media_unavailable');
    const files=await readZip(archive,'publication'),bytes=files.get(selected.asset.path);if(!bytes||digest(bytes)!==selected.asset.sha256)throw new ApiError(503,'media_unavailable');
    // Recheck visibility after storage I/O; deletion/unpublish cannot grant new media reads.
    await this.tx(c=>this.active(c,id));return {bytes,mimeType:selected.asset.mimeType,sha256:selected.asset.sha256};
  }
  async discover(input:{sort?:string;food?:string;query?:string;cursor?:string}){
    const sort=input.sort??'liked',food=input.food??'',query=input.query??'';
    if(!['liked','forked'].includes(sort)||food.length>80||query.length>80||(input.cursor?.length??0)>256)throw new ApiError(400,'invalid_filter');
    let cursor:{score:number;id:string}|undefined;
    if(input.cursor){try{cursor=JSON.parse(Buffer.from(input.cursor,'base64url').toString());}catch{throw new ApiError(400,'invalid_cursor');}
      if(!cursor||!uuid(cursor.id)||!Number.isSafeInteger(cursor.score)||cursor.score<0)throw new ApiError(400,'invalid_cursor');}
    const score=sort==='liked'?'(SELECT count(*) FROM publications.likes l WHERE l.item_id=i.id)':'(SELECT count(*) FROM publications.items f WHERE f.parent_id=i.id AND f.status=\'active\')';
    const rows=await this.pool.query(`WITH shelf AS (SELECT i.id,${score}::bigint AS score FROM publications.items i JOIN publications.revisions r ON r.id=i.current_revision_id
      WHERE i.status='active' AND ($1='' OR EXISTS(SELECT 1 FROM jsonb_array_elements(r.payload->'recipes') p WHERE lower(p->>'foodType')=lower($1)))
      AND ($2='' OR position(lower($2) in lower(r.payload->>'title'))>0))
      SELECT * FROM shelf WHERE $3::bigint IS NULL OR (score<$3 OR (score=$3 AND id>$4::uuid)) ORDER BY score DESC,id ASC LIMIT 21`,[food,query,cursor?.score??null,cursor?.id??null]);
    const page=rows.rows.slice(0,20),items=[];
    for(const row of page){try{const item=await this.get(row.id);items.push({id:item.id,revisionId:item.revisionId,title:item.payload.title,kind:item.payload.kind,credit:item.credit,likes:item.likes,forks:item.forks,heroAssetId:item.payload.heroAssetId});}catch(e){if(!(e instanceof ApiError)||e.status!==404)throw e;}}
    const last=page.at(-1);return {items,nextCursor:rows.rows.length>20&&last?Buffer.from(JSON.stringify({score:Number(last.score),id:last.id})).toString('base64url'):null};
  }
  async profile(handle:string,cursor?:string){
    if(!/^[a-z0-9_]{3,24}$/.test(handle)||cursor&&!uuid(cursor))throw missing();
    const p=(await this.pool.query('SELECT user_id,display_name FROM publications.profiles WHERE handle=$1 AND is_public=true',[handle])).rows[0];if(!p)throw missing();
    const ids=(await this.pool.query('SELECT i.id FROM publications.items i JOIN publications.revisions r ON r.id=i.current_revision_id WHERE i.author_id=$1 AND i.status=\'active\' AND r.identity_mode=\'named\' AND ($2::uuid IS NULL OR i.id>$2) ORDER BY i.id LIMIT 21',[p.user_id,cursor??null])).rows;
    return {handle,displayName:p.display_name,items:ids.slice(0,20).map(x=>({id:x.id,url:`https://pittech.app/c/${x.id}`})),nextCursor:ids.length>20?ids[19].id:null};
  }
  async myProfile(user:string){
    const row=(await this.pool.query('SELECT handle,display_name,is_public FROM publications.profiles WHERE user_id=$1',[user])).rows[0];
    return {handle:row?.handle??null,displayName:row?.display_name??'',isPublic:row?.is_public??false};
  }
  async myItems(user:string,cursor?:string){
    if(cursor&&!uuid(cursor))throw new ApiError(400,'invalid_cursor');
    const rows=(await this.pool.query('SELECT i.id,i.status,r.id AS revision_id,r.revision_no,r.payload->>\'title\' AS title,r.identity_mode FROM publications.items i JOIN publications.revisions r ON r.id=i.current_revision_id WHERE i.author_id=$1 AND i.status<>\'purged\' AND ($2::uuid IS NULL OR i.id>$2) ORDER BY i.id LIMIT 21',[user,cursor??null])).rows;
    return {items:rows.slice(0,20).map(r=>({id:r.id,status:r.status,revisionId:r.revision_id,revision:r.revision_no,title:r.title,identityMode:r.identity_mode})),nextCursor:rows.length>20?rows[19].id:null};
  }
  async updateProfile(user:string,operation:string,input:{handle:unknown;displayName:unknown;isPublic:unknown}){
    if(input.handle!==null&&(typeof input.handle!=='string'||/[^\x00-\x7f]/.test(input.handle)))throw new ApiError(422,'invalid_profile');
    const handle=typeof input.handle==='string'?input.handle.toLowerCase():null,name=input.displayName;
    if(handle!==null&&(!/^[a-z0-9_]{3,24}$/.test(handle)||/^(admin|api|auth|support|pittech|moderator|deleted|removed|fuck|shit|nazi)$/i.test(handle))||typeof name!=='string'||name.length>160||/[\x00-\x1f\x7f]/.test(name)||typeof input.isPublic!=='boolean'||input.isPublic&&(!handle||!name.trim()))throw new ApiError(422,'invalid_profile');
    const value={handle,displayName:name.trim(),isPublic:input.isPublic},hash=digest(stable({kind:'profile',...value}));
    return this.tx(async c=>{const prior=await this.operation(c,user,operation,hash);if(prior)return prior;
      try{await c.query('INSERT INTO publications.profiles VALUES($1,$2,$3,$4,$5) ON CONFLICT(user_id) DO UPDATE SET handle=$2,display_name=$3,is_public=$4,updated_at=$5',[user,handle,value.displayName,value.isPublic,this.clock()]);}
      catch(e){if((e as {code?:string}).code==='23505')throw new ApiError(409,'handle_unavailable');throw e;}
      await this.record(c,user,operation,hash,value);return value;});
  }
  async sweep(){
    const lease=randomUUID(),now=this.clock();
    const objects=await this.pool.query<ObjectRow>(`WITH ready AS (SELECT id FROM publications.objects WHERE state IN ('uploading','deleting') AND lease_until<$1 FOR UPDATE SKIP LOCKED LIMIT 10)
      UPDATE publications.objects o SET state='deleting',lease_id=$2,lease_until=$1+60000 FROM ready r WHERE o.id=r.id RETURNING o.*`,[now,lease]);
    for(const object of objects.rows){try{await this.storage.delete(object.key);await this.pool.query('DELETE FROM publications.objects WHERE id=$1 AND state=\'deleting\' AND lease_id=$2',[object.id,lease]);}catch{/* Keep reservation until a later leased cleanup succeeds. */}}
    await this.pool.query('DELETE FROM publications.budgets WHERE expires_at<=$1',[now]);
  }
}
