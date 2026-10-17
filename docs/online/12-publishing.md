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
response-loss recovery, account switching, corruption, refresh and revision conflicts.
These stores are wired into the explicit publishing and cookbook controls.

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
At `002d8ac`, both required Android API 36 text sizes pass every suite; the advisory
API 37 retry also passes. iOS compiles in Debug/Release and passes 95/96 tests,
zero skips. The sole failure is a duplicate accessibility match on the confirmation
sheet's unpublish button; the selector now explicitly chooses its first match.
The complete updated native run and visual review remain pending.

Actual Android and iOS publication archives from Actions pass canonical backend
validation and are retained with producer/digest metadata as
`native-publication-android-v1.json` and `native-publication-ios-v1.json`. These
prove selected frozen story/source/photo fidelity, not overall UI success.
Backend Actions at `002d8ac` passes 36 tests with zero skips, real Postgres,
strict typecheck, image validation and runtime audit.

Owners can preview and create an editable private draft without the originating
local draft. `GET /v1/me/recipes/:id/archive` authenticates ownership and requires
an exact revision, streams a bounded checksum-verified archive, and rechecks
ownership/revision/moderation after storage I/O. Voluntarily unpublished originals
remain editable; takedowns and purges do not. The download retains its concurrency
slot until the response closes.

Per-account, device-only editing metadata preserves the original public member,
instruction and asset identities, parent credits and recipe method. It never enters
a library export or public payload. Only a deliberate update applies it; publishing
an independent variation keeps credit to the copied publication and fresh member
IDs. Instructions remain editable while measured stories/verdicts stay frozen.
An actual-attempt update preserves its measured attempt identity; a reference
copy cannot claim a newly recorded cook or insert a synthetic meal into history.
Successful updates advance the context revision; a stale preview must reopen the
latest owner copy rather than silently adopting a newer server revision.

Matching native tests cover context persistence/account isolation, independent
photo ownership, editable instructions versus frozen results, actual-attempt
identity, metadata survival and actual owned-update archive exports. Both UI flows
exercise owner editing after voluntary unpublication and a separately confirmed
republication. Owner download tests cover revision/auth headers, one 401 refresh,
checksum/size limits, rejected-download cleanup and account switches. This owner
increment still requires fresh Actions and visual evidence.

Still open: complete native UI/visual results, backend validation of both native
owned-update exports, full collection/variation/new-actual-cook acceptance flows,
source-update diff presentation, publication-terms acknowledgment and capacity
validation. No complete step-12 claim is made.

The server remains gated by moderation/deletion readiness and normal clients
keep community/account entry disabled. PR 13 supplies reporting, blocking,
moderation propagation and deletion; PR 14 supplies private sync. Native build
and runtime evidence comes only from Actions. Real installed App/Universal Links,
release certificate association and final-host routing require separate staging
and physical-device evidence.

The simulator test step has a 50-minute bound (120 minutes for the complete job)
to accommodate the expanded two-size publication/update/cookbook UI flows. This
changes CI time limits only; store upload jobs are unchanged.

Fresh native writer exports are collected independently of later UI success,
including failed/cancelled test runs, with the same per-run timestamp checks.
A retained export proves its writer output only; it does not make a failed run green.

The next acceptance increment adds the versioned public-copy acknowledgment shown
on both confirmation screens. The exact request stores its accepted version and
sends it separately from the public archive. Migration 004 adds a private
per-user/operation/revision acknowledgment with server time; public responses and
exports never include it. New writes without current consent fail with 428 before
object reservation. Exact already-applied legacy requests still recover their
old receipt without inventing a past acknowledgment. Rejected new requests require
review/discard. Direct server-copy forks enforce the same contract. Migration/role
and native checks are still pending for this increment.

A signed-out, read-only shared-source review is available from saved recipes on
both clients. It shows the saved/shared references and ordered ingredients and
instructions, with explicit dish selection for collections. It compares the
user's saved copy (which may contain edits), never claims to reconstruct the
original source, and never replaces an active meal or saved recipe. Cached
previews are labeled as such; a known 404 evicts the shared preview. A common
fixture checks equivalent ingredient quantities and instruction reordering.
Both synthetic discovery UI flows now open this review, explicitly choose the source dish, and capture normal/enlarged text. Their fresh Actions/visual results remain pending.

