# Live ChatGPT app-control verification

Date: 2026-10-01. Host: `qa-build-host`, dedicated Android 15 emulator `emulator-5582`.

This record documents the original live integration and fixes through **0.1.1**. The newer **0.1.2** APK, speed measurements and regression results are recorded in [performance verification](VERIFICATION-PERFORMANCE.md).

The account owner manually signed in to ChatGPT in MagicPhone and explicitly authorized testing with that subscription. The test preserved the selected ChatGPT profile, account and model. No credentials were copied to the host or logged. The already-installed `dev.magicphone.fixture` application was selected as the target, with Read/Act enabled; the local deterministic demo provider was not selected.

## Reproduction and fix

The first actual live request reproduced the user's blank-task failure. Protocol counters showed 26 events, one completed function-call item, 20 argument deltas, **zero items in `response.completed.output`**, zero dispatched operations, and `empty_model_response`.

`ResponsesProvider` previously read function calls only from the terminal output array. It now retains full `response.output_item.done` items by output index and uses them when the terminal array is empty. It still waits for `response.completed` and the entire stream to finish before returning executable calls. Failed or interrupted streams remain inert. Missing/duplicate output indexes fail closed. Completed items are also preserved in the next request's conversation context.

A JVM regression recreates this exact transport shape, verifies item ordering/text/tool-call preservation, and confirms that interrupted, failed or incomplete-index streams cannot return calls.

## Successful live run

The opt-in emulator test entered this prompt in the actual MagicPhone task text field and clicked **Start task**:

> Open MagicPhone Practice. Verify that its screen is visible, then finish.

The resulting gateway sequence was:

1. `PLAN:updated`
2. `APPS:apps`
3. `OPEN:dispatched` — after the test clicked the visible local approval for this exact fixture launch.
4. `OBSERVE:observed`
5. `COMPLETE:reported`

Final state: **COMPLETED**, no error. The test independently checked that the fixture package was visible; Android's resumed activity was `dev.magicphone.fixture/.FixtureActivity`. A screenshot confirms the Practice screen and unchanged counter at zero. The live test passed in **27.73 seconds**.

The final request again had an empty terminal output array, while the parser correctly retained and handled its completed function call. This confirms the fix against the live response shape that caused the original failure.

## Other checks and artifacts

### Follow-up: in-app tap with floating controls

The user then reported a screen-change error when attempting actions inside an app. The extended live test reproduced `screen_uncertain` immediately after OPEN with floating controls enabled. Debug flags showed a readable target app, no lock, no mixed application/system window, and exactly one overlay whose window ID matched MagicPhone's attached control view; the prior root/package-name check nevertheless classified that overlay as foreign.

`PhoneService` now recognizes only the exact Accessibility window IDs of its attached approval/control views. Untracked overlay windows remain blocked even when their root has the same application package. The floating controls also remain attached across planning/acting steps instead of being removed and recreated on every state change. Fresh snapshot binding, approval revalidation, protected control bounds and other safety checks are unchanged.

The real ChatGPT task was submitted through the task field:

> Open MagicPhone Practice, tap its Add one button exactly once, and verify that the counter is 1. Then finish.

It passed in **35.836 seconds**, with floating controls enabled and actual UI approvals for OPEN and TAP. Recorded operations: APPS → PLAN → OPEN → OBSERVE → TAP → OBSERVE → COMPLETE. UI assertions and the saved screenshot independently confirm **Counter: 1**. The same test then created an untracked service overlay and confirmed that the gateway rejected observation with `screen_uncertain`. Evidence is retained in `artifacts/live-tap/`.

The debug/unsigned release builds, lint (**0 errors, 15 warnings**), release audit and **68 core tests** pass. The updated emulator APK and HTTP download are identical; the downloaded file's SHA-256 was verified. This adds live semantic-tap coverage; it does not establish every action in every third-party app.

- **68 core JVM tests passed**, zero failures/errors/skips.
- Debug APK and unsigned release build; lint passes; release component, permission, wrapper checksum and Greek resource checks pass.
- The emulator keyboard issue was also verified after disabling `stylus_handwriting_enabled`: a single tap on the fixture's ordinary text field opens the full Gboard keyboard. `keyboard-verified.png` records the result; no text was entered.
- Earlier seven-test API 35 device coverage remains documented separately. This run adds one actual account-backed, UI-started integration check; it does not relabel fixture mocks as live tests.
- The live test is guarded by the explicit `liveChatGpt=true` instrumentation argument and an emulator check. It requires an already-enabled Accessibility service and an existing ChatGPT sign-in. Instrumentation rebinds that existing service after restarting the app process; it never runs on the physical phone.
- Do not run the general `DeviceAcceptanceTest` suite on this signed-in emulator: its deterministic setup changes test preferences and selects the mock profile. Use a fresh dedicated AVD for that suite.
- Evidence: ignored `artifacts/live-chatgpt/`, including the failing response counters, passing instrumentation output, core XML results, screenshot, APKs and hashes.

### Follow-up: automatic app access without approvals

The Settings button **Allow all apps without asking** now enables persistent all-app Read/Act and skips device-action approvals. It defaults off. Explicit blocks and protected/sensitive/stale/locked/mixed-screen checks still apply; external MCP tools retain their consent and approval requirements. App listing, service observation and dispatch use the same effective app rule.

`automaticAccessOpensTapsAndTypesWithoutApprovals` passed on the same API 35 emulator in **55.678 seconds**. The test deliberately removed the fixture's individual app rule, began in plan-only mode, then clicked the actual Settings button. It verified that the option was enabled, plan-only was disabled and encrypted settings retained the choice. Through the actual task field it submitted:

> Open MagicPhone Practice, tap its Add one button exactly once, enter MagicPhone ready in its Ordinary text field, and verify the counter is 1 and the text is present. Do not open the manual-secret field. Then finish.

