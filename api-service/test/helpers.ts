import { ApiError, LIMITS, type Budgets } from '../src/core.ts';
import { sameUpload, type Pack, type Repository } from '../src/repository.ts';
import type { BillingStore, BindingConflict, EntitlementRecord, StoredTransaction } from '../src/subscriptions.ts';
import type { StoreName } from '../src/subscriptions.ts';
import type { StoreEnvironment } from '../src/subscriptions-apple.ts';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { readZip, writeZip } from '../src/zip.ts';

export const budgets: Budgets = { storageBytes: 1024 ** 3, dailyBytes: 1024 ** 3, dailyUploads: 1000 };
export const golden = (platform: string, kind = 'cook') => new URL(platform === 'native'
  ? `../../app/src/androidTest/assets/cross-platform/native-cook-pack-${kind}-v1.pittech`
  : `../../ios/PitTechTests/Fixtures/android-cook-pack-${kind}-v1.pittech`, import.meta.url);
// Test-only repository. Production always uses Postgres; no in-memory fallback exists.
export class MemoryRepository implements Repository {
  packs = new Map<string, Pack>(); rates = new Map<string, number>(); days = new Map<string, { bytes: number; uploads: number }>();
  async reserve(p: Pack, limits: Budgets) {
    const prior = [...this.packs.values()].find(r => r.ownerHash === p.ownerHash && r.idempotencyKey === p.idempotencyKey);
    if (prior) { sameUpload(prior, p); return { pack: structuredClone(prior), existing: true }; }
    if (this.packs.has(p.code)) return null;
    const day = new Date(p.createdAt).toISOString().slice(0, 10), usage = this.days.get(day) ?? { bytes: 0, uploads: 0 };
    if ([...this.packs.values()].reduce((n, p) => n + p.bytes, 0) + p.bytes > limits.storageBytes || usage.bytes + p.bytes > limits.dailyBytes || usage.uploads >= limits.dailyUploads) throw new ApiError(503, 'relay_budget_exhausted');
    this.days.set(day, { bytes: usage.bytes + p.bytes, uploads: usage.uploads + 1 }); this.packs.set(p.code, structuredClone(p)); return { pack: p, existing: false };
  }
  async activate(code: string) { this.packs.get(code)!.state = 'active'; }
  async get(code: string) { const p = this.packs.get(code); return p ? structuredClone(p) : undefined; }
  async renew(code: string, owner: string, now: number) { const p = this.packs.get(code); if (!p || p.ownerHash !== owner || p.state !== 'active' || p.expiresAt <= now) return undefined; p.expiresAt = now + LIMITS.life; return structuredClone(p); }
  async revoke(code: string, owner: string, now: number) { const p = this.packs.get(code); if (!p || p.ownerHash !== owner || p.state !== 'active' || p.expiresAt <= now) return false; p.state = 'revoked'; return true; }
  async rate(key: string, max: number, _expiresAt: number) { const n = this.rates.get(key) ?? 0; if (n >= max) return false; this.rates.set(key, n + 1); return true; }
  async claim(now: number, limit: number) {
    const p = [...this.packs.values()].filter(p => p.leaseUntil <= now && (p.state === 'revoked' || p.state === 'deleting' || p.expiresAt <= now || p.state === 'uploading' && p.createdAt <= now - LIMITS.uploadLease)).slice(0, limit);
    for (const row of p) { row.state = 'deleting'; row.leaseUntil = now + 300000; row.leaseToken = randomUUID(); }
    return structuredClone(p);
  }
  async deleted(pack: Pack) { if (this.packs.get(pack.code)?.leaseToken === pack.leaseToken) this.packs.delete(pack.code); }
  async prune(_now: number) { }
}
// Test-only billing store. Production always uses Postgres; no in-memory fallback exists.
export class MemoryBillingStore implements BillingStore {
  transactions = new Map<string, StoredTransaction>();
  entitlements = new Map<string, EntitlementRecord>();
  events = new Map<string, { store: StoreName; environment: StoreEnvironment; applied: boolean; note: string }>();
  conflicts = new Map<string, BindingConflict>();
  reconciliations: { accountId: string; environment: StoreEnvironment; from: string; to: string; reason: string; at: number }[] = [];
  async transaction(store: StoreName, environment: StoreEnvironment, key: string) { return structuredClone(this.transactions.get(`${store}/${environment}/${key}`)); }
  async insertTransaction(tx: StoredTransaction) {
    const key = `${tx.store}/${tx.environment}/${tx.transactionKey}`;
    if (this.transactions.has(key)) return false;
    this.transactions.set(key, structuredClone(tx)); return true;
  }
  async updateTransaction(store: StoreName, environment: StoreEnvironment, key: string, patch: Partial<StoredTransaction>) {
    const row = this.transactions.get(`${store}/${environment}/${key}`); if (row) this.transactions.set(`${store}/${environment}/${key}`, { ...row, ...structuredClone(patch) });
  }
  async entitlement(accountId: string, environment: StoreEnvironment) { return structuredClone(this.entitlements.get(`${accountId}/${environment}`)); }
  async saveEntitlement(record: EntitlementRecord) { this.entitlements.set(`${record.accountId}/${record.environment}`, structuredClone(record)); }
  async staleEntitlements(before: number, limit: number) {
    return [...this.entitlements.values()].filter(r => ['active', 'grace', 'retry', 'paused', 'cancelled', 'pending'].includes(r.state) && r.lastVerifiedAt < before).sort((a, b) => a.lastVerifiedAt - b.lastVerifiedAt).slice(0, Math.max(1, Math.min(50, limit))).map(r => structuredClone(r));
  }
  async eventSeen(eventKey: string) { return this.events.has(eventKey); }
  async recordEvent(eventKey: string, store: StoreName, environment: StoreEnvironment, applied: boolean, note: string) { if (!this.events.has(eventKey)) this.events.set(eventKey, { store, environment, applied, note }); }
  async openConflict(store: StoreName, environment: StoreEnvironment, key: string, claimant: string) {
    return structuredClone([...this.conflicts.values()].find(c => c.store === store && c.environment === environment && c.transactionKey === key && c.claimantAccount === claimant && c.state === 'open'));
  }
  async openConflictsFor(store: StoreName, environment: StoreEnvironment, key: string) {
    return structuredClone([...this.conflicts.values()].filter(c => c.store === store && c.environment === environment && c.transactionKey === key && c.state === 'open'));
  }
  async insertConflict(conflict: BindingConflict) {
    const existing = await this.openConflict(conflict.store, conflict.environment, conflict.transactionKey, conflict.claimantAccount);
    if (existing) return existing;
    this.conflicts.set(conflict.id, structuredClone(conflict)); return conflict;
  }
  async resolveConflict(id: string, state: BindingConflict['state'], now: number) {
    const row = this.conflicts.get(id); if (row && row.state === 'open') this.conflicts.set(id, { ...row, state, resolvedAt: now });
  }
  async recordReconciliation(accountId: string, environment: StoreEnvironment, from: string, to: string, reason: string, now: number) {
    this.reconciliations.push({ accountId, environment, from, to, reason, at: now });
  }
}
export async function alter(platform: string, change: (data: Record<string, any>, manifest: Record<string, any>, files: Map<string, Buffer>) => void) {
  const files = await readZip(await readFile(golden(platform))), data = JSON.parse(files.get('data/pittech.json')!.toString()), manifest = JSON.parse(files.get('manifest.json')!.toString());
  change(data, manifest, files); files.set('data/pittech.json', Buffer.from(JSON.stringify(data))); files.set('manifest.json', Buffer.from(JSON.stringify(manifest))); return writeZip(files);
}
