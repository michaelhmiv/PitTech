import { randomUUID } from 'node:crypto';
import type { Pool } from 'pg';
import { ApiError, digest } from './core.ts';
import {
  STORE_VERIFICATION_VERSION, invalidProof, verifyAppleTransaction, applyAppleRenewal,
  verifyAppleNotification, appleNotificationNeedsStore, parseAppleStatus,
  type AppleConfig, type AppleTrust, type AppleVerification, type StoreEnvironment,
} from './subscriptions-apple.ts';
import {
  verifyGoogleSubscription, verifyRtdnPush,
  type GoogleConfig,
} from './subscriptions-google.ts';
import type { AppleStoreApi } from './subscriptions-apple.ts';
import type { PlayDeveloperApi } from './subscriptions-google.ts';

export type EntitlementState = 'none' | 'pending' | 'active' | 'grace' | 'retry' | 'paused' | 'expired' | 'cancelled' | 'refunded' | 'revoked';
export type StoreName = 'apple' | 'google';
export const ENTITLEMENT_CACHE_VERSION = 'entitlement-cache-v1';
export const OFFLINE_WINDOW_MS = 7 * 86400000;
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(value);

/** Explicit transition table. Every edge is driven by verified store evidence;
 * anything absent here is rejected and recorded, never applied. */
const TRANSITIONS: Record<EntitlementState, readonly EntitlementState[]> = {
  none: ['pending', 'active', 'paused'],
  pending: ['pending', 'active', 'expired', 'cancelled'],
  active: ['active', 'grace', 'retry', 'paused', 'expired', 'cancelled', 'refunded', 'revoked'],
  grace: ['active', 'grace', 'retry', 'expired', 'cancelled', 'refunded', 'revoked'],
  retry: ['active', 'grace', 'retry', 'expired', 'cancelled', 'refunded', 'revoked'],
  paused: ['active', 'paused', 'expired', 'cancelled', 'refunded', 'revoked'],
  cancelled: ['active', 'expired', 'cancelled', 'refunded', 'revoked'],
  expired: ['active', 'expired'],
  refunded: ['active', 'refunded'],
  revoked: ['active', 'revoked'],
};
export type StoreVerdict = {
  state: EntitlementState; expiresAt: number | null; autoRenew: boolean | null;
  eventAt: number; evidenceRef: string; reason: string;
  transactionStore: StoreName; transactionKey: string;
};
export type EntitlementRecord = {
  accountId: string; environment: StoreEnvironment; store: StoreName; state: EntitlementState;
  transactionStore: StoreName | null; transactionKey: string | null;
  expiresAt: number | null; autoRenew: boolean | null;
  lastVerifiedAt: number; appliedEventAt: number;
};
export type TransitionOutcome = { record: EntitlementRecord; applied: boolean; note: string };
const blank = (accountId: string, environment: StoreEnvironment, store: StoreName, now: number): EntitlementRecord =>
  ({ accountId, environment, store, state: 'none', transactionStore: null, transactionKey: null, expiresAt: null, autoRenew: null, lastVerifiedAt: now, appliedEventAt: 0 });

/** Strength ranking for the deterministic multi-binding rule: when one account
 * holds two live store purchases, the stronger binding wins and the weaker is
 * shadowed, never merged and never double-counted. */
export function rankState(state: EntitlementState, expiresAt: number | null, now: number): number {
  switch (state) {
    case 'active': return 5;
    case 'grace': return 4;
    case 'retry': case 'paused': return 3;
    case 'cancelled': return expiresAt !== null && expiresAt > now ? 3 : 0;
    case 'pending': return 1;
    default: return 0;
  }
}
/** Pure transition. Out-of-order verdicts are superseded; table violations are
 * rejected; a weaker verdict from a different binding is shadowed. */
export function transitionEntitlement(current: EntitlementRecord | undefined, verdict: StoreVerdict, context: { accountId: string; environment: StoreEnvironment; store: StoreName }, now: number): TransitionOutcome {
  const base = current ?? blank(context.accountId, context.environment, context.store, now);
  if (current && verdict.eventAt < current.appliedEventAt) return { record: current, applied: false, note: 'superseded' };
  const sameBinding = current !== undefined && verdict.transactionStore === current.transactionStore && verdict.transactionKey === current.transactionKey;
  if (current && !sameBinding && rankState(verdict.state, verdict.expiresAt, now) < rankState(current.state, current.expiresAt, now))
    return { record: current, applied: false, note: 'shadowed-weaker-binding' };
  if (!TRANSITIONS[base.state].includes(verdict.state)) return { record: current ?? { ...base }, applied: false, note: 'transition-rejected' };
  const record: EntitlementRecord = {
    ...base, store: verdict.transactionStore, state: verdict.state, transactionStore: verdict.transactionStore, transactionKey: verdict.transactionKey,
    expiresAt: verdict.expiresAt, autoRenew: verdict.autoRenew, lastVerifiedAt: now, appliedEventAt: Math.max(base.appliedEventAt, verdict.eventAt),
  };
  return { record, applied: verdict.state !== base.state || verdict.expiresAt !== base.expiresAt, note: verdict.state === base.state ? 'refreshed' : 'transition' };
}

