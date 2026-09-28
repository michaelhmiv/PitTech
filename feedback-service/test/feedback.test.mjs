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

test("controller diagnostic reports create device-report issues with BLE data", () => {
  const submission = normalizeSubmission({
    ...base,
    kind: "DEVICE_DIAGNOSTIC",
    title: "BLE controller: unknown model",
    description: "User-selected unverified device.",
    diagnosticReport: {
      referenceCode: "BLE-12345678",
      occurredAtUtc: "2026-09-28 15:00:00 UTC",
      source: "Bluetooth controller discovery",
      summary: "Unverified BLE device; 8 observations captured.",
      details: "Address: 11:22:33:44:55:66\\nRaw advertisement: 020106",
    },
  });
  const issue = buildGitHubIssue(submission);
  assert.equal(issue.title, "[Device report] BLE controller: unknown model");
  assert.deepEqual(issue.labels, ["device-diagnostics"]);
  assert.match(issue.body, /Controller diagnostic report/);
  assert.match(issue.body, /11:22:33:44:55:66/);
  assert.match(issue.body, /020106/);
  assert.match(issue.body, /explicitly chose to submit/);
});

test("device diagnostics require a report and accept bounded 46K details", () => {
  const report = {
    referenceCode: "BLE-12345678",
    occurredAtUtc: "2026-09-28 15:00:00 UTC",
    source: "Bluetooth controller discovery",
    summary: "BLE scan",
    details: "x".repeat(46_000),
  };
  assert.equal(normalizeSubmission({
    ...base,
    kind: "DEVICE_DIAGNOSTIC",
    diagnosticReport: report,
  }).diagnosticReport.details.length, 46_000);
  assert.throws(() => normalizeSubmission({
    ...base,
    kind: "DEVICE_DIAGNOSTIC",
  }), ValidationError);
  assert.throws(() => normalizeSubmission({
    ...base,
    kind: "DEVICE_DIAGNOSTIC",
    diagnosticReport: { ...report, details: "x".repeat(46_001) },
  }), ValidationError);
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
