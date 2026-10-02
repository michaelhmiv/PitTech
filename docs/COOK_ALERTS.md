# Cook alerts

Alerts live in a collapsed section on Live and are opt-in. Rules cover food targets (dish/stage/probe scoped), sustained pit ranges, missing accepted readings and a fresh service-reported offline status. Reaching a target never marks food done. Manual readings evaluate when entered; unattended alerts require an attached grill with timed recording. Logging-only collection offers an explicit switch to timed monitoring, preserving the chosen cadence.

Rules normalize units, reject invalid/stale readings, reset dwell after gaps, and recover with a margin. Pit alarms offer ten-minute lid-open suppression. Acknowledge stops repeats while keeping the unresolved condition visible. Snooze delays delivery without claiming recovery. Episodes deliver at most three alerts, fifteen minutes apart; recovery re-arms. Rest/removal deactivates cooking-stage targets; hold targets must be explicitly scoped to holding.

Monitoring alerts have their own Android channel, distinct from plan reminders and quiet recording. Contextual permission, a test alert and OS notification settings are provided. The ongoing recording notification includes accepted values, receipt age and next planned action. Recent cloud receipt with unknown sensor age is identified explicitly.

Accepted-reading persistence and the existing recording service drive evaluations. Missing-read alarms inspect persisted receipt deadlines and do not fetch the grill. Restored alerts are disabled and have no live incident state. Controller integrations retain their existing Dev/debug gates.
