# MagicPhone 0.2.2: current-screen prompts, speech and result bubbles

Version code **9**. Verified on 2026-10-02 using the dedicated MagicPhone_QA35 Android 15/API 35 emulator. The existing ChatGPT sign-in and original settings/history are retained by the test harness.

## User behavior

- Android’s Accessibility shortcut opens **Ask MagicPhone** over the current app with an editable prompt and the on-screen keyboard. It works from any permitted app, not only a PDF reader. Opening the panel pauses active work but does not capture or transmit a screen. Send closes the overlay/keyboard and starts from the underlying screen. For an existing paused task it supplies a correction and resumes that task. Close preserves the unsent overlay draft; Open chat transfers it to the full conversation. Inside MagicPhone the shortcut continues to open its current chat.
- A new screen task supplies a fresh filtered Accessibility observation and, for an image-capable model, a transient screenshot bound to that observation. The current app is checked again after dismissing the panel. All observation/capture still passes through Gateway; blocked apps, secure windows, unknown overlays and unverified mutations remain protected. A direct text answer may finish a screen task only when no executed mutation still needs verification; ASK remains available for clarification.
- **Settings → Access → Show completion and error bubbles** defaults to **on**, including when upgrading settings without the new field. It shows a preview of the final answer or a localized terminal error. Open chat returns to the same conversation with focus and the existing draft. Turning the switch off clears a visible result without stopping the task and leaves question bubbles enabled. Results hide on lock/in-chat, can be dismissed, and are not restored after process restart.
- **Read aloud / Stop reading** appears below assistant responses and in bubbles. Playback uses Android’s chosen text-to-speech engine, explicitly on tap, with Greek detection, bounded chunks and stale-callback cancellation. The response text may be processed online by that engine. Missing engines/voices produce a readable message; no audio is persisted.
- The approved open-source/AI automation disclaimer is in README, a dedicated website page, onboarding and automatic-access settings in English/Greek. It describes MagicPhone as an open-source automation tool and qualifies warranty/liability limitations by applicable law. No extra acceptance dialog was added to automatic access.

## Executed verification

- **102 core tests pass** with zero failures. Six new cases cover text/image context, a text-only provider, blocked/changed foreground rejection before inference, secure screenshot failure without sending context, and refusing a text completion after an unverified mutation.
- Debug, instrumentation and unsigned release builds pass through the repository wrapper. Lint: **0 errors / 12 warnings** (SDK/dependency notices and three UseKtx suggestions). Wrapper checksum, release component/permission checks and English/Greek parity pass. No new Android permission or exported component was added; the manifest adds only TTS service discovery.
- **All 19 final emulator checks pass in 202.193 seconds**: 12 bubble/screen/disclaimer/speech cases, five existing voice/notification cases and two UI/shortcut regressions. These cover result-setting persistence/recreation, live task toggle behavior, old settings default, final error localization, lock/dismiss, question round trips, same-chat focus, preserved draft, no repeated completion bubble, overlay submit/cancel/blocked-app behavior, English and Greek speech start/stop callbacks, English/Greek notices, actual Android shortcut repeated entry, notification controls and large-history responsiveness.
- The live current-screen check submits a request through the actual overlay and the existing ChatGPT profile, with only Read permission for the isolated practice app. A screenshot is recorded as captured by Gateway, the answer contains a Greek translation, no mutating action is dispatched, and the result bubble opens the full chat. Greek speech playback starts and stops through the real Android engine. This is live inference and device/UI verification; pronunciation quality and translation accuracy across arbitrary documents were not measured.
- An earlier full run passed 18/19 checks and failed the Settings-to-chat shortcut lookup. An isolated reproduction also failed; after adding a debug-only click counter for diagnosis, the isolated retest and the final complete suite passed. This was intermittent, and does not establish a universal fix for every OEM shortcut issue. A preceding SSH session interrupted another run; that incomplete run is not counted as passing.
- Actual prompt, Greek answer, full chat, setting and disclaimer screenshots were visually inspected. The website disclaimer was checked on desktop and 320 px phone layouts; a narrow header overlap was corrected. Local download/disclaimer navigation and console checks pass.

The selected model/profile, original settings/history and locale are restored after each applicable test. The harness refuses physical devices, uses the isolated fixture and rebinds only its already-enabled Accessibility service. It does not operate personal accounts in third-party apps. Hosted API 30/35 failures previously recorded in WEBSITE.md remain separate; no claim is made that the full hosted matrix or physical-phone tests pass for this version.

## Artifacts and update identity

Local evidence: ignored `artifacts/release-0.2.2/`. The public GitHub release distributes the installable debug APK, SHA256SUMS, signing identity and four license/notice files. The unsigned release is build/audit evidence only.

| Artifact | SHA-256 |
|---|---|
| magicphone-0.2.2-debug.apk | `6fa4071e6b3dd21e3bc187c1c2dfb26bbc880fff08f447bc6a98e9fea87ce6b2` |
| magicphone-0.2.2-release-unsigned.apk | `46491f0ed69110a2da8428606dad2da220f527ea98fdde33d75c22ef38fad9e0` |

Signing certificate SHA-256: `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`. This matches previous releases so an update can retain settings, sign-in and chats.

## Reproduce

```sh
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lint
python3 tools/release-check.py
adb -s emulator-5582 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5582 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Supply liveChatGpt=true only with the account owner's authorization.
adb -s emulator-5582 shell am instrument -w -r -e liveChatGpt true \
  -e class dev.magicphone.app.InputBubbleTest,dev.magicphone.app.VoiceAndControlsTest,dev.magicphone.app.UiResponsivenessTest \
  dev.magicphone.app.test/androidx.test.runner.AndroidJUnitRunner
```
