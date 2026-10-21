import { generateKeyPairSync, createSign } from 'node:crypto';
import { AppleStoreApi, type AppleConfig, type AppleTrust } from '../src/subscriptions-apple.ts';
import { PlayDeveloperApi, type GoogleConfig } from '../src/subscriptions-google.ts';

// Synthetic store identities for tests only. Real product, bundle and package
// identifiers are owner-gated store-console configuration, never committed here.
export const NOW = Date.UTC(2026, 9, 18, 12);
export const MONTH = 30 * 86400000;
export const appleConfig: AppleConfig = { bundleId: 'app.pittech.synthetic', productId: 'app.pittech.synthetic.pro.monthly' };
export const googleConfig: GoogleConfig = { packageName: 'com.pittech.synthetic', productId: 'pittech_pro_monthly_synthetic' };

const appleKeys = generateKeyPairSync('ec', { namedCurve: 'P-256' });
export const applePublicPem = appleKeys.publicKey.export({ type: 'spki', format: 'pem' }).toString();
const applePrivatePem = appleKeys.privateKey.export({ type: 'pkcs8', format: 'pem' }).toString();
export const appleTrust: AppleTrust = { mode: 'pinned', keys: new Map([['synthetic-apple-key', applePublicPem]]) };
export function signApple(payload: Record<string, unknown>, header: Record<string, unknown> = {}): string {
  const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const input = encode({ alg: 'ES256', kid: 'synthetic-apple-key', ...header }) + '.' + encode(payload);
  const signer = createSign('sha256'); signer.update(input); signer.end();
  return input + '.' + signer.sign({ key: appleKeys.privateKey, dsaEncoding: 'ieee-p1363' }).toString('base64url');
}
export function appleTransaction(overrides: Record<string, unknown> = {}, now = NOW): Record<string, unknown> {
  return {
    transactionId: '2000000123456789', originalTransactionId: '2000000111111111',
    bundleId: appleConfig.bundleId, productId: appleConfig.productId,
    purchaseDate: now, originalPurchaseDate: now, expiresDate: now + MONTH,
    environment: 'Production', signedDate: now, storefront: 'USA', storefrontId: '143441', ...overrides,
  };
}
export function appleRenewal(originalTransactionId: string, autoRenew: boolean, now = NOW, environment = 'Production'): Record<string, unknown> {
  return { originalTransactionId, productId: appleConfig.productId, autoRenewStatus: autoRenew ? 1 : 0, environment, signedDate: now };
}
export function appleStatusBody(rows: { status: number; transaction: string; renewal?: string }[], environment = 'Production'): unknown {
  return { environment, bundleId: appleConfig.bundleId, data: [{ subscriptionGroupIdentifier: 'synthetic-pro', lastTransactions: rows.map(r => ({ status: r.status, signedTransactionInfo: r.transaction, ...(r.renewal ? { signedRenewalInfo: r.renewal } : {}) })) }] };
}
export function appleNotificationBody(type: string, tx: string, id: string, now = NOW, environment = 'Production', renewal?: string, subtype?: string): Record<string, unknown> {
  return { notificationType: type, ...(subtype ? { subtype } : {}), notificationUUID: id, version: '2.0', signedDate: now, environment,
    data: { appAppleId: '1234567890', bundleId: appleConfig.bundleId, environment, signedTransactionInfo: tx, ...(renewal ? { signedRenewalInfo: renewal } : {}) } };
}
export type StubApple = { api: AppleStoreApi; calls: { url: string }[]; status: { body: unknown } };
export function stubAppleApi(): StubApple {
  const calls: { url: string }[] = [], status: { body: unknown } = { body: undefined };
  const api = new AppleStoreApi({ issuerId: 'synthetic-issuer', keyId: 'synthetic-key', privateKey: applePrivatePem, bundleId: appleConfig.bundleId },
    async url => { calls.push({ url }); return { status: 200, body: status.body }; });
  return { api, calls, status };
}

const oidcKeys = generateKeyPairSync('rsa', { modulusLength: 2048 });
export const oidcPublicPem = oidcKeys.publicKey.export({ type: 'spki', format: 'pem' }).toString();
export const oidcAudience = 'https://pittech.example/rtdn';
export const oidcEmail = 'pubsub-push@synthetic.iam.gserviceaccount.com';
export const oidcPublicJwk = { ...oidcKeys.publicKey.export({ format: 'jwk' }), kid: 'synthetic-oidc-key', alg: 'RS256', use: 'sig' };
export const oidcKeySet = new Map([['synthetic-oidc-key', oidcPublicPem]]);
export function signOidc(overrides: Record<string, unknown> = {}, now = NOW, header: Record<string, unknown> = {}): string {
  const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const input = encode({ alg: 'RS256', kid: 'synthetic-oidc-key', typ: 'JWT', ...header }) + '.' + encode({ iss: 'https://accounts.google.com', aud: oidcAudience, email: oidcEmail, email_verified: true, iat: Math.floor(now / 1000), exp: Math.floor(now / 1000) + 3600, ...overrides });
  const signer = createSign('RSA-SHA256'); signer.update(input); signer.end();
  return input + '.' + signer.sign(oidcKeys.privateKey).toString('base64url');
}
export function googleSubscription(overrides: Record<string, unknown> = {}, now = NOW): Record<string, unknown> {
  return {
    kind: 'androidpublisher#subscriptionPurchaseV2', startTime: new Date(now).toISOString(), regionCode: 'US',
    subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE', latestOrderId: 'GPA.1234-5678-9012-34567',
    acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    lineItems: [{ productId: googleConfig.productId, expiryTime: new Date(now + MONTH).toISOString(), autoRenewingPlan: { autoRenewEnabled: true } }],
    ...overrides,
  };
}
export type StubPlay = { api: PlayDeveloperApi; calls: { url: string; method: string }[]; acknowledged: string[]; acknowledgement: { status: number }; subscription: { body: unknown; status: number } };
export function stubPlayApi(): StubPlay {
  const calls: { url: string; method: string }[] = [], acknowledged: string[] = [], acknowledgement = { status: 200 }, subscription = { body: undefined as unknown, status: 200 };
  const api = new PlayDeveloperApi({ clientEmail: 'synthetic@test.iam.gserviceaccount.com', privateKey: oidcKeys.privateKey.export({ type: 'pkcs8', format: 'pem' }).toString() },
    async (url, init) => {
      calls.push({ url, method: init.method });
      if (url === 'https://oauth2.googleapis.com/token') return { status: 200, body: { access_token: 'synthetic-access-token' } };
      if (url.endsWith(':acknowledge')) { acknowledged.push(url); return { status: acknowledgement.status, body: {} }; }
      return { status: subscription.status, body: subscription.body };
    });
  return { api, calls, acknowledged, acknowledgement, subscription };
}
export function rtdnBody(messageId: string, inner: Record<string, unknown>): Record<string, unknown> {
  return { message: { data: Buffer.from(JSON.stringify(inner)).toString('base64'), messageId, publishTime: new Date(NOW).toISOString() }, subscription: 'projects/synthetic/subscriptions/rtdn' };
}
export function rtdnSubscription(token: string, type: number, now = NOW): Record<string, unknown> {
  return { version: '1.0', packageName: googleConfig.packageName, eventTimeMillis: String(now), subscriptionNotification: { version: '1.0', notificationType: type, purchaseToken: token, subscriptionId: googleConfig.productId } };
}
