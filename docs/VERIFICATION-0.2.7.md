# MagicPhone 0.2.7: preserve action context with large screenshots

Version code **14**, verified on 2026-10-03.

## Reproduced failure

The 0.2.6 Chrome diagnostic could read the screen and capture images, but the next model turn lost the `observed` tool result. A deterministic core test with a 250,000-character encoded image failed on the same missing current observation. This isolates an agent-context bug, not a missing Accessibility connection.

The old 160,000-character history check counted the entire encoded screenshot as text. A large image could trigger compaction every round, dropping all tool calls/results while retaining the image and older initial user context. The model could then lack fresh snapshot/node references and action verification, leading to stale requests or unnecessary questions. More frequent screenshots in 0.2.6 made the existing compaction defect easier to encounter. Larger screen images can expose it; this is not inherently a fold-state failure.

## Change

- Encoded `input_image` data is excluded from the text-history budget. Existing image size limits still apply.
- Text-history compaction retains the entire latest completed round: model calls, matching outputs, current observations and the latest screenshot. Replacing the previous automatic screenshot adjusts the retained-round boundary, including multiple tool calls in one response.
- Older history can still be summarized, and uncertain actions are never blindly repeated. App access, screenshot filtering, sensitive checks, Stop and stale-target checks are unchanged.

## Final APK verification

| Check | Result |
|---|---|
| Core suite | **127 passed**, no failures/errors/skips |
| API 35, original 1080×2340 layout | **11 passed**, 117.291 s; Chrome (2) and popup (9) suites |
| API 35, 1768×2208 / 320 dpi tablet layout | **2 passed**, 102.022 s; controlled and live Chrome navigation |
| API 30 compatibility | **12 passed / 1 live-account case skipped**, 177.977 s; runner reports 13 cases |
| Debug, unsigned release, test and fixture builds | Pass through checked-in wrapper |
| Lint | **0 errors / 12 warnings** |
| Wrapper, release-manifest and localization audit | Pass |
| Upgrade/signing | Same certificate, install-over upgrade succeeds |

The controlled Chrome case reads/captures the public MagicPhone page, opens its address bar, enters a URL, verifies the entered text in the next observation and uses Back. The opt-in live ChatGPT case submits an actual Accessibility-popup prompt to navigate from the public MagicPhone page to `https://example.org`; it taps, types, selects the destination and verifies the loaded page. Both pass in the phone and tablet layouts. All actions go through Gateway. No account operation, message, purchase or consent-setting change is requested. Chrome tests require the explicit `chromeNavigation` flag; inference also requires `liveChatGpt` and account-owner consent.

Existing popup cases cover fresh capture, latest-image retention, Stop/cancellation, covered controls, Android dictation, completion/question/error bubbles, the sensitive-content switch, and a live fixture tap/type task. The default-on sensitive checks remain enabled during the live Chrome navigation tests. API 30 also covers document/keyboard reads and screenshot stability.

The live tests use the existing authorized ChatGPT connection. Test setup restores MagicPhone's saved settings/history afterward. Display overrides are restored in a `finally` block. The physical Xiaomi and its exact `mobile.de` workflow were not tested; the wide-layout check is a tablet viewport test, not a physical hinge/fold test. No new success claim is made for all third-party apps. Hosted CI is separate from these dedicated-host results.

## Reproduction and assets

Local evidence is ignored under `artifacts/release-0.2.7/`. Baseline logs record the failed large-image unit test and missing Chrome observation before the fix. The public release includes the development APK, checksums, signing identity and license notices. The unsigned release build stays local.

```sh
./gradlew :core:test :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :fixture:assembleDebug :app:lint
python3 tools/release-check.py
# Dedicated emulator; public Chrome navigation and live-account tests are opt-in.
adb -s emulator-5582 shell am instrument -w -r -e chromeNavigation true -e liveChatGpt true \
  -e class dev.magicphone.app.ChromeNavigationTest,dev.magicphone.app.PopupWorkflowTest \
  dev.magicphone.app.test/androidx.test.runner.AndroidJUnitRunner
```

| Artifact | SHA-256 |
|---|---|
| magicphone-0.2.7-debug.apk | `052441cb78e1d23e3696b3d6a3d5125bb8555b63496064b74deac5fd45395b3b` |
| magicphone-0.2.7-release-unsigned.apk | `dc9477e5b962dfa4c7d8c7aeff850c93f81f97ca39982b82eb34d2d09e9e4984` |

Certificate SHA-256: `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`. This remains an early-access development APK signed with the existing debug certificate. Install over a matching-signer version to preserve data.
