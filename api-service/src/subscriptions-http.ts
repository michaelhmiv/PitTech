import type { IncomingMessage, ServerResponse } from 'node:http';
import { ApiError } from './core.ts';
import type { Authentication } from './auth.ts';
import type { Subscriptions, VerifyInput } from './subscriptions.ts';
import type { StoreEnvironment } from './subscriptions-apple.ts';

export type BillingRoute = '/v1/me/entitlement' | '/v1/me/purchases/verify' | '/v1/me/purchases/restore' | '/v1/me/purchases/release' | '/v1/me/entitlement/reconcile' | '/v1/billing/apple/notifications' | '/v1/billing/google/notifications';
export function billingRoute(url: string, method: string): BillingRoute | undefined {
  const path = url.split('?', 1)[0];
  if (path === '/v1/me/entitlement' && method === 'GET') return '/v1/me/entitlement';
  if (path === '/v1/me/purchases/verify' && method === 'POST') return '/v1/me/purchases/verify';
  if (path === '/v1/me/purchases/restore' && method === 'POST') return '/v1/me/purchases/restore';
  if (path === '/v1/me/purchases/release' && method === 'POST') return '/v1/me/purchases/release';
  if (path === '/v1/me/entitlement/reconcile' && method === 'POST') return '/v1/me/entitlement/reconcile';
  if (path === '/v1/billing/apple/notifications' && method === 'POST') return '/v1/billing/apple/notifications';
  if (path === '/v1/billing/google/notifications' && method === 'POST') return '/v1/billing/google/notifications';
}
async function body(req: IncomingMessage, limit: number) {
  if (req.headers['content-type'] !== 'application/json' || ![undefined, 'identity'].includes(req.headers['content-encoding'])) throw new ApiError(415, 'json_required');
  const declared = req.headers['content-length'];
  if (declared !== undefined && (!/^[0-9]+$/.test(declared) || Number(declared) > limit)) throw new ApiError(413, 'request_too_large');
  const chunks: Buffer[] = []; let size = 0;
  for await (const chunk of req) { size += chunk.length; if (size > limit) throw new ApiError(413, 'request_too_large'); chunks.push(Buffer.from(chunk)); }
  if (!req.complete) throw new ApiError(400, 'upload_interrupted');
  return Buffer.concat(chunks);
}
async function object(req: IncomingMessage, limit: number): Promise<Record<string, unknown>> {
  const bytes = await body(req, limit);
  try {
    const value: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error();
    return value as Record<string, unknown>;
  } catch { throw new ApiError(400, 'json_required'); }
  finally { bytes.fill(0); }
}
function fields(value: Record<string, unknown>, allowed: string): Record<string, unknown> {
  const keys = new Set(allowed.split(' '));
  if (Object.keys(value).some(key => !keys.has(key))) throw new ApiError(422, 'invalid_billing_request');
  return value;
}
function verifyInput(value: unknown): VerifyInput {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ApiError(422, 'invalid_billing_request');
  const row = fields(value as Record<string, unknown>, 'store signedTransaction signedRenewalInfo claimedEnvironment purchaseToken');
  if (row.store === 'apple') {
    if (typeof row.signedTransaction !== 'string' || !row.signedTransaction || row.signedTransaction.length > 32768) throw new ApiError(422, 'invalid_billing_request');
    if (row.purchaseToken !== undefined) throw new ApiError(422, 'invalid_billing_request');
    if (row.signedRenewalInfo !== undefined && (typeof row.signedRenewalInfo !== 'string' || !row.signedRenewalInfo || row.signedRenewalInfo.length > 32768)) throw new ApiError(422, 'invalid_billing_request');
    if (row.claimedEnvironment !== undefined && row.claimedEnvironment !== 'sandbox' && row.claimedEnvironment !== 'production') throw new ApiError(422, 'invalid_billing_request');
    return { store: 'apple', signedTransaction: row.signedTransaction, signedRenewalInfo: row.signedRenewalInfo, claimedEnvironment: row.claimedEnvironment };
  }
  if (row.store === 'google') {
    if (typeof row.purchaseToken !== 'string' || !row.purchaseToken || row.purchaseToken.length > 4096) throw new ApiError(422, 'invalid_billing_request');
    if (row.signedTransaction !== undefined || row.signedRenewalInfo !== undefined || row.claimedEnvironment !== undefined) throw new ApiError(422, 'invalid_billing_request');
    return { store: 'google', purchaseToken: row.purchaseToken };
  }
  throw new ApiError(422, 'invalid_billing_request');
}
function queryEnvironment(url: string): StoreEnvironment {
  const query = url.split('?', 2)[1] ?? '';
  if (!query) return 'production';
  const params = new URLSearchParams(query);
  const names = [...params.keys()];
  if (names.length !== 1 || names[0] !== 'environment') throw new ApiError(400, 'invalid_billing_request');
  const value = params.get('environment');
  if (value !== 'sandbox' && value !== 'production') throw new ApiError(400, 'invalid_billing_request');
  return value;
}
/** Billing routes. Purchase and cache routes require a verified account from
 * the auth layer; notification routes verify store signatures instead. No
 * route unlocks from a client Boolean or an unverified callback. */
