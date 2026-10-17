// Refresh the shared fixture using the actual server Cook Pack writer. IDs represent one captured export.
import { readFile,writeFile } from 'node:fs/promises';
import { publicationCookPack } from '../src/publication-cook-pack.ts';
const path=new URL('../../contracts/fixtures/public-native-v1.json',import.meta.url),fixture=JSON.parse(await readFile(path,'utf8'));
const media=fixture.detail.media[0],pack=await publicationCookPack(fixture.detail,new Map([[media.id,Buffer.from(fixture.imageBase64,'base64')]]));
fixture.packBase64=pack.bytes.toString('base64');fixture.packSha256=pack.summary.sha256;
const json=JSON.stringify(fixture,null,2)+'\n';
for(const path of ['contracts/fixtures/public-native-v1.json','app/src/debug/assets/public-native-v1.json','app/src/androidTest/assets/public-native-v1.json'])await writeFile(new URL('../../'+path,import.meta.url),json);
await writeFile(new URL('../../ios/PitTech/PublicUITestFixture.swift',import.meta.url),'#if DEBUG\nimport Foundation\nenum NativePublicUITestFixture { static var data: Data { Data(base64Encoded: "'+Buffer.from(json).toString('base64')+'")! } }\n#endif\n');
