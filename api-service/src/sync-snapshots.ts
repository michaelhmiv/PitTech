import { randomUUID } from 'node:crypto';
import type { Pool, PoolClient } from 'pg';
import { ApiError, digest } from './core.ts';
import { stable } from './pack.ts';
import { lockAccount, uuid } from './lifecycle-contract.ts';
import { SyncEncryption } from './sync-encryption.ts';
import { syncEnvelope, syncTransition, type SyncEnvelope } from './sync-envelope.ts';
import type { Storage } from './storage.ts';
import { persistPrivateObject } from './sync-object-bytes.ts';
import { photoMetadata } from './sync-photos.ts';

export type SyncStorageLimits = {
  ownerBytes: number; globalBytes: number; dailyBytes: number; dailyUploads: number;
  ownerOperations: number;
};
export type SyncSnapshotReceipt = { version: number; mutationId: string; sha256: string };
type ObjectRow = { id: string; key: string; sha256: string; cipher_sha256: string | null; plain_bytes: number; state: string };
type Head = { version: number; object?: ObjectRow };
type Operation = { request_hash: string; object_id: string; state: string; response: SyncSnapshotReceipt | null };
const leaseMillis = 300_000;
const unavailable = () => new ApiError(503, 'private_snapshot_unavailable');
const conflict = () => new ApiError(409, 'sync_version_changed');

/** Internal storage boundary, exposed over HTTP only through the PRIVATE_SYNC_ENABLED
 * opt-in. A production caller must provide the strict native-library
 * projection/relationship validator. syncEnvelope by itself only validates metadata
 * and is not that validator. Photo references must name verified, completed assets
 * owned by this account.
 */
