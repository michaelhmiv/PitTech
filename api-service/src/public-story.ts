import {readFile} from 'node:fs/promises';
import {Ajv2020} from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
const contract=JSON.parse(await readFile(new URL('../../contracts/publication.schema.json',import.meta.url),'utf8'));
const ajv=new Ajv2020({strict:true,strictTypes:false});addFormats.default(ajv);
const schema=ajv.compile(contract.properties.recipes.items.properties.story.anyOf[0]);
/** Explicit public-only frozen story, carried by saved copies so gaps/quality/results survive republication. */
export function validPublicStory(value:unknown): boolean {
  if(!schema(value))return false;
  const s=value as {attemptId:string;channels:{id:string;role:string}[];measurements:{channelId:string;role:string;offsetMillis:number}[];milestones:{id:string;offsetMillis:number}[];gaps:{channelId:string;fromMillis:number;toMillis:number}[];foodOnOffsetMillis:number|null;foodOffOffsetMillis:number|null};
  const offset=(v:number|null)=>v===null||Number.isSafeInteger(v)&&v>=-604800000&&v<=2592000000;
  return new Set(s.channels.map(c=>c.id)).size===s.channels.length&&new Set(s.milestones.map(m=>m.id)).size===s.milestones.length&&
    offset(s.foodOnOffsetMillis)&&offset(s.foodOffOffsetMillis)&&(s.foodOnOffsetMillis===null||s.foodOffOffsetMillis===null||s.foodOffOffsetMillis>=s.foodOnOffsetMillis)&&
    s.measurements.every(m=>offset(m.offsetMillis)&&s.channels.some(c=>c.id===m.channelId&&c.role===m.role))&&s.milestones.every(m=>offset(m.offsetMillis))&&
    s.gaps.every(g=>offset(g.fromMillis)&&offset(g.toMillis)&&g.toMillis>=g.fromMillis&&s.channels.some(c=>c.id===g.channelId));
}
