import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import {
  GitHubAppAuthError,
  createGitHubAppAuth,
  normalizePrivateKey,
  postGitHubIssue,
  resolveAuthMode,
  signGitHubAppJwt,
} from "../github-app.mjs";

const OWNER = "michaelhmiv";
const REPO = "PitTech";
const APP_ID = "123456";
const INSTALLATION_ID = "789012";
const BASE_MS = Date.parse("2026-10-22T00:00:00Z");

function makeKeyPair() {
  return crypto.generateKeyPairSync("rsa", {
    modulusLength: 2048,
    publicKeyEncoding: { type: "spki", format: "pem" },
    privateKeyEncoding: { type: "pkcs8", format: "pem" },
  });
}

function decodeSegment(segment) {
  return JSON.parse(Buffer.from(segment, "base64url").toString("utf8"));
}

function mockResponse({ status = 200, jsonBody = null, textBody = null } = {}) {
  return {
    status,
    ok: status >= 200 && status < 300,
    json: async () => {
      if (jsonBody === null) throw new Error("no json body");
      return jsonBody;
    },
    text: async () => (textBody ?? (jsonBody === null ? "" : JSON.stringify(jsonBody))),
  };
}

function controllableClock(startMs = BASE_MS) {
  let current = startMs;
  return { now: () => current, advance: (ms) => { current += ms; } };
}

test("signs an RS256 JWT with the app id as issuer and expiry within 10 minutes", () => {
  const { publicKey, privateKey } = makeKeyPair();
  const jwt = signGitHubAppJwt({ appId: APP_ID, privateKey, nowMs: BASE_MS });
  const [header, payload, signature] = jwt.split(".");
  assert.equal([header, payload, signature].filter(Boolean).length, 3);
  assert.deepEqual(decodeSegment(header), { alg: "RS256", typ: "JWT" });

  const claims = decodeSegment(payload);
  const nowSec = Math.floor(BASE_MS / 1000);
  assert.equal(claims.iss, APP_ID);
  assert.equal(typeof claims.iat, "number");
  assert.equal(typeof claims.exp, "number");
  assert.ok(claims.exp - nowSec <= 600, "exp must be within 10 minutes of now");
  assert.ok(claims.exp - claims.iat <= 600, "JWT lifetime must not exceed 10 minutes");
  assert.ok(claims.exp > nowSec, "exp must be in the future");

  const valid = crypto.verify(
    "RSA-SHA256",
    Buffer.from(`${header}.${payload}`, "utf8"),
    publicKey,
    Buffer.from(signature, "base64url"),
  );
  assert.equal(valid, true);
});

test("accepts a private key with literal backslash-n escapes", () => {
  const { publicKey, privateKey } = makeKeyPair();
  assert.match(privateKey, /\n/);
  const escaped = privateKey.replace(/\n/g, "\\n");
  assert.doesNotMatch(escaped, /\n/);
  assert.equal(normalizePrivateKey(escaped), privateKey.trim());

  const jwt = signGitHubAppJwt({ appId: APP_ID, privateKey: escaped, nowMs: BASE_MS });
  const [header, payload, signature] = jwt.split(".");
  const valid = crypto.verify(
    "RSA-SHA256",
    Buffer.from(`${header}.${payload}`, "utf8"),
    publicKey,
    Buffer.from(signature, "base64url"),
  );
  assert.equal(valid, true);
});

test("token exchange uses the documented request shape", async () => {
  const { privateKey } = makeKeyPair();
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url, options });
    return mockResponse({
      jsonBody: { token: "install-token-1", expires_at: new Date(BASE_MS + 3600_000).toISOString() },
    });
  };
  const auth = createGitHubAppAuth({
    appId: APP_ID,
    privateKey,
    installationId: INSTALLATION_ID,
    owner: OWNER,
    repo: REPO,
    fetchImpl,
    now: () => BASE_MS,
  });

  const token = await auth.getToken();
  assert.equal(token, "install-token-1");
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, `https://api.github.com/app/installations/${INSTALLATION_ID}/access_tokens`);
  assert.equal(calls[0].options.method, "POST");
  assert.equal(calls[0].options.headers.Accept, "application/vnd.github+json");
  assert.equal(calls[0].options.headers["User-Agent"], "PitTech-Feedback-Relay");
  assert.equal(calls[0].options.headers["X-GitHub-Api-Version"], "2022-11-28");
  const bearer = calls[0].options.headers.Authorization;
  assert.match(bearer, /^Bearer /);
  assert.equal(bearer.slice("Bearer ".length).split(".").length, 3);
});

