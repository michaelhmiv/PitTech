# PitTech — Meals, Saved Recipes & Historical Cooking Guidance

**Engineering Implementation Specification — Six Pull Requests**

**Repository:** `michaelhmiv/PitTech`  
**Specification baseline:** `d8204c1`; implementation begins at current `main` `7e18c68` (October 12, 2026)  
**Status:** Approved product direction; engineering specification ready for implementation  
**Platforms:** Android (Kotlin, Jetpack Compose, Room) and native iOS (SwiftUI)  
**Delivery:** Six sequential, independently validated pull requests

---

## A. Product requirements and non-negotiable principles

### Primary objective

Transform PitTech from a cook-session logger into a meal-based cooking journal with individually reusable saved recipes and optional guidance derived from actual previous cooking experiences.

The core workflow:

1. Start a meal containing one or more dishes.
2. Record activities naturally during preparation, smoking, cooking, resting, and serving.
3. Finish the meal while preserving every dish's individual history.
4. Explicitly select successful dishes to save as reusable recipes.
5. Create future meals by combining saved recipes from different previous meals.
6. Modify any loaded recipe without changing its saved source.
7. Optionally enable reminders based on time, probe temperatures, or historical actions.

### Non-negotiable requirements

- **No mandatory recipe setup.** Starting a meal must not require ingredients, preparation steps, a schedule, or reminders.
- **No automatic recipe saving.** Completed dishes stay in history; only expressly selected dishes enter Saved Recipes.
- **No automatic guidance activation.** Previous milestones are reference information until the user explicitly enables reminders or guided cooking.
- **No source-history mutation.** Editing a recipe, starting a new meal, or creating a variation cannot modify completed cooking records.
- **Full recipe editability.** Ingredients, quantities, seasonings, preparation, notes, milestones, target temperatures, timings, and descriptive information can be changed in saved copies.
- **One meal, independent dishes.** Each dish retains its own activities, stage, history, results, and recipe provenance.
- **Offline-first.** The complete core workflow must function without an account, server connection, or AI service.
- **Backward compatibility.** Existing cooks, saved playbooks, media, measurements, device bindings, recording history, and archives must survive upgrades.
- **No invented events.** PitTech may derive elapsed durations from actual timestamps but must never fabricate actions, cooking times, measurements, or recipe instructions.
- **Platform-appropriate behavior.** Android controller-dependent features must not be falsely presented as available on iOS, which currently uses manual logging.

### Non-goals for these six PRs

This initiative does not introduce an AI-generated recipe service, a public recipe-sharing network, cloud synchronization, a nutritional database, grocery ordering, automatic control of smoker hardware, or a mandatory multi-step cooking wizard.

Existing controller and telemetry integrations remain read-only.

---

# PR 1 — Foundational meal, recipe and revision architecture

**Proposed title:** `feat: add meal and reusable recipe domain foundation`

**Depends on:** Current `main`.

### Purpose

Establish the database architecture that makes independently reusable dish recipes possible without replacing or destabilizing the existing cook records.

### 1.1 Data model

Retain the existing `CookEntity` as the underlying storage for a meal session. Rename user-facing terminology to **Meal**, but do not immediately rename the `cooks` SQL table or existing `cookId` relationships.

Retain the existing `DishEntity` as a specific dish being prepared within a meal.

Introduce these logical entities:

**SavedRecipe**
- `id`: stable UUID
- `name`: user-editable title
- `description`: optional
- `tags`: optional
- `currentRevisionId`
- `createdAtUtcMillis`
- `updatedAtUtcMillis`
- `archivedAtUtcMillis`: optional

**RecipeRevision**
- `id`: stable UUID
- `recipeId`
- `parentRevisionId`: optional
- `revisionNumber`
- `sourceCookId`: optional
- `sourceDishId`: optional
- `snapshotSchemaVersion`
- `snapshotJson`
- `createdAtUtcMillis`
- `changeNotes`: optional

Revisions are immutable once saved. Editing creates another revision and updates the recipe's current revision pointer.

**DishRecipeSource**
- `dishId`
- `recipeId`
- `recipeRevisionId`
- `appliedAtUtcMillis`

This links the new dish attempt to the exact saved recipe version used to create it.

**RecipeAsset**
- `id`
- `recipeRevisionId`
- `relativeFilePath`
- `mimeType`
- `caption`
- `sourcePhotoId`: optional provenance
- `createdAtUtcMillis`

Reference photos must have independently owned files, so deleting the original meal cannot break a saved recipe.

**MealPreset**
- `id`
- `name`
- `itemsJson`: ordered references to individual recipe revisions or standalone dish setup snapshots
- `createdAtUtcMillis`
- `updatedAtUtcMillis`

