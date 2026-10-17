import{test}from'node:test';import assert from'node:assert/strict';import{randomUUID,randomBytes}from'node:crypto';import{readFile,writeFile,mkdtemp,rm,stat}from'node:fs/promises';import{tmpdir}from'node:os';import{join}from'node:path';import{Pool}from'pg';
import{migrate}from'../src/migrate.ts';import{FileStorage}from'../src/storage.ts';import{SyncEncryption}from'../src/sync-encryption.ts';import{PrivateSyncPurger}from'../src/sync-purge.ts';import{AccountDeletion}from'../src/account-deletion.ts';import{AuthSecrets,credential,proofHash}from'../src/auth-credentials.ts';import{digest}from'../src/core.ts';import type{LifecycleMail}from'../src/lifecycle-email.ts';
const gate={skip:!process.env.TEST_DATABASE_URL&&process.env.REQUIRE_DB_TESTS!=='1'};
test('real Postgres private-sync isolation, deletion fence, encrypted purge recovery and independent owner survival',gate,async()=>{
 const source=process.env.TEST_DATABASE_URL;assert.ok(source);const url=new URL(source);assert.match(url.pathname,/^\/pittech_test[a-z_\d]*$/i);
 const control=new Pool({connectionString:source}),database='pittech_test_sync_'+randomUUID().replaceAll('-','');await control.query(`CREATE DATABASE ${database}`);url.pathname='/'+database;const connectionString=url.toString();
 const admin=new Pool({connectionString,max:5}),life=new Pool({connectionString,max:5,options:'-c role=pittech_lifecycle_runtime'}),sync=new Pool({connectionString,max:5,options:'-c role=pittech_private_sync_runtime'}),pub=new Pool({connectionString,max:5,options:'-c role=pittech_publications_runtime'});
 const root=await mkdtemp(join(tmpdir(),'pittech-sync-test-'));let now=Date.UTC(2026,9,16),unavailable=true;
 try{
  await migrate(admin);await migrate(admin);
  for(const role of ['lifecycle','private-sync','publications']){const sql=await readFile(new URL(`../operations/${role}-runtime-role.sql`,import.meta.url),'utf8');await admin.query(sql);await admin.query(sql);}
  for(const sql of ['SELECT * FROM auth.users','SELECT * FROM relay.packs','SELECT * FROM publications.items','UPDATE auth.account_lifecycle SET deleting=false','CREATE TABLE private_sync.forbidden(id uuid)','TRUNCATE private_sync.objects','UPDATE private_sync.objects SET user_id=gen_random_uuid()'])await assert.rejects(sync.query(sql),{code:'42501'});
  for(const sql of ['SELECT * FROM private_sync.accounts','SELECT * FROM private_sync.objects','SELECT * FROM private_sync.operations'])await assert.rejects(pub.query(sql),{code:'42501'});
  await assert.rejects(life.query('UPDATE private_sync.accounts SET version=0'),{code:'42501'});await assert.rejects(life.query('INSERT INTO private_sync.accounts VALUES(gen_random_uuid(),0,NULL,0)'),{code:'42501'});
  const alice=randomUUID(),bob=randomUUID();await admin.query('INSERT INTO auth.users VALUES($1,$2,$5),($3,$4,$5)',[alice,'synthetic-private-alice@example.com',bob,'synthetic-private-bob@example.com',now]);
  const crypto=new SyncEncryption('synthetic',{synthetic:randomBytes(32).toString('base64url')}),storage=new FileStorage(root),bytes=Buffer.from('Synthetic private journal content must never enter public reads or email');
  const objects=[];
  for(const user of [alice,bob]){
   const id=randomUUID(),key=`private-sync/${id}.pittech`,context={userId:user,objectId:id,kind:'snapshot' as const,sha256:digest(bytes)},encrypted=crypto.seal(context,bytes),file=join(root,id);await writeFile(file,encrypted);await storage.put(key,file);
   await sync.query('INSERT INTO private_sync.accounts(user_id,version,updated_at) VALUES($1,1,$2)',[user,now]);
   await sync.query("INSERT INTO private_sync.objects VALUES($1,$2,$3,'snapshot',$4,$5,$5+77,$6,'active',$7,0,$8)",[id,user,key,digest(bytes),bytes.length,digest(encrypted),randomUUID(),now]);await sync.query('UPDATE private_sync.accounts SET head_id=$2 WHERE user_id=$1',[user,id]);
   await sync.query("INSERT INTO private_sync.operations VALUES($1,$2,$3,$4,0,'committed',$5,$6)",[user,randomUUID(),digest(bytes),id,{version:1},now]);objects.push({user,id,key,context,file});
  }
  const wrapped={put:storage.put.bind(storage),get:storage.get.bind(storage),delete:async(key:string)=>{if(unavailable)throw Error('Synthetic storage outage');await storage.delete(key);}};
  const purger=new PrivateSyncPurger(life,wrapped,()=>now);await assert.rejects(purger.purge(alice));
  const secrets=new AuthSecrets(credential(),credential()),notices:LifecycleMail[]=[];
  const deletion=new AccountDeletion(life,secrets,wrapped,{send:async mail=>{notices.push(mail);}},email=>secrets.hash('email',email),()=>now,purger);
  const receipt=await deletion.request({id:alice,email:'ignored@example.com'},{operationId:randomUUID(),proofChallenge:proofHash(credential()),confirm:'delete-account',policyVersion:'account-deletion-v1'});
  await assert.rejects(sync.query('INSERT INTO private_sync.accounts(user_id,updated_at) VALUES($1,$2) ON CONFLICT(user_id) DO UPDATE SET updated_at=$2',[alice,now]),{code:'23514'});
  await admin.query('CREATE TABLE private_sync.future_table(id uuid)');assert.equal(await purger.purge(alice),false);await admin.query('DROP TABLE private_sync.future_table');
  await deletion.sweepDeletion();assert.equal((await admin.query('SELECT 1 FROM auth.users WHERE id=$1',[alice])).rowCount,1);assert.equal((await admin.query('SELECT 1 FROM private_sync.objects WHERE user_id=$1',[alice])).rowCount,1);
  unavailable=false;now+=300001;await deletion.sweepDeletion();
  assert.equal((await admin.query('SELECT 1 FROM auth.users WHERE id=$1',[alice])).rowCount,0);for(const table of ['accounts','objects','operations'])assert.equal((await admin.query(`SELECT 1 FROM private_sync.${table} WHERE user_id=$1`,[alice])).rowCount,0);
  const a=objects[0],b=objects[1];assert.equal((await stat(storage.path(a.key))).size,0);await assert.rejects(storage.put(a.key,a.file));assert.deepEqual(crypto.open(b.context,await storage.get(b.key)),bytes);assert.equal((await sync.query('SELECT version FROM private_sync.accounts WHERE user_id=$1',[bob])).rows[0].version,'1');
  await deletion.deliverNotices();now+=60001;await deletion.sweepDeletion();assert.equal((await deletion.status({deletionId:receipt.deletionId,statusToken:receipt.statusToken})).state,'complete');assert.equal(notices.length,1);assert.ok(!JSON.stringify(notices).includes(bytes.toString()));
 }finally{await Promise.all([admin.end(),life.end(),sync.end(),pub.end()]);await control.query(`DROP DATABASE ${database}`);await control.end();await rm(root,{recursive:true,force:true});}
});
