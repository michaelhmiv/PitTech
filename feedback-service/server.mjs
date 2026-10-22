import http from "node:http";
import crypto from "node:crypto";
import {
  SlidingWindowLimiter,
  ValidationError,
  buildGitHubIssue,
  normalizeSubmission,
} from "./lib.mjs";
import { createGitHubAppAuth, postGitHubIssue, resolveAuthMode } from "./github-app.mjs";

const port = Number(process.env.PORT || 3000);
const patToken = process.env.GITHUB_TOKEN || "";
const appId = process.env.GITHUB_APP_ID || "";
const appPrivateKey = process.env.GITHUB_APP_PRIVATE_KEY || "";
const appInstallationId = process.env.GITHUB_APP_INSTALLATION_ID || "";
const repository = process.env.GITHUB_REPOSITORY || "michaelhmiv/PitTech";
const [owner, repo] = repository.split("/");
if (!owner || !repo) throw new Error("GITHUB_REPOSITORY must use owner/name format.");

const githubAuthMode = resolveAuthMode({ appId, privateKey: appPrivateKey, patToken });
const githubConfigured = githubAuthMode !== "none";
const appAuth =
  githubAuthMode === "app"
    ? createGitHubAppAuth({ appId, privateKey: appPrivateKey, installationId: appInstallationId, owner, repo })
    : null;

const perIpLimiter = new SlidingWindowLimiter({ limit: 5, windowMs: 60 * 60 * 1000 });
const globalLimiter = new SlidingWindowLimiter({ limit: 100, windowMs: 60 * 60 * 1000 });

const server = http.createServer(async (req, res) => {
  const requestId = crypto.randomUUID();

  if (req.method === "GET" && req.url === "/health") {
    return json(res, 200, {
      status: "ok",
      githubConfigured,
      githubAuthMode,
      repository,
    });
  }

  if (req.method !== "POST" || req.url !== "/v1/feedback") {
    return json(res, 404, { error: "not_found", requestId });
  }

  if (!String(req.headers["content-type"] || "").toLowerCase().startsWith("application/json")) {
    return json(res, 415, { error: "application_json_required", requestId });
  }

  const ip = clientIp(req);
  if (!perIpLimiter.take(ip) || !globalLimiter.take("global")) {
    res.setHeader("Retry-After", "3600");
    return json(res, 429, { error: "rate_limited", requestId });
  }

  if (!githubConfigured) {
    return json(res, 503, { error: "feedback_relay_not_configured", requestId });
  }

  try {
    const raw = await readBody(req, 64 * 1024);
    const submission = normalizeSubmission(JSON.parse(raw));
    const issue = buildGitHubIssue(submission);
    const created = await postGitHubIssue({
      mode: githubAuthMode,
      patToken,
      appAuth,
      owner,
      repo,
      issue,
      requestId,
    });

    console.log(JSON.stringify({
      event: "feedback_issue_created",
      requestId,
      issueNumber: created.number,
      kind: submission.kind,
    }));

    return json(res, 201, {
      ok: true,
      issueNumber: created.number,
      issueUrl: created.html_url,
      requestId,
    });
  } catch (error) {
    if (error instanceof ValidationError || error instanceof SyntaxError) {
      return json(res, 400, { error: "invalid_feedback", message: error.message, requestId });
    }
    if (error?.code === "BODY_TOO_LARGE") {
      return json(res, 413, { error: "payload_too_large", requestId });
    }

    console.error(JSON.stringify({
      event: "feedback_issue_failed",
      requestId,
      message: error?.message || "Unknown error",
      status: error?.status || null,
    }));
    return json(res, 502, { error: "github_issue_creation_failed", requestId });
  }
});

server.listen(port, "0.0.0.0", () => {
  console.log(JSON.stringify({ event: "feedback_relay_started", port, repository, githubConfigured, githubAuthMode }));
});

function clientIp(req) {
  const railway = req.headers["x-real-ip"];
  if (typeof railway === "string" && railway.trim()) return railway.trim();
  const forwarded = req.headers["x-forwarded-for"];
  if (typeof forwarded === "string" && forwarded.trim()) return forwarded.split(",")[0].trim();
  return req.socket.remoteAddress || "unknown";
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > limit) {
        const error = new Error("Body too large.");
        error.code = "BODY_TOO_LARGE";
        reject(error);
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function json(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Content-Length": Buffer.byteLength(body),
  });
  res.end(body);
}
