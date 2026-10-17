# PitTech experience system

This is the concrete design contract for issue #89. Open
[the interactive review board](experience.html) to inspect representative
Meals, Recipes, Discover, recipe detail, collection, publishing, Live, Timeline,
Charts, Insights and sign-in states. The board contains labeled synthetic
examples, not claimed public recipes. Theme, text scale, device width and guide
visibility are interactive. Native screenshots remain the release evidence.

## Direction

Warm paper, charcoal type, sage for reference and quiet status, ember for primary
actions. Content is the focus: a useful cook, a legible recipe, an honest chart.
Native bars/sheets, system type, sharing and purchases keep their platform
behavior. No login promotion precedes a user's local task.

| Semantic token | Light | Dark | Native use |
|---|---|---|---|
| Canvas | `#F5F0E7` | `#1B1E1B` | Screen background |
| Surface | `#FFFCF6` | `#242923` | Cards and sheets |
| Raised | `#FFFFFF` | `#2C322B` | Selected controls |
| Ink | `#242622` | `#F0EDE4` | Primary text |
| Supporting ink | `#514C45` | `#C9C5B9` | Metadata and captions |
| Border | `#E2D9CD` | `#444B40` | Separators, quiet outlines |
| Ember | `#B04D2E` | `#D77954` | Primary action, current cook |
| Ember fill | `#F2D9CC` | `#65341F` | Selected/action background |
| Sage | `#58665A` | `#9BAC98` | Source guide and completion |
| Sage fill | `#DCE3DA` | `#37443A` | Reference container |
| Water | `#416C8A` | `#83B6CE` | Secondary chart role |

Spacing uses 4/8/12/16/24/32; phone gutters 20, tablet gutters 32. Cards use
16-unit corners, primary actions 14, small controls 10. Photo crops use 4:3 in
cards and 16:10 in detail, with a stable crop chosen by the user. Missing photos
use a restrained food symbol and available facts, never invented imagery.

Typography follows system dynamic type: page title 28/semibold, section
20/semibold, body 16–17, supporting 14, caption 12–13; temperatures/times use
monospaced digits. No fixed-height text rows or mandatory single-line titles.
Android controls target 48dp, iOS 44pt. Large text stacks metadata and actions;
the native tab bar retains platform accessibility behavior.

## Layout and interaction

| View | Hierarchy and primary action |
|---|---|
| Meals | Active cook first; Start meal; completed history; planning secondary. Resume opens the actual cook. |
| Recipes | Search and filter button; selected filter chips; image/fact cards. Compose mode reveals selection controls and a tray. |
| Discover | Search, concise food filters, useful liked/forked shelves. Accountless browsing, saving and cooking. No fake content before the API exists. |
| Recipe | Hero/title/source; measured facts; Save/Cook; ingredients, editable instructions, recorded story, results. Version credit stays visible. |
| Collection | Ordered dish cards; independent selection; Cook selected dishes. Removing a dish affects the composition only. |
| Publish | Saved draft; scope; editable text/hero; explicit story/photo choice; per-item identity; exact public preview; Publish. |
| Live | Compact meal status and guide switch; phase-grouped food cards; direct equipment access; stable Log/Temp/Photo/Reminder strip. |
| Timeline | Actual activity and scope filters. Guide on adds paired full-width Author/Your cook blocks on phone; two lanes on wide layouts. |
| Charts | Current measurements first; dashed, labeled source channels. Real gaps stay gaps; inspect each series; per-dish Food on alignment. |
| Insights | Useful personal timing/comparisons, then deeper analysis. Missing measurements stay missing. Small-sample community states state the threshold. |
| More | Optional account/sync status; Devices; appearance/units; notifications; data; privacy/support. |
| Sign in | Explain the requested account benefit; email; code/link; return to the exact draft. Cancel preserves the local task. |

## Guide contract

Show guide is one durable meal-level preference, initially off. Each dish pins
a recipe revision and independently owned measured story. Correcting Food on
repositions reference presentation; changing source probe mapping is explicit.
Current curves are solid, source curves dashed and labeled; color alone never
distinguishes them. No weight retiming, fake interpolation or finish prediction.
“Author wrapped at +3h20m” is a recorded observation; “Check bark around +3h” is
editable instruction. Guide visibility never activates reminders. Enabled
reminders remain visibly reachable even with guidance hidden.

## States and accessibility

Empty states explain the next useful local action. Loading preserves geometry;
retry stays beside the failed task; cached content keeps its hierarchy offline.
Auth failure preserves drafts. Long recipes reflow. Controls retain text labels
and announce selection/state. Sheets account for keyboard and safe areas.
Reduced motion removes decorative transitions. Errors focus the affected field.
Public previews show all included captions/notes/probe labels; photos are chosen
explicitly. No credentials, private paths or hardware identifiers appear.

## Visual review gate

Inspect native light/dark phone, tablet and enlarged-text screenshots. Include
empty library, three-dish active meal, sparse manual readings, no photo, long
title, offline discovery, partial upload, account cancellation and retry.
Check clipping, scan order, tap targets, keyboard/toolbar overlap, chart labels
and outdoor contrast. Exported share cards are reviewed at their 1080×1350
pixels, separately from the app preview. Record evidence in STATUS.md.
