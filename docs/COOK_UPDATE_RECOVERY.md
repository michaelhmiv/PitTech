# Active cooks during an Android update

An ordinary in-place update retains the local database, cook IDs, timestamps, photos, and recording configuration. Uninstalling or clearing app data is a different operation.

The package-replacement receiver and foreground application now share a serialized recording reconciler. It starts periodic recording only for a saved running recorder on an active cook. It preserves the original grill binding, probe assignments, sampling cadence, and ingestion watermark. Repeated reconciliation uses the same foreground service and does not create another sampling observer.

Paused/stopped recorders and completed cooks are not restarted. On-log mode retains its binding without a periodic foreground recorder. The existing Debug/Dev controller gates remain enforced.

Android/provider failures pause recording with a persisted explanation and, where notification permission allows, a recovery notification that opens the affected cook. The app never invents backfill; receipt/cadence watermarks detect missing periods after ingestion resumes.

## Upgrade verification

The existing stopped-app migration test remains. A second scenario builds the previous checkout with only a Debug controller fixture and its setup test overlaid. No recovery implementation is backported. It creates a cook with a dish, note, photo, manual temperature, target, and probe mapping; starts recording; backgrounds the old app; verifies a foreground service exists; and installs the candidate APK without force-stop.

The candidate test verifies recording and new samples without launching an activity, plus preserved cook/start/action timestamps, photo bytes, target, mapping, cadence, binding, and unique reading IDs. The fixture is opt-in, Debug-only, synthetic, and contains no production credentials. Separate tests cover paused/stopped/completed/on-log behavior.

APK identity and signing must match. Archive restore continues to restore recording stopped, guidance paused, and alerts disabled. Updating a binary never makes an archive restore an instruction to start services.
