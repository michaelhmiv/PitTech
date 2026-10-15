# PR 07 — Code and link sharing

Draft implementation for issue #89. Both clients expose Share by code from the
existing selected-content draft, signed-out Import by code in More, and My shared
codes. Upload is an explicit action after a content/retention preview. Receipt
codes can appear on a native card only after the upload succeeds and the local
draft revision still matches the frozen upload.

## Ownership and transfer boundaries

- Generate the owner capability and UUID retry identity on the phone. Secure
  both before the first upload; do not add either to library data or Cook Packs.
- Pending uploads keep the exact exported bytes outside cloud backup. Retrying
  sends those bytes and the same identity, including after a process restart.
- Keep every issued capability when a draft is edited or re-shared. A new code
  does not overwrite management access to earlier codes.
- Cancel aborts the request. It cannot promise the server rejected bytes already
  accepted; the saved retry recovers the same receipt in that case.
- Native clients use bounded HTTPS transfers to the exact API origin, reject
  redirects, verify download byte count and SHA-256, then invoke the existing
  Cook Pack validation and selective-save preview. Relay intake rejects full
  backups. No network response imports data automatically.

## Links and web fallback

Only exact `https://pittech.app/s/<code>` and `pittech://share/<code>` routes are
accepted. Reject credentials, explicit ports, query strings, fragments, encoded
paths, foreign hosts and trailing path segments. The shared golden cases are in
`contracts/fixtures/relay-links-v1.json`.

The web handler displays availability, selected dish count, type, expiry and a
manual code. It does not load the object or expose selected notes/photos. Missing,
expired and revoked codes have the same unavailable page. Requests share the
download abuse limit; route logs redact codes. CSP prohibits script, embeds,
external resources and forms. No deferred import after store installation is
promised.

Configure `APPLE_APP_IDS`, `ANDROID_APP_LINK_CERTIFICATES` and, when actually
available, `APPLE_APP_STORE_URL`. Empty association configuration returns 503;
it never serves fabricated signing fingerprints. Use the production Play app
signing certificate, separately from upload/debug certificates. The iOS CI
profile generator enables Associated Domains before minting its profile.

## Acceptance still required

Native compilation, protocol and secure-storage tests, transport interruption
tests, both enlarged-text flows and screenshot review must pass in Actions.
Backend web tests and production-container checks must pass. DNS/TLS, final
association files, real installed/uninstalled release links, Firebase/TestFlight
variant behavior, and the isolated staging storage/proxy/logging gates require
their own evidence. This draft does not authorize deployment, merge or store
submission. Nothing in this document claims those operational checks passed.
