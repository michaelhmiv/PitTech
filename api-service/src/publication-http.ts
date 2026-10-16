import type { IncomingMessage, ServerResponse } from 'node:http';
import { createWriteStream } from 'node:fs';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import { ApiError, LIMITS } from './core.ts';
import type { Publications } from './publications.ts';
import { requestAudience } from './lifecycle-http.ts';
import type { CommunityLifecycle } from './community-lifecycle.ts';
import type { Authentication } from './auth.ts';
import { validatePublication } from './publication-validation.ts';

type Gate={claimUpload:()=>boolean;releaseUpload:()=>void;root:string};
const idempotency=(req:IncomingMessage)=>{const id=String(req.headers['idempotency-key']??'');if(!/^[a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(id))throw new ApiError(400,'operation_required');return id;};
async function body(req:IncomingMessage){
  if(req.headers['content-type']!=='application/json'||![undefined,'identity'].includes(req.headers['content-encoding']))throw new ApiError(415,'json_required');
  const chunks:Buffer[]=[];let size=0;
  for await(const chunk of req){size+=chunk.length;if(size>8192)throw new ApiError(413,'request_too_large');chunks.push(Buffer.from(chunk));}
  try{const value=JSON.parse(Buffer.concat(chunks).toString());if(!value||Array.isArray(value)||typeof value!=='object')throw new Error();return value as Record<string,unknown>;}catch{throw new ApiError(400,'json_required');}
}
export function publicationRoute(raw:string,method:string){
  if(method==='GET'&&/^\/v1\/discover(?:\?[^#]*)?$/.test(raw))return '/v1/discover';
  if(raw==='/v1/me/profile'&&['GET','PUT'].includes(method))return '/v1/me/profile';
  if(method==='GET'&&/^\/v1\/me\/recipes(?:\?cursor=[a-f\d-]{36})?$/.test(raw))return '/v1/me/recipes';
  if(method==='GET'&&/^\/v1\/profiles\/[a-z0-9_]{3,24}(?:\?cursor=[a-f\d-]{36})?$/.test(raw))return '/v1/profiles/:handle';
  if(method==='GET'&&/^\/v1\/me\/recipes\/[a-f\d-]{36}\/archive$/.test(raw))return '/v1/me/recipes/:id/archive';
  if(method==='GET'&&/^\/v1\/me\/recipes\/[a-f\d-]{36}$/.test(raw))return '/v1/me/recipes/:id';
  if(raw==='/v1/recipes'&&method==='POST')return '/v1/recipes';
  if(/^\/v1\/recipes\/[a-f\d-]{36}$/.test(raw)&&['GET','PUT','DELETE'].includes(method))return '/v1/recipes/:id';
  if(/^\/v1\/recipes\/[a-f\d-]{36}\/pack$/.test(raw)&&method==='GET')return '/v1/recipes/:id/pack';
  if(/^\/v1\/recipes\/[a-f\d-]{36}\/fork$/.test(raw)&&method==='POST')return '/v1/recipes/:id/fork';
  if(/^\/v1\/recipes\/[a-f\d-]{36}\/like$/.test(raw)&&['POST','DELETE'].includes(method))return '/v1/recipes/:id/like';
  if(/^\/v1\/recipes\/[a-f\d-]{36}\/assets\/[a-f\d-]{36}$/.test(raw)&&method==='GET')return '/v1/recipes/:id/assets/:assetId';
  return undefined;
}
export async function handlePublication(req:IncomingMessage,res:ServerResponse,route:string,publications:Publications,auth:Authentication|undefined,gate:Gate,community?:CommunityLifecycle){
  const raw=req.url!,method=req.method!,path=raw.split('?')[0].split('/'),id=path[3];
  const blocked=method==='GET'&&!route.startsWith('/v1/me/')||route.endsWith('/fork')||route.endsWith('/like')?await requestAudience(req,auth,community):[];
  const json=(status:number,value:unknown)=>{res.statusCode=status;res.setHeader('Content-Type','application/json');res.end(JSON.stringify(value));};
  if(route==='/v1/discover'){
    const params=new URL(raw,'https://api.pittech.app').searchParams;
    if([...params.keys()].some(k=>!['sort','food','q','cursor'].includes(k))||[...params.keys()].some(k=>params.getAll(k).length>1))throw new ApiError(400,'invalid_filter');
    json(200,await publications.discover({sort:params.get('sort')??undefined,food:params.get('food')??undefined,query:params.get('q')??undefined,cursor:params.get('cursor')??undefined},blocked));return;
  }
  if(route==='/v1/profiles/:handle'){json(200,await publications.profile(path[3],new URL(raw,'https://api.pittech.app').searchParams.get('cursor')??undefined,blocked));return;}
  if(route==='/v1/me/profile'||route==='/v1/me/recipes'||route==='/v1/me/recipes/:id'||route==='/v1/me/recipes/:id/archive'){
    if(!auth)throw new ApiError(503,'accounts_unavailable');
    const user=await auth.user(/^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization??'')?.[1]);
    if(route==='/v1/me/recipes/:id/archive'){
      if(!gate.claimUpload())throw new ApiError(503,'publication_busy');
      let held=true,settled=false,closed=res.destroyed;const release=()=>{if(held&&settled&&closed){held=false;gate.releaseUpload();}};res.once('close',()=>{closed=true;release();});
      try{const archive=await publications.ownedArchive(user.id,path[4],String(req.headers['if-match']??''));res.setHeader('Content-Type','application/zip');res.setHeader('Content-Length',archive.bytes.length);res.setHeader('X-Content-SHA256',archive.sha256);if(res.destroyed)throw new ApiError(400,'request_interrupted');res.end(archive.bytes);}finally{settled=true;release();}return;
    }
    if(route==='/v1/me/recipes/:id'){json(200,await publications.myItem(user.id,path[4]));return;}
    if(route==='/v1/me/recipes'){json(200,await publications.myItems(user.id,new URL(raw,'https://api.pittech.app').searchParams.get('cursor')??undefined));return;}
    if(method==='GET'){json(200,await publications.myProfile(user.id));return;}
    const input=await body(req);json(200,await publications.updateProfile(user.id,idempotency(req),{handle:input.handle,displayName:input.displayName,isPublic:input.isPublic}));return;
  }
  if(route.endsWith('/pack')){
    if(!gate.claimUpload())throw new ApiError(503,'publication_busy');
    try{const pack=await publications.cookPack(id,String(req.headers['if-match']??''),blocked);res.setHeader('Content-Type','application/octet-stream');res.setHeader('Content-Disposition','attachment; filename="PitTech.pittech"');res.setHeader('Content-Length',pack.bytes.length);res.setHeader('X-Content-SHA256',pack.sha256);res.end(pack.bytes);}finally{gate.releaseUpload();}return;
  }
  if(method==='GET'){
    if(route.endsWith('/assets/:assetId')){const media=await publications.media(id,path[5],blocked);res.setHeader('Content-Type',media.mimeType);res.setHeader('Content-Length',media.bytes.length);res.setHeader('X-Content-SHA256',media.sha256);res.end(media.bytes);}
    else json(200,await publications.get(id,blocked));return;
  }
  if(!auth)throw new ApiError(503,'accounts_unavailable');
  const user=await auth.user(/^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization??'')?.[1]),operation=idempotency(req);
  if(route.endsWith('/fork')){
    const input=await body(req);if(input.publish!==true||typeof input.expectedRevisionId!=='string')throw new ApiError(422,'explicit_publication_required');
    if(!gate.claimUpload())throw new ApiError(503,'publication_busy');
    try{json(201,await publications.fork(user.id,id,operation,input.expectedRevisionId,input.identityMode,{version:req.headers['x-pittech-publication-terms']},blocked));}finally{gate.releaseUpload();}return;
  }
  if(route.endsWith('/like')){json(200,await publications.like(user.id,id,operation,method==='POST',blocked));return;}
  const base=String(req.headers['if-match']??'');
  if(method==='DELETE'){json(200,await publications.unpublish(user.id,id,operation,base));return;}
  if(!['application/zip','application/octet-stream'].includes(req.headers['content-type']??'')||![undefined,'identity'].includes(req.headers['content-encoding']))throw new ApiError(415,'publication_archive_required');
  if(Number(req.headers['content-length']??0)>LIMITS.compressed)throw new ApiError(413,'request_too_large');
  if(!gate.claimUpload())throw new ApiError(503,'publication_busy');
  let directory:string|undefined,held=true;
  try{
    await mkdir(gate.root,{recursive:true,mode:0o700});directory=await mkdtemp(join(gate.root,'upload-'));
    const input=join(directory,'publication.zip'),output=join(directory,'accepted.zip');let size=0;
    await pipeline(req,new Transform({transform(chunk,_encoding,done){size+=chunk.length;done(size>LIMITS.compressed?new ApiError(413,'request_too_large'):null,chunk);}}),createWriteStream(input,{mode:0o600,flags:'wx'}));
    const validated=await validatePublication(input,output);
    if(res.destroyed)throw new ApiError(400,'request_interrupted');
    const result=await publications.publish(user.id,operation,validated,output,method==='PUT'?{id,base}:undefined,undefined,undefined,{version:req.headers['x-pittech-publication-terms']});
    // Release the staging file and shared slot before the client can retry the 201.
    await rm(directory,{recursive:true,force:true});directory=undefined;gate.releaseUpload();held=false;
    json(method==='PUT'?200:201,result);
  }finally{if(directory)await rm(directory,{recursive:true,force:true});if(held)gate.releaseUpload();}
}
