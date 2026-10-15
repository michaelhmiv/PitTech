import { ApiError } from './core.ts';
import { emailAddress, isCredential } from './auth-credentials.ts';

export type SignInMail = { email: string; challengeId: string; code: string; token: string };
const escape = (s: string) => s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!));
export function signInEmail(mail: SignInMail, origin: string) {
  emailAddress(mail.email);
  if (!/^[a-f0-9-]{36}$/.test(mail.challengeId) || !/^\d{6}$/.test(mail.code) || !isCredential(mail.token) || !['https://pittech.app', 'https://staging.pittech.app'].includes(origin)) throw new Error('Invalid auth mail');
  // A fragment never reaches the HTTP request or access log. GET does not authenticate.
  const link = `${origin}/auth#challenge=${mail.challengeId}&token=${mail.token}`;
  const text = `Sign in to PitTech\n\nYour code: ${mail.code}\n\nOr open this link on the device where you requested it:\n${link}\n\nThis code and link expire in 15 minutes and can be used only once. Never share them. If you did not request this email, ignore it. Your local library is unchanged.`;
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"></head><body style="margin:0;background:#F5F0E7;color:#242622;font:17px/1.6 Arial,sans-serif"><main style="max-width:560px;margin:auto;padding:32px 24px"><h1 style="font-size:26px">Sign in to PitTech</h1><p>Your sign-in code:</p><p style="font-size:32px;font-weight:bold;letter-spacing:5px">${mail.code}</p><p><a href="${escape(link)}" style="color:#B04D2E">Sign in on the device where you requested this email</a></p><p>This code and link expire in 15 minutes and can be used only once. Never share them.</p><p>If you did not request this email, ignore it. Your local library is unchanged.</p></main></body></html>`;
  return { from: '', to: [mail.email], subject: 'Your PitTech sign-in code', text, html };
}
export interface AuthMailer { send(mail: SignInMail): Promise<void> }
export class ResendAuthMailer implements AuthMailer {
  private apiKey: string; private from: string; private origin: string; private request: typeof fetch;
  constructor(apiKey: string, from: string, origin: string, request: typeof fetch = fetch) {
    if (!/^re_[A-Za-z0-9_-]+$/.test(apiKey) || !['signin@auth.pittech.app', 'signin@auth-staging.pittech.app'].includes(from) ||
      (origin === 'https://pittech.app' ? from !== 'signin@auth.pittech.app' : origin !== 'https://staging.pittech.app' || from !== 'signin@auth-staging.pittech.app')) throw new Error('Auth email configuration required');
    this.apiKey = apiKey; this.from = from; this.origin = origin; this.request = request;
  }
  async send(mail: SignInMail) {
    const payload = { ...signInEmail(mail, this.origin), from: this.from };
    try {
      const response = await this.request('https://api.resend.com/emails', { method: 'POST', redirect: 'error', signal: AbortSignal.timeout(10_000),
        headers: { Authorization: `Bearer ${this.apiKey}`, 'Content-Type': 'application/json', 'Idempotency-Key': `pittech-auth/${mail.challengeId}` }, body: JSON.stringify(payload) });
      // Provider response bodies can contain addresses or diagnostic content; never log or return them.
      await response.body?.cancel(); if (!response.ok) throw new Error('Mail unavailable');
    } catch { throw new ApiError(503, 'email_delivery_deferred'); }
  }
}
