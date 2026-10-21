import { createPublicKey, type JsonWebKey } from 'node:crypto';

export interface GoogleOidcKeys {
  get(id: string): string | undefined;
  has(id: string): boolean;
}

export const GOOGLE_CERTS_ENDPOINT = 'https://www.googleapis.com/oauth2/v3/certs';
export const APPLE_KEYS_ENDPOINT = 'https://appleid.apple.com/auth/keys';
const unavailable = () => new Error('OIDC signing keys unavailable');

/** Google's discovery document names a JWKS endpoint, not a kid -> PEM map. */
export function googleSigningKeys(value: unknown): Map<string, string> {
  const root = value as { keys?: unknown } | null;
  if (!root || !Array.isArray(root.keys) || !root.keys.length || root.keys.length > 16) throw unavailable();
  const keys = new Map<string, string>();
  for (const item of root.keys) {
    const key = item as Record<string, unknown> | null;
    if (!key || typeof key !== 'object' || Array.isArray(key) || key.kty !== 'RSA' || key.alg !== 'RS256' || key.use !== 'sig' ||
        typeof key.kid !== 'string' || !key.kid || key.kid.length > 128 || keys.has(key.kid) ||
        typeof key.n !== 'string' || !/^[A-Za-z0-9_-]{342,1366}$/.test(key.n) ||
        typeof key.e !== 'string' || !/^[A-Za-z0-9_-]{1,12}$/.test(key.e) ||
        ['d', 'p', 'q', 'dp', 'dq', 'qi', 'oth'].some(field => field in key)) throw unavailable();
    try {
      const parsed = createPublicKey({ key: key as JsonWebKey, format: 'jwk' });
      const bits = parsed.asymmetricKeyDetails?.modulusLength;
      if (parsed.asymmetricKeyType !== 'rsa' || !bits || bits < 2048 || bits > 8192) throw unavailable();
      keys.set(key.kid, parsed.export({ type: 'spki', format: 'pem' }).toString());
    } catch { throw unavailable(); }
  }
  return keys;
}

/** Replace a complete verified key set atomically. Failed refreshes never extend
 * its lifetime; expired keys cannot authenticate a notification. */
export class GoogleOidcKeyCache implements GoogleOidcKeys {
  private keys = new Map<string, string>();
  private validUntil = 0;
  private request: typeof fetch;
  private clock: () => number;
  private endpoint: string;
  constructor(request: typeof fetch = fetch, clock: () => number = Date.now, endpoint: string = GOOGLE_CERTS_ENDPOINT) {
    if (endpoint !== GOOGLE_CERTS_ENDPOINT && endpoint !== APPLE_KEYS_ENDPOINT) throw new Error('Unknown OIDC key endpoint');
    this.request = request; this.clock = clock; this.endpoint = endpoint;
  }
  get(id: string): string | undefined { return this.clock() < this.validUntil ? this.keys.get(id) : undefined; }
  has(id: string): boolean { return this.get(id) !== undefined; }
  async refresh(): Promise<void> {
    const response = await this.request(this.endpoint, { redirect: 'error', signal: AbortSignal.timeout(10000) });
    if (!response.ok || !response.body) { await response.body?.cancel(); throw unavailable(); }
    const reader = response.body.getReader(), chunks: Buffer[] = [];
    let size = 0;
    try {
      while (true) {
        const part = await reader.read(); if (part.done) break;
        size += part.value.byteLength;
        if (size > 65536) throw unavailable();
        chunks.push(Buffer.from(part.value));
      }
    } catch { await reader.cancel(); throw unavailable(); }
    finally { reader.releaseLock(); }
    let next: Map<string, string>;
    try { next = googleSigningKeys(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
    catch { throw unavailable(); }
    const maxAge = /(?:^|,)\s*max-age=(\d+)(?:\s*,|\s*$)/i.exec(response.headers.get('cache-control') ?? '');
    const age = Number(response.headers.get('age') ?? '0');
    const seconds = Number(maxAge?.[1] ?? '300');
    if (!Number.isSafeInteger(age) || age < 0 || !Number.isSafeInteger(seconds)) throw unavailable();
    const remaining = Math.max(0, Math.min(3600, seconds - age));
    if (!remaining) throw unavailable();
    this.keys = next; this.validUntil = this.clock() + remaining * 1000;
  }
}
