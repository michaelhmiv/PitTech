import { createReadStream } from 'node:fs';
import { mkdir, copyFile, chmod, rename, readFile, rm, readdir, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { S3Client, PutObjectCommand, GetObjectCommand, DeleteObjectCommand } from '@aws-sdk/client-s3';
import { LIMITS } from './core.ts';

export interface Storage { put(key: string, file: string): Promise<void>; get(key: string): Promise<Buffer>; delete(key: string): Promise<void> }
function validKey(key: string): void { if (!/^relay\/[a-f\d-]{36}\.pittech$/i.test(key)) throw new Error('Invalid object key'); }
export class FileStorage implements Storage {
  root: string;
  constructor(root: string) { this.root = root; }
  path(key: string) { validKey(key); return join(this.root, key); }
  async put(key: string, file: string) {
    const path = this.path(key), temporary = path + '.partial'; await mkdir(join(this.root, 'relay'), { recursive: true, mode: 0o700 });
    try { await copyFile(file, temporary); await chmod(temporary, 0o600); await rename(temporary, path); } finally { await rm(temporary, { force: true }); }
  }
  async get(key: string) { const p = this.path(key); if ((await stat(p)).size > LIMITS.compressed) throw new Error('Oversize object'); return readFile(p); }
  async delete(key: string) { await rm(this.path(key), { force: true }); }
  async prune(now: number) {
    const root = join(this.root, 'relay'); for (const item of await readdir(root).catch(() => [])) if (item.endsWith('.partial')) {
      const p = join(root, item); if ((await stat(p)).mtimeMs < now - 30 * 60000) await rm(p, { force: true });
    }
  }
}
export class S3Storage implements Storage {
  client: S3Client; bucket: string;
  constructor(client: S3Client, bucket: string) { this.client = client; this.bucket = bucket; }
  async put(key: string, file: string) {
    validKey(key); const body = createReadStream(file);
    try { await this.client.send(new PutObjectCommand({ Bucket: this.bucket, Key: key, Body: body,
      ContentLength: (await stat(file)).size, ContentType: 'application/octet-stream', CacheControl: 'private,no-store' }), { abortSignal: AbortSignal.timeout(30000) }); }
    finally { body.destroy(); }
  }
  async get(key: string) {
    validKey(key); const r = await this.client.send(new GetObjectCommand({ Bucket: this.bucket, Key: key }), { abortSignal: AbortSignal.timeout(30000) });
    if (!r.Body || (r.ContentLength ?? Infinity) > LIMITS.compressed) { await r.Body?.transformToWebStream().cancel().catch(() => {}); throw new Error('Unavailable object'); }
    const stream = r.Body.transformToWebStream(), reader = stream.getReader(); const chunks: Buffer[] = []; let size = 0;
    try { while (true) { const r = await reader.read(); if (r.done) break; size += r.value.length; if (size > LIMITS.compressed) throw new Error('Oversize object'); chunks.push(Buffer.from(r.value)); } }
    finally { await reader.cancel(); }
    return Buffer.concat(chunks);
  }
  async delete(key: string) { validKey(key); await this.client.send(new DeleteObjectCommand({ Bucket: this.bucket, Key: key }), { abortSignal: AbortSignal.timeout(30000) }); }
}
