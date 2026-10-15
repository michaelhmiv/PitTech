import yauzl from 'yauzl';
import { LIMITS, requirePack } from './core.ts';

const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n; for (let i = 0; i < 8; i++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1); return c >>> 0;
});
export function crc32(bytes: Buffer): number {
  let c = 0xFFFFFFFF; for (const byte of bytes) c = crcTable[(c ^ byte) & 255] ^ (c >>> 8); return (c ^ 0xFFFFFFFF) >>> 0;
}
/** A canonical stored ZIP has no uploaded comments, extra fields, timestamps or spare entries. */
export function writeZip(files: Map<string, Buffer>): Buffer {
  const bodies: Buffer[] = [], central: Buffer[] = []; let offset = 0;
  for (const [name, body] of files) {
    const filename = Buffer.from(name), crc = crc32(body), local = Buffer.alloc(30), entry = Buffer.alloc(46);
    local.writeUInt32LE(0x04034B50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6);
    local.writeUInt16LE(0x21, 12); local.writeUInt32LE(crc, 14); local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(body.length, 22); local.writeUInt16LE(filename.length, 26);
    entry.writeUInt32LE(0x02014B50); entry.writeUInt16LE(20, 4); entry.writeUInt16LE(20, 6); entry.writeUInt16LE(0x800, 8);
    entry.writeUInt16LE(0x21, 14); entry.writeUInt32LE(crc, 16); entry.writeUInt32LE(body.length, 20);
    entry.writeUInt32LE(body.length, 24); entry.writeUInt16LE(filename.length, 28); entry.writeUInt32LE(offset, 42);
    bodies.push(local, filename, body); central.push(entry, filename); offset += local.length + filename.length + body.length;
  }
  const directory = Buffer.concat(central), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054B50); end.writeUInt16LE(files.size, 8); end.writeUInt16LE(files.size, 10);
  end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
  const result = Buffer.concat([...bodies, directory, end]); requirePack(result.length <= LIMITS.compressed); return result;
}
export async function readZip(input: Buffer, kind: 'cook'|'publication' = 'cook'): Promise<Map<string, Buffer>> {
  requirePack(input.length >= 22 && input.length <= LIMITS.compressed);
  let eocd = -1;
  for (let i = input.length - 22; i >= Math.max(0, input.length - 65557); i--) {
    if (input.readUInt32LE(i) === 0x06054B50 && i + 22 + input.readUInt16LE(i + 20) === input.length) { eocd = i; break; }
  }
  requirePack(eocd >= 0 && input.readUInt16LE(eocd + 4) === 0 && input.readUInt16LE(eocd + 6) === 0);
  const count = input.readUInt16LE(eocd + 10), directory = input.readUInt32LE(eocd + 16);
  requirePack(count >= (kind === 'publication' ? 1 : 2) && count <= LIMITS.entries && input.readUInt16LE(eocd + 8) === count &&
    directory + input.readUInt32LE(eocd + 12) === eocd);
  const zip = await new Promise<yauzl.ZipFile>((resolve, reject) => yauzl.fromBuffer(input,
    { lazyEntries: true, validateEntrySizes: true, strictFileNames: true }, (error, value) => error ? reject(error) : resolve(value!)));
  const files = new Map<string, Buffer>(); const ranges: [number, number][] = []; let expanded = 0;
  try {
    await new Promise<void>((resolve, reject) => {
      zip.on('error', reject); zip.on('end', resolve);
      zip.on('entry', (entry: yauzl.Entry) => { void (async () => {
        const name = entry.fileName;
        requirePack(!files.has(name) && (kind === 'publication'
          ? name === 'publication.json' || /^attachments\/[a-zA-Z0-9_-]+\.(jpg|png|webp)$/.test(name)
          : /^(manifest\.json|README\.txt|data\/pittech\.json)$/.test(name) || /^attachments\/[a-f\d-]{36}\.jpg$/i.test(name) || /^csv\/[a-z_]+\.csv$/.test(name)));
        requirePack(entry.uncompressedSize <= LIMITS.entry && entry.compressedSize <= LIMITS.compressed &&
          !(entry.generalPurposeBitFlag & 65) && [0, 8].includes(entry.compressionMethod) &&
          entry.versionNeededToExtract <= 20 && !entry.extraFields.some(f => f.id === 1) &&
          [0, 0x8000].includes((entry.externalFileAttributes >>> 16) & 0xF000));
        expanded += entry.uncompressedSize; requirePack(expanded <= LIMITS.expanded);
        const start = entry.relativeOffsetOfLocalHeader;
        requirePack(start + 30 <= directory && input.readUInt32LE(start) === 0x04034B50 &&
          input.readUInt16LE(start + 6) === entry.generalPurposeBitFlag && input.readUInt16LE(start + 8) === entry.compressionMethod);
        const nameSize = input.readUInt16LE(start + 26), extraSize = input.readUInt16LE(start + 28);
        const dataStart = start + 30 + nameSize + extraSize, end = dataStart + entry.compressedSize;
        requirePack(input.subarray(start + 30, start + 30 + nameSize).equals(Buffer.from(name)) && end <= directory);
        requirePack(ranges.every(([a, b]) => end <= a || start >= b)); ranges.push([start, end]);
        const stream = await new Promise<NodeJS.ReadableStream>((ok, fail) => zip.openReadStream(entry, (err, value) => err ? fail(err) : ok(value!)));
        const chunks: Buffer[] = []; let size = 0;
        for await (const chunk of stream) { const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk); size += bytes.length; requirePack(size <= entry.uncompressedSize && size <= LIMITS.entry); chunks.push(bytes); }
        const body = Buffer.concat(chunks); requirePack(size === entry.uncompressedSize && crc32(body) === entry.crc32);
        files.set(name, body); zip.readEntry();
      })().catch(reject); });
      zip.readEntry();
    });
    requirePack(files.size === count); return files;
  } finally { zip.close(); }
}
