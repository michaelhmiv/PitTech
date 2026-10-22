// GitHub App authentication for the feedback relay.
//
// Preferred mode: sign a short-lived RS256 JWT with the app private key,
// exchange it for an installation token, and use that token as the Bearer
// credential when creating issues. A static PAT (GITHUB_TOKEN) remains as a
// transitional fallback while app credentials roll out; see server.mjs.
//
// Every failure here throws GitHubAppAuthError with a fixed message. Auth
// errors must never echo the private key, a JWT, or a token: server.mjs logs
// error messages, so anything sensitive in a message would land in the logs.
import crypto from "node:crypto";

const GITHUB_API = "https://api.github.com";
const REQUEST_TIMEOUT_MS = 10_000;
// 9 minutes from now; iat is backdated 60s for clock skew, so the JWT
// lifetime never exceeds GitHub's 10-minute maximum.
const JWT_TTL_SECONDS = 540;
const REFRESH_MARGIN_MS = 5 * 60 * 1000;
const USER_AGENT = "PitTech-Feedback-Relay";
const API_VERSION = "2022-11-28";

export class GitHubAppAuthError extends Error {
  constructor(message) {
    super(message);
    this.name = "GitHubAppAuthError";
    this.status = 502;
  }
}

// Accepts PEM with real newlines or literal "\n" escapes (single env-line
// form), tolerates wrapping quotes, and never throws on unexpected input.
export function normalizePrivateKey(raw) {
  let key = String(raw ?? "");
  if (key.includes("\\n") || key.includes("\\r")) {
    key = key.replace(/\\r\\n/g, "\n").replace(/\\n/g, "\n").replace(/\\r/g, "\n");
  }
  key = key.trim();
  if (
    key.length >= 2 &&
    ((key.startsWith('"') && key.endsWith('"')) || (key.startsWith("'") && key.endsWith("'")))
  ) {
    key = key.slice(1, -1).trim();
  }
  return key;
}

// 'app' when the App credentials are complete (installation id optional — it
// is discovered when unset), else 'pat' when a PAT is present, else 'none'.
export function resolveAuthMode({ appId = "", privateKey = "", patToken = "" } = {}) {
  if (String(appId).trim() && normalizePrivateKey(privateKey)) return "app";
  if (String(patToken).trim()) return "pat";
  return "none";
}

export function signGitHubAppJwt({ appId, privateKey, nowMs = Date.now(), ttlSeconds = JWT_TTL_SECONDS }) {
  const ttl = Math.min(Math.max(60, Math.floor(ttlSeconds)), 600);
  const nowSec = Math.floor(nowMs / 1000);
  const data = `${base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }))}.${base64url(
    JSON.stringify({ iss: String(appId), iat: nowSec - 60, exp: nowSec + ttl }),
  )}`;
  let signature;
  try {
    signature = crypto.sign("RSA-SHA256", Buffer.from(data, "utf8"), normalizePrivateKey(privateKey));
  } catch {
    throw new GitHubAppAuthError("GitHub App JWT signing failed.");
  }
  return `${data}.${base64url(signature)}`;
}

