import { Worker } from 'node:worker_threads';
import { ApiError, type Summary } from './core.ts';

export async function validate(input: string, output: string): Promise<Summary> {
  const worker = new Worker(new URL('./pack-worker.ts', import.meta.url), { workerData: { input, output }, env: {},
    resourceLimits: { maxOldGenerationSizeMb: 256 } });
  let timer: NodeJS.Timeout | undefined;
  try {
    return await new Promise<Summary>((resolve, reject) => {
      const fail = () => reject(new ApiError(422, 'invalid_pack'));
      timer = setTimeout(() => reject(new ApiError(422, 'pack_validation_timed_out')), 15000);
      worker.once('error', fail); worker.once('exit', fail);
      worker.once('message', result => result.summary ? resolve(result.summary) : fail());
    });
  } finally { clearTimeout(timer); await worker.terminate(); }
}