Meal presets are optional shortcuts for repeating a combination of dishes. Saving a preset must not silently publish its unsaved dishes to Saved Recipes.

### 1.2 Recipe snapshot contract

Implement a versioned `RecipeSnapshot` model that can represent:

- Dish name, type, description, tags and applicable weight or serving details.
- Ingredients, amounts, units, brands and optional preparation grouping.
- Preparation instructions and free-form notes.
- Cooking methods, settings and optional durations.
- Actual historical actions selected as reference milestones.
- Recorded temperature targets and selected observations.
- Reference photographs and captions.
- Historical cooking-stage durations.
- Results, rating and lessons learned when recorded.

A snapshot must distinguish **recorded facts** from **editable instructions**.

For example, "Wrapped after 3h 20m" may be stored as a historical observation, while "Consider checking bark around 3h 20m" is a user-editable guidance suggestion.

Raw telemetry does not need to be duplicated into every saved recipe. The original attempt remains the authoritative measurement history.

### 1.3 History and ownership rules

- Every completed meal preserves its original IDs and events.
- A saved recipe is independent of the meal from which it was created.
- Deleting a meal must not delete saved recipe revisions or their independently stored reference media.
- If the source meal no longer exists, the recipe remains usable and displays a source-history-unavailable message where appropriate.
- Deleting a saved recipe must not delete meals made from it.
- References to deleted sources must not accidentally attach to unrelated records.
- Historical event timestamps retain their original UTC time and source timezone.
- New cooking attempts receive new meal, dish, note, reading and photo IDs.

### 1.4 Compatibility and migration

Propose Room migration **7 → 8**.

Create new tables and indexes without rewriting the existing `cooks`, `dishes`, `timeline_events`, or `sensor_readings` data.

Existing meals and legacy playbooks remain available, but old playbooks must **not** automatically become saved recipes.

Extend the portable archive from current version 6 to proposed version 7, adding recipes, revisions, source relationships, meal presets, and recipe-owned attachments.

Implement the equivalent models and archive decoding on iOS.

### Primary implementation areas

- `data/PitTechDatabase.kt`
- `data/CookEntities.kt`
- New `data/RecipeRepository.kt`
- New `domain/RecipeModels.kt`
- New `domain/RecipeSnapshotCodec.kt`
- `data/CookRepository.kt`
- `data/PitTechDataTransfer.kt`
- `ios/PitTech/LibraryModels.swift`
- `ios/PitTech/CookStore.swift`
- Native archive validation and compatibility code

### Acceptance criteria

- Existing installed cook data survives database migration without changed IDs or timestamps.
- Recipes and revisions can be created, read, and archived independently of source meals.
- A revision cannot be modified after creation.
- A dish can reference exactly the saved revision from which it originated.
- Deleting a source meal does not destroy an independently saved recipe.
- Android and iOS can round-trip a compatible archive containing recipes.
- Re-importing records does not create duplicate or conflicting IDs.
- Legacy backups remain supported.
- Foreign-key validation and migration tests pass.

**PR merge gate:** Database migrations, repositories, codecs, Android builds, iOS build/tests, and archive round-trip tests pass.

---

# PR 2 — General preparation, ingredients and cooking methods

**Proposed title:** `feat: support flexible preparation and multi-method cooking logs`

**Depends on:** PR 1.

### Purpose

Enable PitTech to document complete meals—not just meat that goes directly onto a smoker—without requiring users to enter detailed recipes beforehand.

### 2.1 Redesign dish setup

The default dish editor should be minimal:

- Dish name.
- Optional food category.
- Optional weight or servings.
- Optional additional details.

Do not default every new dish to Pork.

Expand food categories to cover Meat, Poultry, Seafood, Vegetables, Pasta, Sides, Dairy, Baked dishes, Desserts and Other.

Show fields such as bone-in, cut, grade and trimming information only when they are relevant or requested.

All advanced fields remain optional.

### 2.2 General-purpose ingredient recording

Replace the currently meat-oriented ingredient-stage choices with flexible classifications such as:

- Main ingredient
- Seasoning
- Sauce
- Marinade
- Binder
- Brine
- Glaze
- Topping
- Other

Expand quantity support to include common cooking units such as cups, tablespoons, teaspoons, fluid ounces, milliliters, grams, ounces, pounds, kilograms, and count-based quantities.

Store ingredient amounts numerically when provided, retaining the original unit and entry precision.

Allow ingredient lists to remain entirely blank.

Users should be able to record:

"Made cheese sauce with 8 oz cheddar, 4 oz Gouda, milk and butter."

That sentence alone must remain a valid saved note.

If users choose to record structured ingredients separately, those quantities can be reused and edited later.

Do not automatically interpret unstructured text as verified structured ingredients.