The native acceptance tests create a real local new cook from independently saved
public recipes, record its own temperature and fractional verdict, and export a
selected pepper variation, a whole named collection and an actual-cook publication.
They verify that source measurements are not mistaken for the new cook, edited
recipe notes/ratings do not replace frozen results, and publishing leaves the
journal unchanged. Both native jobs retain all three actual outputs for backend
validation. Fractional saved ratings within 1–5 are retained and displayed rather
than rejected, rounded or truncated; the pre-release public contract widens this
number field without changing library or archive versions.

A pre-existing Polaris JVM test raced an Unconfined discovery continuation against
an immediate test Refresh. Its monitor now runs on the test event loop; this
changes test scheduling only, and requires fresh Actions evidence.

Owner archives are rebuilt from the committed revision payload and checksum-verified
owned media, so even an older server-copy fork cannot regain its original source's
lineage by downloading the old blob JSON. New direct forks also store their own
updated publication JSON. Postgres regression cases cover editing both new forks
and an intentionally reproduced legacy fork/blob mismatch.

At `991642b`, Android's required API 36 enlarged-text lane passes all suites,
including 30 core tests, owner context/actual-attempt checks and the separate
owner UI republication flow. Its actual owned-update writer output also passes
canonical backend validation (`native-owned-update-android-v1.json`). The normal
lane stopped in the pre-existing Polaris JVM race before native tests, and the
advisory API 37 emulator crashed. iOS Debug/Release compilation passes and its log reports 88 unit plus 11 UI tests
passing with zero failures. The workflow nevertheless fails: final result-bundle
creation outlasted the 35-minute step and the subsequent upload raced a changing
Staging directory. This is not a green native gate. The next increment allows a
bounded 50-minute test step, records the native console log and uploads only a
finalized, closed xcresult ZIP; a fresh complete run remains required.
Both actual owned-update writers pass canonical backend validation, retained as
`native-owned-update-android-v1.json` and `native-owned-update-ios-v1.json`.

Native iOS Date values can carry fractional milliseconds. Public projection now
rounds relative offsets to the contract's whole-millisecond precision, leaving
private timestamps and measurements untouched. The actual-cook acceptance case
uses explicit fractional source timestamps and checks the projected offset. Both
source-review parsers also bound offsets before formatting, preventing an invalid
remote value from overflowing a duration label. Fresh native verification is open.

Actual Actions exports for a pepper variation, a whole two-recipe collection and
an independently recorded actual cook now pass canonical backend validation on
both platforms. The six `native-{variant,collection,attempt}-{ios,android}-v1.json`
fixtures retain producer commit/run/job/artifact and raw/canonical SHA-256 evidence.
They verify selected membership, pinned credit, distinct new attempt identity,
own manual 75 °C reading and frozen 4.5 verdict, without inventing another dish's
story. iOS producer `0d56a83` passed the complete 101-test native workflow.
Android producer `6c76948` passed its required API 36 large-text job; the overall
workflow failed another lane. These exports do not make either latest PR gate
green. Their comparison exposed an Android free-text ingredient loss through the
numeric-only cook table. The next native run must prove its preservation fix and
provide replacement exports before ingredient parity is closed.

At `83706ab`, both required Android API 36 text-size lanes pass their actual test
suites (31 core, 19 companion, plus the provider/recording/instrumentation suites).
The workflow still fails because a newly added failure-only diagnostic image was
incorrectly required even on success. That collection mistake is corrected;
the script keeps collecting a failure image when one actually exists. Screenshots
now wait for native UI idleness, and source comparison captures the selected
ingredient row rather than a preceding frame. Fresh complete workflow/visual
verification is still required.

Replacement Android variation/collection/attempt exports from required large-text
job 116930170028 (run 38887941305, artifact 12041531267) validate their pinned
credit, own measurements/verdict and inherited “To taste” quantity. Both platforms'
retained exports now assert that quantity in backend regression tests. The producer
job's test suites passed, but its diagnostic collection failed; it is not described
as a green workflow. Android enlarged-text owner update preview has been inspected;
its terms, confirmation and text reflow are readable without horizontal clipping.

October 16 follow-up: iOS run 264 passed 100/101; its selective-import test
failed when a switch tap left the value unchanged. The test now targets the
contained control and verifies its state before continuing. Public detail now
presents its hero and actions together to prevent a late photo shifting a tap
target. Compact CI diagnostics now include the text log, since a failed run's
full xcresult archive can exceed the connector's 512 MiB download limit. Fresh
native results are required; these changes do not close the acceptance gate.

Full xcresult bundles now upload separately from screenshots/logs. This keeps
visual evidence downloadable when a failed result bundle exceeds 512 MiB.
