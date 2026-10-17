import { createServer, type IncomingMessage } from 'node:http';
import { randomUUID } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { mkdir, mkdtemp, rm, readdir, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import proxyaddr from 'proxy-addr';
import { ApiError, LIMITS, ownerHash, rateIdentity } from './core.ts';
import type { Relay } from './relay.ts';
import { validate } from './validation.ts';

type Options = { ipKey: string; trustedProxies?: string[]; uploadsEnabled: boolean; tempRoot?: string;
  log?: (value: { requestId: string; method: string; route: string; status: number }) => void };
export const tempRoot = join(tmpdir(), 'pittech-api-uploads');
export async function pruneUploads(root: string, now = Date.now()) {
  for (const entry of await readdir(root, { withFileTypes: true }).catch(() => [])) if (entry.isDirectory() && /^upload-[\w-]+$/.test(entry.name)) {
    const path = join(root, entry.name); if ((await stat(path)).mtimeMs < now - 30 * 60000) await rm(path, { force: true, recursive: true });
  }
}
function owner(req: IncomingMessage) {
  const secret = /^Bearer ([\w-]+)$/.exec(req.headers.authorization ?? '')?.[1] ?? ''; ownerHash(secret); return secret;
}
export function makeServer(relay: Relay, options: Options) {
  const trust = proxyaddr.compile(options.trustedProxies ?? []); let active = 0, uploading = false;
  const root = options.tempRoot ?? tempRoot;
  const server = createServer({ maxHeaderSize: 8192 }, async (req, res) => {
    const requestId = randomUUID(), method = ['GET', 'POST', 'DELETE'].includes(req.method ?? '') ? req.method! : 'OTHER';
    let route = 'unknown', directory: string | undefined, counted = false, upload = false;
    const release = () => { if (counted) { active--; counted = false; } };
    res.once('finish', release); res.once('close', release);
    res.setTimeout(30000, () => res.destroy());
    res.setHeader('X-Request-Id', requestId); res.setHeader('Cache-Control', 'private, no-store');
    res.setHeader('Referrer-Policy', 'no-referrer'); res.setHeader('X-Content-Type-Options', 'nosniff');
    res.on('finish', () => options.log?.({ requestId, method, route, status: res.statusCode }));
    const json = (status: number, value: unknown) => { res.statusCode = status; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(value)); };
    try {
      if (req.url === '/healthz' && method === 'GET') { route = '/healthz'; json(200, { status: 'ok' }); return; }
      const match = /^\/packs\/([^/?#]{1,128})(\/renew)?$/.exec(req.url ?? '');
      const isUpload = req.url === '/packs' && method === 'POST';
      if (!isUpload && !(match && (method === 'GET' && !match[2] || method === 'DELETE' && !match[2] || method === 'POST' && match[2]))) throw new ApiError(404, 'unavailable');
      route = isUpload ? '/packs' : match![2] ? '/packs/:code/renew' : '/packs/:code';
      const now = relay.clock(), category = isUpload ? 'upload' : method === 'GET' ? 'download' : 'manage';
      const period = category === 'upload' ? 86400000 : 3600000, bucket = Math.floor(now / period);
      const address = proxyaddr(req, trust), key = category + ':' + rateIdentity(options.ipKey, address, category + ':' + bucket);
      if (!await relay.repository.rate(key, category === 'upload' ? 20 : category === 'download' ? 120 : 60, (bucket + 1) * period)) throw new ApiError(429, 'rate_limited');
      if (active >= 2) throw new ApiError(503, 'relay_busy'); active++; counted = true;
      if (isUpload) {
        if (!options.uploadsEnabled) throw new ApiError(503, 'uploads_paused');
        const secret = owner(req), retryKey = String(req.headers['idempotency-key'] ?? '');
        if (!/^[a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(retryKey)) throw new ApiError(400, 'idempotency_key_required');
        if (!['application/octet-stream', 'application/zip'].includes(req.headers['content-type'] ?? '') || ![undefined, 'identity'].includes(req.headers['content-encoding'])) throw new ApiError(415, 'pack_content_type_required');
        if (Number(req.headers['content-length'] ?? 0) > LIMITS.compressed) throw new ApiError(413, 'pack_too_large');
        if (uploading) throw new ApiError(503, 'relay_busy'); uploading = true; upload = true;
        await mkdir(root, { recursive: true, mode: 0o700 }); directory = await mkdtemp(join(root, 'upload-'));
        const input = join(directory, 'input.pittech'), output = join(directory, 'accepted.pittech'); let bytes = 0;
        await pipeline(req, new Transform({ transform(chunk, _encoding, callback) { bytes += chunk.length; callback(bytes > LIMITS.compressed ? new ApiError(413, 'pack_too_large') : null, chunk); } }), createWriteStream(input, { mode: 0o600, flags: 'wx' }));
        const summary = await validate(input, output);
        if (res.destroyed) throw new ApiError(400, 'upload_interrupted');
        const result = await relay.upload(output, summary, secret, retryKey);
        // Release temporary files and the upload slot before the client can retry a 201 response.
        await rm(directory, { recursive: true, force: true }); directory = undefined; uploading = false; upload = false;
        json(201, result);
      } else {
        let code: string; try { code = decodeURIComponent(match![1]); } catch { throw new ApiError(404, 'unavailable'); }
        if (method === 'GET') {
          const result = await relay.download(code); res.statusCode = 200;
          res.setHeader('Content-Type', 'application/octet-stream'); res.setHeader('Content-Disposition', 'attachment; filename="PitTech.pittech"');
          res.setHeader('Content-Length', result.bytes.length); res.setHeader('X-Content-SHA256', result.pack.sha256); res.end(result.bytes);
        } else if (method === 'POST') json(200, await relay.renew(code, owner(req)));
        else { await relay.revoke(code, owner(req)); res.statusCode = 204; res.end(); }
      }
    } catch (failure) {
      const error = failure instanceof ApiError ? failure : new ApiError(503, 'temporarily_unavailable');
      if (!res.destroyed && !res.headersSent) {
        res.setHeader('Connection', 'close'); if ([429, 503].includes(error.status)) res.setHeader('Retry-After', '60');
        const messages: Record<string, string> = {
          invalid_pack: 'This file is not a supported Cook Pack. Export a new file and try again.',
          pack_too_large: 'Choose fewer photos or dishes, then try sharing again.',
          owner_required: 'The saved owner capability is required for this action.',
          unavailable: 'This code is unavailable, expired or revoked.',
          rate_limited: 'Too many attempts. Wait before trying again.',
          uploads_paused: 'New uploads are paused. You can still share a file or image.',
          retry_content_changed: 'This retry contains different content. Start a new share.',
        };
        json(error.status, { error: { code: error.code, message: messages[error.code] ?? 'Please try again later. Your local draft is unchanged.' }, requestId });
      }
    } finally { if (directory) await rm(directory, { force: true, recursive: true }); if (res.destroyed) release(); if (upload) uploading = false; }
  });
  server.requestTimeout = 30000; server.headersTimeout = 10000; server.keepAliveTimeout = 5000;
  return server;
}
