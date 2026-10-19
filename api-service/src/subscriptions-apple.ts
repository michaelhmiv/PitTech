import { createPrivateKey, createPublicKey, createSign, createVerify, X509Certificate, type KeyObject } from 'node:crypto';
import { ApiError, digest } from './core.ts';

export type StoreEnvironment = 'sandbox' | 'production';
export const STORE_VERIFICATION_VERSION = 'store-verification-v1';
export const invalidProof = () => new ApiError(422, 'invalid_store_proof');

export type AppleConfig = { bundleId: string; productId: string };
export type AppleTrust =
  | { mode: 'pinned'; keys: Map<string, string> }
  | { mode: 'chain'; roots: string[] };

export type AppleVerification = {
  version: 'store-verification-v1'; store: 'apple'; environment: StoreEnvironment;
  transactionId: string; originalTransactionId: string; productId: string;
  purchasedAt: number; expiresAt: number | null; autoRenew: boolean | null;
  revoked: boolean; upgraded: boolean; evidenceRef: string; signedAt: number;
};

type Jws = { header: Record<string, unknown>; payload: Record<string, unknown>; signingInput: string; signature: Buffer };
const b64u = (value: string): Buffer => {
  if (!/^[A-Za-z0-9_-]+$/.test(value) || value.length % 4 === 1) throw invalidProof();
  return Buffer.from(value.replace(/-/g, '+').replace(/_/g, '/'), 'base64');
};
function parseJws(jws: string): Jws {
  if (typeof jws !== 'string' || jws.length > 65536) throw invalidProof();
  const parts = jws.split('.'); if (parts.length !== 3) throw invalidProof();
  try {
    const header = JSON.parse(b64u(parts[0]).toString('utf8')) as Record<string, unknown>;
    const payload = JSON.parse(b64u(parts[1]).toString('utf8')) as Record<string, unknown>;
    if (!header || typeof header !== 'object' || Array.isArray(header) || !payload || typeof payload !== 'object' || Array.isArray(payload)) throw new Error();
    return { header, payload, signingInput: parts[0] + '.' + parts[1], signature: b64u(parts[2]) };
  } catch (error) { if (error instanceof ApiError) throw error; throw invalidProof(); }
}
function verifyEs256(signingInput: string, signature: Buffer, key: string | KeyObject) {
  try {
    // JWS carries raw R||S; Node defaults to DER, so the encoding is explicit.
    const keyObject = typeof key === 'string' ? createPublicKey(key) : key;
    const verifier = createVerify('sha256'); verifier.update(signingInput); verifier.end();
    if (!verifier.verify({ key: keyObject, dsaEncoding: 'ieee-p1363' }, signature)) throw invalidProof();
  } catch (error) { if (error instanceof ApiError) throw error; throw invalidProof(); }
}
function fingerprint(cert: X509Certificate): string { return digest(cert.raw); }
function chainKey(x5c: unknown, roots: string[], now: number): KeyObject {
  if (!Array.isArray(x5c) || !x5c.length || x5c.length > 4 || x5c.some(c => typeof c !== 'string' || c.length > 8192)) throw invalidProof();
  let chain: X509Certificate[];
  try { chain = x5c.map(c => new X509Certificate(Buffer.from(c as string, 'base64'))); }
  catch { throw invalidProof(); }
  const trusted = new Set(roots.map(pem => { try { return fingerprint(new X509Certificate(pem)); } catch { throw invalidProof(); } }));
  for (const cert of chain) {
    const since = Date.parse(cert.validFrom), until = Date.parse(cert.validTo);
    if (!Number.isSafeInteger(since) || !Number.isSafeInteger(until) || now < since || now > until) throw invalidProof();
  }
  for (let i = 0; i < chain.length - 1; i++) if (!chain[i].verify(chain[i + 1].publicKey)) throw invalidProof();
  if (!trusted.has(fingerprint(chain[chain.length - 1]))) throw invalidProof();
  return chain[0].publicKey;
}
/** Verify one compact JWS against pinned keys or a certificate chain to a trusted Apple root. */
export function verifyAppleJws(jws: string, trust: AppleTrust, now: number): Record<string, unknown> {
  const { header, payload, signingInput, signature } = parseJws(jws);
  if (header.alg !== 'ES256' || header.crit !== undefined) throw invalidProof();
  if (trust.mode === 'chain') {
    if (header.x5c === undefined) throw new ApiError(422, 'apple_chain_required');
    verifyEs256(signingInput, signature, chainKey(header.x5c, trust.roots, now));
  } else {
    if (typeof header.kid !== 'string' || !trust.keys.has(header.kid)) throw invalidProof();
    verifyEs256(signingInput, signature, trust.keys.get(header.kid)!);
  }
  const signedDate = payload.signedDate;
  if (signedDate !== undefined && (!Number.isSafeInteger(signedDate) || (signedDate as number) > now + 300000)) throw invalidProof();
  return payload;
}
const appleEnvironment = (value: unknown): StoreEnvironment => {
  if (value === 'Sandbox') return 'sandbox'; if (value === 'Production') return 'production'; throw invalidProof();
};
const millis = (value: unknown): number => { if (!Number.isSafeInteger(value) || (value as number) < 0) throw invalidProof(); return value as number; };
const text = (value: unknown, max: number): string => { if (typeof value !== 'string' || !value || value.length > max) throw invalidProof(); return value; };

