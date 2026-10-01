# 0.1.3 UI responsiveness and Accessibility shortcut

Date: 2026-10-01. Version **0.1.3, code 4**. Tests ran on the dedicated Android 15/API 35 ARM64 emulator `emulator-5582` at `qa-build-host`. The account owner's existing ChatGPT profile and account were preserved. UI benchmarks themselves made no model requests.

## Changes

- Moved history sanitization, JSON encoding, Keystore encryption and atomic file writes off the main thread into an ordered background writer. UI state updates immediately. The gateway still awaits durable audit persistence before dispatch and rechecks Stop/Pause and app preflight afterward. Persistence failure stops work and reports a storage notice. Unchanged state updates no longer rewrite history.
- Replaced eager chat/history layouts and the Settings app-permission list with lazy lists. Only visible rows are composed. Package-label discovery runs on IO. UI flows stop collecting while the activity is stopped.
- Kept the message composer below the scrolling conversation; typing reads draft state within that small composition. The current conversation's title is displayed. Draft/tab state survive activity recreation, and the runtime restores the most recently used conversation after a new process starts.
- Declared Android's accessibility-button metadata flag and registered the service callback. The actual system shortcut opens the current chat with the draft intact, focuses its text input and shows the software keyboard after the window gains focus. Repeated presses work. The notification, Quick Settings tile and assist entry share chat routing. Opening the chat does not submit a task.

Settings and permission commits retain their synchronous behavior. This change specifically removes large history serialization/encryption/fsync work from the UI thread; it does not claim every operation or cloud response is instantaneous.

## Before/after UI benchmark

The final comparison used the same emulator and final `largeHistoryUiAndPersistence` harness on 0.1.2 and 0.1.3. The harness appended 181 temporary conversations to the original archive: 180 with 20 messages each and one active conversation with 60 messages. It then made 12 history updates on the main thread, switched between Task/History three times and scrolled both directions. It restored the original archive and current selection afterward. No account/profile settings were replaced.

Main-thread timings surround the history-update call; in 0.1.3 the actual disk write continues on IO. A flush followed by an encrypted vault read independently verified all 72 messages in the active conversation before cleanup. Frame durations were collected using Android `Window.OnFrameMetricsAvailableListener` and `FrameMetrics.TOTAL_DURATION` during the UI workload.

| Measurement | 0.1.2 | 0.1.3 |
|---|---:|---:|
| Median main-thread history-update time | 79.689 ms | 0.422 ms |
| Maximum main-thread history-update time | 93.220 ms | 0.739 ms |
| 95th percentile frame duration | 83.527 ms | 33.961 ms |
| Frames over 32 ms / total captured | 28 / 223 | 17 / 233 |

The history update stalls on the main thread fell by about **99.5%**, while the 95th percentile frame duration fell by about **59%** in this workload. These are UI measurements, separate from the [0.1.2 model-loop timings](VERIFICATION-PERFORMANCE.md). They do not imply that the whole application or storage system is 190 times faster, and they are not physical-phone frame-rate guarantees. Some slow frames remain in this debug-build/emulator stress test.

An earlier baseline measured 83.755 ms median history updates and 83.526 ms p95 frames; an earlier successful optimized run measured 0.397 ms and 33.701 ms. Final runs above use the same updated test harness. During test development, navigation checks failed when the keyboard obscured the navigation bar and when the test assumed a specific first history card remained visible. The harness now explicitly dismisses the IME before navigation and checks visible fixture history rows. Those failed attempts are retained rather than counted as successful benchmark samples.

The final baseline harness passed in **18.202 s**. The final two-test UI suite passed in **45.932 s**. Harness time includes setup, waiting, navigation and cleanup and is not the UI-thread timing in the table.

## Actual shortcut verification

`accessibilityShortcutReturnsToCurrentDraftAndShowsKeyboard` uses the real Android floating accessibility button, configured only on the dedicated emulator. It requires the service to be already enabled and rebinds that service after instrumentation restarts the app. It restores the previous shortcut settings afterward.

