import { Worker } from 'node:worker_threads';
import { ApiError } from './core.ts';
import type { PublicationPayload } from './publication-pack.ts';
export type ValidatedPublication={payload:PublicationPayload;sha256:string;bytes:number};
export async function validatePublication(input:string,output:string):Promise<ValidatedPublication> {
  const worker=new Worker(new URL('./publication-worker.ts',import.meta.url),{workerData:{input,output},env:{},resourceLimits:{maxOldGenerationSizeMb:256}});
  let timer:NodeJS.Timeout|undefined;
  try { return await new Promise((resolve,reject)=>{
    const fail=()=>reject(new ApiError(422,'invalid_publication'));
    timer=setTimeout(fail,15000); worker.once('error',fail); worker.once('exit',fail);
    worker.once('message',value=>value.payload?resolve(value):fail());
  }); } finally { clearTimeout(timer); await worker.terminate(); }
}
