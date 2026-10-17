import { createHash, createHmac, randomBytes } from 'node:crypto';

export const LIMITS = Object.freeze({ compressed: 64 * 1024 ** 2, expanded: 64 * 1024 ** 2,
  entry: 16 * 1024 ** 2, entries: 1000, life: 180 * 86400000, uploadLease: 15 * 60000 });
export class ApiError extends Error {
  status: number; code: string;
  constructor(status: number, code: string) { super(code); this.status = status; this.code = code; }
}
export function requirePack(condition: unknown): asserts condition {
  if (!condition) throw new ApiError(422, 'invalid_pack');
}
export const digest = (bytes: string | Buffer) => createHash('sha256').update(bytes).digest('hex');
export function newCode(entropy: (size: number) => Buffer = randomBytes): string {
  const bytes = entropy(8); if (bytes.length !== 8) throw new Error('Invalid entropy source');
  return bytes.toString('hex').toUpperCase().match(/.{4}/g)!.join('-');
}
export function normalizeCode(code: string): string {
  if (code.length > 64 || !/^[\da-f\s-]+$/i.test(code)) throw new ApiError(404, 'unavailable');
  const raw = code.replace(/[\s-]/g, '').toUpperCase();
  if (!/^[\dA-F]{16}$/.test(raw)) throw new ApiError(404, 'unavailable');
  return raw.match(/.{4}/g)!.join('-');
}
export function ownerHash(secret: string): string {
  if (!/^[\w-]{43}$/.test(secret) || Buffer.from(secret, 'base64url').length !== 32 ||
      Buffer.from(secret, 'base64url').toString('base64url') !== secret) throw new ApiError(401, 'owner_required');
  return digest(secret);
}
export function rateIdentity(key: string, address: string, window: string): string {
  return createHmac('sha256', key).update(window + '\0' + address).digest('hex');
}
export type Summary = { kind: 'recipe' | 'collection' | 'cook'; dishes: number; sha256: string; bytes: number };
export type Budgets = { storageBytes: number; dailyBytes: number; dailyUploads: number };
