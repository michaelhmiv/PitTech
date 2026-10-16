import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Script,createContext } from 'node:vm';
import { randomUUID,webcrypto } from 'node:crypto';
import { deletionPage } from '../src/lifecycle-web.ts';
import { credential,proofHash } from '../src/auth-credentials.ts';

test('browser deletion requires code and typed review; lost-response retries preserve exact proof and recovery never needs access tokens',async()=>{
  const html=deletionPage('a'.repeat(32));assert.ok(!html.includes('__NONCE__'));assert.throws(()=>deletionPage('"><script>'));
  const source=/<script nonce="[a-f0-9]+">([\s\S]*?)<\/script>/.exec(html)![1],script=new Script(source);
  const stored=new Map<string,string>(),requests:{path:string;body:Record<string,unknown>;headers:Record<string,string>}[]=[],access=credential(),deletionId=randomUUID(),statusToken=credential();let drop=true;
  const mount=()=>{
    const elements=new Map<string,{value:string;checked:boolean;hidden:boolean;disabled:boolean;textContent:string;reportValidity:()=>boolean;focus:()=>void;listener?:()=>Promise<void>|void}>();
    for(const id of ['email','code','codeEntry','signIn','review','progress','understand','confirm','send','verify','delete','recover','check','retry','message','state'])elements.set(id,{value:'',checked:false,hidden:['codeEntry','review','progress'].includes(id),disabled:false,textContent:'',reportValidity:()=>true,focus:()=>{}});
    const document={getElementById:(id:string)=>{const item=elements.get(id)!;return Object.assign(item,{addEventListener:(_event:string,listener:()=>Promise<void>|void)=>{item.listener=listener;}});},querySelectorAll:()=>[...elements.values()]};
    const context=createContext({document,sessionStorage:{getItem:(key:string)=>stored.get(key),setItem:(key:string,value:string)=>stored.set(key,value)},crypto:webcrypto,TextEncoder,Uint8Array,AbortSignal,btoa:(s:string)=>Buffer.from(s,'binary').toString('base64'),fetch:async(path:string,options:{body:string;headers:Record<string,string>})=>{
      const body=JSON.parse(options.body);requests.push({path,body,headers:options.headers});
      if(path==='/v1/auth/challenges')return {ok:true,json:async()=>({challengeId:body.idempotencyId})};
      if(path==='/v1/auth/verify')return {ok:true,json:async()=>({accessToken:access,refreshToken:credential()})};
      if(path==='/v1/me/deletion'){if(drop){drop=false;throw new Error('Synthetic response loss');}return {ok:true,json:async()=>({deletionId,statusToken,state:'queued'})};}
      if(path==='/v1/deletion/recover')return {ok:true,json:async()=>({deletionId,statusToken,state:'purging'})};
      return {ok:true,json:async()=>({deletionId,state:'complete'})};
    }});script.runInContext(context);
    return {elements,click:async(id:string)=>{await elements.get(id)!.listener!();}};
  };
  const first=mount();assert.equal(requests.length,0,'Opening the page caused network activity');
  first.elements.get('email')!.value='synthetic@example.com';await first.click('send');first.elements.get('code')!.value='123456';await first.click('verify');assert.equal(first.elements.get('review')!.hidden,false);
  await first.click('delete');assert.equal(requests.filter(r=>r.path==='/v1/me/deletion').length,0);
  first.elements.get('understand')!.checked=true;first.elements.get('confirm')!.value='DELETE';await first.click('delete');
  const original=requests.at(-1)!;assert.equal(original.path,'/v1/me/deletion');assert.equal(original.body.confirm,'delete-account');assert.equal(first.elements.get('progress')!.hidden,false);
  await first.click('retry');await first.click('delete');assert.deepEqual(requests.at(-1)!.body,original.body);
  const serialized=[...stored.values()].join('');assert.ok(!serialized.includes(access));assert.ok(!serialized.includes('synthetic@example.com'));assert.ok(!serialized.includes('123456'));
  const refreshed=mount();assert.equal(refreshed.elements.get('progress')!.hidden,false);await refreshed.click('recover');const recovery=requests.at(-1)!;
  assert.equal(recovery.path,'/v1/deletion/recover');assert.equal(recovery.headers.Authorization,undefined);assert.equal(proofHash(recovery.body.verifier as string),original.body.proofChallenge);
  await refreshed.click('check');assert.equal(requests.at(-1)!.headers.Authorization,undefined);assert.match(refreshed.elements.get('state')!.textContent,/Deletion is complete/);
});
