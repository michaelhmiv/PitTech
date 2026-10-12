# User-controlled meal reminders

Room 9→10 and portable archive 8→9 introduce versioned reminder rules and a durable occurrence ledger. Rules have explicit duration, absolute clock, recorded action, frozen historical offset, or selected-probe triggers. Sequence zero persists evaluation state; later records retain scheduled/delivered notifications, acknowledgment, snoozing and an optional separately confirmed physical action identity.

Historical suggestions require explicit activation. Their words, offset and source identifiers are copied into the current meal's rule. Future due times use that meal's actual action and recalculate when its timestamp changes; saved recipe edits cannot change an existing rule. Skipping and dismissing create no timeline action. Explicit confirmation uses a stable event identity and is idempotent.

Android uses the existing guidance scheduler/receiver and accepted recording stream. It adds no controller polling or writes. Probe identity, measurement type, current assignment, freshness, quality, sensor age, continuity, sustain, hysteresis and repeat suppression are checked. Manual/on-log modes only evaluate recorded samples, and cannot establish sustained monitoring. Time wakes reconcile on startup, reboot, package replacement and clock/timezone changes. Notification permissions and optional precise-alarm access remain honored.

Native iOS schedules local time notifications with stable request identities and retains rule/occurrence history. Temperature rules from Android are preserved without pretending that native continuous monitoring exists. Removed, disabled, completed and finished-meal rules cancel pending notifications. Imported definitions restore inactive, retaining earlier receipts without arming old schedules.

Validation: trigger-engine unit tests; real generated-schema 9→10 open/validation; reopen/inactive/idempotent-action integration tests; full existing recording/upgrade gates; native timing, schedule persistence, unsupported configuration and portable archive tests. Emulator tests do not establish physical controller or extended background delivery reliability.
