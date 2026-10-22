import { createHash, sign } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';

const report = { readOnly: false, configuration: 'Ensure the subscription review screenshot; no activation or review submission', sourceSha: process.env.GITHUB_SHA, runId: process.env.GITHUB_RUN_ID };
const productId = 'pittech.pro.monthly';
const subscriptionId = '6825135995';
const appleRoot = 'https://api.appstoreconnect.apple.com';
const reviewImageName = 'pittech-pro-paywall-review.png';

// ASC endpoint construction: one function per resource, so a live-API path
// correction is a one-line change. The review-screenshot resource is
// subscriptionAppStoreReviewScreenshots (relationship: appStoreReviewScreenshot)
// — confirmed against a public, tested ASC client implementation after the
// first live run 404'd on the originally guessed subscriptionReviewScreenshots
// path (run 39757909836, stage apple_reserve_review_screenshot, NOT_FOUND).
const subscriptionPath = id => '/v1/subscriptions/' + encodeURIComponent(id);
const reviewScreenshotPath = id => subscriptionPath(id) + '/appStoreReviewScreenshot';
const reviewScreenshotsCreatePath = () => '/v1/subscriptionAppStoreReviewScreenshots';
const reviewScreenshotResourcePath = id => '/v1/subscriptionAppStoreReviewScreenshots/' + encodeURIComponent(id);
report.attemptedPaths = {
  subscription: subscriptionPath(subscriptionId),
  reviewScreenshot: reviewScreenshotPath(subscriptionId),
  reviewScreenshotsCreate: reviewScreenshotsCreatePath()
};

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
function insist(value, stage, code) { if (!value) throw Object.assign(new Error(), { stage, code }); }
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
async function appleJson(method, path, token, body, stage) {
  const target = new URL(path, appleRoot);
  insist(target.origin === appleRoot, stage, 'UNEXPECTED_PROVIDER_ORIGIN');
  return responseJson(await fetch(target, {
    method, headers: { Authorization: 'Bearer ' + token, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    redirect: 'error', signal: AbortSignal.timeout(20000)
  }), stage);
}
const getJson = (path, token, stage) => appleJson('GET', path, token, undefined, stage);
const postJson = (path, token, body, stage) => appleJson('POST', path, token, body, stage);
const patchJson = (path, token, body, stage) => appleJson('PATCH', path, token, body, stage);
function failure(error, stage) {
  return { ok: false, stage: error?.stage ?? stage, ...(Number.isInteger(error?.status) ? { httpStatus: error.status } : {}), code: error?.code && /^[A-Z0-9_.-]{1,80}$/.test(error.code) ? error.code : 'REQUEST_FAILED' };
}
function appleToken() {
  const now = Math.floor(Date.now() / 1000);
  return jwt({ alg: 'ES256', kid: requireEnv('ASC_KEY_ID'), typ: 'JWT' }, { iss: requireEnv('ASC_ISSUER_ID'), iat: now, exp: now + 900, aud: 'appstoreconnect-v1' }, requireEnv('ASC_PRIVATE_KEY'), true);
}

// Pure upload helpers: checksum, image validation, request bodies and upload
// slicing stay free of network access so a live-API correction stays local.
const sourceFileChecksum = bytes => createHash('md5').update(bytes).digest('hex');
function validatePng(bytes) {
  if (!Buffer.isBuffer(bytes) || !bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return { ok: false, reason: 'NOT_PNG' };
  if (bytes.length < 1024) return { ok: false, reason: 'IMAGE_TOO_SMALL' };
  if (bytes.length > 10 * 1024 * 1024) return { ok: false, reason: 'IMAGE_TOO_LARGE' };
  return { ok: true, bytes: bytes.length };
}
function reviewScreenshotCreateBody(fileName, fileSize) {
  return { data: { type: 'subscriptionAppStoreReviewScreenshots', attributes: { fileName, fileSize }, relationships: { subscription: { data: { type: 'subscriptions', id: subscriptionId } } } } };
}
function reviewScreenshotCommitBody(id, checksum) {
  return { data: { type: 'subscriptionAppStoreReviewScreenshots', id, attributes: { uploaded: true, sourceFileChecksum: checksum } } };
}
function uploadSlice(bytes, operation) {
  const offset = Number(operation?.offset ?? 0), length = Number(operation?.length ?? bytes.length);
  insist(Number.isInteger(offset) && Number.isInteger(length) && offset >= 0 && length > 0 && offset + length <= bytes.length, 'apple_upload_operation', 'INVALID_UPLOAD_OPERATION');
  const headers = Object.fromEntries((operation?.requestHeaders ?? []).filter(header => typeof header?.name === 'string' && typeof header?.value === 'string').map(header => [header.name, header.value]));
  return { offset, length, headers };
}
async function applyUploadOperations(operations, bytes) {
  insist(Array.isArray(operations) && operations.length > 0, 'apple_upload_operation', 'UPLOAD_OPERATIONS_MISSING');
  insist(operations.length <= 32, 'apple_upload_operation', 'TOO_MANY_UPLOAD_OPERATIONS');
  for (const operation of operations) {
    let target = null;
    try { target = new URL(String(operation?.url ?? '')); } catch { target = null; }
    insist(target && target.protocol === 'https:', 'apple_upload_operation', 'UNEXPECTED_UPLOAD_ORIGIN');
    insist((operation?.method ?? 'PUT').toUpperCase() === 'PUT', 'apple_upload_operation', 'UNEXPECTED_UPLOAD_METHOD');
    const { offset, length, headers } = uploadSlice(bytes, operation);
    // Signed upload URLs carry their own authorization; the ASC token is never sent off-origin.
    const response = await fetch(target, { method: 'PUT', headers, body: bytes.subarray(offset, offset + length), redirect: 'error', signal: AbortSignal.timeout(60000) });
    if (!response.ok) throw Object.assign(new Error(), { stage: 'apple_upload_operation', status: response.status, code: 'UPLOAD_REJECTED' });
    await response.arrayBuffer().catch(() => {});
  }
}
async function readReviewScreenshot(token) {
  try {
    const body = await getJson(reviewScreenshotPath(subscriptionId), token, 'apple_review_screenshot_read');
    const node = Array.isArray(body.data) ? body.data[0] : body.data;
    if (!node) return { present: false };
    return { present: true, id: node.id ?? null, uploaded: node.attributes?.uploaded ?? null };
  } catch (error) {
    // A missing screenshot (or an unconfirmed relationship path) reads as absent; creation confirms the path.
    if (error?.status === 404) return { present: false, notFound: true };
    throw error;
  }
}
async function ensureReviewScreenshot() {
  insist(process.env.REVIEW_SCREENSHOT_CONFIRM === 'upload-subscription-review-screenshot', 'configuration', 'SETUP_CONFIRM_REQUIRED');
  const imagePath = requireEnv('REVIEW_SCREENSHOT_FILE');
  const token = appleToken();
  const before = await getJson(subscriptionPath(subscriptionId), token, 'apple_product_readback');
  insist(before.data?.id === subscriptionId && before.data?.attributes?.productId === productId, 'apple_product_readback', 'EXACT_SUBSCRIPTION_NOT_FOUND');
  const existing = await readReviewScreenshot(token);
  if (existing.present) {
    return { ok: true, productId, subscriptionId, alreadyPresent: true, reviewScreenshotId: existing.id, subscriptionState: before.data?.attributes?.state ?? null, uploaded: false };
  }
  const bytes = await readFile(imagePath);
  const valid = validatePng(bytes);
  insist(valid.ok, 'review_image', valid.reason ?? 'INVALID_IMAGE');
  const reserved = await postJson(reviewScreenshotsCreatePath(), token, reviewScreenshotCreateBody(reviewImageName, bytes.length), 'apple_reserve_review_screenshot');
  const resourceId = reserved.data?.id;
  insist(typeof resourceId === 'string' && resourceId.length > 0, 'apple_reserve_review_screenshot', 'RESERVATION_NOT_CONFIRMED');
  await applyUploadOperations(reserved.data?.attributes?.uploadOperations, bytes);
  await patchJson(reviewScreenshotResourcePath(resourceId), token, reviewScreenshotCommitBody(resourceId, sourceFileChecksum(bytes)), 'apple_commit_review_screenshot');
  const after = await getJson(subscriptionPath(subscriptionId), token, 'apple_product_readback');
  const confirmed = await readReviewScreenshot(token);
  return { ok: true, productId, subscriptionId, alreadyPresent: false, reviewScreenshotId: resourceId, reviewScreenshotPresent: confirmed.present, subscriptionState: after.data?.attributes?.state ?? null, uploaded: true };
}
try { report.apple = await ensureReviewScreenshot(); } catch (error) { report.apple = failure(error, 'apple'); }
await mkdir('store-readiness', { recursive: true });
await writeFile('store-readiness/review-screenshot.json', JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
console.info(JSON.stringify(report, null, 2));
if (!report.apple.ok) process.exitCode = 1;
