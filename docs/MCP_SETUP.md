# PitTech MCP — connecting your AI assistant

PitTech MCP lets an AI assistant you choose (ChatGPT or Muse) help
with **your own** PitTech library — meals, recipes, cook logs, notes,
photos and plans — using the same private data the apps sync. It is a
PitTech Pro feature.

## Before you start

You need:

- A PitTech account, signed in on your phone (Android or iOS 17+).
- An active **PitTech Pro** subscription. Access is checked on every
  single request: if Pro lapses, the connection stops working until
  it is restored — nothing is deleted.
- **Private sync** turned on, and the app opened at least once while
  signed in, so your library exists on the server for the assistant
  to work with. The assistant only ever sees what has synced.

## Connect

The MCP endpoint is:

```
https://api.pittech.app/mcp
```

### ChatGPT or Muse (OAuth)

1. In the assistant's settings, add a custom connector / MCP server
   with the endpoint above.
2. The assistant opens PitTech's approval page, which shows a short
   **connection code**.
3. In PitTech on your phone: **Account → Connect your AI → Approve a
   connection**, enter the code, and confirm the permissions
   (scopes) shown.
4. The assistant finishes connecting in your browser. Approvals
   expire if not completed promptly; just start again if one lapses.

### Personal access token (alternative)

1. In PitTech: **Account → Connect your AI → New connection**. Name
   it after the assistant, choose its permissions and how long it
   lasts (30, 90 or 365 days — 90 is preselected).
2. Copy the token **when it is shown — it is shown only once** and is
   never stored on your phone. If you lose it, disconnect that
   connection and create a new one.
3. Paste the token into the assistant's connector settings as the
   bearer token for the endpoint above.

## Permissions (scopes)

Grant the least the assistant needs; you can disconnect and re-issue
at any time, and existing connections keep the permissions they were
granted with.

| Scope | Lets the assistant… |
|---|---|
| See your library | Read meals, recipes, logs and plans |
| Add and edit | Create and change meals, recipes, notes, plans |
| Delete | Delete meals, recipes and notes |
| See photos | View cook photos |
| Add photos | Attach photos to your cooks |
| Plans | Create and change cook plans and reminder rules |
| Ask for device actions | Queue small in-app actions for your phone (see below) |
| Exports | Create and download exports of your data |
| Imports | Import archives into your library (always previewed first) |
| Community benchmarks | See anonymous community comparisons |

Account, billing and credential actions are **never** available to
an assistant — those stay in the app and the stores, by design.

## What the assistant can and cannot do

- **Your data, read-write** (within the scopes you granted): the
  whole cooking loop — plan, cook, log, photograph, review.
- **Community recipes: read-only.** It can search public recipes and
  save a private copy to your library. It can never publish, like,
  fork, comment, or touch your public profile.
- **Equipment: read-only.** There is no way for an assistant to
  control a grill — no ignition, no setpoints, no power. That door
  does not exist in the protocol.
- **Destructive changes are guarded.** Big operations (imports,
  bulk deletes) go through an exact preview with a short-lived
  confirmation; stale previews fail rather than guessing. Most
  writes can be undone once with `undo_private_operation`, as long
  as nothing newer has touched the same records.

## Photos and attachments

- Ask the assistant for a photo and it receives the actual image,
  not a link.
- Adding a photo is chunked under the hood: if an upload is
  interrupted, ask the assistant to check the upload status and
  resume rather than starting over.
- How an assistant hands you a file it received (and how it accepts
  one from you) differs between ChatGPT and Muse — each client
  manages its own attachments. The MCP side is identical either
  way.

## Freshness and conflicts

- Every answer the assistant gives carries the library version it
  was read from. Writes check that version: if your phone changed
  the library in between, the write is refused with an explanation
  instead of overwriting your newer work.
- Retries are safe: repeating the same request returns the original
  result rather than doing the work twice.
- If the assistant reports a conflict, resolve it in the app's sync
  review — the app is always the source of truth.

## Device actions

With the **Ask for device actions** scope, an assistant can ask your
phone to do small things in the app: turn a reminder on or off,
snooze or acknowledge it, start or stop a timer, pause or resume
temperature collection, assign a probe to a dish, or change display
units.

How it works, honestly:

1. On each phone: **Account → Device actions**, choose which kinds
   of requests this phone accepts. Nothing is accepted until you
   opt in, per kind.
2. A request waits in a queue. Your phone picks it up **when you
   open PitTech** and check (the account screen checks automatically;
   there is also a "Check for requests" button).
3. The phone does the work in the app itself and reports back what
   actually happened — an action only ever shows as *applied* when
   the phone itself says so. If something needs you (notifications
   off, the item hasn't synced to that phone yet), it reports
   *needs attention* with the reason.
4. Requests **expire** (15 minutes unless the assistant sets a
   different window up to a day). An offline or sleeping phone
   cannot be woken up; an expired request never applies late.
5. Removing a phone (Stop taking requests) cancels its waiting
   requests — they are never handed to another device.

Platform notes: reminder snooze is the app's standard 10 minutes on
both platforms (the receipt says so if a different duration was
asked for). On iPhone, temperature collection runs on the recording
device, so pause/resume/cadence requests answer *needs attention*
there; Android executes them. Preferences changes apply on the
app's next refresh.

## Moving data: exports, imports, share cards

- **Export** creates a portable archive (the same format the apps
  back up) of your whole library, one meal, or one recipe. Exports
  expire after 24 hours; photos travel as a verified list — ask for
  a photo if you need its bytes.
- **Import** always starts with a **preview**: what is new, what is
  unchanged, what has changed, and what conflicts. Nothing is
  written until the exact preview is confirmed, and a confirmation
  can be used only once. Records the format forbids restoring under
  the same identity (for example a previously deleted meal) are
  reported in the preview, not silently resurrected.
- **Share cards** render a deterministic card for a cook (hero
  photo or temperature chart, milestones, stats) that the app can
  display. Rendering a card publishes nothing.

## Disconnecting and recovery

- **Account → Connect your AI** lists every connection. Disconnect
  one, or all, at any time — the assistant loses access
  immediately.
- Signing out of PitTech on a phone stops its assistant requests;
  queued requests are never transferred to another account or
  device.
- If an OAuth refresh token is ever replayed, the whole connection
  family is revoked automatically. Reconnect from the app.
- A lost personal token cannot be recovered or shown again —
  disconnect it and create a new one.

## Limits worth knowing

- The assistant sees only **synced** data. A cook that exists only on
  a phone that has not synced yet is invisible to it, and device
  requests for items not yet on the target phone answer
  *needs attention* until that phone syncs.
- Community benchmarks only appear when enough cooks contribute —
  small samples are withheld rather than shown as noise.
- Rates and sizes are capped (photo chunks, request sizes, request
  frequency). If a request is refused for size, split it — the
  assistant can do uploads and imports in steps.
