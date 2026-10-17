import { parentPort, workerData } from 'node:worker_threads';
import { readFile, writeFile } from 'node:fs/promises';
import { canonicalPublication } from './publication-pack.ts';
try {
  const result=await canonicalPublication(await readFile(workerData.input));
  await writeFile(workerData.output,result.bytes,{mode:0o600,flag:'wx'});
  parentPort!.postMessage({payload:result.payload,sha256:result.sha256,bytes:result.bytes.length});
} catch { parentPort!.postMessage({error:'invalid_publication'}); }