test("caches the installation token until near expiry", async () => {
  const { privateKey } = makeKeyPair();
  let exchanges = 0;
  const clock = controllableClock();
  const fetchImpl = async () => {
    exchanges += 1;
    return mockResponse({
      jsonBody: { token: `install-token-${exchanges}`, expires_at: new Date(BASE_MS + 3600_000).toISOString() },
    });
  };
  const auth = createGitHubAppAuth({
    appId: APP_ID,
    privateKey,
    installationId: INSTALLATION_ID,
    owner: OWNER,
    repo: REPO,
    fetchImpl,
    now: clock.now,
  });

  assert.equal(await auth.getToken(), "install-token-1");
  assert.equal(await auth.getToken(), "install-token-1");
  assert.equal(exchanges, 1);

  // Six minutes before expiry is outside the 5-minute refresh window.
  clock.advance(3600_000 - 6 * 60_000);
  assert.equal(await auth.getToken(), "install-token-1");
  assert.equal(exchanges, 1);

  // Four minutes before expiry is inside the window: refresh.
  clock.advance(2 * 60_000);
  assert.equal(await auth.getToken(), "install-token-2");
  assert.equal(exchanges, 2);
});

test("shares a single in-flight refresh under concurrency", async () => {
  const { privateKey } = makeKeyPair();
  let exchanges = 0;
  const fetchImpl = async () => {
    exchanges += 1;
    await new Promise((resolve) => setTimeout(resolve, 20));
    return mockResponse({
      jsonBody: { token: "install-token-1", expires_at: new Date(BASE_MS + 3600_000).toISOString() },
    });
  };
  const auth = createGitHubAppAuth({
    appId: APP_ID,
    privateKey,
    installationId: INSTALLATION_ID,
    owner: OWNER,
    repo: REPO,
    fetchImpl,
    now: () => BASE_MS,
  });

  const tokens = await Promise.all([
    auth.getToken(),
    auth.getToken(),
    auth.getToken(),
    auth.getToken(),
    auth.getToken(),
  ]);
  assert.deepEqual(tokens, ["install-token-1", "install-token-1", "install-token-1", "install-token-1", "install-token-1"]);
  assert.equal(exchanges, 1);
});

test("discovers the installation id once when unset", async () => {
  const { privateKey } = makeKeyPair();
  const clock = controllableClock();
  const discoveries = [];
  const exchanges = [];
  const fetchImpl = async (url, options) => {
    if (url === `https://api.github.com/repos/${OWNER}/${REPO}/installation`) {
      discoveries.push({ url, options });
      assert.equal(options.method, "GET");
      assert.match(options.headers.Authorization, /^Bearer /);
      return mockResponse({ jsonBody: { id: 4242 } });
    }
    exchanges.push({ url, options });
    return mockResponse({
      jsonBody: {
        token: `install-token-${exchanges.length}`,
        expires_at: new Date(clock.now() + 3600_000).toISOString(),
      },
    });
  };
  const auth = createGitHubAppAuth({
    appId: APP_ID,
    privateKey,
    owner: OWNER,
    repo: REPO,
    fetchImpl,
    now: clock.now,
  });

  assert.equal(await auth.getToken(), "install-token-1");
  assert.equal(discoveries.length, 1);
  assert.equal(exchanges.length, 1);
  assert.equal(exchanges[0].url, "https://api.github.com/app/installations/4242/access_tokens");

  // A later refresh reuses the discovered installation id without rediscovery.
  clock.advance(3600_000);
  assert.equal(await auth.getToken(), "install-token-2");
  assert.equal(discoveries.length, 1);
  assert.equal(exchanges.length, 2);
});

