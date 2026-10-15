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

Still required in this same draft: exact selected-content/identity preview, interrupted-write
recovery bound to the same signed-in account, native publish/update/unpublish,
profile editing/opt-in preview, likes, modified variations and actual-attempt
publication, native UI tests and visual review. This foundation does not claim
that those publishing flows already exist.

The server remains gated by moderation/deletion readiness and normal clients
keep community/account entry disabled. PR 13 supplies reporting, blocking,
moderation propagation and deletion; PR 14 supplies private sync. Native build
and runtime evidence comes only from Actions. Real installed App/Universal Links,
release certificate association and final-host routing require separate staging
and physical-device evidence.
