# PR 11 — Native signed-out discovery, saving and cooking

Both clients gain native discovery, bounded filters/pagination, public recipe
previews, selected photos/captions, recorded stories, private Save and explicit
Cook. Entry remains disabled until the complete accounts/social/sync/deletion
phase passes. Debug automation injects local synthetic responses; it never
contacts Resend or the live API. Release builds cannot activate test fixtures.

| Behavior | iOS | Android |
|---|---|---|
| Anonymous browsing | Native navigation, search/food filter and liked/forked pages | Native Compose equivalents |
| Source content | Captions, ingredients, instructions, verdict and recorded milestones | Same |
| Measurements | Select a distinct labeled channel; actual point plot, manual/recorded symbols | Same, native Canvas with spoken range/count summary |
| Missing measurements | No invented readings, connecting curves, gap filling or finish predictions | Same |
| Saved browsing | Bounded on-device public JSON/photo cache; explicit saved-preview status | Same, no-backup directory |
| Missing original | Known 404 removes stale remote preview; existing local copies remain | Same |
| Save | Download/checksum and source-revision check, then selective Cook Pack preview | Same |
| Cook | Explicit preview/save, then a new locally owned meal from selected recipes | Same |
| Local independence | Owned photos, guide/story and a pinned public source survive offline edits | Same |
| Account requirement | None for browse, preview, save or cook | None |

## Shared transport

GET `/v1/recipes/:id/pack` requires the exact public revision UUID in `If-Match`.
It does not publish, like, create an account or modify the source. A bounded
worker builds the existing Cook Pack format with independently owned recipe,
revision, entry and media IDs. The writer is validated by the same canonical
Cook Pack checks used for real native uploads. Public publication validation now
also checks that its selected data can be represented within the shared native
limits before acceptance. Invalid semantic offsets, missing channel relations
and unsupported scopes cannot create a public item that clients cannot save.

The receiving clients verify the complete size/hash, genuine Cook Pack contract
and exact public item/revision binding before presenting a preview. Cancel makes
no library change. Save uses the existing selective import; Cook explicitly adds
a new meal afterward. Reminders and monitoring do not activate themselves.
Public HTTP clients carry no account Authorization header or cookies, reject
redirects and foreign media URLs, and bound JSON/images/packs at 8/16/64 MiB.
Cached browsing is capped at 32 MiB outside library exports and backups.

`RecipeSnapshot.publicSource` is an optional backward-compatible field containing
only public item/revision UUIDs, display credit, removed status and relative-time
semantics. Both codecs preserve it through local edits and duplication. Optional
instruction `action` remains separate from the culinary `method`. Shared public text limits also bound UTF-16 length so both native codecs accept
the same Unicode payloads. No schema bump or destructive migration is required. Older archives still default these fields.

Published stories provide relative offsets rather than a calendar date. Their
portable guide uses an explicitly marked internal coordinate origin, never an
asserted real cook date. Both reference views show relative times or “Original
date not shared.” Recorded Food on/off properties remain observations, and
stable measured-attempt identity is retained across copies for later aggregate
deduplication. Charts plot actual selected-channel samples; large series retain
endpoints/extrema and label the displayed point count without filling gaps.

## Verification and remaining phase gates

The captured `public-native-v1.json` fixture contains an actual server-produced
Cook Pack, its digest and synthetic source/photo data. Both native consumers test
selective ownership, pinned credit, action semantics and a new independent meal.
Tests cover cache restart/offline fallback, known-404 eviction, strict media
origins, anonymous bounded transfers and explicit Save/Cook UI at both required
text sizes. The generator is `api-service/scripts/public-native-fixture.ts`.
Android core-data/companion gates require 26/18 tests; iOS keeps all compatibility
tests and captures actual UI. Native builds run only in Actions; no native pass
is claimed until those runs finish and screenshots are reviewed.

Native publishing/profiles/forks and `/c/:id` installed/web routing are PR 12.
Takedown/reporting/blocking, deletion and removed-source credit propagation are
PR 13. Private sync is separate in PR 14. Real staging origins/namespaces, physical
links, live delivery, export presentation of relative public stories, full-scale
memory/capacity evidence and complete Phase 2 privacy/visual review remain gates.
No deployment, merge or store submission is authorized by this draft.