The test enters a draft, navigates to Settings and clicks the actual Android shortcut. It checks the same current conversation, unchanged draft, input focus, visible Gboard and an idle agent. It repeats from the separate Practice app, then recreates MainActivity and checks the draft, conversation, input focus and keyboard again. All assertions passed. The test never clicks Start task or makes a model request.

The callback/focus implementation follows Android's [accessibility service button guidance](https://developer.android.com/guide/topics/ui/accessibility/views/service) and [Compose focus guidance](https://developer.android.com/develop/ui/compose/touch-input/focus/request-focus). Physical Xiaomi shortcut behavior remains to be checked after installation; no phone was attached during these tests.

## Build and regression checks

- **87 core JVM tests passed**, zero failures/errors/skips. Three new tests cover ordered background writes and flush completion, propagation of persistence failure, and Stop during an audit suspension preventing dispatch.
- Both dedicated UI instrumentation tests pass. The final benchmark also verifies persisted message count after the background writer's barrier.
- Debug, instrumentation and unsigned release APK builds pass. Lint: **0 errors, 15 pre-existing warnings**. Wrapper, release manifest/component, permissions and Greek-resource audits pass.
- **Live ChatGPT screen-change regression passed**, `automaticAccessRecoversAfterScreenChanges`, **45.258 s** harness time. The actual task rejected a stale tap, observed again, opened/tapped/typed successfully, and independently verified Counter: 1 and the expected text with zero approvals. A coordinate tap outside floating controls then produced Counter: 2. The unknown-overlay negative check and task-screen UI revocation both passed. The first run completed the task and safety checks but failed to locate the offscreen revoke button during teardown; the test now waits for the app/IME transition and scrolls the lazy chat to the beginning before clicking it.
- The general mock `DeviceAcceptanceTest` was not run on the signed-in emulator because its setup replaces profile selection. Existing account-specific configuration was preserved.

## Reproduction and evidence

Local evidence is in ignored `artifacts/ui-performance/`: baseline/final instrumentation logs, sanitized timing counters, core test XML, lint/build reports and the final APKs. No raw model responses, credentials or personal screen captures were collected.

```sh
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lint :app:assembleRelease
python3 tools/release-check.py
adb -s emulator-5582 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5582 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5582 shell am instrument -w -r \
  -e class dev.magicphone.app.UiResponsivenessTest \
  dev.magicphone.app.test/androidx.test.runner.AndroidJUnitRunner
```

This harness refuses non-emulator devices. It uses temporary synthetic history and restores the original archive; it never configures the mock model or signs out of ChatGPT. Live inference remains a separate opt-in test requiring account-owner authorization.

## Final APK identity

| File | SHA-256 |
|---|---|
| `magicphone-debug.apk` | `3efd18be613ca69dc6b7613b0e13f92475d07ddb72567475e1392b9eefa4932e` |
| `magicphone-release-unsigned.apk` | `8c072417477fcb0c0aa135182f69706b731a2f69cd1b7e9d24c0e7c2ea478abc` |

The debug APK passes `apksigner verify` and retains the same certificate SHA-256 as previous `.52` builds: `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`. It can update the existing installation without an uninstall. Release remains unsigned; no production key or public release upload was used.

The LAN download (private test network) is refreshed, and the built APK, installed emulator `base.apk` and HTTP download have identical hashes. The installable local copy is `artifacts/ui-performance/magicphone-debug.apk`. APKs and `SHA256SUMS` were refreshed using atomic file replacements.

After instrumentation, the emulator's already-enabled service was rebound, the Android shortcut was assigned to MagicPhone, and the current chat was opened through the new shortcut intent. Final read-only status confirms **0.1.3**, Accessibility connected, the existing ChatGPT account selected, model configured, faster decisions on and automatic app access off after test revocation. No account/profile reset or data clear was performed.
