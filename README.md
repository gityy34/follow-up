# Follow Up

Follow Up tracks who to follow up with and when. Add a title, due date, and optional contact and notes. Search, filter, edit, complete, reopen, or delete entries.

## Website

Serve the files in `dist/` over HTTPS or run a local server from the repository root:

```sh
python3 -m http.server 8000 --directory dist
```

Open http://localhost:8000. To install the website as a progressive web app, choose **Install options** and follow the prompt or your browser’s install menu. On iOS, open the site in Safari and choose **Share → Add to Home Screen**. The web app can reopen offline after the service worker caches it.

## Android SDK setup

Use Android Studio or install the [Android SDK command-line tools](https://developer.android.com/studio#command-tools) and a JDK (17 or 21). Set `JAVA_HOME` and `ANDROID_HOME` to the JDK and Android SDK directories. Install the SDK packages and accept the licenses:

```sh
sdkmanager --licenses
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

Run Gradle from the repository root. On Windows, use `gradlew.bat` in place of `./gradlew`.

## Debug APK

```sh
./gradlew :app:assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The debug version uses the package ID `com.followup.tracker.debug`, so it can sit alongside the release. You can also copy the APK to an Android 8.0 (API 26) or newer device and open it there. Keep Android System WebView updated.

## Signed release

Use the same signing key for every update of a sideloaded release. Keep your key and password backed up privately, and increment `versionCode` in `android/app/build.gradle` for each release.

To create a signing key for a new distribution, run this once and keep the file outside the repository:

```sh
keytool -genkeypair -storetype PKCS12 -keystore /path/to/follow-up-release.p12 \
  -alias follow-up -keyalg RSA -keysize 4096 -validity 10000
```

Supply the existing key and sign both artifacts. These Bash commands prompt for the password without echoing it. For a PKCS12 key created with the command above, the store and key passwords match.

```bash
export FOLLOW_UP_KEYSTORE=/path/to/follow-up-release.p12
export FOLLOW_UP_KEY_ALIAS=follow-up
read -rs -p "Keystore password: " FOLLOW_UP_STORE_PASSWORD
printf '\n'
export FOLLOW_UP_STORE_PASSWORD
export FOLLOW_UP_KEY_PASSWORD="$FOLLOW_UP_STORE_PASSWORD"
./gradlew :app:assembleRelease :app:bundleRelease
unset FOLLOW_UP_STORE_PASSWORD FOLLOW_UP_KEY_PASSWORD
```

- Installable APK at `android/app/build/outputs/apk/release/app-release.apk`.
- Android App Bundle at `android/app/build/outputs/bundle/release/app-release.aab`, for a Google Play upload. The bundle is separate from the APK used for direct installs.

Run `apksigner verify --print-certs android/app/build/outputs/apk/release/app-release.apk` to verify the release APK. To install the APK on a connected device, run `adb install -r android/app/build/outputs/apk/release/app-release.apk`. To install from the hosted website, select **Install options**, download the release APK, and open it. Android may ask you to allow installs from the browser or file manager used to open the download.

## Data and packaging

The Android app ships with the tracker and opens offline without a website login. Follow-ups are saved separately in each Android installation and browser profile. Clearing the app or website data removes those entries.

Android builds copy the app shell from `dist/`. When changing the web app shell, increment the cache version in `dist/sw.js`. For a Sites deployment that offers Android downloads, add the signed APKs as `dist/downloads/follow-up-release.apk` and `dist/downloads/follow-up-debug.apk` and add `dist/downloads/android-builds.json` containing `{"androidApks":true}` to the deployment output.
