import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { lifecycleEmail,ResendLifecycleMailer,LifecycleMailFailure } from '../src/lifecycle-email.ts';

test('lifecycle notices contain no authored public/private content and retry the same event without diagnostic leakage',async()=>{
  const mail={id:randomUUID(),email:'private@example.com',kind:'deletion_complete' as const};
  const payload=lifecycleEmail(mail,'https://pittech.app');assert.ok(!payload.html.includes(mail.email));assert.match(payload.html,/lang="en"/);assert.equal(payload.reply_to,'sportfolioholdings@gmail.com');assert.match(payload.text,/independent copies/);
  assert.throws(()=>lifecycleEmail({...mail,email:'bad'},'https://pittech.app'));assert.throws(()=>lifecycleEmail(mail,'http://attacker.invalid'));
  const keys:string[]=[];let status=503;
  const sender=new ResendLifecycleMailer('re_test','signin@auth.pittech.app','https://pittech.app',async(url,request)=>{
    assert.equal(url,'https://api.resend.com/emails');assert.equal(request?.redirect,'error');keys.push((request?.headers as Record<string,string>)['Idempotency-Key']);return new Response('private-provider-diagnostic',{status,headers:{'Retry-After':'10'}});
  });
  for(const code of [503,429,422]){status=code;await assert.rejects(sender.send(mail),(error:unknown)=>{assert.ok(error instanceof LifecycleMailFailure);assert.equal(error.permanent,code===422);assert.ok(!error.message.includes('private-provider-diagnostic'));assert.equal(error.retryAfterMillis,10000);return true;});}
  status=200;await sender.send(mail);assert.equal(new Set(keys).size,1);assert.equal(keys[0],'pittech-lifecycle/'+mail.id);
});
