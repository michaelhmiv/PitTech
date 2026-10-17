import { randomUUID } from 'node:crypto';
import type { Pool } from 'pg';
import { ApiError, LIMITS, type Summary, type Budgets } from './core.ts';

export type Pack = Summary & { code: string; objectKey: string; ownerHash: string; idempotencyKey: string;
  state: 'uploading' | 'active' | 'revoked' | 'deleting'; createdAt: number; expiresAt: number; leaseUntil: number; leaseToken?: string };
export interface Repository {
  reserve(pack: Pack, budgets: Budgets): Promise<{ pack: Pack; existing: boolean } | null>;
  activate(code: string): Promise<void>;
  get(code: string): Promise<Pack | undefined>;
  renew(code: string, owner: string, now: number): Promise<Pack | undefined>;
  revoke(code: string, owner: string, now: number): Promise<boolean>;
  rate(key: string, max: number, expiresAt: number): Promise<boolean>;
  claim(now: number, limit: number): Promise<Pack[]>;
  deleted(pack: Pack): Promise<void>;
  prune(now: number): Promise<void>;
}
export function sameUpload(prior: Pack, next: Pack): void {
  if (prior.sha256 !== next.sha256 || prior.bytes !== next.bytes) throw new ApiError(409, 'retry_content_changed');
  if (prior.state !== 'active' || prior.expiresAt <= next.createdAt) throw new ApiError(409, 'upload_unavailable');
}
type DbPack = { code: string; object_key: string; owner_hash: string; idempotency_key: string; sha256: string; bytes: string;
  kind: Pack['kind']; dishes: number; state: Pack['state']; created_at: string; expires_at: string; lease_until: string; lease_token?: string };
const fromDb = (p: DbPack): Pack => ({ code: p.code, objectKey: p.object_key, ownerHash: p.owner_hash, idempotencyKey: p.idempotency_key,
  sha256: p.sha256, bytes: Number(p.bytes), kind: p.kind, dishes: p.dishes, state: p.state,
  createdAt: Number(p.created_at), expiresAt: Number(p.expires_at), leaseUntil: Number(p.lease_until), leaseToken: p.lease_token });
export class PostgresRepository implements Repository {
  pool: Pool;
  constructor(pool: Pool) { this.pool = pool; }
  async reserve(pack: Pack, budgets: Budgets) {
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN'); await client.query('SELECT pg_advisory_xact_lock(890006)');
      const prior = await client.query<DbPack>('SELECT * FROM relay.packs WHERE owner_hash=$1 AND idempotency_key=$2', [pack.ownerHash, pack.idempotencyKey]);
      if (prior.rows[0]) { const p = fromDb(prior.rows[0]); sameUpload(p, pack); await client.query('COMMIT'); return { pack: p, existing: true }; }
      if ((await client.query('SELECT 1 FROM relay.packs WHERE code=$1', [pack.code])).rowCount) { await client.query('ROLLBACK'); return null; }
      const stored = Number((await client.query('SELECT COALESCE(SUM(bytes),0) AS bytes FROM relay.packs')).rows[0].bytes);
      const day = new Date(pack.createdAt).toISOString().slice(0, 10);
      const usage = (await client.query('SELECT uploads,bytes FROM relay.daily_budgets WHERE day=$1', [day])).rows[0];
      if (stored + pack.bytes > budgets.storageBytes || Number(usage?.uploads ?? 0) >= budgets.dailyUploads || Number(usage?.bytes ?? 0) + pack.bytes > budgets.dailyBytes) throw new ApiError(503, 'relay_budget_exhausted');
      await client.query('INSERT INTO relay.daily_budgets VALUES($1,1,$2) ON CONFLICT(day) DO UPDATE SET uploads=relay.daily_budgets.uploads+1,bytes=relay.daily_budgets.bytes+$2', [day, pack.bytes]);
      await client.query('INSERT INTO relay.packs(code,object_key,owner_hash,idempotency_key,sha256,bytes,kind,dishes,state,created_at,expires_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)',
        [pack.code, pack.objectKey, pack.ownerHash, pack.idempotencyKey, pack.sha256, pack.bytes, pack.kind, pack.dishes, pack.state, pack.createdAt, pack.expiresAt]);
      await client.query('COMMIT'); return { pack, existing: false };
    } catch (error) { await client.query('ROLLBACK'); throw error; } finally { client.release(); }
  }
  async activate(code: string) { if (!(await this.pool.query("UPDATE relay.packs SET state='active' WHERE code=$1 AND state='uploading'", [code])).rowCount) throw new ApiError(503, 'upload_interrupted'); }
  async get(code: string) { const r = await this.pool.query<DbPack>('SELECT * FROM relay.packs WHERE code=$1', [code]); return r.rows[0] ? fromDb(r.rows[0]) : undefined; }
  async renew(code: string, owner: string, now: number) {
    const r = await this.pool.query<DbPack>("UPDATE relay.packs SET expires_at=$4 WHERE code=$1 AND owner_hash=$2 AND state='active' AND expires_at>$3 RETURNING *", [code, owner, now, now + LIMITS.life]);
    return r.rows[0] ? fromDb(r.rows[0]) : undefined;
  }
  async revoke(code: string, owner: string, now: number) { return !!(await this.pool.query("UPDATE relay.packs SET state='revoked' WHERE code=$1 AND owner_hash=$2 AND state='active' AND expires_at>$3", [code, owner, now])).rowCount; }
  async rate(key: string, max: number, expiresAt: number) {
    const r = await this.pool.query('INSERT INTO relay.rate_buckets VALUES($1,1,$2) ON CONFLICT(key) DO UPDATE SET count=relay.rate_buckets.count+1 WHERE relay.rate_buckets.count<$3 RETURNING count', [key, expiresAt, max]); return !!r.rowCount;
  }
  async claim(now: number, limit: number) {
    const token = randomUUID();
    const r = await this.pool.query<DbPack>(`WITH selected AS (
      SELECT code FROM relay.packs WHERE lease_until<=$1 AND (state IN ('revoked','deleting') OR expires_at<=$1 OR state='uploading' AND created_at<=$2)
      ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT $3)
      UPDATE relay.packs p SET state='deleting',lease_until=$1+300000,lease_token=$4 FROM selected s WHERE p.code=s.code RETURNING p.*`, [now, now - LIMITS.uploadLease, limit, token]);
    return r.rows.map(fromDb);
  }
  async deleted(pack: Pack) { await this.pool.query("DELETE FROM relay.packs WHERE code=$1 AND state='deleting' AND lease_token=$2", [pack.code, pack.leaseToken]); }
  async prune(now: number) {
    await this.pool.query('DELETE FROM relay.rate_buckets WHERE expires_at<$1', [now]);
    await this.pool.query('DELETE FROM relay.daily_budgets WHERE day<$1', [new Date(now - 3 * 86400000).toISOString().slice(0, 10)]);
  }
}
