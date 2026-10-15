import { randomUUID } from 'node:crypto';
import sharp from 'sharp';
import { ApiError } from './core.ts';
import { canonicalPack, stable } from './pack.ts';
import type { PublicationPayload } from './publication-pack.ts';
import { writeZip } from './zip.ts';

// Internal coordinate origin only. Both clients mark relativeTiming and never display it as a cook date.
const origin=Date.UTC(2000,0,1);
const arrays='photos dishes targetHistory recipeAssets mealPresets probes logReadingLinks targets probeAssignments reminderOccurrences events reminderRules dishRecipeSources lifecycles recordings reminders recipes companionPhotos activityTimers cooks readings devices ingredients companionRecords recipeRevisions results'.split(' ');
export async function publicationCookPack(input:{id:string;revisionId:string;payload:PublicationPayload;credit:{displayName:string}},photos:Map<string,Buffer>){
  const data:Record<string,unknown>={schemaVersion:10};for(const key of arrays)data[key]=[];
  const rows=(key:string)=>data[key] as Record<string,unknown>[];
  const attachments=new Map<string,Buffer>(),packId=randomUUID(),now=Date.now(),entries:unknown[]=[];
  const source={itemId:input.id,revisionId:input.revisionId,displayName:input.credit.displayName,removed:false};
  let hero:string|undefined;
  for(const [index,recipe] of input.payload.recipes.entries()){
    const recipeId=randomUUID(),revisionId=randomUUID(),entryId=randomUUID(),story=recipe.story;
    const channels=new Map((story?.channels??[]).map(c=>[c.id,{...c,probeId:randomUUID()}]));
    const anchor=story?.foodOnOffsetMillis!=null?origin+story.foodOnOffsetMillis:null;
    const observations=(story?.milestones??[]).map(m=>({sourceEventId:randomUUID(),occurredAtUtcMillis:origin+m.offsetMillis,timeZoneId:'UTC',title:m.text||m.action||'Recorded action',details:m.text,action:m.action,offsetMillis:anchor===null?null:origin+m.offsetMillis-anchor,recordedTemperatures:[]}));
    if(story?.foodOnOffsetMillis!=null && !observations.some(o=>o.action==='food_on'))observations.push({sourceEventId:randomUUID(),occurredAtUtcMillis:origin+story.foodOnOffsetMillis,timeZoneId:'UTC',title:'Food on',details:'',action:'food_on',offsetMillis:0,recordedTemperatures:[]});
    if(story?.foodOffOffsetMillis!=null && !observations.some(o=>o.action==='remove'))observations.push({sourceEventId:randomUUID(),occurredAtUtcMillis:origin+story.foodOffOffsetMillis,timeZoneId:'UTC',title:'Food off',details:'',action:'remove',offsetMillis:anchor===null?null:origin+story.foodOffOffsetMillis-anchor,recordedTemperatures:[]});
    const readings=(story?.measurements??[]).map(m=>{
      const channel=channels.get(m.channelId);if(!channel)throw new ApiError(422,'invalid_publication');
      return {id:randomUUID(),probeId:channel.probeId,probeName:channel.label,measurementType:m.role,value:m.valueCelsius,unit:'°C',measuredAtUtcMillis:origin+m.offsetMillis,timeZoneId:'UTC',source:m.quality==='manual'?'manual':'recorded',qualityStatus:m.quality==='recovered'?'recovered':'valid',samplingIntervalMillis:0};
    });
    const snapshot={version:1,name:recipe.title,description:recipe.description,tags:recipe.tags,
      draft:{title:recipe.title,dishes:[{name:recipe.title,foodType:recipe.foodType,cut:recipe.cut??'',weight:recipe.weightGrams===null?'':String(recipe.weightGrams/1000),weightUnit:'kg',placement:'',grade:'',thickness:'',prep:recipe.method??'',
        ingredients:recipe.ingredients.map(i=>({name:i.name,stage:'main_ingredient',brand:'',amount:'',unit:i.amount}))}]},
      instructions:recipe.instructions.map(i=>({id:randomUUID(),title:i.title,details:i.details,method:i.method??null,action:i.action,offsetMillis:i.suggestedOffsetMillis,durationMillis:null,sourceEventId:null})),
      observations,targets:[],resultNotes:story?.verdict.notes??'',rating:story?.verdict.rating??null,cookAgain:story?.verdict.cookAgain??null,
      smokingAnchorUtcMillis:anchor,preferredPitTemperatureF:null,servings:'',publicSource:{...source,relativeTiming:true}};
    const snapshotJson=stable(snapshot),savedStory={version:1,attemptId:story?.attemptId??null,anchorUtcMillis:anchor,observations,readings,
      connectionGaps:(story?.gaps??[]).flatMap(g=>[origin+g.fromMillis,origin+g.toMillis]),results:[],publicStory:story};
    rows('recipes').push({id:recipeId,name:recipe.title,description:recipe.description,tagsJson:JSON.stringify(recipe.tags),currentRevisionId:revisionId,createdAtUtcMillis:now,updatedAtUtcMillis:now,favorite:false});
    rows('recipeRevisions').push({id:revisionId,recipeId,revisionNumber:1,snapshotSchemaVersion:1,snapshotJson,createdAtUtcMillis:now,changeNotes:'Saved public recipe'});
    const photoIds:string[]=[];
    for(const asset of recipe.assets){
      const raw=photos.get(asset.id);if(!raw)throw new ApiError(503,'media_unavailable');
      const image=await sharp(raw,{limitInputPixels:20_000_000,failOn:'warning'}).rotate().flatten({background:'#ffffff'}).jpeg({quality:85}).toBuffer();
      const photoId=randomUUID(),assetId=randomUUID();photoIds.push(photoId);if(input.payload.heroAssetId===asset.id)hero=photoId;
      attachments.set(`attachments/${photoId}.jpg`,image);attachments.set(`attachments/${assetId}.jpg`,image);
      rows('companionPhotos').push({id:photoId,recordId:packId,archivePath:`attachments/${photoId}.jpg`,mimeType:'image/jpeg',caption:asset.caption,dishIndex:index,action:entryId});
      rows('recipeAssets').push({id:assetId,recipeRevisionId:revisionId,relativeFilePath:`photos/${assetId}.jpg`,archivePath:`attachments/${assetId}.jpg`,mimeType:'image/jpeg',caption:asset.caption,createdAtUtcMillis:now});
    }
    entries.push({id:entryId,sourceRevisionId:revisionId,snapshotJson,story:savedStory,included:true,includeStory:story!==null,photoIds});
  }
  const kind=input.payload.recipes.length===1?'recipe':'collection';
  rows('companionRecords').push({id:packId,kind:kind==='recipe'?'recipe_reference':'collection_revision',title:input.payload.title,
    payload:{version:1,title:input.payload.title,identityMode:'anonymous',collectionId:packId,...(hero?{heroPhotoId:hero}:{}),entries},created:now,updated:now});
  const metadata={contractVersion:1,id:packId,kind,recipeRevisionIds:rows('recipeRevisions').map(r=>r.id),dishIds:[],includedStory:input.payload.recipes.some(r=>r.story!==null),source};
  const manifest={format:'PitTech complete cook archive',archiveVersion:10,includesPreferences:false,exportedAtUtc:new Date(now).toISOString(),cookPack:metadata};
  const result=await canonicalPack(writeZip(new Map([['manifest.json',Buffer.from(stable(manifest))],['data/pittech.json',Buffer.from(stable(data))],...attachments])));
  return result;
}
