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
