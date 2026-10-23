import type { IncomingMessage, ServerResponse } from 'node:http';
import { ApiError } from './core.ts';
import type { Authentication } from './auth.ts';
import type { McpDeviceStore } from './mcp-device.ts';

/**
 * Device-execution routes (issue #114, §7): the phone's side of the
 * command path, exercised with a native account session from the
 * first-party apps — register/opt-in, heartbeat-claim, receipts, and
 * the owner's request history. An MCP token cannot use these routes
 * (the session token shape excludes it, exactly like grant
 * management): an assistant queues requests, only the owner's own
 * devices can claim and resolve them.
 */

export type McpDeviceRoute =
  | '/v1/me/mcp/devices'                       // POST register/update · GET list
  | '/v1/me/mcp/devices/:deviceId'             // DELETE unregister
  | '/v1/me/mcp/devices/:deviceId/claim'       // POST heartbeat + claim queued commands
  | '/v1/me/mcp/devices/:deviceId/receipt'     // POST resolve one claimed command
  | '/v1/me/mcp/device-commands';              // GET owner's request history

const UUID = '[0-9a-fA-F-]{36}';
export function mcpDeviceRoute(url: string, method: string): McpDeviceRoute | undefined {
  const path = url.split('?', 1)[0];
  if (path === '/v1/me/mcp/devices' && ['POST', 'GET'].includes(method)) return '/v1/me/mcp/devices';
  if (new RegExp(`^/v1/me/mcp/devices/${UUID}$`).test(path) && method === 'DELETE') return '/v1/me/mcp/devices/:deviceId';
  if (new RegExp(`^/v1/me/mcp/devices/${UUID}/claim$`).test(path) && method === 'POST') return '/v1/me/mcp/devices/:deviceId/claim';
  if (new RegExp(`^/v1/me/mcp/devices/${UUID}/receipt$`).test(path) && method === 'POST') return '/v1/me/mcp/devices/:deviceId/receipt';
  if (path === '/v1/me/mcp/device-commands' && method === 'GET') return '/v1/me/mcp/device-commands';
}

const ACTION_CLASSES = ['reminder', 'timer', 'guidance', 'collection', 'probe', 'preferences'] as const;

async function body(req: IncomingMessage, limit: number) {
  if (req.headers['content-type'] !== 'application/json' || ![undefined, 'identity'].includes(req.headers['content-encoding'])) throw new ApiError(415, 'json_required');
  const declared = req.headers['content-length'];
  if (declared !== undefined && (!/^[0-9]+$/.test(declared) || Number(declared) > limit)) throw new ApiError(413, 'request_too_large');
  const chunks: Buffer[] = []; let size = 0;
  for await (const chunk of req) { size += chunk.length; if (size > limit) throw new ApiError(413, 'request_too_large'); chunks.push(Buffer.from(chunk)); }
  if (!req.complete) throw new ApiError(400, 'upload_interrupted');
  return Buffer.concat(chunks);
}
async function object(req: IncomingMessage, limit: number): Promise<Record<string, unknown>> {
  const bytes = await body(req, limit);
  try {
    const value: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error();
    return value as Record<string, unknown>;
  } catch { throw new ApiError(400, 'json_required'); }
  finally { bytes.fill(0); }
}
function fields(value: Record<string, unknown>, allowed: string): Record<string, unknown> {
  const keys = new Set(allowed.split(' '));
  if (Object.keys(value).some(key => !keys.has(key))) throw new ApiError(422, 'invalid_mcp_request');
  return value;
}
const segments = (req: IncomingMessage): string[] => (req.url ?? '').split('?', 1)[0].split('/').filter(Boolean);