/** Verify a StoreKit signed transaction. The environment is read from the verified
 * payload, never from a client flag; a client hint that disagrees is rejected. */
export function verifyAppleTransaction(input: { signedTransaction: unknown; claimedEnvironment?: unknown }, config: AppleConfig, trust: AppleTrust, now: number): AppleVerification {
  if (typeof input.signedTransaction !== 'string') throw invalidProof();
  const payload = verifyAppleJws(input.signedTransaction, trust, now);
  const environment = appleEnvironment(payload.environment);
  if (input.claimedEnvironment !== undefined && input.claimedEnvironment !== environment) throw new ApiError(422, 'wrong_store_environment');
  if (text(payload.bundleId, 256) !== config.bundleId || text(payload.productId, 256) !== config.productId) throw new ApiError(422, 'unknown_store_product');
  return {
    version: STORE_VERIFICATION_VERSION, store: 'apple', environment,
    transactionId: text(payload.transactionId, 128), originalTransactionId: text(payload.originalTransactionId, 128), productId: config.productId,
    purchasedAt: millis(payload.purchaseDate), expiresAt: payload.expiresDate === undefined ? null : millis(payload.expiresDate),
    autoRenew: null, revoked: payload.revocationDate !== undefined && payload.revocationDate !== null,
    upgraded: payload.isUpgraded === true, evidenceRef: digest(input.signedTransaction),
    signedAt: typeof payload.signedDate === 'number' ? payload.signedDate : now,
  };
}
export function applyAppleRenewal(verification: AppleVerification, signedRenewalInfo: unknown, config: AppleConfig, trust: AppleTrust, now: number): AppleVerification {
  if (signedRenewalInfo === undefined) return verification;
  if (typeof signedRenewalInfo !== 'string') throw invalidProof();
  const renewal = verifyAppleJws(signedRenewalInfo, trust, now);
  if (appleEnvironment(renewal.environment) !== verification.environment) throw new ApiError(422, 'wrong_store_environment');
  if (text(renewal.originalTransactionId, 128) !== verification.originalTransactionId) throw invalidProof();
  const status = renewal.autoRenewStatus;
  if (status !== 0 && status !== 1) throw invalidProof();
  return { ...verification, autoRenew: status === 1 };
}

export type AppleNotification = {
  notificationUUID: string; notificationType: string; subtype: string | null;
  transaction: AppleVerification; autoRenew: boolean | null; testFlight: boolean;
};
/** App Store Server Notifications V2. The signature, bundle/product binding and
 * environment are verified; the caller deduplicates by notificationUUID and then
 * reconciles against the App Store Server API — the notification is a hint. */
export function verifyAppleNotification(input: { signedPayload: unknown }, config: AppleConfig, trust: AppleTrust, now: number): AppleNotification {
  if (typeof input.signedPayload !== 'string') throw new ApiError(401, 'notification_rejected');
  let payload: Record<string, unknown>;
  try { payload = verifyAppleJws(input.signedPayload, trust, now); }
  catch { throw new ApiError(401, 'notification_rejected'); }
  let environment: StoreEnvironment;
  try { environment = appleEnvironment(payload.environment); }
  catch { throw new ApiError(401, 'notification_rejected'); }
  const data = payload.data as Record<string, unknown> | undefined;
  if (!data || typeof data !== 'object') throw new ApiError(401, 'notification_rejected');
  if (data.environment !== undefined && data.environment !== payload.environment) throw new ApiError(401, 'notification_rejected');
  if (typeof data.bundleId === 'string' && data.bundleId !== config.bundleId) throw new ApiError(401, 'notification_rejected');
  let transaction: AppleVerification;
  try {
    transaction = verifyAppleTransaction({ signedTransaction: data.signedTransactionInfo }, config, trust, now);
    if (transaction.environment !== environment) throw new ApiError(422, 'wrong_store_environment');
    transaction = applyAppleRenewal(transaction, data.signedRenewalInfo, config, trust, now);
  } catch { throw new ApiError(401, 'notification_rejected'); }
  if (typeof payload.notificationUUID !== 'string' || !/^[a-f0-9-]{36}$/i.test(payload.notificationUUID) || typeof payload.notificationType !== 'string') throw new ApiError(401, 'notification_rejected');
  return {
    notificationUUID: payload.notificationUUID.toLowerCase(), notificationType: payload.notificationType,
    subtype: typeof payload.subtype === 'string' ? payload.subtype : null,
    transaction, autoRenew: transaction.autoRenew, testFlight: environment === 'sandbox',
  };
}
/** Notification types that change entitlement and therefore require authoritative
 * status retrieval. Informational types are recorded without a store fetch. */
