/**
 * Generates contracts/fixtures/sync-canonical-v1.json: shared test vectors
 * pinning the private-sync canonical serialization.
 *
 * The snapshot-commit receipt's sha256 covers stable(envelope) as computed by
 * the server (api-service/src/pack.ts). Both native clients must serialize a
 * parsed envelope to exactly these bytes, or the receipt check can never
 * pass (issue #262). Vectors are produced by the server's own stable() over
 * JSON.parse of each case's raw input text, so JS parse semantics (225.0 ->
 * 225, key order discarded by the serializer) are baked in.
 *
 * Run: node scripts/sync-canonical-fixtures.ts   (from api-service/)
 */
import { writeFile } from 'node:fs/promises';
import { stable } from '../src/pack.ts';
import { digest } from '../src/core.ts';

const DEVICE = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const MUTATION = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';

// A readings row whose payload carries a whole-number double (225.0) and a
// raw degree sign; object keys deliberately out of canonical order.
const readingRow = (value: string, title: string) =>
  `{"recordId":"reading-1","recordType":"readings","deleted":false,` +
  `"version":{"clientId":"${DEVICE}","counter":7},` +
  `"payload":{"value":${value},"unit":"°F","probeName":"Pit","id":"reading-1","cookId":"cook-1",` +
  `"measurementType":"temperature","measuredAtUtcMillis":1761513600000,"timeZoneId":"UTC","source":"manual",` +
  `"qualityStatus":"valid","recordedAtUtcMillis":1761513600000,"timestampBasis":"measurement","samplingIntervalMillis":15000,` +
  `"title":${JSON.stringify(title)}},"assetIds":[]}`;

const cookRow =
  `{"assetIds":[],"payload":{"title":"Sunday brisket","id":"cook-1","status":"active",` +
  `"startedAtUtcMillis":1761513600000,"startedTimeZoneId":"America/New_York","createdAtUtcMillis":1761513600000,"updatedAtUtcMillis":1761513600000},` +
  `"deleted":false,"recordType":"cooks","recordId":"cook-1","version":{"counter":3,"clientId":"${DEVICE}"}}`;

const envelope = (records: string, extra = '') =>
  `{"records":[${records}],"mutationId":"${MUTATION}","conflicts":[],"contractVersion":1,"clientId":"${DEVICE}","baseVersion":4${extra}}`;

const cases: { name: string; input: string }[] = [
  { name: 'empty-envelope', input: envelope('') },
  { name: 'whole-number-doubles', input: envelope(readingRow('225.0', 'Wrapped')) },
  {
    name: 'decimal-values',
    input: envelope(
      readingRow('98.6', 'Probe check') + ',' +
      readingRow('0.5', 'Half') .replaceAll('reading-1', 'reading-2') + ',' +
      readingRow('13.75', 'Offset') .replaceAll('reading-1', 'reading-3') + ',' +
      readingRow('-4.5', 'Cold morning') .replaceAll('reading-1', 'reading-4') + ',' +
      readingRow('0.0001', 'Tiny') .replaceAll('reading-1', 'reading-5'),
    ),
  },
  {
    name: 'millis-and-counters',
    input: envelope(
      cookRow + ',' +
      `{"recordType":"targets","recordId":"target-1","version":{"counter":4294967296,"clientId":"${DEVICE}"},"deleted":false,` +
      `"payload":{"id":"target-1","cookId":"cook-1","targetType":"pit","value":250,"unit":"°F","effectiveAt":1761513600000},"assetIds":[]}`,
    ),
  },
  {
    name: 'nested-and-empty',
    input: envelope(
      `{"recordType":"recipes","recordId":"recipe-1","version":{"counter":1,"clientId":"${DEVICE}"},"deleted":false,` +
      `"payload":{"id":"recipe-1","title":"Sauce","meta":{"nested":{"deep":[1,2,{"z":1,"a":2}]},"emptyObject":{},"emptyList":[],"tags":[]},"servings":4},"assetIds":[]},` +
      `{"recordType":"recipes","recordId":"recipe-0","version":{"counter":1,"clientId":"${DEVICE}"},"deleted":true,"payload":null,"assetIds":[]}`,
    ),
  },
  {
    name: 'string-escaping',
    input: envelope(readingRow('225.0', 'Cook’s “best” brisket — café 1/2, say "hi" \\ back\tslash\nnewline 🔥 €5')),
  },
  {
    name: 'key-ordering',
    input: '{"b":1,"A":2,"a":3,"Z":4,"_":5,"nested":{"y":1,"B":2,"x":3}}',
  },
  {
    name: 'full-envelope-realistic',
    input: envelope(cookRow + ',' + readingRow('225.0', 'Cook’s brisket, wrapped at the stall')),
  },
];

const vectors = cases.map(({ name, input }) => {
  const canonical = stable(JSON.parse(input));
  return { name, input, canonical, sha256: digest(Buffer.from(canonical, 'utf8')) };
});

const fixture = {
  contractVersion: 1,
  description:
    'Canonical private-sync serialization vectors (issue #262). canonical/sha256 are the server stable() ' +
    '(api-service/src/pack.ts) of JSON.parse(input). Native clients must reproduce canonical byte-for-byte ' +
    'from the parsed input; the snapshot-commit receipt sha256 is over exactly these bytes. ' +
    'Regenerate: cd api-service && node scripts/sync-canonical-fixtures.ts',
  cases: vectors,
};

const out = new URL('../../contracts/fixtures/sync-canonical-v1.json', import.meta.url);
await writeFile(out, JSON.stringify(fixture, null, 2) + '\n');
console.info(`Wrote ${vectors.length} canonical vectors to contracts/fixtures/sync-canonical-v1.json`);
for (const v of vectors) console.info(`- ${v.name}: ${v.sha256.slice(0, 12)}… ${v.canonical.length} chars`);
