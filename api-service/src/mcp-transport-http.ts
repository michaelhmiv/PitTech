import type { IncomingMessage, ServerResponse } from 'node:http';
import { ApiError } from './core.ts';
import { MCP_SCOPES, type McpGrants } from './mcp-grants.ts';
import type { McpServer } from './mcp-server.ts';

/**
 * Streamable HTTP transport for /mcp (issue #114, M2), stateless mode:
 * POST carries one JSON-RPC message and gets one JSON response; there
 * is no server-initiated stream to open, so GET/DELETE are 405.
 * Authentication is per request — the Bearer token (PAT or OAuth) is
 * resolved through McpGrants.authenticate, which re-checks revocation,
 * expiry and current Pro entitlement on every call. Denials carry the
 * RFC 9728 resource-metadata pointer so MCP clients can discover how
 * to connect.
 */

/** Bounded request body for one JSON-RPC call. Sized for archive
 * import payloads (preview_import carries a whole portable archive);
 * still a hard cap — larger libraries move photos by asset id, not
 * inline. */
const MCP_MAX_BODY_BYTES = 10 * 1024 * 1024;
export function mcpTransportRoute(url: string, method: string): '/mcp' | 'resource-metadata' | undefined {
  const path = url.split('?', 1)[0];
  if (path === '/mcp') return '/mcp'; // any method: the handler 405s non-POST per stateless-mode rules
  if (path === '/.well-known/oauth-protected-resource' && method === 'GET') return 'resource-metadata';
}

export async function handleMcpTransport(req: IncomingMessage, res: ServerResponse,
  grants: McpGrants, server: McpServer, issuer: string | undefined) {
  const json = (status: number, value: unknown) => { if (res.destroyed) throw new ApiError(400, 'request_interrupted'); res.statusCode = status; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(value)); };
  res.setHeader('Cache-Control', 'private, no-store'); res.setHeader('X-Robots-Tag', 'noindex, nofollow');
  const path = (req.url ?? '').split('?', 1)[0];
  if (path === '/.well-known/oauth-protected-resource') {
    if (!issuer) throw new ApiError(404, 'unavailable');
    json(200, {
      resource: issuer + '/mcp',
      authorization_servers: [issuer],
      scopes_supported: [...MCP_SCOPES],
      bearer_methods_supported: ['header'],
      resource_documentation: 'https://pittech.app/mcp',
    });
    return;
  }
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST');
    json(405, { jsonrpc: '2.0', error: { code: -32000, message: 'Method not allowed: this MCP endpoint is stateless; send JSON-RPC by POST.' }, id: null });
    return;
  }
  const token = /^Bearer (.+)$/.exec(req.headers.authorization ?? '')?.[1];
  const principal = token ? await grants.authenticate(token) : undefined;
  if (!principal) {
    if (issuer) res.setHeader('WWW-Authenticate', `Bearer resource_metadata="${issuer}/.well-known/oauth-protected-resource"`);
    json(401, { jsonrpc: '2.0', error: { code: -32001, message: 'Authentication required: connect an AI assistant from PitTech (Pro) and present its token.' }, id: null });
    return;
  }
  if (req.headers['content-type'] !== 'application/json' || ![undefined, 'identity'].includes(req.headers['content-encoding'])) throw new ApiError(415, 'json_required');
  const declared = req.headers['content-length'];
  if (declared !== undefined && (!/^[0-9]+$/.test(declared) || Number(declared) > MCP_MAX_BODY_BYTES)) throw new ApiError(413, 'request_too_large');
  const chunks: Buffer[] = []; let size = 0;
  for await (const chunk of req) { size += chunk.length; if (size > MCP_MAX_BODY_BYTES) throw new ApiError(413, 'request_too_large'); chunks.push(Buffer.from(chunk)); }
  if (!req.complete) throw new ApiError(400, 'upload_interrupted');
  let message: unknown;
  try { message = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks))); }
  catch { json(400, { jsonrpc: '2.0', error: { code: -32700, message: 'Parse error' }, id: null }); return; }
  const response = await server.handle(principal, message);
  if (response === null) { res.statusCode = 202; res.end(); return; }
  json(200, response);
}
