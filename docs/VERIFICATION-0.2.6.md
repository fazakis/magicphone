# MagicPhone 0.2.6: popup action continuity and configurable sensitive checks

Version code **13**. Verified on 2026-10-03 as the requested follow-up to 0.2.5.

## Reproduction and changes

On the unchanged 0.2.5 production APK, a synthetic popup tap/type task reproduced an initial `not_dispatched_stale_target` after the working bubble appeared. After the tap succeeded, the model still had the initial screenshot. The regression failed on the stale image. This establishes two popup-flow defects; the user's generic Chrome error screenshot alone does not identify that phone's exact failure code or prove a sensitive-content false positive.

- Gateway now hides only the working bubble across device inspection, validation and dispatch. It returns after a 200 ms debounce that does not delay tools. A control directly underneath the bubble can be observed and tapped without a stale retry. Approval/reply controls retain their protection, and the notification's Stop action stays available. Run identity is captured before overlay removal so Stop/restart cannot revive an old operation.
- Popup tasks with an image-capable provider refresh screenshots after dispatched actions and explicit observations, including navigation to another permitted app. The previous automatic screenshot is removed from model context; user attachments are preserved. Screenshot retries remain bounded, and uncertain actions are never automatically replayed.
- Invalid model tool parameters get matched correction feedback instead of immediately becoming the generic connection/settings error. All calls from a malformed response are withheld, including valid siblings. Two correction opportunities are allowed before a specific error. Invalid targets also receive fresh-observation feedback; popup completion can be deferred twice for verification of an earlier action. This does not claim an unobserved outcome succeeded.
- **Settings → Access → Check sensitive content** defaults on for existing and new installations. Turning it off stops the current run and saves the local choice; detected-sensitive-screen/node restrictions, password filtering and the credential-scan image gate are disabled. Visible sensitive information may reach the selected model. Explicit app blocks, ordinary permissions, locked/mixed windows, stale-target rules, protected system controls and Android's secure-window restriction remain independent. This setting cannot be changed by a model, script or backup import.
- Device-target, verification-limit and malformed-model-action errors have specific English/Greek messages. No permission, exported component or dependency was added.

## Verification

| Check | Result |
|---|---|
| Final core tests | **125 passed**, zero failures/errors/skips |
| Broad API 35 regression before final validation hardening | **38 passed / 1 fold-host opt-in skipped**, 352.299 s; runner reports 39 cases |
| Final APK API 35 popup suite | **9 passed**, 63.077 s; includes live ChatGPT tap/type task |
| Final APK API 30 compatibility | **12 passed / 1 live-account opt-in skipped**, 152.485 s; runner reports 13 cases |
| Final settings visual/behavior check on API 35 | **1 passed**, 6.577 s |
| Debug, test, fixture and unsigned release builds | Pass via checked-in wrapper |
| Lint | **0 errors / 12 warnings** |
| Wrapper checksum, release components/permissions and English/Greek parity | Pass |
| Signing and installation | Same certificate; `adb install -r` upgrade succeeds |

The broad suite covers document reads, partial trees, keyboard masking, credentials/secure windows, notification controls, completion/error/question bubbles, real speech engine integration and UI responsiveness. The final APK differs from that broad run by the malformed-call recovery and run-generation guard; those changes have targeted core tests, and the complete nine-case popup suite plus API 30 compatibility were run on the final APK. The last test-only adjustments prepare onboarding on the disposable API 30 emulator, scroll settings when needed and wait for switch animation before screenshots; production bytes are unchanged.

New device cases verify fresh image content after a tap, one retained automatic screenshot, subsequent text input, interaction with a covered control, persistent sensitive-check opt-out/on behavior, synthetic password-field observation/capture/input, and API 35 secure-window rejection even with the switch off. Existing Stop and popup dictation checks continue to pass. Initial API 30 runs could not find Settings. Sanitized activity diagnostics showed that the disposable emulator had never completed onboarding; the test now temporarily prepares that state and restores it afterward. The final suite passed.

With explicit account-owner consent, live ChatGPT completes a task submitted through the actual Accessibility popup: press Add one exactly once, enter `Popup live verified`, observe Counter: 1 and the entered text, then complete. At least three images are captured through Gateway. Testing uses only the disposable fixture, without third-party account actions. The signed-in emulator is restored to its original account/settings, **27 conversations and 350 audit entries**, with sensitive checks on. The installed final APK hash matches the release asset.

Physical Xiaomi/Chrome behavior and arbitrary third-party app workflows were not directly tested. Model choices, Android app behavior, app permissions and Android restrictions can still limit a requested action. This release does not promise every app supports every operation. Hosted CI is separate from the dedicated-host verification. The earlier fold/resize result is documented in [0.2.5 verification](VERIFICATION-0.2.5.md); folding was not rerun for this update.

## Artifacts and reproduction

Local ignored evidence: `artifacts/release-0.2.6/`. Public assets are the APK, checksums, signing identity and four license/notice files. The unsigned release build remains local.

| Artifact | SHA-256 |
|---|---|
| magicphone-0.2.6-debug.apk | `5623ecbaba8d6e02b04281f567dcd805d6d8e4f5f7610c079323f7983152a61c` |
| magicphone-0.2.6-release-unsigned.apk | `784e09a1f5460331a901441ff320851b1a35c56c20fc3b00ddbac2104ceeab63` |

Certificate SHA-256: `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`. The update remains an early-access development APK using the existing Android debug certificate. Matching-signer upgrades preserve app data.

```sh
./gradlew :core:test :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :fixture:assembleDebug :app:lint
python3 tools/release-check.py
# Dedicated configured emulator; liveChatGpt requires account-owner consent.
adb -s emulator-5582 shell am instrument -w -r -e liveChatGpt true \
  -e class dev.magicphone.app.PopupWorkflowTest \
  dev.magicphone.app.test/androidx.test.runner.AndroidJUnitRunner
```
