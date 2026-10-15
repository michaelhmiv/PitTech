import { readFile } from 'node:fs/promises';
import { Ajv2020 } from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import sharp from 'sharp';
import { ApiError, digest, LIMITS } from './core.ts';
import { stable } from './pack.ts';
import { readZip, writeZip } from './zip.ts';

export type Source = { itemId: string; revisionId: string; removed: boolean; displayName?: string };
export type Asset = { id: string; path: string; mimeType: string; sha256: string; caption: string };
export type PublicationRecipe = { id: string; revisionId: string; title: string; description: string; foodType: string; cut: string | null;
  weightGrams: number | null; method: string | null; ingredients: unknown[]; instructions: { id: string }[]; tags: string[];
  story: { attemptId: string; channels: { id: string; role: string }[]; measurements: { channelId: string; role: string }[]; milestones: { id: string }[] } | null;
  assets: Asset[]; source: Source | null };
export type PublicationPayload = { contractVersion: 1; kind: 'recipe'|'collection'|'attempt'; identityMode: 'named'|'anonymous'; title: string;
  recipes: PublicationRecipe[]; source: Source | null; measuredAttemptId: string | null; heroAssetId: string | null };
const ajv = new Ajv2020({ strict: true, strictTypes: false }); addFormats.default(ajv);
const schema = ajv.compile(JSON.parse(await readFile(new URL('../../contracts/publication.schema.json',import.meta.url),'utf8')));
const invalid = () => new ApiError(422,'invalid_publication');
function unique(ids: string[]) { if(new Set(ids).size !== ids.length) throw invalid(); }
/** A public archive is explicit publication.json plus selected, independently owned photos; never a private library ZIP. */
export async function canonicalPublication(bytes: Buffer) {
  const files = await readZip(bytes,'publication'), json = files.get('publication.json');
  if(!json || json.length > 8*1024**2) throw invalid();
  let payload: PublicationPayload;
  try { payload=JSON.parse(json.toString('utf8')); } catch { throw invalid(); }
  if(!schema(payload)) throw invalid();
  if(!payload.title.trim() || payload.recipes.some(r=>!r.title.trim())) throw invalid();
  if(payload.kind === 'recipe' && payload.recipes.length !== 1) throw invalid();
  unique(payload.recipes.map(r=>r.id)); unique(payload.recipes.map(r=>r.revisionId));
  const assets=payload.recipes.flatMap(r=>r.assets); unique(assets.map(a=>a.id)); unique(assets.map(a=>a.path));
  if(files.size !== assets.length+1 || (payload.heroAssetId !== null && !assets.some(a=>a.id===payload.heroAssetId))) throw invalid();
  const attempts = new Set<string>();
  for(const recipe of payload.recipes) {
    unique(recipe.instructions.map(i=>i.id));
    if(!recipe.story) continue;
    const story=recipe.story; unique(story.channels.map(c=>c.id)); unique(story.milestones.map(m=>m.id));
    if(attempts.has(story.attemptId)) throw invalid(); attempts.add(story.attemptId);
    if(story.measurements.some(m=>!story.channels.some(c=>c.id===m.channelId&&c.role===m.role))) throw invalid();
  }
  if(payload.measuredAttemptId !== null && !attempts.has(payload.measuredAttemptId)) throw invalid();
  // Attribution is resolved by the server, never copied from uploaded display strings.
  if(payload.source)delete payload.source.displayName;
  for(const recipe of payload.recipes)if(recipe.source)delete recipe.source.displayName;
  const accepted = new Map<string,Buffer>();
  for(const asset of assets) {
    if(!asset.path.endsWith(asset.mimeType==='image/jpeg'?'.jpg':asset.mimeType==='image/png'?'.png':'.webp')) throw invalid();
    const raw=files.get(asset.path); if(!raw || raw.length > LIMITS.entry || digest(raw)!==asset.sha256) throw invalid();
    try {
      const image=sharp(raw,{failOn:'warning',limitInputPixels:20_000_000,animated:false}), metadata=await image.metadata();
      const expected=asset.mimeType==='image/jpeg'?'jpeg':asset.mimeType==='image/png'?'png':'webp';
      if(metadata.format!==expected || (metadata.pages??1)!==1) throw invalid();
      const oriented=image.rotate().resize({width:2400,height:2400,fit:'inside',withoutEnlargement:true});
      const output=expected==='jpeg'?await oriented.jpeg({quality:85}).toBuffer():expected==='png'?await oriented.png().toBuffer():await oriented.webp({quality:85}).toBuffer();
      if(output.length>LIMITS.entry) throw invalid();
      // Sharp's default output omits EXIF/XMP/GPS, embedded thumbnails and uploaded metadata.
      asset.sha256=digest(output); accepted.set(asset.path,output);
    } catch { throw invalid(); }
  }
  accepted.set('publication.json',Buffer.from(stable(payload)));
  const canonical=writeZip(accepted); if(canonical.length>LIMITS.compressed) throw invalid();
  return { payload, bytes:canonical, sha256:digest(canonical) };
}
