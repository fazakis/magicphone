# Android voice input, notification controls and background chat entry

Build: **0.1.4 / version code 5**. Dedicated `MagicPhone_QA35` Android 15/API 35 ARM64 emulator, serial `emulator-5582`, on the user-authorized host `qa-build-host`.

## Changes

- A microphone in the task field launches the installed Android speech recognition activity. It requests free-form speech, receives a bounded transcript, and appends it to the current draft. Sending remains explicit. Cancellation leaves the draft unchanged, and a result for another conversation is discarded. Pending voice state and the draft use saved activity state.
- No OpenAI API key, new Android microphone permission, audio recording, audio upload by MagicPhone, or new runtime dependency was added. The selected Android speech provider manages audio and its own consent. Its availability, languages and online/offline behavior depend on the device/provider.
- The former top Pause/Stop Accessibility overlay and its Settings switch are removed. Existing installations cannot restore it through their saved `floating` preference. Action approvals retain their separate protected overlay.
- A quiet, ongoing notification remains while Accessibility is connected. Active tasks expose Pause/Resume and Stop; terminal/idle status keeps the current-chat entry without stale actions. The notification uses localized states, immutable explicit PendingIntents and secret lock-screen visibility. Disconnect cancels it. Notification permission/channel recovery is available in the app. Resume closes the shade on API 31+ before continuing; Android 11 users close it manually.
- Shortcut, assist, tile and notification chat entry pause an active task so it cannot take the screen back during input. Repeated Pause no longer replaces the resume waiter. A model reply arriving during Pause cannot overwrite that state or dispatch actions. Focus waits for RESUMED lifecycle and window focus, with bounded keyboard retries.

## Verified

- **89 core tests, zero failures**, including repeated Pause/Resume and a late text reply while paused.
- Debug APK, Android test APK and unsigned release build successfully. Lint: **0 errors, 15 warnings**. Wrapper integrity, exported-component/permission audit and English/Greek resource parity pass.
- **Five VoiceAndControlsTest device tests pass.** They cover three real system Accessibility-button returns from the background while a task is active; preserved conversation/draft, focused input and keyboard; notification-body entry; real notification-shade Pause/Resume/Stop; shade dismissal on Resume; ongoing notification after Stop; absence of the retired overlay; controlled Greek transcript append with no automatic task; cancellation and activity recreation; bounded result parsing with no task context in the speech request; and opening/canceling the installed `com.google.android.tts` recognition activity.
- The Android notification permission prompt was reached through the actual new Enable notification controls UI and accepted on this dedicated emulator.
- Large synthetic-history UI/persistence regression passes on this APK. The existing Settings/fixture shortcut and activity-recreation regression also passes. The shortcut harness now selects the named system button, rather than the floating menu container; tapping the container sometimes only expanded the menu and never delivered a service callback. Notification testing similarly selects the content title, not the collapsible app-name header.
- The opt-in live ChatGPT fixture regression passes: actual launch, tap, text entry, stale-screen rejection/re-observation/replanning, coordinate dispatch without the retired overlay, unknown-overlay rejection and automatic-access revocation. It used the existing signed-in account; no credentials were extracted.
- Debug signing verification passes. The signing certificate matches the earlier `.52` builds, and installing with `adb install -r` preserves the existing ChatGPT profile/account. Built, local and installed APK checksums match.

Speech-result tests use controlled Activity Results; the actual provider was opened and canceled. **Live acoustic transcription accuracy was not measured.** This is not a claim that spoken Greek or every phone's recognition provider was acoustically tested. The user's Xiaomi phone was not connected for this verification.

Newer Android versions allow users to dismiss ongoing notifications individually. MagicPhone restores its notification when opened or when task state changes; it does not defeat Android's notification controls. Android/OEM process termination and disabled Accessibility still require the user to restore service access.

## Reproduction

```sh
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lint :app:assembleRelease
python3 tools/release-check.py
adb -s emulator-5582 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5582 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5582 shell am instrument -w -r \
  -e class dev.magicphone.app.VoiceAndControlsTest \
  dev.magicphone.app.test/androidx.test.runner.AndroidJUnitRunner
```

The harness refuses non-emulator devices, preserves the selected model/account and original archive, and restores shortcut preferences. It rebinds the already-enabled service after instrumentation replaces the app process. It makes no model/network calls. Real-account inference, when run, uses the separately opt-in live fixture test.

## APK identity

| APK | SHA-256 |
|---|---|
| Debug | `c2380bcee60011590427b35457d45b825633a129def57202fd19d2920a3690f4` |
| Unsigned release | `b64a0264b039d68fb3f1a29098afe55a05141f191ac58f826deec81eb77a4c66` |

Debug signing certificate SHA-256: `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`.

The LAN download (private test network) is refreshed. Built, installed, local and HTTP-downloaded debug APKs have the same SHA-256. The final emulator is on 0.1.4 with Accessibility connected and its existing ChatGPT sign-in preserved.

Local APKs and evidence are under ignored `artifacts/voice-controls/`. No production key, repository publication or public release upload is involved.

## Platform references

- [Android speech recognition activity contract](https://developer.android.com/reference/android/speech/RecognizerIntent)
- [Android 14 changes to ongoing notification dismissal](https://developer.android.com/about/versions/14/behavior-changes-all#non-dismissable-notifications)
- [API 31 notification-shade dismissal action](https://developer.android.com/sdk/api_diff/31/changes/android.accessibilityservice.AccessibilityService)
