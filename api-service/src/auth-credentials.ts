import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { ApiError } from './core.ts';

export const AUTH = Object.freeze({ challengeMs: 15 * 60_000, resendMs: 60_000, attempts: 5, accessMs: 10 * 60_000, sessionMs: 30 * 86400_000 });
export function emailAddress(value: unknown): string {
  if (typeof value !== 'string' || value.length > 254 || /[^\x00-\x7f]/.test(value)) throw new ApiError(400, 'email_required');
  const email = value.trim().toLowerCase(), parts = email.split('@');
  if (parts.length !== 2) throw new ApiError(400, 'email_required');
  const [local, domain] = parts;
  if (!local || local.length > 64 || !/^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+$/.test(local) || local.startsWith('.') || local.endsWith('.') || local.includes('..') ||
      !domain.includes('.') || domain.split('.').some(label => !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label))) throw new ApiError(400, 'email_required');
  return email;
}
export const credential = () => randomBytes(32).toString('base64url');
export function isCredential(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{43}$/.test(value) && Buffer.from(value, 'base64url').toString('base64url') === value;
}
export function proofHash(value: string): string {
  if (!isCredential(value)) throw new ApiError(401, 'sign_in_unavailable');
  return createHash('sha256').update(value).digest('hex');
}
export function equalHash(a: string, b: string): boolean {
  return /^[a-f0-9]{64}$/.test(a) && /^[a-f0-9]{64}$/.test(b) && timingSafeEqual(Buffer.from(a, 'hex'), Buffer.from(b, 'hex'));
}
/** Domain-separated keyed hashes prevent offline enumeration of six-digit codes or email identities. */
export class AuthSecrets {
  private pepper: Buffer; private encryption: Buffer;
  constructor(pepper: string, encryptionKey: string) {
    if (!isCredential(pepper) || !isCredential(encryptionKey) || pepper === encryptionKey) throw new Error('Separate auth secrets required');
    this.pepper = Buffer.from(pepper, 'base64url'); this.encryption = Buffer.from(encryptionKey, 'base64url');
  }
  hash(purpose: string, value: string): string { return createHmac('sha256', this.pepper).update(purpose).update('\0').update(value).digest('hex'); }
  code() { return randomInt(0, 1_000_000).toString().padStart(6, '0'); }
  seal(purpose: string, value: unknown): string {
    const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm', this.encryption, iv); cipher.setAAD(Buffer.from(purpose));
    const plain = Buffer.from(JSON.stringify(value));
    try { const bytes = Buffer.concat([cipher.update(plain), cipher.final()]); return Buffer.concat([iv, cipher.getAuthTag(), bytes]).toString('base64url'); }
    finally { plain.fill(0); }
  }
  open(purpose: string, value: string): unknown {
    const bytes = Buffer.from(value, 'base64url'); if (bytes.length < 29 || bytes.length > 16384) throw new Error('Auth envelope unavailable');
    const cipher = createDecipheriv('aes-256-gcm', this.encryption, bytes.subarray(0, 12)); cipher.setAAD(Buffer.from(purpose)); cipher.setAuthTag(bytes.subarray(12, 28));
    const plain = Buffer.concat([cipher.update(bytes.subarray(28)), cipher.final()]);
    try { return JSON.parse(plain.toString('utf8')); } finally { plain.fill(0); }
  }
}
