# Android portable writer fixture

`android-meal-v9.zip` comes from the Android repository/data-transfer writer in `CrossPlatformArchiveTest`. The emulator first reads the actual native v8/v9 archives, then writes this archive with precise ingredients, one measured-duration note, Celsius readings/targets, independently owned photos, pinned sources and reminder definitions. All records are synthetic.

Writer commit: `a4f986cea0266da2f6f6882553230ceb29eb4f63`. Android workflow: `38314819245`, API 36/font 1.5 artifact: `11777290230`. The complete ten-test recipe/archive suite passed, including both cross-platform methods. Later UI tests in that job failed; those failures are covered by the final PR checks. The ZIP is copied byte for byte from `build/ci-emulator/portable-fixtures/android-meal-v9.zip`.

SHA-256: `da7c2df93280e47d7a9dae9445c2f323cd48e7d7d4332a54655f9d2f779f054d`.
