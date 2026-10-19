import { createPublicKey, createSign, createVerify, X509Certificate } from 'node:crypto';
import { ApiError, digest } from './core.ts';
import { STORE_VERIFICATION_VERSION, invalidProof, type StoreEnvironment } from './subscriptions-apple.ts';

export type GoogleConfig = { packageName: string; productId: string };
export type GoogleVerification = {
  version: 'store-verification-v1'; store: 'google'; environment: StoreEnvironment;
  purchaseTokenRef: string; orderId: string; productId: string;
  purchasedAt: number; expiresAt: number; autoRenew: boolean;
  subscriptionState: string; acknowledged: boolean; testPurchase: boolean;
  storeReference: string; evidenceRef: string;
};

const text = (value: unknown, max: number): string => { if (typeof value !== 'string' || !value || value.length > max) throw new ApiError(503, 'store_unavailable'); return value; };
const millis = (value: unknown): number => { const n = Date.parse(typeof value === 'string' ? value : ''); if (!Number.isSafeInteger(n) || n < 0) throw new ApiError(503, 'store_unavailable'); return n; };

export type PlayApiFetch = (url: string, init: { method: string; token?: string; body?: string; contentType?: string }) => Promise<{ status: number; body: unknown }>;
export function googleServiceJwt(clientEmail: string, privateKey: string, now: number): string {
  const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const input = encode({ alg: 'RS256', typ: 'JWT' }) + '.' + encode({ iss: clientEmail, scope: 'https://www.googleapis.com/auth/androidpublisher', aud: 'https://oauth2.googleapis.com/token', iat: Math.floor(now / 1000), exp: Math.floor(now / 1000) + 3600 });
  try {
    const signer = createSign('RSA-SHA256'); signer.update(input); signer.end();
    return input + '.' + signer.sign(privateKey).toString('base64url');
  } catch { throw new ApiError(503, 'store_unavailable'); }
}
export class PlayDeveloperApi {
  private clientEmail: string; private privateKey: string; private fetchImpl: PlayApiFetch; clock: () => number;
  constructor(serviceAccount: { clientEmail: string; privateKey: string }, fetchImpl: PlayApiFetch, clock = Date.now) {
    this.clientEmail = serviceAccount.clientEmail; this.privateKey = serviceAccount.privateKey; this.fetchImpl = fetchImpl; this.clock = clock;
  }
  private async accessToken(): Promise<string> {
    const assertion = googleServiceJwt(this.clientEmail, this.privateKey, this.clock());
    const params = new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion });
    const response = await this.fetchImpl('https://oauth2.googleapis.com/token', { method: 'POST', body: params.toString(), contentType: 'application/x-www-form-urlencoded' });
    const body = response.body as Record<string, unknown>;
    if (response.status !== 200 || !body || typeof body.access_token !== 'string' || !body.access_token) throw new ApiError(503, 'store_unavailable');
    return body.access_token;
  }
  /** Authoritative subscription state. The package name is the service's own
   * configuration, never the client's claim, so app association holds by construction. */
  async subscription(purchaseToken: string, packageName: string): Promise<unknown> {
    if (typeof purchaseToken !== 'string' || !purchaseToken || purchaseToken.length > 4096) throw invalidProof();
    const token = await this.accessToken();
    const response = await this.fetchImpl(`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases/subscriptionsv2/${encodeURIComponent(purchaseToken)}`, { method: 'GET', token });
    if (response.status === 404) throw new ApiError(422, 'unknown_store_purchase');
    if (response.status !== 200 || !response.body || typeof response.body !== 'object') throw new ApiError(503, 'store_unavailable');
    return response.body;
  }
  /** Play acknowledgment within its required lifecycle: called during verification
   * whenever the store reports a pending acknowledgement, and retried on reconcile. */
  async acknowledge(purchaseToken: string, packageName: string): Promise<void> {
    const token = await this.accessToken();
    const response = await this.fetchImpl(`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases/subscriptionsv2/${encodeURIComponent(purchaseToken)}:acknowledge`, { method: 'POST', token, body: '{}', contentType: 'application/json' });
    if (response.status !== 200 && response.status !== 404) throw new ApiError(503, 'store_unavailable');
  }
}

/** Validate one purchases.subscriptionsv2 payload against the configured product.
 * Test-track purchases resolve to the sandbox environment from the store's own
 * testPurchase marker, never from a client flag. */
export function verifyGoogleSubscription(body: unknown, purchaseToken: string, config: GoogleConfig): GoogleVerification {
  const root = body as Record<string, unknown>;
  if (!root || typeof root !== 'object') throw new ApiError(503, 'store_unavailable');
  const items = root.lineItems;
  if (!Array.isArray(items) || items.length !== 1) throw new ApiError(503, 'store_unavailable');
  const line = items[0] as Record<string, unknown>;
  if (text(line.productId, 256) !== config.productId) throw new ApiError(422, 'unknown_store_product');
  const plan = line.autoRenewingPlan as Record<string, unknown> | undefined;
  return {
    version: STORE_VERIFICATION_VERSION, store: 'google',
    environment: root.testPurchase !== undefined && root.testPurchase !== null ? 'sandbox' : 'production',
    purchaseTokenRef: digest(purchaseToken), orderId: text(root.latestOrderId, 128), productId: config.productId,
    purchasedAt: millis(root.startTime), expiresAt: millis(line.expiryTime),
    autoRenew: plan?.autoRenewEnabled === true, subscriptionState: text(root.subscriptionState, 64),
    acknowledged: root.acknowledgementState !== 'ACKNOWLEDGEMENT_STATE_PENDING',
    testPurchase: root.testPurchase !== undefined && root.testPurchase !== null,
    storeReference: purchaseToken, evidenceRef: digest(JSON.stringify(body)),
  };
}
const b64u = (value: string): Buffer => {
  if (!/^[A-Za-z0-9_-]+$/.test(value) || value.length % 4 === 1) throw new ApiError(401, 'notification_rejected');
  return Buffer.from(value.replace(/-/g, '+').replace(/_/g, '/'), 'base64');
};
const b64std = (value: string): Buffer => {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
  if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(normalized)) throw new ApiError(401, 'notification_rejected');
  return Buffer.from(normalized, 'base64');
};
/** Verify the Pub/Sub push OIDC bearer against Google signing keys. Production
 * provisions the keys over TLS from Google's certificates; tests pin generated keys. */
