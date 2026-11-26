# YoU Follow ups

An offline-first customer follow-up and call tracker. The Android app is native Kotlin with Jetpack Compose and Material 3. The website is a responsive, installable PWA with browser-local records.

## Use the app

- **Overview** opens with the neon road, a local-time greeting and your profile name inside the road card. The header shows your display title and the date. The three-dot menu at the lower left of the road filters the New, Contacted, Follow-up and Successful counts by Today, the rolling last 24 hours, or the last 2, 7, 20 or 30 calendar days. Below the road, today’s deal amounts appear by currency and today’s calls appear in a donut by outcome. The Mission menu holds Start call session, Pending calls and Change call goal.
- **Reminders** opens from the bell. The badge counts today’s reminders, including weekend dates. Use the three-dot menu to switch between Yesterday, Today and Tomorrow, then open a reminder to view its follow-up. The list uses the reminder schedule and includes planned reminders while Android notifications are off.
- **Contacts** holds customer details, notes, tags, status, priority, and an append-only interaction timeline. Archive customers to keep their history.
- **Schedule** groups follow-ups by date and lets you search or filter by date range, priority, and status. The add flow collects purpose, schedule and note. Deleted follow-ups can be restored.
- **Call reflection** puts Successful and Pending up front. More outcomes includes Follow-up Needed, which opens the scheduler. Open a Pending call to mark it Successful, Cancelled, or Other while keeping the original call and notes. Saving a Successful result requires the deal amount.
- **Insights** opens the Performance Coach with the same report periods as Overview. Deal totals use the time the result was marked Successful. Call counts use the original call time. Amounts are stored as exact decimals, and each currency has its own total.
- **Settings** opens from the header’s three-dot menu. Edit your name and dashboard title, and choose the device font, app default, Manrope, Serif or Monospace there, along with the daily goal, deal currency, appearance, demo data, retention, and encrypted backups. Open the profile photo editor from the header avatar or Settings to view, change, crop or remove your photo. Apply saves the edit. The header shows the app icon when no photo is set. New deals default to PKR. Select USD or another currency for future deals. Saved deals keep their currency. Android settings also include local notifications, app lock, and recent-app privacy. Sign in & Sync is marked Coming Soon.

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

Data Safety draft for Play Console review: the app stores customer and call records locally. The Android app collects and shares no user data over a network and contains no analytics or ads. The current version can restore earlier backups. New backups also preserve the profile, Pending states, deal amounts, and currencies. Portable backup export uses the system document picker and the destination chosen by the user. The dial action opens the device’s phone app. The final Play Console disclosures should match the submitted build.

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

The road artwork and markers are included in both builds. The overview plays an entrance animation of about four seconds and honors reduced motion. Profile photo sources and cropped thumbnails are kept locally. Use New profile or Use existing profile when setup opens on first launch or upgrade. Website line icons are [Google Material Symbols](https://github.com/google/material-design-icons) under the [Apache 2.0 license](dist/material-symbols-LICENSE.txt).
Manrope is included under the [SIL Open Font License](dist/manrope-OFL.txt).
