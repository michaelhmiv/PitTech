import type { IncomingMessage, ServerResponse } from 'node:http';
import { ApiError } from './core.ts';
import { MCP_SCOPES } from './mcp-grants.ts';
import { MCP_SCOPE_LABELS, type McpOAuth } from './mcp-oauth.ts';

/**
 * OAuth HTTP surface for MCP clients (issue #114, M1b). Discovery,
 * authorize (in-app approval page), status poll, token, revoke. Errors
 * at authorize time render on this origin only — nothing redirects to a
 * URI that has not passed the registered-client check inside
 * McpOAuth.startAuthorization.
 */

export type McpOAuthRoute = 'well-known' | 'authorize' | 'authorize-status' | 'token' | 'revoke';
export function mcpOAuthRoute(url: string, method: string): McpOAuthRoute | undefined {
  const path = url.split('?', 1)[0];
  if (path === '/.well-known/oauth-authorization-server' && method === 'GET') return 'well-known';
  if (path === '/oauth/authorize' && method === 'GET') return 'authorize';
  if (path === '/oauth/authorize/status' && method === 'GET') return 'authorize-status';
  if (path === '/oauth/token' && method === 'POST') return 'token';
  if (path === '/oauth/revoke' && method === 'POST') return 'revoke';
}

async function formBody(req: IncomingMessage, limit: number): Promise<URLSearchParams> {
  if (req.headers['content-type'] !== 'application/x-www-form-urlencoded' || ![undefined, 'identity'].includes(req.headers['content-encoding'])) throw new ApiError(415, 'form_required');
  const declared = req.headers['content-length'];
  if (declared !== undefined && (!/^[0-9]+$/.test(declared) || Number(declared) > limit)) throw new ApiError(413, 'request_too_large');
  const chunks: Buffer[] = []; let size = 0;
  for await (const chunk of req) { size += chunk.length; if (size > limit) throw new ApiError(413, 'request_too_large'); chunks.push(Buffer.from(chunk)); }
  if (!req.complete) throw new ApiError(400, 'upload_interrupted');
  return new URLSearchParams(Buffer.concat(chunks).toString('utf8'));
}
function one(params: URLSearchParams, name: string): string {
  const values = params.getAll(name);
  if (values.length !== 1 || !values[0]) throw new ApiError(400, 'invalid_oauth_request');
  return values[0];
}

const escapeHtml = (value: string) => value.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!));

function page(nonce: string, title: string, bodyHtml: string): string {
  // TURN-IN (area-06 `c11`/`c12`): the authorize page wears the app's theme —
  // dark ground first, light as a full sibling via prefers-color-scheme;
  // the approval code is a mono readout on a raised card face. Presentation
  // only: copy, structure, polling and the CSP nonce are unchanged.
  const css = `:root{color-scheme:dark light}*{box-sizing:border-box}body{font-family:system-ui,sans-serif;background:#10151C;color:#F5EFE3;margin:0;padding:2rem 1rem;line-height:1.55}main{max-width:34rem;margin:0 auto;background:#1D2733;border:1px solid #33404F;border-radius:14px;padding:1.5rem 1.4rem}h1{font-size:1.45rem;line-height:1.25;margin:0 0 1rem}strong{color:#F5EFE3}ul{padding-left:1.25rem}li{margin:.3rem 0}code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:1.9rem;letter-spacing:.18em;display:block;text-align:center;margin:1.1rem 0;padding:.9rem .5rem;background:#10151C;border:1px solid #33404F;border-radius:10px;color:#F07D2E}#status{color:#9AA7B4}@media (prefers-color-scheme:light){body{background:#FBF9F4;color:#1D2733}main{background:#FFFFFF;border-color:#D8D2C4}strong{color:#1D2733}code{background:#FBF9F4;border-color:#D8D2C4;color:#A84A0C}#status{color:#5C6B7A}}`;
  return `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${escapeHtml(title)}</title><style nonce="${nonce}">${css}</style><main><h1>${escapeHtml(title)}</h1>${bodyHtml}</main></html>`;
}

