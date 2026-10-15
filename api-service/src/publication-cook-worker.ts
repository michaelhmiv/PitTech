import { parentPort,workerData } from 'node:worker_threads';
import { readFile,writeFile } from 'node:fs/promises';
import { readZip } from './zip.ts';
import { digest } from './core.ts';
import { publicationCookPack } from './publication-cook-pack.ts';
try{
  const files=await readZip(await readFile(workerData.input),'publication'),photos=new Map<string,Buffer>();
  for(const recipe of workerData.publication.payload.recipes)for(const asset of recipe.assets){
    const bytes=files.get(asset.path);if(!bytes||digest(bytes)!==asset.sha256)throw new Error('Invalid photo');photos.set(asset.id,bytes);
  }
  const result=await publicationCookPack(workerData.publication,photos);await writeFile(workerData.output,result.bytes,{mode:0o600,flag:'wx'});
  parentPort!.postMessage({sha256:result.summary.sha256,bytes:result.bytes.length});
}catch{parentPort!.postMessage({error:'pack_unavailable'});}
