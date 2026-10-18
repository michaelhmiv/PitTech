import {randomUUID}from'node:crypto';import type{Pool,PoolClient}from'pg';import type{Storage}from'./storage.ts';import{lockAccount}from'./lifecycle-contract.ts';import type{PrivateAccountPurger}from'./account-deletion.ts';
/** Separate lifecycle pool only. No private authored payload needs to be decrypted for deletion. */
export class PrivateSyncPurger implements PrivateAccountPurger {
 private pool:Pool;private storage:Storage;private clock:()=>number;
 constructor(pool:Pool,storage:Storage,clock:()=>number=Date.now){this.pool=pool;this.storage=storage;this.clock=clock;}
 private async tx<T>(work:(client:PoolClient)=>Promise<T>){const c=await this.pool.connect();try{await c.query('BEGIN');const value=await work(c);await c.query('COMMIT');return value;}catch(e){await c.query('ROLLBACK');throw e;}finally{c.release();}}
 async purge(user:string):Promise<boolean>{
  // A future table requires a consciously updated purge adapter, not an assumed success.
  const tables=(await this.pool.query("SELECT tablename FROM pg_tables WHERE schemaname='private_sync' ORDER BY tablename")).rows.map(r=>r.tablename);
  if(JSON.stringify(tables)!==JSON.stringify(['accounts','budgets','objects','operations']))return false;
  await this.tx(async c=>{
   await lockAccount(c,user);const account=(await c.query('SELECT deleting FROM publications.publisher_controls WHERE user_id=$1',[user])).rows[0];if(!account?.deleting)throw Error('Private cleanup requires accepted deletion');
   await c.query('UPDATE private_sync.accounts SET head_id=NULL WHERE user_id=$1',[user]);
   await c.query('DELETE FROM private_sync.operations WHERE user_id=$1',[user]);
   await c.query("UPDATE private_sync.objects SET state='deleting' WHERE user_id=$1",[user]);
  });
  const lease=randomUUID(),now=this.clock();
  const rows=await this.tx(async c=>{await lockAccount(c,user);return (await c.query<{id:string;key:string}>(`WITH ready AS(SELECT id FROM private_sync.objects WHERE user_id=$1 AND state='deleting' AND lease_until<=$2 FOR UPDATE SKIP LOCKED LIMIT 5)
   UPDATE private_sync.objects o SET lease_id=$3,lease_until=$2+300000 FROM ready r WHERE o.id=r.id RETURNING o.id,o.key`,[user,now,lease])).rows;});
  for(const row of rows)try{await this.storage.delete(row.key);await this.pool.query("DELETE FROM private_sync.objects WHERE id=$1 AND user_id=$2 AND state='deleting' AND lease_id=$3",[row.id,user,lease]);}catch{/* Preserve ownership/quota/lease for retry; never claim deletion after a failed storage operation. */}
  if((await this.pool.query('SELECT 1 FROM private_sync.objects WHERE user_id=$1 LIMIT 1',[user])).rowCount)return false;
  return this.tx(async c=>{
   await lockAccount(c,user);if(!(await c.query('SELECT 1 FROM publications.publisher_controls WHERE user_id=$1 AND deleting',[user])).rowCount)return false;if((await c.query('SELECT 1 FROM private_sync.objects WHERE user_id=$1 LIMIT 1',[user])).rowCount)return false;
   await c.query('DELETE FROM private_sync.operations WHERE user_id=$1',[user]);await c.query('DELETE FROM private_sync.accounts WHERE user_id=$1',[user]);return true;
  });
 }
}
