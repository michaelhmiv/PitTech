# Per-dish reference guides

Each recipe revision retains an independently owned cook story in a `recipe_reference`
companion document. Raw readings, units, logical probe identities, quality, cadence,
timestamp basis, source timezone and explicit gaps remain recorded facts. Instruction
edits keep the story unchanged; deleting the source meal does not remove it.

Starting a recipe meal pins a copy in the dish's `recipe_attempt.payload.guide`:
`version: 1`, `revisionId`, immutable `snapshot` and `story`. The existing attempt's
editable `snapshot` remains separate. Older attempts acquire an available pinned
reference once; unavailable measurements yield saved observations/instructions only.

One cook-owned `guide_presentation` document stores `version: 1`, `visible` (default
false), nullable `dishIds` (null means all available guides), and `channels`. Channel
keys include current dish, source logical probe and semantic role. Values reference
an eligible current channel; mappings never cross dish food scopes or turn a setpoint
into a food/pit reading. A mapped reference keeps its own identity, dashed style and
Author label. Visibility and mappings only affect presentation.

Live shows instructions and a compact recorded story. Timeline pairs recorded and
actual actions by semantic action and occurrence within each dish; unmatched source
events are labeled honestly, and the actual log remains unchanged. More milestones
can be revealed without loading all rows into the first screen.

Charts place each source timestamp at `current Food on + source time - source Food on`.
Negative preparation offsets survive. A whole-meal action recorded before a new dish
was added is not assumed to be that dish's Food on. No current anchor means no curve.
Manual measurements remain points; explicit outages, invalid samples and gaps beyond
the recorded cadence split curves. Inspection shows original values/units and source
times. No interpolation or weight-based timing is used.

Hiding guides leaves opted-in reminders enabled. Reference events never enter the
actual log, start monitoring, satisfy a reminder condition or mark an action complete.
The existing independently controlled reminders and prior-cook comparison tools remain
available. Cook Packs contain only the explicitly selected public story; guide state is
local/private backup data. Archive v10 and Room v10 stay unchanged.

GitHub Actions gates include the new Android integration class (19 data tests total),
14 companion/UI tests, native unit tests and standard/accessibility-text UI flows.
Actual guide-bearing archive exchange and screenshot review are still required.