### 2.3 Extend combined cook notes

Preserve the existing single-note editor and quick actions.

Add optional structured attributes to a note:

- Cooking method.
- Duration.
- Start/end time where known.
- Related dish.
- Related ingredients.
- Optional result or observation.

Example:

**Note:** Boiled macaroni  
**Method:** Stovetop  
**Duration:** 8 minutes  
**Description:** Slightly undercooked so it finishes in the smoker.

Users may enter only the description, only the duration, or any combination.

Extend note metadata through a backward-compatible versioned contract. Preserve all existing version-1 notes.

### 2.4 Optional task timers

Support independently running timers attached to preparation activities.

Examples:
- Pasta cooking timer.
- Sauce-reduction timer.
- Grill-preheat timer.

Timers must be optional and may overlap.

Persist timer anchors so navigating away or relaunching the app doesn't reset them.

Stopping a timer offers to save the measured duration to an ordinary cook note. It must not create duplicate timeline entries.

Full notification integration follows in PR 5. This PR should focus on accurate timing and logging.

### 2.5 Cooking-method transitions

Support optional methods including:

- Preparation
- Stovetop
- Smoker
- Oven
- Grill
- Resting
- Holding
- Other

A dish can transition between methods while retaining a continuous cooking history.

For example:

Stovetop preparation → Smoker → Oven finish → Rest.

A method transition must not be interpreted as a completed dish or meal.

Preserve the existing `food_on`, `wrap`, `remove`, `rest_start`, `hold_start`, and `dish_done` milestone semantics.

A smoker placement remains a first-class action because it anchors smoking duration, chart comparison, controller context, and historical reminders.

Stovetop activity should not count as smoker usage.

Equipment-hour calculations must count only applicable intervals on the relevant smoker. They must union overlapping dish intervals so concurrent dishes don't double-count equipment time.

Existing legacy records should retain their historical timing semantics.

### 2.6 Preparation UX

The Live screen should not show irrelevant cooking timers as the dominant information when every dish is still being prepared.

The user must be able to record preparation notes and photos immediately.

An optional checklist can be opened, but must not be created or completed as a prerequisite to cooking.

Starting a smoker session is a simple per-dish action.

### Data and compatibility

Propose Room **8 → 9** and archive **7 → 8** if the new activity/timer records require persistence. Update Android and iOS codecs together.

### Primary implementation areas

- `domain/CookPreparation.kt`
- `domain/CookNote.kt`
- `domain/CookPhases.kt`
- `data/CookNoteRepository.kt`
- `data/CookPreparationRepository.kt`
- `ui/CookScreens.kt`
- `ui/CookNoteComposer.kt`
- `ui/CookPhaseCard.kt`
- `ui/CookPreparationTools.kt`
- `ui/CookDetailScreen.kt`
- Native iOS equivalents

### Acceptance criteria

- Create and start a simple one-dish meal without entering preparation details.
- Log pasta cooking for eight minutes without starting a timer.
- Alternatively, run an eight-minute timer and save its actual duration.
- Log multiple ingredients within one cheese-sauce note.
- Move a dish from stovetop preparation to a smoker without creating another meal.
- Track smoker time without counting unrelated stovetop activities.
- Log an oven finish without corrupting the dish's history.
- Preserve old notes, stages, and smoker equipment-hour behavior.
- Verify background/relaunch timer persistence, unit validation, and simultaneous activity timers.

**PR merge gate:** Preparation scenarios, lifecycle calculations, Room migration, backup compatibility, Android UI and iOS compatibility tests pass.

---

# PR 3 — Save, edit, duplicate and combine recipes

**Proposed title:** `feat: save independent dish recipes and compose meals`

**Depends on:** PRs 1 and 2.

### Purpose

Deliver the central user-facing feature: turning successful dishes into reusable recipes and combining recipes from different historical meals.

### 3.1 Save individual dishes

On a finished meal, each dish offers:

**Save as recipe**

Selecting it opens a lightweight review screen containing:
- Suggested recipe name.
- Existing ingredients and preparation details.
- Recorded historical milestones.
- Available notes and reference photos.
- Optional tags and description.

The user can edit the draft before saving.

Do not require them to complete missing information.

Saving one dish must never automatically save the other dishes in that meal.

The same Save as recipe action must be accessible later from a historical meal.

### 3.2 Historical extraction rules

When extracting an individual dish, include:

- Dish-owned ingredients.
- Dish-owned preparation notes.
- Notes and actions explicitly scoped to that dish.
- Shared multi-dish notes only where that dish is explicitly included in their scope.
- Relevant dish temperatures and targets as historical observations.
- Dish-owned photos or user-selected reference photos.
- Actual recorded timing milestones.
- Dish-specific outcomes and lessons.