export function deriveAppleState(input: { status: number | null; revoked: boolean; upgraded: boolean; expiresAt: number | null; autoRenew: boolean | null }, now: number): EntitlementState {
  if (input.revoked || input.status === 5) return 'revoked';
  if (input.upgraded) return 'expired';
  if (input.expiresAt === null) return 'pending';
  if (input.status === null) {
    if (input.expiresAt <= now) return 'expired';
    return input.autoRenew === false ? 'cancelled' : 'active';
  }
  switch (input.status) {
    case 1: return input.autoRenew === false ? 'cancelled' : 'active';
    case 2: return 'expired';
    case 3: return 'retry';
    case 4: return 'grace';
    default: throw new ApiError(503, 'unsupported_store_state');
  }
}
export function deriveGoogleState(subscriptionState: string, autoRenew: boolean, expiresAt: number, now: number): EntitlementState {
  switch (subscriptionState) {
    case 'SUBSCRIPTION_STATE_ACTIVE': return autoRenew ? 'active' : 'cancelled';
    case 'SUBSCRIPTION_STATE_CANCELED': return expiresAt > now ? 'cancelled' : 'expired';
    case 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD': return 'grace';
    case 'SUBSCRIPTION_STATE_ON_HOLD': return 'retry';
    case 'SUBSCRIPTION_STATE_PAUSED': return 'paused';
    case 'SUBSCRIPTION_STATE_EXPIRED': return 'expired';
    default: throw new ApiError(503, 'unsupported_store_state');
  }
}

export type EntitlementCacheRecord = {
  version: 'entitlement-cache-v1'; environment: StoreEnvironment; verified: boolean;
  state: EntitlementState | 'unknown'; verifiedUntil: string | null;
  lastVerifiedAt: string | null; offlineValidUntil: string | null;
  contractVersion: 'store-verification-v1';
};
/** Versioned cache projection. Unknown (never verified) is distinct from verified
 * not-entitled; paid-through active/cancelled records project to expired once
 * their date passes; recently verified active/grace records carry a bounded
 * offline window for use without connectivity. */
export function projectCache(record: EntitlementRecord | undefined, environment: StoreEnvironment, now: number): EntitlementCacheRecord {
  if (!record) return { version: ENTITLEMENT_CACHE_VERSION, environment, verified: false, state: 'unknown', verifiedUntil: null, lastVerifiedAt: null, offlineValidUntil: null, contractVersion: STORE_VERIFICATION_VERSION };
  let state = record.state;
  if ((state === 'active' || state === 'cancelled') && record.expiresAt !== null && record.expiresAt <= now) state = 'expired';
  const offline = state === 'active' || state === 'grace';
  return {
    version: ENTITLEMENT_CACHE_VERSION, environment, verified: true, state,
    verifiedUntil: record.expiresAt === null ? null : new Date(record.expiresAt).toISOString(),
    lastVerifiedAt: new Date(record.lastVerifiedAt).toISOString(),
    offlineValidUntil: offline ? new Date(record.lastVerifiedAt + OFFLINE_WINDOW_MS).toISOString() : null,
    contractVersion: STORE_VERIFICATION_VERSION,
  };
}

