# Input-request bubbles and user screenshots

Version **0.1.5**, version code **6**, verified on the dedicated `MagicPhone_QA35` Android 15/API 35 ARM64 emulator (`emulator-5582`) at `qa-build-host`.

## Behavior

Screenshots of MagicPhone are enabled: MainActivity no longer sets `FLAG_SECURE`, and the test harness does not temporarily override the window flags. This is independent of the gateway's protection against model actions on MagicPhone and system security controls.

When the agent waits for input outside the requesting chat, a rounded message bubble shows a sanitized preview and **Tap to reply**. Both explicit ASK tool calls and text-only replies use this path. The bubble does not take focus from the other app. Tapping it opens its conversation, preserves the current draft, focuses the input and opens the keyboard. An agent already in WAITING_USER does not acquire an additional Pause; Send delivers the answer and continues the task. Manually paused/locked work still requires Resume.

Dismiss hides that bubble instance without answering or stopping the task. The existing ongoing notification shows the question and a chat-bound reply action. Questions are also persisted to encrypted history, so an older notification can reopen its original conversation without replaying work. The bubble disappears on answer, Stop, service teardown, screen off/lock, or while the requesting chat is visible. It uses the existing bound Accessibility service; no new permission or exported component was added. Its attached window/bounds are included in the existing overlay protections.

Notification updates are deduplicated and combined at a maximum of two posts per second. An initial rapid-state device test exposed Android dropping notification updates above its enqueue-rate limit; the final implementation preserves the latest state, including a newly arrived question, without flooding the notification manager.

## Executed verification

- **89 core tests, zero failures.**
- Debug, instrumentation and unsigned release builds pass. Lint: **0 errors / 15 warnings**. Wrapper checksum, release component/permission checks and English/Greek resource parity pass.
- **10 emulator tests pass together in 99.816 seconds:**
  - Four `InputBubbleTest` cases: explicit ASK and text-only question round trips; visible message over the isolated fixture; same chat/draft, focused input and keyboard; Send resumes the model loop without extra Resume; question persistence; foreground inline behavior; dismiss without Stop; notification fallback; screen-off hiding; old notification returning to its own conversation without automatically starting work.
  - Five existing voice/notification tests: controlled Greek transcript append, cancellation and activity recreation, actual installed speech activity open/cancel, repeated background Accessibility entry while work is active, and real notification Pause/Resume/Stop.
  - Existing Settings/fixture Accessibility-shortcut and activity-recreation regression.
- Screenshot-enabled window flags were asserted without modifying them. Actual captures of the bubble and MagicPhone chat were visually inspected; the chat is rendered normally, not blacked out.
- The installed, built, local and HTTP-downloaded APKs have matching SHA-256. Debug signature verification passes and retains the prior signing certificate, allowing an update without uninstalling.

These are real Android UI tests with controlled question providers and synthetic fixture text. They do not call a cloud model or alter the selected ChatGPT account/profile. Live ChatGPT integration was last separately verified in 0.1.4; the authentication/network integration is unchanged here. No physical-phone test was performed for 0.1.5.

The harness restores the original archive/current conversation, preserves the provider, refuses non-emulator devices and rebinds only the already-enabled test service. Evidence and screenshots are under ignored `artifacts/input-bubble/`.

## Commands

```sh
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lint
python3 tools/release-check.py
adb -s emulator-5582 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5582 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5582 shell am instrument -w -r \
  -e class dev.magicphone.app.InputBubbleTest,dev.magicphone.app.VoiceAndControlsTest,dev.magicphone.app.UiResponsivenessTest#accessibilityShortcutReturnsToCurrentDraftAndShowsKeyboard \
  dev.magicphone.app.test/androidx.test.runner.AndroidJUnitRunner
```

## APK identity

| APK | SHA-256 |
|---|---|
| Debug | `71b9ae71d3b080ccc86908dda4123503a3dca938b5c2d2cff0386603f18e9f31` |
| Unsigned release | `72eaa2a4325ec5af9e2c6ccf12b6fdb201f937d7f36911a2bed8e7a37082e026` |

Signing certificate SHA-256: `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`.

Installable LAN APK (private test network). No repository publication, public release upload or production signing key was used.