Whole-meal activities such as general grill setup or ambient conditions may be shown as contextual history.

Do not silently copy ambiguous whole-meal entries into every saved recipe.

For example, a global "Changed smoker temperature" event should remain shared session context unless the user chooses to associate it with a particular recipe.

If the source contains only free-form notes, preserve those notes intact.

### 3.3 Saved Recipes library

Introduce a Saved Recipes section within existing cooking navigation rather than adding unnecessary bottom-navigation tabs.

Capabilities:

- Browse.
- Search by name.
- Filter by category, tag and favorites.
- Open recipe details.
- Review original and later versions.
- Edit.
- Duplicate.
- Archive.
- Add to a new or existing meal.

Display the preferred revision clearly.

A recipe's historical source must remain identifiable.

### 3.4 Full recipe editing

All saved recipe content must be editable through a dedicated editor, including:

- Recipe name and description.
- Ingredient names and quantities.
- Seasonings, brands and substitutions.
- Preparation instructions.
- Cooking methods.
- Target settings.
- Optional expected durations.
- Reference milestone descriptions and offsets.
- Photos and notes.
- Preferred serving amounts.

Edits produce an immutable new revision.

A duplicate creates a new saved recipe with its own independent revision history.

Example:

**Original:** Smoked Mac & Cheese  
**Duplicate:** Spicy Smoked Mac & Cheese

Changing the duplicate's ingredients cannot modify the original.

### 3.5 Updating after a better cook

After completing a new attempt originating from a saved recipe, provide:

- Keep existing recipe.
- Update saved recipe from this attempt.
- Save as a new recipe.

"Update" opens a reviewable draft using the latest recorded information, while retaining previous recipe content where the current cook did not provide replacements.

The user must approve the updated revision.

Do not infer that missing ingredient entries mean an ingredient was intentionally removed.

### 3.6 Build a meal from saved recipes

The user can select saved dishes independently from different prior meals.

Examples:
- Chicken from last week.
- Mac and cheese from two weeks ago.
- Pulled pork from a month ago.
- A new, unsaved side dish.

Each selected recipe is copied into a new dish attempt, linked to its exact source revision.

The new attempt gets its own:
- Notes.
- Photos.
- Temperature readings.
- Actions.
- Results.
- Timeline.
- Reminder settings.

The historical reference remains unchanged.

All loaded fields may be edited before or during cooking.

Where copied recipes suggest conflicting smoker temperatures, show a non-blocking conflict notice. Never silently change the real controller setting or overwrite another dish's preferences.

### 3.7 Optional whole-meal presets

Offer "Save meal combination" for users who regularly cook the same collection of dishes.

This saves recipe selections, order, optional serving goals and shared setup preferences.

It must not automatically convert unsaved dishes into individually saved recipes.

### Primary implementation areas

- `data/RecipeRepository.kt`
- `data/CookCompanionRepository.kt`
- `data/CookRepository.kt`
- `domain/RecipeSnapshotCodec.kt`
- `domain/CookPlaybook.kt`
- `ui/CookScreens.kt`
- `ui/CookDetailScreen.kt`
- `ui/PlaybookTools.kt`
- New Saved Recipes library and editor components
- iOS recipe library/editor and meal-composition views

### Acceptance criteria

- Save only two dishes from a completed three-dish meal.
- Independently extract a dish with preparation, photos, and recorded milestones.
- Duplicate a recipe and change its seasonings without changing the original.
- Update a saved recipe while retaining its previous revision.
- Assemble a new meal from three recipes originating in three separate meals.
- Add an entirely new dish to that combined meal.
- Edit a loaded recipe's setup without modifying its source.
- Create another attempt from an older recipe revision.
- Preserve active cooking history even if a saved recipe is edited or deleted.
- Verify recipe assets remain available if the original meal is deleted.
- Verify legacy playbooks remain usable.

**PR merge gate:** Full recipe CRUD, source isolation, mixed-meal creation, revisioning, backups and Android/iOS usability tests pass.

---

# PR 4 — Historical timelines, baselines and cross-attempt comparison

**Proposed title:** `feat: reuse individual dish timelines as cooking references`

**Depends on:** PR 3.

### Purpose

Allow users to see precisely how a saved dish was prepared previously and compare that history with their current attempt.

### 4.1 Reference timeline model

Every loaded recipe can carry an optional reference timeline.

Reference events store:
- Original event identity and provenance.
- Original action time.
- Relative time from the appropriate historical anchor.
- Original method or stage where available.
- Description and optional quantities.
- Recorded temperature context.
- Reference media.
- Scope and source revision.

For smoking milestones, the primary comparison anchor should be the individual dish's actual first relevant "On smoker" event.

Preparation notes occurring before that anchor can appear with negative relative time.

