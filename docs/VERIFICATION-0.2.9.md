# MagicPhone 0.2.9: visible windows, focus and occlusion

Version code **16**. Verified on 2026-10-03.

## Problem and behavior

The earlier physical MIX Fold 2 investigation found that a small Xiaomi caption window made Chrome fail the blanket mixed-window rule. The local 0.2.8 hotfix completed the owner's actual Chrome task; [its record](VERIFICATION-0.2.8.md) describes that separate physical evidence. It was not published on GitHub.

0.2.9 replaces that device-specific heuristic with geometry applicable to overlapping applications, dialogs, system decorations and split-screen panes. A permitted app can be observed while visible but unfocused. Higher windows mask only covered areas. An unfocused TAP uses a physical gesture at a freshly validated visible point; subsequent typing, scrolling, long presses and global navigation require a fresh focused observation. The focus tap can also activate the control, so the agent observes before continuing. For a modal surface that retains focus, the model can OPEN the permitted app instead of repeating taps.

Foreign trees are not traversed. Covered/clipped nodes are omitted, screenshots are cropped and masked, and coordinates must stay inside the selected window's uncovered regions. Moving/resizing windows and focus changes invalidate old snapshots. App denies, protected packages, secret checks, locks, Stop and Android secure-window enforcement remain separate. Unknown geometry and completely covered windows cannot be treated as readable.

## Additional Android 11 issue found and fixed

The moved-window test initially failed on API 30. Waiting longer and refreshing the client service cache did not fix it. Window Manager showed the fixture moving from `(540,1343)–(940,1693)` to `(540,1023)–(940,1373)`, while Accessibility's window list kept the original rectangle. Refreshing the window's root returned the new geometry immediately.

The production API 30–32 path now refreshes application-root geometry without traversing foreign contents, and protects both reported and refreshed bounds while they disagree. Failed geometry refreshes remain unreadable. This invalidates the old snapshot and masks the newly covered area, while keeping other visible areas usable. The regression passes on the final APK. No service-config workaround or test-only cache refresh remains in the test. A separate keyboard regression confirmed that an IME root can span the display while its window occupies only the bottom keyboard area; system/IME windows therefore retain their own reported bounds rather than application-root expansion.

## Final artifact checks

| Check | Result |
|---|---|
| Core tests | **132 passed**, zero failures/errors/skips |
| API 35 device suite | **18 passed**, 135.35 seconds |
| API 30 compatibility suite | **15 passed / 2 skipped**, 166.449 seconds; 17 runner cases |
| Debug, unsigned release, instrumentation and both fixture builds | Pass through checked-in wrapper |
| Lint | **0 errors / 12 warnings** |
| Wrapper checksum, release component/permission audit, English/Greek resource parity | Pass |
| Update signer | Same certificate as earlier APKs; install-over upgrade verified on test emulators |

The four new API 35 device cases verify:

1. Read an unfocused app behind a separate synthetic app window, reject premature text entry, tap/bring forward, verify a counter and enter text.
2. Exclude foreign labels, inspect black masking of foreign pixels in the actual captured image, reject a covered coordinate, and retain an explicit foreign-app deny.
3. Move an overlay, reject old snapshot references and use a newly observed target. This reproduces the additional API 30 geometry problem above.
4. Create two real split-screen panes, focus the other pane, capture only the requested pane, physically tap to transfer focus, verify the counter, and reject coordinates in the other pane.

The remaining API 35 cases include real ChatGPT Chrome navigation through the Accessibility popup, a live fixture tap/type task, fresh screenshots, progress/Stop controls, dictation and screen-read/password/keyboard regressions. API 30 runs the three overlapping-window cases, popup coverage and document/keyboard capture checks. Its WMShell split-screen setup case is skipped because that harness requires API 31+, and its live-account case is skipped because inference was not opted in on that emulator. These are explicit skips, not passing tests.

All model-equivalent operations use Gateway. Two synthetic fixture apps provide controlled content; the test APKs are not public release assets. Dedicated-emulator tests restore saved app settings/history and split-screen state. The temporary API 30 emulator is stopped after collection; the signed-in API 35 emulator is restored to idle.

0.2.9 was not installed or retested on the user's physical phone during this work. Its general window behavior is verified on emulators; the earlier 0.2.8 physical Chrome result is separate evidence. Hosted CI status is separate from these completed dedicated-host checks. The existing GitHub token lacks workflow-edit scope, so the optional Actions workflow update was not published. The window suite explicitly skips when the separate windowfixture APK is not installed; the reported dedicated-emulator runs installed it and executed the cases listed above. The final test harness was checked separately on API 35: all four cases execute when the helper is installed, and all four explicitly skip when it is absent. This does not claim support for every OEM window or for hidden/protected content.

## Reproduction

```sh
./gradlew :core:test :app:lint :app:assembleDebug :app:assembleRelease \
  :app:assembleDebugAndroidTest :fixture:assembleDebug :windowfixture:assembleDebug
python3 tools/release-check.py
# Install the app, test APK and both fixture APKs on a dedicated emulator.
# Enable Accessibility on that emulator; real-phone privilege setup remains manual.
adb shell am instrument -w -r -e class dev.magicphone.app.WindowInteractionTest \
  dev.magicphone.app.test/androidx.test.runner.AndroidJUnitRunner
```

Chrome/live checks additionally require the explicit `chromeNavigation` and `liveChatGpt` opt-ins and account-owner consent. The implementation uses Android's documented [window bounds, layers and input-focus metadata](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo); the API 30 stale-geometry workaround is based on the reproduced device evidence above.

## Artifacts

| Artifact | SHA-256 |
|---|---|
| magicphone-0.2.9-debug.apk | `585dceb8bd003d8de274c4d61d5c3b3bcd88d9ca55ff3f6606b013aea94f7f00` |
| magicphone-0.2.9-release-unsigned.apk | `4528ff01db0d1a488c5f1e24d43949b82f86304a9da55c9089128c81d4e64d3a` |

Certificate SHA-256: `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`.

The public download is the tested early-access development APK signed with the established debug certificate. The unsigned release stays local. Public assets also include checksums, signer information and license notices. Local evidence under ignored `artifacts/release-0.2.9/` includes initial failures, the API 30 diagnosis and final passing reports; no personal screenshots, account data or raw provider logs are published.
