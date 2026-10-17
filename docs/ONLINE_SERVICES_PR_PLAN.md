# PitTech — Complete Online Services & UI Implementation Plan

Prepared October 13, 2026 (America/New_York). Planning baseline: `82cbe1b335499aba561fc58c91d727055b8ce4fc` on `michaelhmiv/PitTech`.

**Status:** Implementation authorized October 14, 2026. The owner approved proceeding through the complete plan and delegated the deletion amendment. See `docs/online/STATUS.md` for actual implementation and verification progress. This remains a post-1.0 initiative and must not delay the existing store launch.

**Authority:** [Issue #89](https://github.com/michaelhmiv/PitTech/issues/89), the owner's subsequent decisions in this conversation, and [AGENTS.md](https://github.com/michaelhmiv/PitTech/blob/82cbe1b335499aba561fc58c91d727055b8ce4fc/AGENTS.md). Conversation decisions extend the issue wherever noted below.

## 1. Decisions and scope

| Decision | Required behavior |
|---|---|
| Coherent refresh | Refresh existing Meals, Recipes, Insights, and surrounding flows alongside the new screens. Preserve the warm paper, charcoal, sage, and ember identity; use one shared visual hierarchy on both platforms. |
| Main navigation | Meals, Recipes, Discover, Insights, More. More contains account, devices, settings, data, and support. Live retains direct equipment/connection access. |
| Account optional | Starting, logging, editing, exporting, sharing by file/code, browsing public recipes, and saving/cooking downloaded recipes work without a PitTech account. Previously free capabilities remain free. |
| Free account | Private library sync, publishing, likes, profile controls, and publication management. Private sync never publishes anything. |
| Public content | Individual dish recipes, meal collections containing selected dishes, and real cooking attempts linked to a recipe/version. No comments, DMs, follow feed, or automatic public upload. |
| Flexibility | Save copies, change ingredients and guidance, add/remove dishes from a composition, and publish a selected subset. A publishing draft is separate from the logged meal. |
| Guidance | Instructions plus a recorded cook story. One Show guide toggle controls reference presentation across Live, Timeline, and Charts for the current meal. This is required, including reference temperature curves. |
| Attribution | Saved copies retain source/version credit. Publishing a modified recipe creates a visible fork; publishing an actual attempt creates an I cooked this relationship. No automatic publication when saving or copying. |
| Anonymous publishing | Identity is selected per publication. Anonymous items appear in discovery but stay off the author's public profile and do not expose attribution links to that identity. |
| Verdicts | Optional per-dish rating and cook-again choice; optional meal summary. Users can finish without rating anything and edit results later. |
| PitTech Pro | Target US price $3/month, monthly only, using Apple IAP and Google Play Billing. Ad removal, deeper personal analysis, customization, and eligible community aggregates. Personal analysis leads the experience. |
| Infrastructure | Railway-hosted API/Postgres/storage; Resend delivers magic links and six-digit fallback codes. The owner reports purchasing `pittech.app` through Railway. DNS, TLS, Resend verification, and mail delivery still require implementation-stage checks. |
| Platforms | Every feature's definition of done includes Android and iOS. OS differences must be recorded and justified. |

### Corrections to the original issue

1. Android and iOS store builds now expose Home Assistant, Combustion, FireBoard, Flame Boss, and MEATER Beta connections. The earlier manual-only iOS baseline is stale. Preserve the current [connection parity matrix](https://github.com/michaelhmiv/PitTech/blob/82cbe1b335499aba561fc58c91d727055b8ce4fc/docs/CONNECTION_PLATFORM_PARITY.md).
2. The reference curve moves from stretch scope to required scope because the owner explicitly requested one toggle that reveals the reference throughout the cook.
3. A fork/download starts as a private local copy. The original proposed fork endpoint must not immediately create a publicly visible recipe.
4. Public measured cook snapshots are distinct from editable recipe instructions. Aggregates cannot be computed from edited instructions or repeated copies/revisions of the same cook.
5. Reporting, filtering/moderation, user blocking, and contact information ship with public recipes/photos. They cannot wait for comments.
6. Account deletion requires a product amendment: Apple's published guidance includes authored UGC in account deletion. **Approved release behavior (October 14):** delete the account's original public content and private data, retain only minimal non-identifying lineage placeholders, and preserve other users' independent copies/forks. This supersedes the issue’s anonymize-by-default behavior. The owner authorized the amendment; deletion code remains part of PR 13.
7. Reassess store privacy/data-safety declarations at every phase. First-party, user-initiated relay uploads are not automatically exempt from disclosure.
8. Discovery defaults to useful liked/forked content. The issue's `newest` query example does not override its explicit rejection of recency-driven discovery. No newest-first public shelf in v1.

## 2. Baseline and delivery constraints

Current Android Room schema is 10; the shared portable archive is version 9. These are different version namespaces. Reserve portable archive v10 for the new local models; determine the actual next Room migration from the implementation branch. Never reuse a schema number consumed by intervening work.

Both apps already have recipes, immutable revisions, dish provenance, meal presets, unified reminders, and explicit physical phases. Extend those systems. Existing archive import adds missing identities/skips duplicate cooks and rejects certain revision conflicts; it is **not** the sync merge engine assumed by the issue.

Open launch PRs at planning time: #77 App Store readiness, #78 screenshot tour, and #88 iOS AdMob configuration. Resolve their final baseline through the separate launch work before this initiative starts. Rebase the plan's source assumptions after those land; do not bundle or silently merge them here.

GitHub Actions is the build authority. Android API 36 lanes at font scales 1.15 and 1.5 are required; the existing API 37 lane is advisory. iOS builds/tests/screenshots and signing remain CI-side, using `ios/project.yml`. Workflow updates need a workflow-capable write path: the current GitHub App connection cannot write `.github/workflows/*`. Prepare the required workflow patch in PR 00 and arrange a permitted application path before backend feature PRs depend on it. Never bypass the restriction or declare a feature tested without its workflow evidence.

All equipment integration remains read-only. No guide, reminder, template, account, or Pro feature may ignite, stop, configure, or change a grill. Existing monitoring cadence, probe attribution, restart recovery, and supported provider history must survive this work.

## 3. Shared UX specification

### 3.1 Visual system

Create a documented cross-platform token map for semantic colors, text hierarchy, spacing, shapes, controls, charts, and states. Start from Android's existing warm canvas/ember/sage palette and give iOS equivalent semantic surfaces instead of unrelated generic grouping. Retain native navigation, system sharing, purchases, text scaling, and accessibility behaviors.

- Spacing scale: 4/8/12/16/24/32 logical units. Screen gutters start at 20 on phones and grow appropriately on larger layouts.
- Typography: a restrained page title, distinct section heading, readable body, supporting metadata, and monospaced temperatures/times. Avoid multiple oversized headings before meaningful content.
- Primary actions: one obvious action per task. Secondary actions use quieter controls or a contextual menu. Icons receive visible labels where their meaning is uncertain.
- Android actions meet 48 dp targets; iOS actions meet 44 pt targets. Text and controls must reflow at the required enlarged sizes. Color is never the only distinction between current/reference data or status.
- Photos have consistent crops and an intentional missing-photo treatment. Food cards do not become checklists covered in action buttons. Avoid prominent empty placeholders for data the user never recorded.
- Dark mode uses purpose-designed surfaces and contrast. Light mode remains readable outdoors. No decorative glow, neon, or gratuitous animation.
- Loading states preserve the eventual content layout; retry errors appear close to the failed task; offline content keeps its normal hierarchy with a small status explanation.

### 3.2 Screen layouts

| Screen | Content order and interaction |
|---|---|
| Meals | Active meal first, with dishes, current useful readings/stages, and Resume. Start meal remains obvious. Completed meals follow with selected photo, dishes, date, meaningful duration/results. Planning stays secondary. No account pitch before cooking. |
| Live | Compact meal status; food cards grouped by Preparing/Heating/Resting-Holding/Finished; current readings and latest meaningful action; optional source/reference context when Show guide is on. A stable quick-log strip keeps Log, Temp, Photo, Reminder accessible. Connection access is contextual and direct. |
| Timeline | Existing story/content and food filters; actual activity remains readable. With Show guide on, show reference milestones alongside current activity, aligned to each dish's Food on anchor. Compact phones use full-width paired reference/current blocks; wider screens can use two lanes. Preserve unmatched events and empty lanes without inventing matches. |
| Charts | Current curves first; reference curves appear dashed/labeled when Show guide is on. Keep readable axes, legend, probe selection, inspection, zoom, and visible gaps. Source and current channels are matched by role/scope, never just equal probe names. |
| Recipes | Recipe content first: photo or useful fallback, name, food/cut, known weight/duration, and concise source credit. Search stays visible; detailed filters move behind a filter action with selected chips. Selection mode reveals a meal-composition tray. |
| Recipe detail | Hero; name and attribution; recorded facts/known duration; Save recipe and Cook this; Ingredients, Instructions, Cook story, Results. Use sections/tabs only where they simplify the content. Charts and milestones are context, not an instrumentation dashboard occupying the whole first screen. |
| Meal collection | Collection hero/title, dish list with individual save choices, shared equipment/context where explicitly included, and Cook selected dishes. Missing or removed children have an honest state. The user can retain only the pork and omit potatoes. |
| Discover | Search, a compact food/cut filter row, and photo-led recipe cards with practical facts. Additional weight/method/time filters use a sheet. Collection and cooked-attempt content are visibly identified; default shelves emphasize recipes. Likes/forks are secondary signals. No feed, mandatory login overlay, or dense graph wall. |
| Share | One entry point offers Share card, Cook Pack file, or Share by code. Show a preview before export/upload. Choose meal/dishes, relevant media, and included story. Publishing is a separately labeled public action. |
| Publish editor | Persistent draft; selected dishes; title/hero; editable instructions; chosen cook story/photos; identity; exact public preview. Disclosure of what is shared sits beside that choice. Save draft and Publish are clear. Validation preserves work and focuses the relevant field. |
| Profile | Public profiles are opt-in cookbooks with a modest identity header and named published recipes/collections/attempts. Anonymous publications stay out. Pro identity styling is restrained. Private profile management has its own view. |
| Insights | Useful personal comparisons/history first. Advanced views progressively reveal filters, curve alignment, and explanations. Community benchmarks appear when eligible. Small-sample states explain what is missing. Avoid a first screen dominated by database record counts. |
| More | Account/sync status, Devices, Appearance/units, Notifications, Import/export, Privacy/support. Signed-out status offers optional sign-in without replacing access to local settings. |

### 3.3 Show guide behavior

Use one durable presentation state per meal shared by Live, Timeline, Charts, and relevant detail components. Each dish may reference a different immutable source recipe revision and cook snapshot. A meal collection is composition, not a single assumed common timing anchor.

Show guide defaults off unless the user explicitly chooses a start-with-guide option. Toggling it changes presentation immediately without altering data, starting polling, enabling reminders, or recording physical actions. If the user hides guidance while reminders are enabled, show a compact Reminders active indication with access to those controls; hiding reference content does not quietly disable notifications.

Reference wording is factual: Author wrapped at +3h20m. User-edited instructions may instead say Check bark around +3h. Keep those distinct. Align elapsed reference time to the relevant current dish's actual Food on; retain negative preparation offsets. Do not scale timelines linearly by weight or claim a finish prediction. Mark differing cuts, weights, or methods when relevant. Missing source measurements yield milestones/instructions without a fabricated curve.

Reference data never becomes current telemetry, current notes, completed guidance actions, or fired reminders. Reminders are selected explicitly, copied/pinned to the current attempt, and edited independently. Later upstream edits never rewrite an in-progress cook's plan.

### 3.4 Copy, fork, cook, publish

- Save recipe downloads an independently owned, editable local copy with pinned provenance; no account or publication is required.
- Cook this creates new meal/dish/reading/event identities and editable attempt setup. It can create a new meal or add selected dishes to an active one.
- Duplicate creates another independent local variation. Removing potatoes from a sharing/composition draft does not edit the original logged meal.
- Publish variation intentionally creates a public derivative with visible source credit. Reuse as a starting template retains provenance while letting the user clear optional reference guidance.
- Publish my cook shares selected measured results for an actual new attempt linked to the source version; it does not automatically claim a new recipe.
- Update publication creates a new immutable public revision. Downloaded copies remain pinned; upstream updates are offered for explicit review, never silently applied.

## 4. Shared contracts and privacy boundaries

| Contract/entity | Required fields and invariant |
|---|---|
| Dish verdict | Nullable rating 1–5, nullable cookAgain, notes, dish identity, update metadata. Missing is distinct from a negative verdict. Reuse existing result storage where compatible; avoid a second contradictory rating system. |
| Recipe | Existing one-dish editable snapshot and immutable revisions; separate editable instructions from recorded observations; recipe-owned photo files survive source deletion. |
| Meal collection | Versioned ordered references to selected recipe revisions plus explicitly included shared setup/story. Maximum composition size follows the existing supported meal limit. Individual dish recipes remain independently saveable. |
| Cook story | Frozen, scoped actual attempt: food/cut/weight/units, physical actions/times, relevant pit/food samples, gaps/quality, recorded results, selected notes/photos, and source version. Stable attempt identity survives recipe revisions. |
| Provenance | Local copy identity, source public item/revision, optional fork parent/revision, source attempt, and credited display identity. Public payloads omit account IDs, emails, device IDs, credentials, and internal paths. |
| Sharing draft | Selected scope/media/story, editable presentation/instructions, identity choice, staged asset ownership, and source pin. Durable and separate from meal history. |
| Cook Pack | `.pittech` ZIP with existing manifest and `data/pittech.json`, explicit scope, shared version, attachments, and optional provenance/reference/collection metadata. Both writers/readers use the same definition. |
| Sync envelope | Independent versioned transport with archive payload, per-record mutation metadata/tombstones, encrypted asset references, base server version, client identity, and idempotency identity. It is not ordinary archive import. |
| Entitlement | Verified platform subscription ownership/status/expiry/grace/environment, stable account binding, and versioned cached result. Client flags never grant server-paid features. |
| Public publication | Recipe, collection, or cook-attempt kind; status/revision; per-item named/anonymous mode; owner-only write authorization. Public profile membership is independent. |

Four distinct storage/access paths are mandatory: local library; private authenticated sync; anonymous bearer-code relay; deliberate public publications. Public routes and aggregate jobs never have a code path that reads private sync libraries. Public export is an allowlist transformation, not a full backup upload followed by hidden fields.

Public/relay photo derivatives strip EXIF/GPS, thumbnails, and other location metadata, preserve orientation, and validate actual image type. Private local originals remain intact. All remaining notes, captions, timeline context, and probe labels are visible in the user's sharing preview. Equipment/provider credentials never enter packs, sync archives, public JSON, fixtures, or logs.

Use canonical public IDs for stable URLs. Named profile-friendly aliases may redirect to the canonical item. Anonymous item URLs and fork credit must not contain the author's handle or reveal account identity through source references. Blocking uses an opaque public publisher alias, with server-side identity resolution, and does not disclose the underlying account.

## 5. PR sequence and release boundaries

There are **20 implementation PRs (00–19)**. A PR may merge with its unfinished online surface unavailable, provided the existing app remains sound and CI/visual gates pass. A public phase launches only when both clients and the entire phase's release gate are complete. Existing connections stay available.

| Wave | PRs | Public release gate |
|---|---|---|
| Design/local foundations (Phase 0) | 00–04 | Shared design, refreshed local workflows, verdicts, portable packs, and optional reference guidance on both platforms. |
| Sharing (Phase 1) | 05–07 | Native cards/files and anonymous relay with working app/web import links. |
| Accounts/community (Phase 2) | 08–14 | Auth, profiles, discovery, intentional publishing/forks/attempts/collections, moderation/deletion, and private sync together. Social implementation precedes sync, but sync remains in this release. |
| Pro (Phase 3) | 15–19 | Preserved free Insights, verified billing/ad removal, deeper personal analysis/customization, eligible aggregate data, and final store validation. Launch follows Phase 2; aggregate density does not hold Pro launch. |

The default integration order is numerical. Backend/client branches can be developed against the frozen contracts, but dependent PRs must revalidate after their prerequisites merge. No calendar estimate is asserted before the detailed parity audit and first design review.

## 6. Detailed PR specifications

### PR 00 — Freeze the experience, parity matrix, contracts, and CI requirements

**Depends on:** final post-launch baseline. **User impact:** establishes reviewable designs and protects existing functionality before feature coding.

Deliver `docs/ONLINE_SERVICES_SPEC.md`, `docs/ONLINE_SERVICES_PR_PLAN.md`, `docs/PLATFORM_PARITY.md`, a shared token/component specification, API/pack/sync schemas, and named synthetic fixtures. Include the account-deletion amendment as a decision checkpoint. Reconcile older specs that describe write controls or manual-only iOS; the latest read-only policy and connection matrix govern.

Produce high-fidelity phone mockups for Meals, recipe library/detail, Discover, meal collection, publish preview, Live with/without guide, paired Timeline, reference Charts, Insights, and account/sign-in. Show matching Android/iOS task hierarchy, light/dark, large text, and at least one tablet layout. Use representative pork/steaks/potatoes and sparse-data sessions. The implementation authorization delegates routine design choices; inspect concrete designs against the agreed direction before dependent UI work starts.

The parity matrix names storage/exports, editing, draft/photo recovery, cooking phases, recipes, timers/reminders, monitoring/history, free Insights, support/feedback, and store availability. Every gap has an owner, target PR, test, and port/drop/platform decision. Preserve the existing connection matrix. The Combustion iOS log-recovery gap remains an explicitly tracked port investigation until a documented SDK route or owner disposition resolves it; do not promise recovered data that the pinned SDK cannot supply.

Prepare workflow changes for backend Postgres/API tests, contract fixtures, screenshots, and signed-artifact checks. Establish the permitted workflow write path before those changes are needed. No replacement of existing CI/signing scripts with a local build assumption.

**Acceptance:** mockups cover real task states; every observed gap has a disposition; schema reviewers can distinguish local/private/relay/public data; release gates and unresolved deletion amendment are explicit. Documentation PR verification does not pretend to be an app build.

### PR 01 — Shared navigation, design components, and existing core UX parity

**Depends on:** 00. **Primary areas:** Android `CookScreens.kt`, `PitTechTheme.kt`, food/timeline components, `InsightsScreen.kt`, `SettingsScreen.kt`; iOS `ContentView.swift`, `PitTechStyle.swift`, dish/detail views; new shared-pattern components in each native UI.

Implement Meals/Recipes/Discover/Insights/More with stable navigation state and correct back behavior. Before community APIs are available, Discover has an honest unavailable/coming-in-this-wave state, not fake recipes. Move Devices/settings under More and keep connection access one tap away within a meal. All five currently enabled providers stay enabled in Release.

Refresh active meal, food cards, history, preparation/edit forms, timeline filtering, quick-log controls, and existing free Insights to the agreed layouts. Port missing everyday iOS equivalents instead of creating attractive new screens around an unfinished local app. Provide the agreed support/feedback flow on both platforms with explicit user-directed submission and redacted diagnostics; keep development controller interrogation out of public artifacts.

Use scoped components rather than adding every new feature into the already large Android detail screen. The fixed logging controls must not cover content or conflict with the keyboard/system safe area. Restore state after navigation, process death/relaunch, or notification entry.

**Acceptance on Android and iOS:** start an empty meal; resume a three-dish cook; log a combined note/photo/temp; filter activity; reach a connected provider; review free history/Insights; navigate back without lost state. Existing active cooking, monitoring, and update recovery regressions pass. Review native screenshots at all required text sizes and themes.

### PR 02 — Per-dish verdicts, flexible composition, and durable sharing drafts

**Depends on:** 00–01. **Areas:** result entities/repositories, existing recipe revisions/presets, native result/edit views and draft storage. **Migration:** shared archive v10 with v1–v9 defaults. Implementation reuses existing result records and independent companion documents/owned photos, so Room stays v10; no table/column migration is necessary.

Unify existing rating/result storage with optional overall dish rating and nullable cookAgain. Completion remains a short confirmation followed by a voluntary result/save/share summary. Do not put a mandatory review questionnaire in front of Finish. Add equivalent result editing on iOS; avoid overwriting recorded older ratings with empty new fields.

Add local collection revisions and draft composition over existing one-dish recipes. Recipes/dishes can be added, reordered, duplicated, customized, or omitted independently. A sharing draft has its own identity and staged attachments; changing it cannot mutate source meal events, recipe revisions, or monitoring bindings. Saving a recipe copy generates new identities while preserving the pinned source.

Persist unfinished sharing edits; offer resume/discard after relaunch. Source deletion and failed asset copies produce usable recovery states. Recorded story remains a separately owned snapshot; editing instructions does not claim that an altered planned wrap time was actually measured.

**Acceptance on both clients:** finish pork+potatoes without ratings; later rate each differently; create a pork-only sharing draft; change its seasoning/guide; retain the original two-dish meal unchanged; duplicate and edit recipes independently; relaunch mid-draft. Real migration tests preserve older meals/results/media.

### PR 03 — Portable Cook Packs, scoped import/export, and archive parity

**Depends on:** 02. **Areas:** Android `PitTechDataTransfer.kt`, recipe/reminder archive modules; iOS `CookStore.swift`, archive/validation modules; native document/share integration.

Extend the existing archive builder with one-dish recipe, selected-dish meal collection, and scoped cook-story exports. Recipe-only packs preserve independently owned photos and source/version metadata without dragging in unrelated whole-library records. Full backups preserve original media; public/relay packs use stripped, bounded photo derivatives. Credential and device-identifier allowlists are tested explicitly.

Validate completely before mutation. Preview what the pack contains and allow Save recipes/guide or Add recorded cook to history. Save can select one or several dishes. Downloaded source facts remain reference data when a new cook starts. Re-import is idempotent; import failure is atomic; restored reminders/recording remain inactive.

**Resolve the verified size-limit disparity:** Android currently permits 350 MiB compressed, 300 MiB expanded, 45 MiB/entry, and 10,000 entries; iOS currently permits 64 MiB compressed/expanded, 16 MiB/entry, and 1,000 entries. Preserve Android's supported full-backup range and port bounded/streamed iOS archive handling toward the same contract, with memory/performance evidence. For network Cook Packs, use a separately declared initial 64 MiB compressed/expanded, 16 MiB/entry, 1,000-entry cap supported by both clients. Tell the user when their selected pack exceeds this scope; never silently omit raw measurements. A lower relay quota does not change full-backup import support.

Close the existing CSV/workbook export gap through the parity matrix: provide the supported Android export tables/workbook semantics on iOS, using standard formats and bounded writing. Validate numeric cells, explicit units/timezones, stable relationships, escaping, and photo references. Scope-specific limits are explicit.

**Acceptance:** actual Android→iOS and iOS→Android fixtures for recipe, collection, cook, and legacy/full archive; source-photo ownership; Celsius values; multiple same-name probes; malicious paths/duplicate entries/ZIP bombs; limit boundaries; atomic retry; inactive automation. Validate workbook output using an independent reader in CI.

### PR 04 — One Show guide toggle across Live, Timeline, and Charts

**Depends on:** 02–03. **Areas:** guide/reference state, physical phase engine, chart/channel mapping, timeline presentation, unified reminder integration on both platforms.

Attach pinned reference story/instructions to each selected dish. Implement the single meal-level toggle and independently selectable source scope. On phones, paired full-width milestone blocks show Your cook and Author; use two lanes only at widths where text remains readable. Actual and reference entries use separate models and renderers. Match action/scope semantically and display unmatched events honestly.

Charts overlay mapped source channels with dashed style and labeled legend; preserve manual points, real gaps, quality, original precision, and independent pit/food roles. Anchor to each dish's Food on; no weight-based retiming, interpolation through outages, or mixing one food's probe into another. Inspecting reference/current data reveals which source the value belongs to.

Reference reminders remain opt-in and use the existing trigger/scheduler machinery. Pin instructions and timing into the attempt when enabled; edits, snooze, skip, and actual-action confirmation keep their current semantics. Guide visibility does not start monitoring or alter reminder activation.

**Acceptance on both clients:** toggle from any section and see consistent changes everywhere; relaunch with the same selection; mix three different source recipes; start a dish later; change probe mapping; view sparse/no-temperature sources; correct Food on; hide guidance with reminders active. Assert reference events never enter the actual log or satisfy reminder conditions.

### PR 05 — Native share-card renderer and preview/export experience

**Depends on:** 02–04. **Areas:** SwiftUI image rendering; Compose/native bitmap rendering; shared card content selection; export/sharing UI.

Build the 1080×1350 card using a fixed layout skeleton with data-dependent slots: chosen photo or real chart hero, selected dish chips, at most five meaningful milestones, measured stats, optional chosen note/verdict, and restrained PitTech footer. Support recipe and meal-collection scope. Native renderer outputs are based on one contract/fixture set, although native rasterization need not be pixel-identical.

Preview before sharing. Crop/hero selection is explicit; private notes/photos are never unexpectedly chosen. Missing weight, duration, verdict, or readings collapse their slots gracefully. Long titles/notes are shortened only in the image, with the full data retained in the pack. Show code/link only once relay upload succeeds. The image remains useful without a code.

System sharing/export can be canceled without losing a draft or leaving orphaned temporary files. A renderer error has a retry/file-share path. Source originals are untouched. Pro theme slots exist in the contract, but all required default sharing remains free.

**Acceptance on Android and iOS:** photo/graph/no-photo/no-reading cards, one/many dishes, long text, non-ASCII content, multiple units, code absent/present, canceled share, render failure. Inspect actual exported pixels at final size; do not approve solely from the in-app preview.

### PR 06 — Railway API foundation and anonymous relay service

**Depends on:** 00, 03, working backend CI. **Areas:** new `api-service/` Node/TypeScript service, Postgres migrations, storage adapter, relay validation/tests, deployment documentation. Existing privacy/feedback services are preserved.

Use the repository's Node 22 operational baseline with locked dependencies. One modular service may host relay/account/public routes, but storage and authorization boundaries remain explicit. Provision isolated staging and production configuration, Postgres, and private object storage; no production credentials/data in CI. Persist pack/photo objects behind a storage interface so a volume or bucket choice does not leak into the clients. Prefer a bucket for independent object ownership/deletion and document actual pricing at deployment.

Implement upload/download, expiration, renewal, and revocation. Validate manifest, supported version, scope, relations, image signatures, bounded decompression, paths, and checksums before acceptance; the server does not fetch arbitrary user-supplied URLs. Apply the declared network pack limits and configurable per-IP/global quotas. Initial limits are 20 uploads/IP/day and 120 downloads/IP/hour, with global storage/upload budgets established from staging measurements and clearly recoverable limit errors.

Share codes use a CSPRNG with at least 64 bits of actual entropy. A six-word code drawn uniformly from a fixed 2,048-word list provides 66 bits; mockups may choose an equivalent accessible grouped format. The short two-word/one-digit example in the issue is not a security claim or final format. Case/paste/separator normalization, collision retries, rate limits, QR/copy support, and enumeration tests are required. Owner management uses a separate >=128-bit secret stored hashed server-side; a download code grants access but not renewal/revocation authority.

Expire after 180 days; only authenticated owner-management renewal extends life. Reads never renew. Sweep expired objects, partial uploads, and revoked packs. No anonymous listing endpoint or publicly enumerable bucket. Public logs redact codes, tokens, email, and pack content.

**Acceptance:** shared Android/iOS golden packs accepted; forged/malformed/over-limit uploads rejected; interrupted uploads cleaned; expiry/renew/revoke ownership tested; collision/rate-limit behavior verified; restart preserves storage. Node/Postgres/contract tests pass in Actions.

### PR 07 — Relay clients, shared links, and mobile web landing pages

**Depends on:** 05–06. **Areas:** both client sharing/import coordinators; Android verified App Links, iOS Universal Links; server-rendered `pittech.app` landing routes and association files.

Add Share by code upload progress, retry/cancel, code/QR/copy/link display, expiration information, and owner revoke/renew controls. Preview the exact content before upload. An expired/revoked/unavailable code has an explanatory recovery state; failed upload does not put a dead code onto a card.

Provide signed-out code entry and link import using PR 03's validation/preview. The landing page has a concise public preview where permitted, Open in PitTech, store links, and an honest manual-code fallback after installation. Do not promise universal deferred deep linking after a store install. No automatic import, account creation, or exposure of the whole pack on the page.

Serve association files from the final HTTPS host with correct content type and release bundle/package identifiers. Validate Android's production Play signing certificate separately from upload/debug certificates; cover Firebase/TestFlight variants as appropriate. Installed app, uninstalled app, desktop email/browser, malformed/expired links, and return-from-background cases all have defined behavior.

**Acceptance on both platforms:** someone sees only a card image, types its code, selects pork from a shared meal, saves it, and starts a new local cook without an account. Test genuine release link associations, not merely custom-scheme emulator links. Phase 1 cannot release before both clients and fallback pages work.

### PR 08 — Resend migration and passwordless authentication backend

**Depends on:** 06–07. **Areas:** auth challenges/sessions, mail templates/adapter, account lifecycle, secure configuration, domain setup, delivery webhook handling.

Configure the purchased `pittech.app` domain and HTTPS hosts; verify DNS ownership and certificates before treating them as ready. Recommended email identity is `PitTech <hello@auth.pittech.app>` with a real monitored Reply-To. Use separate production/staging sending keys restricted to the appropriate verified domain, separate secrets, and separate callback origins. Templates have HTML/plain-text versions, a clear sign-in button, readable copyable code, and 15-minute expiration. Click/open tracking is disabled. Resend delivers the message; the backend owns authentication and token verification.

Create one challenge with a high-entropy link token and a six-digit code. Both are single-use views of the same challenge, expire together, and invalidate together. Store hashes, cap wrong guesses (initially five/challenge), throttle email/IP requests, apply a 60-second resend cooldown, and invalidate superseded challenges. Responses do not reveal whether an account exists. Access/refresh sessions are short-lived/rotating; replay of a used refresh token revokes its token family. No passwords or manufacturer credentials.

GET of an email link must not consume the credential: security scanners/prefetches must not log users in or burn the token. Exchange through an explicit app/user action and an allowlisted return destination. Do not expose access/refresh tokens in web URLs or logs. Different-device email opening has a first-class code fallback. Keychain/Keystore session storage is supplied by PR 09.

Receive signed delivery/bounce/complaint events, deduplicate webhook event IDs, and redact payloads in logs. Do not automatically import sign-in users into marketing contacts. Respect suppression state and make send failures/retries intelligible without revealing account existence.

The corrected connected Resend account contains `sportfolio.market`, Sportfolio Production Magic Links, Sportfolio Beta Magic Links, and matching production/beta webhooks. The owner's requested retirement is confined to that Sportfolio configuration. Stage/verify PitTech delivery and callbacks first, then revoke old application keys and remove/disable old webhooks; remove the old sending domain only as part of confirmed retirement. Munch's separate account/domain is outside scope. Provider-required explicit confirmations are handled against the concrete named retirement list, not a vague account wipe. Migration does not send announcements or unsolicited messages.

**Acceptance:** link/code race permits one successful exchange; expiration, brute-force throttling, scanner GET, refresh replay, cross-device login, canceled login, email delivery failure, webhook signature/replay, staging/prod separation, and secret redaction tested in CI. Real authorized test delivery and DNS checks are separate operational evidence; unit tests do not prove inbox placement.

### PR 09 — Optional native accounts and profile/sync settings

**Depends on:** 08, 01. **Areas:** both clients' auth/session coordinators, account screens, Keychain/Keystore storage, per-item identity controls and profile API.

Implement Continue with email, link/code entry, resend/countdown, expired challenge, recovery, sign-out, and device session controls. Sign-in appears only when a user invokes an account-required action and returns to that exact draft/task afterward. Cancel returns to usable local functionality. Login never enables a public profile or publishes content.

Profiles start private. Handle/display-name/photo editing is optional; named publication credit can exist without a public profile page. Explicit public-profile opt-in shows an exact preview. Reserved/profanity checks apply to claimed handles. Email never becomes a display name. Prepare per-publication named/anonymous selection independently of the profile toggle.

Private sync is offered with a short data/merge explanation and explicit enablement. Until PR 14 is ready, it must not claim to have backed up data. Sign-out leaves local meals/recipes accessible, cancels authenticated work, and separates the prior account's cloud association so a new login cannot silently upload another account's private library. Explicitly preview any later attach/merge decision.

Account deletion is easy to find. At this stage deletion covers account/session/private data; the final public-content semantics and subscribed-account flow land in PR 13/16 before Phase 2/3 release.

**Acceptance on Android and iOS:** full local flow without login, canceled publish/like sign-in, challenge from another device, reauthentication, session persistence/relaunch, sign-out while cooking, switch accounts, private/public profile toggles, and no involuntary sync/publication. Enlarged-text forms and keyboard transitions receive native visual review.

### PR 10 — Publishing, collections, attempts, forks, likes, and public API

**Depends on:** 03, 08–09. **Areas:** versioned REST API, ownership/revision relations, public asset generation, public-safe payload schemas, pagination and public web pages.

Model public recipes/revisions, collection revisions/member references, and measured cook attempts separately over a common publication/status/identity abstraction. Add public asset ownership, provenance, likes, opaque publisher aliases, report/block foundations, and minimal lineage placeholders. Writes are authenticated and owner-only; public reads are accountless and never use sync storage.

Saving a copy does not create a public row. A public fork exists only after explicit publication and points to the exact source revision. Public fork counts count deliberate public variations, not anonymous downloads, private saves, recipe revision updates, or fabricated cooked attempts. Publishing a real cook links a unique attempt to the recipe version used; edits/revisions keep that attempt identity for aggregation deduplication.

Collection-only child snapshots are public within their collection, but are not independently inserted into discovery unless deliberately published as standalone recipes. Existing public recipes can be pinned as members with proper attribution. Removing/unpublishing a collection must not leave its collection-only content unexpectedly public. No unrelated dish data leaks through graph traversal.

Use immutable revisions, ETags/expected revisions, idempotency keys, bounded pagination, consistent canonical URLs, escaped public text, and author-authorized asset writes. Server validation owns source/provenance claims; unverified imported files cannot impersonate a known publisher. Revision histories never disclose private prior drafts, anonymous owner identity, internal cook IDs, or tokens. Source chains are cycle-free and bounded.

Publishing creates a pending/active moderation state consistent with PR 13. Inaccessible/taken-down/deleted originals return appropriate unavailable states and remove public media access. Independent user-owned forks survive an upstream voluntary unpublish; known violating assets cannot remain public just because another item references them.

**Acceptance:** two accounts publish and revise; a third signed-out reader downloads; non-owners cannot mutate; duplicate likes/writes are idempotent; collections pin correct revisions; source deletion and anonymous/public-profile isolation work; no public endpoint reads private sync; all contracts accept both native client fixtures.

### PR 11 — Discover, public details, and accountless save/cook

**Depends on:** 01, 03–04, 10. **Areas:** native discovery/data cache, recipe/collection/cook detail views, search/filter state, downloaded asset ownership and local recipe composition.

Build photo-led discovery with cut/food, optional weight/method/time filters, and liked/forked ranking. Use stable pagination and deterministic secondary ordering rather than upload-recency promotion. Public cook attempts are reachable from the recipe's cooked-results section; do not flood the main recipe shelf with every attempt or all collection children.

Save recipe, Save selected dishes, Cook this, and Add to active meal stay available signed-out. Downloading is explicit, cancellable, validates the complete bundle, and commits atomically. Saved content includes the pinned instructions and source story needed for offline guidance; images are independently owned. Favorites remain local and distinct from public likes.

An unavailable network shows saved/cache content with retry, not an account demand. A removed source leaves independently saved recipes usable while making source/update availability clear. An author revision shows Update available; review/accept creates a local revision without overwriting user customizations or active attempt setup. Current local edits survive failed requests and navigation.

**Acceptance on both clients:** browse signed-out; find a pork recipe; save/cook offline; retain pork while omitting potatoes; mix recipes from three authors; add to an active meal; cancel/retry interrupted download; handle stale pagination and source removal; review an upstream update with local changes. Native visual checks cover realistic cards, sparse content, long names, loading, offline, and zero results.

### PR 12 — Intentional publish editor, forks, attempts, and public cookbooks

**Depends on:** 02, 09–11. **Areas:** both clients' sharing drafts, public preview, publication/revision management, profile cookbook views, native attribution/lineage presentation.

Offer Publish recipe, Publish meal collection, and Share my actual cook from the relevant saved/completed content. One task-focused editor lets users choose dishes, title/hero, ingredients/instructions, selected story/photos/notes/results, and named/anonymous identity. Changing selection immediately changes the preview and export graph. Nothing writes to the original meal or public server before explicit Publish.

Publish variation explains its source in ordinary language and creates a fork only on submission. Reuse as a template keeps the local source record even when reference guidance is cleared. Share my cook records an actual attempt relationship and never claims every cook was a modified recipe. Public copy/fork rights and publication terms are disclosed before publishing, with the acknowledgment version retained.

The owner can edit a new public revision, unpublish, manage private drafts, and inspect named/anonymous items. Public cookbook pages display only deliberately named/profile-listed content. Anonymous publications never acquire a public-profile route or owner handle through lineage. Publication identity changes cannot promise to recall copies already saved by someone else.

Staged uploads have visible progress and retry, with idempotent publication submission. Pending moderation has a clear status; drafts remain editable. Likes are a small secondary action; canceled login preserves the preview and selection. Source-update review shows ingredients/instruction changes plus reference-version information; active cooks stay pinned.

**Acceptance on Android and iOS:** create a pork-only public variant from a pork+potatoes meal; change seasoning; publish a whole collection; publish an unchanged recipe's actual new cook; anonymous/named per item; canceled authentication/upload, process death, owner revision conflict, and upstream update review. Inspect the actual public page/payload to verify the preview matched what became visible.

### PR 13 — Moderation, blocking, deletion, and public-data lifecycle

**Depends on:** 08–12 and the account-deletion amendment resolved. **Areas:** backend moderation/report/block/deletion jobs, role-gated admin queue, both native menus/settings, `pittech.app/account/delete`, privacy/support content.

All public recipes, collections, attempts, text, and photos have report-content/report-cook/block controls. Signed-out users can report and locally block without creating an account; signed-in blocks persist privately. The backend maps opaque publisher aliases without returning anonymous account identity. Reports have per-IP/session limits and an operator queue; include report statuses, moderator notes, suspension/takedown, and audit identities. No public user can grant an admin role.

For the initial small corpus, use publication filtering plus an operator pre-publication review path for new/untrusted text/photo content. Do not rely on a filename/MIME/profanity check as image moderation. Release requires a real content-filtering/review implementation; a future automated image service is not silently assumed. Known violating shared assets are blocked wherever served, and direct forks receive review flags without indiscriminately deleting legitimate independent variants. Respond to reports through the operator workflow; transactional notices go through Resend.

**Proposed account deletion amendment:** remove account identity, profile, sessions, likes, private sync and original assets, and the account's original public payloads/revisions/media. Leave only non-identifying removed-source references required by other users' independent forks. Purge public caches/access links and recompute aggregates; document backup-retention and provider-side deletion procedures. Private copies already exported/downloaded cannot be remotely recalled. This replaces anonymize-and-retain originals and must be reflected in issue #89 before implementation.

Provide an in-app deletion flow on both clients and a functioning web request flow without requiring reinstallation. Include a local-data choice separately: retaining the phone's local journal does not keep server account data. Signing out/deleting while cooking does not stop an unrelated local meal. For subscribers, explain store-managed billing and offer Manage subscription while retaining immediate account-deletion access.

**Acceptance:** reports/blocks work for named and anonymous content while signed-out; arbitrary admin escalation fails; takedown/source removal/fork review/asset purge work; deletion completes and all private/public account-owned reads fail afterward; independently owned recipient copies survive; web deletion works; notifications contain no exposed private content. Actions and native UI suites cover the full paths. Phase 2 stays unavailable publicly until these controls exist.

### PR 14 — Private sync with photos and safe two-device merge

**Depends on:** 08–13, shared v10 models. **Areas:** encrypted sync/asset API, per-record metadata/tombstones, native outboxes, conflict UI, account association and sync status.

Keep the device library authoritative for immediate reads/writes. Persist local changes first and queue network work. Sync transports versioned whole-library snapshots plus asset references initially; photo objects transfer separately/resumably, not as missing paths in a JSON-only mirror. Encrypt private snapshot/photo objects at rest with authenticated encryption and a documented server key/rotation scheme; do not claim end-to-end encryption.

GET returns server version/envelope. PUT carries base version and idempotency identity; 409 fetch/merge/retry preserves divergent changes. Add mutation metadata for every syncable record; raw ID deduplication is insufficient. Use deterministic logical ordering with device tie-breakers and deletion tombstones, retain a recoverable losing edit for meaningful same-record conflicts, and do not resolve by uncontrolled phone clock alone. Concurrent recipe revisions remain branches/version choices rather than silently destroying immutable history.

Immutable measured samples append by stable identities. Attachments have user-scoped ownership/hashes; commits advertise complete objects, and incomplete upload/download never leaves dangling local relationships. Tombstones prevent deleted records from returning after an offline device reconnects; a device older than the supported tombstone horizon must rebase before uploading. Document retention and reset behavior.

Do not sync manufacturer session secrets, credential stores, staged device-specific timers, or silently activated notification/recording state. A second phone can view/log an active meal, but importing synced rule definitions does not start duplicate monitoring or arm its reminders without explicit local adoption. Existing monitoring on the original device is preserved. Account switches/deletion cancel that account's outbox and require deliberate library attachment to another account.

Use Android persistent network work and iOS supported foreground/background opportunities with honest status: last synced, waiting for connection, retry needed, and conflict review. Never block Log/Finish on a server call. Private sync is not accessible from public recipe APIs or aggregate jobs, verified with forbidden-access tests.

**Acceptance across Android↔iOS devices:** simultaneous new notes, same-note edits, recipe branches, deletes while the peer is offline, clock skew, interrupted photo upload, failed persistence, sign-out/account switch, active meal handoff, duplicate request replay, and old snapshots. Relational integrity, recoverability, bounded payloads, and no involuntary monitoring/notifications are required. Phase 2 launches only after this PR passes alongside the social core.

### PR 15 — Preserved free Insights and shared analysis foundation

**Depends on:** 01–04, 14. **Areas:** existing per-dish phase/channel analysis, normalized comparison models, Android/iOS Insights view models, source-quality summaries.

Audit the exact currently free comparison/history tools and retain them on both platforms. Basic history, results, existing comparisons, raw chart inspection, and the Show guide feature are never retroactively paywalled. Port remaining free analysis differences identified by PR 00 before adding paid screens.

Implement equivalent deterministic calculations over dish-level measured data: heating/rest/hold durations, known cut/weight, distinct pit/food channels, temperature unit conversion, wrap anchors, and recorded results. Elapsed session creation time is not automatically cooking time. Missing weight or phase anchors remain missing; repeated return-to-heat intervals are handled by the existing phase rules. Preserve manual point data and real gaps.

Define versioned outputs and matched golden fixtures for the deeper Pro engines supplied in PR 18. Shared contract/math fixtures keep native implementations equivalent without forcing a cross-platform UI rewrite. Differences in methods, weights, units, telemetry quality, and coverage are visible in comparisons. The UI does not attribute causation or predict safe doneness from a curve.

**Acceptance on both clients:** identical supported metrics for the same archive; Celsius/Fahrenheit consistency; simultaneous dishes versus equipment hours; missing/edited anchors; source corrections; sparse manual data; changed probe assignments; all existing free tools remain usable signed-out/offline. Verify parity from result values, not matching labels alone.

### PR 16 — Store subscriptions, verified entitlements, and ad removal

**Depends on:** 08–09, 13–15. **Areas:** StoreKit 2, current supported Play Billing integration, subscription/store-event API, entitlement cache, consent/ad request gates, store product setup and disclosures.

Create one monthly PitTech Pro product with the settled target US price of $3; use actual localized store product data in every purchase UI. No annual plan or web checkout is introduced. Purchases are explicitly bound to a verified PitTech account, confirmed by server-side store verification, and deduplicated by store transaction/purchase identity. Never unlock from a client Boolean or unverified callback.

Process signed App Store Server Notifications V2 and authenticated Play RTDN. Notifications trigger authoritative store-state retrieval/verification where appropriate; deduplicate and handle out-of-order/replayed events, pending purchase, grace, billing retry/hold, cancellation, expiration, refund, and revocation. Implement Play acknowledgment within its required lifecycle, environment separation, and reconciliation for missed notifications.

One account entitlement works across Android/iOS after valid sign-in; do not offer a second subscription when an existing entitlement already covers the user. Restore/reinstall and transaction-binding conflicts have clear recovery paths. Account deletion explains store-managed billing without blocking immediate deletion; document necessary minimal billing/fraud retention separately from deleted profile/cook content.

Check cached/current entitlement before any ad loading. Entitled users trigger zero ad requests, including prefetch and intermediate screen transitions. Unknown/loading entitlement cannot briefly flash ads at a previously entitled user. Cache verified validity/grace for sensible offline access and reconcile when possible; an active cook is never interrupted. Local records, exported files, and free capabilities remain usable after expiration.

The paywall is contextual within advanced Insights/customization/More, with a practical preview, precise monthly billing text, Restore, Manage subscription, privacy/terms links, and a clear close action. No purchase popup interrupts Live or Finish. Debug tests use test ads/store sandboxes; production IDs/secrets remain in their approved configurations.

**Acceptance on Android/iOS and backend:** initial/pending purchase, renew, grace, cancel/expire, refund/revoke, missed/out-of-order notification, restore, reinstall, cross-platform login, account switch, and invalid proof. CI instrumentation proves no ad request under entitlement. Store sandbox/test-track evidence and inspection of the signed shipped build are additional gates, not inferred from mocks.

### PR 17 — Community aggregate pipeline and benchmark API

**Depends on:** 10, 13, 15–16. **Areas:** scheduled aggregation job, eligible published-attempt projections, algorithm/config versioning, aggregate API and native benchmark states.

Aggregate only deliberate active public measured cook stories/attempts. The private sync database is not an input. A recipe revision, saved copy, public fork carrying the same source story, or repeated collection membership is not another physical cook. Deduplicate by the original measured attempt and dish identity before bucketing; actual subsequent cooks receive independent identities. Unpublishing/deletion/takedown removes the applicable contribution and triggers refresh.

Bucket by a consistent food/cut taxonomy and normalized weight bands, with declared method/equipment context where available. Do not combine pork loin and pork butt just because both are pork. Version the taxonomy/band boundaries and publish the method alongside sample counts. Missing fields exclude an attempt from that metric, not from every possible statistic. Each metric/curve interval has its own eligible n and data-coverage rules.

Initial threshold is n >= 10 distinct eligible published attempts per displayed metric/cell. Display n and compute date on every figure; below threshold show an honest insufficient-data state. Publish median heating time/range, supported pit/food curve percentiles, measured stall-related summaries, and recorded wrap/spritz prevalence. Stall detection and resampling rules must have explicit window/continuity/quality thresholds and golden tests before release. Sparse manual spot checks cannot establish a continuous stall distribution; comparison bands never masquerade as recorded individual measurements.

The backend job is idempotent and recomputable with a recorded input cutoff and algorithm version. There is no author ranking inferred from sample quality and no claim that community norms prescribe safe cooking. Native benchmark cards use the same meanings and counts; unsupported fields collapse gracefully. Pro launches on personal value even if every community cell is still below threshold.

**Acceptance:** n=9 hidden/n=10 visible, forks/revisions/copies do not inflate n, mixed units/cuts remain correct, exclusions are metric-specific, deleted/taken-down contributions disappear, repeated job runs agree, and private sync is inaccessible. Both clients display the same fixtures/empty states and include sample counts and coverage explanations.

### PR 18 — Pro personal analysis, customization, and Year in Smoke

**Depends on:** 05, 12, 15–17. **Areas:** both clients' advanced Insights, comparison/recap engines, accessible theme presets, share-card/profile/icon customization.

Lead with useful personal questions: how one's pork cooks compare, which recorded attempt one rated best, how timing changes across recorded cut/weight groups, and where wrap/rest methods differ. Advanced curve views offer explicit Food on/Wrap alignment and measured source selection. Reuse PR 15 calculations; no new metric silently changes an existing free chart's meaning.

Personal analysis runs from the local library and cached verified entitlement; it does not require publishing private history. Explain sample/coverage limitations near each result. Personal bests use the user's declared rating/criterion, not an opaque quality score. Avoid a blank dashboard full of zero-value cards for a new user.

Add a shareable Year in Smoke/periodic recap with user-controlled scope and preview. Publishing/relay upload of that image or associated data remains explicit; a private cook does not become public because it appears in a recap.

Provide a curated initial set of card themes, accessible chart palettes, restrained public-profile presentation options, and app-icon variants using platform-supported mechanisms. Preview customization before applying. Save preferences without modifying measured data; unsupported/expired entitlement has a readable free fallback and does not destroy prior exports or interrupt a cook. Anonymous content cannot expose a profile customization/Pro identity link.

**Acceptance on Android and iOS:** zero/one/many cook libraries, meaningful versus sparse data, personal comparisons with unit/weight/method differences, offline Pro access, entitlement expiration, recap selection/privacy, long text/photo-free cards, color-vision contrast, and platform icon selection. Visual review covers the entire free→preview→purchase→Pro→restore experience as one flow.

### PR 19 — Release hardening, privacy declarations, and operational proof

**Depends on:** all prior PRs and resolved release checkpoints. **Areas:** release flags/configuration, documentation, fixtures/screenshots, store metadata, deployment/recovery runbooks, actual artifact inspection.

Run the complete cross-platform regression suite against the final tested head. Re-run after material changes or a new baseline introduces unresolved risk; do not continuously repeat unrelated passing tests. Inspect light/dark/enlarged-text phone/tablet screenshots for every new task, with realistic content and failure states. Fix awkward navigation, inaccessible controls, overlap, clipped labels, redundant confirmations, and poor content hierarchy before declaring done.

Exercise installed Android upgrades and iOS app replacement/relaunch with an active meal, recording, drafts, photos, reference sources, reminders, and account/sync state. Test new and legacy archives, new-pack round trips, signed-out local operation, two-device sync, publish/fork/deletion, and store entitlements/zero-ad requests from release artifacts.

Update privacy policy, support/contact, terms/publication rights, store App Privacy/Data Safety, account deletion URL, subscription disclosures, screenshots, and user-visible release notes. Determine declarations from actual relay/account/photo/billing/ads behavior; the plan does not assume optional uploads exempt disclosure. Preserve valid old policy/support links when moving content under `pittech.app`.

Deploy staging first, then a controlled production configuration. Validate TLS/domain association, migration rollback/backup restore, scheduled expiry/aggregate jobs, mail delivery, storage cleanup, and admin queue operation. Public publishing writes can be disabled during an incident while local cooking/saving remains available; suppress invalid benchmark results and unsafe public assets independently.

Use TestFlight and Play closed testing before each public wave. Store submission is based on inspected artifacts and documented review flows. Phase 2 requires every social/privacy/sync control on both platforms; Phase 3 requires store products and verified entitlement behavior. Distribution/merging requires the owner's later implementation/release instruction; preparing this plan does not authorize those actions.

**Acceptance:** final parity matrix has no unexplained drift; required CI is green at the submitted head; relevant hardware/provider limits are reported separately; beta task walkthroughs and release artifacts match declarations; rollback/runbooks are executable and all launch checkpoints are resolved.

## 7. Backend and API implementation map

Recommended new module boundaries: `api-service/auth`, `relay`, `publications`, `moderation`, `sync`, `billing`, `analytics`, `assets`, and `web`. Keep business decisions in domain/services and validate every external request at its boundary. The server-authorized owner/entitlement is never taken from submitted JSON.

| Data group | Persistence/authorization |
|---|---|
| users, profiles, auth_challenges, sessions | Private account data; hashed one-time/refresh secrets; public profile projection only by opt-in. |
| public publications, recipe/collection revisions, cook snapshots, lineage | Owner-only writes; schema-validated public projections and immutable version pins; per-item named/anonymous presentation. |
| assets | Ownership/reference graph, original versus public derivative, hashes, readiness, moderation status, and deletion/retention state. Private assets never obtain public URLs through source traversal. |
| likes, blocks, reports, moderation actions | Unique/idempotent relationships; private reporter/block identity; role-authorized administration. |
| sync_libraries, mutation/tombstone metadata, private blob references | User-only reads/writes; optimistic versioning; encrypted payloads; never queried by discovery/aggregates. |
| relay_packs | Anonymous code, payload reference, expiration, owner management-secret hash, bounded transient IP hash; no public list. |
| subscriptions, store_events | Verified store identity/state/environment; unique event handling; private account entitlement projection. |
| aggregate_stats, aggregate_job_runs | Published-input projection, metric eligibility/n, taxonomy/method version, input cutoff, computed time; Pro-authorized result access. |

| API family | Planned routes/contract |
|---|---|
| Relay | `POST /packs`, `GET /packs/:code`, `POST /packs/:code/renew`, `DELETE /packs/:code`; management operations require the independent owner capability. |
| Web links | `GET /s/:code`, canonical public recipe/collection/cook routes, named profile aliases, privacy/terms/help, and `/account/delete`. |
| Identity | `/v1/auth/request`, `/verify`, `/refresh`, `/logout`; owner-only `/v1/me`, sessions, profile, and account deletion. |
| Public content | `/v1/recipes`, `/collections`, `/cooks` with owner-only creation/revision/unpublish; accountless active public detail/revision reads and discover pagination. |
| Derivatives | Recipe publication accepts verified parent/revision provenance. Any optional private fork-registration endpoint returns a private state, never an active public publication. |
| Social | Idempotent `POST/DELETE /v1/recipes/:id/like`; `/v1/profiles/:handle`; reports/blocks using opaque publisher aliases and optional authenticated identity. |
| Sync | `GET/PUT /v1/sync`, private asset upload/complete/download, reset/detach operations; base version, idempotency, ownership and envelope contract. |
| Billing | Store purchase verification endpoints, verified Apple/Play notification handlers, `/v1/me/entitlement`, and reconciliation. |
| Analytics | Pro aggregate queries with cut/weight/method/version/eligible n; internal scheduled job endpoints are not public triggers. |

Exact OpenAPI paths/error codes freeze in PR 00 and remain backward compatible. Every request type has size/shape/ownership checks. Public images/JSON honor publication status and moderation state; signed private/object URLs have limited lifetime and appropriate cache policy. Downloads do not grant private storage access. Text stays data and is escaped in HTML; no executable content, arbitrary redirects, embedded scripts, archive symlinks, or server-side fetches of user-controlled URLs.

## 8. Domain and Resend migration runbook

These are implementation-stage operations; none were performed for this plan.

| Purpose | Proposed production configuration |
|---|---|
| Product/share links | `https://pittech.app` with `/s/...` and stable public recipe/collection/cook pages. |
| Account email/deep-link fallback | `auth.pittech.app`, or final allowlisted paths under the root; serve HTTPS user-confirmed exchange flow. |
| API | `https://api.pittech.app/v1` and separately routed relay endpoints; no secret-bearing client environment values. |
| Staging | Separate staging host/project, DB/storage namespace, email key/domain, callback origin, store sandbox, and fixture accounts. |
| Email From | `PitTech <hello@auth.pittech.app>` after Resend verification. Monitored Reply-To is configured and tested. |

1. Confirm the purchased domain is in the intended Railway workspace, identify DNS ownership, record actual renewal/hosting costs, and configure the planned hosts/certificates.
2. Create/verify the production and staging Resend sending domains with their exact supplied SPF/DKIM records; add appropriate DMARC and validate actual DNS. Disable open/click tracking.
3. Create scoped PitTech keys and store them only in the appropriate server/CI secret stores. Client apps never receive a Resend key. Set sender/reply-to/callback configuration and signed delivery webhook endpoints.
4. Render/preview the magic-link+code email and perform an explicitly authorized test send to the owner's chosen test address. Verify the link/code on same/different devices, expiration, scanner handling, bounce/suppression handling, and actual release callback routing.
5. Once replacement delivery works, execute the concrete Sportfolio retirement: old Production/Beta Magic Links keys, their two webhook endpoints, and the retired `sportfolio.market` sending configuration. Check existing dependencies and preserve the owner's existing authorization. If a genuinely mandatory named destructive confirmation has not already been satisfied, identify that exact requirement and present the concrete retirement list before requesting it. No Munch account changes, contact purge, account-wide suppression clearing, or third-party announcement is included.
6. Verify the live binary's configuration against staging/production boundaries and keep a rollback path that restores the working PitTech setup without placing old secrets in documentation.

## 9. CI, visual review, and acceptance evidence

### Shared fixtures

Maintain versioned synthetic fixtures for: pork+potatoes with selective sharing; three recipes from different sources; same-name probes with different identities; Fahrenheit/Celsius; negative prep offset; long titles/notes; multiple photos with EXIF orientation/GPS; sparse/no measurements; dense multi-probe data with gaps; revised/deleted source; anonymous publication; offline device deletes/edits; differing recipe branches; and subscription lifecycle events. No real private cook photos, email addresses, device IDs, or secrets are added to the repo.

| Gate | Evidence required |
|---|---|
| Domain/unit math | Equivalent native golden results for phases, source mapping, eligibility/counting, gap behavior, and recipe/reminder invariants. |
| Android persistence | Real generated-schema migrations from supported versions, relational ownership, drafts/assets, active-cook upgrade recovery, sync replay/tombstones. |
| iOS persistence | Legacy decoding, atomic save/import recovery, scoped asset/reference ownership, relaunch, restored inactive automation, large-archive memory behavior. |
| Cross-platform portability | Actual writers/readers both directions; pack/full-backup scope, source/version relations, selected media, units, metadata removal, and failure boundaries. |
| Backend | Node tests plus real Postgres integration tests for authentication matrix, revisions/idempotency, private isolation, abuse limits, asset cleanup, moderation/deletion, sync, billing, and aggregate thresholds. |
| Native UI | Android required API 36 lanes and native iOS unit/UI flows; iPhone/iPad where applicable. API 37 retains its existing advisory status. |
| Visual quality | Reviewer inspects native images/exported cards at the final dimensions; light/dark, required enlarged text, narrow phone, tablet, keyboard, long content, loading, offline/error, and empty states. |
| Release networking | Actual Universal/App Links and release signing identities, staging/production routes, restore purchase, and zero ad requests for entitled users. |
| Operational | DNS/TLS, approved test delivery, expiry/aggregate schedules, privacy/deletion pages, storage cleanup, admin reports, backup restore and rollback. |
| Hardware | Provider account/device and screen-off history/notification behavior reported separately; emulator success never substitutes for physical evidence. |

Each PR description states user impact, both platforms, data migration, required evidence links/head SHA, and the remaining physical/store checks. Include the plain-language release-note entry. API/UI failure logs must be redacted. Do not merge a tested earlier head after later untested changes.

### Visual acceptance checklist

- A fresh user can find Start/Resume, select a recipe, save it, and use it without being interrupted by account setup.
- Content is the first visual priority: active food, recipe photo/instructions, and useful comparisons appear before optional controls/metadata.
- One primary task action is clear; dense lists of equally prominent buttons and oversized control stacks are rejected.
- Guide on/off is obvious and consistent; source/current distinction remains readable in monochrome and enlarged text.
- No footer/safe-area/keyboard overlays obscure the last item or confirmation action. Buttons do not shrink below the platform target sizes to fit.
- A multi-dish meal remains scannable; selection, optional advanced fields, and provenance use progressive disclosure.
- Failed saves/uploads/imports preserve the user's work and provide a nearby retry/recovery choice.
- Colors, surfaces, typography, card shapes, photo crops, chart semantics, and error states are coherent across the entire app.
- Every proposed final screen has native screenshot evidence; a compiler pass or a concept mockup alone is insufficient.

## 10. Release checkpoints, rollback, and measurement

Phase 0/1 can ship independently after 1.0. Phase 2 cannot be advertised as complete until accountless discovery/save/cook, publishing/profiles/forks/attempts/collections, reporting/blocking/deletion, and private sync all work on both clients. Phase 3 launches on personal analysis/ad removal/customization; insufficient community data remains a normal supported state.

Before each release, refresh the parity matrix and baseline, inspect the exact build artifacts, and run beta task walkthroughs: start/log/finish; save only pork; make a variation; share/import by code; follow/hide a source guide; browse/save signed-out; publish anonymously; fork; share a new attempt; sign in on another phone and sync; report/block; delete account; buy/restore/cancel Pro.

Feature-specific incident switches may pause uploads/public writes, hide invalid public content/benchmarks, or stop a failing sync queue. They must preserve local logging, existing readable data, valid entitlements, and current provider connections. Database migrations use staged expand/backfill/contract; avoid irreversible destructive changes in the first rollout. Keep backup recovery instructions for metadata and assets, and prove deletion/cleanup is idempotent.

Measure infrastructure/email/storage behavior from staging and the controlled production rollout; do not repeat the original rough idle-cost estimate as a guarantee. Track public content/like/fork operations and aggregated delivery/service failures without reading private cook history. If actual discovery-to-cook conversion needs new client telemetry, that requires an explicit minimal collection design and updated declarations; otherwise report downloads as a proxy, not completed cooks. No new analytics SDK is assumed.

The owner can seed public recipes and collections through the same publish flow. Announcements/social posts are prepared only under a later explicit publishing instruction. This plan does not send messages, submit stores, or retire configuration by itself.

## 11. Readiness and decision register

| Item | Status at planning time | Resolution point |
|---|---|---|
| Product/navigation/guidance/editing/fork/anonymous/Pro direction | Settled in this conversation | Preserved in all PRs. |
| `pittech.app` purchase | Owner reports purchased | PR 08 verifies workspace/DNS/TLS; not a planning blocker. |
| Correct Resend account | Sportfolio configuration inspected; no changes made | PR 08 migration, limited to identified Sportfolio resources. |
| Companion DESIGN.md/share-card concept images | Not located in accessible repository/files during review | PR 00 produces original concrete designs from the agreed direction; incorporate companion assets if supplied. |
| Account-deletion default | Original anonymous-retention decision conflicts with Apple guidance | Amend issue #89 before PR 13; recommended behavior deletes account-owned originals and preserves independent forks/copies. |
| Full feature parity | Connection matrix exists; broader audit and port work still required | PR 00 matrix assigns PRs; PR 01/03/15 and provider investigation resolve gaps. No silent deferral. |
| Workflow writing | Current App credential cannot edit workflow files | Arrange permitted workflow application path in PR 00; backend CI must exist before PR 06 merges. |
| Store products/secrets/review access | Implementation-stage setup | PR 16/19; credentials stay out of documents/repos. |
| Physical provider/background/store tests | Not performed by this planning work | Appropriate PR release gates, reported honestly. |

**Execution is authorized.** The owner delegated the deletion amendment on October 14; the approved behavior is in `ONLINE_SERVICES_SPEC.md`. Concrete design inspection and the documented CI/release gates remain required.

## 12. Source map and official references

### Repository evidence

- [Current planning baseline](https://github.com/michaelhmiv/PitTech/commit/82cbe1b335499aba561fc58c91d727055b8ce4fc) and [issue #89](https://github.com/michaelhmiv/PitTech/issues/89).
- [AGENTS.md](https://github.com/michaelhmiv/PitTech/blob/82cbe1b335499aba561fc58c91d727055b8ce4fc/AGENTS.md): CI/build authority, workflow permission, signing, privacy, compatibility, and read-only policy.
- [Connection platform parity](https://github.com/michaelhmiv/PitTech/blob/82cbe1b335499aba561fc58c91d727055b8ce4fc/docs/CONNECTION_PLATFORM_PARITY.md): latest public availability and documented SDK/OS gaps.
- [Cook experience](https://github.com/michaelhmiv/PitTech/blob/82cbe1b335499aba561fc58c91d727055b8ce4fc/docs/COOK_EXPERIENCE.md), [saved recipes/meals](https://github.com/michaelhmiv/PitTech/blob/82cbe1b335499aba561fc58c91d727055b8ce4fc/docs/SAVED_RECIPES_AND_MEALS.md), [unified reminders](https://github.com/michaelhmiv/PitTech/blob/82cbe1b335499aba561fc58c91d727055b8ce4fc/docs/UNIFIED_MEAL_REMINDERS.md), and [integration validation](https://github.com/michaelhmiv/PitTech/blob/82cbe1b335499aba561fc58c91d727055b8ce4fc/docs/MEAL_INTEGRATION_VALIDATION.md): reuse existing workflows and ownership semantics; older manual-only statements are superseded by current connection evidence.
- [Android transfer implementation](https://github.com/michaelhmiv/PitTech/blob/82cbe1b335499aba561fc58c91d727055b8ce4fc/app/src/main/java/com/pittech/data/PitTechDataTransfer.kt), [Room database](https://github.com/michaelhmiv/PitTech/blob/82cbe1b335499aba561fc58c91d727055b8ce4fc/app/src/main/java/com/pittech/data/PitTechDatabase.kt), [iOS library](https://github.com/michaelhmiv/PitTech/blob/82cbe1b335499aba561fc58c91d727055b8ce4fc/ios/PitTech/LibraryModels.swift), and [iOS store](https://github.com/michaelhmiv/PitTech/blob/82cbe1b335499aba561fc58c91d727055b8ce4fc/ios/PitTech/CookStore.swift): verified schema namespaces, duplicate-import behavior, and different archive limits.
- Native navigation/recipe source and recent CI screenshots were reviewed earlier in this conversation. Those screenshots are baseline evidence, not screenshots of this proposed implementation.

### Official references checked October 13–14, 2026

- [Railway domain purchase/DNS](https://docs.railway.com/networking/domains/railway-domains): purchased domain management and DNS control.
- [Resend verified domains](https://resend.com/docs/dashboard/domains/introduction) and [auth email deliverability/scanners](https://resend.com/docs/knowledge-base/how-do-i-maximize-deliverability-for-supabase-auth-emails): domain verification, tracking avoidance, and the email-scanner behavior informing the proposed exchange flow. The Supabase example is a precedent; no Supabase dependency is introduced.
- [Android offline-first architecture](https://developer.android.com/topic/architecture/data-layer/offline-first): local availability, persistent writes, and conflict-handling requirements. The specific merge design here is a PitTech proposal.
- [Apple App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/) and [Google Play UGC](https://support.google.com/googleplay/android-developer/answer/9876937): public-content safeguards.
- [Apple account deletion guidance](https://developer.apple.com/help/app-review/guideline-reference/5-1-1-account-deletion/): authored-content deletion and subscribed-account handling; this source motivates the explicit amendment to the issue.
- [Google Play account deletion](https://support.google.com/googleplay/android-developer/answer/13327111): web request resource alongside the in-app path.
- [Google Play subscription lifecycle](https://developer.android.com/google/play/billing/lifecycle/subscriptions), [Play RTDN reference](https://developer.android.com/google/play/billing/rtdn-reference), [Apple subscriptions](https://developer.apple.com/app-store/subscriptions/), and [App Store Server Notifications](https://developer.apple.com/documentation/appstoreservernotifications): lifecycle/review planning. Play state-change notifications require authoritative status retrieval. SDK/API versions and store-specific mechanics must be rechecked during PR 16 implementation.

All layouts, PR boundaries, data models, algorithm defaults, quotas, and operational choices in this document are engineering proposals derived from the settled product decisions and inspected source. They are not claims that the current application or infrastructure already implements them.
