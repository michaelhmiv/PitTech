import type { Storage } from './storage.ts';
import { readZip } from './zip.ts';
import type { PublicationPayload } from './publication-pack.ts';
import { SOURCE_REFERENCE_SQL,SOURCE_REVIEW_REQUIRED_SQL,BLOCKED_PUBLIC_ASSET_SQL } from './publication-policy.ts';
import { randomUUID } from 'node:crypto';
import type { Pool,PoolClient } from 'pg';
import { ApiError,digest } from './core.ts';
import { AuthSecrets,isCredential } from './auth-credentials.ts';
import { uuid,operation,report as reportContract,requestHash,lockAccount,fields,text } from './lifecycle-contract.ts';

export type Operator={id:string;role:'moderator'|'owner'};
export type PublicAudience={blockedPublishers:string[]};
export class CommunityLifecycle {
  private pool:Pool;private secrets:AuthSecrets;private clock:()=>number;private storage?:Storage;
  constructor(pool:Pool,secrets:AuthSecrets,clock:()=>number=Date.now,storage?:Storage){this.pool=pool;this.secrets=secrets;this.clock=clock;this.storage=storage;}
  async transaction<T>(body:(client:PoolClient)=>Promise<T>):Promise<T>{
    const client=await this.pool.connect();try{await client.query('BEGIN');const value=await body(client);await client.query('COMMIT');return value;}catch(error){await client.query('ROLLBACK');throw error;}finally{client.release();}
  }
  private async rate(client:PoolClient,key:string,limit:number){
    const day=Math.floor(this.clock()/86400000),bucket=key+':'+day;
    const result=await client.query('INSERT INTO moderation.budgets VALUES($1,1,$2) ON CONFLICT(key) DO UPDATE SET count=moderation.budgets.count+1 WHERE moderation.budgets.count<$3 RETURNING count',[bucket,(day+1)*86400000,limit]);
    if(!result.rowCount)throw new ApiError(429,'rate_limited');
  }
  private async account(client:PoolClient,user:string){
    await lockAccount(client,user);const row=(await client.query('SELECT deleting FROM publications.publisher_controls WHERE user_id=$1',[user])).rows[0];if(!row||row.deleting)throw new ApiError(401,'authentication_required');
  }
  private reporter(user:string|undefined,session:unknown){
    if(user!==undefined){if(!uuid(user))throw new ApiError(401,'authentication_required');return this.secrets.hash('reporter','user/'+user);}
    if(!isCredential(session))throw new ApiError(400,'report_session_required');return this.secrets.hash('reporter','anonymous/'+session);
  }
  async operator(token:unknown):Promise<Operator>{
    if(!isCredential(token))throw new ApiError(403,'operator_required');
    const row=(await this.pool.query<{id:string;role:Operator['role']}>('SELECT id,role FROM moderation.operators WHERE token_hash=$1 AND NOT disabled',[this.secrets.hash('operator',token)])).rows[0];
    if(!row)throw new ApiError(403,'operator_required');return row;
  }
  async report(user:string|undefined,session:unknown,input:unknown){
    const request=reportContract(input),reporter=this.reporter(user,session),hash=requestHash(request);
    return this.transaction(async client=>{
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,890013))',[reporter+':'+request.operationId]);
      const prior=(await client.query('SELECT id,request_hash FROM moderation.reports WHERE reporter_key=$1 AND operation_id=$2',[reporter,request.operationId])).rows[0];
      if(prior){if(prior.request_hash!==hash)throw new ApiError(409,'retry_content_changed');return {reportId:prior.id,status:'received'};}
      if(user)await this.account(client,user);
      const item=(await client.query('SELECT i.id,r.payload FROM publications.items i JOIN publications.revisions r ON r.id=i.current_revision_id LEFT JOIN publications.publisher_controls c ON c.user_id=i.author_id WHERE i.id=$1 AND i.status=\'active\' AND NOT COALESCE(c.deleting OR c.suspended,false)',[request.itemId])).rows[0];
      if(!item||request.assetId&&!item.payload.recipes.some((recipe:{assets:{id:string}[]})=>recipe.assets.some(asset=>asset.id===request.assetId)))throw new ApiError(404,'publication_unavailable');
      await this.rate(client,'reports-global',1000);await this.rate(client,'reports-'+reporter,10);
      const id=randomUUID(),now=this.clock();
      await client.query('INSERT INTO moderation.reports(id,operation_id,reporter_key,reporter_user_id,request_hash,item_id,asset_id,kind,reason,details,created_at,updated_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$11)',[id,request.operationId,reporter,user??null,hash,request.itemId,request.assetId,request.kind,request.reason,request.details,now]);
      return {reportId:id,status:'received'};
    });
  }
  async reportStatus(user:string|undefined,session:unknown,id:string){
    if(!uuid(id))throw new ApiError(404,'report_unavailable');const reporter=this.reporter(user,session);
    const row=(await this.pool.query('SELECT state FROM moderation.reports WHERE id=$1 AND reporter_key=$2',[id,reporter])).rows[0];if(!row)throw new ApiError(404,'report_unavailable');return {reportId:id,status:row.state};
  }
  async block(user:string,idempotency:unknown,alias:unknown,blocked:unknown){
    operation(idempotency);if(!uuid(user)||!uuid(alias)||typeof blocked!=='boolean')throw new ApiError(422,'invalid_block');
    const hash=requestHash({kind:'block',alias,blocked});
    return this.transaction(async client=>{
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,890013))',[user+':'+idempotency]);
      const prior=(await client.query('SELECT request_hash,response FROM moderation.operations WHERE principal_id=$1 AND operation_id=$2',[user,idempotency])).rows[0];
      if(prior){if(prior.request_hash!==hash)throw new ApiError(409,'retry_content_changed');return prior.response;}
      await this.account(client,user);
      const publisher=(await client.query('SELECT i.author_id FROM publications.publisher_aliases a JOIN publications.items i ON i.id=a.item_id WHERE a.alias=$1',[alias])).rows[0]?.author_id;
      if(!publisher)throw new ApiError(404,'publisher_unavailable');if(publisher===user)throw new ApiError(422,'invalid_block');
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,890013))',[user+':blocks']);await this.rate(client,'blocks-global',10000);await this.rate(client,'blocks-'+user,200);
      if(blocked){
        const priorBlock=(await client.query('SELECT 1 FROM publications.blocks WHERE user_id=$1 AND publisher_id=$2',[user,publisher])).rowCount;
        if(!priorBlock&&Number((await client.query('SELECT count(*) AS n FROM publications.blocks WHERE user_id=$1',[user])).rows[0].n)>=100)throw new ApiError(422,'block_limit');
        await client.query('INSERT INTO publications.blocks VALUES($1,$2,$3,$4) ON CONFLICT(user_id,publisher_id) DO NOTHING',[user,publisher,alias,this.clock()]);
      }else await client.query('DELETE FROM publications.blocks WHERE user_id=$1 AND publisher_id=$2',[user,publisher]);
      const response={alias,blocked};await client.query('INSERT INTO moderation.operations VALUES($1,$2,$3,$4)',[user,idempotency,hash,response]);return response;
    });
  }
  async blocks(user:string){
    if(!uuid(user))throw new ApiError(401,'authentication_required');
    const rows=(await this.pool.query('SELECT alias FROM publications.blocks WHERE user_id=$1 ORDER BY created_at,alias LIMIT 100',[user])).rows;return {aliases:rows.map(row=>row.alias)};
  }
  async audience(user:string|undefined,aliases:string[]):Promise<PublicAudience>{
    if(aliases.length>100||aliases.some(alias=>!uuid(alias))||user!==undefined&&!uuid(user))throw new ApiError(400,'invalid_blocks');
    const rows=(await this.pool.query('SELECT publisher_id AS id FROM publications.blocks WHERE user_id=$1 UNION SELECT i.author_id AS id FROM publications.publisher_aliases a JOIN publications.items i ON i.id=a.item_id WHERE a.alias=ANY($2::uuid[]) AND i.author_id IS NOT NULL',[user??null,aliases])).rows;
    return {blockedPublishers:rows.map(row=>row.id)};
  }
  async queue(token:unknown,kind:'content'|'reports'|'profiles',cursor?:string){
    await this.operator(token);if(cursor!==undefined&&!uuid(cursor))throw new ApiError(400,'invalid_cursor');
    if(kind==='content'){
      const rows=(await this.pool.query(`SELECT COALESCE(q.id,i.id) AS id,i.id AS "itemId",r.id AS "revisionId",COALESCE(q.reason,CASE WHEN ${BLOCKED_PUBLIC_ASSET_SQL} THEN 'known_asset' ELSE 'source_takedown' END) AS reason,r.payload->>'title' AS title,r.payload->>'kind' AS kind,r.identity_mode AS "identityMode"
        FROM publications.items i JOIN publications.revisions r ON r.id=i.current_revision_id
        LEFT JOIN moderation.reviews q ON q.revision_id=r.id AND q.state='pending'
        WHERE ((i.status='pending_review' AND q.id IS NOT NULL) OR (i.status='active' AND ((${SOURCE_REVIEW_REQUIRED_SQL}) OR (${BLOCKED_PUBLIC_ASSET_SQL}))))
        AND ($1::uuid IS NULL OR COALESCE(q.id,i.id)>$1) ORDER BY COALESCE(q.id,i.id) LIMIT 21`,[cursor??null])).rows;
      return {items:rows.slice(0,20),nextCursor:rows.length>20?rows[19].id:null};
    }
    if(kind==='profiles'){
      const rows=(await this.pool.query('SELECT q.id,q.handle,q.display_name AS "displayName",q.is_public AS "isPublic" FROM moderation.profile_reviews q JOIN publications.profiles p ON p.review_id=q.id WHERE q.state=\'pending\' AND ($1::uuid IS NULL OR q.id>$1) ORDER BY q.id LIMIT 21',[cursor??null])).rows;
      return {items:rows.slice(0,20),nextCursor:rows.length>20?rows[19].id:null};
    }
    const rows=(await this.pool.query('SELECT id,item_id AS "itemId",asset_id AS "assetId",kind,reason,details,state,created_at AS "createdAt" FROM moderation.reports WHERE state IN (\'open\',\'reviewing\') AND ($1::uuid IS NULL OR id>$1) ORDER BY id LIMIT 21',[cursor??null])).rows;
    return {items:rows.slice(0,20),nextCursor:rows.length>20?rows[19].id:null};
  }
  private async notice(client:PoolClient,user:string,kind:'publication_approved'|'publication_removed'|'account_suspended'){
    const account=(await client.query('SELECT email FROM auth.users WHERE id=$1',[user])).rows[0];if(!account)return;
    const id=randomUUID(),now=this.clock();await client.query('INSERT INTO moderation.notices(id,user_id,kind,envelope,state,next_attempt,created_at) VALUES($1,$2,$3,$4,\'pending\',$5,$5)',[id,user,kind,this.secrets.seal('notice/'+id,{id,email:account.email,kind}),now]);
  }
  async review(token:unknown,itemId:string){
    await this.operator(token);if(!uuid(itemId))throw new ApiError(404,'review_unavailable');
    const row=(await this.pool.query<{revision_id:string;payload:PublicationPayload;publisher_alias:string}>('SELECT r.id AS revision_id,r.payload,a.alias AS publisher_alias FROM publications.items i JOIN publications.revisions r ON r.id=i.current_revision_id JOIN publications.publisher_aliases a ON a.item_id=i.id WHERE i.id=$1 AND i.status<>\'purged\'',[itemId])).rows[0];if(!row)throw new ApiError(404,'review_unavailable');
    return {itemId,revisionId:row.revision_id,publisherAlias:row.publisher_alias,payload:row.payload,assets:row.payload.recipes.flatMap(recipe=>recipe.assets).map(asset=>({id:asset.id,url:'/v1/operator/items/'+itemId+'/assets/'+asset.id}))};
  }
  async reviewMedia(token:unknown,itemId:string,assetId:string){
    await this.operator(token);if(!uuid(itemId)||!uuid(assetId)||!this.storage)throw new ApiError(404,'review_unavailable');
    const row=(await this.pool.query<{revision_id:string;payload:PublicationPayload;key:string;sha256:string}>('SELECT r.id AS revision_id,r.payload,o.key,o.sha256 FROM publications.items i JOIN publications.revisions r ON r.id=i.current_revision_id JOIN publications.objects o ON o.id=r.object_id WHERE i.id=$1 AND i.status<>\'purged\' AND o.state=\'active\'',[itemId])).rows[0];
    const asset=row?.payload.recipes.flatMap(recipe=>recipe.assets).find(asset=>asset.id===assetId);if(!row||!asset)throw new ApiError(404,'review_unavailable');
    const archive=await this.storage.get(row.key);if(digest(archive)!==row.sha256)throw new ApiError(503,'media_unavailable');
    const bytes=(await readZip(archive,'publication')).get(asset.path);if(!bytes||digest(bytes)!==asset.sha256)throw new ApiError(503,'media_unavailable');
    await this.operator(token);const still=(await this.pool.query('SELECT 1 FROM publications.items WHERE id=$1 AND current_revision_id=$2 AND status<>\'purged\'',[itemId,row.revision_id])).rowCount;if(!still)throw new ApiError(404,'review_unavailable');
    return {bytes,sha256:asset.sha256,mimeType:asset.mimeType};
  }
  private ids(value:unknown){
    if(!Array.isArray(value)||value.length>600||value.some(id=>!uuid(id)))throw new ApiError(422,'invalid_review');
    const ids=value.map(id=>(id as string).toLowerCase());if(new Set(ids).size!==ids.length)throw new ApiError(422,'invalid_review');return ids.sort();
  }
  async decideContent(token:unknown,input:unknown){
    const operator=await this.operator(token),row=fields(input,'operationId itemId revisionId action note reviewedText reviewedAssetIds violatingAssetIds');operation(row.operationId);
    if(!uuid(row.itemId)||!uuid(row.revisionId)||!['approve','reject','takedown'].includes(String(row.action)))throw new ApiError(422,'invalid_review');
    const note=text(row.note??'',2000,row.action!=='approve'),seen=this.ids(row.reviewedAssetIds??[]),violating=this.ids(row.violatingAssetIds??[]),hash=requestHash({...row,note,reviewedAssetIds:seen,violatingAssetIds:violating});
    return this.transaction(async client=>{
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,890013))',[operator.id+':'+row.operationId]);
      const prior=(await client.query('SELECT request_hash,response FROM moderation.operations WHERE principal_id=$1 AND operation_id=$2',[operator.id,row.operationId])).rows[0];if(prior){if(prior.request_hash!==hash)throw new ApiError(409,'retry_content_changed');return prior.response;}
      const owner=(await client.query('SELECT author_id FROM publications.items WHERE id=$1',[row.itemId])).rows[0]?.author_id;if(!owner)throw new ApiError(404,'review_unavailable');
      await this.account(client,owner);
      const item=(await client.query<{status:string;revision_id:string;payload:PublicationPayload;flagged:boolean}>(`SELECT i.status,r.id AS revision_id,r.payload,((${SOURCE_REVIEW_REQUIRED_SQL}) OR (${BLOCKED_PUBLIC_ASSET_SQL})) AS flagged FROM publications.items i JOIN publications.revisions r ON r.id=i.current_revision_id WHERE i.id=$1 AND i.author_id=$2 FOR UPDATE OF i`,[row.itemId,owner])).rows[0];
      if(!item||item.revision_id!==row.revisionId)throw new ApiError(409,'review_changed');
      const assets=item.payload.recipes.flatMap(recipe=>recipe.assets),assetIds=assets.map(asset=>asset.id.toLowerCase()).sort();
      if(violating.some(id=>!assetIds.includes(id)))throw new ApiError(422,'invalid_review');
      if(row.action==='approve'){
        if(item.status!=='pending_review'&&!(item.status==='active'&&item.flagged))throw new ApiError(409,'review_changed');
        if(row.reviewedText!==true||JSON.stringify(seen)!==JSON.stringify(assetIds)||violating.length)throw new ApiError(422,'complete_review_required');
        if((await client.query('SELECT 1 FROM publications.blocked_assets WHERE sha256=ANY($1::text[]) LIMIT 1',[assets.map(asset=>asset.sha256)])).rowCount)throw new ApiError(403,'content_under_review');
        await client.query(`INSERT INTO publications.source_clearances(item_id,revision_id,incident_id)
          SELECT i.id,r.id,f.incident_id FROM publications.items i JOIN publications.revisions r ON r.id=i.current_revision_id CROSS JOIN publications.source_flags f
          WHERE i.id=$1 AND ${SOURCE_REFERENCE_SQL} ON CONFLICT DO NOTHING`,[row.itemId]);
      }
      await this.rate(client,'operator-'+operator.id,1000);
      const status=row.action==='approve'?'active':'taken_down',now=this.clock();
      await client.query('UPDATE publications.items SET status=$2,updated_at=$3 WHERE id=$1',[row.itemId,status,now]);
      await client.query('INSERT INTO moderation.reviews(item_id,revision_id,state,reason,note,created_at,reviewed_at) VALUES($1,$2,$3,$4,$5,$6,$6) ON CONFLICT(revision_id) DO UPDATE SET state=$3,note=$5,reviewed_at=$6',[row.itemId,row.revisionId,row.action==='approve'?'approved':'rejected',item.flagged?'source_takedown':'changed_content',note,now]);
      if(row.action!=='approve'){
        for(const id of violating){const asset=assets.find(asset=>asset.id.toLowerCase()===id)!;await client.query('INSERT INTO publications.blocked_assets VALUES($1,$2) ON CONFLICT DO NOTHING',[asset.sha256,now]);}
        await client.query('INSERT INTO publications.source_flags VALUES($1,$2,$3,$4) ON CONFLICT(source_id) DO UPDATE SET source_revision_id=$2,incident_id=$3,created_at=$4',[row.itemId,row.revisionId,randomUUID(),now]);
      }
      await client.query('INSERT INTO moderation.audit VALUES($1,$2,$3,$4,$5,$6,$7)',[randomUUID(),operator.id,row.action,row.itemId,row.revisionId,note,now]);
      await this.notice(client,owner,row.action==='approve'?'publication_approved':'publication_removed');
      const response={itemId:row.itemId,revisionId:row.revisionId,status};await client.query('INSERT INTO moderation.operations VALUES($1,$2,$3,$4)',[operator.id,row.operationId,hash,response]);return response;
    });
  }
  async decideProfile(token:unknown,input:unknown){
    const operator=await this.operator(token),row=fields(input,'operationId reviewId action note reviewedText');operation(row.operationId);
    if(!uuid(row.reviewId)||!['approve','reject'].includes(String(row.action))||row.reviewedText!==true)throw new ApiError(422,'complete_review_required');
    const note=text(row.note??'',2000,row.action==='reject'),hash=requestHash({...row,note});
    return this.transaction(async client=>{
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,890013))',[operator.id+':'+row.operationId]);
      const prior=(await client.query('SELECT request_hash,response FROM moderation.operations WHERE principal_id=$1 AND operation_id=$2',[operator.id,row.operationId])).rows[0];if(prior){if(prior.request_hash!==hash)throw new ApiError(409,'retry_content_changed');return prior.response;}
      const user=(await client.query('SELECT user_id FROM moderation.profile_reviews WHERE id=$1',[row.reviewId])).rows[0]?.user_id;if(!user)throw new ApiError(404,'review_unavailable');await this.account(client,user);
      const profile=(await client.query('SELECT p.handle,p.is_public FROM publications.profiles p JOIN moderation.profile_reviews q ON q.id=p.review_id WHERE q.id=$1 AND q.state=\'pending\' FOR UPDATE OF p',[row.reviewId])).rows[0];if(!profile)throw new ApiError(409,'review_changed');
      await this.rate(client,'operator-'+operator.id,1000);const state=row.action==='approve'?'approved':'rejected',now=this.clock();
      await client.query('UPDATE moderation.profile_reviews SET state=$2,note=$3 WHERE id=$1',[row.reviewId,state,note]);
      if(state==='approved'){
        await client.query('INSERT INTO moderation.approved_profiles VALUES($1,$2) ON CONFLICT(user_id) DO UPDATE SET review_id=$2',[user,row.reviewId]);
        if(profile.is_public&&profile.handle)await client.query('INSERT INTO publications.retired_handles VALUES($1) ON CONFLICT DO NOTHING',[this.secrets.hash('retired-handle',profile.handle)]);
      }
      await client.query('INSERT INTO moderation.audit VALUES($1,$2,$3,$4,NULL,$5,$6)',[randomUUID(),operator.id,row.action,row.reviewId,note,now]);
      const response={reviewId:row.reviewId,state};await client.query('INSERT INTO moderation.operations VALUES($1,$2,$3,$4)',[operator.id,row.operationId,hash,response]);return response;
    });
  }
  async setPublisher(token:unknown,input:unknown){
    const operator=await this.operator(token);if(operator.role!=='owner')throw new ApiError(403,'owner_operator_required');
    const row=fields(input,'operationId alias suspended note');operation(row.operationId);if(!uuid(row.alias)||typeof row.suspended!=='boolean')throw new ApiError(422,'invalid_review');
    const note=text(row.note,2000,true),hash=requestHash({...row,note});
    return this.transaction(async client=>{
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,890013))',[operator.id+':'+row.operationId]);
      const prior=(await client.query('SELECT request_hash,response FROM moderation.operations WHERE principal_id=$1 AND operation_id=$2',[operator.id,row.operationId])).rows[0];if(prior){if(prior.request_hash!==hash)throw new ApiError(409,'retry_content_changed');return prior.response;}
      const user=(await client.query('SELECT i.author_id FROM publications.publisher_aliases a JOIN publications.items i ON i.id=a.item_id WHERE a.alias=$1',[row.alias])).rows[0]?.author_id;if(!user)throw new ApiError(404,'publisher_unavailable');await this.account(client,user);
      await this.rate(client,'operator-'+operator.id,1000);
      await client.query('INSERT INTO auth.account_lifecycle(user_id,suspended,updated_at) VALUES($1,$2,$3) ON CONFLICT(user_id) DO UPDATE SET suspended=$2,updated_at=$3',[user,row.suspended,this.clock()]);
      await client.query('INSERT INTO moderation.audit VALUES($1,$2,$3,$4,NULL,$5,$6)',[randomUUID(),operator.id,row.suspended?'suspend':'restore',user,note,this.clock()]);
      if(row.suspended)await this.notice(client,user,'account_suspended');
      const response={alias:row.alias,suspended:row.suspended};await client.query('INSERT INTO moderation.operations VALUES($1,$2,$3,$4)',[operator.id,row.operationId,hash,response]);return response;
    });
  }
  async setReport(token:unknown,input:unknown){
    const operator=await this.operator(token),row=fields(input,'operationId reportId status note');operation(row.operationId);
    if(!uuid(row.reportId)||!['reviewing','resolved','dismissed'].includes(String(row.status)))throw new ApiError(422,'invalid_review');const note=text(row.note??'',2000),hash=requestHash({...row,note});
    return this.transaction(async client=>{
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,890013))',[operator.id+':'+row.operationId]);
      const prior=(await client.query('SELECT request_hash,response FROM moderation.operations WHERE principal_id=$1 AND operation_id=$2',[operator.id,row.operationId])).rows[0];if(prior){if(prior.request_hash!==hash)throw new ApiError(409,'retry_content_changed');return prior.response;}
      await this.rate(client,'operator-'+operator.id,1000);
      const result=await client.query('UPDATE moderation.reports SET state=$2,moderator_note=$3,updated_at=$4 WHERE id=$1 RETURNING id',[row.reportId,row.status,note,this.clock()]);if(!result.rowCount)throw new ApiError(404,'report_unavailable');
      await client.query('INSERT INTO moderation.audit VALUES($1,$2,\'report_status\',$3,NULL,$4,$5)',[randomUUID(),operator.id,row.reportId,note,this.clock()]);
      const response={reportId:row.reportId,status:row.status};await client.query('INSERT INTO moderation.operations VALUES($1,$2,$3,$4)',[operator.id,row.operationId,hash,response]);return response;
    });
  }

}
