import test from "node:test";
import assert from "node:assert/strict";
import {
  SlidingWindowLimiter,
  ValidationError,
  buildGitHubIssue,
  normalizeSubmission,
} from "../lib.mjs";

const base = {
  kind: "BUG",
  title: "App closes after saving",
  description: "It happened when I saved a cook.",
  appVersion: "0.1.0",
  androidVersion: "17 (API 37)",
  device: "Google Pixel 8 Pro",
};

test("normalizes a valid bug report and includes opted-in diagnostics", () => {
  const submission = normalizeSubmission({
    ...base,
    diagnosticReport: {
      referenceCode: "PT-AB12CD34",
      occurredAtUtc: "2026-09-28 14:00:00 UTC",
      source: "Uncaught application exception",
      summary: "IllegalStateException: failed to save",
      details: "Thread: main\nstack frame",
    },
  });

  const issue = buildGitHubIssue(submission);
  assert.equal(issue.title, "[Bug] App closes after saving");
  assert.deepEqual(issue.labels, ["bug"]);
  assert.match(issue.body, /PT-AB12CD34/);
  assert.match(issue.body, /stack frame/);
});

test("feature requests ignore diagnostic data supplied by a client", () => {
  const submission = normalizeSubmission({
    ...base,
    kind: "FEATURE",
    title: "Add a cook timer",
    diagnosticReport: {
      referenceCode: "PT-SECRET",
      occurredAtUtc: "now",
      source: "client",
      summary: "private",
      details: "private trace",
    },
  });

  assert.equal(submission.diagnosticReport, null);
  const issue = buildGitHubIssue(submission);
  assert.equal(issue.title, "[Feature] Add a cook timer");
  assert.deepEqual(issue.labels, ["enhancement"]);
  assert.doesNotMatch(issue.body, /private trace/);
});

test("rejects oversized or missing required fields", () => {
  assert.throws(
    () => normalizeSubmission({ ...base, title: "" }),
    ValidationError,
  );
  assert.throws(
    () => normalizeSubmission({ ...base, description: "x".repeat(1501) }),
    ValidationError,
  );
});

test("rate limiter enforces a sliding window", () => {
  const limiter = new SlidingWindowLimiter({ limit: 2, windowMs: 1000 });
  assert.equal(limiter.take("a", 1000), true);
  assert.equal(limiter.take("a", 1500), true);
  assert.equal(limiter.take("a", 1600), false);
  assert.equal(limiter.take("a", 2101), true);
});
