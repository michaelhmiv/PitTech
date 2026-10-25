import type { IncomingMessage, ServerResponse } from 'node:http';
import { ApiError } from './core.ts';
import type { Authentication } from './auth.ts';
import { hasScope, type McpGrants } from './mcp-grants.ts';
import type { McpOAuth } from './mcp-oauth.ts';
import type { McpPortabilityStore } from './mcp-portability.ts';

/**
 * Grant-management routes (issue #114, M1b). These are the owner's own
 * connection controls, exercised from trusted first-party UI with a native
 * account session — the same Bearer session discipline as the billing
 * routes. An MCP token (`ptm_…`) deliberately cannot match the session
 * token shape, so a connected assistant can never mint, list-scope, or
 * revoke its own or another connection's grant. Revoking stays reachable
 * regardless of Pro state: the entitlement gate lives in McpGrants
 * .authenticate (data calls), never in grant management. The describe
 * route is the same discipline: an owner-session, read-only lookup that
 * powers the in-app approval confirmation (issue #172, PL-16) — it
 * approves nothing and changes no state.
 */

export type McpGrantRoute =
  | '/v1/me/mcp/grants'            // POST create · GET list · DELETE disconnect-all
  | '/v1/me/mcp/grants/:id'        // DELETE revoke one
  | '/v1/me/mcp/oauth/describe'    // POST describe a user code for the approval confirmation
  | '/v1/me/mcp/oauth/approve';    // POST approve a browser-shown user code

export function mcpGrantRoute(url: string, method: string): McpGrantRoute | undefined {
  const path = url.split('?', 1)[0];
  if (path === '/v1/me/mcp/grants' && ['POST', 'GET', 'DELETE'].includes(method)) return '/v1/me/mcp/grants';
  if (/^\/v1\/me\/mcp\/grants\/[0-9a-fA-F-]{36}$/.test(path) && method === 'DELETE') return '/v1/me/mcp/grants/:id';
  if (path === '/v1/me/mcp/oauth/describe' && method === 'POST') return '/v1/me/mcp/oauth/describe';
  if (path === '/v1/me/mcp/oauth/approve' && method === 'POST') return '/v1/me/mcp/oauth/approve';
}

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
/** The service reports validation problems as plain "Invalid grant …"
 * errors; at the HTTP boundary they are one indistinguishable 422. */
function asRequestError(failure: unknown): unknown {
  if (failure instanceof Error && failure.message.startsWith('Invalid grant')) return new ApiError(422, 'invalid_mcp_request');
  return failure;
}

const DAY_MS = 24 * 60 * 60 * 1000;
export const MCP_DEFAULT_GRANT_DAYS = 90;

/** Private export download (issue #114, M5): the artifacts create_export
 * and render_private_share_card produce. Authenticated either by the
 * owner's native session or by an MCP connection token holding
 * exports:read — never a public or permanent bearer link. Unknown and
 * other-account ids are one indistinguishable 404; expired is 410. */
export function mcpExportRoute(url: string, method: string): '/v1/me/mcp/exports/:id' | undefined {
  const path = url.split('?', 1)[0];
  if (method === 'GET' && /^\/v1\/me\/mcp\/exports\/[0-9a-fA-F-]{36}$/.test(path)) return '/v1/me/mcp/exports/:id';
}

export async function handleMcpExportDownload(req: IncomingMessage, res: ServerResponse,
  auth: Authentication | undefined, grants: McpGrants, portability: McpPortabilityStore) {
  const json = (status: number, value: unknown) => { if (res.destroyed) throw new ApiError(400, 'request_interrupted'); res.statusCode = status; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(value)); };
  res.setHeader('Cache-Control', 'private, no-store'); res.setHeader('Vary', 'Authorization'); res.setHeader('X-Robots-Tag', 'noindex, nofollow');
  if (req.headers['transfer-encoding'] !== undefined || Number(req.headers['content-length'] ?? 0) !== 0) throw new ApiError(400, 'unexpected_body');
  const id = (req.url ?? '').split('?', 1)[0].split('/').pop()!;
  let userId: string | undefined;
  const sessionToken = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization ?? '')?.[1];
  if (auth && sessionToken) {
    try { userId = (await auth.user(sessionToken)).id; } catch { userId = undefined; }
  }
  if (!userId) {
    const bearer = /^Bearer (.+)$/.exec(req.headers.authorization ?? '')?.[1];
    const principal = bearer ? await grants.authenticate(bearer) : undefined;
    if (principal && hasScope(principal, 'exports:read')) userId = principal.userId;
  }
  if (!userId) throw new ApiError(401, 'authentication_required');
  const record = await portability.exportFor(userId, id);
  if (!record) throw new ApiError(404, 'mcp_export_not_found');
  if (record.expiresAt <= Date.now()) throw new ApiError(410, 'mcp_export_expired');
  const payload = record.payload as { archive?: unknown; card?: unknown };
  json(200, record.kind === 'share_card' ? payload.card : payload.archive);
}

