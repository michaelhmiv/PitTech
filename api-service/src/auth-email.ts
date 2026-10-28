import { ApiError } from './core.ts';
import { emailAddress, isCredential } from './auth-credentials.ts';
import { emailShell, emailHeading, emailMuted, emailButton, emailColors } from './email-shell.ts';

export type SignInMail = { email: string; challengeId: string; code: string; token: string };
const escape = (s: string) => s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!));
export function signInEmail(mail: SignInMail, origin: string) {
  emailAddress(mail.email);
  if (!/^[a-f0-9-]{36}$/.test(mail.challengeId) || !/^\d{6}$/.test(mail.code) || !isCredential(mail.token) || !['https://pittech.app', 'https://staging.pittech.app'].includes(origin)) throw new Error('Invalid auth mail');
  // A fragment never reaches the HTTP request or access log. GET does not authenticate.
  const link = `${origin}/auth#challenge=${mail.challengeId}&token=${mail.token}`;
  const text = `Sign in to PitTech\n\nYour code: ${mail.code}\n\nOr open this link on the device where you requested it:\n${link}\n\nThis code and link expire in 15 minutes and can be used only once. Never share them. If you did not request this email, ignore it. Your local library is unchanged.`;
  const html = emailShell(origin, 'Your PitTech sign-in code',
    emailHeading('Sign in to PitTech')
    + `<p style="margin:0 0 10px">Your sign-in code:</p>`
    + `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr><td align="center" bgcolor="${emailColors.cell}" style="background-color:${emailColors.cell};border:1px solid ${emailColors.line};border-radius:10px;padding:18px 12px;font:700 34px/1.2 Consolas,'Courier New',monospace;letter-spacing:6px;color:${emailColors.cream}">${mail.code}</td></tr></table>`
    + `<div style="margin:24px 0 0">${emailButton(escape(link), 'Sign in on the device where you requested this email')}</div>`
    + emailMuted('This code and link expire in 15 minutes and can be used only once. Never share them.')
    + emailMuted('If you did not request this email, ignore it. Your local library is unchanged.'));
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