export async function handleMcpDevices(req: IncomingMessage, res: ServerResponse, route: McpDeviceRoute,
  auth: Authentication | undefined, store: McpDeviceStore, capacity: { claim(): boolean; release(): void },
  clock: () => number = Date.now) {
  const json = (status: number, value: unknown) => { if (res.destroyed) throw new ApiError(400, 'request_interrupted'); res.statusCode = status; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(value)); };
  res.setHeader('Cache-Control', 'private, no-store'); res.setHeader('Vary', 'Authorization'); res.setHeader('X-Robots-Tag', 'noindex, nofollow');
  if (!capacity.claim()) throw new ApiError(503, 'mcp_busy');
  try {
    const token = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization ?? '')?.[1];
    if (!auth || !token) throw new ApiError(401, 'authentication_required');
    const user = await auth.user(token);
    const now = clock();
    const parts = segments(req);
    if (route === '/v1/me/mcp/devices' && req.method === 'GET') {
      json(200, { devices: await store.listDevices(user.id) });
      return;
    }
    if (route === '/v1/me/mcp/devices' && req.method === 'POST') {
      const value = fields(await object(req, 8192), 'deviceId name platform actionClasses');
      if (typeof value.deviceId !== 'string' || !new RegExp(`^${UUID}$`).test(value.deviceId)) throw new ApiError(422, 'invalid_mcp_request');
      if (typeof value.name !== 'string' || !value.name.trim() || value.name.length > 80) throw new ApiError(422, 'invalid_mcp_request');
      if (typeof value.platform !== 'string' || !value.platform.trim() || value.platform.length > 20) throw new ApiError(422, 'invalid_mcp_request');
      if (!Array.isArray(value.actionClasses) || value.actionClasses.some(c => typeof c !== 'string' || !(ACTION_CLASSES as readonly string[]).includes(c))) throw new ApiError(422, 'invalid_mcp_request');
      const device = await store.upsertDevice(user.id, {
        deviceId: value.deviceId, name: value.name.trim(), platform: value.platform.trim(),
        actionClasses: [...new Set(value.actionClasses as string[])],
        lastSeenAt: now, createdAt: now, updatedAt: now,
      });
      json(200, { device });
      return;
    }
    if (route === '/v1/me/mcp/device-commands') {
      const query = new URL(req.url ?? '', 'https://x').searchParams;
      const state = query.get('state') ?? undefined;
      json(200, { commands: await store.listCommands(user.id, state) });
      return;
    }
    const deviceId = parts[4];
    const device = await store.deviceFor(user.id, deviceId);
    if (!device) throw new ApiError(404, 'mcp_device_not_found');
    if (route === '/v1/me/mcp/devices/:deviceId') {
      await store.removeDevice(user.id, deviceId, now);
      res.statusCode = 204; res.end();
      return;
    }
    if (route === '/v1/me/mcp/devices/:deviceId/claim') {
      await object(req, 1024).catch((failure: unknown) => {
        // An empty body is a valid heartbeat-claim; a malformed one is not.
        if (req.headers['content-length'] === '0' || req.headers['content-length'] === undefined) return {};
        throw failure;
      });
      await store.touchDevice(user.id, deviceId, now);
      const commands = await store.claim(user.id, deviceId, device.actionClasses, now, 10);
      json(200, { commands });
      return;
    }
    // receipt
    const value = fields(await object(req, 8192), 'commandId outcome detail result');
    if (typeof value.commandId !== 'string' || !new RegExp(`^${UUID}$`).test(value.commandId)) throw new ApiError(422, 'invalid_mcp_request');
    if (!['applied', 'needs_attention', 'failed'].includes(String(value.outcome))) throw new ApiError(422, 'invalid_mcp_request');
    if (value.detail !== undefined && typeof value.detail !== 'string') throw new ApiError(422, 'invalid_mcp_request');
    if (value.result !== undefined && (typeof value.result !== 'object' || value.result === null || Array.isArray(value.result))) throw new ApiError(422, 'invalid_mcp_request');
    const receipt: Record<string, unknown> = { outcome: value.outcome, at: now };
    if (typeof value.detail === 'string') receipt.detail = value.detail.slice(0, 500);
    if (value.result !== undefined) receipt.result = value.result;
    if (!await store.resolve(user.id, deviceId, value.commandId, receipt, now)) throw new ApiError(409, 'mcp_device_command_not_claimed');
    json(200, { resolved: true });
  } finally { capacity.release(); }
}