export function appleNotificationNeedsStore(type: string): boolean {
  return ['SUBSCRIBED', 'DID_RENEW', 'DID_FAIL_TO_RENEW', 'GRACE_PERIOD_EXPIRED', 'OFFER_REDEEMED', 'DID_CHANGE_RENEWAL_PREF', 'DID_CHANGE_RENEWAL_STATUS', 'EXPIRED', 'REFUND', 'REVOKE', 'REFUND_DECLINED', 'RENEWAL_EXTENDED', 'RENEWAL_EXTENSION'].includes(type);
}

export type AppleApiFetch = (url: string, token: string) => Promise<{ status: number; body: unknown }>;
const APPLE_HOSTS: Record<StoreEnvironment, string> = { production: 'https://api.storekit.itunes.apple.com', sandbox: 'https://api.storekit-sandbox.itunes.apple.com' };
export function appleApiJwt(issuerId: string, keyId: string, privateKeyPem: string, bundleId: string, now: number): string {
  const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const input = encode({ alg: 'ES256', kid: keyId, typ: 'JWT' }) + '.' + encode({ iss: issuerId, iat: Math.floor(now / 1000), exp: Math.floor(now / 1000) + 3600, aud: 'appstoreconnect-v1', bid: bundleId });
  try {
    const signer = createSign('sha256'); signer.update(input); signer.end();
    return input + '.' + signer.sign({ key: createPrivateKey(privateKeyPem), dsaEncoding: 'ieee-p1363' }).toString('base64url');
  } catch { throw new ApiError(503, 'store_unavailable'); }
}
export class AppleStoreApi {
  private issuerId: string; private keyId: string; private privateKey: string; private bundleId: string;
  private fetchImpl: AppleApiFetch; clock: () => number;
  constructor(auth: { issuerId: string; keyId: string; privateKey: string; bundleId: string }, fetchImpl: AppleApiFetch, clock = Date.now) {
    this.issuerId = auth.issuerId; this.keyId = auth.keyId; this.privateKey = auth.privateKey; this.bundleId = auth.bundleId; this.fetchImpl = fetchImpl; this.clock = clock;
  }
  /** Authoritative subscription status for one verified original transaction. The
   * environment selects the store host explicitly; sandbox and production never mix. */
  async subscriptionStatus(originalTransactionId: string, environment: StoreEnvironment): Promise<unknown> {
    if (!/^[0-9A-Za-z_-]{1,128}$/.test(originalTransactionId)) throw invalidProof();
    const token = appleApiJwt(this.issuerId, this.keyId, this.privateKey, this.bundleId, this.clock());
    const response = await this.fetchImpl(`${APPLE_HOSTS[environment]}/inApps/v1/subscriptions/${originalTransactionId}`, token);
    if (response.status !== 200 || !response.body || typeof response.body !== 'object') throw new ApiError(503, 'store_unavailable');
    return response.body;
  }
}
export type AppleStatusTransaction = { status: number; transaction: AppleVerification };
/** Parse and verify every signed transaction in a status response. */
export function parseAppleStatus(body: unknown, config: AppleConfig, trust: AppleTrust, now: number, environment: StoreEnvironment): AppleStatusTransaction[] {
  const root = body as Record<string, unknown>;
  if (!root || typeof root !== 'object' || !Array.isArray(root.data)) throw new ApiError(503, 'store_unavailable');
  let statusEnvironment: StoreEnvironment;
  try { statusEnvironment = appleEnvironment(root.environment); }
  catch { throw new ApiError(503, 'store_unavailable'); }
  if (statusEnvironment !== environment) throw new ApiError(503, 'store_unavailable');
  const out: AppleStatusTransaction[] = [];
  for (const group of root.data as Record<string, unknown>[]) {
    if (!group || !Array.isArray(group.lastTransactions)) throw new ApiError(503, 'store_unavailable');
    for (const row of group.lastTransactions as Record<string, unknown>[]) {
      if (typeof row.status !== 'number') throw new ApiError(503, 'store_unavailable');
      out.push({ status: row.status, transaction: applyAppleRenewal(verifyAppleTransaction({ signedTransaction: row.signedTransactionInfo }, config, trust, now), row.signedRenewalInfo, config, trust, now) });
    }
  }
  return out;
}
