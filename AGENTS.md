# AGENTS.md — working in the PitTech repo

Guidance for any coding agent (Codex, Muse, or other) working in this
repository. Read this before starting work. Product/design decisions live in
GitHub issues; this file holds the durable rules of the road.

## Repo layout

- `app/` — Android app (Kotlin / Jetpack Compose), at the repo **root** module.
- `ios/` — iOS app (Swift / SwiftUI). The Xcode project is generated from
  `ios/project.yml` (XcodeGen) — edit the spec, not the generated project.
- `privacy-site/` — the hosted privacy policy (Railway).
- `feedback-service/` — Android feedback relay (Railway → GitHub issues).
- `store-assets/`, `distribution/` — store listing material.

## Rules of the road

1. **GitHub Actions is the only build machine.** The project owner has no Mac
   and does not build locally. A change is not verified until the relevant
   workflow proves it. If a change lacks the test/CI coverage that would make
   it trustworthy, add it — Actions time is free on this repo.
2. **iOS signing is fully CI-side.** The `app-store-ipa` workflow mints its
   own distribution certificate and "PitTech App Store CI" provisioning
   profile via the App Store Connect API on every run. Never add steps that
   assume local keychains, exported .p12 files, or manually created
   certificates/profiles.
3. **Workflow files:** GitHub App tokens cannot modify `.github/workflows/*`.
   If a task needs a workflow change, surface that explicitly instead of
   assuming a PR can carry it.
4. **AdMob** (publisher `ca-app-pub-2708638041809482`):
   - Android: live native unit only via `-PpittechLiveAds=true`; the `debug`
     build type always forces Google's test unit.
   - iOS: live IDs live **only** in the `Release` config of
     `ios/project.yml`; base/Debug keep Google's test IDs. Never put live
     IDs in a debug-reachable config.
5. **Privacy declarations must match the binary.** Both apps ship the Google
   Mobile Ads + UMP SDKs. Keep `ios/PitTech/PrivacyInfo.xcprivacy`,
   `privacy-site/index.html`, and the store privacy declarations consistent
   with whatever SDKs are actually compiled in. If you add or remove an SDK,
   the declarations are part of the same change.
6. **Data compatibility:** the library archive format is a shared contract
   across platforms (iOS tests import an Android v9 archive fixture). Treat
   archive schema changes as migrations: version them, keep old archives
   importable, and test the round trip.

## Online services implementation — read issue #89 first

The post-launch online-services build (share cards, Cook Packs, code relay,
accounts, publishing, social layer, PitTech Pro) is specced in
**issue #89**, which reproduces the full implementation plan (phases 0–3)
and the settled decisions: PitTech Pro at $3/month, monthly only; magic-link
auth via Resend (no passwords); anonymous publishing allowed; comments out
of scope; sync and publishing are separate systems (backup is a private
mirror, publishing is a deliberate per-recipe act).

## Parity commitment (owner directive — do not drift)

The owner requires iOS/Android parity going forward:

- Both clients are in every feature's definition of done. Sequencing work
  (one platform leads) is fine; parking the other platform as "later" is not.
- Maintain a feature parity matrix (in the repo) as implementation proceeds:
  every platform gap is marked port, drop, or platform-appropriate with a
  written reason. An undocumented difference is a defect.
- Shared contracts (archive format, Cook Pack format, backend APIs) get
  cross-platform round-trip tests in CI.
- The existing Android-only surface (grill-provider integrations, telemetry)
  gets an explicit decision in the matrix — port, keep Android-only by
  decision, or fold into the plan — not silent divergence.
