import { sign } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';

const report = { readOnly: true, sourceSha: process.env.GITHUB_SHA, runId: process.env.GITHUB_RUN_ID };
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
const key = value => value.replaceAll('\\n', '\n');
function jwt(header, claims, privateKey, p1363 = false) {
  const body = encode(header) + '.' + encode(claims);
  return body + '.' + sign('sha256', Buffer.from(body), p1363 ? { key: key(privateKey), dsaEncoding: 'ieee-p1363' } : key(privateKey)).toString('base64url');
}
function requireEnv(name) {
  const value = process.env[name];
  if (!value) throw Object.assign(new Error(), { stage: 'configuration', code: 'MISSING_' + name });
  return value;
}
async function responseJson(response, stage) {
  // An empty successful collection may be returned as HTTP 204.
  if (response.status === 204) return {};
  let body;
  try { body = await response.json(); } catch { throw Object.assign(new Error(), { stage, status: response.status, code: 'INVALID_JSON' }); }
  if (!response.ok) {
    const candidate = body?.error?.status ?? body?.errors?.[0]?.code;
    const code = typeof candidate === 'string' && /^[A-Z0-9_.-]{1,80}$/.test(candidate) ? candidate : 'PROVIDER_REJECTED';
    throw Object.assign(new Error(), { stage, status: response.status, code });
  }
  return body;
}
async function getJson(url, token, stage) {
  const target = new URL(url);
  if (!['https://androidpublisher.googleapis.com', 'https://api.appstoreconnect.apple.com'].includes(target.origin)) throw Object.assign(new Error(), { stage, code: 'UNEXPECTED_PROVIDER_ORIGIN' });
  return responseJson(await fetch(target, { headers: { Authorization: 'Bearer ' + token }, redirect: 'error', signal: AbortSignal.timeout(20000) }), stage);
}
function failure(error, stage) {
  return { ok: false, stage: error?.stage ?? stage, ...(Number.isInteger(error?.status) ? { httpStatus: error.status } : {}), code: error?.code && /^[A-Z0-9_.-]{1,80}$/.test(error.code) ? error.code : 'REQUEST_FAILED' };
}
async function googleInventory() {
  const account = JSON.parse(requireEnv('GCP_SERVICE_ACCOUNT_JSON'));
  if (typeof account.client_email !== 'string' || typeof account.private_key !== 'string') throw Object.assign(new Error(), { stage: 'configuration', code: 'INVALID_GOOGLE_CREDENTIALS' });
  const now = Math.floor(Date.now() / 1000);
  const assertion = jwt({ alg: 'RS256', typ: 'JWT' }, { iss: account.client_email, scope: 'https://www.googleapis.com/auth/androidpublisher', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 900 }, account.private_key);
  const oauth = await responseJson(await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }),
    redirect: 'error', signal: AbortSignal.timeout(20000)
  }), 'google_authentication');
  if (typeof oauth.access_token !== 'string') throw Object.assign(new Error(), { stage: 'google_authentication', code: 'TOKEN_MISSING' });
  const subscriptions = [];
  let pageToken;
  for (let page = 0; page < 5; page++) {
    const url = new URL('https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.pittech/subscriptions');
    url.searchParams.set('pageSize', '1000');
    if (pageToken) url.searchParams.set('pageToken', pageToken);
    const body = await getJson(url, oauth.access_token, 'google_subscriptions');
    for (const item of body.subscriptions ?? []) subscriptions.push({
      productId: item.productId,
      basePlans: (item.basePlans ?? []).map(plan => ({
        basePlanId: plan.basePlanId, state: plan.state,
        billingPeriod: plan.autoRenewingBasePlanType?.billingPeriodDuration,
        us: (plan.regionalConfigs ?? []).filter(region => region.regionCode === 'US').map(region => ({ price: region.price, available: region.newSubscriberAvailability }))
      }))
    });
    pageToken = body.nextPageToken;
    if (!pageToken) return { ok: true, packageName: 'com.pittech', complete: true, subscriptions };
  }
  throw Object.assign(new Error(), { stage: 'google_subscriptions', code: 'PAGINATION_LIMIT' });
}
async function appleList(path, token, stage = 'apple_catalog') {
  let next = new URL(path, 'https://api.appstoreconnect.apple.com').href;
  const data = [];
  for (let page = 0; page < 10; page++) {
    const body = await getJson(next, token, stage);
    if (!Array.isArray(body.data)) throw Object.assign(new Error(), { stage, code: 'INVALID_COLLECTION' });
    data.push(...body.data);
    next = body.links?.next;
    if (!next) return data;
  }
  throw Object.assign(new Error(), { stage, code: 'PAGINATION_LIMIT' });
}
const signInWithAppleIdentifiers = ['com.pittech', 'com.pittech.staging'];
function parseAppleIdAuth(capabilities) {
  const matches = (capabilities ?? []).filter(item => item?.attributes?.capabilityType === 'APPLE_ID_AUTH');
  if (!matches.length) return { present: false, enabled: false, capabilityId: null, enabledSource: 'absent' };
  const attributes = matches[0].attributes ?? {};
  if (typeof attributes.enabled === 'boolean') return { present: true, enabled: attributes.enabled, capabilityId: matches[0].id ?? null, enabledSource: 'attribute' };
  // Repo-observed bundleIdCapability shapes expose capabilityType only; a
  // listed APPLE_ID_AUTH capability reads as enabled unless the API says otherwise.
  return { present: true, enabled: true, capabilityId: matches[0].id ?? null, enabledSource: 'presence' };
}
async function appleSignInWithApple(token) {
  // A read-only inventory reports per-call outcomes instead of failing the
  // whole Apple section on the first rejected call: a rejected read is data
  // (stage + status + code), and the remaining identifiers still report.
  const entries = [];
  for (const identifier of signInWithAppleIdentifiers) {
    let records;
    try {
      records = await appleList('/v1/bundleIds?filter[identifier]=' + encodeURIComponent(identifier), token, 'apple_siwa');
    } catch (error) { entries.push({ identifier, found: null, lookup: failure(error, 'apple_siwa') }); continue; }
    if (records.length > 1) { entries.push({ identifier, found: null, lookup: { ok: false, stage: 'apple_siwa', code: 'AMBIGUOUS_BUNDLE_ID' } }); continue; }
    if (!records.length) { entries.push({ identifier, found: false }); continue; }
    let capabilities;
    try {
      capabilities = await appleList('/v1/bundleIds/' + encodeURIComponent(records[0].id) + '/bundleIdCapabilities', token, 'apple_siwa');
    } catch (error) { entries.push({ identifier, found: true, bundleId: records[0].id, capabilitiesRead: failure(error, 'apple_siwa') }); continue; }
    entries.push({
      identifier, found: true, bundleId: records[0].id, name: records[0].attributes?.name ?? null, platform: records[0].attributes?.platform ?? null,
      capabilityTypes: capabilities.map(item => item?.attributes?.capabilityType).filter(type => typeof type === 'string'),
      appleIdAuth: parseAppleIdAuth(capabilities)
    });
  }
  return entries;
}
async function appleOne(path, token, stage = 'apple_completeness') {
  try {
    const body = await getJson(new URL(path, 'https://api.appstoreconnect.apple.com').href, token, stage);
    return body.data ?? null;
  } catch (error) {
    if (error?.status === 404) return null;
    throw error;
  }
}
async function soft(read) { try { return await read(); } catch (error) { return failure(error, 'apple_completeness'); } }
async function appleInventory() {
  const now = Math.floor(Date.now() / 1000);
  const token = jwt({ alg: 'ES256', kid: requireEnv('ASC_KEY_ID'), typ: 'JWT' }, { iss: requireEnv('ASC_ISSUER_ID'), iat: now, exp: now + 900, aud: 'appstoreconnect-v1' }, requireEnv('ASC_PRIVATE_KEY'), true);
  const apps = await appleList('/v1/apps?filter[bundleId]=com.pittech&limit=10', token);
  if (apps.length !== 1 || apps[0].attributes?.bundleId !== 'com.pittech') throw Object.assign(new Error(), { stage: 'apple_app', code: 'EXACT_APP_NOT_FOUND' });
  const app = apps[0];
  const groups = [];
  for (const group of await appleList('/v1/apps/' + encodeURIComponent(app.id) + '/subscriptionGroups?limit=200', token)) {
    const subscriptions = await appleList('/v1/subscriptionGroups/' + encodeURIComponent(group.id) + '/subscriptions?limit=200', token);
    // Completeness diagnostics: the subscription state alone (e.g.
    // MISSING_METADATA) does not say WHICH requirement is unmet, so read each
    // candidate surface directly. Every read is fail-soft: a rejected read is
    // reported as data, never fatal to the inventory.
    const versions = await soft(async () => {
      const list = await appleList('/v1/subscriptionGroups/' + encodeURIComponent(group.id) + '/versions', token);
      const detailed = [];
      for (const version of list) {
        const localizations = await soft(async () => (await appleList('/v1/subscriptionGroupVersions/' + encodeURIComponent(version.id) + '/localizations', token)).map(item => ({ locale: item.attributes?.locale, name: item.attributes?.name })));
        detailed.push({ id: version.id, localizations });
      }
      return detailed;
    });
    const detailedSubscriptions = [];
    for (const item of subscriptions) {
      const base = '/v1/subscriptions/' + encodeURIComponent(item.id);
      const completeness = {
        localizations: await soft(async () => (await appleList(base + '/subscriptionLocalizations', token)).map(loc => ({ locale: loc.attributes?.locale, name: loc.attributes?.name, hasDescription: typeof loc.attributes?.description === 'string' && loc.attributes.description.length > 0 }))),
        prices: await soft(async () => (await appleList(base + '/prices', token)).length),
        planAvailabilities: await soft(async () => (await appleList(base + '/planAvailabilities', token)).map(plan => ({ planType: plan.attributes?.planType }))),
        reviewScreenshot: await soft(async () => {
          const shot = await appleOne(base + '/appStoreReviewScreenshot', token);
          if (!shot) return { present: false };
          return { present: true, id: shot.id, uploaded: shot.attributes?.uploaded ?? null, assetDeliveryState: shot.attributes?.assetDeliveryState?.state ?? null, assetDeliveryErrors: shot.attributes?.assetDeliveryState?.errors ?? null };
        })
      };
      detailedSubscriptions.push({
        id: item.id, productId: item.attributes?.productId, name: item.attributes?.name,
        subscriptionPeriod: item.attributes?.subscriptionPeriod, state: item.attributes?.state, completeness
      });
    }
    groups.push({ id: group.id, referenceName: group.attributes?.referenceName, versions, subscriptions: detailedSubscriptions });
  }
  return { ok: true, bundleId: 'com.pittech', appId: app.id, complete: true, groups, signInWithApple: await appleSignInWithApple(token) };
}
try { report.google = await googleInventory(); } catch (error) { report.google = failure(error, 'google'); }
try { report.apple = await appleInventory(); } catch (error) { report.apple = failure(error, 'apple'); }
await mkdir('store-readiness', { recursive: true });
await writeFile('store-readiness/catalog-inventory.json', JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
console.info(JSON.stringify(report, null, 2));
if (!report.google.ok || !report.apple.ok) process.exitCode = 1;
