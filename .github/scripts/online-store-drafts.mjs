import { sign } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';

const report = { readOnly: false, configuration: 'Single monthly PitTech Pro draft; no activation or review submission', sourceSha: process.env.GITHUB_SHA, runId: process.env.GITHUB_RUN_ID };
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

const productId = 'pittech.pro.monthly';
const googleRoot = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.pittech';
const appleRoot = 'https://api.appstoreconnect.apple.com';
function insist(value, stage, code) { if (!value) throw Object.assign(new Error(), { stage, code }); }
async function postJson(url, token, body, stage) {
  const target = new URL(url);
  insist(['https://androidpublisher.googleapis.com', appleRoot].includes(target.origin), stage, 'UNEXPECTED_PROVIDER_ORIGIN');
  return responseJson(await fetch(target, { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' }, body: JSON.stringify(body), redirect: 'error', signal: AbortSignal.timeout(20000) }), stage);
}
async function googleToken() {
  const account = JSON.parse(requireEnv('GCP_SERVICE_ACCOUNT_JSON')), now = Math.floor(Date.now() / 1000);
  const assertion = jwt({ alg: 'RS256', typ: 'JWT' }, { iss: account.client_email, scope: 'https://www.googleapis.com/auth/androidpublisher', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 900 }, account.private_key);
  const result = await responseJson(await fetch('https://oauth2.googleapis.com/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }), redirect: 'error', signal: AbortSignal.timeout(20000) }), 'google_authentication');
  insist(typeof result.access_token === 'string', 'google_authentication', 'TOKEN_MISSING');
  return result.access_token;
}
function appleToken() {
  const now = Math.floor(Date.now() / 1000);
  return jwt({ alg: 'ES256', kid: requireEnv('ASC_KEY_ID'), typ: 'JWT' }, { iss: requireEnv('ASC_ISSUER_ID'), iat: now, exp: now + 900, aud: 'appstoreconnect-v1' }, requireEnv('ASC_PRIVATE_KEY'), true);
}
async function appleCollection(path, token) {
  let next = new URL(path, appleRoot).href;
  const data = [];
  for (let page = 0; page < 10; page++) {
    const body = await getJson(next, token, 'apple_catalog');
    insist(body.data === undefined || Array.isArray(body.data), 'apple_catalog', 'INVALID_COLLECTION');
    data.push(...(body.data ?? []));
    next = body.links?.next;
    if (!next) return data;
  }
  throw Object.assign(new Error(), { stage: 'apple_catalog', code: 'PAGINATION_LIMIT' });
}
async function ensureApple(path, token, matches, createPath, body, stage) {
  let found = (await appleCollection(path, token)).filter(matches);
  insist(found.length <= 1, stage, 'AMBIGUOUS_EXISTING_RESOURCE');
  if (!found.length) {
    const result = await postJson(appleRoot + createPath, token, body, stage);
    if (result.data?.id) { report.lastCreatedAppleResource = { type: result.data.type, id: result.data.id, stage }; (report.createdAppleResources ??= []).push(report.lastCreatedAppleResource); }
    found = (await appleCollection(path, token)).filter(matches);
  }
  insist(found.length === 1, stage, 'CREATION_NOT_CONFIRMED');
  return found[0];
}
async function configureGoogle() {
  const token = await googleToken();
  const listed = await getJson(googleRoot + '/subscriptions?pageSize=1000', token, 'google_existing_products');
  insist(!listed.nextPageToken, 'google_existing_products', 'PAGINATION_REQUIRED');
  const products = listed.subscriptions ?? [];
  insist(products.every(item => item.productId === productId), 'google_existing_products', 'UNEXPECTED_EXISTING_PRODUCTS');
  if (!products.length) {
    const conversion = await postJson(googleRoot + '/pricing:convertRegionPrices', token, { price: { currencyCode: 'USD', units: '3', nanos: 0 } }, 'google_price_conversion');
    insist(typeof conversion.regionVersion?.version === 'string', 'google_price_conversion', 'REGION_VERSION_MISSING');
    const createUrl = new URL(googleRoot + '/subscriptions');
    createUrl.searchParams.set('productId', productId);
    createUrl.searchParams.set('regionsVersion.version', conversion.regionVersion.version);
    await postJson(createUrl, token, {
      packageName: 'com.pittech', productId,
      listings: [{ languageCode: 'en-US', title: 'PitTech Pro', description: 'Monthly access to ad-free cooking, personal analysis, advanced curves, and themes.', benefits: ['Ad-free cooking', 'Personal cooking analysis', 'Advanced curves', 'Share-card themes'] }],
      basePlans: [{ basePlanId: 'monthly', autoRenewingBasePlanType: { billingPeriodDuration: 'P1M', gracePeriodDuration: 'P7D' }, regionalConfigs: [{ regionCode: 'US', newSubscriberAvailability: true, price: { currencyCode: 'USD', units: '3', nanos: 0 } }] }]
    }, 'google_create_monthly_draft');
  }
  const product = await getJson(googleRoot + '/subscriptions/' + encodeURIComponent(productId), token, 'google_product_readback');
  const plans = product.basePlans ?? [], plan = plans.find(item => item.basePlanId === 'monthly');
  const us = plan?.regionalConfigs?.find(item => item.regionCode === 'US');
  insist(product.productId === productId && plans.length === 1 && plan?.autoRenewingBasePlanType?.billingPeriodDuration === 'P1M', 'google_product_readback', 'MONTHLY_CONTRACT_MISMATCH');
  insist(us?.price?.currencyCode === 'USD' && Number(us.price.units) === 3 && Number(us.price.nanos ?? 0) === 0, 'google_product_readback', 'PRICE_CONTRACT_MISMATCH');
  insist(plan.state === 'DRAFT', 'google_product_readback', 'UNEXPECTED_BASE_PLAN_STATE');
  return { ok: true, productId, basePlanId: plan.basePlanId, state: plan.state, billingPeriod: 'P1M', usPrice: us.price, activated: false };
}
async function configureApple() {
  const token = appleToken();
  const apps = await appleCollection('/v1/apps?filter[bundleId]=com.pittech&limit=10', token);
  insist(apps.length === 1 && apps[0].id === '6821518899' && apps[0].attributes?.bundleId === 'com.pittech', 'apple_app', 'EXACT_APP_NOT_FOUND');
  const group = await ensureApple('/v1/apps/6821518899/subscriptionGroups?limit=200', token, item => item.attributes?.referenceName === 'PitTech Pro', '/v1/subscriptionGroups',
    { data: { type: 'subscriptionGroups', attributes: { referenceName: 'PitTech Pro' }, relationships: { app: { data: { type: 'apps', id: '6821518899' } } } } }, 'apple_create_group');
  const groupVersion = await ensureApple('/v1/subscriptionGroups/' + group.id + '/versions?limit=200', token, () => true, '/v1/subscriptionGroupVersions',
    { data: { type: 'subscriptionGroupVersions', relationships: { subscriptionGroup: { data: { type: 'subscriptionGroups', id: group.id } } } } }, 'apple_create_group_version');
  await ensureApple('/v1/subscriptionGroupVersions/' + groupVersion.id + '/localizations?limit=200', token, item => item.attributes?.locale === 'en-US', '/v2/subscriptionGroupLocalizations',
    { data: { type: 'subscriptionGroupLocalizations', attributes: { name: 'PitTech Pro', locale: 'en-US' }, relationships: { version: { data: { type: 'subscriptionGroupVersions', id: groupVersion.id } } } } }, 'apple_localize_group');
  const subscription = await ensureApple('/v1/subscriptionGroups/' + group.id + '/subscriptions?limit=200', token, item => item.attributes?.productId === productId, '/v1/subscriptions',
    { data: { type: 'subscriptions', attributes: { name: 'PitTech Pro Monthly', productId, subscriptionPeriod: 'ONE_MONTH', familySharable: false, groupLevel: 1, reviewNote: 'One monthly PitTech Pro plan. Purchases require a signed-in PitTech account and server verification. Account sync, basic logging, and saved recipes remain free.' }, relationships: { group: { data: { type: 'subscriptionGroups', id: group.id } } } } }, 'apple_create_monthly_draft');
  insist(subscription.attributes?.subscriptionPeriod === 'ONE_MONTH', 'apple_product_readback', 'MONTHLY_CONTRACT_MISMATCH');
  await ensureApple('/v1/subscriptions/' + subscription.id + '/subscriptionLocalizations?limit=200', token, item => item.attributes?.locale === 'en-US', '/v1/subscriptionLocalizations',
    { data: { type: 'subscriptionLocalizations', attributes: { name: 'PitTech Pro', locale: 'en-US', description: 'Ad-free cooking, personal analysis, and themes.' }, relationships: { subscription: { data: { type: 'subscriptions', id: subscription.id } } } } }, 'apple_localize_subscription');
  // UPFRONT pays for this ONE_MONTH period. Apple's MONTHLY plan is a
  // 12-month installment commitment and is outside this product's contract.
  const availability = await ensureApple('/v1/subscriptions/' + subscription.id + '/planAvailabilities?limit=200', token, item => item.attributes?.planType === 'UPFRONT', '/v1/subscriptionPlanAvailabilities',
    { data: { type: 'subscriptionPlanAvailabilities', attributes: { planType: 'UPFRONT', availableInNewTerritories: false }, relationships: {
      subscription: { data: { type: 'subscriptions', id: subscription.id } },
      availableTerritories: { data: [{ type: 'territories', id: 'USA' }] }
    } } }, 'apple_configure_one_month_availability');
  const territories = await appleCollection('/v1/subscriptionPlanAvailabilities/' + availability.id + '/availableTerritories?limit=200', token);
  insist(territories.length === 1 && territories[0].id === 'USA', 'apple_availability_readback', 'UNEXPECTED_TERRITORY_AVAILABILITY');
  const points = await appleCollection('/v1/subscriptions/' + subscription.id + '/pricePoints?filter[territory]=USA&include=territory&limit=8000', token);
  const target = points.filter(item => Number(item.attributes?.customerPrice) === 3);
  insist(target.length === 1, 'apple_us_price', 'EXACT_USD_3_PRICE_POINT_NOT_AVAILABLE');
  const prices = await appleCollection('/v1/subscriptions/' + subscription.id + '/prices?filter[planType]=UPFRONT&filter[territory]=USA&include=subscriptionPricePoint&limit=200', token);
  insist(prices.every(item => item.relationships?.subscriptionPricePoint?.data?.id === target[0].id), 'apple_us_price', 'EXISTING_PRICE_REQUIRES_REVIEW');
  if (!prices.length) await postJson(appleRoot + '/v1/subscriptionPrices', token, {
    data: { type: 'subscriptionPrices', attributes: { startDate: null, planType: 'UPFRONT' }, relationships: {
      subscription: { data: { type: 'subscriptions', id: subscription.id } },
      subscriptionPricePoint: { data: { type: 'subscriptionPricePoints', id: target[0].id } }
    } }
  }, 'apple_set_initial_us_price');
  const finalPrices = await appleCollection('/v1/subscriptions/' + subscription.id + '/prices?filter[planType]=UPFRONT&filter[territory]=USA&include=subscriptionPricePoint&limit=200', token);
  insist(finalPrices.some(item => item.relationships?.subscriptionPricePoint?.data?.id === target[0].id), 'apple_price_readback', 'PRICE_NOT_CONFIRMED');
  const final = await getJson(appleRoot + '/v1/subscriptions/' + subscription.id, token, 'apple_product_readback');
  return { ok: true, productId, appId: apps[0].id, groupId: group.id, groupVersionId: groupVersion.id, subscriptionId: subscription.id, subscriptionPeriod: final.data?.attributes?.subscriptionPeriod, state: final.data?.attributes?.state, usCustomerPrice: target[0].attributes.customerPrice, reviewSubmitted: false, planType: 'UPFRONT', availabilityConfigured: true, territories: ['USA'] };
}
insist(process.env.STORE_SETUP_CONFIRM === 'configure-single-monthly-product', 'configuration', 'SETUP_CONFIRM_REQUIRED');
const provider = requireEnv('STORE_SETUP_PROVIDER');
insist(['apple', 'both'].includes(provider), 'configuration', 'INVALID_PROVIDER_SELECTION');
report.selectedProvider = provider;
if (provider === 'both') { try { report.google = await configureGoogle(); } catch (error) { report.google = failure(error, 'google'); } }
else report.google = { ok: false, blocked: true, code: 'PERMISSION_DENIED', observedRunId: '39525750420', attemptedThisRun: false };
try { report.apple = await configureApple(); } catch (error) { report.apple = failure(error, 'apple'); }
await mkdir('store-readiness', { recursive: true });
await writeFile('store-readiness/monthly-product-setup.json', JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
console.info(JSON.stringify(report, null, 2));
if ((provider === 'both' && !report.google.ok) || !report.apple.ok) process.exitCode = 1;
