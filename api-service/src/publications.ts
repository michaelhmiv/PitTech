import { onlineEnvironment, type OnlineEnvironment } from './online-environment.ts';
import { SOURCE_REVIEW_REQUIRED_SQL,BLOCKED_PUBLIC_ASSET_SQL } from './publication-policy.ts';
import { lockAccount } from './lifecycle-contract.ts';
import { PUBLICATION_TERMS_VERSION,type PublicationAcknowledgment } from './publication-terms.ts';
import { randomUUID } from 'node:crypto';
import { mkdir,mkdtemp,readFile,writeFile,rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import type { Pool, PoolClient } from 'pg';
import { ApiError, digest, LIMITS } from './core.ts';
import { readZip,writeZip } from './zip.ts';
import { stable } from './pack.ts';
import { publicCookWorker } from './publication-cook-validation.ts';
import type { Storage } from './storage.ts';
import type { PublicationPayload } from './publication-pack.ts';
import type { ValidatedPublication } from './publication-validation.ts';

const uuid=(value:unknown):value is string=>typeof value==='string'&&/^[a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(value);
const missing=()=>new ApiError(404,'publication_unavailable');
const retryChanged=()=>new ApiError(409,'retry_content_changed');
type Result={id:string;revisionId:string;revision:number;url:string;status?:string};
type ObjectRow={id:string;owner_id:string;operation_id:string;request_hash:string;key:string;sha256:string;bytes:string;state:string;lease_id:string;lease_until:string};
type ItemRow={id:string;author_id:string|null;current_revision_id:string;parent_id:string|null;status:string;created_at:string};
type RevisionRow={id:string;item_id:string;revision_no:number;object_id:string;identity_mode:string;payload:PublicationPayload};
export type PublicationLimits={storageBytes:number;dailyBytes:number;dailyWrites:number};
export class Publications {
  private online:OnlineEnvironment; private pool:Pool; private storage:Storage; private limits:PublicationLimits;private reviewRequired:boolean;private hashHandle?:((handle:string)=>string); clock:()=>number;
  constructor(pool:Pool,storage:Storage,limits:PublicationLimits,clock=Date.now,reviewRequired=false,hashHandle?:((handle:string)=>string),online:OnlineEnvironment=onlineEnvironment()) {
    if(Object.values(limits).some(n=>!Number.isSafeInteger(n)||n<1)) throw new Error('Publication budgets required');
    if(reviewRequired&&!hashHandle)throw new Error('Publication moderation configuration required');
    this.online=online;this.hashHandle=hashHandle;this.pool=pool;this.storage=storage;this.limits=limits;this.clock=clock;this.reviewRequired=reviewRequired;
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
  private async assertAccount(c:PoolClient,user:string,allowSuspended=false){
    await lockAccount(c,user);
    const row=(await c.query('SELECT deleting,suspended FROM publications.publisher_controls WHERE user_id=$1',[user])).rows[0];
    if(!row)throw new ApiError(401,'authentication_required');if(row.deleting||row.suspended&&!allowSuspended)throw new ApiError(403,'account_restricted');
  }
  private async active(c:PoolClient,id:string,blocked:string[]=[]){
    const row=(await c.query<ItemRow>(`SELECT i.* FROM publications.items i JOIN publications.revisions r ON r.id=i.current_revision_id
      LEFT JOIN publications.publisher_controls c ON c.user_id=i.author_id
      WHERE i.id=$1 AND i.status='active' AND NOT COALESCE(c.deleting OR c.suspended,false) AND NOT(i.author_id=ANY($2::uuid[]))
      AND NOT(${BLOCKED_PUBLIC_ASSET_SQL}) AND NOT(${SOURCE_REVIEW_REQUIRED_SQL}) FOR SHARE OF i`,[id,blocked])).rows[0];
    if(!row)throw missing();return row;
  }
  private async reserve(user:string,operation:string,hash:string,bytes:number,sha256:string,acknowledgment:PublicationAcknowledgment) {
    return this.tx(async c=>{
      const prior=await this.operation(c,user,operation,hash);if(prior)return {response:prior as Result};
      await this.assertAccount(c,user);
      if(acknowledgment.version!==PUBLICATION_TERMS_VERSION)throw new ApiError(428,'publication_terms_required');
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
  async publish(user:string,operation:string,validated:ValidatedPublication,file:string,update?:{id:string;base:string},parent?:{id:string;revisionId:string},forkRequestHash?:string,acknowledgment:PublicationAcknowledgment={}):Promise<Result>{
    if(update&&(!uuid(update.id)||!uuid(update.base))||parent&&(!uuid(parent.id)||!uuid(parent.revisionId)))throw missing();
    const hash=forkRequestHash??digest(stable({kind:update?'update':'publish',sha256:validated.sha256,payloadHash:digest(stable(validated.payload)),update:update??null,parent:parent??null,...(acknowledgment.version===undefined?{}:{termsVersion:acknowledgment.version})}));
    const reservation=await this.reserve(user,operation,hash,validated.bytes,validated.sha256,acknowledgment);
    if(reservation.response)return reservation.response;
    const object=reservation.object!;
    await this.storage.put(object.key,file);
    return this.tx(async c=>{
      const prior=await this.operation(c,user,operation,hash);if(prior)return prior as Result;
      await this.assertAccount(c,user);
      const bad=await c.query('SELECT 1 FROM publications.blocked_assets WHERE sha256=ANY($1::text[]) LIMIT 1',[validated.payload.recipes.flatMap(recipe=>recipe.assets.map(asset=>asset.sha256))]);if(bad.rowCount)throw new ApiError(403,'content_under_review');
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
        const retained=old?.itemId===source.itemId&&old.revisionId===source.revisionId;
        if(!p)throw missing();
        if(p.status==='taken_down'){
          if(retained)return {itemId:source.itemId,revisionId:source.revisionId,removed:true};
          throw new ApiError(403,'publication_under_review');
        }
        // A saved copy pins its source revision. Later upstream edits never force
        // the owner to republish newer instructions or discard independently owned media.
        const pinned=(await c.query<RevisionRow>('SELECT * FROM publications.revisions WHERE id=$1 AND item_id=$2',[source.revisionId,source.itemId])).rows[0];
        if(!pinned&&p.status!=='purged')throw new ApiError(422,'invalid_lineage');
        if(parent&&source.itemId===parent.id&&(p.status!=='active'||p.current_revision_id!==parent.revisionId))throw new ApiError(409,'source_revision_changed');
        if(p.status!=='active')return {itemId:source.itemId,revisionId:source.revisionId,removed:true};
        return {itemId:source.itemId,revisionId:source.revisionId,removed:false};
      };
      payload.source=await validateSource(selectedRoot,previous?.source??null);
      for(const r of payload.recipes){
        const old=previous?.recipes.find(p=>p.id===r.id)?.source??null;
        r.source=await validateSource(r.source??payload.source,old??previous?.source??null);
      }
      // Bound the complete pinned lineage, including collection members. Reject
      // an item-level cycle even when immutable revision IDs would terminate it.
      let frontier=[payload.source,...payload.recipes.map(r=>r.source)].filter(s=>s!==null),depth=0;
      const seen=new Set<string>();
      while(frontier.length){
        if(++depth>32)throw new ApiError(422,'lineage_too_deep');
        const next:NonNullable<PublicationPayload['source']>[]=[];
        for(const source of frontier){
          if(source.itemId===(item?.id??object.id))throw new ApiError(422,'invalid_lineage');
          const key=source.itemId+':'+source.revisionId;if(seen.has(key))continue;
          seen.add(key);if(seen.size>200)throw new ApiError(422,'lineage_too_deep');
          const revision=(await c.query<RevisionRow>('SELECT * FROM publications.revisions WHERE id=$1 AND item_id=$2',[source.revisionId,source.itemId])).rows[0];
          if(revision)for(const parent of [revision.payload.source,...revision.payload.recipes.map(r=>r.source)])if(parent)next.push(parent);
        }
        frontier=next;
      }
      const id=item?.id??object.id,revisionId=randomUUID();
      const revision=item?Number((await c.query('SELECT max(revision_no) AS n FROM publications.revisions WHERE item_id=$1',[id])).rows[0].n)+1:1;
      if(!item)await c.query('INSERT INTO publications.items VALUES($1,$2,NULL,$3,\'active\',$4,$4)',[id,user,parentId,this.clock()]);
      await c.query('INSERT INTO publications.revisions VALUES($1,$2,$3,$4,$5,$6,$7)',[revisionId,id,revision,object.id,payload.identityMode,payload,this.clock()]);
      const status=this.reviewRequired?'pending_review':'active';
      await c.query('UPDATE publications.items SET current_revision_id=$2,status=$3,updated_at=$4 WHERE id=$1',[id,revisionId,status,this.clock()]);
      await c.query('INSERT INTO publications.publisher_aliases(item_id) VALUES($1) ON CONFLICT(item_id) DO NOTHING',[id]);
      if(this.reviewRequired)await c.query('INSERT INTO moderation.reviews(item_id,revision_id,reason,created_at) VALUES($1,$2,$3,$4)',[id,revisionId,item?'changed_content':'new_content',this.clock()]);
      await c.query('UPDATE publications.objects SET state=\'active\' WHERE id=$1',[object.id]);
      const result={id,revisionId,revision,url:`${this.online.publicOrigin}/c/${id}`,...(this.reviewRequired?{status}:{})};await this.record(c,user,operation,hash,result);await c.query('INSERT INTO publications.acknowledgments VALUES($1,$2,$3,$4,$5)',[user,operation,revisionId,PUBLICATION_TERMS_VERSION,this.clock()]);return result;
    });
  }
  /** Explicit public fork. Ordinary local Save never calls this endpoint. Stable measured attempt IDs are retained. */
  async fork(user:string,id:string,operation:string,base:string,identity:unknown,acknowledgment:PublicationAcknowledgment={},blocked:string[]=[]){
    if(!uuid(id)||!uuid(base)||!['named','anonymous'].includes(String(identity)))throw new ApiError(422,'invalid_fork');
    const hash=digest(stable({kind:'fork',id,base,identity,...(acknowledgment.version===undefined?{}:{termsVersion:acknowledgment.version})}));
    const prior=await this.tx(c=>this.operation(c,user,operation,hash));if(prior)return prior as Result;
    if(acknowledgment.version!==PUBLICATION_TERMS_VERSION)throw new ApiError(428,'publication_terms_required');
    const selected=await this.tx(async c=>{const item=await this.active(c,id,blocked);if(item.current_revision_id!==base)throw new ApiError(409,'source_revision_changed');
      const revision=(await c.query<RevisionRow>('SELECT * FROM publications.revisions WHERE id=$1',[base])).rows[0];
      const object=(await c.query<ObjectRow>('SELECT * FROM publications.objects WHERE id=$1 AND state=\'active\'',[revision.object_id])).rows[0];if(!object)throw missing();return {payload:structuredClone(revision.payload),key:object.key,sha256:object.sha256};});
    const stored=await this.storage.get(selected.key),payload=selected.payload;
    if(digest(stored)!==selected.sha256)throw new ApiError(503,'media_unavailable');
    payload.identityMode=identity as 'named'|'anonymous';payload.source={itemId:id,revisionId:base,removed:false};
    for(const recipe of payload.recipes)recipe.source=payload.source;
    const files=await readZip(stored,'publication');files.set('publication.json',Buffer.from(stable(payload)));const bytes=writeZip(files);
    const root=join(tmpdir(),'pittech-api-uploads');await mkdir(root,{recursive:true,mode:0o700});
    const directory=await mkdtemp(join(root,'upload-'));
    try{const file=join(directory,'fork.pittech');await writeFile(file,bytes,{mode:0o600,flag:'wx'});
      return await this.publish(user,operation,{payload,sha256:digest(bytes),bytes:bytes.length},file,undefined,{id,revisionId:base},hash,acknowledgment);
    }finally{await rm(directory,{recursive:true,force:true});}
  }
  async unpublish(user:string,id:string,operation:string,base:string){
    if(!uuid(id)||!uuid(base))throw missing();const hash=digest(stable({kind:'unpublish',id,base}));
    return this.tx(async c=>{const prior=await this.operation(c,user,operation,hash);if(prior)return prior;
      await this.assertAccount(c,user,true);await this.owner(c,user,id,base);await c.query('UPDATE publications.items SET status=\'unpublished\',updated_at=$2 WHERE id=$1',[id,this.clock()]);
      const result={id,unpublished:true};await this.record(c,user,operation,hash,result);return result;});
  }
  async like(user:string,id:string,operation:string,liked:boolean,blocked:string[]=[]){
    if(!uuid(id))throw missing();const hash=digest(stable({kind:'like',id,liked}));
    return this.tx(async c=>{const prior=await this.operation(c,user,operation,hash);if(prior)return prior;await this.assertAccount(c,user,true);await this.active(c,id,blocked);
      if(liked)await c.query('INSERT INTO publications.likes VALUES($1,$2) ON CONFLICT DO NOTHING',[user,id]);else await c.query('DELETE FROM publications.likes WHERE user_id=$1 AND item_id=$2',[user,id]);
      const result={id,liked};await this.record(c,user,operation,hash,result);return result;});
  }
  private async credit(c:PoolClient,item:ItemRow,revision:RevisionRow){
    if(revision.identity_mode==='anonymous'||!item.author_id)return {displayName:'a PitTech cook'};
    const profile=(await c.query('SELECT p.handle,p.display_name,p.is_public FROM publications.profiles p WHERE p.user_id=$1 AND (NOT $2::boolean OR EXISTS(SELECT 1 FROM moderation.approved_profiles a WHERE a.user_id=p.user_id AND a.review_id=p.review_id))',[item.author_id,this.reviewRequired])).rows[0];
    return {displayName:profile?.display_name||'a PitTech cook',...(profile?.is_public&&profile.handle?{profileUrl:`${this.online.publicOrigin}/p/${profile.handle}`}:{} )};
  }
  private async safeSource(c:PoolClient,id:string,revisionId:string,blocked:string[]=[]){
    let item:ItemRow;try{item=await this.active(c,id,blocked);}catch(error){if(error instanceof ApiError&&error.status===404)return {itemId:id,revisionId,removed:true};throw error;}
    const revision=(await c.query<RevisionRow>('SELECT * FROM publications.revisions WHERE id=$1',[revisionId])).rows[0];
    if(!revision||revision.item_id!==id)return {itemId:id,revisionId,removed:true};
    return {itemId:id,revisionId:revision.id,removed:false,displayName:(await this.credit(c,item,revision)).displayName};
  }
  async get(id:string,blocked:string[]=[]){if(!uuid(id))throw missing();return this.tx(async c=>{
    const item=await this.active(c,id,blocked),revision=(await c.query<RevisionRow>('SELECT * FROM publications.revisions WHERE id=$1',[item.current_revision_id])).rows[0];
    const payload=structuredClone(revision.payload);
    // Sources are rendered from current visibility; stale copied names never leak through revisions.
    if(payload.source)payload.source=await this.safeSource(c,payload.source.itemId,payload.source.revisionId,blocked);
    for(const recipe of payload.recipes)if(recipe.source)recipe.source=await this.safeSource(c,recipe.source.itemId,recipe.source.revisionId,blocked);
    const alias=(await c.query('SELECT alias FROM publications.publisher_aliases WHERE item_id=$1',[id])).rows[0]?.alias;
    return {id,publisherAlias:alias,revisionId:revision.id,revision:revision.revision_no,payload,credit:await this.credit(c,item,revision),
      media:payload.recipes.flatMap(r=>r.assets).map(a=>({id:a.id,url:`${this.online.apiOrigin}/v1/recipes/${id}/assets/${a.id}`,sha256:a.sha256,mimeType:a.mimeType})),
      likes:Number((await c.query('SELECT count(*) AS n FROM publications.likes WHERE item_id=$1',[id])).rows[0].n),
      forks:Number((await c.query('SELECT count(*) AS n FROM publications.items WHERE parent_id=$1 AND status=\'active\'',[id])).rows[0].n)};
  });}
  async media(id:string,assetId:string,blocked:string[]=[]){
    if(!uuid(id)||!uuid(assetId))throw missing();
    const selected=await this.tx(async c=>{const item=await this.active(c,id,blocked);const revision=(await c.query<RevisionRow>('SELECT * FROM publications.revisions WHERE id=$1',[item.current_revision_id])).rows[0];
      const asset=revision.payload.recipes.flatMap(r=>r.assets).find(a=>a.id===assetId);if(!asset)throw missing();
      const object=(await c.query<ObjectRow>('SELECT * FROM publications.objects WHERE id=$1 AND state=\'active\'',[revision.object_id])).rows[0];if(!object)throw missing();return {asset,key:object.key,sha256:object.sha256};});
    const archive=await this.storage.get(selected.key);if(digest(archive)!==selected.sha256)throw new ApiError(503,'media_unavailable');
    const files=await readZip(archive,'publication'),bytes=files.get(selected.asset.path);if(!bytes||digest(bytes)!==selected.asset.sha256)throw new ApiError(503,'media_unavailable');
    // Recheck visibility after storage I/O; deletion/unpublish cannot grant new media reads.
    await this.tx(c=>this.active(c,id,blocked));return {bytes,mimeType:selected.asset.mimeType,sha256:selected.asset.sha256};
  }
  async cookPack(id:string,expected:string,blocked:string[]=[]){
    if(!uuid(expected))throw new ApiError(400,'revision_required');
    const publication=await this.get(id,blocked);if(publication.revisionId!==expected)throw new ApiError(409,'revision_changed');
    const selected=await this.tx(async c=>{const item=await this.active(c,id,blocked);if(item.current_revision_id!==expected)throw new ApiError(409,'revision_changed');
      const object=(await c.query<ObjectRow>('SELECT o.* FROM publications.revisions r JOIN publications.objects o ON o.id=r.object_id WHERE r.id=$1 AND o.state=\'active\'',[expected])).rows[0];if(!object)throw missing();return object;});
    const bytes=await this.storage.get(selected.key);if(digest(bytes)!==selected.sha256)throw new ApiError(503,'media_unavailable');
    const root=join(tmpdir(),'pittech-api-uploads');await mkdir(root,{recursive:true,mode:0o700});const directory=await mkdtemp(join(root,'upload-'));
    try{
      const input=join(directory,'publication.zip'),output=join(directory,'public.pittech');await writeFile(input,bytes,{mode:0o600,flag:'wx'});
      const summary=await publicCookWorker(input,output,publication),pack=await readFile(output);
      if(pack.length!==summary.bytes||digest(pack)!==summary.sha256)throw new ApiError(503,'pack_unavailable');
      const still=await this.tx(c=>this.active(c,id,blocked));if(still.current_revision_id!==expected)throw new ApiError(409,'revision_changed');return {bytes:pack,sha256:summary.sha256};
    }finally{await rm(directory,{recursive:true,force:true});}
  }
  async discover(input:{sort?:string;food?:string;query?:string;cursor?:string},blocked:string[]=[]){
    const sort=input.sort??'liked',food=input.food??'',query=input.query??'';
    if(!['liked','forked'].includes(sort)||food.length>80||query.length>80||(input.cursor?.length??0)>256)throw new ApiError(400,'invalid_filter');
    let cursor:{score:number;id:string}|undefined;
    if(input.cursor){try{cursor=JSON.parse(Buffer.from(input.cursor,'base64url').toString());}catch{throw new ApiError(400,'invalid_cursor');}
      if(!cursor||!uuid(cursor.id)||!Number.isSafeInteger(cursor.score)||cursor.score<0)throw new ApiError(400,'invalid_cursor');}
    const score=sort==='liked'?'(SELECT count(*) FROM publications.likes l WHERE l.item_id=i.id)':'(SELECT count(*) FROM publications.items f WHERE f.parent_id=i.id AND f.status=\'active\')';
    const rows=await this.pool.query(`WITH shelf AS (SELECT i.id,${score}::bigint AS score FROM publications.items i JOIN publications.revisions r ON r.id=i.current_revision_id
      WHERE i.status='active' AND NOT(i.author_id=ANY($5::uuid[])) AND ($1='' OR EXISTS(SELECT 1 FROM jsonb_array_elements(r.payload->'recipes') p WHERE lower(p->>'foodType')=lower($1)))
      AND ($2='' OR position(lower($2) in lower(r.payload->>'title'))>0))
      SELECT * FROM shelf WHERE $3::bigint IS NULL OR (score<$3 OR (score=$3 AND id>$4::uuid)) ORDER BY score DESC,id ASC LIMIT 21`,[food,query,cursor?.score??null,cursor?.id??null,blocked]);
    const page=rows.rows.slice(0,20),items=[];
    for(const row of page){try{const item=await this.get(row.id,blocked);items.push({id:item.id,revisionId:item.revisionId,title:item.payload.title,kind:item.payload.kind,credit:item.credit,likes:item.likes,forks:item.forks,heroAssetId:item.payload.heroAssetId});}catch(e){if(!(e instanceof ApiError)||e.status!==404)throw e;}}
    const last=page.at(-1);return {items,nextCursor:rows.rows.length>20&&last?Buffer.from(JSON.stringify({score:Number(last.score),id:last.id})).toString('base64url'):null};
  }
  async profile(handle:string,cursor?:string,blocked:string[]=[]){
    if(!/^[a-z0-9_]{3,24}$/.test(handle)||cursor&&!uuid(cursor))throw missing();
    const p=(await this.pool.query('SELECT p.user_id,p.display_name FROM publications.profiles p JOIN publications.publisher_controls c ON c.user_id=p.user_id WHERE p.handle=$1 AND p.is_public=true AND NOT(c.deleting OR c.suspended) AND NOT(p.user_id=ANY($2::uuid[])) AND (NOT $3::boolean OR EXISTS(SELECT 1 FROM moderation.approved_profiles a WHERE a.user_id=p.user_id AND a.review_id=p.review_id))',[handle,blocked,this.reviewRequired])).rows[0];if(!p)throw missing();
    const ids=(await this.pool.query('SELECT i.id FROM publications.items i JOIN publications.revisions r ON r.id=i.current_revision_id WHERE i.author_id=$1 AND i.status=\'active\' AND r.identity_mode=\'named\' AND ($2::uuid IS NULL OR i.id>$2) ORDER BY i.id LIMIT 21',[p.user_id,cursor??null])).rows;
    const visible=[];for(const row of ids.slice(0,20)){try{await this.get(row.id,blocked);visible.push({id:row.id,url:`${this.online.publicOrigin}/c/${row.id}`});}catch(error){if(!(error instanceof ApiError)||error.status!==404)throw error;}}return {handle,displayName:p.display_name,items:visible,nextCursor:ids.length>20?ids[19].id:null};
  }
  async myProfile(user:string){
    const row=(await this.pool.query('SELECT p.handle,p.display_name,p.is_public,q.state FROM publications.profiles p LEFT JOIN moderation.profile_reviews q ON q.id=p.review_id WHERE p.user_id=$1',[user])).rows[0];
    return {handle:row?.handle??null,displayName:row?.display_name??'',isPublic:row?.is_public??false,...(this.reviewRequired?{reviewStatus:row?.state??'none'}:{})};
  }
  async ownedArchive(user:string,id:string,expected:string){
    if(!uuid(id)||!uuid(expected))throw new ApiError(400,'revision_required');
    const select=async(c:PoolClient)=>{const item=await this.owner(c,user,id,expected);if(item.status==='taken_down')throw new ApiError(403,'publication_under_review');return item;};
    const selected=await this.tx(async c=>{await select(c);
      const row=(await c.query<ObjectRow&{payload:PublicationPayload}>('SELECT o.*,r.payload FROM publications.revisions r JOIN publications.objects o ON o.id=r.object_id WHERE r.id=$1 AND r.item_id=$2 AND o.state=\'active\'',[expected,id])).rows[0];if(!row)throw missing();return row;});
    const stored=await this.storage.get(selected.key);
    if(stored.length>LIMITS.compressed||stored.length!==Number(selected.bytes)||digest(stored)!==selected.sha256)throw new ApiError(503,'publication_unavailable');
    const files=await readZip(stored,'publication'),payload=selected.payload;
    // The committed revision owns source attribution. Older direct server forks
    // can have original-source publication.json bytes in their independent blob.
    const strip=(source:PublicationPayload['source'])=>source?{itemId:source.itemId,revisionId:source.revisionId,removed:source.removed}:null;
    payload.source=strip(payload.source);for(const recipe of payload.recipes)recipe.source=strip(recipe.source);
    const accepted=new Map<string,Buffer>([['publication.json',Buffer.from(stable(payload))]]);
    if(accepted.get('publication.json')!.length>8*1024**2)throw new ApiError(503,'publication_unavailable');
    for(const asset of payload.recipes.flatMap(recipe=>recipe.assets)){
      const bytes=files.get(asset.path);if(!bytes||digest(bytes)!==asset.sha256)throw new ApiError(503,'publication_unavailable');accepted.set(asset.path,bytes);
    }
    const bytes=writeZip(accepted);
    // Ownership, revision and moderation are rechecked after storage and ZIP work.
    await this.tx(select);return {bytes,sha256:digest(bytes)};
  }

  async myItem(user:string,id:string){
    if(!uuid(id))throw missing();
    const r=(await this.pool.query(`SELECT i.id,CASE WHEN i.status='active' AND ((${SOURCE_REVIEW_REQUIRED_SQL}) OR (${BLOCKED_PUBLIC_ASSET_SQL})) THEN 'pending_review' ELSE i.status END AS status,r.id AS revision_id,r.revision_no,r.payload->>\'title\' AS title,r.identity_mode FROM publications.items i JOIN publications.revisions r ON r.id=i.current_revision_id WHERE i.id=$1 AND i.author_id=$2 AND i.status<>\'purged\'`,[id,user])).rows[0];
    if(!r)throw missing();
    return {id:r.id,status:r.status,revisionId:r.revision_id,revision:r.revision_no,title:r.title,identityMode:r.identity_mode};
  }
  async myItems(user:string,cursor?:string){
    if(cursor&&!uuid(cursor))throw new ApiError(400,'invalid_cursor');
    const rows=(await this.pool.query(`SELECT i.id,CASE WHEN i.status='active' AND ((${SOURCE_REVIEW_REQUIRED_SQL}) OR (${BLOCKED_PUBLIC_ASSET_SQL})) THEN 'pending_review' ELSE i.status END AS status,r.id AS revision_id,r.revision_no,r.payload->>\'title\' AS title,r.identity_mode FROM publications.items i JOIN publications.revisions r ON r.id=i.current_revision_id WHERE i.author_id=$1 AND i.status<>\'purged\' AND ($2::uuid IS NULL OR i.id>$2) ORDER BY i.id LIMIT 21`,[user,cursor??null])).rows;
    return {items:rows.slice(0,20).map(r=>({id:r.id,status:r.status,revisionId:r.revision_id,revision:r.revision_no,title:r.title,identityMode:r.identity_mode})),nextCursor:rows.length>20?rows[19].id:null};
  }
  async updateProfile(user:string,operation:string,input:{handle:unknown;displayName:unknown;isPublic:unknown}){
    if(input.handle!==null&&(typeof input.handle!=='string'||/[^\x00-\x7f]/.test(input.handle)))throw new ApiError(422,'invalid_profile');
    const handle=typeof input.handle==='string'?input.handle.toLowerCase():null,name=input.displayName;
    if(handle!==null&&(!/^[a-z0-9_]{3,24}$/.test(handle)||/^(admin|api|auth|support|pittech|moderator|deleted|removed|fuck|shit|nazi)$/i.test(handle))||typeof name!=='string'||name.length>160||/[\x00-\x1f\x7f]/.test(name)||typeof input.isPublic!=='boolean'||input.isPublic&&(!handle||!name.trim()))throw new ApiError(422,'invalid_profile');
    const value={handle,displayName:name.trim(),isPublic:input.isPublic},hash=digest(stable({kind:'profile',...value}));
    return this.tx(async c=>{const prior=await this.operation(c,user,operation,hash);if(prior)return prior;
      await this.assertAccount(c,user,!value.isPublic);
      if(handle&&this.hashHandle){const priorProfile=(await c.query('SELECT handle FROM publications.profiles WHERE user_id=$1',[user])).rows[0];if(priorProfile?.handle!==handle&&(await c.query('SELECT 1 FROM publications.retired_handles WHERE hash=$1',[this.hashHandle(handle)])).rowCount)throw new ApiError(409,'handle_unavailable');}
      try{await c.query('INSERT INTO publications.profiles(user_id,handle,display_name,is_public,updated_at) VALUES($1,$2,$3,$4,$5) ON CONFLICT(user_id) DO UPDATE SET handle=$2,display_name=$3,is_public=$4,updated_at=$5',[user,handle,value.displayName,value.isPublic,this.clock()]);}
      catch(e){if((e as {code?:string}).code==='23505')throw new ApiError(409,'handle_unavailable');throw e;}
      if(this.reviewRequired){const review=randomUUID();await c.query('INSERT INTO moderation.profile_reviews(id,user_id,request_hash,handle,display_name,is_public,created_at) VALUES($1,$2,$3,$4,$5,$6,$7)',[review,user,hash,handle,value.displayName,value.isPublic,this.clock()]);await c.query('UPDATE publications.profiles SET review_id=$2 WHERE user_id=$1',[user,review]);}
      const result={...value,...(this.reviewRequired?{reviewStatus:'pending'}:{})};await this.record(c,user,operation,hash,result);return result;});
  }
  async sweep(){
    const lease=randomUUID(),now=this.clock();
    const objects=await this.pool.query<ObjectRow>(`WITH ready AS (SELECT id FROM publications.objects WHERE state IN ('uploading','deleting') AND lease_until<$1 FOR UPDATE SKIP LOCKED LIMIT 10)
      UPDATE publications.objects o SET state='deleting',lease_id=$2,lease_until=$1+60000 FROM ready r WHERE o.id=r.id RETURNING o.*`,[now,lease]);
    for(const object of objects.rows){try{await this.storage.delete(object.key);await this.pool.query('DELETE FROM publications.objects WHERE id=$1 AND state=\'deleting\' AND lease_id=$2',[object.id,lease]);}catch{/* Keep reservation until a later leased cleanup succeeds. */}}
    await this.pool.query('DELETE FROM publications.budgets WHERE expires_at<=$1',[now]);
  }
}
