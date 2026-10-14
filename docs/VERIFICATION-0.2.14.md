# MagicPhone 0.2.14 verification

Verified on 2026-10-14. Version code 21. This release preserves a spoken Accessibility explanation when another popup temporarily takes focus.

## Reproduction and fix

On the previous signed 0.2.13 APK, the new foreign-dialog regression reproduced the report: opening the isolated window-fixture popup changed the foreground package, and the explanation monitor called `stop()`. That cleared the plan, current section and player even after the popup was dismissed. The test failed with `armed=false`, `section=0` and `regions=null` in 25.665 seconds. Android's separate feedback-interruption callback also called the same destructive cleanup.

Temporary focus loss now suspends presentation while retaining the conversation, plan, section, playback position and manual-pause state. Speech and highlights stop and presentation windows hide while another app or popup has focus. Active explanation planning is paused. Returning to the original app restores the player and requests fresh screen context for the current and remaining sections before showing highlights or resuming speech. An already paused session stays paused. The interrupted spoken section may repeat; this is section-level continuation, not word-level seeking.

Android's `onInterrupt()` now preserves the session and requires the user's Resume action instead of deleting it. Explicit Stop, conversation changes, screen locking and service destruction still end playback. Progress controls do not reappear over the interrupting window. Device observation and re-anchoring remain behind the existing Gateway; no permission, protected-window or foreign-content rule is bypassed.

## Build and signing

- **152 core tests pass**, with no failures, errors or skips.
- Release lint: **0 errors / 18 warnings**.
- Checked-in wrapper release build, test-harness build and release audit pass.
- APK: `magicphone-0.2.14-release.apk`, SHA-256 `93b582fcd90e833ddb1fcddab590fb03e75a09e20056ea83289d23ac75ce6e95`.
- Package `dev.magicphone.app`, Android 11 / API 30+, non-debuggable.
- Release certificate SHA-256 `e15cef3dd788893f862ff20f7a19f08de96c3450bb2acf9eb1331a40a3b979b2`; signing identity and rotation lineage unchanged.
- Installed as an in-place update over 0.2.13 on the dedicated API 35 emulator. No uninstall, new dependency or new permission.

## Device verification

All four new targeted cases pass on the signed release APK in **30.585 seconds**, using real Accessibility windows and Android TTS:

1. A separate synthetic foreign dialog appears during speech. The plan and section remain retained, speech/controls/highlights hide, and no new capture occurs while it stays open. Pressing the dialog's Close button restores playback with a fresh snapshot and highlights in the same chat, without another user prompt. The foreign app is not read by the model.
2. Opening and dismissing the actual Android notification shade preserves manual Pause. Resume starts a refreshed explanation. Explicit Stop during a subsequent interruption prevents the player from returning.
3. The service feedback-interruption callback leaves a paused, resumable player. Resume starts speech again.
4. An interruption during the second section sends exactly that section and the remaining section to the re-anchoring callback. An already completed section is excluded.

The broader selected suite ran 37 cases: **33 passed**, three live-inference cases were intentionally skipped and one older background-draft test failed. That test assumed an empty popup, while preceding popup/voice tests left a retained draft that Open chat intentionally transfers into the full composer. The harness now explicitly empties that input before testing the separate existing-chat-draft path; a diagnostic confirms a prior popup draft was present. No app code or APK changed for this test-isolation correction.

The final popup/voice group then passed **13 cases with one intentionally skipped live-inference case** in **103.708 seconds**. This yields **34 distinct deterministic device checks passing across the broader run and final group**, including all 14 deterministic guided-explanation cases, popup actions/voice/capture, background shortcut focus, notification controls and device acceptance checks. The earlier combined run is not represented as entirely green.

## Limits

The reproduction uses a separate synthetic dialog and the actual Android notification shade on API 35. A physical Xiaomi low-battery dialog and every OEM popup have not been exercised. The fix retains an in-memory listening session across temporary focus changes; it does not resume automatically after Android kills the process, Accessibility is disabled or the device locks. Re-anchoring can require another model request and therefore depends on the selected provider and connection. Existing document-reading and highlight-accuracy limits from [0.2.13](VERIFICATION-0.2.13.md) still apply. No new live account inference is needed or claimed for these lifecycle regressions.
