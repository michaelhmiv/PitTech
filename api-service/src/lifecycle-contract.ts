import type { PoolClient } from 'pg';
import { ApiError,digest } from './core.ts';
import { isCredential,proofHash } from './auth-credentials.ts';
import { stable } from './pack.ts';

export const DELETION_POLICY='account-deletion-v1';
export const uuid=(value:unknown):value is string=>typeof value==='string'&&/^[a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(value);
export function fields(value:unknown,allowed:string):Record<string,unknown>{
  if(!value||typeof value!=='object'||Array.isArray(value))throw new ApiError(422,'invalid_lifecycle_request');
  const row=value as Record<string,unknown>,keys=new Set(allowed.split(' '));if(Object.keys(row).some(key=>!keys.has(key)))throw new ApiError(422,'invalid_lifecycle_request');return row;
}
export function text(value:unknown,max:number,required=false):string{
  if(typeof value!=='string'||value.length>max||/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(value)||required&&!value.trim())throw new ApiError(422,'invalid_lifecycle_request');return value.trim();
}
export function operation(value:unknown):string{if(!uuid(value))throw new ApiError(400,'operation_required');return value;}
export function blockAliases(value:unknown):string[]{
  if(value===undefined)return [];if(typeof value!=='string'||value.length>3700)throw new ApiError(400,'invalid_blocks');
  const rows=value===''?[]:value.split(',').map(row=>row.toLowerCase());if(rows.length>100||rows.some(row=>!uuid(row))||new Set(rows).size!==rows.length)throw new ApiError(400,'invalid_blocks');return rows;
}
export type Report={operationId:string;itemId:string;assetId:string|null;kind:'content'|'publisher';reason:string;details:string};
export function report(input:unknown):Report{
  const row=fields(input,'operationId itemId assetId kind reason details');operation(row.operationId);
  if(!uuid(row.itemId)||row.assetId!==null&&row.assetId!==undefined&&!uuid(row.assetId)||!['content','publisher'].includes(String(row.kind))||!['spam','harassment','unsafe','privacy','copyright','other'].includes(String(row.reason)))throw new ApiError(422,'invalid_report');
  if(row.kind==='publisher'&&row.assetId!=null)throw new ApiError(422,'invalid_report');
  return {operationId:row.operationId as string,itemId:row.itemId,assetId:row.assetId as string??null,kind:row.kind as Report['kind'],reason:row.reason as string,details:text(row.details??'',2000)};
}
export function deletion(input:unknown){
  const row=fields(input,'operationId proofChallenge confirm policyVersion');operation(row.operationId);
  if(typeof row.proofChallenge!=='string'||!/^[a-f0-9]{64}$/.test(row.proofChallenge)||row.confirm!=='delete-account'||row.policyVersion!==DELETION_POLICY)throw new ApiError(422,'explicit_deletion_required');
  return {operationId:row.operationId as string,proofChallenge:row.proofChallenge,policyVersion:DELETION_POLICY};
}
export function recovery(input:unknown){
  const row=fields(input,'operationId verifier');operation(row.operationId);if(!isCredential(row.verifier))throw new ApiError(404,'deletion_unavailable');
  return {operationId:row.operationId as string,proofChallenge:proofHash(row.verifier)};
}
export const requestHash=(value:unknown)=>digest(stable(value));
export type DeletionReceipt={deletionId:string;statusToken:string;state:'queued'|'purging'|'notifying'|'complete'};

export async function lockAccount(client:PoolClient,user:string){
  if(!uuid(user))throw new ApiError(401,'authentication_required');
  await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,890014))',['account/'+user.toLowerCase()]);
}
