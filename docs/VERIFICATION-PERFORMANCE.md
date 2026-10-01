# 0.1.2 performance verification

Date: 2026-10-01. Build: **0.1.2, version code 3**. Host: `qa-build-host`; visible, dedicated Android 15/API 35 ARM64 emulator `MagicPhone_QA35`, serial `emulator-5582`.

This is the artifact-specific 0.1.2 record. The LAN download is subsequently updated to **0.1.3**; see [UI/shortcut verification](VERIFICATION-UI.md) for its current checksum and changes. The original 0.1.2 APK remains in the local evidence directory below.

The account owner explicitly authorized live ChatGPT testing and had already signed in. These checks used the same selected **`gpt-6-astra`** model, account and provider throughout. No profile was replaced by a mock and no personal Instagram actions were executed. The target was the separate `dev.magicphone.fixture` Practice app. Login, model selection and existing data were preserved with update-in-place installs.

## Implemented changes

1. **Return fresh observations with actions.** A dispatched OPEN/TAP/TEXT/navigation action is followed by a new OBSERVE through the authoritative gateway. That screen is included in the same tool result, removing a model round trip that previously just requested another observation. Reads still validate current permissions; targets come from the new snapshot. A read failure remains separate from a dispatched action and cannot cause that action to be replayed.
2. **Supply useful context before inference.** The permitted app list and, when readable, the current foreground screen are supplied before the first model request. Prompts tell the model to act directly for short tasks, avoid unnecessary PLAN/CHECKLIST/APPS calls, and use the returned screen evidence. App labels, screen content and notes remain untrusted data.
3. **Request lower reasoning effort.** The default-on, English/Greek **Faster model decisions** setting requests `reasoning.effort=low` for explicitly supported Responses models. It retains the selected model/account. Turning it off restores the provider default; unknown/custom models and compatible providers omit the override. Live counters confirmed `effort=low` and successful inference on the selected model. This records the requested effort, not private server reasoning details.
4. **Wait for screen readiness.** Fixed 700 ms launch and 250 ms post-action sleeps were replaced by bounded polling: an unchanged readable screen for 80 ms, checked every 40 ms, with launch/action maximum waits of 1,200/350 ms. Every later operation still revalidates its screen and policy. Explicit condition waits and approval-overlay cleanup retain their separate behavior.
5. **Expose timing.** The task screen now displays elapsed time, model request count, model/network time and phone/tool time. Monotonic timing samples contain only phase, operation enum, counts and milliseconds. Scripts record their execution time without model requests. No raw arguments, screen content, credentials or model payloads appear in timing samples.

## Controlled before/after comparison

Each benchmark run entered the following prompt through the actual MagicPhone task field and clicked Start task:

> Open MagicPhone Practice, tap its Add one button exactly once, enter MagicPhone ready in its Ordinary text field, and verify the counter is 1 and the text is present. Do not open the manual-secret field. Then finish.

The fixture was force-stopped before each task so its counter began at zero; every task used a new conversation. Floating controls were on. The harness clicked the actual automatic-access button, with no individual fixture permission, and asserted that no approval appeared. It independently checked the visible counter and text after completion. It then tested unknown-overlay rejection and revoked automatic access through the UI. The three baseline tasks ran before the three optimized tasks on the same emulator/account/model. This is a small sequential comparison, not a randomized load-controlled service benchmark.

The baseline had the previous 0.1.1 behavior plus payload-free timing instrumentation. The optimized comparison used the new action loop, low-effort request and readiness polling. Results below are **task-run elapsed times**, excluding harness setup/teardown:

| Run | Baseline elapsed | Optimized elapsed | Baseline model calls | Optimized model calls |
|---|---:|---:|---:|---:|
| 1 | 58.414 s | 21.931 s | 9 | 4 |
| 2 | 49.191 s | 22.940 s | 9 | 4 |
| 3 | 48.100 s | 20.779 s | 9 | 4 |
| **Median** | **49.191 s** | **21.931 s** | **9** | **4** |

| Median component | Baseline | Optimized |
|---|---:|---:|
| Model/network combined | 47.444 s | 21.037 s |
| Phone/tool execution | 1.642 s | 0.828 s |

The task median fell by **55.4%**, equivalent to **2.24×** the previous speed for this fixture workflow. Phone/tool time fell by approximately 50%. The baseline used separate model rounds for PLAN → APPS → OPEN → OBSERVE → TAP → OBSERVE → TEXT → OBSERVE → COMPLETE. The optimized sequence supplied APPS locally, then used four model responses: OPEN plus automatic OBSERVE, TAP plus automatic OBSERVE, TEXT plus automatic OBSERVE, and COMPLETE. All six task runs completed with the expected real Android effects.

Model/network timing wraps provider work including authentication preparation, HTTP and model execution; it cannot separate network transit from server computation. In approval mode, phone/tool timing includes time waiting for local approval. Component medians need not add to the elapsed median; small persistence/orchestration costs are outside those two timers. Model/network work still accounts for approximately 96% of the optimized task. The improvements do not make remote inference instantaneous, and these measurements do not establish Instagram-specific performance or isolate the contribution of each individual change.

