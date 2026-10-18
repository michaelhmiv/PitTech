import {createCipheriv,createDecipheriv,randomBytes}from'node:crypto';
import {digest}from'./core.ts';import{isCredential}from'./auth-credentials.ts';import{uuid}from'./lifecycle-contract.ts';
export type SyncObjectContext={userId:string;objectId:string;kind:'snapshot'|'photo';sha256:string};
const magic=Buffer.from('PTSYNC01');
/** Server-managed at-rest encryption. Keys are provisioned separately from auth/lifecycle keys. Not E2EE. */
export class SyncEncryption {
 private keys:Map<string,Buffer>;private current:string;
 constructor(current:string,keys:Record<string,string>){
  const rows=Object.entries(keys);if(rows.length<1||rows.length>3||!rows.every(([id,key])=>id.length>=1&&id.length<=40&&!/[^a-z0-9-]/.test(id)&&isCredential(key))||new Set(rows.map(([,key])=>key)).size!==rows.length||!Object.hasOwn(keys,current))throw Error('Private sync encryption configuration unavailable');
  this.current=current;this.keys=new Map(rows.map(([id,key])=>[id,Buffer.from(key,'base64url')]));
 }
 private aad(context:SyncObjectContext,key:string){
  if(!uuid(context.userId)||context.userId.length!==36||!uuid(context.objectId)||context.objectId.length!==36||!['snapshot','photo'].includes(context.kind)||!/^[a-f0-9]{64}$/.test(context.sha256))throw Error('Private sync object unavailable');
  return Buffer.from(['private-sync-v1',context.userId.toLowerCase(),context.objectId.toLowerCase(),context.kind,context.sha256,key].join('\0'));
 }
 seal(context:SyncObjectContext,source:Buffer){
  const limit=context.kind==='photo'?16*1024**2:32*1024**2;if(!source.length||source.length>limit||digest(source)!==context.sha256)throw Error('Private sync object unavailable');
  const name=Buffer.from(this.current),iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',this.keys.get(this.current)!,iv);cipher.setAAD(this.aad(context,this.current));
  const plain=Buffer.from(source);try{const bytes=Buffer.concat([cipher.update(plain),cipher.final()]);return Buffer.concat([magic,Buffer.from([name.length]),name,iv,cipher.getAuthTag(),bytes]);}finally{plain.fill(0);}
 }
 open(context:SyncObjectContext,envelope:Buffer){
  const limit=context.kind==='photo'?16*1024**2:32*1024**2;
  if(envelope.length<39||envelope.length>limit+77||!envelope.subarray(0,8).equals(magic))throw Error('Private sync object unavailable');
  const length=envelope[8],start=9+length,keyId=envelope.subarray(9,start).toString('ascii'),key=this.keys.get(keyId);
  if(length<1||length>40||!key||!envelope.subarray(9,start).equals(Buffer.from(keyId))||envelope.length<=start+28)throw Error('Private sync object unavailable');
  let plain:Buffer|undefined,partial:Buffer|undefined;
  try{const cipher=createDecipheriv('aes-256-gcm',key,envelope.subarray(start,start+12));cipher.setAAD(this.aad(context,keyId));cipher.setAuthTag(envelope.subarray(start+12,start+28));partial=cipher.update(envelope.subarray(start+28));plain=Buffer.concat([partial,cipher.final()]);partial.fill(0);if(plain.length>limit||digest(plain)!==context.sha256)throw Error();return plain;}
  catch{partial?.fill(0);plain?.fill(0);throw Error('Private sync object unavailable');}
 }
}