Where the source lacks an actual anchor, show chronological history without inventing relative timing.

Support separate historical heating intervals where a dish was removed and returned to heat.

### 4.2 Current versus previous timeline

The active dish should expose two clearly distinguished histories:

**Previous attempt:** Read-only reference.

**Current attempt:** Editable live record.

Do not insert previous events into the new timeline as if they happened again.

Possible presentation:
- Previous: Wrapped at +3h 20m.
- Current: Not wrapped yet.
- Previous: Removed at +6h 10m.
- Current: Cooking.

Users can refer to old timings without accepting them as mandatory instructions.

### 4.3 Visual comparison

Extend existing chart reference functionality to support:

- Selecting a historical attempt for an individual dish.
- Comparing compatible food-temperature channels.
- Comparing pit-temperature readings where scope is known.
- Showing important historical milestones.
- Showing historical temperature targets distinctly from current targets.
- Switching the reference attempt.
- Inspecting exact source readings.

Maintain correct Fahrenheit/Celsius conversion.

Never interpolate missing telemetry across connection gaps.

Never present historical data as live measurements.

If the source is incomplete, communicate which comparisons are unavailable.

### 4.4 Historical filtering

Users should be able to view the current meal's full timeline or focus on one dish.

Historical references should only show correctly scoped information.

Whole-meal context remains accessible separately.

Routine notes and dense telemetry should not overwhelm major actions.

Use timeline filters, grouped readings and selective expansion to keep the record readable.

### 4.5 Historical outcomes

Show each saved recipe's available attempt history:
- Date prepared.
- Recorded duration.
- Relevant smoker or cooking method.
- Rating when supplied.
- Important notes or adjustments.
- Whether it became the preferred recipe revision.

Do not invent a quality ranking if no rating exists.

### Primary implementation areas

- `domain/CookReference.kt`
- `ui/TemperatureChart.kt`
- `ui/CookDetailScreen.kt`
- `ui/CookReferenceTools.kt`
- `data/CookRepository.kt`
- `data/RecipeRepository.kt`
- `ios/PitTech/CookReference.swift`
- `ios/PitTech/CookTemperatureChart.swift`

### Acceptance criteria

- Compare today's chicken against last week's recorded chicken.
- Use a mac and cheese recipe extracted from a meal that also contained pork.
- Ensure the pork's private notes do not appear in the mac reference.
- Show original prep events relative to the real smoking start where available.
- Display reference actions without copying them into the active timeline.
- Preserve independent current and historical targets.
- Correctly handle Fahrenheit/Celsius, missing probes, renamed probes and recording gaps.
- Navigate from historical annotations to the correct source context.
- Preserve comparisons through backups and app upgrades.

**PR merge gate:** Timeline projection, event ownership, historical alignment, chart fidelity, media lookup and UI navigation tests pass.

---

# PR 5 — Unified user-controlled reminders and historical guidance

**Proposed title:** `feat: add flexible reminders using live and historical cook events`

**Depends on:** PR 4.

### Purpose

Provide a single, understandable reminder experience covering ordinary timers, temperature thresholds and milestones from saved recipes.

### 5.1 Reminder interface

Add a prominent **Reminders** action accessible from the meal and individual dish views.

Available reminder types:

1. After a duration.
2. At a specific clock time.
3. When a selected probe reaches or crosses a temperature.
4. Relative to a recorded current-cook action.
5. Relative to a historical milestone from a saved recipe.

All fields remain optional except those required for the reminder being created.

Users may add reminders at any point during a cook.

### 5.2 Unified reminder data contract

Introduce a versioned `ReminderRule` containing:

- `id`
- `cookId`
- `dishId`: optional
- `title`
- `kind`
- `triggerConfig`
- `sourceRecipeRevisionId`: optional
- `sourceMilestoneId`: optional
- `enabled`
- `status`
- `createdAtUtcMillis`
- `updatedAtUtcMillis`

Introduce a durable delivery/occurrence ledger for notification identity, acknowledgment, snoozing, cancellation and repeat suppression.

Trigger types should be explicitly modeled rather than represented through ambiguous labels.

The implementation should reuse or adapt existing local reminder, guidance and monitoring infrastructure instead of creating competing schedulers.

### 5.3 Duration reminders

Examples:

"Remind me to stir in 10 minutes."

"Remind me 30 minutes after this dish goes on the smoker."

"Remind me 15 minutes after wrapping."

Durations must anchor to the selected action's actual timestamp or the user's explicit creation time.

A delayed reminder must not silently reset its duration when the app restarts.

### 5.4 Probe-temperature reminders

Examples:

"Notify me when the pork reaches 165°F."

"Notify me when the pit falls below 200°F."

