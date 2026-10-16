import { createServer, type IncomingMessage } from 'node:http';
import { randomUUID,randomBytes } from 'node:crypto';
import { deletionPage, operatorPage } from './lifecycle-web.ts';
import { createWriteStream } from 'node:fs';
import { mkdir, mkdtemp, rm, readdir, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import proxyaddr from 'proxy-addr';
import { ApiError, LIMITS, ownerHash, rateIdentity, normalizeCode } from './core.ts';
import { association, landing, type LinkConfig } from './web.ts';
import type { Authentication } from './auth.ts';
import type { Relay } from './relay.ts';
import { validate } from './validation.ts';
import { publicationRoute, handlePublication } from './publication-http.ts';
import type { Publications } from './publications.ts';
import { lifecycleRoute,handleLifecycle,requestAudience } from './lifecycle-http.ts';
import type { AccountDeletion } from './account-deletion.ts';
import type { CommunityLifecycle } from './community-lifecycle.ts';
import { publicWebRoute, publicPage } from './publication-web.ts';

type Options = { ipKey: string; trustedProxies?: string[]; uploadsEnabled: boolean; tempRoot?: string;
  links?: LinkConfig; auth?: Authentication; publications?: Publications; deletion?:AccountDeletion; community?:CommunityLifecycle; log?: (value: { requestId: string; method: string; route: string; status: number }) => void };
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
    const requestId = randomUUID(), method = ['GET', 'POST', 'PUT', 'DELETE'].includes(req.method ?? '') ? req.method! : 'OTHER';
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
      if (method === 'GET' && req.url === '/auth') {
        route = '/auth'; res.setHeader('Content-Type', 'text/html; charset=utf-8');
        res.setHeader('Content-Security-Policy', "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
        res.setHeader('X-Robots-Tag', 'noindex, nofollow');
        res.end('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>PitTech sign in</title><h1>Sign in to PitTech</h1><p>Open PitTech on the device where you requested this email. If the app did not open, enter the six-digit code from your email there.</p><p>This page does not sign you in or use your link. Your local library is unchanged.</p></html>'); return;
      }
      if(['/account/delete','/delete-account'].includes(req.url??'')&&method==='GET'&&options.deletion){
        route='/account/delete';const nonce=randomBytes(16).toString('hex');
        res.setHeader('Content-Type','text/html; charset=utf-8');res.setHeader('X-Robots-Tag','noindex, nofollow');
        res.setHeader('Content-Security-Policy',`default-src 'none'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'`);
        res.end(deletionPage(nonce));return;
      }
      if(req.url==='/operator'&&method==='GET'&&options.deletion){
        route='/operator';const nonce=randomBytes(16).toString('hex');
        res.setHeader('Content-Type','text/html; charset=utf-8');res.setHeader('X-Robots-Tag','noindex, nofollow');
        res.setHeader('Content-Security-Policy',`default-src 'none'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'`);
        res.end(operatorPage(nonce));return;
      }
      const authRoute = ['/v1/auth/challenges', '/v1/auth/verify', '/v1/auth/refresh', '/v1/auth/logout', '/v1/me'].includes(req.url ?? '') ? req.url! : undefined;
      if (authRoute && options.auth && (method === 'POST' && authRoute !== '/v1/me' || method === 'GET' && authRoute === '/v1/me')) {
        route = authRoute;
        if (active >= 2) throw new ApiError(503, 'temporarily_unavailable'); active++; counted = true;
        const now = relay.clock(), bucket = Math.floor(now / 3600000);
        const key = 'auth:' + rateIdentity(options.ipKey, proxyaddr(req, trust), 'auth:' + bucket);
        if (!await relay.repository.rate(key, 60, (bucket + 1) * 3600000)) throw new ApiError(429, 'rate_limited');
        if (authRoute === '/v1/me') { json(200, await options.auth.user(/^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization ?? '')?.[1])); return; }
        if (req.headers['content-type'] !== 'application/json' || ![undefined, 'identity'].includes(req.headers['content-encoding'])) throw new ApiError(415, 'json_required');
        if (Number(req.headers['content-length'] ?? 0) > 8192) throw new ApiError(413, 'request_too_large');
        const chunks: Buffer[] = []; let size = 0;
        for await (const chunk of req) { size += chunk.length; if (size > 8192) throw new ApiError(413, 'request_too_large'); chunks.push(Buffer.from(chunk)); }
        let body: Record<string, unknown>;
        try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); if (body === null || Array.isArray(body) || typeof body !== 'object') throw new Error(); }
        catch { throw new ApiError(400, 'json_required'); }
        if (authRoute === '/v1/auth/challenges') json(202, await options.auth.start({ email: body.email, idempotencyId: body.idempotencyId, proofChallenge: body.proofChallenge }));
        else if (authRoute === '/v1/auth/verify') json(200, await options.auth.verify({ challengeId: body.challengeId, verifier: body.verifier, code: body.code, token: body.token, idempotencyId: body.idempotencyId }));
        else if (authRoute === '/v1/auth/refresh') json(200, await options.auth.refresh(body.refreshToken, body.idempotencyId));
        else { await options.auth.logout(body.refreshToken); res.statusCode = 204; res.end(); }
        return;
      }
      const lifecycle=lifecycleRoute(req.url??'',method);
      if(lifecycle&&options.deletion&&options.community){
        route=lifecycle;if(active>=2)throw new ApiError(503,'temporarily_unavailable');active++;counted=true;
        const bucket=Math.floor(relay.clock()/3600000),key='lifecycle:'+rateIdentity(options.ipKey,proxyaddr(req,trust),'lifecycle:'+bucket);
        if(!await relay.repository.rate(key,60,(bucket+1)*3600000))throw new ApiError(429,'rate_limited');
        await handleLifecycle(req,res,lifecycle,options.auth,options.deletion,options.community);return;
      }
      const pageRoute=method==='GET'?publicWebRoute(req.url??''):undefined;
      const publicRoute=publicationRoute(req.url??'',method);
      if((publicRoute||pageRoute)&&options.publications){
        route=publicRoute??pageRoute!;
        if(active>=2)throw new ApiError(503,'temporarily_unavailable');active++;counted=true;
        const now=relay.clock(),bucket=Math.floor(now/3600000),category=method==='GET'?'public-read':'public-write';
        const key=category+':'+rateIdentity(options.ipKey,proxyaddr(req,trust),category+':'+bucket);
        if(!await relay.repository.rate(key,method==='GET'?120:60,(bucket+1)*3600000))throw new ApiError(429,'rate_limited');
        if(pageRoute){
          res.setHeader('Content-Type','text/html; charset=utf-8');
          res.setHeader('Content-Security-Policy',"default-src 'none'; style-src 'unsafe-inline'; img-src https://api.pittech.app; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
          res.setHeader('X-Robots-Tag','noindex, nofollow');
          res.end(await publicPage(req.url!,options.publications,options.links??{appleAppIds:[],androidCertificates:[]},await requestAudience(req,options.auth,options.community)));return;
        }
        await handlePublication(req,res,publicRoute!,options.publications,options.auth,{root,
          claimUpload:()=>{if(uploading)return false;uploading=true;return true;},releaseUpload:()=>{uploading=false;}},options.community);return;
      }
      const links = options.links ?? { appleAppIds: [], androidCertificates: [] };
      if (method === 'GET' && ['/.well-known/apple-app-site-association', '/.well-known/assetlinks.json'].includes(req.url ?? '')) {
        route = req.url!;
        const value = association(req.url!, links); json(value ? 200 : 503, value ?? { error: { code: 'association_not_configured' } }); return;
      }
      const page = method === 'GET' ? /^\/s\/([^/?#]{1,128})$/.exec(req.url ?? '') : null;
      const match = /^\/packs\/([^/?#]{1,128})(\/renew)?$/.exec(req.url ?? '');
      const isUpload = req.url === '/packs' && method === 'POST';
      if (!page && !isUpload && !(match && (method === 'GET' && !match[2] || method === 'DELETE' && !match[2] || method === 'POST' && match[2]))) throw new ApiError(404, 'unavailable');
      route = page ? '/s/:code' : isUpload ? '/packs' : match![2] ? '/packs/:code/renew' : '/packs/:code';
      const now = relay.clock(), category = isUpload ? 'upload' : method === 'GET' ? 'download' : 'manage';
      const period = category === 'upload' ? 86400000 : 3600000, bucket = Math.floor(now / period);
      const address = proxyaddr(req, trust), key = category + ':' + rateIdentity(options.ipKey, address, category + ':' + bucket);
      if (!await relay.repository.rate(key, category === 'upload' ? 20 : category === 'download' ? 120 : 60, (bucket + 1) * period)) throw new ApiError(429, 'rate_limited');
      if (active >= 2) throw new ApiError(503, 'relay_busy'); active++; counted = true;
      if (page) {
        let code = ''; try { code = normalizeCode(decodeURIComponent(page[1])); } catch { /* Same unavailable page for invalid codes. */ }
        const pack = code ? await relay.repository.get(code) : undefined;
        const result = landing(code, pack, now, links);
        res.statusCode = result.status; res.setHeader('Content-Type', 'text/html; charset=utf-8');
        res.setHeader('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
        res.setHeader('X-Robots-Tag', 'noindex, nofollow'); res.end(result.html); return;
      }
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