export async function handleBilling(req: IncomingMessage, res: ServerResponse, route: BillingRoute, auth: Authentication | undefined, subscriptions: Subscriptions, capacity: { claim(): boolean; release(): void }) {
  const json = (status: number, value: unknown) => { if (res.destroyed) throw new ApiError(400, 'request_interrupted'); res.statusCode = status; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(value)); };
  res.setHeader('Cache-Control', 'private, no-store'); res.setHeader('Vary', 'Authorization'); res.setHeader('X-Robots-Tag', 'noindex, nofollow');
  if (!capacity.claim()) throw new ApiError(503, 'billing_busy');
  try {
    if (req.method === 'GET' && (req.headers['transfer-encoding'] !== undefined || Number(req.headers['content-length'] ?? 0) !== 0)) throw new ApiError(400, 'unexpected_body');
    if (route === '/v1/billing/apple/notifications') {
      const value = fields(await object(req, 65536), 'signedPayload');
      json(200, await subscriptions.appleNotification(value.signedPayload)); return;
    }
    if (route === '/v1/billing/google/notifications') {
      const value = fields(await object(req, 65536), 'message subscription');
      json(200, await subscriptions.rtdnPush(value, req.headers.authorization)); return;
    }
    const token = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization ?? '')?.[1];
    if (!auth || !token) throw new ApiError(401, 'authentication_required');
    const user = await auth.user(token);
    if (route === '/v1/me/entitlement') { json(200, await subscriptions.cache(user.id, queryEnvironment(req.url ?? ''))); return; }
    if (route === '/v1/me/entitlement/reconcile') {
      const value = fields(await object(req, 8192), 'environment');
      const environment = value.environment === undefined ? 'production' as StoreEnvironment : value.environment;
      if (environment !== 'sandbox' && environment !== 'production') throw new ApiError(422, 'invalid_billing_request');
      if (res.destroyed) throw new ApiError(400, 'upload_interrupted');
      json(200, await subscriptions.reconcileAccount(user.id, environment)); return;
    }
    if (route === '/v1/me/purchases/verify') {
      const proof = verifyInput(await object(req, 65536));
      if (res.destroyed) throw new ApiError(400, 'upload_interrupted');
      json(200, await subscriptions.verifyPurchase(user.id, proof)); return;
    }
    if (route === '/v1/me/purchases/restore') {
      const value = fields(await object(req, 65536), 'proofs');
      if (!Array.isArray(value.proofs) || !value.proofs.length || value.proofs.length > 10) throw new ApiError(422, 'invalid_billing_request');
      if (res.destroyed) throw new ApiError(400, 'upload_interrupted');
      json(200, { outcomes: await subscriptions.restore(user.id, value.proofs.map(verifyInput)) }); return;
    }
    const value = fields(await object(req, 8192), 'store environment transactionKey');
    json(200, await subscriptions.releaseBinding(user.id, { store: value.store, environment: value.environment, transactionKey: value.transactionKey }));
  } finally { capacity.release(); }
}
