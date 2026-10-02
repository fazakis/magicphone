# MagicPhone 0.2.4: readable screenshots and fold-transition recovery

Version code **11**. Verified on 2026-10-02. The user authorized implementation and GitHub release publication. These results address the reproduced [0.2.3 screen-reading failures](DIAGNOSIS-SCREEN-READING-0.2.3.md).

## Changes

- A partial Accessibility text tree no longer suppresses the current-screen image. The agent requests an image through Gateway, while the device separately checks whether the visible credential scan completed. Text extraction budgets no longer disable an otherwise permitted capture.
- Android 14+ uses the target app's window screenshot API. Android 11–13 retains display capture with an app-content crop. Keyboard and owned floating-control rectangles are masked in both paths. App/window/focus, locked-device, foreign-overlay and credential checks still apply. The window API is documented by [Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)).
- Screenshot validation compares the app window, display, geometry and protection state instead of every text-node revision. Updating an unrelated toolbar no longer invalidates a read-only screenshot. The image is freshly captured from the currently permitted app; taps, typing and approvals retain exact snapshot/node validation.
- Screen dimensions, capture bounds and rotation are obtained for the selected display. A briefly absent foreground/root window is reacquired locally in 50 ms checks, up to 500 ms per lookup. Healthy reads have no added wait. This is a bounded window-transition recovery, not a two-second notice delay or a cloud retry round.
- Accessible labels preserve up to 8,000 characters per node with a 40,000-character aggregate budget. Truncation explicitly marks the observation partial. A separate bounded scan continues beyond the text traversal budget to find visible password fields; an incomplete credential scan still prevents an image.
- The existing two-second nonblocking notice and ordinary partial-text fallback remain. Permission revocation, secure windows, unknown overlays and password fields retain their checks. No Android permission, dependency or exported component was added.

## Verification

| Check | Result |
|---|---|
| Core tests | **112 passed**, zero failures/errors/skips |
| Android 15/API 35 release suite | Runner reports **30 cases, zero failures** in **283.050 s**: 29 executed, fold-host opt-in skipped and then run separately |
| Dedicated fold + resize regression | **1 passed** in **9.055 s**; 18/18 observations and 3/3 final screenshots succeed |
| Android 11/API 30 compatibility | **4 passed** in **80.097 s**, exercising the display-capture path |
| Debug, test, fixture and unsigned release builds | Pass using the checked-in wrapper |
| Lint | **0 errors / 12 warnings** |
| Release manifest, wrapper and English/Greek resources | Pass |
| Signing | Same certificate as prior updates; upgrade installed with `adb install -r` |

The full API 35 suite includes the new synthetic-document regressions, existing temporary-read/notice checks, current-screen overlay/chat flows, completion/error bubbles, real English/Greek speech, notification controls, voice input and UI responsiveness. The actual screenshot pixels supplied to the model were visually checked. A decoded-image check confirms the keyboard overlap is black; the readable document remains in the image.

The changing-toolbar case now captures **4/4 images**, compared with **0/4** on 0.2.3; the stable control remains **4/4**. The partial large-tree and keyboard cases each supply one image. A 727-character document node now retains its final code, and a longer node verifies explicit truncation. A password placed beyond the old 100-child text limit remains protected, and an API 35 FLAG_SECURE window rejects capture.

The separately authorized **live ChatGPT** comparison completes both the plain PDF and identical PDF with a large UI tree, reading the synthetic code correctly in **one model call each**, with a captured image and no ASK/user-input state. On 0.2.3 the large-tree case used three calls and asked for input without receiving an image. The existing live Greek current-screen task also passes. These are functional results, not a model-speed benchmark.

For folding, the isolated 7.6-inch API 35 emulator receives real fold/unfold commands alongside WindowManager resizing: **1768 × 2208 → 884 × 2208 → 1768 × 2208**. Every first and subsequent observation succeeds through the production Gateway, with the same Accessibility service instance and current bounds. The generic emulator does not reproduce Xiaomi's exact inner/outer display implementation; physical Xiaomi/Acrobat behavior remains external. Android 12–14 and other OEMs were not separately exercised.

The first focused run had one outdated assertion expecting the initial screenshot in a UI action-summary list; the provider had already received the image. The assertion was removed, and the full final suite passed. On the API 30 emulator, the old disposable fixture had a different signer and was replaced; MagicPhone itself updated successfully with its data preserved. These harness issues are not counted as passing runs.

Final review retained the pre-existing restriction on mutations outside the default display, separately from window reads, and limited the secure-window error assertion to Android 14+. After this final guard, all 112 core tests, builds, lint and audits passed again. The final APK also passed **two targeted device checks in 34.306 s**: both live PDF reads, the password beyond the text budget, and the secure-window rejection. In this last smoke run, the plain case recovered automatically after one initial capture failure and completed in three model calls; the partial-tree case completed in one. Neither requested user input. The larger API 35/API 30/fold suites above preceded this final guard.

The signed-in API 35 emulator was restored to its original selected account/settings, **27 conversations** and **350 audit entries**. Temporary fold/API 30 emulators were shut down after collecting evidence. No third-party personal app was used for testing.

## Artifacts

Local ignored evidence: `artifacts/release-0.2.4/`. Public assets are the installable debug APK, SHA256SUMS, SIGNING.txt and four license/notice files. The unsigned release build is local audit evidence, not a public update asset.

| Artifact | SHA-256 |
|---|---|
| magicphone-0.2.4-debug.apk | `011345954490a481287febebe78388188d7b37f8e9773870e4cbb9b63a05b700` |
| magicphone-0.2.4-release-unsigned.apk | `8c4ba870dac20933fb99279a60f6e439328f6558e161a23d899ba60622532df2` |

Signing certificate SHA-256: `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`. The distributed APK remains an early-access development build signed with the existing Android debug certificate.

## Reproduce

```sh
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :fixture:assembleDebug :app:assembleRelease :app:lint
python3 tools/release-check.py
# Install app, test and fixture APKs on a dedicated configured emulator.
# liveChatGpt=true requires account-owner consent.
adb -s emulator-5582 shell am instrument -w -r -e liveChatGpt true \
  -e class dev.magicphone.app.ScreenReadDiagnosticTest,dev.magicphone.app.ScreenReadRecoveryTest,dev.magicphone.app.InputBubbleTest,dev.magicphone.app.VoiceAndControlsTest,dev.magicphone.app.UiResponsivenessTest \
  dev.magicphone.app.test/androidx.test.runner.AndroidJUnitRunner
```

Fold instrumentation uses `foldDevice=true` and `resizeTransitions=true`, with the `fold-ready`/`fold-go` host coordination described in the diagnostic record. API 30 verification selects the plain/large document, keyboard masking, long text and changing-toolbar cases without cloud inference. Hosted CI results are separate from this emulator verification.
