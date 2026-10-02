# MagicPhone 0.2.1: fetched and manual model options

Version code 8. Tested on the dedicated MagicPhone_QA35 Android 15/API 35 emulator on 2026-10-02. The existing ChatGPT sign-in is retained.

## Behavior

Settings → Models continues to fetch models, thinking levels and speed capabilities. **Manual model options** adds clearly labeled model presets, including GPT-6.1 Sol, plus custom model IDs for both ChatGPT and OpenAI profiles. It exposes all bounded thinking choices (default, none, minimal, low, medium, high, xhigh, max, ultra) and speed choices (account default, Standard, Fast, Ultrafast). Account default omits the parameter; Standard explicitly requests `default`.

Manual preferences are encrypted per profile, survive activity recreation and catalog refreshes, and are sent through the existing provider. Choosing a manual preset does not assert account availability. Disabling manual mode normalizes unsupported options against the fetched/documented capabilities. Fast and priority normalize as equivalent aliases. Unknown effort/tier values remain invalid, and no paid tier is selected automatically.

The chat's **Last model response** card separates requested model/thinking/speed from final server-reported metadata. Unreported fields say so. A mismatch or absent tier does not confirm the requested speed. Model metadata is allowlisted and contains no prompt or reply text; it is cleared on a new run or conversation switch. Actual phone actions still pass through the unchanged Gateway.

## Verification

- **96 core tests pass**, covering manual selection persistence through capability refresh/model switching, dynamic-mode restoration, bounded enum validation, fast/priority equivalence, emitted request parameters, server fallback/missing metadata and clearing the last-response display.
- Debug, instrumentation and unsigned release builds pass through the repository wrapper. Lint: **0 errors / 11 warnings**. Wrapper, exported components, permissions, release manifest and English/Greek parity checks pass.
- The integrated 16-case emulator run passed **15 cases**: the live Sol workflow, three previous model/migration/catalog checks, four question-bubble checks, five voice/notification checks and two UI/shortcut checks. The remaining custom-model editing test exposed keyboard/navigation timing and an unreliable reverse-scroll lookup within a large settings card. Saving a custom model now explicitly clears focus and hides the keyboard; the harness focuses the field before typing and reopens Settings before checking the saved mode.
- On the final APK, the targeted emulator run passed **all 4 tests in 36.948 seconds**: manual model/thinking/speed persistence, custom-ID editing and dynamic-mode restoration, legacy migration, existing selector/theme/keyboard behavior, and the authorized live account catalog fetch. The live action and unrelated voice/bubble regressions above passed before the final keyboard-dismiss adjustment; the complete 16-case suite was not repeated afterward. Evidence: `artifacts/manual-options-0.2.1/final-model-tests.log` and `final-device-tests.log`.
- The actual manual settings and server-result screens were captured and visually inspected. The user subsequently authorized publishing this exact APK as GitHub release 0.2.1; release preparation only updates publication documentation and website links.

## Live Sol task

With explicit account-owner authorization, the app used **GPT-6.1 Sol, Low thinking, requested Ultrafast** through the existing ChatGPT connection. The actual task screen submitted a request to open the isolated practice app, tap Add one exactly once, enter “MagicPhone ready” and verify the results. It completed with zero approvals. The fixture visibly showed Counter: 1 and the requested text. Unknown-overlay rejection and disabling automatic access from the chat chip also passed.

Task time: **26.497 seconds**, with four model calls totaling **25.604 seconds**, and **0.835 seconds** of tool work. This is one functional regression, not a speed comparison. The final response reported `gpt-6.1-sol`, `low`, and **`default`** processing, so the UI correctly displayed Standard and that requested Ultrafast was not confirmed. The test restored the prior settings and chat archive afterward.

## Artifacts

Local output: ignored `artifacts/manual-options-0.2.1/`.

The final debug APK is installed on the dedicated emulator and served by the development LAN download server as `magicphone-0.2.1-debug.apk` (also `magicphone-debug.apk`). A fresh HTTP download and the installed `base.apk` both match the debug checksum below. The post-test status reports version 0.2.1, Accessibility connected, ChatGPT account selected and no current error; the pre-test preferences were restored.

| Artifact | SHA-256 |
|---|---|
| magicphone-0.2.1-debug.apk | 0afc15f3a9067cb1d04d22812995d5123b2e22e5c35a61be6111f3ca81accf5d |
| magicphone-0.2.1-release-unsigned.apk | 4b5a74edcb33b9826f62d708a12478b91e02d280393beb6586a6679ffe3e2bf9 |

The installable debug APK uses the existing development signing identity, certificate SHA-256 `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`. The unsigned release is build/audit evidence only. Earlier hosted API 30/35 failures remain separate; this verification does not claim that hosted matrix or physical-phone/OEM testing was rerun.
