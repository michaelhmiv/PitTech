// Slice 17 completion: Pro-authorized community aggregate reads.
//
// Figures are returned exactly as the aggregation job recorded them, including
// insufficient_data cells with n and compute date. The API never invents a
// figure the job did not compute. Access requires a verified Pro entitlement
// checked server-side via the slice-16 subscriptions cache; client Booleans
// and unverified callbacks never unlock.
import type { IncomingMessage, ServerResponse } from 'node:http';
import { ApiError } from './core.ts';
import type { Authentication } from './auth.ts';
import type { Subscriptions } from './subscriptions.ts';
import type { CommunityAggregates } from './community-aggregation.ts';
import { COMMUNITY_TAXONOMY_GROUPS_V1 } from './community-taxonomy.ts';
import { COMMUNITY_METRICS_V1 } from './community-aggregation.ts';

export type CommunityRoute = '/v1/community/aggregates' | '/v1/community/job-runs';
export function communityRoute(url: string, method: string): CommunityRoute | undefined {
  if (method !== 'GET') return undefined;
  const path = url.split('?', 1)[0];
  if (path === '/v1/community/aggregates') return '/v1/community/aggregates';
  if (path === '/v1/community/job-runs') return '/v1/community/job-runs';
  return undefined;
}

const METRIC_IDS = new Set(COMMUNITY_METRICS_V1.map((m) => m.metricId));
const GROUPS = new Set<string>(COMMUNITY_TAXONOMY_GROUPS_V1 as readonly string[]);
const BANDS = new Set(['small', 'medium', 'large', 'unknown']);
const uuid = (value: string): boolean =>
  /^[a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(value);

function bucketAllowed(bucket: string): boolean {
  if (bucket.length < 1 || bucket.length > 320) return false;
  const parts = bucket.split('|');
  if (parts.length < 3 || parts.length > 4) return false;
  if (!GROUPS.has(parts[0]) || !BANDS.has(parts[1])) return false;
  if (!parts[2].startsWith('method:') || parts[2].length < 8 || parts[2].length > 120) return false;
  if (parts.length === 4 && (!parts[3].startsWith('equipment:') || parts[3].length < 12 || parts[3].length > 100)) return false;
  return true;
}

function params(url: string, allowed: readonly string[]): URLSearchParams {
  const query = url.split('?', 2)[1] ?? '';
  const search = new URLSearchParams(query);
  for (const key of [...search.keys()]) {
    if (!allowed.includes(key) || search.getAll(key).length > 1) throw new ApiError(400, 'invalid_community_filter');
  }
  return search;
}

// Verified Pro check mirroring the native ad gate: verified cache, current
// versions, and a live server state (or a cancelled-but-paid-through record).
// Unknown (never verified) is distinct from not-entitled and never unlocks.
async function requirePro(auth: Authentication | undefined, subscriptions: Subscriptions, req: IncomingMessage): Promise<void> {
  const token = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization ?? '')?.[1];
  if (!auth || !token) throw new ApiError(401, 'authentication_required');
  const user = await auth.user(token);
  const now = Date.now();
  for (const environment of ['production', 'sandbox'] as const) {
    const cache = await subscriptions.cache(user.id, environment);
    if (!cache.verified || cache.version !== 'entitlement-cache-v1' || cache.contractVersion !== 'store-verification-v1') continue;
    if (['active', 'grace', 'retry', 'paused'].includes(cache.state)) return;
    if (cache.state === 'cancelled' && cache.verifiedUntil !== null && Date.parse(cache.verifiedUntil) > now) return;
  }
  throw new ApiError(403, 'pro_required');
}

export async function handleCommunity(
  req: IncomingMessage, res: ServerResponse, route: CommunityRoute,
  auth: Authentication | undefined, subscriptions: Subscriptions, aggregates: CommunityAggregates,
) {
  const json = (status: number, value: unknown): void => {
    if (res.destroyed) throw new ApiError(400, 'request_interrupted');
    res.statusCode = status;
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify(value));
  };
  res.setHeader('Cache-Control', 'private, no-store');
  res.setHeader('Vary', 'Authorization');
  res.setHeader('X-Robots-Tag', 'noindex, nofollow');
  if (req.headers['transfer-encoding'] !== undefined || Number(req.headers['content-length'] ?? 0) !== 0) {
    throw new ApiError(400, 'unexpected_body');
  }
  await requirePro(auth, subscriptions, req);
  const raw = req.url ?? '';
  if (route === '/v1/community/job-runs') {
    const search = params(raw, ['limit', 'cursor']);
    const limitRaw = search.get('limit') ?? '20';
    if (!/^[0-9]+$/.test(limitRaw)) throw new ApiError(400, 'invalid_community_filter');
    const limit = Number(limitRaw);
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100) throw new ApiError(400, 'invalid_community_filter');
    const cursor = search.get('cursor');
    if (cursor !== null && !uuid(cursor)) throw new ApiError(400, 'invalid_cursor');
    const runs = await aggregates.store.list();
    let start = 0;
    if (cursor !== null) {
      const index = runs.findIndex((r) => r.runId === cursor);
      if (index < 0) throw new ApiError(400, 'invalid_cursor');
      start = index + 1;
    }
    const page = runs.slice(start, start + limit);
    json(200, {
      runs: page,
      nextCursor: start + limit < runs.length ? page[page.length - 1].runId : null,
    });
    return;
  }
  const search = params(raw, ['bucket', 'metric', 'runId']);
  const bucket = search.get('bucket');
  const metric = search.get('metric');
  const runId = search.get('runId');
  if (bucket !== null && !bucketAllowed(bucket)) throw new ApiError(400, 'invalid_community_filter');
  if (metric !== null && !METRIC_IDS.has(metric)) throw new ApiError(400, 'invalid_community_filter');
  if (runId !== null && !uuid(runId)) throw new ApiError(400, 'invalid_community_filter');
  const runs = await aggregates.store.list();
  if (!runs.length) {
    json(200, { run: null, figures: [] });
    return;
  }
  const run = runId !== null ? runs.find((r) => r.runId === runId) : runs[runs.length - 1];
  if (!run) throw new ApiError(404, 'unknown_community_run');
  const figures = (await aggregates.store.figures(run.runId)) ?? [];
  json(200, {
    run,
    figures: figures.filter((f) =>
      (bucket === null || f.bucket === bucket) && (metric === null || f.metricId === metric)),
  });
}
