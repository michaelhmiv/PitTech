import { randomUUID } from 'node:crypto';
import type { Pool,PoolClient } from 'pg';
import { ApiError,digest } from './core.ts';
import { AuthSecrets,credential,equalHash,isCredential } from './auth-credentials.ts';
import { deletion,recovery,fields,uuid,requestHash,lockAccount,type DeletionReceipt } from './lifecycle-contract.ts';
import { LifecycleMailFailure,type LifecycleMailer,type LifecycleMail } from './lifecycle-email.ts';
import type { Storage } from './storage.ts';

type Job={id:string;operation_id:string;user_id:string|null;proof_hash:string;request_hash:string;status_hash:string;receipt_envelope:string;state:DeletionReceipt['state'];lease_id:string|null};
export interface PrivateAccountPurger{purge(user:string):Promise<boolean>}
export class AccountDeletion {
  private pool:Pool;private secrets:AuthSecrets;private storage:Storage;private mailer:LifecycleMailer;private clock:()=>number;private privatePurger?:PrivateAccountPurger;private hashEmail:(email:string)=>string;
  constructor(pool:Pool,secrets:AuthSecrets,storage:Storage,mailer:LifecycleMailer,hashEmail:(email:string)=>string,clock:()=>number=Date.now,privatePurger?:PrivateAccountPurger){this.hashEmail=hashEmail;this.pool=pool;this.secrets=secrets;this.storage=storage;this.mailer=mailer;this.clock=clock;this.privatePurger=privatePurger;}
  private async tx<T>(body:(client:PoolClient)=>Promise<T>):Promise<T>{const client=await this.pool.connect();try{await client.query('BEGIN');const value=await body(client);await client.query('COMMIT');return value;}catch(error){await client.query('ROLLBACK');throw error;}finally{client.release();}}
  private receipt(job:Job):DeletionReceipt{
    const value=this.secrets.open('deletion-receipt/'+job.id,job.receipt_envelope) as DeletionReceipt;
    if(value.deletionId!==job.id||!isCredential(value.statusToken))throw new Error('Deletion receipt unavailable');return {...value,state:job.state};
  }
  async request(user:{id:string;email:string},input:unknown){
    const request=deletion(input);if(!uuid(user.id))throw new ApiError(401,'authentication_required');const hash=requestHash({user:user.id,...request});
    return this.tx(async client=>{
      const prior=(await client.query<Job>('SELECT * FROM moderation.deletions WHERE operation_id=$1',[request.operationId])).rows[0];
      if(prior){if(prior.request_hash!==hash||!equalHash(prior.proof_hash,request.proofChallenge))throw new ApiError(409,'retry_content_changed');return this.receipt(prior);}
      const hint=(await client.query('SELECT email FROM auth.users WHERE id=$1',[user.id])).rows[0];if(!hint)throw new ApiError(401,'authentication_required');
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,890008))',[this.hashEmail(hint.email)]);
      await lockAccount(client,user.id);
      const account=(await client.query('SELECT id,email FROM auth.users WHERE id=$1',[user.id])).rows[0];if(!account)throw new ApiError(401,'authentication_required');
      const applied=(await client.query<Job>('SELECT * FROM moderation.deletions WHERE operation_id=$1',[request.operationId])).rows[0];
      if(applied){if(applied.request_hash!==hash||!equalHash(applied.proof_hash,request.proofChallenge))throw new ApiError(409,'retry_content_changed');return this.receipt(applied);}
      if((await client.query('SELECT 1 FROM moderation.deletions WHERE user_id=$1',[user.id])).rowCount)throw new ApiError(409,'deletion_already_requested');
      const id=randomUUID(),statusToken=credential(),now=this.clock(),receipt:DeletionReceipt={deletionId:id,statusToken,state:'queued'};
      await client.query('INSERT INTO moderation.deletions(id,operation_id,user_id,proof_hash,request_hash,status_hash,receipt_envelope,state,created_at) VALUES($1,$2,$3,$4,$5,$6,$7,\'queued\',$8)',[id,request.operationId,user.id,request.proofChallenge,hash,this.secrets.hash('deletion-status/'+id,statusToken),this.secrets.seal('deletion-receipt/'+id,receipt),now]);
      await client.query('INSERT INTO auth.account_lifecycle(user_id,deleting,updated_at) VALUES($1,true,$2) ON CONFLICT(user_id) DO UPDATE SET deleting=true,updated_at=$2',[user.id,now]);
      await client.query('DELETE FROM auth.sessions WHERE user_id=$1',[user.id]);await client.query('DELETE FROM auth.challenges WHERE email=$1',[account.email]);
      await client.query('DELETE FROM auth.provider_challenges WHERE user_id=$1',[user.id]);await client.query('DELETE FROM auth.mcp_exports WHERE user_id=$1',[user.id]);await client.query('DELETE FROM auth.mcp_import_previews WHERE user_id=$1',[user.id]);await client.query('DELETE FROM auth.mcp_operation_journal WHERE user_id=$1',[user.id]);await client.query('DELETE FROM auth.mcp_grants WHERE user_id=$1',[user.id]);
      await client.query('UPDATE publications.profiles SET is_public=false WHERE user_id=$1',[user.id]);
      await client.query('UPDATE publications.items SET status=\'purged\',current_revision_id=NULL WHERE author_id=$1',[user.id]);
      // Preserve live upload leases to reduce contention. Storage cleanup replaces
      // bytes with a create-only fence; lease expiry alone is not a write fence.
      await client.query('UPDATE publications.objects SET state=\'deleting\',lease_until=GREATEST(lease_until,$2) WHERE owner_id=$1',[user.id,now+60000]);
      return receipt;
    });
  }
  /** Provider-initiated deletion (Sign in with Apple account-delete /
   * consent-revoked). Reproduces request()'s account-side effects under a
   * server-derived operation identifier; no proof or receipt capability is
   * issued, and the existing sweep consumes the job. Idempotent. */
  async requestForAppleEvent(input:{operationId:string;userId:string}):Promise<{deletionId:string;state:string}>{
    if(!uuid(input.operationId)||!uuid(input.userId))throw new ApiError(503,'temporarily_unavailable');
    return this.tx(async client=>{
      const prior=(await client.query<Job>('SELECT * FROM moderation.deletions WHERE operation_id=$1',[input.operationId])).rows[0];
      if(prior)return {deletionId:prior.id,state:prior.state};
      const hint=(await client.query('SELECT id,email FROM auth.users WHERE id=$1',[input.userId])).rows[0] as {id:string;email:string}|undefined;
      if(!hint)return {deletionId:'',state:'unknown-account'};
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,890008))',[this.hashEmail(hint.email)]);
      await lockAccount(client,input.userId);
      const applied=(await client.query<Job>('SELECT * FROM moderation.deletions WHERE operation_id=$1',[input.operationId])).rows[0];
      if(applied)return {deletionId:applied.id,state:applied.state};
      const userJob=(await client.query<Job>('SELECT * FROM moderation.deletions WHERE user_id=$1',[input.userId])).rows[0];
      if(userJob)return {deletionId:userJob.id,state:userJob.state};
      const account=(await client.query('SELECT id,email FROM auth.users WHERE id=$1',[input.userId])).rows[0] as {id:string;email:string}|undefined;
      if(!account)return {deletionId:'',state:'unknown-account'};
      const id=randomUUID(),now=this.clock(),statusToken=credential();
      // Placeholder proof material satisfies the job's integrity checks but is
      // discarded unissued: no device can recover or poll this provider job.
      await client.query('INSERT INTO moderation.deletions(id,operation_id,user_id,proof_hash,request_hash,status_hash,receipt_envelope,state,created_at) VALUES($1,$2,$3,$4,$5,$6,$7,\'queued\',$8)',[id,input.operationId,input.userId,digest('apple-event-proof/'+input.operationId),digest('apple-event-request/'+input.operationId),this.secrets.hash('deletion-status/'+id,statusToken),this.secrets.seal('deletion-receipt/'+id,{deletionId:id,statusToken,state:'queued'}),now]);
      await client.query('INSERT INTO auth.account_lifecycle(user_id,deleting,updated_at) VALUES($1,true,$2) ON CONFLICT(user_id) DO UPDATE SET deleting=true,updated_at=$2',[input.userId,now]);
      await client.query('DELETE FROM auth.sessions WHERE user_id=$1',[input.userId]);await client.query('DELETE FROM auth.challenges WHERE email=$1',[account.email]);
      await client.query('DELETE FROM auth.provider_challenges WHERE user_id=$1',[input.userId]);await client.query('DELETE FROM auth.mcp_exports WHERE user_id=$1',[input.userId]);await client.query('DELETE FROM auth.mcp_import_previews WHERE user_id=$1',[input.userId]);await client.query('DELETE FROM auth.mcp_operation_journal WHERE user_id=$1',[input.userId]);await client.query('DELETE FROM auth.mcp_grants WHERE user_id=$1',[input.userId]);
      await client.query('UPDATE publications.profiles SET is_public=false WHERE user_id=$1',[input.userId]);
      await client.query('UPDATE publications.items SET status=\'purged\',current_revision_id=NULL WHERE author_id=$1',[input.userId]);
      await client.query('UPDATE publications.objects SET state=\'deleting\',lease_until=GREATEST(lease_until,$2) WHERE owner_id=$1',[input.userId,now+60000]);
      return {deletionId:id,state:'queued'};
    });
  }
  async recover(input:unknown){
    const request=recovery(input),job=(await this.pool.query<Job>('SELECT * FROM moderation.deletions WHERE operation_id=$1',[request.operationId])).rows[0];
    if(!job||!equalHash(job.proof_hash,request.proofChallenge))throw new ApiError(404,'deletion_unavailable');return this.receipt(job);
  }
  async status(input:unknown){
    const row=fields(input,'deletionId statusToken');if(!uuid(row.deletionId)||!isCredential(row.statusToken))throw new ApiError(404,'deletion_unavailable');
    const job=(await this.pool.query<Job>('SELECT * FROM moderation.deletions WHERE id=$1 AND status_hash=$2',[row.deletionId,this.secrets.hash('deletion-status/'+row.deletionId,row.statusToken)])).rows[0];
    if(!job)throw new ApiError(404,'deletion_unavailable');return {deletionId:job.id,state:job.state};
  }
  private async purgeOriginalRows(user:string){
    await this.tx(async client=>{
      await lockAccount(client,user);
      await client.query('UPDATE publications.items SET status=\'purged\',current_revision_id=NULL WHERE author_id=$1',[user]);
      const reportIds=(await client.query('SELECT id FROM moderation.reports WHERE reporter_user_id=$1 OR item_id IN (SELECT id FROM publications.items WHERE author_id=$1) FOR UPDATE',[user])).rows.map(row=>row.id);
      await client.query('UPDATE moderation.audit SET note=\'\' WHERE target_id=ANY($1::uuid[])',[reportIds]);
      await client.query('DELETE FROM moderation.reports WHERE reporter_user_id=$1 OR item_id IN (SELECT id FROM publications.items WHERE author_id=$1)',[user]);
      await client.query('UPDATE moderation.audit SET note=\'\' WHERE target_id=$1 OR target_id IN (SELECT id FROM publications.items WHERE author_id=$1) OR target_id IN (SELECT id FROM moderation.profile_reviews WHERE user_id=$1)',[user]);
      await client.query('DELETE FROM publications.revisions WHERE item_id IN (SELECT id FROM publications.items WHERE author_id=$1)',[user]);
      await client.query('DELETE FROM publications.likes WHERE user_id=$1 OR item_id IN (SELECT id FROM publications.items WHERE author_id=$1)',[user]);
      await client.query('DELETE FROM publications.operations WHERE user_id=$1',[user]);await client.query('DELETE FROM moderation.operations WHERE principal_id=$1',[user]);
      await client.query('DELETE FROM publications.blocks WHERE user_id=$1 OR publisher_id=$1',[user]);
    });
  }
  private async privateComplete(user:string){
    const exists=(await this.pool.query("SELECT to_regnamespace('private_sync') IS NOT NULL AS present")).rows[0].present;
    if(exists&&!this.privatePurger)return false;return this.privatePurger?this.privatePurger.purge(user):true;
  }
  private async deleteObjects(user:string){
    const now=this.clock(),lease=randomUUID();
    const rows=await this.pool.query<{id:string;key:string}>(`WITH ready AS (
      SELECT id FROM publications.objects WHERE owner_id=$1 AND state='deleting' AND lease_until<=$2 FOR UPDATE SKIP LOCKED LIMIT 5)
      UPDATE publications.objects o SET lease_id=$3,lease_until=$2+300000 FROM ready r WHERE o.id=r.id RETURNING o.id,o.key`,[user,now,lease]);
    for(const row of rows.rows){try{await this.storage.delete(row.key);await this.pool.query('DELETE FROM publications.objects WHERE id=$1 AND owner_id=$2 AND state=\'deleting\' AND lease_id=$3',[row.id,user,lease]);}catch{/* Retain the owned object and lease for durable retry. */}}
    return !(await this.pool.query('SELECT 1 FROM publications.objects WHERE owner_id=$1 LIMIT 1',[user])).rowCount;
  }
  private async finishIdentity(job:Job){
    const user=job.user_id!;
    return this.tx(async client=>{
      const owned=(await client.query('SELECT lease_id,state FROM moderation.deletions WHERE id=$1 FOR UPDATE',[job.id])).rows[0];
      if(!owned||owned.lease_id!==job.lease_id||owned.state!=='purging')return false;
      const hint=(await client.query('SELECT email FROM auth.users WHERE id=$1',[user])).rows[0];
      if(!hint)throw new Error('Deletion ownership unavailable');
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,890008))',[this.hashEmail(hint.email)]);
      await lockAccount(client,user);
      if((await client.query('SELECT 1 FROM publications.objects WHERE owner_id=$1 LIMIT 1',[user])).rowCount)return false;
      if((await client.query('SELECT 1 FROM moderation.notices WHERE user_id=$1 AND envelope IS NOT NULL AND lease_until>$2 LIMIT 1',[user,this.clock()])).rowCount)return false;
      const account=(await client.query('SELECT email FROM auth.users WHERE id=$1',[user])).rows[0];
      if(!account)throw new Error('Deletion ownership unavailable');
      await client.query('DELETE FROM moderation.notices WHERE user_id=$1',[user]);
      const noticeId=randomUUID(),mail:LifecycleMail={id:noticeId,email:account.email,kind:'deletion_complete'},now=this.clock();
      await client.query('INSERT INTO moderation.notices(id,deletion_id,kind,envelope,state,next_attempt,created_at) VALUES($1,$2,\'deletion_complete\',$3,\'pending\',$4,$4)',[noticeId,job.id,this.secrets.seal('notice/'+noticeId,mail),now]);
      await client.query('DELETE FROM publications.profiles WHERE user_id=$1',[user]);
      await client.query('DELETE FROM auth.challenges WHERE email=$1',[account.email]);
      await client.query('DELETE FROM auth.provider_challenges WHERE user_id=$1',[user]);await client.query('DELETE FROM auth.mcp_exports WHERE user_id=$1',[user]);await client.query('DELETE FROM auth.mcp_import_previews WHERE user_id=$1',[user]);await client.query('DELETE FROM auth.mcp_operation_journal WHERE user_id=$1',[user]);await client.query('DELETE FROM auth.mcp_grants WHERE user_id=$1',[user]);
      // Remove all identifying ownership and original lineage. Keep a stub only
      // when an independently owned revision or parent relation refers to its ID.
      const ids=(await client.query('SELECT id FROM publications.items WHERE author_id=$1',[user])).rows.map(row=>row.id);
      await client.query('UPDATE publications.items SET author_id=NULL,parent_id=NULL,created_at=0,updated_at=0 WHERE author_id=$1',[user]);
      for(const id of ids){
        const referred=(await client.query(`SELECT 1 FROM publications.items WHERE parent_id=$1 UNION ALL
          SELECT 1 FROM publications.revisions r WHERE r.payload->'source'->>'itemId'=$1::text OR EXISTS(
            SELECT 1 FROM jsonb_array_elements(r.payload->'recipes') p WHERE p->'source'->>'itemId'=$1::text) LIMIT 1`,[id])).rowCount;
        if(!referred)await client.query('DELETE FROM publications.items WHERE id=$1',[id]);
      }
      await client.query('DELETE FROM auth.users WHERE id=$1',[user]);
      await client.query('UPDATE moderation.deletions SET state=\'notifying\',lease_until=0 WHERE id=$1 AND lease_id=$2',[job.id,job.lease_id]);return true;
    });
  }
  async sweepDeletion(){
    const lease=randomUUID(),now=this.clock();
    const job=(await this.pool.query<Job>(`WITH ready AS(SELECT id FROM moderation.deletions WHERE state<>'complete' AND lease_until<=$1 ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
      UPDATE moderation.deletions d SET lease_id=$2,lease_until=$1+600000,attempts=d.attempts+1 FROM ready r WHERE d.id=r.id RETURNING d.*`,[now,lease])).rows[0];
    if(!job)return;
    try{
      if(job.state==='notifying'){
        await this.pool.query('UPDATE moderation.notices SET state=\'failed\',envelope=NULL WHERE deletion_id=$1 AND created_at<$2 AND envelope IS NOT NULL',[job.id,now-20*3600000]);
        if(!(await this.pool.query('SELECT 1 FROM moderation.notices WHERE deletion_id=$1 AND envelope IS NOT NULL LIMIT 1',[job.id])).rowCount)await this.pool.query('UPDATE moderation.deletions SET state=\'complete\',completed_at=$2 WHERE id=$1 AND lease_id=$3',[job.id,now,lease]);
        return;
      }
      if(!job.user_id)throw new Error('Deletion ownership unavailable');
      await this.pool.query('UPDATE moderation.deletions SET state=\'purging\' WHERE id=$1 AND lease_id=$2',[job.id,lease]);
      await this.purgeOriginalRows(job.user_id);
      if(!await this.privateComplete(job.user_id))return;
      if(await this.deleteObjects(job.user_id))await this.finishIdentity(job);
    }finally{await this.pool.query('UPDATE moderation.deletions SET lease_until=$3 WHERE id=$1 AND lease_id=$2',[job.id,lease,this.clock()+60000]);}
  }
  async deliverNotices(){
    const now=this.clock(),lease=randomUUID();
    const rows=(await this.pool.query<{id:string;envelope:string;attempts:number;created_at:string}>(`WITH ready AS(SELECT id FROM moderation.notices WHERE state='pending' AND envelope IS NOT NULL AND next_attempt<=$1 AND lease_until<=$1 AND (user_id IS NULL OR NOT EXISTS(SELECT 1 FROM auth.account_lifecycle a WHERE a.user_id=moderation.notices.user_id AND a.deleting)) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 5)
      UPDATE moderation.notices n SET lease_id=$2,lease_until=$1+60000,attempts=n.attempts+1 FROM ready r WHERE n.id=r.id RETURNING n.id,n.envelope,n.attempts,n.created_at`,[now,lease])).rows;
    for(const row of rows){
      try{
        if(row.attempts>8||now-Number(row.created_at)>=20*3600000)throw new LifecycleMailFailure(true);
        const mail=this.secrets.open('notice/'+row.id,row.envelope) as LifecycleMail;await this.mailer.send(mail);
        await this.pool.query('UPDATE moderation.notices SET state=\'sent\',envelope=NULL WHERE id=$1 AND lease_id=$2',[row.id,lease]);
      }catch(error){
        const permanent=error instanceof LifecycleMailFailure&&error.permanent||row.attempts>=8;
        if(permanent)await this.pool.query('UPDATE moderation.notices SET state=\'failed\',envelope=NULL WHERE id=$1 AND lease_id=$2',[row.id,lease]);
        else await this.pool.query('UPDATE moderation.notices SET next_attempt=$3 WHERE id=$1 AND lease_id=$2',[row.id,lease,now+Math.max(1000*2**row.attempts,error instanceof LifecycleMailFailure?error.retryAfterMillis:0)]);
      }
    }
  }
}
