# Saved recipes and independent meal attempts

Each saved recipe contains exactly one dish. Saving is an explicit review action on a finished meal; unrelated dishes never enter the library automatically. Recipe edits create immutable versions, duplicates have separate identities, and new meals retain the exact source version for each dish.

Recipe extraction copies explicitly scoped notes, ingredients, methods/durations, actual anchors, chosen photos, and recorded manual temperatures. Ambiguous whole-meal context is opt-in. Shared probe names cannot assign another food's temperatures to a recipe. Instructions are editable separately from recorded observations. Partial update drafts retain ingredients and instructions for which the newer attempt supplied no replacement.

Recipe-only operations never replay source notes, photos, readings or physical actions into the new meal. A cook-owned recipe_attempt document holds editable setup for the current attempt. Ingredients and targets can change there without updating the saved source or resetting controller recording. Meal presets retain order, individual versions, customized setup and optional shared equipment preferences; standalone dishes do not become saved recipes.

Android and native iOS use the same archive v8 contract. New snapshot fields are optional and preserve earlier snapshots. Reference assets remain independent of source meal files. Whole-library archives include all saved recipes and presets; a single Android meal export includes only its related recipe graph.

Validation adds scoped extraction, identical probe-name isolation, negative preparation offsets, immutable manual temperature facts, partial update retention, multi-source composition, current-attempt isolation, preset reuse, older version reuse, source deletion and idempotent restores. CI runs the new Room integration classes explicitly along with the existing user, recording, migration and archive suites.
