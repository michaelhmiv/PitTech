# Meals and saved recipes: integration validation

The six layers are delivered sequentially. The Live, Timeline and Charts views remain available; Meals and Saved Recipes are primary destinations. Starts can contain no dish, one new dish, saved recipes from different meals or an optional meal combination. Advanced fields are optional.

Dish cards display recorded stage/method, known cooking time, relevant temperature, recent action and the next enabled reminder. Historical data opens for the selected dish. Unguided dishes have no hypothetical next action. Completed/paused meals finish without fabricated physical milestones or mandatory ratings. The completion choices allow summary viewing, explicit selected-dish recipe saving/updating, a meal combination, or leaving without saving.

Android flow coverage combines Chicken, Mac and cheese and Pork from three independent meals, enables a historical check before current Food on, changes an attempt without rewriting its sources, finishes from paused with missing milestones and saves only the selected dish. Existing tests retain note/photo/temperature identity, migration, backup, recording restart and installed-APK update checks at API 36 with font scales 1.15 and 1.5. API 37 remains the existing advisory lane.

Native coverage includes recipe/version/media ownership, general preparation and timer notes, independent attempts, local reminder timing/persistence, inactive import, scoped references and chart identity/unit/gap behavior. Two native UI flows cover composition/historical activation/optional finishing and a simple empty-meal start at enlarged accessibility text. App appearance follows system/light/dark settings. Simulator screenshots and UI attachments are reviewed separately from compiler success.

Portable fixture verification uses actual native and Android writers/readers and checks ingredients, measured duration, original versus current notes, Celsius measurements, independent photo assets, pinned sources, reminder configurations and inactive restores. Fixture results must be recorded after both readers run successfully.

| Gate | Requirement |
| --- | --- |
| JVM/domain | All existing and new tests pass |
| Room migration | Real generated schemas open and validate through v10 |
| Android UI | Both required API 36/text-size jobs pass |
| Recording upgrade | Old installed APK history/active recording survives update |
| Native | Simulator build, unit tests and UI flows pass |
| Portable archive | Both platform readers accept opposite-platform fixtures |
| Release | Android release/dev builds and Play bundle pass |
| Visual QA | User-flow screenshots reviewed for light/dark and enlarged text |
| Hardware | Physical controller and extended background delivery reported separately |

Hardware-dependent checks cannot be inferred from emulator or simulator results. Distribution and signed App Store builds are separate from source merge validation.
