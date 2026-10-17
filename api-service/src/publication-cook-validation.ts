import { Worker } from 'node:worker_threads';
import { ApiError } from './core.ts';
import type { PublicationPayload } from './publication-pack.ts';
export async function publicCookWorker(input:string,output:string,publication:{id:string;revisionId:string;payload:PublicationPayload;credit:{displayName:string}}):Promise<{sha256:string;bytes:number}>{
  const worker=new Worker(new URL('./publication-cook-worker.ts',import.meta.url),{workerData:{input,output,publication},env:{},resourceLimits:{maxOldGenerationSizeMb:256}});
  let timer:NodeJS.Timeout|undefined;
  try{return await new Promise((resolve,reject)=>{
    const fail=()=>reject(new ApiError(503,'pack_unavailable'));timer=setTimeout(fail,15000);worker.once('error',fail);worker.once('exit',fail);worker.once('message',value=>value.sha256?resolve(value):fail());
  });}finally{clearTimeout(timer);await worker.terminate();}
}