export type StoredTransaction = {
  store: StoreName; environment: StoreEnvironment; transactionKey: string; latestTransaction: string;
  accountId: string; productId: string; purchasedAt: number; expiresAt: number | null; autoRenew: boolean | null;
  revoked: boolean; cancelledMarker: boolean; storeReference: string; evidenceRef: string; released: boolean;
};
export type BindingConflict = {
  id: string; store: StoreName; environment: StoreEnvironment; transactionKey: string;
  holderAccount: string; claimantAccount: string; state: 'open' | 'released' | 'transferred' | 'rejected';
  createdAt: number; resolvedAt: number | null;
};
export interface BillingStore {
  transaction(store: StoreName, environment: StoreEnvironment, key: string): Promise<StoredTransaction | undefined>;
  insertTransaction(tx: StoredTransaction): Promise<boolean>;
  updateTransaction(store: StoreName, environment: StoreEnvironment, key: string, patch: Partial<StoredTransaction>): Promise<void>;
  entitlement(accountId: string, environment: StoreEnvironment): Promise<EntitlementRecord | undefined>;
  saveEntitlement(record: EntitlementRecord): Promise<void>;
  staleEntitlements(before: number, limit: number): Promise<EntitlementRecord[]>;
  eventSeen(eventKey: string): Promise<boolean>;
  recordEvent(eventKey: string, store: StoreName, environment: StoreEnvironment, applied: boolean, note: string, now: number): Promise<void>;
  openConflict(store: StoreName, environment: StoreEnvironment, key: string, claimant: string): Promise<BindingConflict | undefined>;
  openConflictsFor(store: StoreName, environment: StoreEnvironment, key: string): Promise<BindingConflict[]>;
  insertConflict(conflict: BindingConflict): Promise<BindingConflict>;
  resolveConflict(id: string, state: BindingConflict['state'], now: number): Promise<void>;
  recordReconciliation(accountId: string, environment: StoreEnvironment, from: string, to: string, reason: string, now: number): Promise<void>;
}
type DbTransaction = { store: StoreName; environment: StoreEnvironment; transaction_key: string; latest_transaction: string; account_id: string; product_id: string; purchased_at: string; expires_at: string | null; auto_renew: boolean | null; revoked: boolean; cancelled_marker: boolean; store_reference: string; evidence_ref: string; released: boolean };
type DbEntitlement = { account_id: string; environment: StoreEnvironment; store: StoreName; state: EntitlementState; transaction_store: StoreName | null; transaction_key: string | null; expires_at: string | null; auto_renew: boolean | null; last_verified_at: string; applied_event_at: string };
type DbConflict = { id: string; store: StoreName; environment: StoreEnvironment; transaction_key: string; holder_account: string; claimant_account: string; state: BindingConflict['state']; created_at: string; resolved_at: string | null };
const txFromDb = (r: DbTransaction): StoredTransaction => ({ store: r.store, environment: r.environment, transactionKey: r.transaction_key, latestTransaction: r.latest_transaction, accountId: r.account_id, productId: r.product_id, purchasedAt: Number(r.purchased_at), expiresAt: r.expires_at === null ? null : Number(r.expires_at), autoRenew: r.auto_renew, revoked: r.revoked, cancelledMarker: r.cancelled_marker, storeReference: r.store_reference, evidenceRef: r.evidence_ref, released: r.released });
const entFromDb = (r: DbEntitlement): EntitlementRecord => ({ accountId: r.account_id, environment: r.environment, store: r.store, state: r.state, transactionStore: r.transaction_store, transactionKey: r.transaction_key, expiresAt: r.expires_at === null ? null : Number(r.expires_at), autoRenew: r.auto_renew, lastVerifiedAt: Number(r.last_verified_at), appliedEventAt: Number(r.applied_event_at) });
const conflictFromDb = (r: DbConflict): BindingConflict => ({ id: r.id, store: r.store, environment: r.environment, transactionKey: r.transaction_key, holderAccount: r.holder_account, claimantAccount: r.claimant_account, state: r.state, createdAt: Number(r.created_at), resolvedAt: r.resolved_at === null ? null : Number(r.resolved_at) });
export class PostgresBillingStore implements BillingStore {
  private pool: Pool;
  constructor(pool: Pool) { this.pool = pool; }
  async transaction(store: StoreName, environment: StoreEnvironment, key: string) {
    const r = await this.pool.query<DbTransaction>('SELECT * FROM billing.transactions WHERE store=$1 AND environment=$2 AND transaction_key=$3', [store, environment, key]);
    return r.rows[0] ? txFromDb(r.rows[0]) : undefined;
  }
  async insertTransaction(tx: StoredTransaction) {
    const r = await this.pool.query('INSERT INTO billing.transactions(store,environment,transaction_key,latest_transaction,account_id,product_id,purchased_at,expires_at,auto_renew,revoked,cancelled_marker,store_reference,evidence_ref,released,created_at,updated_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$15) ON CONFLICT DO NOTHING RETURNING transaction_key',
      [tx.store, tx.environment, tx.transactionKey, tx.latestTransaction, tx.accountId, tx.productId, tx.purchasedAt, tx.expiresAt, tx.autoRenew, tx.revoked, tx.cancelledMarker, tx.storeReference, tx.evidenceRef, tx.released, Date.now()]);
    return !!r.rowCount;
  }
  async updateTransaction(store: StoreName, environment: StoreEnvironment, key: string, patch: Partial<StoredTransaction>) {
    const fields: string[] = [], values: unknown[] = [];
    const set = (column: string, value: unknown) => { fields.push(`${column}=$${fields.length + 1}`); values.push(value); };
    if (patch.latestTransaction !== undefined) set('latest_transaction', patch.latestTransaction);
    if (patch.accountId !== undefined) set('account_id', patch.accountId);
    if (patch.expiresAt !== undefined) set('expires_at', patch.expiresAt);
    if (patch.autoRenew !== undefined) set('auto_renew', patch.autoRenew);
    if (patch.revoked !== undefined) set('revoked', patch.revoked);
    if (patch.cancelledMarker !== undefined) set('cancelled_marker', patch.cancelledMarker);
    if (patch.storeReference !== undefined) set('store_reference', patch.storeReference);
    if (patch.evidenceRef !== undefined) set('evidence_ref', patch.evidenceRef);
    if (patch.released !== undefined) set('released', patch.released);
    set('updated_at', Date.now());
    await this.pool.query(`UPDATE billing.transactions SET ${fields.join(',')} WHERE store=$${fields.length + 1} AND environment=$${fields.length + 2} AND transaction_key=$${fields.length + 3}`, [...values, store, environment, key]);
  }
  async entitlement(accountId: string, environment: StoreEnvironment) {
    const r = await this.pool.query<DbEntitlement>('SELECT * FROM billing.entitlements WHERE account_id=$1 AND environment=$2', [accountId, environment]);
    return r.rows[0] ? entFromDb(r.rows[0]) : undefined;
  }
  async saveEntitlement(record: EntitlementRecord) {
    await this.pool.query(`INSERT INTO billing.entitlements(account_id,environment,store,state,transaction_store,transaction_key,expires_at,auto_renew,last_verified_at,applied_event_at,created_at,updated_at)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$11) ON CONFLICT(account_id,environment) DO UPDATE SET store=$3,state=$4,transaction_store=$5,transaction_key=$6,expires_at=$7,auto_renew=$8,last_verified_at=$9,applied_event_at=$10,updated_at=$11`,
      [record.accountId, record.environment, record.store, record.state, record.transactionStore, record.transactionKey, record.expiresAt, record.autoRenew, record.lastVerifiedAt, record.appliedEventAt, Date.now()]);
  }
  async staleEntitlements(before: number, limit: number) {
    const r = await this.pool.query<DbEntitlement>("SELECT * FROM billing.entitlements WHERE state IN ('active','grace','retry','paused','cancelled','pending') AND last_verified_at<$1 ORDER BY last_verified_at LIMIT $2", [before, Math.max(1, Math.min(50, limit))]);
    return r.rows.map(entFromDb);
  }
  async eventSeen(eventKey: string) { return !!((await this.pool.query('SELECT 1 FROM billing.store_events WHERE event_key=$1', [eventKey])).rowCount); }
  async recordEvent(eventKey: string, store: StoreName, environment: StoreEnvironment, applied: boolean, note: string, now: number) {
    await this.pool.query('INSERT INTO billing.store_events VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT DO NOTHING', [eventKey, store, environment, now, applied, note.slice(0, 256)]);
  }
  async openConflict(store: StoreName, environment: StoreEnvironment, key: string, claimant: string) {
    const r = await this.pool.query<DbConflict>("SELECT * FROM billing.binding_conflicts WHERE store=$1 AND environment=$2 AND transaction_key=$3 AND claimant_account=$4 AND state='open'", [store, environment, key, claimant]);
    return r.rows[0] ? conflictFromDb(r.rows[0]) : undefined;
  }
  async openConflictsFor(store: StoreName, environment: StoreEnvironment, key: string) {
    const r = await this.pool.query<DbConflict>("SELECT * FROM billing.binding_conflicts WHERE store=$1 AND environment=$2 AND transaction_key=$3 AND state='open'", [store, environment, key]);
    return r.rows.map(conflictFromDb);
  }
  async insertConflict(conflict: BindingConflict) {
    const existing = await this.openConflict(conflict.store, conflict.environment, conflict.transactionKey, conflict.claimantAccount);
    if (existing) return existing;
    await this.pool.query('INSERT INTO billing.binding_conflicts(id,store,environment,transaction_key,holder_account,claimant_account,state,created_at,resolved_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)',
      [conflict.id, conflict.store, conflict.environment, conflict.transactionKey, conflict.holderAccount, conflict.claimantAccount, conflict.state, conflict.createdAt, conflict.resolvedAt]);
    return conflict;
  }
  async resolveConflict(id: string, state: BindingConflict['state'], now: number) {
    await this.pool.query("UPDATE billing.binding_conflicts SET state=$2,resolved_at=$3 WHERE id=$1 AND state='open'", [id, state, now]);
  }
  async recordReconciliation(accountId: string, environment: StoreEnvironment, from: string, to: string, reason: string, now: number) {
    await this.pool.query('INSERT INTO billing.reconciliation_events VALUES($1,$2,$3,$4,$5,$6,$7)', [randomUUID(), accountId, environment, from.slice(0, 32), to.slice(0, 32), reason.slice(0, 256), now]);
  }
}

