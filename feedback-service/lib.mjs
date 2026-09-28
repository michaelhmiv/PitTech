const MAX = Object.freeze({
  title: 120,
  description: 1500,
  appVersion: 40,
  androidVersion: 40,
  device: 120,
  referenceCode: 64,
  occurredAtUtc: 80,
  source: 120,
  summary: 500,
  details: 46000,
});

export function normalizeSubmission(input) {
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    throw new ValidationError("Request body must be a JSON object.");
  }

  const kind = String(input.kind ?? "").trim().toUpperCase();
  if (kind !== "BUG" && kind !== "FEATURE" && kind !== "DEVICE_DIAGNOSTIC") {
    throw new ValidationError("kind must be BUG, FEATURE, or DEVICE_DIAGNOSTIC.");
  }

  const title = text(input.title, "title", MAX.title, true);
  const description = text(input.description, "description", MAX.description, false);
  const appVersion = text(input.appVersion, "appVersion", MAX.appVersion, true);
  const androidVersion = text(input.androidVersion, "androidVersion", MAX.androidVersion, true);
  const device = text(input.device, "device", MAX.device, true);

  let diagnosticReport = null;
  if (kind !== "FEATURE" && input.diagnosticReport != null) {
    const report = input.diagnosticReport;
    if (typeof report !== "object" || Array.isArray(report)) {
      throw new ValidationError("diagnosticReport must be an object.");
    }
    diagnosticReport = {
      referenceCode: text(report.referenceCode, "diagnosticReport.referenceCode", MAX.referenceCode, true),
      occurredAtUtc: text(report.occurredAtUtc, "diagnosticReport.occurredAtUtc", MAX.occurredAtUtc, true),
      source: text(report.source, "diagnosticReport.source", MAX.source, true),
      summary: text(report.summary, "diagnosticReport.summary", MAX.summary, true),
      details: text(report.details, "diagnosticReport.details", MAX.details, false),
    };
  }

  if (kind === "DEVICE_DIAGNOSTIC" && diagnosticReport == null) {
    throw new ValidationError("DEVICE_DIAGNOSTIC requires a diagnosticReport.");
  }

  return { kind, title, description, appVersion, androidVersion, device, diagnosticReport };
}

export function buildGitHubIssue(submission) {
  const isBug = submission.kind === "BUG";
  const isDeviceDiagnostic = submission.kind === "DEVICE_DIAGNOSTIC";
  const prefix = isDeviceDiagnostic ? "[Device report]" : isBug ? "[Bug]" : "[Feature]";
  const body = [
    `## ${isDeviceDiagnostic ? "Controller diagnostic report" : isBug ? "Problem report" : "Feature request"}`,
    submission.description || "(No description provided)",
    "",
    "## App and device",
    `- PitTech: ${submission.appVersion}`,
    `- Android: ${submission.androidVersion}`,
    `- Device: ${submission.device}`,
  ];

  if (submission.diagnosticReport) {
    const report = submission.diagnosticReport;
    body.push(
      "",
      "## Diagnostic details",
      "The user reviewed and explicitly chose to submit this diagnostic report.",
      `- Reference: ${report.referenceCode}`,
      `- Occurred (UTC): ${report.occurredAtUtc}`,
      `- Source: ${report.source}`,
      `- Summary: ${report.summary}`,
      "",
      "~~~text",
      report.details,
      "~~~",
    );
  }

  body.push(
    "",
    "---",
    "Submitted from the PitTech app through the anonymous feedback relay.",
  );

  return {
    title: `${prefix} ${submission.title}`,
    body: body.join("\n"),
    labels: [isDeviceDiagnostic ? "device-diagnostics" : isBug ? "bug" : "enhancement"],
  };
}

export class ValidationError extends Error {
  constructor(message) {
    super(message);
    this.name = "ValidationError";
  }
}

export class SlidingWindowLimiter {
  constructor({ limit, windowMs }) {
    this.limit = limit;
    this.windowMs = windowMs;
    this.entries = new Map();
  }

  take(key, now = Date.now()) {
    const floor = now - this.windowMs;
    const prior = (this.entries.get(key) || []).filter((timestamp) => timestamp > floor);
    if (prior.length >= this.limit) {
      this.entries.set(key, prior);
      return false;
    }
    prior.push(now);
    this.entries.set(key, prior);
    return true;
  }
}

function text(value, field, maxLength, required) {
  if (value == null) {
    if (required) throw new ValidationError(`${field} is required.`);
    return "";
  }
  if (typeof value !== "string") throw new ValidationError(`${field} must be a string.`);
  const normalized = value.replace(/\r\n/g, "\n").trim();
  if (required && normalized.length === 0) throw new ValidationError(`${field} is required.`);
  if (normalized.length > maxLength) throw new ValidationError(`${field} is too long.`);
  return normalized;
}
