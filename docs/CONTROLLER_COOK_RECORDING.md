# Connected cook recording

Debug and Firebase Dev builds can attach a GrillirG cloud grill to a local cook. The controller must already be provisioned and accessible to the signed-in account. PitTech continues to expose only the five existing authentication/discovery/read operations; there are no grill-control requests.

## User flow

1. Connect/select a grill in Devices. Start a cook, select **Automatically record temperatures**, and optionally assign Probe 1/2 to dishes. Existing active cooks can use **Attach grill & record** in Live.
2. Live shows chamber, reported setpoint, food channels, receipt age, connection state, and recent history. **Assign probes to dishes** preserves each prior assignment interval and each reading's original dish.
3. Food on, spritz, wrap, remove and rest quick actions create ordinary editable cook entries. Notes, reminder check-ins and events copy available preceding temperature values. A copied snapshot cannot change when a source reading is edited/deleted.
4. New camera photos preserve the temporary camera file's capture time. Library EXIF dates are accepted only with an explicit offset; ambiguous dates stay unknown. Photos obtain their own context at capture time, not import time. A default photo timeline entry follows known capture time; an unknown gallery date clears its automatic context. Explicitly backdated entries retain their chosen time. Entries/photos without relevant recent data have no fabricated context.
5. Pause/stop recording independently of the cook. Pausing a cook pauses recording. Resume recording is explicit. Finishing/deleting serializes with ingestion and prevents late samples being added. Restore imports history in a stopped state and never starts background recording.

One selected grill can record into one active cook at a time, with multiple dishes/probes in that cook. A running recording locks grill selection. A restored cook can attach a grill again; cloud identities and authentication credentials are never exported.

## Collection and timestamps

The shared cloud monitor has one serial poller. App visibility and the recording service are separate owners, so changing tabs/opening the camera cannot cancel an active recording. A user-started foreground service keeps an explicit recording session running with a persistent notification and a pause action. The service releases its bounded CPU wake lease when stopped. **Screen-off recording options** explains Android's idle network restriction and offers a user-initiated battery-optimization exemption in debug/Dev builds, plus notification permission when disabled. Charging also avoids deep idle. Android/vendor power restrictions can still interrupt receipt; force-stop cannot be bypassed. App reopening can recover an active session with encrypted authentication. Expired/displaced authentication pauses recording until explicit sign-in/resume. See [Android Doze behavior and exemptions](https://developer.android.com/training/monitoring-device-state/doze-standby).

The debug/Dev service declares `specialUse` with its continuous, user-visible cook-monitoring rationale. It is absent from the regular release manifest. This does not claim Google Play approval: this declaration must be reviewed before promoting the integration to the production app. `dataSync` is inappropriate for assuming an unattended cook longer than its Android 15+ six-hour allowance; periodic WorkManager is not a 15-second sampler. See [service types](https://developer.android.com/develop/background-work/services/fgs/service-types) and [timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout).

Automatic readings explicitly carry `timestampBasis=cloud_receipt`: receipt time is known, the sensor's actual sample timestamp is not. A recent HTTP response does not prove fresh sensor data. Vendor-reported offline readings are not inserted as valid temperatures. Missing/zero probe and setpoint values are retained as unavailable channel samples; probe status is alarm arming, not plug detection. Missing unit means no temperatures can be normalized. Graphs split at unavailable samples, receipt gaps over 45 seconds, and probe reassignment; no interpolation or history backfill is invented. Display reduction preserves segment endpoints/extremes while the database/export keeps every sample.

Room transactions enforce cook status, binding, resume watermark and per-sample IDs during ingestion. Repeated monitor emissions cannot duplicate samples. Connection interruptions/restoration are bounded timeline events; a reported setpoint change needs two consecutive agreeing observations. Operating codes do not start/finish cooks automatically.

## Local data and archive compatibility

Room schema 4 adds recording state, probe assignment intervals, copied event/photo context, and a reading timestamp basis. Migration 3→4 preserves older cook data. Archive schema 3 adds recording history, assignments and snapshots to JSON, CSV and workbook exports; schemas 1/2 remain importable. Exports contain only local device/probe IDs. Controller binding hashes are omitted and imports force recording status to stopped.

## Verification

The JVM suite checks temperature normalization, unavailable channels, timestamp-based context, copied snapshots, graph gaps/reassignment, display reduction, recording ownership and selection locking. Android integration tests cover binding/duplicate/finish guards, immutable snapshots and camera timestamps, assignment history, gap/setpoint events, archive restore, schema migration, foreground recording across activity backgrounding and terminal authentication failure, plus starting a recorded cook and saving a wrap through the UI. The existing data-preserving APK upgrade and app flows remain in the CI gate. Synthetic backend tests validate app behavior; a physical overnight/network-interruption test is still required to measure this controller/cloud/phone combination in practice.