export function verifyOidcBearer(token: unknown, keys: Map<string, string>, audience: string, now: number): Record<string, unknown> {
  if (typeof token !== 'string') throw new ApiError(401, 'notification_rejected');
  const parts = token.split('.'); if (parts.length !== 3) throw new ApiError(401, 'notification_rejected');
  try {
    const header = JSON.parse(b64u(parts[0]).toString('utf8')) as Record<string, unknown>;
    const claims = JSON.parse(b64u(parts[1]).toString('utf8')) as Record<string, unknown>;
    if (!header || !claims || typeof header !== 'object' || typeof claims !== 'object') throw new Error();
    if (header.alg !== 'RS256' && header.alg !== 'ES256') throw new Error();
    if (typeof header.kid !== 'string' || !keys.has(header.kid)) throw new Error();
    const verifier = createVerify(header.alg === 'RS256' ? 'RSA-SHA256' : 'sha256');
    verifier.update(parts[0] + '.' + parts[1]); verifier.end();
    // Google publishes X.509 certificates; pinned test keys are bare public keys.
    const keyObject = (() => {
      const pem = keys.get(header.kid)!;
      try { return createPublicKey(pem); }
      catch {
        const cert = new X509Certificate(pem);
        const since = Date.parse(cert.validFrom), until = Date.parse(cert.validTo);
        if (!Number.isSafeInteger(since) || !Number.isSafeInteger(until) || now < since || now > until) throw new Error();
        return cert.publicKey;
      }
    })();
    const ok = header.alg === 'RS256' ? verifier.verify(keyObject, b64u(parts[2])) : verifier.verify({ key: keyObject, dsaEncoding: 'ieee-p1363' }, b64u(parts[2]));
    if (!ok) throw new Error();
    if (claims.iss !== 'https://accounts.google.com' && claims.iss !== 'accounts.google.com') throw new Error();
    if (claims.aud !== audience) throw new Error();
    if (!Number.isSafeInteger(claims.exp) || (claims.exp as number) * 1000 <= now || !Number.isSafeInteger(claims.iat) || (claims.iat as number) * 1000 > now + 300000) throw new Error();
    return claims;
  } catch (error) { if (error instanceof ApiError) throw error; throw new ApiError(401, 'notification_rejected'); }
}

export type RtdnIntent =
  | { kind: 'test'; messageId: string }
  | { kind: 'reconcile'; messageId: string; purchaseToken: string; subscriptionId: string; notificationType: number; eventAt: number };
/** Authenticate a Play RTDN push, validate its envelope, and extract the reconcile
 * intent. The caller deduplicates by messageId, answers 200 as the Play
 * acknowledgment, and then reconciles against the Play API — the notification
 * is a hint, the store is the truth. */
export function verifyRtdnPush(body: unknown, authorization: string | undefined, keys: Map<string, string>, audience: string, config: GoogleConfig, now: number): RtdnIntent {
  const bearer = /^Bearer (.+)$/.exec(authorization ?? '')?.[1];
  verifyOidcBearer(bearer, keys, audience, now);
  const root = body as Record<string, unknown>;
  const message = root?.message as Record<string, unknown> | undefined;
  if (!root || typeof root !== 'object' || !message || typeof message !== 'object') throw new ApiError(401, 'notification_rejected');
  if (typeof message.messageId !== 'string' || !message.messageId || message.messageId.length > 256) throw new ApiError(401, 'notification_rejected');
  if (typeof message.data !== 'string' || message.data.length > 65536) throw new ApiError(401, 'notification_rejected');
  let notification: Record<string, unknown>;
  try { notification = JSON.parse(b64std(message.data).toString('utf8')) as Record<string, unknown>; if (!notification || typeof notification !== 'object') throw new Error(); }
  catch { throw new ApiError(401, 'notification_rejected'); }
  if (notification.testNotification !== undefined) return { kind: 'test', messageId: message.messageId };
  if (notification.packageName !== config.packageName) throw new ApiError(401, 'notification_rejected');
  const sub = notification.subscriptionNotification as Record<string, unknown> | undefined;
  if (!sub || typeof sub !== 'object' || sub.subscriptionId !== config.productId || typeof sub.purchaseToken !== 'string' || !sub.purchaseToken || sub.purchaseToken.length > 4096 || !Number.isSafeInteger(sub.notificationType)) throw new ApiError(401, 'notification_rejected');
  const eventAt = Number(notification.eventTimeMillis);
  if (!Number.isSafeInteger(eventAt) || eventAt <= 0) throw new ApiError(401, 'notification_rejected');
  return { kind: 'reconcile', messageId: message.messageId, purchaseToken: sub.purchaseToken, subscriptionId: sub.subscriptionId, notificationType: sub.notificationType as number, eventAt };
}
