import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { Ajv2020 } from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { readZip } from '../src/zip.ts';
import { golden } from './helpers.ts';

test('all four shared contracts compile and actual Cook Pack, publication and card fixtures validate', async () => {
  const ajv = new Ajv2020({ strict: true, strictTypes: false }); addFormats.default(ajv);
  const root = new URL('../../contracts/', import.meta.url);
  for (const name of ['cook-pack', 'publication', 'share-card', 'sync']) {
    const validate = ajv.compile(JSON.parse(await readFile(new URL(name + '.schema.json', root), 'utf8')));
    if (name === 'cook-pack') for (const platform of ['native', 'android']) {
      const files = await readZip(await readFile(golden(platform))); assert.ok(validate(JSON.parse(files.get('manifest.json')!.toString()).cookPack), JSON.stringify(validate.errors));
    }
    if (name === 'publication') assert.ok(validate(JSON.parse(await readFile(new URL('fixtures/anonymous-pork.json', root), 'utf8'))), JSON.stringify(validate.errors));
    if (name === 'share-card') assert.ok(validate(JSON.parse(await readFile(new URL('fixtures/share-card-v1.json', root), 'utf8'))), JSON.stringify(validate.errors));
  }
});
