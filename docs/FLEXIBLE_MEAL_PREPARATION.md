# Flexible meal preparation

Room 8→9 adds activity timers and preserves original ingredient quantity text beside its numeric value. Archive v8 carries those records between Android and iOS; restored timers remain inactive. Existing v1 notes keep their contract. Notes with optional methods, duration, actual start/end, or structured ingredients use metadata v2, with both readers validating it.

A task can be described without a timer or structured ingredients. Timers store absolute anchors, overlap independently, and offer a single ordinary note when stopped. The note identity is stable through retries; saving marks the timer logged atomically. Dismissal creates no cooking action. Finishing cancels running timers without inventing stopped durations.

Food on remains an actual smoker action. Stovetop/oven/method notes do not complete dishes or reset recording. Equipment hours subdivide physical heating intervals using explicit method changes, count only smoker intervals, and union overlapping dishes. Legacy records without methods retain their existing calculations.

Validation covers metadata compatibility, quantity precision, measured-duration consistency, method-dependent smoker hours, overlapping timers across database reopen, retry identity, inactive restoration, and native persistence. The real Android note form logs an eight-minute stovetop task in one note without recipe setup or ingredient parsing. CI additionally exercises all existing migration, recording, backup, and user flows at both required text sizes.
