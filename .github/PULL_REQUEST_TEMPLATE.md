## What changed?

Briefly describe the benefit to someone using PitTech. Avoid implementation-only summaries.

## Platforms

- [ ] Android
- [ ] iOS
- [ ] MCP (issue #114)
- [ ] Documentation / release tooling only

## Capability impact (Android / iOS / MCP parity — issue #114 §3.1)

Every PR declares its capability impact. Check one and complete it:

- [ ] **No capability surface change** — concrete reason: <!-- required; "internal only" is not a reason -->
- [ ] **Capability added or changed** — `contracts/capabilities-v1.json` updated in this PR (bindings for every affected interface, generated `docs/MCP_PARITY.md` regenerated), and the `Capability parity` check passes. A private feature enabled in either app without its MCP disposition declared fails that check by design.

## User-facing release note

Write a short plain-language note that can be included in the changelog or app update description. If no user-visible change, write **None (internal only)**.

## Verification

- [ ] Relevant JVM/unit tests
- [ ] Emulator/instrumentation tests where applicable
- [ ] Data migration and backup/restore review where applicable
- [ ] Documentation and privacy reviewed
- [ ] No controller state-changing operations introduced
- [ ] Provider Beta label / licensing / hardware gates reviewed where applicable

## Known limitations

What should users or reviewers know?
