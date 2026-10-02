# MagicPhone 0.2.3: automatic screen recovery and fewer false sensitive-content alerts

Version code **10**. Verified on 2026-10-02 on the dedicated MagicPhone_QA35 Android 15/API 35 emulator. Release publication is user-authorized.

## Behavior

- Temporary OBSERVE/SCREENSHOT failures are returned to the model as unavailable observation feedback instead of ending the model task. This includes the initial current-screen context, later tool calls and automatic post-action reads. When a screenshot fails, available app text remains in context. Partial screens skip the optional initial screenshot.
- A short **“Reading the available screen content. Continuing…”** Android notice is explicitly cancelled after **2,000 ms**, on a separate UI coroutine. There is no two-second wait, retry sleep or pause in the agent. It appears at most once per task and does not become a final-error bubble or a persisted failure message.
- Recovery is no longer capped at three screen rejections. Existing total step, model-round and ten-minute run budgets still bound the task. The model is directed to continue with available evidence, obtain fresh permitted observations when needed, and avoid asking the user to close overlays or restart for temporary read failures.
- A normal keyboard no longer makes the app screen unreadable. Only permitted app nodes outside the keyboard and MagicPhone controls are returned. Their rectangles are bound to the snapshot and protected against taps. Keyboard/overlay pixels are not captured. Unknown foreign overlays, app permissions, focus checks and secure windows retain their separate checks; when a read is unavailable, the model can continue with previously available evidence.
- Stale node branches and traversal limits produce a **partial** observation, retaining other fresh readable nodes. A partial scan is never used for a screenshot. An already dispatched action is never replayed because its follow-up read failed; completion still requires observation after mutations.
- Sensitive detection now checks exact Android password class/variation values and the actual password flag. Web-edit, web-email, ordinary text/name/address inputs and large trees no longer trigger false password warnings. Actual password fields remain manual. Locked devices, protected system screens and secure screenshot failures have distinct English/Greek messages instead of the generic sensitive-content message.

The input-type change follows Android’s [InputType definitions](https://developer.android.com/reference/android/text/InputType): web-edit `0xa0` and web-email `0xd0` are distinct variations, not password bits. Username/email fields alone are ordinary text inputs; credential/password fields are protected.

## Executed verification

- **110 core tests pass**, with zero failures, errors or skips. New cases exercise initial/later screenshot fallback, partial text context, five consecutive unavailable reads followed by successful completion, one notice with **zero virtual-time delay**, normal run-budget termination, exact credential classification, and unchanged mixed-window/focus/secret guards. Existing stale-target, approval, permission-revocation and no-replay/unverified-completion checks remain passing.
- The new three-case device suite first passed in **8.995 seconds**. On Android it verifies that the model is already processing while the notice is visible, the notice clears within two seconds plus scheduling margin, and the same task completes without a user reply. It checks reading and a real counter tap with the keyboard open, rejection of keyboard coordinates/capture, ordinary web-edit/email fields, a 120-row tree, and actual password protection.
- **All 22 final emulator tests pass in 203.223 seconds**: the three new regressions plus the existing 19 screen/bubble/voice/notification/UI cases. The account-owner-authorized live ChatGPT current-screen Greek translation and real English/Greek speech checks pass as part of that suite. The new failure-recovery cases use controlled providers for reproducibility; they are not represented as live cloud inference under injected failures.
- An earlier full run passed 20/22 checks. Its new password test raced the outgoing fixture; the harness now waits for an input-type-specific marker. The existing shortcut test matched a MagicPhone status-bar notification icon instead of the floating button (the geometry log identified the wrong target); selection is now scoped to the actual Accessibility shortcut. The final full run uses both corrections.
- Debug, instrumentation, fixture and unsigned release builds pass through the checked-in wrapper. Lint: **0 errors / 12 warnings**. Wrapper checksum, release component/permission checks and English/Greek resource parity pass. No dependency, permission or exported component was added.
- The test harness keeps the original selected provider, settings/history and locale, updates with `adb install -r`, and rebinds only the already-enabled Accessibility service on the dedicated emulator. No tests operate third-party personal accounts. Physical-phone/OEM behavior and the previously documented hosted API 30/35 matrix are not claimed as passing for this version.

## Artifacts

Local evidence is in ignored `artifacts/release-0.2.3/`. GitHub receives the debug APK, SHA256SUMS, SIGNING.txt and four license/notice files. The unsigned build is audit evidence only.

| Artifact | SHA-256 |
|---|---|
| magicphone-0.2.3-debug.apk | `27bd0b543b2f22bc1cc1470b553231d5f255c4437f9ccb78a0a3668fcf314325` |
| magicphone-0.2.3-release-unsigned.apk | `c2e6a820f0d95b11c22d6a2342b7272b01e9377bc0fb38463a46ffb89c0ad20a` |

Signing certificate SHA-256: `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`. The certificate matches previous releases so an update can preserve app data.

## Reproduce

```sh
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :fixture:assembleDebug :app:assembleRelease :app:lint
python3 tools/release-check.py
adb -s emulator-5582 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5582 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5582 install --no-streaming -r fixture/build/outputs/apk/debug/fixture-debug.apk
# liveChatGpt=true requires the account owner's consent.
adb -s emulator-5582 shell am instrument -w -r -e liveChatGpt true \
  -e class dev.magicphone.app.ScreenReadRecoveryTest,dev.magicphone.app.InputBubbleTest,dev.magicphone.app.VoiceAndControlsTest,dev.magicphone.app.UiResponsivenessTest \
  dev.magicphone.app.test/androidx.test.runner.AndroidJUnitRunner
```
