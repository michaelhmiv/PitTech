import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { canonicalPack } from '../src/pack.ts';
import { digest } from '../src/core.ts';
import { execFileSync } from 'node:child_process';

const root = new URL('../../', import.meta.url), check = process.argv.includes('--check');
const paths = [
  ['app/src/androidTest/assets/cross-platform/native-cook-pack-cook-v1.pittech', 'app/src/androidTest/assets/cross-platform/relay-native-cook-v1.pittech'],
  ['ios/PitTechTests/Fixtures/android-cook-pack-cook-v1.pittech', 'ios/PitTechTests/Fixtures/relay-android-cook-v1.pittech'],
];
const fixtures = [];
for (const [source, destination] of paths) {
  const input = await readFile(new URL(source, root)), result = await canonicalPack(input);
  if (check) { if (!(await readFile(new URL(destination, root))).equals(result.bytes)) throw new Error('Canonical fixture differs: ' + destination); }
  else await writeFile(new URL(destination, root), result.bytes);
  fixtures.push({ source, sourceSHA256: digest(input), sourceBytes: input.length, destination, sha256: digest(result.bytes), bytes: result.bytes.length });
}
const blob = (path: string) => execFileSync('git', ['hash-object', path], { cwd: root }).toString().trim();
const provenance = { generatedWith: 'Node 22.23.3, locked sharp/libvips', verification: 'Development generation; backend Actions and native consumers pending',
  validatorBlobSHA: blob('api-service/src/pack.ts'), lockfileSHA256: digest(await readFile(new URL('api-service/package-lock.json', root))), fixtures };
const folder = new URL('contracts/fixtures/relay/', root), file = new URL('provenance.json', folder);
if (!check) { await mkdir(folder, { recursive: true }); await writeFile(file, JSON.stringify(provenance, null, 2) + '\n'); }
else {
  const recorded = JSON.parse(await readFile(file, 'utf8'));
  if (JSON.stringify(recorded.fixtures) !== JSON.stringify(fixtures) || recorded.validatorBlobSHA !== provenance.validatorBlobSHA || recorded.lockfileSHA256 !== provenance.lockfileSHA256) throw new Error('Fixture provenance is stale');
}
console.info(check ? 'Canonical fixtures and provenance match' : 'Canonical fixtures generated');