The baseline three-run instrumentation harness took **177.866 s** in total; the optimized harness took **87.919 s**. Those numbers include setup, UI interaction and negative checks and are not the task latency numbers above.

## Regression and build checks

- **84 core JVM tests passed**, zero failures/errors/skips. New coverage includes supplied initial context, fresh post-action references, metadata-only metrics, blocked foreground reads, read revocation after mutation, no replay/unverified completion after a failed follow-up observation, script timing, low-effort request serialization/defaults and readiness reset on content/geometry/unsafe-screen changes.
- **Live screen-change recovery passed:** `automaticAccessRecoversAfterScreenChanges`, harness time **42.258 s**, task time **34.135 s**. A visible fixture update between observation and tap caused `not_dispatched_stale_target`. The model observed again, proposed a fresh tap and completed with Counter: 1 and the expected text, with zero approvals. A separate coordinate tap outside floating controls then changed the visible counter to 2. Unknown-overlay rejection and UI revocation passed.
- **Live ordinary-approval flow passed:** `chatGptTapsInsideFixtureFromTaskScreen`, harness time **20.186 s**, task time **16.607 s**. Actual UI approvals were clicked for OPEN and one semantic TAP; the visible counter was 1. Automatic access was off. The unknown-overlay negative check also passed.
- The final packaging rebuild adds script-block timing and changes the timing-label wording; the benchmarked device/model loop is identical. The final installed-APK open/tap/type smoke **passed** with four model calls and zero approvals. Harness time was **49.292 s**; task time was **41.450 s**, comprising **40.582 s** model/network and **0.808 s** phone/tools. One model/network request took **26.382 s**. This additional run is reported separately from the planned three-run comparison above, and demonstrates material cloud latency variation. Across all four successful optimized open/tap/type runs, elapsed time ranges from **20.779 to 41.450 s**, with median **22.436 s**.
- Debug, instrumentation and unsigned release APKs compile. Release audit checks wrapper hash, component exports, prohibited permissions and English/Greek resources. Lint has **0 errors and 15 warnings**, covering the deliberate target/min API choices, available dependency updates, unused resources and KTX suggestions. No new warning from the timing UI remains.
- The general seven-test mock `DeviceAcceptanceTest` suite was not rerun on this signed-in emulator because its setup replaces test preferences/profile selection. Its earlier API 30/35 results remain historical. No physical phone was attached for this performance comparison.

## Artifacts and reproducibility

Local ignored evidence is under `artifacts/performance/`: baseline source snapshots/APK, baseline/optimized timing records, instrumented live reports, final build/lint/core XML reports and APKs. Only sanitized protocol counters and operation/timing metadata were retrieved from logcat. Timing samples are not telemetry.

After account-owner authorization on the configured dedicated emulator:

```sh
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lint :app:assembleRelease
python3 tools/release-check.py
adb -s emulator-5582 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5582 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5582 shell am instrument -w -r \
  -e liveChatGpt true -e repeats 3 \
  -e class dev.magicphone.app.LiveChatGptTest#benchmarkOpenTapType \
  dev.magicphone.app.test/androidx.test.runner.AndroidJUnitRunner
```

Run the recovery and approval methods separately using the same opt-in argument. These tests incur real requests on the selected account. Never use this harness on a physical phone. It may rebind only the already-enabled Accessibility service on the dedicated emulator. No credentials or app data need to be extracted to repeat the tests.

## Final artifact identity

The debug APK is signed with the same `.52` Android Debug key used for the preceding update. Its certificate SHA-256 is `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`; `apksigner verify --print-certs` succeeds. The release artifact remains unsigned. No production signing or public release upload was performed.

| File | SHA-256 |
|---|---|
| `magicphone-debug.apk` | `687784297101772008d9a4998ecf619d2ab1d431a3d570e11bee058e32707df1` |
| `magicphone-release-unsigned.apk` | `96649360fad3d344427a49f2db5c9331e236692c4feebe5265ad98c292f144d3` |

The installable update is served at the user's LAN APK server (private test network). Install over the existing app without uninstalling. The Python server remains running on the requested host, and `SHA256SUMS` was refreshed atomically with the APK files.

The built file, installed emulator `base.apk` and bytes downloaded over HTTP have the same SHA-256 above. After instrumentation, the emulator's already-enabled Accessibility service was rebound and MagicPhone was left open. Final read-only status confirms **0.1.2**, Accessibility connected, ChatGPT account selected, model configured, fast decisions on and automatic app access off after the harness revoked it. No login/profile reset or uninstall was performed. The same final debug APK is retained locally at `artifacts/performance/optimized/magicphone-debug.apk`.

## Sources checked

The low-effort request follows the official [reasoning guide](https://developers.openai.com/api/docs/guides/reasoning); ChatGPT requests preserve the documented [OSS preview constraints](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations). Reducing unnecessary sequential requests follows the [latency optimization guidance](https://developers.openai.com/api/docs/guides/latency-optimization). The measured effect above comes from this repository's emulator tests, not a predicted vendor latency.
