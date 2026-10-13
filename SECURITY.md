# Security policy

PitTech is a local-first cooking journal. We treat untrusted input, user cooking records, connected accounts, and optional telemetry integrations as sensitive.

## Reporting a vulnerability

**Please do not disclose exploitable details in a public issue, pull request, or diagnostic report.**

Email **sportfolioholdings@gmail.com** with the subject **PitTech security report**. Include a concise description, affected app version/platform, reproduction steps, and impact. Avoid sending real credentials, private cooking data, or other people's device identifiers. If GitHub's private vulnerability reporting feature is enabled for this repository, you may use that instead.

The maintainers will review valid reports and coordinate an appropriate fix and disclosure. No guaranteed response or remediation SLA is offered at this stage.

## Supported versions

The project is in prerelease development. Security support is prioritized for the current `main` branch and actively distributed test builds. Older APKs and obsolete test builds may no longer receive updates.

## Security principles

- Cook logging remains local-first; optional network integrations require deliberate user actions.
- Controller and thermometer integrations are **observation-only**, with no operation to modify hardware state or configuration.
- Experimental and unverified providers are not automatically production-supported.
- Sensitive tokens belong in protected storage, never in logs or release notes.
- All external data is untrusted and must be validated before rendering, storing, or exporting.
- Public diagnostics must be reviewed and redacted before sharing.

Security review does not constitute a guarantee that software is vulnerability-free.
