import { randomUUID } from 'node:crypto';
import sharp from 'sharp';
import type { Pool, PoolClient } from 'pg';
import { ApiError, digest } from './core.ts';
import { stable } from './pack.ts';
import { lockAccount, uuid } from './lifecycle-contract.ts';
import { SyncEncryption } from './sync-encryption.ts';
import { persistPrivateObject } from './sync-object-bytes.ts';
import type { Storage } from './storage.ts';
import type { PrivateSnapshots, SyncStorageLimits } from './sync-snapshots.ts';
export const PRIVATE_PHOTO_CHUNK_BYTES = 1024 ** 2;
const maximum = 16 * 1024 ** 2, leaseMillis = 300000;
type Part = { index: number; objectId: string; sha256: string; bytes: number; active: boolean };
type Photo = { kind: 'photo-v1'; assetId: string; sha256: string; bytes: number; mimeType: string; count: number; parts: Part[] };
type PhotoOperation = { state: string; response: Photo; request_hash: string };
const invalid = () => new ApiError(422, 'invalid_private_photo');
const missing = () => new ApiError(404, 'private_photo_unavailable');
const canonicalId = (v: unknown): v is string => uuid(v) && v.length === 36 && v === v.toLowerCase();
const sha = (v: unknown): v is string => typeof v === 'string' && v.length === 64 && !/[^a-f0-9]/.test(v);
/** Operations contain only bounded photo hashes/chunk identities, never photo bytes. */
export function photoMetadata(value: unknown): Photo {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid();
  const p = value as Photo;
  if (Object.keys(p).sort().join(',') !== 'assetId,bytes,count,kind,mimeType,parts,sha256' || p.kind !== 'photo-v1' || !canonicalId(p.assetId) || !sha(p.sha256) || !Number.isSafeInteger(p.bytes) || p.bytes < 1 || p.bytes > maximum || !['image/jpeg', 'image/png', 'image/webp'].includes(p.mimeType) || p.count !== Math.ceil(p.bytes / PRIVATE_PHOTO_CHUNK_BYTES) || !Array.isArray(p.parts) || p.parts.length > p.count) throw invalid();
  const seen = new Set<number>(), objects = new Set<string>();
  for (const part of p.parts) {
    if (!part || typeof part !== 'object' || Object.keys(part).sort().join(',') !== 'active,bytes,index,objectId,sha256' || !Number.isInteger(part.index) || part.index < 0 || part.index >= p.count || seen.has(part.index) || !canonicalId(part.objectId) || objects.has(part.objectId) || !sha(part.sha256) || typeof part.active !== 'boolean' || part.bytes !== Math.min(PRIVATE_PHOTO_CHUNK_BYTES, p.bytes - part.index * PRIVATE_PHOTO_CHUNK_BYTES)) throw invalid();
    seen.add(part.index); objects.add(part.objectId);
  }
  return structuredClone(p);
}
/** Internal owner-scoped resumable photos, exposed only through the PRIVATE_SYNC_ENABLED opt-in. */
export class PrivatePhotos {
  private pool: Pool; private storage: Storage; private encryption: SyncEncryption;
  private limits: SyncStorageLimits; private clock: () => number;
  constructor(pool: Pool, storage: Storage, encryption: SyncEncryption, limits: SyncStorageLimits, clock: () => number = Date.now) {
    if (Object.keys(limits).sort().join(',') !== 'dailyBytes,dailyUploads,globalBytes,ownerBytes,ownerOperations' || !Object.values(limits).every(n => Number.isSafeInteger(n) && n > 0)) throw Error('Private photo configuration unavailable');
    this.pool = pool; this.storage = storage; this.encryption = encryption; this.limits = Object.freeze({ ...limits }); this.clock = clock;
  }
  private async tx<T>(work: (c: PoolClient) => Promise<T>) { const c = await this.pool.connect(); try { await c.query('BEGIN'); const v = await work(c); await c.query('COMMIT'); return v; } catch (e) { await c.query('ROLLBACK'); throw e; } finally { c.release(); } }
  private async owner(c: PoolClient, user: string) {
    if (!canonicalId(user)) throw new ApiError(401, 'authentication_required');
    await lockAccount(c, user);
    if (!(await c.query('SELECT 1 FROM publications.publisher_controls WHERE user_id=$1 AND NOT deleting', [user])).rowCount) throw new ApiError(401, 'account_unavailable');
  }
  private async operation(c: PoolClient, user: string, asset: string, allowAbandoned = false): Promise<PhotoOperation> {
    if (!canonicalId(asset)) throw missing();
    const row = (await c.query('SELECT state,response,request_hash FROM private_sync.operations WHERE user_id=$1 AND operation_id=$2', [user, asset])).rows[0];
    if (!row || row.state === 'conflict' && !allowAbandoned) throw missing();
    const response = photoMetadata(row.response); if (response.assetId !== asset) throw missing();
    return { ...row, response };
  }
  private status(row: PhotoOperation) { const p = row.response; return { assetId: p.assetId, sha256: p.sha256, bytes: p.bytes, chunkBytes: PRIVATE_PHOTO_CHUNK_BYTES, receivedParts: p.parts.filter(v => v.active).map(v => v.index).sort((a, b) => a - b), complete: row.state === 'committed' }; }
  async begin(user: string, input: unknown) {
    if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).sort().join(',') !== 'assetId,bytes,mimeType,sha256') throw invalid();
    const value = input as { assetId: string; bytes: number; mimeType: string; sha256: string };
    const photo = photoMetadata({ ...value, kind: 'photo-v1', count: Math.ceil(value.bytes / PRIVATE_PHOTO_CHUNK_BYTES), parts: [] }), hash = digest(stable(input));
    return this.tx(async c => {
      await this.owner(c, user);
      const old = (await c.query('SELECT request_hash FROM private_sync.operations WHERE user_id=$1 AND operation_id=$2', [user, photo.assetId])).rows[0];
      if (old) { if (old.request_hash !== hash) throw new ApiError(409, 'retry_content_changed'); return this.status(await this.operation(c, user, photo.assetId)); }
      const count = Number((await c.query('SELECT count(*) AS n FROM private_sync.operations WHERE user_id=$1', [user])).rows[0].n);
      if (count >= this.limits.ownerOperations) throw new ApiError(429, 'private_sync_capacity');
      await c.query('INSERT INTO private_sync.accounts(user_id,updated_at) VALUES($1,$2) ON CONFLICT(user_id) DO NOTHING', [user, this.clock()]);
      await c.query("INSERT INTO private_sync.operations VALUES($1,$2,$3,$2,0,'pending',$4,$5)", [user, photo.assetId, hash, photo, this.clock()]);
      return this.status({ state: 'pending', request_hash: hash, response: photo });
    });
  }
  async statusOf(user: string, asset: string) {
    return this.tx(async c => {
      await this.owner(c, user); const row = await this.operation(c, user, asset);
      // Expired/cleaned partial chunks are honestly advertised as needing retry.
      if (row.state === 'pending') for (const part of row.response.parts) {
        const object = (await c.query("SELECT state FROM private_sync.objects WHERE id=$1 AND user_id=$2 AND kind='photo'", [part.objectId, user])).rows[0];
        part.active = part.active && object?.state === 'active';
      }
      return this.status(row);
    });
  }
  async putPart(user: string, asset: string, index: number, bytes: Buffer) {
    if (!Number.isInteger(index) || index < 0 || index >= 16 || !Buffer.isBuffer(bytes) || !bytes.length || bytes.length > PRIVATE_PHOTO_CHUNK_BYTES) throw invalid();
    const plain = Buffer.from(bytes), hash = digest(plain);
    try {
      const reserved = await this.tx(async c => {
        await this.owner(c, user); const row = await this.operation(c, user, asset), p = row.response;
        if (index >= p.count || plain.length !== Math.min(PRIVATE_PHOTO_CHUNK_BYTES, p.bytes - index * PRIVATE_PHOTO_CHUNK_BYTES)) throw invalid();
        const prior = p.parts.find(v => v.index === index);
        if (prior && prior.sha256 !== hash) throw new ApiError(409, 'retry_content_changed');
        if (row.state === 'committed') { if (!prior?.active) throw missing(); return { status: this.status(row) }; }
        if (prior) {
          const object = (await c.query("SELECT state,lease_until FROM private_sync.objects WHERE id=$1 AND user_id=$2 AND kind='photo' AND sha256=$3 AND plain_bytes=$4", [prior.objectId, user, hash, plain.length])).rows[0];
          if (object?.state === 'active' && prior.active) return { status: this.status(row) };
          if (object?.state === 'reserved' && Number(object.lease_until) > this.clock()) return { part: prior };
          if (object?.state === 'reserved') await c.query("UPDATE private_sync.objects SET state='deleting' WHERE id=$1 AND user_id=$2", [prior.objectId, user]);
        }
        await c.query('SELECT pg_advisory_xact_lock(890015)');
        const usage = (await c.query('SELECT COALESCE(sum(reserved_bytes),0) AS total,COALESCE(sum(reserved_bytes) FILTER(WHERE user_id=$1),0) AS owned FROM private_sync.objects', [user])).rows[0];
        const day = Math.floor(this.clock() / 86400000), cost = plain.length + 77;
        await c.query('INSERT INTO private_sync.budgets VALUES($1,0,0) ON CONFLICT(day) DO NOTHING', [day]);
        const budget = (await c.query('SELECT uploads,bytes FROM private_sync.budgets WHERE day=$1 FOR UPDATE', [day])).rows[0];
        if (Number(usage.total) + cost > this.limits.globalBytes || Number(usage.owned) + cost > this.limits.ownerBytes || Number(budget.bytes) + cost > this.limits.dailyBytes || Number(budget.uploads) >= this.limits.dailyUploads) throw new ApiError(429, 'private_sync_capacity');
        const part: Part = { index, objectId: randomUUID(), sha256: hash, bytes: plain.length, active: false };
        await c.query("INSERT INTO private_sync.objects(id,user_id,key,kind,sha256,plain_bytes,reserved_bytes,state,lease_id,lease_until,created_at) VALUES($1,$2,$3,'photo',$4,$5,$5+77,'reserved',$6,$7,$8)", [part.objectId, user, `private-sync/${part.objectId}.pittech`, hash, plain.length, randomUUID(), this.clock() + leaseMillis, this.clock()]);
        p.parts = p.parts.filter(v => v.index !== index); p.parts.push(part); p.parts.sort((a, b) => a.index - b.index);
        await c.query('UPDATE private_sync.operations SET response=$3 WHERE user_id=$1 AND operation_id=$2', [user, asset, p]);
        await c.query('UPDATE private_sync.budgets SET uploads=uploads+1,bytes=bytes+$2 WHERE day=$1', [day, cost]);
        return { part };
      });
      if (reserved.status) return reserved.status;
      const part = reserved.part!;
      const cipher = await persistPrivateObject(this.storage, this.encryption, { userId: user, objectId: part.objectId, kind: 'photo', sha256: hash }, `private-sync/${part.objectId}.pittech`, plain);
      return this.tx(async c => {
        await this.owner(c, user); const row = await this.operation(c, user, asset), current = row.response.parts.find(v => v.index === index);
        if (current?.objectId !== part.objectId) throw new ApiError(409, 'private_part_replaced');
        if (current.active) return this.status(row);
        if (row.state !== 'pending' || !(await c.query("SELECT 1 FROM private_sync.objects WHERE id=$1 AND user_id=$2 AND state='reserved' AND lease_until>$3", [part.objectId, user, this.clock()])).rowCount) throw new ApiError(409, 'private_part_expired');
        await c.query("UPDATE private_sync.objects SET state='active',cipher_sha256=$3 WHERE id=$1 AND user_id=$2", [part.objectId, user, cipher]);
        current.active = true; await c.query('UPDATE private_sync.operations SET response=$3 WHERE user_id=$1 AND operation_id=$2', [user, asset, row.response]);
        return this.status(row);
      });
    } finally { plain.fill(0); }
  }
  private async collect(user: string, p: Photo) {
    if (p.parts.length !== p.count || p.parts.some(v => !v.active)) throw new ApiError(409, 'private_photo_incomplete');
    const chunks: Buffer[] = [];
    try {
      for (const part of [...p.parts].sort((a, b) => a.index - b.index)) {
        const row = (await this.pool.query("SELECT key,cipher_sha256 FROM private_sync.objects WHERE id=$1 AND user_id=$2 AND kind='photo' AND state='active' AND sha256=$3 AND plain_bytes=$4", [part.objectId, user, part.sha256, part.bytes])).rows[0];
        if (!row) throw missing();
        const cipher = await this.storage.get(row.key); if (digest(cipher) !== row.cipher_sha256) throw missing();
        const plain = this.encryption.open({ userId: user, objectId: part.objectId, kind: 'photo', sha256: part.sha256 }, cipher); chunks.push(plain);
        if (plain.length !== part.bytes) throw missing();
      }
      const bytes = Buffer.concat(chunks);
      if (bytes.length !== p.bytes || digest(bytes) !== p.sha256) { bytes.fill(0); throw invalid(); }
      return bytes;
    } finally { for (const bytes of chunks) bytes.fill(0); }
  }
  async finish(user: string, asset: string) {
    const row = await this.tx(async c => { await this.owner(c, user); return this.operation(c, user, asset); });
    if (row.state === 'committed') return this.status(row);
    const bytes = await this.collect(user, row.response);
    try {
      let valid = false;
      try {
        const image = sharp(bytes, { limitInputPixels: 20_000_000, animated: false });
        const info = await image.metadata();
        valid = info.format === ({ 'image/jpeg': 'jpeg', 'image/png': 'png', 'image/webp': 'webp' } as Record<string, string>)[row.response.mimeType] && (info.pages ?? 1) === 1 && !!info.width && !!info.height && info.width <= 10000 && info.height <= 10000;
        if (valid) await image.resize({ width: 1, height: 1, fit: 'inside', withoutEnlargement: true }).png().toBuffer();
      } catch { valid = false; /* Invalid/decompression-bomb input never becomes a complete asset. */ }
      if (!valid) throw invalid();
      return this.tx(async c => {
        await this.owner(c, user); const current = await this.operation(c, user, asset);
        if (stable(current.response) !== stable(row.response)) throw new ApiError(409, 'private_photo_changed');
        if (current.state !== 'committed') await c.query("UPDATE private_sync.operations SET state='committed' WHERE user_id=$1 AND operation_id=$2", [user, asset]);
        current.state = 'committed'; return this.status(current);
      });
    } finally { bytes.fill(0); }
  }
  async read(user: string, asset: string) {
    const row = await this.tx(async c => { await this.owner(c, user); const row = await this.operation(c, user, asset); if (row.state !== 'committed') throw missing(); return row; });
    const bytes = await this.collect(user, row.response);
    try {
      await this.tx(async c => { await this.owner(c, user); const current = await this.operation(c, user, asset); if (current.state !== 'committed' || stable(current.response) !== stable(row.response)) throw missing(); });
      return { bytes, mimeType: row.response.mimeType, sha256: row.response.sha256 };
    } catch (e) { bytes.fill(0); throw e; }
  }
  async abandon(user: string, asset: string, snapshots: PrivateSnapshots) {
    const head = await snapshots.read(user);
    const refs = head.envelope ? [...head.envelope.records, ...head.envelope.conflicts.flatMap(v => [v.winner, v.loser])] : [];
    if (refs.some(v => v.assetIds.includes(asset))) throw new ApiError(409, 'private_asset_in_use');
    return this.tx(async c => {
      await this.owner(c, user);
      const version = Number((await c.query('SELECT version FROM private_sync.accounts WHERE user_id=$1', [user])).rows[0]?.version ?? 0);
      if (version !== head.version) throw new ApiError(409, 'sync_version_changed');
      const row = await this.operation(c, user, asset, true);
      if (row.state === 'conflict') return { assetId: asset, abandoned: true };
      await c.query("UPDATE private_sync.operations SET state='conflict' WHERE user_id=$1 AND operation_id=$2", [user, asset]);
      for (const part of row.response.parts) await c.query("UPDATE private_sync.objects SET state='deleting' WHERE id=$1 AND user_id=$2", [part.objectId, user]);
      return { assetId: asset, abandoned: true };
    });
  }
}
