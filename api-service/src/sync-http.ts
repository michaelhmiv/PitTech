import type { IncomingMessage, ServerResponse } from 'node:http';
import { ApiError } from './core.ts';
import type { Authentication } from './auth.ts';
import type { PrivateSnapshots } from './sync-snapshots.ts';
import type { PrivatePhotos } from './sync-photos.ts';
import { PRIVATE_PHOTO_CHUNK_BYTES } from './sync-photos.ts';
export type PrivateSyncServices = { snapshots: PrivateSnapshots; photos: PrivatePhotos };
export type PrivateSyncRoute = '/v1/sync' | '/v1/sync/photos' | '/v1/sync/photos/:id' | '/v1/sync/photos/:id/status' | '/v1/sync/photos/:id/complete' | '/v1/sync/photos/:id/parts/:index';
const id = '[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}';
const photo = new RegExp(`^/v1/sync/photos/(${id})(?:/(status|complete)|/parts/(0|[1-9][0-9]?))?$`);
export function privateSyncRoute(url: string, method: string): PrivateSyncRoute | undefined {
  if (url === '/v1/sync' && ['GET', 'PUT'].includes(method)) return '/v1/sync';
  if (url === '/v1/sync/photos' && method === 'POST') return '/v1/sync/photos';
  const m = photo.exec(url);
  if (!m) return;
  if (!m[2] && m[3] === undefined && ['GET', 'DELETE'].includes(method)) return '/v1/sync/photos/:id';
  if (m[2] === 'status' && method === 'GET') return '/v1/sync/photos/:id/status';
  if (m[2] === 'complete' && method === 'POST') return '/v1/sync/photos/:id/complete';
  if (m[3] !== undefined && Number(m[3]) < 16 && method === 'PUT') return '/v1/sync/photos/:id/parts/:index';
}
async function body(req: IncomingMessage, limit: number, json: boolean) {
  if (req.headers['content-type'] !== (json ? 'application/json' : 'application/octet-stream') || ![undefined, 'identity'].includes(req.headers['content-encoding'])) throw new ApiError(415, json ? 'json_required' : 'private_chunk_required');
  const declared = req.headers['content-length'];
  if (declared !== undefined && (!/^[0-9]+$/.test(declared) || Number(declared) > limit)) throw new ApiError(413, 'request_too_large');
  const chunks: Buffer[] = []; let size = 0;
  for await (const chunk of req) { size += chunk.length; if (size > limit) throw new ApiError(413, 'request_too_large'); chunks.push(Buffer.from(chunk)); }
  if (!req.complete) throw new ApiError(400, 'upload_interrupted');
  return Buffer.concat(chunks);
}
async function object(req: IncomingMessage, limit: number): Promise<Record<string, unknown>> {
  const bytes = await body(req, limit, true);
  try {
    const value: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error();
    return value as Record<string, unknown>;
  } catch { throw new ApiError(400, 'json_required'); }
  finally { bytes.fill(0); }
}
function empty(value: Record<string, unknown>) { if (Object.keys(value).length) throw new ApiError(422, 'invalid_sync_request'); }
/** Explicit dependency injection only. Production startup does not supply these
 * services until strict native projection, durable application and release gates pass. */
export async function handlePrivateSync(req: IncomingMessage, res: ServerResponse, route: PrivateSyncRoute, auth: Authentication | undefined, services: PrivateSyncServices, capacity: { claim(): boolean; release(): void }) {
  const token = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization ?? '')?.[1];
  if (!auth || !token) throw new ApiError(401, 'authentication_required');
  const user = await auth.user(token);
  if (!capacity.claim()) throw new ApiError(503, 'sync_busy');
  const json = (status: number, value: unknown) => { if (res.destroyed) throw new ApiError(400, 'request_interrupted'); res.statusCode = status; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(value)); };
  res.setHeader('Cache-Control', 'private, no-store'); res.setHeader('Vary', 'Authorization'); res.setHeader('X-Robots-Tag', 'noindex, nofollow');
  try {
    if (req.method === 'GET' && (req.headers['transfer-encoding'] !== undefined || Number(req.headers['content-length'] ?? 0) !== 0)) throw new ApiError(400, 'unexpected_body');
    if (route === '/v1/sync') {
      if (req.method === 'GET') json(200, await services.snapshots.read(user.id));
      else {
        const value = await object(req, 32 * 1024 ** 2);
        if (typeof value.mutationId !== 'string' || req.headers['idempotency-key'] !== value.mutationId) throw new ApiError(400, 'idempotency_key_required');
        if (res.destroyed) throw new ApiError(400, 'upload_interrupted');
        json(200, await services.snapshots.put(user.id, value));
      }
      return;
    }
    if (route === '/v1/sync/photos') {
      const value = await object(req, 8192);
      if (typeof value.assetId !== 'string' || req.headers['idempotency-key'] !== value.assetId) throw new ApiError(400, 'idempotency_key_required');
      json(200, await services.photos.begin(user.id, value)); return;
    }
    const matched = photo.exec(req.url ?? ''); if (!matched) throw new ApiError(404, 'unavailable');
    const asset = matched[1];
    if (route.endsWith('/status')) json(200, await services.photos.statusOf(user.id, asset));
    else if (route.endsWith('/complete')) { empty(await object(req, 8192)); json(200, await services.photos.finish(user.id, asset)); }
    else if (route.endsWith('/parts/:index')) {
      const bytes = await body(req, PRIVATE_PHOTO_CHUNK_BYTES, false);
      try { if (res.destroyed) throw new ApiError(400, 'upload_interrupted'); json(200, await services.photos.putPart(user.id, asset, Number(matched[3]), bytes)); }
      finally { bytes.fill(0); }
    } else if (req.method === 'DELETE') {
      const value = await object(req, 8192);
      if (Object.keys(value).join(',') !== 'confirm' || value.confirm !== 'abandon-private-photo') throw new ApiError(422, 'explicit_photo_abandon_required');
      json(200, await services.photos.abandon(user.id, asset, services.snapshots));
    } else {
      const value = await services.photos.read(user.id, asset);
      if (res.destroyed) { value.bytes.fill(0); return; }
      res.statusCode = 200; res.setHeader('Content-Type', value.mimeType); res.setHeader('Content-Length', value.bytes.length);
      res.setHeader('Content-Disposition', 'attachment; filename="PitTech-private-photo"'); res.setHeader('X-Content-SHA256', value.sha256); res.end(value.bytes);
    }
  } finally { capacity.release(); }
}
