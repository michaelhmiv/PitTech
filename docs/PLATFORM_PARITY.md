# Platform parity — issue #89

“Port” is a committed implementation task; “platform-appropriate” documents an
OS limitation. Completion requires both clients and CI evidence. Existing
[connection policy/matrix](CONNECTION_PLATFORM_PARITY.md) remains authoritative.

| Capability / gap | Disposition | Target | Verification |
|---|---|---|---|
| Shared navigation, semantic light/dark surfaces | Port | 01 | Native phone/tablet/large-text screenshots; back/notification state |
| Recipe search/filter/selection hierarchy | Port | 01 | Select three recipes, cancel filters, compose meal |
| iOS result editing, per-dish optional cook-again | Port | 02 | Skip finish verdict; edit later; migrate existing results |
| Scoped drafts and source-owned story/photos | Port both | 02 | Pork-only draft leaves potatoes/history unchanged; relaunch |
| Full backup limits (Android 350/300/45 MiB, 10k entries; iOS 64/64/16 MiB, 1k) | Port iOS bounded streaming | 03 | Cross-platform boundaries, peak memory and ZIP attack fixtures |
| iOS CSV/workbook export equivalents | Port | 03 | Independent reader; units/timezones/escaping/relationships |
| One guide toggle, per-dish reference, dashed curves | Implemented on both; Actions/visual gate pending | 04 | Pinned stories; independent visibility/reminders; negative offsets; gaps; scope-safe channel mapping |
| Native share-card rendering and system share | Implemented on both; Actions/pixel review pending | 05 | Shared chart/photo/sparse/Unicode/relay fixtures; native PNGs; recipe/meal scope; preview and canceled share |
| Code entry, Universal/App Links | Port both | 07 | Signed artifacts, installed/uninstalled fallback, cancel import |
| Secure magic-link/code sessions and account controls | Port both | 09 | Replays, code retries, sign-out/account switch/delete |
| Discovery/save/cook available signed out | Port both | 11 | Offline owned copy; source removed; no auth barrier |
| Editable recipe/collection/attempt publication | Port both | 12 | Explicit scope/identity preview, durable retry/idempotency |
| Reporting, opaque publisher blocking, deletion | Port both | 13 | Anonymous privacy; original purge; independent fork survival |
| Sync metadata/tombstones/photo queue/recovery | Port both | 14 | Two-device offline conflicts; account ownership; inactive automation |
| Existing free Insights math | Port common metrics | 15 | Shared golden measurements and sparse/gapped samples |
| Verified purchases, restore and zero-ad requests | Port both | 16 | Store sandboxes and SDK/network spies |
| Personal analysis/customization/recap | Port both | 18 | Offline entitlement cache; accessible defaults; explicit sharing |
| Support with user-directed redacted diagnostics | Port iOS | 01 | Review/cancel/send; no secret/device-identifying payload |
| Five Beta connections in store builds | Preserve | All | Release artifact gates and provider UI tests |
| Combustion iOS bounded history/repeater recovery | Port investigation | 01/19 | Supported SDK surface/version and hardware history evidence; no fabricated data |
| Background monitoring | Platform-appropriate | Existing/19 | Android opted-in foreground service; iOS foreground + supported provider history |
| Private photo originals vs public EXIF-free derivatives | Port both | 03 | Orientation/GPS fixtures; original hash unchanged |

Owner of implementation: issue #89 implementation branch. Every unresolved row
remains open in the delivery status. Emulator evidence does not replace real
provider, store or device background validation.