// fetchImpl and now are injectable for tests. getToken caches the
// installation token in memory, refreshes within 5 minutes of expires_at,
// and shares one in-flight refresh across concurrent callers.
export function createGitHubAppAuth({
  appId,
  privateKey,
  installationId = "",
  owner,
  repo,
  fetchImpl = globalThis.fetch,
  now = Date.now,
  jwtTtlSeconds = JWT_TTL_SECONDS,
}) {
  if (!String(appId).trim()) throw new Error("createGitHubAppAuth requires an appId.");
  if (!normalizePrivateKey(privateKey)) throw new Error("createGitHubAppAuth requires a privateKey.");
  if (!owner || !repo) throw new Error("createGitHubAppAuth requires owner and repo.");

  let cachedToken = null;
  let cachedExpiresAtMs = 0;
  let cachedInstallationId = String(installationId).trim() || null;
  let inflight = null;

  async function getToken() {
    if (cachedToken && cachedExpiresAtMs - now() > REFRESH_MARGIN_MS) return cachedToken;
    if (inflight) return inflight;
    inflight = refresh();
    try {
      return await inflight;
    } finally {
      inflight = null;
    }
  }

  function invalidateToken() {
    cachedToken = null;
    cachedExpiresAtMs = 0;
  }

  async function refresh() {
    const jwt = signGitHubAppJwt({ appId, privateKey, nowMs: now(), ttlSeconds: jwtTtlSeconds });
    const id = cachedInstallationId ?? (await discoverInstallation(jwt));
    const exchanged = await exchangeToken(jwt, id);
    cachedToken = exchanged.token;
    cachedExpiresAtMs = exchanged.expiresAtMs;
    return cachedToken;
  }

  async function discoverInstallation(jwt) {
    let response;
    try {
      response = await fetchImpl(`${GITHUB_API}/repos/${owner}/${repo}/installation`, {
        method: "GET",
        headers: jwtHeaders(jwt),
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
    } catch {
      throw new GitHubAppAuthError("GitHub App installation discovery failed: network error.");
    }
    if (!response.ok) {
      throw new GitHubAppAuthError(
        `GitHub App installation discovery failed (HTTP ${response.status}).`,
      );
    }
    let data;
    try {
      data = await response.json();
    } catch {
      throw new GitHubAppAuthError("GitHub App installation discovery failed: invalid response.");
    }
    if (data?.id === undefined || data?.id === null || String(data.id).trim() === "") {
      throw new GitHubAppAuthError("GitHub App installation discovery failed: invalid response.");
    }
    cachedInstallationId = String(data.id);
    return cachedInstallationId;
  }

  async function exchangeToken(jwt, id) {
    let response;
    try {
      response = await fetchImpl(`${GITHUB_API}/app/installations/${id}/access_tokens`, {
        method: "POST",
        headers: jwtHeaders(jwt),
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
    } catch {
      throw new GitHubAppAuthError("GitHub App token exchange failed: network error.");
    }
    if (!response.ok) {
      throw new GitHubAppAuthError(`GitHub App token exchange failed (HTTP ${response.status}).`);
    }
    let data;
    try {
      data = await response.json();
    } catch {
      throw new GitHubAppAuthError("GitHub App token exchange failed: invalid response.");
    }
    const expiresAtMs = Date.parse(data?.expires_at);
    if (typeof data?.token !== "string" || !data.token || !Number.isFinite(expiresAtMs)) {
      throw new GitHubAppAuthError("GitHub App token exchange failed: invalid response.");
    }
    return { token: data.token, expiresAtMs };
  }

  return { getToken, invalidateToken };
}

// Creates the issue with PAT or installation-token Bearer auth. In app mode a
// 401 with the cached token invalidates the cache and retries once with a
// fresh token; in both modes a 422 with labels retries once without labels
// (a removed/renamed label must never break user feedback).
export async function postGitHubIssue({
  mode,
  patToken = "",
  appAuth = null,
  owner,
  repo,
  issue,
  requestId,
  fetchImpl = globalThis.fetch,
}) {
  const url = `${GITHUB_API}/repos/${owner}/${repo}/issues`;
  let token;
  if (mode === "app") {
    if (!appAuth) throw new GitHubAppAuthError("GitHub App authentication is not configured.");
    token = await appAuth.getToken();
  } else {
    token = patToken;
  }

  let response = await fetchImpl(url, issueRequest(token, issue, requestId));

  if (response.status === 401 && mode === "app") {
    appAuth.invalidateToken();
    token = await appAuth.getToken();
    response = await fetchImpl(url, issueRequest(token, issue, requestId));
  }

  if (response.status === 422 && Array.isArray(issue.labels) && issue.labels.length > 0) {
    response = await fetchImpl(
      url,
      issueRequest(token, { title: issue.title, body: issue.body }, requestId),
    );
  }

  if (!response.ok) {
    const body = await response.text();
    const error = new Error(`GitHub returned HTTP ${response.status}: ${body.slice(0, 500)}`);
    error.status = response.status;
    throw error;
  }
  return response.json();
}

function jwtHeaders(jwt) {
  return {
    Accept: "application/vnd.github+json",
    Authorization: `Bearer ${jwt}`,
    "User-Agent": USER_AGENT,
    "X-GitHub-Api-Version": API_VERSION,
  };
}

function issueRequest(token, body, requestId) {
  return {
    method: "POST",
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      "User-Agent": USER_AGENT,
      "X-GitHub-Api-Version": API_VERSION,
      "X-PitTech-Request-Id": requestId,
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  };
}

function base64url(input) {
  return Buffer.isBuffer(input)
    ? input.toString("base64url")
    : Buffer.from(input, "utf8").toString("base64url");
}