export type SubscriptionDeps = {
  apple: { config: AppleConfig; trust: AppleTrust; api?: AppleStoreApi };
  google: { config: GoogleConfig; api?: PlayDeveloperApi; oidcKeys: Map<string, string>; oidcAudience: string };
};
export type VerifyInput =
  | { store: 'apple'; signedTransaction: unknown; signedRenewalInfo?: unknown; claimedEnvironment?: unknown }
  | { store: 'google'; purchaseToken: unknown };
export type VerifyOutcome = {
  cache: EntitlementCacheRecord; applied: boolean; note: string;
  transaction: { store: StoreName; environment: StoreEnvironment; transactionKey: string; evidenceRef: string };
  conflict?: { id: string; state: BindingConflict['state']; recovery: string };
};
const recoveryText = 'This store purchase is already bound to a different PitTech account. Sign in with that account to use it there, or ask its holder to release the purchase and then retry; nothing was changed on either account.';

export class Subscriptions {
  private store: BillingStore; private deps: SubscriptionDeps; clock: () => number;
  constructor(store: BillingStore, deps: SubscriptionDeps, clock = Date.now) { this.store = store; this.deps = deps; this.clock = clock; }
  private async applyVerdict(accountId: string, environment: StoreEnvironment, store: StoreName, verdict: StoreVerdict, reason: string): Promise<{ cache: EntitlementCacheRecord; applied: boolean; note: string }> {
    const now = this.clock(), current = await this.store.entitlement(accountId, environment);
    const outcome = transitionEntitlement(current, verdict, { accountId, environment, store }, now);
    if (!current && !outcome.applied) {
      const cache = projectCache(undefined, environment, now);
      return { cache, applied: false, note: outcome.note };
    }
    if (outcome.applied || !current) await this.store.saveEntitlement(outcome.record);
    await this.store.recordReconciliation(accountId, environment, current?.state ?? 'unknown', outcome.record.state, `${reason}:${outcome.note}:${verdict.evidenceRef.slice(0, 16)}`, now);
    return { cache: projectCache(outcome.record, environment, now), applied: outcome.applied, note: outcome.note };
  }
  private async bind(accountId: string, tx: StoredTransaction): Promise<{ holder: StoredTransaction; transferred: boolean; created: boolean }> {
    const existing = await this.store.transaction(tx.store, tx.environment, tx.transactionKey);
    if (!existing) { await this.store.insertTransaction(tx); return { holder: tx, transferred: false, created: true }; }
    if (existing.accountId === accountId && !existing.released) return { holder: existing, transferred: false, created: false };
    if (existing.accountId !== accountId && !existing.released) {
      // Deterministic precedence: a live holder keeps the binding and the
      // claimant gets an explicit conflict. A binding whose holder entitlement
      // is terminal (or never formed) may be claimed through fresh verification.
      const holderEntitlement = await this.store.entitlement(existing.accountId, existing.environment);
      const dead = !holderEntitlement || (holderEntitlement.transactionStore === existing.store && holderEntitlement.transactionKey === existing.transactionKey && (holderEntitlement.state === 'expired' || holderEntitlement.state === 'refunded' || holderEntitlement.state === 'revoked'));
      if (!dead) return { holder: existing, transferred: false, created: false };
      await this.store.updateTransaction(tx.store, tx.environment, tx.transactionKey, { accountId, released: false });
    } else {
      await this.store.updateTransaction(tx.store, tx.environment, tx.transactionKey, { accountId, released: false });
    }
    await this.store.updateTransaction(tx.store, tx.environment, tx.transactionKey, { latestTransaction: tx.latestTransaction, expiresAt: tx.expiresAt, autoRenew: tx.autoRenew, revoked: tx.revoked, cancelledMarker: tx.cancelledMarker, storeReference: tx.storeReference, evidenceRef: tx.evidenceRef });
    const conflict = await this.store.openConflict(tx.store, tx.environment, tx.transactionKey, accountId);
    if (conflict) await this.store.resolveConflict(conflict.id, 'transferred', this.clock());
    return { holder: { ...existing, accountId, released: false }, transferred: existing.accountId !== accountId, created: false };
  }
  private async conflictReceipt(tx: StoredTransaction, claimant: string): Promise<VerifyOutcome['conflict']> {
    const now = this.clock();
    const conflict = await this.store.insertConflict({ id: randomUUID(), store: tx.store, environment: tx.environment, transactionKey: tx.transactionKey, holderAccount: tx.accountId, claimantAccount: claimant, state: 'open', createdAt: now, resolvedAt: null });
    await this.store.recordReconciliation(claimant, tx.environment, 'unknown', 'unknown', `binding-conflict:${conflict.id.slice(0, 8)}`, now);
    return { id: conflict.id, state: conflict.state, recovery: recoveryText };
  }
  /** Verify a client-submitted purchase proof and bind it to the verified account.
   * The environment is resolved from verified store evidence, never trusted from
   * the client; the same store transaction applied twice never double-extends. */
  async verifyPurchase(accountId: string, input: VerifyInput): Promise<VerifyOutcome> {
    if (!uuid(accountId)) throw new ApiError(401, 'authentication_required');
    const now = this.clock();
    if (input.store === 'apple') {
      const { config, trust } = this.deps.apple;
      let verification: AppleVerification = verifyAppleTransaction(input, config, trust, now);
      verification = applyAppleRenewal(verification, input.signedRenewalInfo, config, trust, now);
      const key = verification.originalTransactionId;
      const tx: StoredTransaction = { store: 'apple', environment: verification.environment, transactionKey: key, latestTransaction: verification.transactionId, accountId, productId: config.productId, purchasedAt: verification.purchasedAt, expiresAt: verification.expiresAt, autoRenew: verification.autoRenew, revoked: verification.revoked, cancelledMarker: verification.autoRenew === false, storeReference: key, evidenceRef: verification.evidenceRef, released: false };
      const { holder, transferred, created } = await this.bind(accountId, tx);
      if (holder.accountId !== accountId) {
        const cache = projectCache(await this.store.entitlement(accountId, verification.environment), verification.environment, now);
        return { cache, applied: false, note: 'binding-conflict', transaction: { store: 'apple', environment: verification.environment, transactionKey: key, evidenceRef: verification.evidenceRef }, conflict: await this.conflictReceipt(holder, accountId) };
      }
      // Deduplicate by store transaction identity: a re-signed copy of the same
      // store transaction (reinstall restore, re-fetched receipt) must not
      // double-extend entitlement. New transactionIds in the lineage renew.
      if (!transferred && !created && holder.latestTransaction === verification.transactionId) {
        const cache = projectCache(await this.store.entitlement(accountId, verification.environment), verification.environment, now);
        return { cache, applied: false, note: 'duplicate-transaction', transaction: { store: 'apple', environment: verification.environment, transactionKey: key, evidenceRef: verification.evidenceRef } };
      }
      if (!transferred && !created) await this.store.updateTransaction('apple', verification.environment, key, { latestTransaction: verification.transactionId, expiresAt: verification.expiresAt, autoRenew: verification.autoRenew, revoked: verification.revoked, cancelledMarker: verification.autoRenew === false, evidenceRef: verification.evidenceRef });
      const state = deriveAppleState({ status: null, revoked: verification.revoked, upgraded: verification.upgraded, expiresAt: verification.expiresAt, autoRenew: verification.autoRenew }, now);
      const applied = await this.applyVerdict(accountId, verification.environment, 'apple',
        { state, expiresAt: verification.expiresAt, autoRenew: verification.autoRenew, eventAt: verification.purchasedAt, evidenceRef: verification.evidenceRef, reason: 'initial-verification', transactionStore: 'apple', transactionKey: key }, transferred ? 'rebind-transfer' : 'initial-verification');
      return { ...applied, transaction: { store: 'apple', environment: verification.environment, transactionKey: key, evidenceRef: verification.evidenceRef } };
    }
    const api = this.deps.google.api;
    if (!api) throw new ApiError(503, 'store_unavailable');
    if (typeof input.purchaseToken !== 'string' || !input.purchaseToken || input.purchaseToken.length > 4096) throw invalidProof();
    const body = await api.subscription(input.purchaseToken, this.deps.google.config.packageName);
    const verification = verifyGoogleSubscription(body, input.purchaseToken, this.deps.google.config);
    if (!verification.acknowledged) { await api.acknowledge(input.purchaseToken, this.deps.google.config.packageName); verification.acknowledged = true; }
    const linked = (body as Record<string, unknown>).linkedPurchaseToken;
    if (typeof linked === 'string' && linked && linked !== input.purchaseToken) {
      const prior = await this.store.transaction('google', verification.environment, digest(linked));
      if (prior && prior.accountId === accountId && !prior.released) await this.store.updateTransaction('google', verification.environment, prior.transactionKey, { released: true });
      if (prior && prior.accountId !== accountId && !prior.released) {
        const cache = projectCache(await this.store.entitlement(accountId, verification.environment), verification.environment, now);
        return { cache, applied: false, note: 'binding-conflict', transaction: { store: 'google', environment: verification.environment, transactionKey: verification.purchaseTokenRef, evidenceRef: verification.evidenceRef }, conflict: await this.conflictReceipt(prior, accountId) };
      }
    }
    const tx: StoredTransaction = { store: 'google', environment: verification.environment, transactionKey: verification.purchaseTokenRef, latestTransaction: verification.orderId, accountId, productId: verification.productId, purchasedAt: verification.purchasedAt, expiresAt: verification.expiresAt, autoRenew: verification.autoRenew, revoked: false, cancelledMarker: verification.subscriptionState === 'SUBSCRIPTION_STATE_CANCELED', storeReference: verification.storeReference, evidenceRef: verification.evidenceRef, released: false };
    const { holder, transferred, created } = await this.bind(accountId, tx);
    if (holder.accountId !== accountId) {
      const cache = projectCache(await this.store.entitlement(accountId, verification.environment), verification.environment, now);
      return { cache, applied: false, note: 'binding-conflict', transaction: { store: 'google', environment: verification.environment, transactionKey: tx.transactionKey, evidenceRef: verification.evidenceRef }, conflict: await this.conflictReceipt(holder, accountId) };
    }
    // Same store transaction re-verified: dedupe by purchase-token identity, not
    // signature bytes (a re-fetched receipt re-signs the same purchase).
    if (!transferred && !created && holder.latestTransaction === verification.orderId) {
      const cache = projectCache(await this.store.entitlement(accountId, verification.environment), verification.environment, now);
      return { cache, applied: false, note: 'duplicate-transaction', transaction: { store: 'google', environment: verification.environment, transactionKey: tx.transactionKey, evidenceRef: verification.evidenceRef } };
    }
    if (!transferred && !created) await this.store.updateTransaction('google', verification.environment, tx.transactionKey, { latestTransaction: verification.orderId, expiresAt: verification.expiresAt, autoRenew: verification.autoRenew, cancelledMarker: tx.cancelledMarker, evidenceRef: verification.evidenceRef });
    const state = deriveGoogleState(verification.subscriptionState, verification.autoRenew, verification.expiresAt, now);
    const applied = await this.applyVerdict(accountId, verification.environment, 'google',
      { state, expiresAt: verification.expiresAt, autoRenew: verification.autoRenew, eventAt: verification.purchasedAt, evidenceRef: verification.evidenceRef, reason: 'initial-verification', transactionStore: 'google', transactionKey: tx.transactionKey }, transferred ? 'rebind-transfer' : 'initial-verification');
    return { ...applied, transaction: { store: 'google', environment: verification.environment, transactionKey: tx.transactionKey, evidenceRef: verification.evidenceRef } };
  }
  /** Restore re-verifies each submitted proof with the store and re-establishes
   * the same entitlement on the verified account, on either platform. Proofs
   * bound elsewhere return explicit conflict receipts, never silent loss. */
  async restore(accountId: string, proofs: VerifyInput[]): Promise<VerifyOutcome[]> {
    if (!Array.isArray(proofs) || !proofs.length || proofs.length > 10) throw new ApiError(400, 'restore_invalid');
    const outcomes: VerifyOutcome[] = [];
    for (const proof of proofs) outcomes.push(await this.verifyPurchase(accountId, proof));
    return outcomes;
  }
  /** The holder releases its binding while signed in; open claimants may then
   * claim the purchase through fresh store verification. */
  async releaseBinding(accountId: string, ref: { store: unknown; environment: unknown; transactionKey: unknown }): Promise<{ released: boolean }> {
    if (!uuid(accountId)) throw new ApiError(401, 'authentication_required');
    if ((ref.store !== 'apple' && ref.store !== 'google') || (ref.environment !== 'sandbox' && ref.environment !== 'production') || typeof ref.transactionKey !== 'string' || !ref.transactionKey) throw new ApiError(400, 'rebind_invalid');
    const tx = await this.store.transaction(ref.store, ref.environment, ref.transactionKey);
    if (!tx || tx.accountId !== accountId || tx.released) throw new ApiError(404, 'unknown_store_purchase');
    const now = this.clock();
    await this.store.updateTransaction(ref.store, ref.environment, ref.transactionKey, { released: true });
    for (const conflict of await this.store.openConflictsFor(ref.store, ref.environment, ref.transactionKey)) await this.store.resolveConflict(conflict.id, 'released', now);
    // The holder gave the purchase away: a live entitlement derived from this
    // binding ends here rather than lingering as a stale record. Reconcile also
    // converges this if a release ever lands without the downgrade.
    const record = await this.store.entitlement(accountId, ref.environment);
    if (record && record.transactionStore === ref.store && record.transactionKey === ref.transactionKey &&
        (record.state === 'pending' || record.state === 'active' || record.state === 'grace' || record.state === 'retry' || record.state === 'paused' || record.state === 'cancelled'))
      await this.applyVerdict(accountId, ref.environment, ref.store, { state: 'expired', expiresAt: record.expiresAt, autoRenew: record.autoRenew, eventAt: now, evidenceRef: tx.evidenceRef, reason: 'binding-released', transactionStore: ref.store, transactionKey: ref.transactionKey }, 'binding-released');
    await this.store.recordReconciliation(accountId, ref.environment, 'unknown', 'unknown', `binding-released:${digest(ref.transactionKey).slice(0, 16)}`, this.clock());
    return { released: true };
  }
  async cache(accountId: string, environment: StoreEnvironment): Promise<EntitlementCacheRecord> {
    if (!uuid(accountId)) throw new ApiError(401, 'authentication_required');
    return projectCache(await this.store.entitlement(accountId, environment), environment, this.clock());
  }
  private async reconcileApple(holder: StoredTransaction, hint: { notificationType: string; eventAt: number; transactionId: string }): Promise<{ applied: boolean; note: string }> {
    const api = this.deps.apple.api;
    if (!api) throw new ApiError(503, 'store_unavailable');
    const body = await api.subscriptionStatus(holder.transactionKey, holder.environment);
    const rows = parseAppleStatus(body, this.deps.apple.config, this.deps.apple.trust, this.clock(), holder.environment);
    const latest = rows.filter(r => r.transaction.originalTransactionId === holder.transactionKey).sort((a, b) => b.transaction.signedAt - a.transaction.signedAt)[0] ?? rows.sort((a, b) => b.transaction.signedAt - a.transaction.signedAt)[0];
    if (!latest) throw new ApiError(503, 'store_unavailable');
    await this.store.updateTransaction('apple', holder.environment, holder.transactionKey, { latestTransaction: latest.transaction.transactionId, expiresAt: latest.transaction.expiresAt, autoRenew: latest.transaction.autoRenew, revoked: latest.transaction.revoked, cancelledMarker: latest.transaction.autoRenew === false, evidenceRef: latest.transaction.evidenceRef });
    let state = deriveAppleState({ status: latest.status, revoked: latest.transaction.revoked, upgraded: latest.transaction.upgraded, expiresAt: latest.transaction.expiresAt, autoRenew: latest.transaction.autoRenew }, this.clock());
    const sameLineage = latest.transaction.transactionId === hint.transactionId;
    if (hint.notificationType === 'REFUND' && sameLineage && (state === 'active' || state === 'grace' || state === 'expired')) state = 'refunded';
    if (hint.notificationType === 'REVOKE' && sameLineage && state !== 'active') state = 'revoked';
    return this.applyVerdict(holder.accountId, holder.environment, 'apple',
      { state, expiresAt: latest.transaction.expiresAt, autoRenew: latest.transaction.autoRenew, eventAt: Math.max(hint.eventAt, latest.transaction.signedAt), evidenceRef: latest.transaction.evidenceRef, reason: 'notification', transactionStore: 'apple', transactionKey: holder.transactionKey }, 'notification');
  }
  /** App Store Server Notifications V2. Verifies the signed payload, deduplicates
   * by notification UUID, then reconciles against the App Store Server API. */
  async appleNotification(signedPayload: unknown): Promise<{ outcome: 'applied' | 'deduped' | 'recorded'; note: string }> {
    const now = this.clock();
    const notification = verifyAppleNotification({ signedPayload }, this.deps.apple.config, this.deps.apple.trust, now);
    const key = `apple:${notification.notificationUUID}`;
    if (await this.store.eventSeen(key)) return { outcome: 'deduped', note: 'replay' };
    const environment = notification.transaction.environment;
    if (!appleNotificationNeedsStore(notification.notificationType)) {
      await this.store.recordEvent(key, 'apple', environment, false, `informational:${notification.notificationType}`, now);
      return { outcome: 'recorded', note: 'informational' };
    }
    const holder = await this.store.transaction('apple', environment, notification.transaction.originalTransactionId);
    if (!holder || holder.released) {
      await this.store.recordEvent(key, 'apple', environment, false, 'unknown-transaction', now);
      return { outcome: 'recorded', note: 'unknown-transaction' };
    }
    const result = await this.reconcileApple(holder, { notificationType: notification.notificationType, eventAt: notification.transaction.signedAt, transactionId: notification.transaction.transactionId });
    await this.store.recordEvent(key, 'apple', environment, result.applied, `reconciled:${result.note}`, now);
    return { outcome: 'applied', note: result.note };
  }
  private async reconcileGoogle(holder: StoredTransaction, hint: { notificationType: number; eventAt: number }): Promise<{ applied: boolean; note: string }> {
    const api = this.deps.google.api;
    if (!api) throw new ApiError(503, 'store_unavailable');
    const body = await api.subscription(holder.storeReference, this.deps.google.config.packageName);
    const verification = verifyGoogleSubscription(body, holder.storeReference, this.deps.google.config);
    if (!verification.acknowledged) { await api.acknowledge(holder.storeReference, this.deps.google.config.packageName); verification.acknowledged = true; }
    await this.store.updateTransaction('google', holder.environment, holder.transactionKey, { latestTransaction: verification.orderId, expiresAt: verification.expiresAt, autoRenew: verification.autoRenew, cancelledMarker: verification.subscriptionState === 'SUBSCRIPTION_STATE_CANCELED', evidenceRef: verification.evidenceRef });
    let state = deriveGoogleState(verification.subscriptionState, verification.autoRenew, verification.expiresAt, this.clock());
    if (hint.notificationType === 12 && state !== 'active' && state !== 'grace') state = 'revoked';
    return this.applyVerdict(holder.accountId, holder.environment, 'google',
      { state, expiresAt: verification.expiresAt, autoRenew: verification.autoRenew, eventAt: hint.eventAt, evidenceRef: verification.evidenceRef, reason: 'notification', transactionStore: 'google', transactionKey: holder.transactionKey }, 'notification');
  }
  /** Play RTDN. Authenticates the push, deduplicates by message ID, and
   * reconciles against the Play API. The HTTP layer answers 200 as the Play
   * acknowledgment once this resolves. */
  async rtdnPush(body: unknown, authorization: string | undefined): Promise<{ outcome: 'applied' | 'deduped' | 'recorded'; note: string }> {
    const now = this.clock();
    const intent = verifyRtdnPush(body, authorization, this.deps.google.oidcKeys, this.deps.google.oidcAudience, this.deps.google.config, now);
    const key = `google:${intent.messageId}`;
    if (await this.store.eventSeen(key)) return { outcome: 'deduped', note: 'replay' };
    if (intent.kind === 'test') {
      await this.store.recordEvent(key, 'google', 'production', false, 'test-delivery', now);
      return { outcome: 'recorded', note: 'test-delivery' };
    }
    const tokenRef = digest(intent.purchaseToken);
    const holder = (await this.store.transaction('google', 'production', tokenRef)) ?? (await this.store.transaction('google', 'sandbox', tokenRef));
    if (!holder || holder.released) {
      await this.store.recordEvent(key, 'google', 'production', false, 'unknown-transaction', now);
      return { outcome: 'recorded', note: 'unknown-transaction' };
    }
    const result = await this.reconcileGoogle(holder, { notificationType: intent.notificationType, eventAt: intent.eventAt });
    await this.store.recordEvent(key, 'google', holder.environment, result.applied, `reconciled:${result.note}`, now);
    return { outcome: 'applied', note: result.note };
  }
  /** Reconcile one account against the store: re-fetch authoritative state for
   * its bound purchase and converge the cache. Conflicts resolve toward the
   * store with a recorded reconciliation event. */
  async reconcileAccount(accountId: string, environment: StoreEnvironment): Promise<EntitlementCacheRecord> {
    if (!uuid(accountId)) throw new ApiError(401, 'authentication_required');
    const record = await this.store.entitlement(accountId, environment);
    if (!record || !record.transactionStore || !record.transactionKey) return projectCache(record, environment, this.clock());
    const holder = await this.store.transaction(record.transactionStore, environment, record.transactionKey);
    if (!holder || holder.released || holder.accountId !== accountId) {
      if (record.state === 'pending' || record.state === 'active' || record.state === 'grace' || record.state === 'retry' || record.state === 'paused' || record.state === 'cancelled')
        await this.applyVerdict(accountId, environment, record.transactionStore, { state: 'expired', expiresAt: record.expiresAt, autoRenew: record.autoRenew, eventAt: this.clock(), evidenceRef: 'binding-lost', reason: 'binding-lost', transactionStore: record.transactionStore, transactionKey: record.transactionKey }, 'reconciliation');
      return projectCache(await this.store.entitlement(accountId, environment), environment, this.clock());
    }
    if (holder.store === 'apple') await this.reconcileApple(holder, { notificationType: 'RECONCILE', eventAt: this.clock(), transactionId: holder.latestTransaction });
    else await this.reconcileGoogle(holder, { notificationType: 0, eventAt: this.clock() });
    return projectCache(await this.store.entitlement(accountId, environment), environment, this.clock());
  }
  /** Bounded gap polling for missed notifications: re-verify stale live
   * entitlements against the store, oldest first. */
  async reconcileStale(maxAgeMs: number, limit: number): Promise<number> {
    const now = this.clock();
    let reconciled = 0;
    for (const record of await this.store.staleEntitlements(now - maxAgeMs, limit)) {
      try { await this.reconcileAccount(record.accountId, record.environment); reconciled++; }
      catch { /* One unreachable purchase never blocks the sweep; retry next run. */ }
    }
    return reconciled;
  }
}
