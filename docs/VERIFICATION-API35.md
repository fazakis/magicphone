# Android 15 verification on the requested SSH host

Date: 2026-10-01. Host: `qa-build-host`, Apple Silicon/arm64 macOS. Source remains authoritative in `~/magicphone` on the original Mac; the remote test copy is `~/magicphone-qa/source`.

## Running emulator

- Name: **MagicPhone_QA35**; ADB serial: **emulator-5582**; emulator console port: **5582**.
- Android **15 / API 35**, `google_apis_playstore;arm64-v8a`, Pixel 5 profile, 3 GB guest RAM, hardware GPU/Hypervisor.Framework acceleration.
- Created as a new dedicated AVD. Existing GymApp and Soma AVDs were not used or modified.
- Started with a visible emulator window and no snapshot loading/saving. It is intentionally left running with MagicPhone installed and its activity open. The fixture is installed for further controlled practice.
- Java 21 was installed only at `~/magicphone-qa/jdk21`; system Java configuration was not changed. The official Eclipse Temurin archive matched its published SHA-256. Exact version/source/hash are in `artifacts/qa-openclaw-api35/jdk.json`.

## Results

**7 Android instrumentation tests passed, 0 failures/errors/skips**, with 19.497 seconds reported by the final device XML. **65 core JVM tests passed, 0 failures/errors/skips.** Lint: **0 errors, 15 warnings**, the same documented categories as the original build. The final combined Gradle run succeeded in 39 seconds with dependency locks and strict verification enabled.

Tests exercised real local approval controls and the fixture counter through the agent, stale targets after a visible external change, password screens without FLAG_SECURE, Greek share/resources, screenshot capture, Greek/emoji script text input, encrypted storage/tamper/interrupted-write recovery, and dark mode at 1.4× text. The screenshot was visually inspected; task entry, navigation and Stop remain visible.

The first seven-test run had six passes and one stale-target failure. The test first verified that the new counter value was actually visible, and still reproduced the failure. Android’s Accessibility node cache was serving the prior value. `PhoneService.inspect` now disables caching/prefetch on API 33+ and refreshes each traversed node on API 30–32; obsolete nodes reject inspection. The stale-target test now confirms the external screen change and bounds the rejection check to five seconds. The focused regression and final complete suite both pass. The separate API 30 fallback check is recorded in VERIFICATION.md.

This is a controlled fixture/mock-provider integration test. It does not establish live ChatGPT consent/inference or external-provider/MCP connectivity.

## Reproduce on this host

```sh
ssh qa-build-host
cd ~/magicphone-qa/source
export JAVA_HOME="$HOME/magicphone-qa/jdk21/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SERIAL=emulator-5582
./gradlew :core:test :app:lint :fixture:installDebug :app:connectedDebugAndroidTest --console=plain
```

Gradle's device runner uninstalls the tested application afterward. The app was reinstalled from this host's freshly built debug APK and opened for the user:

```sh
"$ANDROID_HOME/platform-tools/adb" -s emulator-5582 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5582 shell am start -n dev.magicphone.app/.MainActivity
```

To restart this AVD later if needed:

```sh
~/Library/Android/sdk/emulator/emulator -avd MagicPhone_QA35 -port 5582 -no-snapshot -no-audio -gpu host -memory 3072
```

## Evidence and artifacts

Local evidence lives under `artifacts/qa-openclaw-api35/`: `gradle-test.log`, device/core HTML and XML reports, lint reports, `qa-ui-dark-large.png`, environment details, tested APK hashes and signature/running-activity output. The first failed-run report and focused regression logs are preserved separately. Remote equivalents are in `~/magicphone-qa/reports` and the source modules' build directories.

The APK installed on this host is a source build signed with **this host's Android Debug key**. It differs from the original build host's debug signer; these are separate development identities, not production signatures. The original-host APKs are rebuilt separately to preserve their update identity. The API 35 debug app and test APK are retained locally under `artifacts/qa-openclaw-api35/` with SHA256SUMS.

Tested APK SHA-256 values from the API 35 build:

- `app/build/outputs/apk/debug/app-debug.apk`: `baf2b9b7e2ee63ccbdca588641733a3d7dfbaed9503421b86d66df852be50761`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`: `4b5161b743eef3916f1a01e307e77f1c9e391abe225990b9b76d457d4f0f1fcf`
- `fixture/build/outputs/apk/debug/fixture-debug.apk`: `7dc9ea3cb9ca735a2e4fd9230585861a87fafb662158d50bccc6bdeaa0b73905`
