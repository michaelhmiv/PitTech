# Guided cooking

Live keeps elapsed time and status above one Next action card. View plan opens detailed steps, per-dish stages, relevant reference photos, comparison, checklist and reminder controls. Done records the selected physical action once; Snooze affects one occurrence and rearms delivery; Skip records guidance progress only. A quick action satisfies one matching pending step per affected dish atomically. A Remove action offers Start rest. Per-dish stages never finish the whole cook automatically.

Elapsed checks anchor to Food on, not cook setup creation. After-action checks follow the latest actual action timestamp. Recurrences start from the actual confirmation and consolidate missed occurrences into a single current check. Timeline corrections recalculate future due times without erasing completion history. A skipped anchor remains waiting unless the user explicitly chooses Use now for later steps.

Guidance pause is separate from recording and physical timers. Rest/hold checks must be completed or skipped before finishing a guided cook. Restored plans start paused.

Local one-shot alarms are inexact by default. The plan offers an optional precise-timer switch through SCHEDULE_EXACT_ALARM and checks capability before scheduling, with a SecurityException fallback. Relative checks use elapsed-realtime alarms; calendar reminders retain explicit dates and use calendar alarms. Boot, app replacement and clock/timezone changes reconcile persisted steps. Stable notification identities and occurrence ledgers prevent repeated confirmation/delivery. Notifications offer Done and Snooze. Permission is requested only from reminder controls. Reminder and monitoring channel settings are independent.

Steps support preparation as well as cooking/rest/hold phases and optional named food probes. Pit readings cannot satisfy food-temperature steps. The editor rejects circular action dependencies. Starting a reused playbook requires review of past calendar reminders. Deleting a dish detaches its steps and alerts and preserves its historical actions without turning them into whole-cook anchors; deleting a cook cancels its pending notifications.

No new network polling loop is used. Local state observation evaluates accepted readings and actual events while the application or existing recording service is alive. Android may delay inexact reminders; physical controller and overnight delivery tests require devices and are not implied by emulator results.