test("exchange failure surfaces a sanitized 5xx with no credential material", async () => {
  const { privateKey } = makeKeyPair();
  const fetchImpl = async () => mockResponse({ status: 500, jsonBody: { message: "boom" } });
  const auth = createGitHubAppAuth({
    appId: APP_ID,
    privateKey,
    installationId: INSTALLATION_ID,
    owner: OWNER,
    repo: REPO,
    fetchImpl,
    now: () => BASE_MS,
  });

  const error = await auth.getToken().then(
    () => { throw new Error("expected getToken to fail"); },
    (failure) => failure,
  );
  assert.ok(error instanceof GitHubAppAuthError);
  assert.equal(error.status, 502);
  const output = `${error.message}\n${JSON.stringify({ name: error.name, status: error.status })}`;
  assert.doesNotMatch(output, /PRIVATE KEY/);
  assert.doesNotMatch(output, /-----BEGIN/);
  assert.doesNotMatch(output, /eyJ/);
  for (const fragment of privateKey.split("\n").filter((line) => line.trim().length > 12).slice(1, 4)) {
    assert.ok(!output.includes(fragment.trim()), "output must not contain key material");
  }

  // Through the submission path the failure is still a clear 5xx.
  const submissionError = await postGitHubIssue({
    mode: "app",
    appAuth: auth,
    owner: OWNER,
    repo: REPO,
    issue: { title: "t", body: "b", labels: ["bug"] },
    requestId: "req-1",
    fetchImpl: async () => { throw new Error("issues endpoint must not be reached"); },
  }).then(
    () => { throw new Error("expected postGitHubIssue to fail"); },
    (failure) => failure,
  );
  assert.ok(submissionError.status >= 500 && submissionError.status < 600);
  assert.doesNotMatch(`${submissionError.message}`, /PRIVATE KEY|BEGIN|eyJ/);
});

test("unusable private key fails closed without leaking key material", async () => {
  const auth = createGitHubAppAuth({
    appId: APP_ID,
    privateKey: "not-a-valid-private-key",
    installationId: INSTALLATION_ID,
    owner: OWNER,
    repo: REPO,
    fetchImpl: async () => { throw new Error("network must not be reached"); },
    now: () => BASE_MS,
  });
  const error = await auth.getToken().then(
    () => { throw new Error("expected getToken to fail"); },
    (failure) => failure,
  );
  assert.ok(error instanceof GitHubAppAuthError);
  assert.equal(error.status, 502);
  assert.ok(!`${error.message}`.includes("not-a-valid-private-key"));
});

test("a 401 with a cached installation token retries once with a fresh token", async () => {
  const { privateKey } = makeKeyPair();
  const clock = controllableClock();
  let exchanges = 0;
  const issueCalls = [];
  const fetchImpl = async (url, options) => {
    if (url.includes("/access_tokens")) {
      exchanges += 1;
      return mockResponse({
        jsonBody: {
          token: `install-token-${exchanges}`,
          expires_at: new Date(clock.now() + 3600_000).toISOString(),
        },
      });
    }
    issueCalls.push({ url, options });
    if (issueCalls.length === 1) return mockResponse({ status: 401, jsonBody: { message: "Bad credentials" } });
    return mockResponse({ status: 201, jsonBody: { number: 7, html_url: "https://github.com/x/y/issues/7" } });
  };
  const auth = createGitHubAppAuth({
    appId: APP_ID,
    privateKey,
    installationId: INSTALLATION_ID,
    owner: OWNER,
    repo: REPO,
    fetchImpl,
    now: clock.now,
  });

  const created = await postGitHubIssue({
    mode: "app",
    appAuth: auth,
    owner: OWNER,
    repo: REPO,
    issue: { title: "t", body: "b", labels: ["bug"] },
    requestId: "req-2",
    fetchImpl,
  });
  assert.equal(created.number, 7);
  assert.equal(exchanges, 2);
  assert.equal(issueCalls.length, 2);
  assert.equal(issueCalls[0].options.headers.Authorization, "Bearer install-token-1");
  assert.equal(issueCalls[1].options.headers.Authorization, "Bearer install-token-2");
});

