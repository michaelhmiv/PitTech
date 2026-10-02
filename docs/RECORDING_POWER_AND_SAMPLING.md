# Android recording power and sampling

PitTech Dev offers a one-minute default, 15-second, 30-second, two-minute and five-minute intervals, plus **When I log something**. This preference applies across the three existing read-only providers and is saved per cook. A legacy recording migrated from schema 4 keeps its original 15-second cadence.

## Collection work, not battery percentages

| Selection | Nominal collection cycles/hour | Cycles during 3 hours | Reduction from 15 seconds |
| --- | ---: | ---: | ---: |
| 15 seconds | 240 | 720 | 0% |
| 30 seconds | 120 | 360 | 50% |
| 1 minute (default) | 60 | 180 | 75% |
| 2 minutes | 30 | 90 | 87.5% |
| 5 minutes | 12 | 36 | 95% |
| When I log something | Depends on entries | No scheduled cycles | Depends on usage |

These calculations exclude request duration, failures, discovery and session renewal. The implementation waits the selected interval **after** a read completes; actual cadence is longer by request duration. One collection cycle is not one network request: GrillirG normally reads both status and temperatures. Pit Boss uses an observational WebSocket exchange. Traeger uses an MQTT subscription whose receiver continues processing status updates between scheduled observations; a slower observation interval does not throttle the grill's MQTT publication rate. Failures back off without shortening the selected interval, and a larger server Retry-After is honored. On-demand actions also honor a rate-limit pause without scheduling a retry.

Fewer cycles reduce some network and database work. They are not equivalent to a measured percentage improvement in battery life. Cell signal, Wi-Fi, incoming publications, TLS reconnects, screen/camera use and CPU awake time also matter. A faster cadence gives denser history, not guaranteed faster sensor updates. A five-minute schedule can observe a change almost five minutes later; no safety alerts or control actions depend on it.

## Actual Android controls

Timed recording uses the existing explicitly started foreground service, with an ongoing low-priority recording notification and a Pause action. Its screen-off CPU wake lock is renewed with a timeout while the cook records and released on service destruction. **Renewing that lease continuously keeps the CPU awake for the cook.** Changing the polling interval alone does not eliminate that cost. The app discloses it; this change does not claim an overnight battery measurement or lower CPU duty cycle.

The setup and Live panel expose user-initiated Android controls directly:

- `PowerManager.isIgnoringBatteryOptimizations` and `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: status plus the real Android approval dialog for screen-off access. The app cannot grant itself the exemption.
- Notification permission, app-wide notification enablement and the actual cook-recording channel's importance: show permission and system-settings buttons when hidden, including after a denial or muted channel.
- `PowerManager.isPowerSaveMode`: Battery Saver status and a system-settings shortcut while enabled.
- `ActivityManager.isBackgroundRestricted`: the app settings shortcut when Android reports restricted background use.

Status refreshes on returning to the app and when Battery Saver changes. Vendor-specific power controls, stopping the app and network outages remain outside these permissions' guarantees. The notification helps expose active work; it does not itself bypass Doze.

## Logging-only behavior

Once the provider is connected, **When I log something** takes a bounded one-shot observation for a new note, quick action, manual temperature entry, photo entry/attachment or reminder check-in. **Read grill now** and the Devices Refresh button are explicit extra reads. A valid observation from the past 15 seconds can be reused to avoid duplicate camera/save or rapid-entry requests. There is no scheduled read, retry timer, recording foreground service, recording notification or recording wake lock between actions. Live sockets close after discovery/observation, including failure, timeout or cancellation. Switching from timed to logging-only stops the service while retaining the cook's provider/grill binding.

The camera path requests the observation before launching the external camera. New readings can only contextualize entries/photos at or after their receipt time; the app never puts a later query onto an earlier photo or explicitly backdated note. A recent preceding observation can be shown with its actual timestamp. A long camera interaction, unknown gallery date or unavailable read can leave the photo without temperatures. Log saving remains available when the optional read fails. Edits/deletes do not initiate new observations.

This mode is expected to use the least **idle** battery of the offered choices because it removes background collection and its wake lock, but frequent user actions and reconnects still cost power. It intentionally produces sparse points rather than a continuous temperature curve. The user's manufacturer app does not have to remain open; provider/cloud connectivity must still work.

## Honest history and data compatibility

Each reading retains its interval; `0` marks an on-log snapshot. Graphs split at real scheduled gaps, unavailable channels, probe moves and every on-log snapshot. A scheduled receipt remains recent for the selected interval plus 30 seconds (at least 45 seconds); log-only context expires after 15 seconds. Native device timestamps remain subject to the original 45-second age check **at receipt**, so slower polling cannot make an old device report valid. UI context shows when cloud values were received. The live-only adapters cannot backfill periods with no observations.

Room schema 5 migrates schema 4 without deleting data and gives older readings/recordings the legacy 15-second policy. ZIP archive schema 4 includes the mode/interval in portable JSON, CSV and workbook outputs. Schemas 1–3 remain importable with legacy defaults. Restored recording history remains stopped and does not include remote controller identifiers or authentication credentials.

## Validation and remaining measurements

JVM checks cover schedules, cadence-aware freshness/graphs, native-age checks, no idle on-log work, connection cleanup, cancellation, retry limits and schedule changes. Android checks cover recording/photo contexts, schema 3→5 and 4→5 migrations, archive round trips, timed background ownership and UI transitions into/out of logging-only, including a failed read that still saves the entry. Existing app flows, data-preserving APK upgrade and controller tests remain required CI gates.

Before promotion beyond Dev, perform a physical overnight run and controlled screen-off/Doze/network-loss tests, comparing 15 seconds, one minute, five minutes and logging-only on Wi-Fi/cellular with a recorded number of entries. Use Android Batterystats to compare actual radio/CPU time and battery use. No physical grill or battery measurements are claimed by synthetic tests. The Dev-only `specialUse` foreground service declaration and direct power-exemption use require production policy review; the regular Play build still excludes controller recording.

## Android primary sources

- [Foreground services](https://developer.android.com/develop/background-work/services/fgs): user-visible background work and notification requirements.
- [Doze, standby, exemptions and test commands](https://developer.android.com/training/monitoring-device-state/doze-standby): network/CPU restrictions, remaining limits and user-approved exemptions.
- [Notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission): without permission, an FGS notice may appear only in Task Manager.
- [WorkManager periodic requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work): 15-minute minimum and inexact timing, unsuitable for the requested sub-15-minute live cadence.
- [Wake-lock best practices](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/best-practices): minimize duration and always release locks.
- [Excessive partial wake locks](https://developer.android.com/topic/performance/vitals/excessive-wakelock): extended background/FGS locks can affect Android vitals; a timed cook needs measurement, not an assumption of exemption.
- [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types) and [timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout): appropriate declaration and the Android 15+ dataSync six-hour background allowance.
- [User stopping an FGS](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping): the user can stop the app; the app must respect it.
