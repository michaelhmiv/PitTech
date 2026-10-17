import type { Publications } from './publications.ts';
import type { LinkConfig } from './web.ts';

const escape=(value:unknown)=>String(value??'').replace(/[&<>"']/g,x=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[x]!));
const shell=(title:string,body:string,links:LinkConfig)=>`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(title)} · PitTech</title><style>body{margin:0;background:#f5f0e7;color:#242622;font:1.05rem/1.6 system-ui,sans-serif}main{max-width:42rem;margin:auto;padding:2rem 1.3rem}h1{font-size:clamp(1.8rem,6vw,2.8rem);line-height:1.15}h2{line-height:1.25}a{color:#934025}article{background:#fffdf7;border:1px solid #c7c5b9;border-radius:1rem;padding:1.2rem;margin:1rem 0}img{display:block;width:100%;height:auto;border-radius:.8rem}.button{display:inline-block;background:#a9472a;color:white;padding:.8rem 1.2rem;border-radius:.7rem;text-decoration:none}footer{border-top:1px solid #c7c5b9;margin-top:2rem;padding-top:1rem}.muted{color:#58665a}ul,ol{padding-left:1.4rem}p,li{overflow-wrap:anywhere}</style></head><body><main><p class="muted">PITTECH COOKBOOK</p>${body}<footer><p><a href="https://play.google.com/store/apps/details?id=com.pittech">Google Play</a>${links.appleStoreUrl?` · <a href="${escape(links.appleStoreUrl)}">App Store</a>`:''}</p><p>Browse, save and cook without an account. After installing, reopen this link. Nothing is imported or published automatically.</p><p><a href="https://pittech.app/privacy">Privacy</a> · <a href="mailto:sportfolioholdings@gmail.com">Content issues and support</a></p></footer></main></body></html>`;
export function publicWebRoute(raw:string){
  if(/^\/c\/[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/.test(raw))return '/c/:id';
  if(/^\/p\/[a-z0-9_]{3,24}$/.test(raw))return '/p/:handle';
  return undefined;
}
export async function publicPage(raw:string,publications:Publications,links:LinkConfig,blocked:string[]=[]){
  const route=publicWebRoute(raw),id=raw.split('/')[2];
  if(route==='/p/:handle'){
    const profile=await publications.profile(id,undefined,blocked),items=[];
    for(const entry of profile.items){try{const detail=await publications.get(entry.id,blocked);items.push(`<article><h2><a href="/c/${entry.id}">${escape(detail.payload.title)}</a></h2><p>${escape(detail.payload.kind)} · ${detail.likes} likes · ${detail.forks} forks</p></article>`);}catch(error){if((error as {status?:number}).status!==404)throw error;}}
    await publications.profile(id,undefined,blocked); // Recheck opt-in after fetching item content.
    return shell(profile.displayName,`<h1>${escape(profile.displayName)}</h1><p class="muted">@${escape(profile.handle)}</p>${items.join('')||'<p>No named publications here yet.</p>'}${profile.nextCursor?'<p>Open the cookbook in PitTech to browse more.</p>':''}<a class="button" href="pittech://profile/${id}">Open cookbook in PitTech</a>`,links);
  }
  if(route!=='/c/:id')throw new Error('Invalid public page route');
  const item=await publications.get(id,blocked),payload=item.payload;
  const recipes=payload.recipes.map(recipe=>{
    const photos=recipe.assets.map(asset=>`<figure><img src="https://api.pittech.app/v1/recipes/${id}/assets/${asset.id}" alt="${escape(asset.caption||recipe.title)}" loading="lazy"><figcaption>${escape(asset.caption)}</figcaption></figure>`).join('');
    const source=recipe.source?`<p class="muted">${recipe.source.removed?'Based on a removed recipe':`Based on a recipe shared by ${escape(recipe.source.displayName||'a PitTech cook')}`}</p>`:'';
    const story=recipe.story?`<p>Recorded cook story · ${recipe.story.measurements.length} original measurements${recipe.story.verdict.rating===null?'':` · ${recipe.story.verdict.rating}/5 rating`}. Open PitTech to inspect the recorded values.</p>${recipe.story.verdict.notes?`<p>${escape(recipe.story.verdict.notes)}</p>`:''}`:'';
    return `<article><h2>${escape(recipe.title)}</h2>${source}<p>${escape(recipe.description)}</p><p class="muted">${escape(recipe.foodType)}${recipe.cut?' · '+escape(recipe.cut):''}${recipe.weightGrams===null?'':' · '+escape(recipe.weightGrams/1000)+' kg'}${recipe.method?' · '+escape(recipe.method):''}</p>${photos}${recipe.ingredients.length?`<h3>Ingredients</h3><ul>${recipe.ingredients.map(i=>`<li>${escape(i.name)} · ${escape(i.amount)}</li>`).join('')}</ul>`:''}${recipe.instructions.length?`<h3>Instructions</h3><ol>${recipe.instructions.map(i=>`<li><strong>${escape(i.title)}</strong>${i.details?'<p>'+escape(i.details)+'</p>':''}</li>`).join('')}</ol>`:''}${story}</article>`;
  }).join('');
  return shell(payload.title,`<h1>${escape(payload.title)}</h1><p>Shared by ${escape(item.credit.displayName)}</p><p class="muted">${escape(payload.kind)} · ${item.likes} likes · ${item.forks} forks</p><p><a class="button" href="pittech://recipe/${id}">Preview in PitTech</a></p>${recipes}`,links);
}
