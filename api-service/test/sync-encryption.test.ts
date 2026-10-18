import{test}from'node:test';import assert from'node:assert/strict';import{randomBytes,randomUUID}from'node:crypto';import{SyncEncryption}from'../src/sync-encryption.ts';import{digest}from'../src/core.ts';
test('private object encryption authenticates owner, identity, kind, hash and bytes without changing local originals',()=>{
 const key=randomBytes(32).toString('base64url'),crypto=new SyncEncryption('first',{first:key}),bytes=Buffer.from('Synthetic private cook notes and original photo metadata'),original=Buffer.from(bytes),context={userId:randomUUID(),objectId:randomUUID(),kind:'snapshot' as const,sha256:digest(bytes)};
 const encrypted=crypto.seal(context,bytes);assert.deepEqual(bytes,original);assert.equal(encrypted.includes(bytes),false);assert.deepEqual(crypto.open(context,encrypted),bytes);assert.notDeepEqual(crypto.seal(context,bytes),encrypted);
 for(const changed of [{...context,userId:randomUUID()},{...context,objectId:randomUUID()},{...context,kind:'photo' as const},{...context,sha256:digest('different') }])assert.throws(()=>crypto.open(changed,encrypted));
 for(const index of [0,8,9,encrypted.length-1]){const changed=Buffer.from(encrypted);changed[index]^=1;assert.throws(()=>crypto.open(context,changed));}
 assert.throws(()=>crypto.seal({...context,sha256:digest('wrong')},bytes));assert.throws(()=>crypto.open(context,Buffer.alloc(0)));
});
test('rotation reads retained old keys, writes with the current key and refuses a removed key',()=>{
 const first=randomBytes(32).toString('base64url'),second=randomBytes(32).toString('base64url'),bytes=Buffer.from('Synthetic private snapshot'),context={userId:randomUUID(),objectId:randomUUID(),kind:'snapshot' as const,sha256:digest(bytes)};
 const old=new SyncEncryption('first',{first}),encrypted=old.seal(context,bytes),rotated=new SyncEncryption('second',{first,second});assert.deepEqual(rotated.open(context,encrypted),bytes);const fresh=rotated.seal(context,bytes);assert.deepEqual(new SyncEncryption('second',{second}).open(context,fresh),bytes);assert.throws(()=>old.open(context,fresh));assert.throws(()=>new SyncEncryption('second',{second}).open(context,encrypted));
 assert.throws(()=>new SyncEncryption('missing',{first}));assert.throws(()=>new SyncEncryption('first',{first,second:first}));assert.throws(()=>new SyncEncryption('first',{first:'not-a-key'}));
});
