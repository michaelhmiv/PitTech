import { createReadStream } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { mkdir, copyFile, chmod, rename, link, writeFile, readFile, rm, readdir, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { S3Client, PutObjectCommand, GetObjectCommand, DeleteObjectCommand, GetBucketVersioningCommand } from '@aws-sdk/client-s3';
import { LIMITS } from './core.ts';

export interface Storage { put(key: string, file: string): Promise<void>; get(key: string): Promise<Buffer>; delete(key: string): Promise<void> }
const fenced = (key: string) => key.startsWith('publications/') || key.startsWith('private-sync/');
function validKey(key: string): void { if (!/^(relay|publications|private-sync)\/[a-fA-F\d-]{36}\.pittech$/.test(key)) throw new Error('Invalid object key'); }
export class FileStorage implements Storage {
  root: string;
  constructor(root: string) { this.root = root; }
  path(key: string) { validKey(key); return join(this.root, key); }
  async put(key: string, file: string) {
    const path = this.path(key), temporary = path + '.' + randomUUID() + '.partial'; await mkdir(join(this.root, key.split('/')[0]), { recursive: true, mode: 0o700 });
    try { await copyFile(file, temporary); await chmod(temporary, 0o600); if (fenced(key)) {
      try { await link(temporary, path); } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
        if (!(await this.get(key)).equals(await readFile(file))) throw new Error('Object already exists');
      }
    } else await rename(temporary, path); } finally { await rm(temporary, { force: true }); }
  }
  async get(key: string) { const p = this.path(key); if ((await stat(p)).size === 0 || (await stat(p)).size > LIMITS.compressed) throw new Error('Oversize object'); return readFile(p); }
  async delete(key: string) {
    const path = this.path(key);
    if (!fenced(key)) { await rm(path, { force: true }); return; }
    // Never remove the key: a create-only upload that resumes after cleanup must
    // fail instead of restoring the original bytes. No identity enters this marker.
    await mkdir(join(this.root, key.split('/')[0]), { recursive: true, mode: 0o700 });
    const temporary = path + '.' + randomUUID() + '.partial';
    try { await writeFile(temporary, Buffer.alloc(0), { mode: 0o600, flag: 'wx' }); await rename(temporary, path); }
    finally { await rm(temporary, { force: true }); }
  }
  async prune(now: number) {
    for (const namespace of ['relay','publications','private-sync']) {
    const root = join(this.root, namespace); for (const item of await readdir(root).catch(() => [])) if (item.endsWith('.partial')) {
      const p = join(root, item); if ((await stat(p)).mtimeMs < now - 30 * 60000) await rm(p, { force: true });
    }
    }
  }
}
export class S3Storage implements Storage {
  client: S3Client; bucket: string;
  constructor(client: S3Client, bucket: string) { this.client = client; this.bucket = bucket; }
  async put(key: string, file: string) {
    validKey(key); const body = createReadStream(file);
    try { await this.client.send(new PutObjectCommand({ Bucket: this.bucket, Key: key, Body: body,
      ...(fenced(key) ? { IfNoneMatch: '*' } : {}),
      ContentLength: (await stat(file)).size, ContentType: 'application/octet-stream', CacheControl: 'private,no-store' }), { abortSignal: AbortSignal.timeout(30000) }); }
    catch (error) {
      if (!fenced(key) || (error as { $metadata?: { httpStatusCode?: number } }).$metadata?.httpStatusCode !== 412) throw error;
      // Response loss may retry an already committed identical body. A marker or
      // mismatched body can never be replaced by this retry.
      if (!(await this.get(key)).equals(await readFile(file))) throw new Error('Object already exists');
    } finally { body.destroy(); }
  }
  async get(key: string) {
    validKey(key); const r = await this.client.send(new GetObjectCommand({ Bucket: this.bucket, Key: key }), { abortSignal: AbortSignal.timeout(30000) });
    if (!r.Body || r.ContentLength === 0 || (r.ContentLength ?? Infinity) > LIMITS.compressed) { await r.Body?.transformToWebStream().cancel().catch(() => {}); throw new Error('Unavailable object'); }
    const stream = r.Body.transformToWebStream(), reader = stream.getReader(); const chunks: Buffer[] = []; let size = 0;
    try { while (true) { const r = await reader.read(); if (r.done) break; size += r.value.length; if (size > LIMITS.compressed) throw new Error('Oversize object'); chunks.push(Buffer.from(r.value)); } }
    finally { await reader.cancel(); }
    return Buffer.concat(chunks);
  }
  async delete(key: string) {
    validKey(key);
    if (!fenced(key)) {
      await this.client.send(new DeleteObjectCommand({ Bucket: this.bucket, Key: key }), { abortSignal: AbortSignal.timeout(30000) }); return;
    }
    // Overwriting a versioned object would retain its original bytes. Unknown or
    // unsupported versioning checks fail closed and keep the durable cleanup job.
    const versioning = await this.client.send(new GetBucketVersioningCommand({ Bucket: this.bucket }), { abortSignal: AbortSignal.timeout(30000) });
    if (versioning.Status !== undefined) throw new Error('Object version cleanup required');
    await this.client.send(new PutObjectCommand({ Bucket: this.bucket, Key: key, Body: Buffer.alloc(0), ContentLength: 0,
      ContentType: 'application/octet-stream', CacheControl: 'private,no-store' }), { abortSignal: AbortSignal.timeout(30000) });
  }
}