export class PrivateSnapshots {
  private pool: Pool;
  private storage: Storage;
  private encryption: SyncEncryption;
  private project: (value: unknown) => SyncEnvelope;
  private limits: SyncStorageLimits;
  private clock: () => number;
  constructor(
    pool: Pool, storage: Storage, encryption: SyncEncryption,
    project: (value: unknown) => SyncEnvelope,
    limits: SyncStorageLimits, clock: () => number = Date.now,
  ) {
    this.pool = pool; this.storage = storage; this.encryption = encryption;
    this.project = project; this.limits = Object.freeze({ ...limits }); this.clock = clock;
    if (typeof project !== 'function' || Object.keys(limits).sort().join(',') !== 'dailyBytes,dailyUploads,globalBytes,ownerBytes,ownerOperations' ||
        !Object.values(limits).every(n => Number.isSafeInteger(n) && n > 0)) throw Error('Private snapshot configuration unavailable');
  }
  private async tx<T>(work: (c: PoolClient) => Promise<T>): Promise<T> {
    const c = await this.pool.connect();
    try { await c.query('BEGIN'); const value = await work(c); await c.query('COMMIT'); return value; }
    catch (e) { await c.query('ROLLBACK'); throw e; } finally { c.release(); }
  }
  private async owner(c: PoolClient, user: string) {
    if (!uuid(user) || user.length !== 36 || user !== user.toLowerCase()) throw new ApiError(401, 'authentication_required');
    await lockAccount(c, user);
    if (!(await c.query('SELECT 1 FROM publications.publisher_controls WHERE user_id=$1 AND NOT deleting', [user])).rowCount) throw new ApiError(401, 'account_unavailable');
  }
  private validate(value: unknown) {
    const envelope = syncEnvelope(this.project(value));
    return envelope;
  }
  private async assets(c: PoolClient, user: string, envelope: SyncEnvelope) {
    const ids = [...new Set([...envelope.records, ...envelope.conflicts.flatMap(c => [c.winner, c.loser])].flatMap(r => r.assetIds))];
    if (ids.length > 10000) throw new ApiError(422, 'private_asset_capacity');
    if (!ids.length) return;
    const rows = (await c.query("SELECT operation_id,response FROM private_sync.operations WHERE user_id=$1 AND operation_id=ANY($2::uuid[]) AND state='committed'", [user, ids])).rows;
    if (rows.length !== ids.length) throw new ApiError(422, 'private_assets_unavailable');
    for (const row of rows) { const photo = photoMetadata(row.response); if (photo.assetId !== row.operation_id || photo.parts.length !== photo.count || photo.parts.some(p => !p.active)) throw new ApiError(422, 'private_assets_unavailable'); }
  }
  private async head(c: PoolClient, user: string): Promise<Head> {
    const account = (await c.query('SELECT version,head_id FROM private_sync.accounts WHERE user_id=$1', [user])).rows[0];
    if (!account) return { version: 0 };
    const version = Number(account.version);
    if (!Number.isSafeInteger(version) || version < 0 || !account.head_id && version !== 0) throw unavailable();
    if (!account.head_id) return { version };
    const object = (await c.query<ObjectRow>("SELECT id,key,sha256,cipher_sha256,plain_bytes,state FROM private_sync.objects WHERE id=$1 AND user_id=$2 AND kind='snapshot' AND state='active'", [account.head_id, user])).rows[0];
    if (!object || !object.cipher_sha256) throw unavailable();
    return { version, object };
  }
  private context(user: string, row: ObjectRow) { return { userId: user, objectId: row.id, kind: 'snapshot' as const, sha256: row.sha256 }; }
  /** Snapshot head metadata without decrypting the envelope: the current
   * committed version and when it was committed (epoch ms), or version 0
   * when the account has never synced. Read-only status surfaces (MCP
   * connection context) use this; it never exposes library content. */
  /** The durable receipt for a previously committed mutation id, if
   * any. Lets idempotent callers (the MCP command layer) answer a
   * retry from the receipt alone, without rebuilding the change. */
  async receiptFor(user: string, mutationId: string): Promise<SyncSnapshotReceipt | undefined> {
    const row = (await this.pool.query("SELECT response FROM private_sync.operations WHERE user_id=$1 AND operation_id=$2 AND state='committed'", [user, mutationId])).rows[0] as { response: SyncSnapshotReceipt | null } | undefined;
    return row?.response ?? undefined;
  }
  async status(user: string): Promise<{ version: number; updatedAt: number | null }> {
    const row = (await this.pool.query('SELECT version,updated_at FROM private_sync.accounts WHERE user_id=$1', [user])).rows[0] as { version: string; updated_at: string } | undefined;
    if (!row) return { version: 0, updatedAt: null };
    return { version: Number(row.version), updatedAt: Number(row.updated_at) };
  }
  async read(user: string): Promise<{ version: number; envelope: SyncEnvelope | null }> {
    const head = await this.tx(async c => { await this.owner(c, user); return this.head(c, user); });
    let envelope: SyncEnvelope | null = null;
    if (head.object) {
      let plain: Buffer | undefined;
      try {
        const bytes = await this.storage.get(head.object.key);
        if (digest(bytes) !== head.object.cipher_sha256) throw unavailable();
        plain = this.encryption.open(this.context(user, head.object), bytes);
        if (plain.length !== head.object.plain_bytes) throw unavailable();
        envelope = this.validate(JSON.parse(plain.toString('utf8')));
        if (stable(envelope) !== plain.toString('utf8')) throw unavailable();
      } catch { throw unavailable(); } finally { plain?.fill(0); }
    }
    // Do not return a decrypted read which crossed an account deletion/head change.
    await this.tx(async c => {
      await this.owner(c, user); const current = await this.head(c, user);
      if (current.version !== head.version || current.object?.id !== head.object?.id) throw conflict();
      if (envelope) await this.assets(c, user, envelope);
    });
    return { version: head.version, envelope };
  }
  private async operation(c: PoolClient, user: string, id: string, hash: string): Promise<Operation | undefined> {
    const row = (await c.query<Operation>('SELECT request_hash,object_id,state,response FROM private_sync.operations WHERE user_id=$1 AND operation_id=$2', [user, id])).rows[0];
    if (row && row.request_hash !== hash) throw new ApiError(409, 'retry_content_changed');
    return row;
  }
  private receipt(row: Operation, id: string, sha: string) {
    const r = row.response;
    if (!r || !Number.isSafeInteger(r.version) || r.version < 1 || r.mutationId !== id || r.sha256 !== sha) throw unavailable();
    return structuredClone(r);
  }
  async put(user: string, value: unknown): Promise<SyncSnapshotReceipt> {
    const envelope = this.validate(value), plain = Buffer.from(stable(envelope)), hash = digest(plain), id = envelope.mutationId;
    try {
      const previousOperation = await this.tx(async c => { await this.owner(c, user); return this.operation(c, user, id, hash); });
      if (previousOperation?.state === 'committed') return this.receipt(previousOperation, id, hash);
      if (previousOperation?.state === 'conflict') throw conflict();
      const previous = await this.read(user);
      if (previous.version !== envelope.baseVersion) throw conflict();
      syncTransition(previous.envelope ?? undefined, envelope);
      const reservation = await this.tx(async c => {
        await this.owner(c, user);
        await c.query('INSERT INTO private_sync.accounts(user_id,updated_at) VALUES($1,$2) ON CONFLICT(user_id) DO NOTHING', [user, this.clock()]);
        const account = (await c.query('SELECT version FROM private_sync.accounts WHERE user_id=$1 FOR UPDATE', [user])).rows[0];
        const old = await this.operation(c, user, id, hash);
        if (old?.state === 'committed') return { receipt: this.receipt(old, id, hash) };
        if (old?.state === 'conflict') return { changed: true };
        await this.assets(c, user, envelope);
        if (Number(account.version) !== envelope.baseVersion) {
          if (old) await c.query("UPDATE private_sync.operations SET state='conflict' WHERE user_id=$1 AND operation_id=$2", [user, id]);
          return { changed: true };
        }
        if (old) {
          const row = (await c.query<ObjectRow>("SELECT id,key,sha256,cipher_sha256,plain_bytes,state FROM private_sync.objects WHERE id=$1 AND user_id=$2 AND kind='snapshot' AND state='reserved' AND lease_until>$3", [old.object_id, user, this.clock()])).rows[0];
          if (!row || row.sha256 !== hash || row.plain_bytes !== plain.length) throw new ApiError(409, 'sync_operation_expired');
          return { object: row };
        }
        if (Number(account.version) >= Number.MAX_SAFE_INTEGER - 1) throw new ApiError(409, 'sync_version_exhausted');
        // Account -> global quota lock order is shared by all reservations.
        await c.query('SELECT pg_advisory_xact_lock(890015)');
        const count = Number((await c.query('SELECT count(*) AS n FROM private_sync.operations WHERE user_id=$1', [user])).rows[0].n);
        const usage = (await c.query('SELECT COALESCE(sum(reserved_bytes),0) AS total,COALESCE(sum(reserved_bytes) FILTER(WHERE user_id=$1),0) AS owned FROM private_sync.objects', [user])).rows[0];
        const day = Math.floor(this.clock() / 86400000), bytes = plain.length + 77;
        await c.query('INSERT INTO private_sync.budgets VALUES($1,0,0) ON CONFLICT(day) DO NOTHING', [day]);
        const budget = (await c.query('SELECT uploads,bytes FROM private_sync.budgets WHERE day=$1 FOR UPDATE', [day])).rows[0];
        if (count >= this.limits.ownerOperations || Number(usage.total) + bytes > this.limits.globalBytes || Number(usage.owned) + bytes > this.limits.ownerBytes || Number(budget.bytes) + bytes > this.limits.dailyBytes || Number(budget.uploads) >= this.limits.dailyUploads) throw new ApiError(429, 'private_sync_capacity');
        const objectId = randomUUID(), key = `private-sync/${objectId}.pittech`, now = this.clock();
        await c.query("INSERT INTO private_sync.objects(id,user_id,key,kind,sha256,plain_bytes,reserved_bytes,state,lease_id,lease_until,created_at) VALUES($1,$2,$3,'snapshot',$4,$5,$5+77,'reserved',$6,$7,$8)", [objectId, user, key, hash, plain.length, randomUUID(), now + leaseMillis, now]);
        await c.query("INSERT INTO private_sync.operations VALUES($1,$2,$3,$4,$5,'pending',NULL,$6)", [user, id, hash, objectId, envelope.baseVersion, now]);
        await c.query('UPDATE private_sync.budgets SET uploads=uploads+1,bytes=bytes+$2 WHERE day=$1', [day, bytes]);
        return { object: { id: objectId, key, sha256: hash, cipher_sha256: null, plain_bytes: plain.length, state: 'reserved' } as ObjectRow };
      });
      if (reservation.receipt) return reservation.receipt;
      if (reservation.changed || !reservation.object) throw conflict();
      const object = reservation.object, cipherHash = await persistPrivateObject(this.storage, this.encryption, this.context(user, object), object.key, plain);
      const result = await this.tx(async c => {
        await this.owner(c, user);
        const account = (await c.query('SELECT version,head_id FROM private_sync.accounts WHERE user_id=$1 FOR UPDATE', [user])).rows[0];
        const old = await this.operation(c, user, id, hash);
        if (old?.state === 'committed') return this.receipt(old, id, hash);
        const current = (await c.query("SELECT 1 FROM private_sync.objects WHERE id=$1 AND user_id=$2 AND state='reserved' AND lease_until>$3", [object.id, user, this.clock()])).rowCount;
        if (!account || old?.state !== 'pending' || !current || Number(account.version) !== envelope.baseVersion) {
          if (old?.state === 'pending') await c.query("UPDATE private_sync.operations SET state='conflict' WHERE user_id=$1 AND operation_id=$2", [user, id]);
          await c.query("UPDATE private_sync.objects SET state='deleting' WHERE id=$1 AND user_id=$2 AND state='reserved'", [object.id, user]);
          return null;
        }
        await this.assets(c, user, envelope);
        const receipt = { version: envelope.baseVersion + 1, mutationId: id, sha256: hash };
        await c.query("UPDATE private_sync.objects SET state='active',cipher_sha256=$3 WHERE id=$1 AND user_id=$2", [object.id, user, cipherHash]);
        await c.query('UPDATE private_sync.accounts SET head_id=$2,version=$3,updated_at=$4 WHERE user_id=$1', [user, object.id, receipt.version, this.clock()]);
        if (account.head_id) await c.query("UPDATE private_sync.objects SET state='deleting',lease_until=$3 WHERE id=$1 AND user_id=$2", [account.head_id, user, this.clock() + leaseMillis]);
        await c.query("UPDATE private_sync.operations SET state='committed',response=$3 WHERE user_id=$1 AND operation_id=$2", [user, id, receipt]);
        return receipt;
      });
      if (!result) throw conflict();
      return result;
    } finally { plain.fill(0); }
  }
  /** Owner-scoped maintenance; account deletion uses the separate lifecycle adapter. */
  async sweep(user: string): Promise<number> {
    const lease = randomUUID(), now = this.clock();
    const rows = await this.tx(async c => {
      await this.owner(c, user);
      const rows = (await c.query<{ id: string; key: string }>(`SELECT o.id,o.key FROM private_sync.objects o WHERE o.user_id=$1 AND o.state IN('reserved','deleting') AND o.lease_until<=$2 AND NOT EXISTS(SELECT 1 FROM private_sync.accounts a WHERE a.head_id=o.id) FOR UPDATE SKIP LOCKED LIMIT 5`, [user, now])).rows;
      for (const row of rows) {
        await c.query("UPDATE private_sync.operations SET state='conflict' WHERE user_id=$1 AND object_id=$2 AND state='pending'", [user, row.id]);
        await c.query("UPDATE private_sync.objects SET state='deleting',lease_id=$3,lease_until=$4 WHERE id=$1 AND user_id=$2", [row.id, user, lease, now + leaseMillis]);
      }
      return rows;
    });
    let removed = 0;
    for (const row of rows) {
      try {
        await this.storage.delete(row.key);
        removed += (await this.pool.query("DELETE FROM private_sync.objects WHERE id=$1 AND user_id=$2 AND state='deleting' AND lease_id=$3", [row.id, user, lease])).rowCount ?? 0;
      } catch { /* Preserve ownership and reserved quota until physical cleanup succeeds. */ }
    }
    return removed;
  }
  /** Bounded global driver for abandoned uploads; each user's sweep stays owner-scoped. */
  async sweepAbandonedUploads(maxUsers = 20): Promise<number> {
    const now = this.clock();
    const rows = (await this.pool.query<{ user_id: string }>(`SELECT DISTINCT o.user_id AS user_id FROM private_sync.objects o WHERE o.state IN('reserved','deleting') AND o.lease_until<=$1 AND NOT EXISTS(SELECT 1 FROM private_sync.accounts a WHERE a.head_id=o.id) ORDER BY user_id LIMIT $2`, [now, maxUsers])).rows;
    let removed = 0;
    for (const row of rows) removed += await this.sweep(row.user_id);
    return removed;
  }
}
