# PitTech UI/UX Plan

## 1. Experience goal

PitTech should feel like a clear, dependable cook notebook with live grill data attached. A user should be able to start a cook, see what is happening, add a note or event, and find the information later without learning a complicated system.

Design for confident everyday use:

- Use plain language and familiar Android patterns.
- Keep the most important action visible on every cook screen.
- Make temperatures and connection state readable at a glance.
- Preserve the full record while keeping the timeline scannable.
- Keep manual and automatic data together, with the source always visible.
- Let users correct and remove entries without creating duplicates or silently changing other data.
- Keep the local cook library usable without an account or internet connection.

The supplied monochrome smoker logo sets the brand tone. The interface should look practical and established: charcoal, white or warm paper surfaces, and one restrained ember accent. Avoid neon colors, glowing gradients, decorative motion, tiny icon-only controls, and jargon.

## 2. Information architecture

### Phone navigation

Use a four-destination Material 3 navigation bar:

1. **Cooks** — active cook, recent cooks, and Start cook.
2. **Insights** — comparisons and patterns across the cook library.
3. **Devices** — grill and logger connections, probe names, pairing.
4. **Settings** — units, notifications, export/import, appearance, privacy, paired-device management.

A navigation bar with four labeled destinations fits Material 3 guidance for compact phone widths. Keep labels visible; icons support the label rather than replace it.

### Cook navigation

Every cook has the same three destinations at the top of its detail screen:

- **Live**
- **Timeline**
- **Charts**

This keeps the data for one cook together. Insights is the place for comparisons across several cooks.

## 3. Screen plans

### A. Cooks home

**Purpose:** Start quickly and get back to an active or previous cook.

**Layout:**

1. Small PitTech mark and page title.
2. Large **Start cook** button.
3. Active cook card, if one exists:
   - cook name or simple default such as “Saturday cook”
   - elapsed time
   - dish names
   - grill connection and last update
   - prominent **Open cook** action
4. Recent cooks list, newest first:
   - date
   - dish names
   - duration
   - result/photo if available
   - one short note or outcome

**Empty state:** Show one sentence explaining that a cook can be logged with or without a connected grill, then a clear Start cook button. Do not lead with account creation, a tutorial carousel, or a device setup requirement.

**Start cook flow:**

- Offer **Start now** as the fastest path.
- Let the user add one or more dishes, optional name, device, and starting setpoint.
- Allow setup to be completed later.
- Provide a date/time field for backdated cooks and preparation events.
- Ask for units once in Settings; allow changing them without losing stored values.

### B. Live cook

**Purpose:** Answer “Is the grill connected, and what are the temperatures right now?”

**Header:**

- Cook name and elapsed time.
- A plainly labeled status such as **At home · Connected**, **Remote · Live**, **Reconnecting**, or **Last update 4:18 PM**.
- A visible connection detail action. Avoid a green dot as the only status indicator.

**Grill card:**

- Controller name and connection mode.
- Current pit temperature and setpoint as separate values.
- Pellet level or other fields only when the controller actually provides them.
- **Adjust setpoint** action only when supported by the connected device.

**Dish/probe cards:**

- One card per assigned food probe.
- Large current temperature.
- Dish name and probe name.
- Optional target temperature.
- A small direction/trend cue and last-reading age.
- Source label when the reading is from another logger or manual entry.

On small screens, use a vertical list rather than shrinking four or more probes into tiny tiles. Allow a user to reorder probes and pin the most important dish first.

**Quick actions:**

- **Log event**
- **Add note**
- **Add photo**

Keep these labels visible and easy to tap. The log-event action should open common choices first, with custom entry available below them.

**Setpoint control behavior:**

- Open a simple control panel showing current setpoint, requested setpoint, and supported range.
- Offer large plus/minus steps and a numeric entry option.
- Require an explicit **Send change** action.
- Display **Sending**, then **Confirmed** or **Couldn’t reach grill** based on the controller response.
- Never update the displayed actual setpoint optimistically before confirmation.

**Connection states:**

- **Live:** show the current reading and its age.
- **Reconnecting:** retain the last value but label it as the last received value.
- **Disconnected:** show when it disconnected and offer **Reconnect**.
- **Remote service unavailable:** distinguish the relay problem from a grill/controller problem.
- **Command failed:** keep the actual setting visible and explain that the grill did not confirm the change.

### C. Cook timeline

**Purpose:** Be the complete, editable story of the cook.

**Structure:**

