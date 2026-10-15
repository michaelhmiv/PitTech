# PR 12 — Publishing, public cookbooks and pinned variations

Work in progress. The community phase stays disabled in both normal app builds.
No deployment, merge or release is authorized by this draft.

Implemented foundation:

- Canonical public recipe `/c/:uuid` and opt-in cookbook `/p/:handle` HTML pages.
  All authored text is escaped; headers prohibit scripts, framing, forms and
  third-party connections. Pages expose public payloads only and reuse the public
  read quota. They do not fetch an archive, authenticate or import data.
- Native HTTPS and custom-scheme recipe/profile navigation on both clients.
  Strict shared vectors reject credentials, ports, queries, fragments, encoded
  paths, foreign hosts and malformed IDs. A link opens a read-only preview;
  saving still requires the existing selected-import confirmation.
- Named opt-in cookbooks load live on both clients. Anonymous publications remain
  absent from public profile lists. Public profile identity is not cached, so
  privacy opt-out cannot be undone by an old offline profile page.
- Publishing an independently saved pinned copy accepts its genuine historical
  source revision after an upstream update or voluntary unpublish. Removed
  sources receive no copied display identity. Direct server-copy forks still
  require the exact current source revision. Item-level lineage cycles and
  over-deep/broad source graphs are rejected.

Both native archive builders now project selected durable sharing drafts into
publication archives. Selected photo derivatives strip metadata; edited
instructions/methods stay separate from frozen measured stories/results. Stable
actual-attempt aliases survive later publication revisions. Saved public stories
carry their original public-only channel/gap/quality/verdict contract so a later
variation does not reconstruct or invent the original cook. Local outage markers
have unknown duration and are represented as markers, without an invented end.
Native tests export actual publication archives for subsequent server validation;
those new native results are pending in Actions.

Both clients now have device-only publication operation stores and streamed authenticated
write clients. A body and its operation UUID are persisted before transmission, bound
to the initiating account, checked for corruption, and reused after an ambiguous
response. A 401 permits one refresh with the same request. Definite rejected writes
require an explicit review/discard; ambiguous writes cannot be discarded automatically.
No access or refresh credentials enter these stores. Matching native tests cover
response-loss recovery, account switching, corruption, refresh and revision conflicts;
Actions verification for this increment is pending. The stores are not yet wired
to user-facing publishing controls.

Both native sharing draft editors now open a separate publication flow. It shows
selected recipes, ingredients, instructions, methods/actions, photo derivatives,
cover choice, pinned credit and frozen recorded stories before an explicit review
and publish confirmation. Credit can be named or anonymous; sign-in returns to
the task without sending it. The durable draft-to-publication binding supports
revision-pinned updates from that draft, retaining the chosen publication kind
so reopening an actual-cook draft does not silently convert it into a recipe. An authenticated, metadata-only owner
read (`GET /v1/me/recipes/:id`) refreshes the exact base revision before the
update preview; it returns no payload, credentials or account identity. The
preview explains replacement or republication after voluntary removal. My cookbook previews display-name/handle
and visibility changes, lists owned publications and confirms removal. Likes and
unlikes are explicit signed-in operations. Interrupted writes reopen their exact
saved archive or profile/action preview; malformed success responses retain the
request instead of clearing it.

Matching synthetic native UI flows cover preview-before-confirmation, anonymous
publication, a revision-pinned update, cookbook opt-in and confirmed removal. They use memory APIs and never
send mail or call a deployed service. Photo previews decode one bounded thumbnail per recipe and let the user step
through every selected photo, avoiding simultaneous full-size bitmap decoding.
Normal and enlarged text captures, native archive recovery round trips and transport/revision/cookie/redirect bounds are
included in the next Actions increment; full verification and visual review are
pending. Core upload code compiled on both platforms at eeb4e78. Both required Android
lanes pass all 29 core tests, including queue recovery, before the separate
fixed-footer discovery test failure. The actual Android publication archive from
Actions 38799334048 passes the canonical backend validator and is retained as
`native-publication-android-v1.json` with producer/digest metadata. A backend
regression test verifies its selected frozen story, source revision and photo.
iOS Actions 38799333789 passes 92/93 tests, zero skips, including all three
queue tests and the standard-size discovery Save/Cook flow. The enlarged
automation launch reset library metadata but retained fixed-ID photo files;
the DEBUG-only reset now clears its owned attachments too. Complete native
runtime/visual verification remains pending.

Still required in this same draft: finish native CI and visual review, validate
both actual native publication archives through the canonical backend validator,
review update/fork and actual-attempt flows end to end, and support editing an
owned publication when its originating local sharing draft is unavailable. A
saved copy must not be blindly uploaded as an update to itself: that would create
self-lineage and lose the original member/source relationships.

The server remains gated by moderation/deletion readiness and normal clients
keep community/account entry disabled. PR 13 supplies reporting, blocking,
moderation propagation and deletion; PR 14 supplies private sync. Native build
and runtime evidence comes only from Actions. Real installed App/Universal Links,
release certificate association and final-host routing require separate staging
and physical-device evidence.

The simulator test step has a 35-minute bound (75 minutes for the complete job)
to accommodate the expanded two-size publication/update/cookbook UI flows. This
changes CI time limits only; store upload jobs are unchanged.

Fresh native writer exports are collected independently of later UI success,
including failed/cancelled test runs, with the same per-run timestamp checks.
A retained export proves its writer output only; it does not make a failed run green.
