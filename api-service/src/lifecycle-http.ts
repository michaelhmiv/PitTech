import type { IncomingMessage,ServerResponse } from 'node:http';
import { ApiError } from './core.ts';
import type { Authentication } from './auth.ts';
import type { AccountDeletion } from './account-deletion.ts';
import type { CommunityLifecycle } from './community-lifecycle.ts';
import { uuid,blockAliases } from './lifecycle-contract.ts';

export function lifecycleRoute(raw:string,method:string){
  if(method==='POST'&&['/v1/me/deletion','/v1/deletion/recover','/v1/deletion/status','/v1/reports','/v1/me/blocks','/v1/operator/content','/v1/operator/profiles','/v1/operator/publishers','/v1/operator/reports'].includes(raw))return raw;
  if(method==='GET'&&raw==='/v1/me/blocks')return raw;
  if(method==='GET'&&/^\/v1\/reports\/[a-f\d-]{36}$/.test(raw))return '/v1/reports/:id';
  if(method==='GET'&&/^\/v1\/operator\/queue\/(content|profiles|reports)(\?cursor=[a-f\d-]{36})?$/.test(raw))return '/v1/operator/queue/:kind';
  if(method==='GET'&&/^\/v1\/operator\/items\/[a-f\d-]{36}$/.test(raw))return '/v1/operator/items/:id';
  if(method==='GET'&&/^\/v1\/operator\/items\/[a-f\d-]{36}\/assets\/[a-f\d-]{36}$/.test(raw))return '/v1/operator/items/:id/assets/:assetId';
  return undefined;
}
export async function lifecycleBody(req:IncomingMessage){
  if(req.headers['content-type']!=='application/json'||![undefined,'identity'].includes(req.headers['content-encoding']))throw new ApiError(415,'json_required');
  if(Number(req.headers['content-length']??0)>8192)throw new ApiError(413,'request_too_large');
  const chunks:Buffer[]=[];let size=0;for await(const chunk of req){size+=chunk.length;if(size>8192)throw new ApiError(413,'request_too_large');chunks.push(Buffer.from(chunk));}
  try{const body=JSON.parse(Buffer.concat(chunks).toString('utf8'));if(!body||typeof body!=='object'||Array.isArray(body))throw new Error();return body as Record<string,unknown>;}catch{throw new ApiError(400,'json_required');}
}
export async function optionalAccount(req:IncomingMessage,auth:Authentication|undefined){
  if(req.headers.authorization===undefined)return undefined;
  if(!auth)throw new ApiError(503,'accounts_unavailable');return auth.user(/^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization)?.[1]);
}
export async function requestAudience(req:IncomingMessage,auth:Authentication|undefined,community:CommunityLifecycle|undefined){
  if(!community)return [];
  const user=await optionalAccount(req,auth),header=req.headers['x-pittech-blocks'];
  if(Array.isArray(header))throw new ApiError(400,'invalid_blocks');
  return (await community.audience(user?.id,blockAliases(header))).blockedPublishers;
}
export async function handleLifecycle(req:IncomingMessage,res:ServerResponse,route:string,auth:Authentication|undefined,deletion:AccountDeletion,community:CommunityLifecycle){
  const json=(status:number,value:unknown)=>{res.statusCode=status;res.setHeader('Content-Type','application/json');res.end(JSON.stringify(value));};
  const segments=req.url!.split('?')[0].split('/');
  if(route.startsWith('/v1/operator/')){
    // Operator credentials are a separate capability namespace. Never accept a
    // user access token or a role supplied in JSON as authority.
    const token=/^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization??'')?.[1];await community.operator(token);
    if(route==='/v1/operator/queue/:kind'){json(200,await community.queue(token,segments[4] as 'content'|'profiles'|'reports',new URL(req.url!,'https://api.pittech.app').searchParams.get('cursor')??undefined));return;}
    if(route==='/v1/operator/items/:id'){json(200,await community.review(token,segments[4]));return;}
    if(route.endsWith('/assets/:assetId')){const image=await community.reviewMedia(token,segments[4],segments[6]);res.setHeader('Content-Type',image.mimeType);res.setHeader('Content-Length',image.bytes.length);res.setHeader('X-Content-SHA256',image.sha256);res.end(image.bytes);return;}
    const body=await lifecycleBody(req);
    const result=route==='/v1/operator/content'?await community.decideContent(token,body):route==='/v1/operator/profiles'?await community.decideProfile(token,body):route==='/v1/operator/publishers'?await community.setPublisher(token,body):await community.setReport(token,body);json(200,result);return;
  }
  // Recovery/status deliberately require only their device proof/capability,
  // because the request atomically revokes the initiating account's sessions.
  if(route==='/v1/deletion/recover'){json(200,await deletion.recover(await lifecycleBody(req)));return;}
  if(route==='/v1/deletion/status'){json(200,await deletion.status(await lifecycleBody(req)));return;}
  const user=await optionalAccount(req,auth);
  if(route==='/v1/reports'){json(202,await community.report(user?.id,req.headers['x-pittech-report-session'],await lifecycleBody(req)));return;}
  if(route==='/v1/reports/:id'){json(200,await community.reportStatus(user?.id,req.headers['x-pittech-report-session'],segments[3]));return;}
  if(!user)throw new ApiError(401,'authentication_required');
  if(route==='/v1/me/deletion'){json(202,await deletion.request(user,await lifecycleBody(req)));return;}
  if(req.method==='GET'){json(200,await community.blocks(user.id));return;}
  const body=await lifecycleBody(req);
  if(Object.keys(body).some(key=>!['operationId','alias','blocked'].includes(key))||!uuid(body.operationId))throw new ApiError(422,'invalid_block');
  json(200,await community.block(user.id,body.operationId,body.alias,body.blocked));
}
