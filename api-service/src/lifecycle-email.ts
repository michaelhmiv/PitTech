import { isCredential,emailAddress } from './auth-credentials.ts';
import { uuid } from './lifecycle-contract.ts';

export type LifecycleNoticeKind='publication_approved'|'publication_removed'|'account_suspended'|'deletion_complete';
export type LifecycleMail={id:string;email:string;kind:LifecycleNoticeKind};
const copy={
  publication_approved:{subject:'PitTech publication review complete',body:'Your publication has passed review. Open My cookbook in PitTech to see its current status.'},
  publication_removed:{subject:'PitTech publication removed',body:'A publication is no longer available publicly. Open My cookbook in PitTech for its status and contact the support address below if you need a review.'},
  account_suspended:{subject:'PitTech publishing paused',body:'Publishing from your account has been paused. Your local cookbook remains available. Open PitTech for account controls, or contact the support address below for a review.'},
  deletion_complete:{subject:'Your PitTech account deletion is complete',body:'Your account and original server data have been removed. Other cooks keep independent copies they already saved. Any journal you chose to keep stays on your device. Store subscriptions are managed separately through Apple or Google Play.'},
} satisfies Record<LifecycleNoticeKind,{subject:string;body:string}>;
const escape=(value:string)=>value.replace(/[&<>"']/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]!));
export function lifecycleEmail(mail:LifecycleMail,origin:string){
  if(!uuid(mail.id)||!Object.hasOwn(copy,mail.kind)||!['https://pittech.app','https://staging.pittech.app'].includes(origin))throw new Error('Invalid lifecycle notice');
  const email=emailAddress(mail.email),content=copy[mail.kind],support='sportfolioholdings@gmail.com';
  return {to:[email],reply_to:support,subject:content.subject,
    text:`${content.subject}\n\n${content.body}\n\nOpen PitTech: ${origin}\n\nQuestions? ${support}`,
    html:`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${escape(content.subject)}</title></head><body style="margin:0;background:#F5F0E7;color:#242622;font:17px/1.6 Arial,sans-serif"><main style="max-width:560px;margin:auto;padding:32px 24px"><h1 style="font-size:26px">${escape(content.subject)}</h1><p>${escape(content.body)}</p><p><a href="${origin}" style="display:inline-block;min-height:44px;color:#B04D2E">Open PitTech</a></p><p>Questions? <a href="mailto:${support}" style="color:#B04D2E">${support}</a></p></main></body></html>`};
}
export class LifecycleMailFailure extends Error {
  readonly permanent:boolean;readonly retryAfterMillis:number;
  constructor(permanent:boolean,retryAfterMillis=0){super('Lifecycle notice deferred');this.permanent=permanent;this.retryAfterMillis=retryAfterMillis;}
}
export interface LifecycleMailer{send(mail:LifecycleMail):Promise<void>}
export class ResendLifecycleMailer implements LifecycleMailer{
  private apiKey:string;private from:string;private origin:string;private request:typeof fetch;
  constructor(apiKey:string,from:string,origin:string,request:typeof fetch=fetch){
    this.apiKey=apiKey;this.from=from;this.origin=origin;this.request=request;
    if(!/^re_[A-Za-z0-9_-]+$/.test(apiKey)||!['signin@auth.pittech.app','signin@auth-staging.pittech.app'].includes(from)||(origin==='https://pittech.app'?from!=='signin@auth.pittech.app':origin!=='https://staging.pittech.app'||from!=='signin@auth-staging.pittech.app'))throw new Error('Lifecycle mail configuration required');
  }
  async send(mail:LifecycleMail){
    const payload={...lifecycleEmail(mail,this.origin),from:this.from};
    try{
      const result=await this.request('https://api.resend.com/emails',{method:'POST',redirect:'error',signal:AbortSignal.timeout(10000),headers:{Authorization:'Bearer '+this.apiKey,'Content-Type':'application/json','Idempotency-Key':'pittech-lifecycle/'+mail.id},body:JSON.stringify(payload)});
      const delay=Number(result.headers.get('Retry-After'));await result.body?.cancel();
      if(!result.ok)throw new LifecycleMailFailure(result.status>=400&&result.status<500&&![408,429].includes(result.status),Number.isFinite(delay)?Math.min(300000,Math.max(0,delay*1000)):0);
    }catch(error){if(error instanceof LifecycleMailFailure)throw error;throw new LifecycleMailFailure(false);}
  }
}