Actual gateway operations: **PLAN → APPS → OPEN → OBSERVE → TAP → OBSERVE → TEXT → OBSERVE → COMPLETE**. Final state was COMPLETED, error empty, approved-operation list empty. Independent UI assertions found **Counter: 1** and **MagicPhone ready**. No approval was clicked, and any pending approval would have failed the test. The floating controls were enabled throughout execution.

The same run confirmed that an untracked overlay still caused `screen_uncertain`. It then clicked the task-screen off button and verified `app_not_allowed` for the now-unlisted fixture. Automatic access was left off, and the existing ChatGPT profile/account/model were preserved. The test initially encountered a harness-only unnecessary-scroll error before task submission; removing that scroll allowed the complete run above.

**72 core JVM tests pass**, zero failures/errors/skips. Four new policy tests cover old settings defaulting off, unlisted-app access, ordinary text/semantic/coordinate actions, block and screen-safety precedence, unchanged MCP consent/approvals and revocation. Debug/unsigned release builds pass; lint remains **0 errors, 15 warnings**; release manifest/wrapper/resource audit passes. Evidence is retained in ignored `artifacts/automatic-access/`. The general seven-test mock fixture suite was not rerun on the signed-in emulator.

### Follow-up: changing screens and floating-control coordinates (0.1.1)

The user reported that Instagram actions on their updated phone still showed “The screen changed. Observe again and request a fresh approval.” No phone is currently attached to local ADB, so this check does not claim Instagram-specific or Xiaomi verification.

The emulator test `automaticAccessRecoversAfterScreenChanges` injects an ordinary-text change after the first OBSERVE while the live model is selecting a tap. Against the old runner this reproduced **FAILED / stale_target** in **28.095 seconds**, with no TAP dispatched. The exception previously ended the whole agent run, preventing re-observation.

The runner now returns known pre-execution device rejections to the model, cancels all remaining proposals from the response and requires OBSERVE before a new device operation. It does not replay stale actions, reuse old approvals or retry ambiguous dispatch. Recovery is limited to three rounds without a successful device action, within the existing run limits. Separate UI messages now identify screen changes, protected controls and uncertain observations. Policy also stops rejecting every coordinate merely because floating controls exist: tap points and conservative swipe bounds are checked against the actual panel bounds.

The final live test passed in **57.936 seconds**, with this actual operation sequence:

`PLAN → APPS → OPEN → OBSERVE → TAP:not_dispatched_stale_target → OBSERVE → TAP:dispatched → OBSERVE → TEXT:dispatched → OBSERVE → COMPLETE`

Final state was COMPLETED, error empty, and **zero approvals** were requested/clicked. The test independently found **Counter: 1** and **MagicPhone ready**. It then restored the floating controls and performed an additional coordinate TAP through the real gateway, outside their bounds: Android accepted it and the visible counter became **2**. Finally, the unknown-overlay rejection and task-screen automatic-access revocation checks passed. The test preserved the selected ChatGPT profile/account/model. The final packaging change updated floating-control help text; the functional live test precedes that text-only rebuild.

**77 core tests pass**, zero failures/errors/skips. Added coverage includes stale batch/call-tail cancellation, mandatory fresh observation, replacement approval, bounded repeated uncertainty, no ambiguous-dispatch/denied-permission retries, and coordinate tap/swipe bounds. Debug and unsigned release builds, lint (**0 errors, 15 warnings**) and the release audit pass. Evidence is retained in ignored `artifacts/screen-recovery/`, including the failing and passing runs, sanitized counters, core XML and APKs. The old generic error was not simply hidden; the stale action remains rejected and recovery is verified through new observations and actual Android effects.

Version **0.1.1 (code 2)** is displayed in Settings and installed on the emulator. Updating over the existing app preserves data; uninstalling is unnecessary. The emulator's Accessibility service was rebound after instrumentation, and final status confirms it is connected with the existing ChatGPT account selected. Automatic access is left off after test revocation.

Historical 0.1.1 LAN download artifacts (superseded by 0.1.2):

| File | SHA-256 |
|---|---|
| magicphone-debug.apk | c594c3d47ca2097169117046ab513dc638a03f56c2fa3413d3d531b731fd25f4 |
| magicphone-release-unsigned.apk | 4e138933bf4b7a074ef3c92465d0cfcbcab6b89fd8ea22250c285399a595f087 |

At the time of this check, the debug update was installed on the emulator and served at `http://<qa-download-host>:8080/magicphone-debug.apk`; that URL now serves the newer build identified in the performance record. The user's physical phone was disconnected before this fix. The user subsequently reported that the update worked well on their phone; this remains a user report rather than emulator or connected-device Instagram verification. Live image inference, token renewal/revocation, Instagram-specific screens and other external app workflows remain unverified by the harness.

## Reproduce only with account-owner authorization

Build `:app:assembleDebugAndroidTest`, install that test APK on the already-configured emulator, and run:

```sh
adb -s emulator-5582 shell am instrument -w -r \
  -e liveChatGpt true \
  -e class dev.magicphone.app.LiveChatGptTest#automaticAccessRecoversAfterScreenChanges \
  dev.magicphone.app.test/androidx.test.runner.AndroidJUnitRunner
```

This incurs real requests on the selected account. The recovery test requires no approval, exercises OPEN, TAP and TEXT, injects a mid-task screen update and adds a real coordinate dispatch check. The separate `automaticAccessOpensTapsAndTypesWithoutApprovals` test runs without the injected change. `chatGptTapsInsideFixtureFromTaskScreen` approves at most one OPEN and one semantic TAP for the fixture package; `chatGptOpensInstalledFixtureFromTaskScreen` tests only the launch flow.