- A single chronological scroll from cook start through finish.
- Date headings when a cook spans multiple dates.
- Event cards ordered by when the event happened, not when it was entered.
- A clear **+ Add entry** action that stays available while scrolling.
- A **Jump to latest** control when new data arrives while the user is reading earlier entries.

**Entry types:**

- **Your event:** spritzed, wrapped, flipped, removed, rested, custom action.
- **Note:** free text, optionally attached to one or more dishes.
- **Photo:** image plus optional caption and dish association.
- **Temperature check:** manually entered value with probe/dish and time.
- **Controller event:** setpoint changed, connected, disconnected, reconnected, or a supported alert.
- **Sensor interval:** compact summary of dense readings, expandable to inspect the samples in that interval.
- **PitTech marker:** a derived event such as a detected stall, labeled as an analysis rather than a controller fact.

Every entry shows its time and source, for example **You**, **Austin XL**, or **PitTech analysis**. Source labels use both text and a small consistent icon.

**Automatic telemetry presentation:**

- Save every valid controller/logger sample at its original timestamp and source.
- Do not create a full-height timeline card for every sample.
- Group dense samples into short time windows, initially five minutes, with a min/max/last summary and a small sparkline.
- Tapping a group expands the readings; **View on chart** jumps to the corresponding interval.
- Important state changes such as connection loss, setpoint change, or a target crossing appear as individual entries.
- Provide an **All entries / Events only** filter. Events only hides dense data blocks from view; it never deletes them.

**Add entry flow:**

Open one bottom sheet titled **Add to timeline**, with large labeled choices:

- Event
- Note
- Photo
- Temperature

For an event, show common smoker actions as text buttons: **Meat on**, **Spritz**, **Wrap**, **Flip**, **Remove**, **Rest**, **Finish**, **Custom**. Then show time, dish selection, and optional note. Default time is now; entering a past time is a deliberate, visible control.

**Edit and delete:**

- Tapping a card opens its details. **Edit** and **Delete** are visible actions, not swipe-only gestures.
- Editing can change event type, text, dish association, photo, and occurred time.
- Store occurred time separately from the time the entry was recorded. Editing an old event reorders that event only.
- Deleting removes the entry from the timeline and recalculates dependent chart annotations; show **Undo** immediately.
- Allow deletion/correction of automatic samples or a selected time range, with a clear explanation that those samples will no longer contribute to analysis.
- Saving an edit updates the existing card in place. Do not add a second card, duplicate a timeline section, or show multiple redundant confirmation panels.

### D. Charts

**Purpose:** Let a user understand the temperature behavior of this cook.

**Default chart:**

- For an active cook, show the recent period with a clear **Last hour / Whole cook / Custom range** selector.
- For a completed cook, show the full session.
- Show pit temperature, food-probe series, and setpoint as separately named lines.
- Add timeline event markers for events such as wrap, spritz, and setpoint adjustment.
- Keep line labels and units visible. Do not rely on color alone to identify a probe.

**Exploration controls:**

- Select/deselect probes with labeled controls.
- Tap or drag across the chart to inspect time, temperature, and source.
- Provide zoom controls as well as pinch gestures.
- **Show readings** opens a tabular view for exact samples.
- **Jump to event** selects a timeline item and moves the chart to that time.

**Below the chart:**

- Pit stability: measured range and deviation from setpoint.
- Per-probe min/max, rate of rise, and detected slow-rise/stall intervals.
- Time above or below user-selected targets, when the target is defined.
- Data quality summary: gaps, stale stretches, sensor changes, and manual measurements.
- Link each calculated value back to its chart interval or source readings.

Do not smooth or fill missing readings in a way that looks like measured data. Show gaps visibly. Any future finish estimate should be labeled as an estimate, include a range, and explain which data informed it.

### E. Insights

**Purpose:** Compare cooks and help a user learn from their history.

Start with a simple cook list and comparison flow:

1. Select two or more completed cooks.
2. Confirm which dishes/probes to compare.
3. Compare temperature curves, duration, pit stability, settings, notes, and outcomes.

Only compare fields that were recorded for both cooks. Label differences in weight, starting condition, grill, or method; do not imply causation from a simple comparison. Let users open the underlying cook from any chart or result.

### F. Devices

**Purpose:** Connect hardware and make sensor identity understandable.

Use a guided sequence:

1. **Choose device type** — grill controller or probe/logger.
2. **Find device** — explain whether the phone needs Bluetooth, local Wi-Fi, or both.
3. **Connect** — show a clear progress state and a plain-language recovery step if it fails.
4. **Name probes** — “Probe 1” can become “Pork loin”; assign it to a dish.
5. **Confirm readings** — show live values and ask the user to verify probe labels.

