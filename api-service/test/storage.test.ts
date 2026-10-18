import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { Readable } from 'node:stream';
import { S3Client, PutObjectCommand, GetObjectCommand, DeleteObjectCommand, GetBucketVersioningCommand } from '@aws-sdk/client-s3';
import { FileStorage, S3Storage } from '../src/storage.ts';

for(const namespace of ['publications','private-sync'])test(`${namespace} files are immutable, retries are exact, and deletion fences both upload orderings across restart`, async t => {
  const root = await mkdtemp(join(tmpdir(), 'pittech-storage-')); t.after(() => rm(root, { recursive: true, force: true }));
  const file = join(root, 'input'); await writeFile(file, 'original');
  const storage = new FileStorage(root), key = `${namespace}/${randomUUID()}.pittech`;
  await storage.put(key, file); await storage.put(key, file);
  await writeFile(file, 'changed'); await assert.rejects(storage.put(key, file)); assert.equal((await storage.get(key)).toString(), 'original');
  await storage.delete(key); assert.equal((await stat(storage.path(key))).size, 0);
  await assert.rejects(new FileStorage(root).put(key, file)); await assert.rejects(storage.get(key));
  // Cleanup can win before an upload even starts; deletion is idempotent.
  const early = `${namespace}/${randomUUID()}.pittech`; await storage.delete(early); await storage.delete(early);
  await assert.rejects(storage.put(early, file)); assert.equal((await readFile(storage.path(early))).length, 0);
  for (let i=0;i<20;i++) {
    const raced = `${namespace}/${randomUUID()}.pittech`;
    await Promise.allSettled([storage.put(raced, file), storage.delete(raced)]);
    assert.equal((await readFile(storage.path(raced))).length, 0);
    await assert.rejects(storage.put(raced, file));
  }
});

for(const namespace of ['publications','private-sync'])test(`S3 ${namespace} sends atomic create-only uploads, never deletes fences, and retains cleanup on unknown/versioned buckets`, async t => {
  const root = await mkdtemp(join(tmpdir(), 'pittech-s3-')); t.after(() => rm(root, { recursive: true, force: true }));
  const file = join(root, 'input'); await writeFile(file, 'original');
  const objects = new Map<string,Buffer>(); let versioning: string|undefined, unknown = false, pause: (()=>void)|undefined, started: (()=>void)|undefined;
  const commands: unknown[] = [];
  const client = { send: async (command: unknown) => {
    commands.push(command);
    if (command instanceof GetBucketVersioningCommand) { if (unknown) throw new Error('Unsupported'); return { Status: versioning }; }
    if (command instanceof PutObjectCommand) {
      const {Key,Body,IfNoneMatch}=command.input; assert.ok(Key);
      if (IfNoneMatch) { assert.equal(IfNoneMatch,'*'); if (pause) { started?.(); await new Promise<void>(resolve => { pause=resolve; }); } }
      if (IfNoneMatch && objects.has(Key)) throw { $metadata: { httpStatusCode: 412 } };
      const chunks: Buffer[]=[];
      if (Buffer.isBuffer(Body)) chunks.push(Body); else for await (const value of Body as Readable) chunks.push(Buffer.from(value));
      objects.set(Key,Buffer.concat(chunks)); return {};
    }
    if (command instanceof GetObjectCommand) {
      const body=objects.get(command.input.Key!); if (!body) throw new Error('Missing');
      return { ContentLength:body.length, Body:{ transformToWebStream:()=>Readable.toWeb(Readable.from([body])) } };
    }
    if (command instanceof DeleteObjectCommand) { objects.delete(command.input.Key!); return {}; }
    throw new Error('Unexpected command');
  }} as unknown as S3Client;
  const storage = new S3Storage(client, 'synthetic'), key=`${namespace}/${randomUUID()}.pittech`;
  await storage.put(key,file); await storage.put(key,file);
  await writeFile(file,'changed'); await assert.rejects(storage.put(key,file)); assert.equal(objects.get(key)?.toString(),'original');
  versioning='Enabled'; await assert.rejects(storage.delete(key)); assert.equal(objects.get(key)?.toString(),'original');
  versioning='Suspended'; await assert.rejects(storage.delete(key));
  versioning=undefined; unknown=true; await assert.rejects(storage.delete(key)); unknown=false;
  await storage.delete(key); await assert.rejects(storage.put(key,file)); await assert.rejects(storage.get(key)); assert.equal(objects.get(key)?.length,0);
  // Simulate a remote upload whose commit resumes after a deletion marker wins.
  const late=`${namespace}/${randomUUID()}.pittech`;
  const entered=new Promise<void>(resolve=>{started=resolve;}); pause=()=>{};
  const upload=storage.put(late,file); const rejected=assert.rejects(upload); await entered;
  await storage.delete(late); const resume=pause!; pause=undefined; resume(); await rejected;
  assert.equal(objects.get(late)?.length,0);
  assert.equal(commands.filter(c=>c instanceof DeleteObjectCommand).length,0);
});
