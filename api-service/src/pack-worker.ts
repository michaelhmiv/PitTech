import { parentPort, workerData } from 'node:worker_threads';
import { readFile, writeFile } from 'node:fs/promises';
import { canonicalPack } from './pack.ts';

try {
  const result = await canonicalPack(await readFile(workerData.input));
  await writeFile(workerData.output, result.bytes, { mode: 0o600, flag: 'wx' });
  parentPort!.postMessage({ summary: result.summary });
} catch { parentPort!.postMessage({ invalid: true }); }
