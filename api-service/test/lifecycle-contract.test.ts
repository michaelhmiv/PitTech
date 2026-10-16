import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { credential,proofHash } from '../src/auth-credentials.ts';
import { blockAliases,deletion,recovery,report,DELETION_POLICY,requestHash } from '../src/lifecycle-contract.ts';

test('deletion requires an explicit policy-bound request and proof-only recovery contains no account identity',()=>{
  const operationId=randomUUID(),verifier=credential(),proofChallenge=proofHash(verifier);
  const request={operationId,proofChallenge,confirm:'delete-account',policyVersion:DELETION_POLICY};
  assert.deepEqual(deletion(request),{operationId,proofChallenge,policyVersion:DELETION_POLICY});assert.deepEqual(recovery({operationId,verifier}),{operationId,proofChallenge});
  for(const bad of [{...request,confirm:false},{...request,policyVersion:'old'},{...request,userId:randomUUID()},{...request,proofChallenge:verifier}])assert.throws(()=>deletion(bad));
  assert.throws(()=>recovery({operationId,verifier:'short'}));assert.equal(requestHash({a:1,b:2}),requestHash({b:2,a:1}));
});
test('report and local block contracts bound scope and cannot assign moderator roles',()=>{
  const operationId=randomUUID(),itemId=randomUUID();assert.deepEqual(report({operationId,itemId,kind:'publisher',reason:'privacy',details:'Please review'}),{operationId,itemId,assetId:null,kind:'publisher',reason:'privacy',details:'Please review'});
  assert.throws(()=>report({operationId,itemId,kind:'publisher',reason:'privacy',details:'x'.repeat(2001)}));assert.throws(()=>report({operationId,itemId,kind:'publisher',reason:'privacy',role:'owner'}));
  assert.deepEqual(blockAliases(undefined),[]);assert.deepEqual(blockAliases(itemId),[itemId]);assert.throws(()=>blockAliases(itemId+','+itemId));assert.throws(()=>blockAliases(Array.from({length:101},()=>randomUUID()).join(',')));assert.throws(()=>blockAliases('../../auth'));
});
