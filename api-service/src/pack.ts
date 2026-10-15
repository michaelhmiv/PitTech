import sharp from 'sharp';
import { validPublicStory } from './public-story.ts';
import { readFile } from 'node:fs/promises';
import { Ajv2020 } from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { LIMITS, requirePack, digest, type Summary } from './core.ts';
import { readZip, writeZip } from './zip.ts';

type ObjectValue = Record<string, unknown>;
export const object = (value: unknown): ObjectValue => {
  requirePack(value !== null && typeof value === 'object' && !Array.isArray(value)); return value as ObjectValue;
};
export const array = (value: unknown, max = 250000): unknown[] => {
  if (value === undefined) return []; requirePack(Array.isArray(value) && value.length <= max); return value;
};
const rows = (root: ObjectValue, name: string) => array(root[name]).map(object);
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[\da-f]{8}(-[\da-f]{4}){3}-[\da-f]{12}$/i.test(value);
const string = (value: unknown, max = 32768): value is string => {
  if (typeof value !== 'string' || value.length > max * 2) return false;
  if (value.length <= max) return true;
  let count = 0; for (const _point of value) if (++count > max) return false; return true;
};
const utc = (value: unknown): value is number => typeof value === 'number' && value > 0 && value <= 253402300799999;
const text = (value: unknown, max: number) => { requirePack(string(value, max) && value.trim().length > 0); };
function known(value: unknown, fields: string): ObjectValue {
  const result = object(value), allowed = new Set(fields.split(' '));
  for (const [key, v] of Object.entries(result)) requirePack(allowed.has(key) || v === null);
  return result;
}
function bounded(value: unknown, depth = 0): void {
  requirePack(depth <= 24);
  if (typeof value === 'number') requirePack(Number.isFinite(value));
  else if (typeof value === 'string') requirePack(value.length <= LIMITS.entry);
  else if (Array.isArray(value)) { requirePack(value.length <= 250000); for (const v of value) bounded(v, depth + 1); }
  else if (value !== null && typeof value === 'object') for (const [key, v] of Object.entries(value)) {
    requirePack(!['__proto__', 'constructor', 'prototype'].includes(key));
    if (v != null) requirePack(!/^(credentials?|password|accessToken|refreshToken|ownerSecret|email|deviceId|sourceDeviceId|latitude|longitude|gps|localPath|uri)$/i.test(key));
    if (/(UtcMillis|effectiveAt)$/.test(key) && v != null) requirePack(utc(v));
    bounded(v, depth + 1);
  }
}
function parse(value: unknown): unknown {
  requirePack(string(value, LIMITS.entry)); const parsed: unknown = JSON.parse(value); bounded(parsed); return parsed;
}
export function stable(value: unknown): string {
  return JSON.stringify(value, (_key, item) => item && typeof item === 'object' && !Array.isArray(item)
    ? Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]])) : item);
}
const measurementFields = 'id sourceReadingId probeId probeName measurementType value unit measuredAtUtcMillis timeZoneId source samplingIntervalMillis qualityStatus timestampBasis';
function measurement(value: unknown, story: boolean): void {
  const m = known(value, measurementFields); text(m.probeName, 200); text(m.measurementType, 120);
  requirePack(typeof m.value === 'number' && Number.isFinite(m.value) && ['°C', '°F'].includes(String(m.unit)) && utc(m.measuredAtUtcMillis));
  for (const key of ['id', 'sourceReadingId', 'probeId']) if (m[key] != null) requirePack(uuid(m[key]));
  if (story) requirePack(uuid(m.id) && ['manual', 'recorded'].includes(String(m.source)));
}
function observation(value: unknown): void {
  const o = known(value, 'sourceEventId occurredAtUtcMillis timeZoneId title details action offsetMillis recordedTemperatures');
  requirePack(uuid(o.sourceEventId) && utc(o.occurredAtUtcMillis)); text(o.title, 32768);
  for (const m of array(o.recordedTemperatures, 100)) measurement(m, false);
}
function ingredient(value: unknown): void {
  const i = known(value, 'name stage brand amount unit'); text(i.name, 32768);
  if (i.amount) requirePack(string(i.amount) && Number.isFinite(Number(i.amount)) && Number(i.amount) > 0);
}
function snapshot(value: unknown): ObjectValue {
  const s = known(parse(value), 'version name description tags draft instructions observations targets resultNotes rating cookAgain smokingAnchorUtcMillis preferredPitTemperatureF servings publicSource');
  requirePack(s.version === 1); text(s.name, 200);
  if(s.publicSource!=null){
    const source=known(s.publicSource,'itemId revisionId displayName removed relativeTiming');
    requirePack(uuid(source.itemId)&&uuid(source.revisionId)&&string(source.displayName,160)&&typeof source.removed==='boolean'&&typeof source.relativeTiming==='boolean');
  }

  requirePack(array(s.tags, 100).every(v => string(v, 200)));
  const draft = known(s.draft, 'title smoker setpoint unit notes fuel wood dishes'); requirePack(draft.notes == null || draft.notes === '');
  const dishes = array(draft.dishes, 1); requirePack(dishes.length === 1);
  for (const value of dishes) {
    const d = known(value, 'name foodType cut weight weightUnit placement grade thickness prep ingredients'); text(d.name, 200);
    for (const i of array(d.ingredients, 100)) ingredient(i);
  }
  const ids = new Set();
  for (const value of array(s.instructions, 500)) {
    const i = known(value, 'id title details method offsetMillis durationMillis sourceEventId action');
    requirePack(uuid(i.id) && !ids.has(i.id)); ids.add(i.id); text(i.title, 32768);
    if (i.action != null) requirePack(string(i.action,80));
    if (i.durationMillis != null) requirePack(typeof i.durationMillis === 'number' && i.durationMillis >= 0);
  }
  const observations = array(s.observations, 5000).map(object);
  requirePack(new Set(observations.map(o => o.sourceEventId)).size === observations.length);
  for (const o of observations) {
    observation(o);
    if (s.smokingAnchorUtcMillis == null) requirePack(o.offsetMillis == null);
    else requirePack(typeof o.occurredAtUtcMillis === 'number' && typeof s.smokingAnchorUtcMillis === 'number' && o.offsetMillis === o.occurredAtUtcMillis - s.smokingAnchorUtcMillis);
  }
  for (const t of array(s.targets, 200)) known(t, 'dish type value unit explanation');
  if (s.rating != null) requirePack(typeof s.rating === 'number' && s.rating >= 0 && s.rating <= 5);
  return s;
}
// These are the public projection fields in both clients' CookPackHistory implementations.
const fields: Record<string, string> = {
  cooks: 'id title status startedAtUtcMillis startedTimeZoneId endedAtUtcMillis initialSetpointValue initialSetpointUnit createdAtUtcMillis updatedAtUtcMillis fuelType woodOrPelletBlend outdoorTemperatureValue outdoorTemperatureUnit',
  dishes: 'id cookId name foodType cut weightValue weightUnit pieceCount startingCondition boneIn placement prepNotes createdAtUtcMillis updatedAtUtcMillis gradeOrSource thicknessNotes',
  ingredients: 'id cookId dishId stage name amountText brand amountValue amountUnit appliedAtUtcMillis notes createdAtUtcMillis',
  events: 'id cookId dishId eventType title details occurredAtUtcMillis recordedAtUtcMillis timeZoneId source createdAtUtcMillis updatedAtUtcMillis noteMetadataJson temperatureContextJson',
  readings: 'id cookId dishId probeId probeName measurementType value unit measuredAtUtcMillis timeZoneId source qualityStatus recordedAtUtcMillis timestampBasis samplingIntervalMillis',
  targets: 'id cookId dishId targetType value unit scope explanation createdAtUtcMillis',
  results: 'id cookId dishId resultType numericValue unit textValue notes recordedAtUtcMillis',
  probes: 'id cookId assignedDishId name measurementType source createdAtUtcMillis',
  photos: 'id cookId dishId eventId mimeType caption capturedAtUtcMillis addedAtUtcMillis originalFileName archivePath temperatureContextJson',
  logReadingLinks: 'readingId eventId', lifecycles: 'cookId timingMode', targetHistory: 'id cookId dishId targetType value unit effectiveAt',
  recipes: 'id name description tagsJson currentRevisionId createdAtUtcMillis updatedAtUtcMillis favorite',
  recipeRevisions: 'id recipeId parentRevisionId revisionNumber sourceCookId sourceDishId snapshotSchemaVersion snapshotJson createdAtUtcMillis changeNotes',
  recipeAssets: 'id recipeRevisionId relativeFilePath mimeType caption sourcePhotoId createdAtUtcMillis archivePath',
  companionRecords: 'id kind cookId title payload createdAtUtcMillis updatedAtUtcMillis created updated',
  companionPhotos: 'id recordId mimeType caption dishIndex action archivePath',
};
const emptyTables = 'devices recordings probeAssignments reminders reminderRules reminderOccurrences mealPresets activityTimers dishRecipeSources'.split(' ');
const ajv = new Ajv2020({ strict: true, strictTypes: false }); addFormats.default(ajv);
const validateMeta = ajv.compile(JSON.parse(await readFile(new URL('../../contracts/cook-pack.schema.json', import.meta.url), 'utf8')));
export async function canonicalPack(input: Buffer): Promise<{ bytes: Buffer; summary: Summary }> {
  const files = await readZip(input), rawManifest = files.get('manifest.json'), rawData = files.get('data/pittech.json');
  requirePack(rawManifest && rawData);
  const manifest = known(parse(rawManifest.toString('utf8')), 'format archiveVersion exportedAtUtc appVersion includesPreferences counts cookPack');
  const data = known(parse(rawData.toString('utf8')), 'schemaVersion preferences ' + [...Object.keys(fields), ...emptyTables].join(' '));
  requirePack(manifest.archiveVersion === 10 && data.schemaVersion === 10 && manifest.includesPreferences === false && data.preferences == null &&
    manifest.format === 'PitTech complete cook archive' && validateMeta(manifest.cookPack));
  requirePack(string(manifest.exportedAtUtc, 128) && Number.isFinite(Date.parse(manifest.exportedAtUtc)) && (manifest.appVersion == null || string(manifest.appVersion, 64)));
  const meta = object(manifest.cookPack), selected = array(meta.recipeRevisionIds, 20), dishIds = array(meta.dishIds, 20);
  if (meta.kind === 'recipe') requirePack(selected.length === 1);
  for (const table of emptyTables) requirePack(array(data[table]).length === 0);
  for (const [table, names] of Object.entries(fields)) {
    const ids = new Set();
    for (const row of rows(data, table)) {
      known(row, names);
      if (names.split(' ').includes('id')) { requirePack(uuid(row.id) && !ids.has(row.id)); ids.add(row.id); }
      for (const [key, value] of Object.entries(row)) {
        if (value == null || key === 'payload') continue;
        if (key.endsWith('Id') && !key.endsWith('TimeZoneId') && key !== 'timeZoneId') requirePack(uuid(value));
        else if (key.endsWith('UtcMillis') || key === 'effectiveAt') requirePack(utc(value));
        else if (['value', 'numericValue', 'weightValue', 'amountValue', 'initialSetpointValue', 'outdoorTemperatureValue', 'pieceCount', 'samplingIntervalMillis', 'revisionNumber', 'snapshotSchemaVersion', 'dishIndex', 'created', 'updated'].includes(key)) requirePack(typeof value === 'number' && Number.isFinite(value));
        else if (['favorite', 'boneIn'].includes(key)) requirePack(typeof value === 'boolean');
        else requirePack(string(value, key === 'snapshotJson' ? LIMITS.entry : 32768));
      }
      if (row.source != null) requirePack(['manual', 'recorded'].includes(String(row.source)));
      if (row.noteMetadataJson != null) {
        const note = known(parse(row.noteMetadataJson), 'version category action method durationMillis startedAtUtcMillis endedAtUtcMillis dishIds ingredients');
        requirePack(array(note.dishIds, 20).every(id => dishIds.includes(id)));
        for (const item of array(note.ingredients, 100)) ingredient(item);
      }
      if (row.temperatureContextJson != null) for (const item of array(parse(row.temperatureContextJson), 12)) known(item, 'name value unit observedAtUtcMillis source timestampBasis');
    }
  }
  const revisions = rows(data, 'recipeRevisions'), recipes = rows(data, 'recipes');
  requirePack(selected.length >= 1 && revisions.length === selected.length && recipes.length === selected.length && selected.every(id => revisions.some(r => r.id === id)));
  for (const r of revisions) {
    requirePack(r.snapshotSchemaVersion === 1 && r.revisionNumber === 1 && r.sourceCookId == null && r.sourceDishId == null && r.parentRevisionId == null &&
      recipes.some(recipe => recipe.id === r.recipeId && recipe.currentRevisionId === r.id));
    snapshot(r.snapshotJson);
  }
  for (const recipe of recipes) requirePack(array(parse(recipe.tagsJson), 100).every(v => string(v, 200)));
  const records = rows(data, 'companionRecords'); requirePack(records.length === 1);
  const record = records[0]; requirePack(record.id === meta.id && record.cookId == null && ['recipe_reference', 'collection_revision'].includes(String(record.kind)));
  const document = known(record.payload, 'version title identityMode sourceCookId heroPhotoId collectionId parentRevisionId sourceHistory card entries');
  requirePack(document.version === 1 && document.identityMode === 'anonymous' && document.sourceCookId == null && document.sourceHistory == null && document.parentRevisionId == null && document.collectionId === meta.id);
  text(document.title, 160);
  if (document.card != null) {
    const card = known(document.card, 'version theme hero cropX cropY zoom note includeVerdict unit');
    requirePack(card.version === 1 && card.theme === 'classic' && ['none', 'photo', 'chart'].includes(String(card.hero)) && ['auto', '°C', '°F'].includes(String(card.unit)) &&
      typeof card.cropX === 'number' && card.cropX >= 0 && card.cropX <= 1 && typeof card.cropY === 'number' && card.cropY >= 0 && card.cropY <= 1 &&
      typeof card.zoom === 'number' && card.zoom >= 1 && card.zoom <= 3 && string(card.note, 2000) && typeof card.includeVerdict === 'boolean');
  }
  const entries = array(document.entries, 20).map(object), photos = rows(data, 'companionPhotos'), assets = rows(data, 'recipeAssets');
  requirePack(entries.length === revisions.length && new Set(entries.map(e => e.id)).size === entries.length &&
    new Set(entries.map(e => e.sourceRevisionId)).size === entries.length && entries.some(e => e.includeStory === true) === meta.includedStory);
  const chosenPhotos = new Set<unknown>();
  for (const [index, entry] of entries.entries()) {
    known(entry, 'id sourceDishId sourceRevisionId snapshotJson story included includeStory photoIds');
    requirePack(uuid(entry.id) && entry.included === true && typeof entry.includeStory === 'boolean' && entry.sourceDishId == null);
    const revision = revisions.find(r => r.id === entry.sourceRevisionId); requirePack(revision);
    const s = snapshot(entry.snapshotJson); requirePack(stable(s) === stable(snapshot(revision.snapshotJson)));
    const story = known(entry.story, 'version attemptId anchorUtcMillis observations readings connectionGaps results publicStory');
    requirePack(story.version === 1 && Array.isArray(story.readings));
    if(story.publicStory != null) requirePack(validPublicStory(story.publicStory) && object(story.publicStory).attemptId === story.attemptId);
    if (story.attemptId != null) requirePack(uuid(story.attemptId));
    if (meta.kind === 'cook') requirePack(dishIds.includes(story.attemptId));
    for (const sample of array(story.readings)) measurement(sample, true);
    for (const o of array(story.observations, 5000)) observation(o);
    for (const result of array(story.results, 1000)) known(result, 'type numericValue textValue unit');
    requirePack(array(story.connectionGaps, 10000).every(utc));
    if (array(story.readings).length) requirePack(uuid(story.attemptId));
    if (!entry.includeStory) requirePack(story.attemptId == null && story.anchorUtcMillis == null &&
      story.publicStory == null && ['readings', 'observations', 'connectionGaps', 'results'].every(k => array(story[k]).length === 0) &&
      array(s.observations).length === 0 && s.rating == null && s.cookAgain == null && !s.resultNotes);
    const selectedPhotos = array(entry.photoIds, 100); requirePack(new Set(selectedPhotos).size === selectedPhotos.length);
    const entryPhotos = selectedPhotos.map(id => {
      requirePack(uuid(id) && !chosenPhotos.has(id)); chosenPhotos.add(id);
      const photo = photos.find(p => p.id === id); requirePack(photo && photo.recordId === record.id && photo.action === entry.id && photo.dishIndex === index); return photo;
    });
    const hashes = (items: ObjectValue[]) => items.map(p => {
      const bytes = files.get(String(p.archivePath)); requirePack(bytes); return digest(bytes) + '\0' + String(p.caption ?? '');
    }).sort();
    requirePack(stable(hashes(entryPhotos)) === stable(hashes(assets.filter(a => a.recipeRevisionId === revision.id))));
  }
  requirePack(photos.length === chosenPhotos.size && (document.heroPhotoId == null || chosenPhotos.has(document.heroPhotoId)));
  const cooks = rows(data, 'cooks'), dishes = rows(data, 'dishes'), events = rows(data, 'events'), readings = rows(data, 'readings'), probes = rows(data, 'probes');
  if (meta.kind === 'cook') {
    requirePack(cooks.length === 1 && cooks[0].status === 'completed' && utc(cooks[0].startedAtUtcMillis) && utc(cooks[0].endedAtUtcMillis) && cooks[0].endedAtUtcMillis >= cooks[0].startedAtUtcMillis &&
      dishes.length === dishIds.length && dishes.length === entries.length && dishIds.every(id => dishes.some(d => d.id === id)) && entries.every(e => e.includeStory));
    requirePack(new Set(entries.map(e => object(e.story).attemptId)).size === dishIds.length);
  } else requirePack(Object.keys(fields).filter(k => !['recipes', 'recipeRevisions', 'recipeAssets', 'companionRecords', 'companionPhotos'].includes(k)).every(k => rows(data, k).length === 0));
  for (const table of ['dishes', 'ingredients', 'events', 'readings', 'targets', 'results', 'probes', 'photos', 'lifecycles', 'targetHistory']) for (const row of rows(data, table)) {
    requirePack(cooks.some(c => c.id === row.cookId));
    if (row.dishId != null) requirePack(dishIds.includes(row.dishId));
    if (row.assignedDishId != null) requirePack(dishIds.includes(row.assignedDishId));
    if (row.probeId != null) requirePack(probes.some(p => p.id === row.probeId));
    if (row.eventId != null) requirePack(events.some(e => e.id === row.eventId));
  }
  for (const link of rows(data, 'logReadingLinks')) requirePack(readings.some(r => r.id === link.readingId) && events.some(e => e.id === link.eventId));
  for (const reading of readings) {
    measurement(Object.fromEntries(Object.entries(reading).filter(([key]) => measurementFields.split(' ').includes(key))), true);
    if (reading.dishId == null) requirePack(['pit', 'pit_ambient', 'setpoint', 'grill_temperature', 'pit_temperature', 'pit_probe', 'ambient_temperature'].includes(String(reading.measurementType)));
  }
  for (const a of assets) requirePack(selected.includes(a.recipeRevisionId) && a.sourcePhotoId == null && a.relativeFilePath === `photos/${a.id}.jpg`);
  const selectedHashes = new Set(photos.map(p => digest(files.get(String(p.archivePath))!)));
  for (const p of rows(data, 'photos')) requirePack(files.has(String(p.archivePath)) && selectedHashes.has(digest(files.get(String(p.archivePath))!)));
  const media = [...assets, ...photos, ...rows(data, 'photos')]; const paths = new Set(media.map(p => String(p.archivePath)));
  requirePack([...files.keys()].filter(p => p.startsWith('attachments/')).length === paths.size);
  if (manifest.counts != null) for (const [name, count] of Object.entries(object(manifest.counts))) {
    const key = name === 'timelineEvents' ? 'events' : name; requirePack(key in data && Array.isArray(data[key]) && array(data[key]).length === count);
  }
  const output = new Map<string, Buffer>([['manifest.json', Buffer.from(stable(manifest))], ['README.txt', Buffer.from('PitTech Cook Pack. Preview before choosing recipes or recorded history.\n')], ['data/pittech.json', Buffer.from(stable(data))]]);
  const encoded = new Map<string, Buffer>(); sharp.cache(false); sharp.concurrency(1);
  for (const path of [...paths].sort()) {
    const source = files.get(path); requirePack(source && media.filter(p => p.archivePath === path).every(p => p.archivePath === `attachments/${p.id}.jpg` && p.mimeType === 'image/jpeg'));
    const hash = digest(source); let image = encoded.get(hash);
    if (!image) {
      const decoder = sharp(source, { limitInputPixels: 4096 * 4096, failOn: 'warning' });
      const metadata = await decoder.metadata(); requirePack(metadata.format === 'jpeg' && (metadata.pages ?? 1) === 1 && metadata.width <= 4096 && metadata.height <= 4096);
      // Re-encode with no metadata. Even a forged EXIF/GPS/IPTC/XMP upload cannot survive canonicalization.
      image = await decoder.rotate().jpeg({ quality: 90 }).toBuffer(); requirePack(image.length <= LIMITS.entry); encoded.set(hash, image);
    }
    output.set(path, image);
  }
  requirePack([...output.values()].every(b => b.length <= LIMITS.entry) && [...output.values()].reduce((n, b) => n + b.length, 0) <= LIMITS.expanded);
  const bytes = writeZip(output);
  return { bytes, summary: { kind: meta.kind as Summary['kind'], dishes: entries.length, sha256: digest(bytes), bytes: bytes.length } };
}
