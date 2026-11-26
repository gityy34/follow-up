# YoU Follow ups 1.1.0 QA

The attached Concept 3 image supplies the violet Customer Journey, Customer Detail, Add Follow-up, Post-Call Reflection and Performance Coach design. The PDF supplies the workflows and data rules. Labels in the app are English. Version 1 uses device-local data. Optional Android Auto Backup is disabled. Encrypted document export is the supported recovery method.

| Area | Check | Result |
| --- | --- | --- |
| Android domain | Outcome transitions, customer validation, reminder offsets, the New York daylight-saving transition and report totals | 3 JVM tests passed |
| Android backup | AES-GCM round trip, wrong passphrase, truncation, tampering, duplicates and invalid relationships | 2 JVM tests passed |
| Database | Exported Room schema and a SQLite check of the version 1 → 2 migration with an existing customer | Passed |
| Android persistence | Repository instrumentation for 120 follow-ups, all outcomes, reopen, soft-delete/undo, append-only history and rollback | Test APK built. Device execution pending. |
| Website data | Legacy migration, storage round trip, report math, encryption, wrong passphrase and tampering | Passed with Node 24 |
| Website interactions | Customer creation, three-step scheduling, Agenda search, call result, report and theme change | Passed in a simulated browser DOM |
| Android packaging | Debug lint, debug APK, signed release APK, signed AAB, APK alignment and manifest permission checks | Passed |
| Visual browser preview | Managed preview port | The environment blocked the local listener. |
| Android on-device | Startup and upgrade from the old WebView tracker, rotation, force-stop, reboot, exact/inexact alarms, Doze, permission denial/grant, notification actions, TalkBack, large text, app lock and document-picker recovery | Pending. No connected Android device or accelerated emulator was available. |

Android builds use compile and target SDK 36, minimum Android API 26, JDK 21 and Gradle 8.13. Install the debug package to test with independent data. A reminder’s timing can vary under Android’s background limits. Browser and Android data stay separate unless the user moves a backup between them.

## Version 1.2.0 deal workflow

- Eight Android JVM tests passed, covering precision, multi-currency totals, local Today versus rolling 24 hours, and encrypted backup of Pending and Successful calls. Debug lint, signed Android packaging and test APK compilation passed.
- The SQLite 2 → 3 migration preserved a pre-existing call and its notes. The new amount and currency fields remain empty for legacy calls that never recorded an amount.
- The browser data suite and DOM smoke flow passed for Pending, mandatory Successful amount, exact decimals, date filters, and currency changes. Existing deals retain their original labels and each currency total stays separate.
- On-device Android execution of the new Pending and payment UI remains pending. The repository instrumentation suite was built.

## Version 1.3.0 neon dashboard

- Ten Android JVM tests passed. The new tests cover local-time greetings, winter and summer boundaries, two- and twenty-day road metrics, and reminder date filtering on a Sunday.
- The browser data suite and DOM smoke flow passed for first-run name setup, the saved display name, hidden call controls, report periods, reminder day filters and opening follow-up details. The Pending and multi-currency deal flows also passed.
- Debug and signed release builds, signed bundle packaging, Android test APK compilation, and debug lint passed. The environment blocked the local visual preview port. Native layouts and notifications remain unverified on a physical Android device.

## Version 1.4.0 profile and dashboard polish

- The Android debug and release JVM suites cover profile review, old settings, font preferences and encrypted photo metadata. The test APK compiles and lint passes.
- Browser data and DOM checks exercise both fresh setup and retaining an existing profile, personal name only in the road greeting, photo controls and persistence, font selection, period labels, marker markup, reminders and the Pending deal flow.
- The browser DOM checks simulate the photo decoder. On-device photo picking, animation playback and visual layout still need device QA.

## Version 1.5.0 road markers and photo editor

- Thirteen Android JVM tests passed in both Debug and Release. Debug lint and the Android instrumentation APK build passed. Coverage includes photo crop bounds, encrypted backup of the crop source, and separate local-day totals for call and payment dates.
- Eight web data tests and both fresh and existing profile DOM flows passed. The flows cover the road period menu, reminders, today’s chart, exact multi-currency amounts, and profile photo crop, Apply, cancellation and removal from both entry points.
- The DOM photo tests simulate decoding. The managed browser preview relay could not start. Native animation playback, real photo selection and visual layout still need device QA.

## Version 1.6.0 journey details and dated payments

- Fifteen Android JVM tests and ten web data tests cover the four stage detail lists, exact decimal totals by local date and currency, bulk validation and payment backup data. The SQLite 3 → 4 migration preserved an existing customer and deal and matched the exported Room schema.
- The web DOM flow exercised all four inline lists, browser and on-screen Back, one reload animation during rapid repeated clicks, batch save, moving an edited entry to another day, and Daily, Monthly and selected-date totals.
- Debug lint, test APK compilation, and signed Android packaging passed. The managed browser preview listener was unavailable. Device execution and visual animation playback remain unverified.
