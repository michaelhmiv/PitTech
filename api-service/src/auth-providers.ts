import { createPublicKey, createVerify } from 'node:crypto';
import { ApiError, digest } from './core.ts';
import { equalHash } from './auth-credentials.ts';
import type { GoogleOidcKeys } from './google-oidc-keys.ts';
import { verifyAppleJws, type AppleTrust } from './subscriptions-apple.ts';
import type { AccountDeletion } from './account-deletion.ts';

export type ProviderName = 'apple' | 'google';
export type AppleProvider = { audiences: string[]; keys: GoogleOidcKeys; trust: AppleTrust };
export type GoogleProvider = { webClientId: string; keys: GoogleOidcKeys };
/** Deployment provider configuration. Absent providers refuse every exchange;
 * Apple notifications additionally require the lifecycle deletion entry point. */
export type ProviderServices = { apple?: AppleProvider; google?: GoogleProvider; deletion?: AccountDeletion };

export type VerifiedProviderClaims = {
  provider: ProviderName; sub: string; nonce: string;
  email?: string; emailVerified: boolean;
};

const unavailable = () => new ApiError(401, 'sign_in_unavailable');
const rejected = () => new ApiError(401, 'notification_rejected');

function b64u(value: string): Buffer {
  if (!/^[A-Za-z0-9_-]+$/.test(value) || value.length % 4 === 1) throw unavailable();
  return Buffer.from(value.replace(/-/g, '+').replace(/_/g, '/'), 'base64');
}

/** Verify an Apple or Google identity token against the deployment's cached
 * provider keys. Every failure mode is one generic 401 without diagnostics,
 * matching email verify. Raw subjects leave this function only as hashes. */
export function verifyProviderToken(token: unknown, provider: ProviderName, services: ProviderServices, expectedNonce: string, now: number): VerifiedProviderClaims {
  const service = provider === 'apple' ? services.apple : services.google;
  if (!service) throw unavailable();
  if (typeof token !== 'string' || !token || token.length > 8192) throw unavailable();
  const parts = token.split('.'); if (parts.length !== 3) throw unavailable();
  try {
    const header = JSON.parse(b64u(parts[0]).toString('utf8')) as Record<string, unknown>;
    const claims = JSON.parse(b64u(parts[1]).toString('utf8')) as Record<string, unknown>;
    if (!header || typeof header !== 'object' || Array.isArray(header) || !claims || typeof claims !== 'object' || Array.isArray(claims)) throw unavailable();
    if (header.alg !== 'RS256') throw unavailable();
    if (typeof header.kid !== 'string' || !header.kid || header.kid.length > 128 || !service.keys.has(header.kid)) throw unavailable();
    const verifier = createVerify('RSA-SHA256'); verifier.update(parts[0] + '.' + parts[1]); verifier.end();
    let key;
    try { key = createPublicKey(service.keys.get(header.kid)!); }
    catch { throw unavailable(); }
    if (!verifier.verify(key, b64u(parts[2]))) throw unavailable();
    if (provider === 'apple') {
      if (claims.iss !== 'https://appleid.apple.com') throw unavailable();
      if (typeof claims.aud !== 'string' || !('audiences' in service) || !service.audiences.includes(claims.aud)) throw unavailable();
    } else {
      if (claims.iss !== 'https://accounts.google.com' && claims.iss !== 'accounts.google.com') throw unavailable();
      if (!('webClientId' in service) || claims.aud !== service.webClientId) throw unavailable();
    }
    if (!Number.isSafeInteger(claims.exp) || (claims.exp as number) * 1000 <= now ||
        !Number.isSafeInteger(claims.iat) || (claims.iat as number) * 1000 > now + 300000) throw unavailable();
    if (typeof claims.nonce !== 'string' || !equalHash(claims.nonce, expectedNonce)) throw unavailable();
    if (typeof claims.sub !== 'string' || !claims.sub || claims.sub.length > 256) throw unavailable();
    const email = typeof claims.email === 'string' && claims.email.length >= 1 && claims.email.length <= 254 ? claims.email : undefined;
    return { provider, sub: claims.sub, nonce: claims.nonce, email, emailVerified: claims.email_verified === true };
  } catch (error) { if (error instanceof ApiError) throw error; throw unavailable(); }
}

export type AppleProviderEvent =
  | { type: 'account-delete' | 'consent-revoked'; eventId: string; sub: string }
  | { type: 'email-enabled' | 'email-disabled'; eventId: string; sub: string; email?: string }
  | { type: 'unknown'; eventId: string };

/** Sign in with Apple server-to-server notification: an ES256 Security Event
 * Token verified with the StoreKit chain verifier. Malformed payloads are a
 * 401; well-formed events always resolve to a durable receipt and a 200. */
export function verifyAppleProviderNotification(input: { signedPayload: unknown }, services: ProviderServices, now: number): AppleProviderEvent {
  const apple = services.apple; if (!apple) throw rejected();
  if (typeof input.signedPayload !== 'string' || !input.signedPayload) throw rejected();
  let payload: Record<string, unknown>;
  try { payload = verifyAppleJws(input.signedPayload, apple.trust, now); }
  catch { throw rejected(); }
  try {
    if (payload.iss !== 'https://appleid.apple.com') throw rejected();
    if (typeof payload.aud !== 'string' || !apple.audiences.includes(payload.aud)) throw rejected();
    if (typeof payload.jti !== 'string' || !payload.jti || payload.jti.length > 256) throw rejected();
    if (typeof payload.events !== 'string' || !payload.events || payload.events.length > 4096) throw rejected();
    let event: unknown;
    try { event = JSON.parse(payload.events); }
    catch { throw rejected(); }
    if (!event || typeof event !== 'object' || Array.isArray(event)) throw rejected();
    const row = event as Record<string, unknown>, type = row.type;
    if (type !== 'account-delete' && type !== 'consent-revoked' && type !== 'email-enabled' && type !== 'email-disabled')
      return { type: 'unknown', eventId: payload.jti };
    if (typeof row.sub !== 'string' || !row.sub || row.sub.length > 256) throw rejected();
    if (type === 'email-enabled' || type === 'email-disabled') {
      const email = typeof row.email === 'string' && row.email.length >= 1 && row.email.length <= 254 ? row.email : undefined;
      return { type, eventId: payload.jti, sub: row.sub, email };
    }
    return { type, eventId: payload.jti, sub: row.sub };
  } catch (error) { if (error instanceof ApiError) throw error; throw rejected(); }
}

/** Deterministic deletion operation identifier derived from the Apple event
 * identifier, which doubles as the notification dedupe key. */
export function appleEventOperationId(eventId: string): string {
  const hex = digest('apple-provider-event/' + eventId);
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
}