Allow the user to select:
- Measurement channel.
- Above or below threshold.
- Threshold value and unit.
- Optional sustained duration.
- Optional repeat behavior.

Evaluate only valid, sufficiently fresh measurements.

Reject stale data, invalid samples and incorrect probe assignments.

Apply suitable hysteresis and deduplication to avoid repetitive notifications near thresholds.

A food-temperature alert may not be satisfied by a pit-temperature channel.

When active monitoring is unavailable, show a clear waiting/unavailable status.

The application must not imply continuous monitoring in modes that only collect readings when the user logs something.

### 5.5 Historical milestone reminders

This is a central differentiating feature.

Example historical record:

Pulled pork was wrapped 3 hours 20 minutes after Food on.

When starting a new pulled pork attempt, offer:

"Last time, you wrapped at approximately +3h 20m. Remind me around then."

The user may:
- Enable the suggestion.
- Disable it.
- Move it earlier or later.
- Change its wording.
- Convert it to a temperature-based check.
- Remove it from this cook.

Historical milestone reminders anchor to the **new attempt's actual action timestamps**.

If Food on hasn't been recorded yet, the reminder waits.

If the user corrects Food on, future reminder times are recalculated.

The notification must be observational, for example:

"Last time you wrapped around this point. Check the bark."

It must not assert that wrapping is appropriate now or automatically log Wrap.

### 5.6 Guidance mode

Users may optionally enable guidance based on a saved recipe's historical milestones.

The next-action card appears only when:
- The user selected guided cooking, or
- An active reminder is due or upcoming.

Users can edit, skip, snooze or dismiss suggestions.

Completing a reminder does not automatically mean a physical action occurred.

If the user specifically confirms a physical action, save that action to the current dish's timeline exactly once.

A user can depart from the recipe at any time without being forced back onto the historical schedule.

### 5.7 Notifications and platform behavior

On Android:
- Reuse local scheduled notifications where possible.
- Reconcile schedules on reboot, app update and timezone changes.
- Respect notification permissions and system alarm restrictions.
- Use accepted controller telemetry without launching a second independent polling loop.
- Preserve reminder state across app restarts.
- Keep controller access read-only.

On iOS:
- Support locally scheduled time-based reminders within available OS capabilities.
- Preserve reminder configurations and historical references.
- Provide temperature-dependent reminder configuration only where compatible real monitoring exists.
- Do not claim Android controller-monitoring parity until an iOS monitoring implementation actually exists.

### 5.8 Backup and recovery

Propose Room **9 → 10** and archive **8 → 9** for new reminder configuration and occurrence records.

Imported reminder definitions should be preserved but restored inactive until explicitly enabled.

Never deliver stale historical reminders automatically following archive restoration.

### Primary implementation areas

- `domain/CookPlanEngine.kt`
- `domain/CookAlertEngine.kt`
- `data/CookAlertRepository.kt`
- `data/CookCompanionRepository.kt`
- `ui/CookGuidanceCard.kt`
- `ui/CookAlertTools.kt`
- `CookReminderNotifications.kt`
- `CookGuidanceNotifications.kt`
- `CookAlertNotifications.kt`
- New unified reminder rule/dispatcher components
- iOS local reminder and configuration equivalents

### Acceptance criteria

- A user can configure a ten-minute timer without creating a playbook.
- A 165°F probe alert uses the intended food channel.
- Invalid or stale telemetry cannot fire a temperature alert.
- A historical wrap reminder anchors to the current Food on timestamp.
- Changing the current Food on time updates future historical reminders.
- Editing a saved recipe does not change reminders already instantiated for an active cook.
- Skipping a historical suggestion creates no fake physical action.
- Confirming a physical action creates exactly one timeline event.
- Multiple dishes can have independent reminders.
- Snoozed, disabled and completed reminders do not produce duplicate notifications.
- Notification permissions, reboot, app updates and archive restoration behave correctly.

**PR merge gate:** Trigger-engine unit tests, notification reconciliation, sensor-quality handling, historical milestone tests, persistence, Android UI and applicable iOS tests pass.

---

# PR 6 — Adaptive Live experience, complete integration and release validation

**Proposed title:** `feat: integrate meal dashboard, saved recipes and guided cook flows`

**Depends on:** PRs 1–5.

### Purpose

Bring all the underlying capabilities together into a coherent experience while protecting the simple cooking workflow.

### 6.1 Main cooking navigation

Use two primary concepts:

**Meals**
- Active meals.
- Upcoming meals.
- Completed meal history.
- Create meal.

**Saved Recipes**
- Individually reusable dishes.
- Favorites and searches.
- Variations and revisions.
- Add to meal.

Retain Devices, Insights and Settings as appropriate.

Do not add unnecessary intermediate setup screens.

### 6.2 Starting a meal