export async function handleMcpOAuth(req: IncomingMessage, res: ServerResponse, route: McpOAuthRoute,
  oauth: McpOAuth, issuer: string, nonce: string) {
  const json = (status: number, value: unknown) => { if (res.destroyed) throw new ApiError(400, 'request_interrupted'); res.statusCode = status; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(value)); };
  const html = (status: number, value: string) => {
    if (res.destroyed) throw new ApiError(400, 'request_interrupted');
    res.statusCode = status; res.setHeader('Content-Type', 'text/html; charset=utf-8');
    res.setHeader('Content-Security-Policy', `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'`);
    res.setHeader('X-Robots-Tag', 'noindex, nofollow');
    res.end(value);
  };
  res.setHeader('Cache-Control', 'private, no-store'); res.setHeader('X-Robots-Tag', 'noindex, nofollow');

  if (route === 'well-known') {
    json(200, {
      issuer,
      authorization_endpoint: issuer + '/oauth/authorize',
      token_endpoint: issuer + '/oauth/token',
      revocation_endpoint: issuer + '/oauth/revoke',
      response_types_supported: ['code'],
      grant_types_supported: ['authorization_code', 'refresh_token'],
      code_challenge_methods_supported: ['S256'],
      scopes_supported: [...MCP_SCOPES],
      token_endpoint_auth_methods_supported: ['none'],
    });
    return;
  }
  if (route === 'authorize') {
    const params = new URLSearchParams((req.url ?? '').split('?', 2)[1] ?? '');
    const param = (name: string) => { const v = params.getAll(name); return v.length === 1 ? v[0] : undefined; };
    try {
      const clientId = param('client_id'), redirectUri = param('redirect_uri'), codeChallenge = param('code_challenge');
      if (!clientId || !redirectUri || !codeChallenge) throw new Error('Missing authorization parameters');
      const pending = await oauth.startAuthorization({
        clientId, redirectUri, codeChallenge,
        responseType: param('response_type') ?? '',
        codeChallengeMethod: param('code_challenge_method') ?? '',
        scope: param('scope'), state: param('state'),
      });
      const labels = pending.scopes.map(s => `<li>${escapeHtml(MCP_SCOPE_LABELS[s])}</li>`).join('');
      html(200, page(nonce, 'Connect ' + pending.client.name + ' to PitTech',
        `<p><strong>${escapeHtml(pending.client.name)}</strong> wants to connect to your PitTech account:</p><ul>${labels}</ul>` +
        `<p>Open <strong>PitTech</strong> on your phone, go to <strong>More → Account → Connect your AI</strong>, and enter this code:</p>` +
        `<code>${escapeHtml(pending.userCode)}</code>` +
        `<p id="status">Waiting for your approval in the app… This code expires in 15 minutes. If you did not start this, close this page — nothing is connected until you approve.</p>` +
        `<script nonce="${nonce}">const poll=${JSON.stringify(pending.pollToken)};const tick=async()=>{try{const r=await fetch('/oauth/authorize/status?poll='+encodeURIComponent(poll));const v=await r.json();if(v.status==='approved'){document.getElementById('status').textContent='Approved — returning to the app…';location.href=v.location;return;}if(v.status==='expired'){document.getElementById('status').textContent='This code expired. Start the connection again from the app.';return;}}catch{}setTimeout(tick,3000);};tick();</script>`));
    } catch {
      html(400, page(nonce, 'This connection link is not valid',
        '<p>The link that brought you here is missing information, names an app PitTech does not recognize, or points somewhere PitTech will not send your data. Start the connection again from the app that asked for it, or connect with a personal access token from PitTech instead.</p>'));
    }
    return;
  }
  if (route === 'authorize-status') {
    const params = new URLSearchParams((req.url ?? '').split('?', 2)[1] ?? '');
    const poll = params.getAll('poll');
    if (poll.length !== 1 || !poll[0]) throw new ApiError(400, 'invalid_oauth_request');
    const status = await oauth.pollStatus(poll[0]);
    if (status.status !== 'approved') { json(200, { status: status.status }); return; }
    const target = new URL(status.redirectUri);
    target.searchParams.set('code', status.code);
    if (status.state !== null) target.searchParams.set('state', status.state);
    json(200, { status: 'approved', location: target.toString() });
    return;
  }
  if (route === 'token') {
    const form = await formBody(req, 8192);
    const grantType = one(form, 'grant_type');
    const clientId = one(form, 'client_id');
    if (grantType === 'authorization_code') {
      const result = await oauth.exchangeCode({ code: one(form, 'code'), clientId, redirectUri: one(form, 'redirect_uri'), codeVerifier: one(form, 'code_verifier') });
      if (!result) { json(400, { error: 'invalid_grant' }); return; }
      json(200, result); return;
    }
    if (grantType === 'refresh_token') {
      const result = await oauth.refresh({ refreshToken: one(form, 'refresh_token'), clientId });
      if (!result) { json(400, { error: 'invalid_grant' }); return; }
      json(200, result); return;
    }
    json(400, { error: 'unsupported_grant_type' }); return;
  }
  // revoke (RFC 7009): always 200; unknown tokens are indistinguishable.
  const form = await formBody(req, 8192);
  const token = form.getAll('token'), clientId = form.getAll('client_id');
  if (token.length === 1 && clientId.length === 1 && token[0] && clientId[0]) await oauth.revokeByToken(token[0], clientId[0]);
  res.statusCode = 200; res.setHeader('Content-Type', 'application/json'); res.end('{}');
}