Show supported capabilities explicitly: read temperatures, report setpoint, change setpoint, report connection state, or remote access. Do not display controls that the adapter has not verified.

**Remote pairing with PIN:**

- Enable remote access while the grill is reachable.
- Generate a one-time pairing PIN for authorizing another PitTech device.
- Explain that the PIN expires after a short time and can only be used once.
- The entering device gets a plain confirmation screen naming the grill being paired.
- Provide a list of paired devices with **Remove access**.
- Use attempt limits and secure network transport. A PIN is for pairing, not a reusable login credential.

### G. Settings and data

Keep these items in one plain Settings list:

- Temperature units.
- Theme and text display.
- Alert choices.
- Paired devices.
- Export this cook.
- Export all PitTech data.
- Import/restore backup.
- Storage use and local data controls.
- Privacy and remote-data explanation.

Export should produce readable CSV for temperature samples and a versioned PitTech archive for complete cooks, events, notes, and attachments. Import should preview what will be added before changing the local library.

## 4. Visual and interaction system

- Use the supplied black-and-white mark; do not place large decorative branding inside cook screens.
- Keep surfaces simple and consistent. Use a restrained ember accent for emphasis, not as a glow effect.
- Use a clear type hierarchy: page title, section title, large temperature, supporting status/time.
- Support the system text size setting without clipping values or hiding actions.
- Use visible labels beside icons for actions that are not universally recognizable.
- Make all key controls at least 48 dp; make primary cook actions larger where practical.
- Use words, icons, and shapes in addition to color for probe identity and alarms.
- Provide a light/high-contrast appearance for bright outdoor use and a dark appearance for night cooking.
- Keep animation minimal and purposeful: connection transitions and chart inspection only.
- Provide immediate, single confirmation feedback for saves. Avoid redundant pop-ups.

## 5. Alerts and background monitoring

- Ask for notification permission when the user turns on cook alerts or background monitoring, with a short explanation of the benefit.
- Separate important temperature/disconnection alerts from lower-priority status messages in Android notification channels.
- During an active device-monitoring session, show a persistent, understandable system notification with the cook name and connection status.
- Let users stop background monitoring from the notification or the Live page.
- Test screen-off operation, battery restrictions, reconnects, and notification permission denial on real devices.

Android requires explicit service types and permissions for applicable foreground services; background Bluetooth connection behavior must be designed to current platform rules. The implementation should choose only the service types actually needed for the validated connection flow.

## 6. Accountless, local-first behavior

- No sign-up or sign-in screen.
- Start, log, review, edit, export, and restore cooks without internet.
- The local database remains the source of truth for the cook library.
- Remote features use the PIN-paired access path and require internet at the grill and phone.
- Show clearly what is available locally versus remotely, including last update time.
- Keep remote transport separate from permanent cook storage, and document exactly what data the relay receives or retains before release.

## 7. Usability and release checks

Test these tasks with someone who has not seen the app before:

1. Start a cook with ribs and pork loin.
2. Name at least four probes and assign them to dishes.
3. Add a spritz event and photo.
4. Correct a historical event time without duplicating or moving unrelated entries.
5. Find a specific temperature on the timeline and inspect it on the chart.
6. Delete and undo an entry.
7. Pair remote access with a PIN and revoke the paired device.
8. Change the grill setpoint remotely and confirm the app reports the controller’s response.
9. Lose internet, continue logging locally, reconnect, and verify the cook record.
10. Export the cook and restore it on a clean install.

Run Android Accessibility Scanner/TalkBack checks, test enlarged font sizes, and test high contrast. Verify every interactive target meets Android’s recommended minimum size.

## 8. Platform references

- [Material 3 navigation bar](https://m3.material.io/components/navigation-bar/overview)
- [Android accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/apps)
- [Android accessibility testing](https://developer.android.com/guide/topics/ui/accessibility/testing)
- [Android offline-first architecture](https://developer.android.com/topic/architecture/data-layer/offline-first)
- [Background BLE communication](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Foreground services overview](https://developer.android.com/develop/background-work/services/fgs)
- [Android notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [Android notification channels](https://developer.android.com/develop/ui/compose/notifications/channels)
- [Android network security guidance](https://developer.android.com/develop/connectivity/network-ops/connecting)
- [OWASP Multifactor Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html)
- [NIST SP 800-63B, Revision 4](https://pages.nist.gov/800-63-4/sp800-63b.html)
