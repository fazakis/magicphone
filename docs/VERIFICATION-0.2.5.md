# MagicPhone 0.2.5: popup dictation, fresh screen context and progress

Version code **12**. Verified on 2026-10-03 for the user-authorized GitHub release.

## Behavior

- Every Accessibility-popup Send starts a fresh model turn in the same conversation, retaining conversation history and capturing the currently open permitted app before model inference. This also applies to follow-up prompts when the preceding task is waiting for input or paused. Opening the popup alone captures nothing.
- The popup and keyboard are dismissed before capture. Window polling exits immediately when they have disappeared. The working bubble is created only after the initial capture finishes, so it does not obscure that image. Images go only to providers supporting images; text-only providers still receive the available screen text.
- Temporary read failures get at most two local screenshot retries, after 350 ms and 700 ms. There is no retry delay on a successful initial capture. After transient exhaustion, the model continues with available content. This does not replay mutations or bypass app permissions, credential checks, secure windows, lock checks or protected controls. The redundant 1.2-second local screenshot throttle was removed; Android's actual capture-rate error is still handled.
- A compact, non-focusable working bubble shows Thinking, Reading the screen, Opening the app, Working in the app or Preparing your answer. Status changes reuse the same view, coalesce at 750 ms and fade gently. Stop cancels pending work and removes the bubble. Terminal results and requests for input use the existing answer/question bubbles. The working control is hidden while the full chat or prompt is visible, and during lock/voice input; it is recreated for changed display bounds.
- The popup input now has an Android dictation microphone. The installed recognizer opens through a private translucent activity, then returns to the same app and popup. Returned speech appends to the editable draft without sending it; Cancel keeps the draft. No microphone permission, network destination, dependency or exported component was added. Recognition availability and language support depend on the installed Android provider.

## Verification

| Check | Result |
|---|---|
| Core tests | **118 passed**, no failures/errors/skips |
| Android 15/API 35 full suite | **34 executed, 1 skipped**, no failures, 297.242 s (runner reports 35 cases) |
| Android 11/API 30 compatibility | **9 executed, 0 skipped**, no failures, 147.218 s (runner reports 9 cases) |
| Separate fold/resize regression | **1 passed**, 9.388 s |
| Debug, instrumentation, fixture and unsigned release builds | Pass via the checked-in Gradle wrapper |
| Lint | **0 errors / 12 warnings** |
| Release component/permission audit, wrapper checksum, English/Greek resource parity | Pass |
| APK identity | Same signing certificate; installed with `adb install -r` |

The five new popup device cases cover initial screenshot pixels without the working overlay, owned-overlay recognition, protected Stop coordinates, stable progress updates, cancellation of an in-flight provider, transition to answer/question/error bubbles, fresh screenshots for follow-up prompts in the same chat, Greek dictation results, cancellation preserving drafts, and opening/canceling the actual installed speech recognizer. Greek transcript insertion is a controlled activity-result test; acoustic recognition accuracy was not measured. Actual popup/progress screenshots were visually inspected.

The first focused run had one test-harness failure: an IntentFilter-based speech monitor also intercepted the private launcher with a null action. The monitor now matches the speech action exactly. All five focused cases passed after that correction in 25.328 s; the final full runs above are separate verification. No production test bypass was added. The first broad API 35 run also had one intermittent image-count failure in a diagnostic that still invoked the legacy one-attempt current-screen mode. That diagnostic now uses the same explicit capture mode and bounded recovery as popup submission; the final broad suite above passed. The production APK was unchanged between those broad runs.

The API 35 suite also exercises existing screen-read recovery, partial document images, changing toolbars, keyboard masking, credential/secure-window checks, answer bubbles, text-to-speech, notification controls and UI responsiveness. Authorized live ChatGPT tasks use only the synthetic fixture: a current-screen request returning Greek and a plain/partial document comparison. The signed-in emulator was restored to its original account/settings, 27 conversations and 350 audit entries; the installed APK hash matches the release asset. Temporary API 30 and fold emulators were shut down after evidence collection. These checks establish functionality, not a speed guarantee.

The API 30 selection runs the five popup cases and four document/keyboard/toolbar compatibility checks against the display-capture path. The separate API 35 foldable emulator receives fold/unfold and WindowManager resize commands at 1768 × 2208 → 884 × 2208 → 1768 × 2208; 18 observations and three captures succeed. This does not reproduce Xiaomi's exact cover/inner-display implementation. Physical devices, other OEMs and Android 12–14 were not separately exercised. Hosted CI results are separate from the dedicated emulator results above.

## Artifacts and reproduction

Ignored local evidence: `artifacts/release-0.2.5/`. Public release assets contain the installable APK, SHA256SUMS, SIGNING.txt and four license/notice files; the unsigned release and instrumentation builds remain local.

| Artifact | SHA-256 |
|---|---|
| magicphone-0.2.5-debug.apk | `a90da97f1278b33b638de5e823406ce5240512c964801a291a14031261a6f26a` |
| magicphone-0.2.5-release-unsigned.apk | `08fc13db8ffea1cff4f54fc52fc915b1d3cafc92c064bb2b5a621bfba213fb81` |

Certificate SHA-256: `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`. This remains the existing early-access development APK, signed with the Android debug certificate. Install over a matching-signer build to retain settings, sign-in and chats.

```sh
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :fixture:assembleDebug :app:assembleRelease :app:lint
python3 tools/release-check.py
# Dedicated configured emulator with app/test/fixture installed.
# liveChatGpt=true requires account-owner consent.
adb -s emulator-5582 shell am instrument -w -r -e liveChatGpt true \
  -e class dev.magicphone.app.PopupWorkflowTest,dev.magicphone.app.ScreenReadDiagnosticTest,dev.magicphone.app.ScreenReadRecoveryTest,dev.magicphone.app.InputBubbleTest,dev.magicphone.app.VoiceAndControlsTest,dev.magicphone.app.UiResponsivenessTest \
  dev.magicphone.app.test/androidx.test.runner.AndroidJUnitRunner
```

Fold host coordination and limitations are described in [0.2.4 verification](VERIFICATION-0.2.4.md).
