import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ApiError, digest } from './core.ts';
import { SyncEncryption, type SyncObjectContext } from './sync-encryption.ts';
import type { Storage } from './storage.ts';
/** Only ciphertext touches the temporary file. Existing bytes are adopted only
 * after authenticating the exact owner/object/kind/hash and comparing plaintext. */
export async function persistPrivateObject(storage: Storage, encryption: SyncEncryption, context: SyncObjectContext, key: string, plain: Buffer) {
  const root = await mkdtemp(join(tmpdir(), 'pittech-private-cipher-'));
  try {
    const file = join(root, 'object');
    await writeFile(file, encryption.seal(context, plain), { mode: 0o600, flag: 'wx' });
    let failure: unknown;
    try { await storage.put(key, file); } catch (e) { failure = e; }
    let recovered: Buffer | undefined;
    try {
      const actual = await storage.get(key); recovered = encryption.open(context, actual);
      if (!recovered.equals(plain)) throw new ApiError(503, 'private_object_unavailable');
      return digest(actual);
    } catch { throw failure ?? new ApiError(503, 'private_object_unavailable'); }
    finally { recovered?.fill(0); }
  } finally { await rm(root, { recursive: true, force: true }); }
}