A user must be able to:
- Start an empty meal.
- Create a single-dish meal.
- Add a new dish.
- Add one saved recipe.
- Combine multiple saved recipes.
- Load an optional saved meal preset.

The minimal action path should remain extremely short.

A user with a pork shoulder should be able to begin recording without opening any advanced configuration.

### 6.3 Adaptive Live dashboard

Show one card per dish.

Each card can display:
- Dish name.
- Current preparation/cooking stage.
- Cooking duration where known.
- Latest relevant temperature.
- Most recent important action.
- Next enabled reminder, if one exists.

Do not display hypothetical next actions for unguided cooks.

A tap opens dish-specific notes, temperatures, historical reference, and optional advanced tools.

Show shared smoker/device information at the meal level.

Preserve the established Live, Timeline and Charts experience.

### 6.4 Logging UX

Keep the existing Note, Temp and Photo quick actions.

A note must support:
- Free-form text.
- Optional photographs.
- Optional ingredients and quantities.
- Optional cooking method and duration.
- Optional temperatures.
- Optional dish association.
- Actual event time.

Avoid creating extra timeline entries for every field within one note.

Maintain compact summaries for routine entries and expand on demand.

### 6.5 Finishing a meal

Finish Meal must be reliable from normal active and paused states.

If some dishes have no completed physical milestones, allow the user to finish with an appropriate warning rather than inventing actions.

After completion, offer:
- View meal summary.
- Save selected dishes as recipes.
- Update existing recipes.
- Save a meal preset.
- Leave without saving any recipe.

Recipe-saving prompts must be dismissible.

A completed meal remains accessible and editable where appropriate, including retrospective notes and corrections.

### 6.6 Historical comparison presentation

For reused dishes, expose:

- Current attempt.
- Historical baseline.
- Previous results.
- Recipe modifications.
- Optional guidance and reminders.

Reference information must not crowd the default Live dashboard.

Use clear labels such as "Previous cook" and "This cook."

### 6.7 iOS parity and accessibility

Provide equivalent core navigation and workflows on iOS:
- Meals.
- Saved Recipes.
- Recipe revisions.
- Individual dish extraction.
- Meal composition.
- Preparation logging.
- Historical reference viewing.
- Local time-based reminders.

Ensure readable layouts at large text settings, suitable touch targets, accessible labels, and correct dark/light appearance.

Explicitly identify platform-dependent monitoring capabilities.

### 6.8 Release and regression validation

Complete a repository-wide verification pass, including:

- Android unit tests.
- Room migration and archive tests.
- Android instrumentation/UI tests.
- Required emulator API and enlarged-font configurations.
- Android debug and release compilation.
- Play bundle build.
- Live recording/app-update recovery.
- Native iOS simulator build and tests.
- Cross-platform portable archive compatibility.
- Accessibility checks.
- User-flow screenshot review.

Physical controller and extended background monitoring checks must be reported separately if hardware testing isn't available.

Do not claim those tests passed merely because emulator tests succeeded.

### Acceptance criteria

- Start a meal with one dish without a lengthy form.
- Prepare mac and cheese on the stove, smoke it, and retain one continuous history.
- Finish a three-dish meal and save only selected dishes.
- Combine chicken, mac and cheese and pork recipes from three different meals.
- Edit one loaded dish while all saved source recipes remain unchanged.
- Enable historical guidance for one dish while leaving another completely unguided.
- Continue logging even when no saved recipes or reminders exist.
- Finish a meal without requiring a rating, review, or recipe save.
- Upgrade an installed application containing old cooking history and active recording data without losing records.
- Import/export the new archive format between compatible Android and iOS builds.
- Retain all existing sensor, photo and cooking-history ownership relationships.

**PR merge gate:** Complete integration matrix passes, release builds succeed, user flows are verified, and no unresolved destructive migration or major usability regressions remain.

---

# B. Pull request dependency and delivery strategy

| PR | Primary responsibility | Expected persistence change |
|---|---|---|
| 1 | Data foundation and recipe ownership | Room v8 / Archive v7 |
| 2 | Preparation and cooking methods | Room v9 / Archive v8 |
| 3 | Recipe saving, editing and meal composition | Uses established v9 model |
| 4 | Historical references and comparison | Primarily domain/UI |
| 5 | Reminder and historical guidance engine | Room v10 / Archive v9 |
| 6 | Integrated UX and release validation | No planned schema change |

Versions above are proposed implementation targets, starting from the verified Room v7 and archive v6 baseline. Additional schema changes discovered during implementation must receive explicit migrations and compatibility tests rather than bypassing versioning.

PRs should be merged sequentially after successful validation. Later PRs must incorporate any integration changes required by earlier merged work.

Do not defer all testing to PR 6.

---

