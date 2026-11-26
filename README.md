# YoU Follow ups

An offline-first customer follow-up and call tracker. The Android app is native Kotlin with Jetpack Compose and Material 3. The website is a responsive, installable PWA with browser-local records.

## Use the app

- **Journey** shows today’s call goal and calls made in separate tiles. Start a call session, open the Pending queue, or check deal totals for Today, the rolling last 24 hours, the last 7 days, or the last 30 days.
- **Customers** holds contact details, notes, tags, status, priority, and an append-only interaction timeline. Archive customers to keep their history.
- **Agenda** groups follow-ups by date and lets you search or filter by date range, priority, and status. The add flow collects purpose, schedule and note. Deleted follow-ups can be restored.
- **Call reflection** puts Successful and Pending up front. More outcomes includes Follow-up Needed, which opens the scheduler. Open a Pending call to mark it Successful, Cancelled, or Other while keeping the original call and notes. Saving a Successful result requires the deal amount.
- **Performance Coach** uses the same report periods as Journey. Deal totals use the time the result was marked Successful. Call counts use the original call time. Amounts are stored as exact decimals, and each currency has its own total.
- **Settings** controls the daily goal, deal currency, appearance, demo data, retention, and encrypted backups. New deals default to PKR. Select USD or another currency for future deals. Saved deals keep their currency. Android settings also include local notifications, app lock, and recent-app privacy. Sign in & Sync is marked Coming Soon.

## Android

Install the **Debug APK** to test or the **Release APK** for normal use on Android 8.0 or newer. A signed Android App Bundle is available for upload to Play Console. The debug and release package IDs have separate data and can coexist.

Enable reminders from Settings. The default is the previous day at the same local time in the follow-up’s saved time zone. You can choose another offset or a custom time. Android may delay best-effort reminders. Exact timing is requested only for time-critical reminders with the device’s exact-alarm access. Use the phone dialer to place a call.

Export and verify an encrypted backup to Documents or another location you control before uninstalling or changing phones. Uninstalling removes the local database. An import verifies the file and previews counts before the user confirms replacement. The Android app writes an encrypted pre-restore copy to app-private storage using the passphrase entered for the import.

### Build

Install a JDK (17 or 21) and the [Android SDK command-line tools](https://developer.android.com/studio#command-tools). Configure `JAVA_HOME` and `ANDROID_HOME`, then run:

```sh
sdkmanager --licenses
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
./gradlew :app:assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`. For a release, set `FOLLOW_UP_KEYSTORE`, `FOLLOW_UP_STORE_PASSWORD`, `FOLLOW_UP_KEY_ALIAS` and `FOLLOW_UP_KEY_PASSWORD` in the build environment, then run:

```sh
./gradlew :app:assembleRelease :app:bundleRelease
```

APK output is in `android/app/build/outputs/apk/`. The AAB is in `android/app/build/outputs/bundle/release/`. Reuse the original signing key to update existing APK installs.

### Storage and privacy

Room over SQLite stores customers, follow-ups, call logs, exact deal amounts, and timeline entries. Important record changes use transactions. DataStore stores settings. WorkManager reconciles reminders and AlarmManager delivers them. Android requests no Internet, contacts, call-log, microphone, location, SMS, direct-call, or broad storage permission. Backup files use AES-GCM and a passphrase-derived key, with an authenticated versioned manifest and checksum. Dedicate a strong passphrase to backups and keep it privately.

Data Safety draft for Play Console review: the app stores customer and call records locally. The Android app collects and shares no user data over a network and contains no analytics or ads. Version 1.2.0 can restore earlier backups. Its newer backups preserve Pending states, deal amounts and currencies. Portable backup export uses the system document picker and the destination chosen by the user. The dial action opens the device’s phone app. The final Play Console disclosures should match the submitted build.

## Website

```sh
python3 -m http.server 8000 --directory dist
```

Open http://localhost:8000 for local development. On the deployed HTTPS website, open Settings and choose **Install Options**. On iOS, use Safari’s **Share → Add to Home Screen**. The app shell opens offline once the service worker has cached it. Browser and Android workspaces are independent. Use encrypted export/import to move records manually. The browser saves reminder preferences in backup. Android provides background reminders and device lock.

The website reads the old browser tracker storage on first launch and converts it to customers and follow-ups. Android includes a local migration attempt for data left by the previous WebView version in the same installation.

## Checks

```sh
node --test tests/web.test.mjs
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

`connectedDebugAndroidTest` needs an emulator or Android device. See [QA](QA.md) for verified coverage and remaining device checks.