test("a repeated 401 fails after exactly one retry", async () => {
  const { privateKey } = makeKeyPair();
  let issues = 0;
  const fetchImpl = async (url) => {
    if (url.includes("/access_tokens")) {
      return mockResponse({
        jsonBody: { token: "install-token", expires_at: new Date(BASE_MS + 3600_000).toISOString() },
      });
    }
    issues += 1;
    return mockResponse({ status: 401, jsonBody: { message: "Bad credentials" } });
  };
  const auth = createGitHubAppAuth({
    appId: APP_ID,
    privateKey,
    installationId: INSTALLATION_ID,
    owner: OWNER,
    repo: REPO,
    fetchImpl,
    now: () => BASE_MS,
  });

  const error = await postGitHubIssue({
    mode: "app",
    appAuth: auth,
    owner: OWNER,
    repo: REPO,
    issue: { title: "t", body: "b", labels: ["bug"] },
    requestId: "req-3",
    fetchImpl,
  }).then(
    () => { throw new Error("expected postGitHubIssue to fail"); },
    (failure) => failure,
  );
  assert.equal(error.status, 401);
  assert.equal(issues, 2);
});

test("PAT mode posts issues with the PAT and never touches the App endpoints", async () => {
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url, options });
    return mockResponse({ status: 201, jsonBody: { number: 9, html_url: "https://github.com/x/y/issues/9" } });
  };
  const created = await postGitHubIssue({
    mode: "pat",
    patToken: "legacy-pat",
    owner: OWNER,
    repo: REPO,
    issue: { title: "t", body: "b", labels: ["bug"] },
    requestId: "req-4",
    fetchImpl,
  });
  assert.equal(created.number, 9);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, `https://api.github.com/repos/${OWNER}/${REPO}/issues`);
  assert.equal(calls[0].options.headers.Authorization, "Bearer legacy-pat");
});

test("422 with labels still retries once without labels in both modes", async () => {
  for (const mode of ["pat", "app"]) {
    const { privateKey } = makeKeyPair();
    const bodies = [];
    const fetchImpl = async (url, options) => {
      if (url.includes("/access_tokens")) {
        return mockResponse({
          jsonBody: { token: "install-token", expires_at: new Date(BASE_MS + 3600_000).toISOString() },
        });
      }
      bodies.push(JSON.parse(options.body));
      if (bodies.length === 1) return mockResponse({ status: 422, jsonBody: { message: "label missing" } });
      return mockResponse({ status: 201, jsonBody: { number: 11, html_url: "https://github.com/x/y/issues/11" } });
    };
    const created = await postGitHubIssue({
      mode,
      patToken: "legacy-pat",
      appAuth:
        mode === "app"
          ? createGitHubAppAuth({
              appId: APP_ID,
              privateKey,
              installationId: INSTALLATION_ID,
              owner: OWNER,
              repo: REPO,
              fetchImpl,
              now: () => BASE_MS,
            })
          : null,
      owner: OWNER,
      repo: REPO,
      issue: { title: "t", body: "b", labels: ["bug"] },
      requestId: `req-${mode}`,
      fetchImpl,
    });
    assert.equal(created.number, 11);
    assert.equal(bodies.length, 2);
    assert.deepEqual(bodies[1], { title: "t", body: "b" });
  }
});

test("auth mode prefers the App and falls back to the PAT only when incomplete", () => {
  const { privateKey } = makeKeyPair();
  assert.equal(resolveAuthMode({ appId: APP_ID, privateKey, patToken: "legacy-pat" }), "app");
  assert.equal(resolveAuthMode({ appId: APP_ID, privateKey }), "app");
  assert.equal(resolveAuthMode({ appId: "", privateKey, patToken: "legacy-pat" }), "pat");
  assert.equal(resolveAuthMode({ appId: APP_ID, privateKey: "", patToken: "legacy-pat" }), "pat");
  assert.equal(resolveAuthMode({ patToken: "legacy-pat" }), "pat");
  assert.equal(resolveAuthMode({}), "none");
  assert.equal(resolveAuthMode({ appId: APP_ID, privateKey: "" }), "none");
});