export async function handleMcpGrants(req: IncomingMessage, res: ServerResponse, route: McpGrantRoute,
  auth: Authentication | undefined, grants: McpGrants, capacity: { claim(): boolean; release(): void },
  oauth?: McpOAuth) {
  const json = (status: number, value: unknown) => { if (res.destroyed) throw new ApiError(400, 'request_interrupted'); res.statusCode = status; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(value)); };
  res.setHeader('Cache-Control', 'private, no-store'); res.setHeader('Vary', 'Authorization'); res.setHeader('X-Robots-Tag', 'noindex, nofollow');
  if (!capacity.claim()) throw new ApiError(503, 'mcp_busy');
  try {
    if (req.method === 'GET' && (req.headers['transfer-encoding'] !== undefined || Number(req.headers['content-length'] ?? 0) !== 0)) throw new ApiError(400, 'unexpected_body');
    const token = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization ?? '')?.[1];
    if (!auth || !token) throw new ApiError(401, 'authentication_required');
    const user = await auth.user(token);
    if (route === '/v1/me/mcp/oauth/describe') {
      if (!oauth) throw new ApiError(404, 'mcp_grant_not_found');
      const value = fields(await object(req, 8192), 'userCode');
      if (typeof value.userCode !== 'string') throw new ApiError(422, 'invalid_mcp_request');
      // Read-only confirmation data for the in-app approval step
      // (issue #172, PL-16): unknown, expired and already-approved codes
      // are one answer, and describing never approves anything.
      const described = await oauth.describe(value.userCode);
      if (!described) throw new ApiError(404, 'mcp_grant_not_found');
      json(200, { clientName: described.clientName, scopes: described.scopes, expiresAt: described.expiresAt }); return;
    }
    if (route === '/v1/me/mcp/oauth/approve') {
      if (!oauth) throw new ApiError(404, 'mcp_grant_not_found');
      const value = fields(await object(req, 8192), 'userCode');
      if (typeof value.userCode !== 'string') throw new ApiError(422, 'invalid_mcp_request');
      // Unknown, expired and already-approved codes are one answer.
      if (!await oauth.approve(user.id, value.userCode)) throw new ApiError(404, 'mcp_grant_not_found');
      json(200, { approved: true }); return;
    }
    if (route === '/v1/me/mcp/grants' && req.method === 'GET') { json(200, { grants: await grants.list(user.id) }); return; }
    if (route === '/v1/me/mcp/grants' && req.method === 'DELETE') { json(200, { revoked: await grants.revokeAll(user.id) }); return; }
    if (route === '/v1/me/mcp/grants' && req.method === 'POST') {
      const value = fields(await object(req, 8192), 'nickname scopes expiresInDays');
      if (typeof value.nickname !== 'string') throw new ApiError(422, 'invalid_mcp_request');
      if (!Array.isArray(value.scopes) || value.scopes.some(s => typeof s !== 'string')) throw new ApiError(422, 'invalid_mcp_request');
      const days = value.expiresInDays === undefined ? MCP_DEFAULT_GRANT_DAYS : value.expiresInDays;
      if (typeof days !== 'number' || !Number.isSafeInteger(days)) throw new ApiError(422, 'invalid_mcp_request');
      let created;
      try { created = await grants.create(user.id, value.nickname, value.scopes as string[], days * DAY_MS); }
      catch (failure) { throw asRequestError(failure); }
      if (res.destroyed) throw new ApiError(400, 'upload_interrupted');
      // The token is returned exactly once, in this response only; list
      // and every later response carry metadata without it.
      json(201, created);
      return;
    }
    // DELETE /v1/me/mcp/grants/:id — an id that is not the caller's own
    // grant (or never existed) is the same 404; existence never leaks.
    const id = (req.url ?? '').split('?', 1)[0].split('/').pop()!;
    if (!await grants.revoke(user.id, id)) throw new ApiError(404, 'mcp_grant_not_found');
    res.statusCode = 204; res.end();
  } finally { capacity.release(); }
}
