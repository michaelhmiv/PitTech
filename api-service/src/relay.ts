import { randomUUID } from 'node:crypto';
import { ApiError, LIMITS, newCode, normalizeCode, ownerHash, digest, type Summary, type Budgets } from './core.ts';
import type { Repository, Pack } from './repository.ts';
import type { Storage } from './storage.ts';

export const receipt = (p: Pack) => ({ code: p.code, expiresAt: new Date(p.expiresAt).toISOString(), sha256: p.sha256, bytes: p.bytes, kind: p.kind, dishes: p.dishes });
export class Relay {
  repository: Repository; storage: Storage; budgets: Budgets; clock: () => number; code: () => string;
  constructor(repository: Repository, storage: Storage, budgets: Budgets, clock = Date.now, code = newCode) {
    this.repository = repository; this.storage = storage; this.budgets = budgets; this.clock = clock; this.code = code;
  }
  async upload(file: string, summary: Summary, owner: string, key: string) {
    const hash = ownerHash(owner), now = this.clock();
    if (!/^[a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(key)) throw new ApiError(400, 'idempotency_key_required');
    for (let attempt = 0; attempt < 5; attempt++) {
      const pack: Pack = { ...summary, code: this.code(), objectKey: `relay/${randomUUID()}.pittech`, ownerHash: hash, idempotencyKey: key,
        state: 'uploading', createdAt: now, expiresAt: now + LIMITS.life, leaseUntil: 0 };
      const reservation = await this.repository.reserve(pack, this.budgets); if (!reservation) continue;
      if (reservation.existing) return receipt(reservation.pack);
      // Failed uploads retain their reservation for durable cleanup, including a process interruption.
      await this.storage.put(pack.objectKey, file); await this.repository.activate(pack.code); return receipt({ ...pack, state: 'active' });
    }
    throw new ApiError(503, 'code_unavailable');
  }
  async download(code: string) {
    const normalized = normalizeCode(code), pack = await this.repository.get(normalized);
    if (!pack || pack.state !== 'active' || pack.expiresAt <= this.clock()) throw new ApiError(404, 'unavailable');
    const bytes = await this.storage.get(pack.objectKey);
    const after = await this.repository.get(normalized);
    if (!after || after.state !== 'active' || after.expiresAt <= this.clock()) throw new ApiError(404, 'unavailable');
    if (bytes.length !== pack.bytes || digest(bytes) !== pack.sha256) throw new ApiError(503, 'pack_temporarily_unavailable');
    return { pack, bytes };
  }
  async renew(code: string, owner: string) {
    const result = await this.repository.renew(normalizeCode(code), ownerHash(owner), this.clock());
    if (!result) throw new ApiError(404, 'unavailable'); return receipt(result);
  }
  async revoke(code: string, owner: string) { if (!await this.repository.revoke(normalizeCode(code), ownerHash(owner), this.clock())) throw new ApiError(404, 'unavailable'); }
  async sweep(limit = 100) {
    let removed = 0;
    for (const pack of await this.repository.claim(this.clock(), limit)) {
      try { await this.storage.delete(pack.objectKey); await this.repository.deleted(pack); removed++; } catch { /* Retry after the durable lease. Do not log keys. */ }
    }
    await this.repository.prune(this.clock()); return removed;
  }
}