# C. Cross-PR engineering constraints

### Source ownership

Historical cook records are immutable with respect to recipe reuse. Legitimate direct edits to an old cook remain possible through existing editing tools, but those edits must not unexpectedly rewrite a previously saved recipe revision.

### Atomicity

Saving or updating a recipe and its reference assets must either complete successfully or leave the prior state unchanged. Incomplete photo copies, broken references and partial revision records must not be committed.

### Archive safety

Archive imports must validate record ownership, duplicate IDs, payload versions, relationship integrity, and attachment paths before committing records.

Invalid imports must not corrupt the existing library.

### Historical accuracy

Relative timing uses recorded timestamps, not estimates disguised as measurements. Missing actions remain missing. Inferred recipe suggestions must be clearly distinguished from recorded cooking history.

### Reminders

Historical reminder suggestions require explicit user activation. Current-cook reminder configuration remains independent of the saved recipe after instantiation.

### Recording isolation

Recipe operations and preparation UI changes must not reset, duplicate, stop, or accidentally start controller recording.

Existing recording recovery behavior must remain intact.

### Simplicity

All optional detail must remain optional. Additional metadata and advanced settings should not increase the required inputs for a basic cook.

---

# D. Required end-to-end acceptance scenarios

### Scenario 1 — Simple pork shoulder

Start a meal, add pork shoulder, log Food on, record several temperatures, attach a photo, remove the pork, finish the meal.

**Pass:** No recipe plan, reminder, structured ingredient list or checklist was required.

### Scenario 2 — Smoked mac and cheese

Start a meal, record eight-minute pasta cooking, document cheese-sauce ingredients in one note, move the dish onto the smoker, stir during smoking, add a topping, remove and finish.

**Pass:** Preparation and smoking remain associated with the same dish. Smoking duration does not include pasta-boiling time.

### Scenario 3 — Multi-dish extraction

Finish a meal containing pork, chicken and mac and cheese. Save only the chicken and mac and cheese.

**Pass:** Two saved recipes are created. All three dishes remain in meal history.

### Scenario 4 — Mix recipes from different dates

Create a new meal with chicken from one historical meal, mac from another and pork from a third.

**Pass:** One active meal contains three independent dish attempts with correct source-revision relationships.

### Scenario 5 — Recipe variation

Duplicate the mac and cheese recipe. Increase Gouda, add jalapeños and rename it Spicy Mac.

**Pass:** Original and duplicate can both be edited independently. Neither historical meal changes.

### Scenario 6 — Better second attempt

Cook pulled pork from an existing recipe and produce a better result. Choose Update saved recipe.

**Pass:** A new preferred revision is created; the previous revision and both actual cook histories remain intact.

### Scenario 7 — Historical wrap reminder

Reuse pulled pork whose previous wrap was recorded at +3h 20m. Enable a reminder based on that event.

**Pass:** Reminder timing follows the new Food on event. Notification is advisory and never automatically records Wrap.

### Scenario 8 — Temperature-based reminder

Enable a food-probe threshold notification.

**Pass:** Only correctly attributed, fresh, valid probe measurements can satisfy the rule; unsupported monitoring states are disclosed.

### Scenario 9 — Interrupted active meal

With multiple active dishes, notes, reminders and controller recording, update/restart the Android application.

**Pass:** Cooking history, timers, recipe provenance and eligible recording recovery remain intact. No duplicate events or reminders.

### Scenario 10 — Legacy upgrade and cross-platform backup

Restore an older PitTech archive, make a meal with saved recipes, then export the new archive and import it into a compatible platform build.

**Pass:** All supported records remain accessible, correctly related and free of unintended duplication. Reminders are restored inactive, and controller credentials or sessions are not improperly transferred.

---

# E. Definition of done for the complete initiative

The six-PR initiative is complete only when:

1. A meal is the primary user-facing cooking session.
2. Multiple independent dishes can coexist within a meal.
3. Users explicitly control which dishes become saved recipes.
4. Saved recipes are independently editable, duplicable and versioned.
5. Historical recipes can be combined across unrelated meals.
6. Each new attempt preserves its own actual history.
7. Preparation and non-smoker cooking activities are supported without mandatory setup.
8. The saved reference timeline is available separately from current activity.
9. Historical guidance and reminders are fully user-controlled.
10. Core features operate offline without AI or account dependencies.
11. Existing cook data, media, recording histories and supported archives survive migrations.
12. Required Android and iOS tests and builds pass.
13. No outstanding critical bugs remain in starting, logging, finishing, saving or reusing meals.

**Final design principle:**

PitTech should require almost nothing from users to record a cook, yet allow everything they record to become useful later.

The more someone cooks, the more valuable their personal recipe library, historical comparisons and optional guidance become.