# MagicPhone 0.2.0: interface and model controls

Verified 2026-10-02 on the dedicated MagicPhone_QA35 Android 15/API 35 ARM64 emulator. Version code 7. Installed with `adb install -r` using the existing debug signing key; the signed-in account was preserved.

## Changes

- Removed the Plan only switch and both gateway plan-only checks. Actions always remain available subject to app access and the existing central Gateway. Known old `planOnly` fields are migrated out without resetting accounts, app rules or automatic access. Script/action JSON remains strict.
- Added a forest-green/ivory theme, original navigation icons, rounded chat bubbles and composer, tappable starter prompts, selectable message text, compact action summaries and grouped Models/Access/Apps/More settings. Light/dark status-bar contrast is updated with the theme. The fixed composer, voice entry, keyboard focus, background shortcut and notification controls are preserved.
- Replaced the binary faster-decisions toggle with per-profile thinking level and processing speed. Catalog metadata is retained, missing known OpenAI reasoning capabilities use documented fallbacks, unknown models use their defaults, and explicit unsupported settings fail before a network request. Changing a model drops incompatible settings. Existing faster-decisions preferences migrate to Low/default.
- Catalog refresh preserves server ordering and visibility, has loading/error/search UI, and invalidates stale results when the selected connection/account changes. No synthetic model IDs or upsell speed tiers are inserted. Account changes clear account-bound capabilities and speed selection.
- ChatGPT exposes only advertised speed tiers. API-key profiles support documented Fast/Ultrafast choices; no higher-cost tier is selected automatically.

## Deterministic and device checks

Wrapper command: `:core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lint :fixture:assembleDebug`, followed by `python3 tools/release-check.py`.

- **93 JVM tests pass**, no failures/errors/skips. New tests verify capability precedence, unknown/empty capability metadata, model switching, no automatic paid tier, request encoding, catalog order/visibility and rejection before credentials/network access.
- Debug/instrumentation/unsigned-release/fixture builds pass. **Lint: zero errors, 11 warnings** (SDK/dependency notices, API attribute compatibility and two URI KTX suggestions). Release manifest, permission/exported-component checks, wrapper hash and English/Greek parity pass.
- **14 emulator tests pass in 133.430 seconds**: ModelSettingsTest (3), InputBubbleTest (4), VoiceAndControlsTest (5), UiResponsivenessTest (2). The catalog test is an explicitly enabled read-only live account check; all other tests in this run use local fixtures or controlled recognition results.
- Old plan=true/false and fast=true/false settings migrate while preserving existing fields. New thinking/speed choices persist through encrypted storage and activity recreation. Light/dark screens, the keyboard and 1.3× text were captured and visually inspected.
- Greek voice-result append/cancel/recreation, real installed recognizer open/cancel, notification Pause/Resume/Stop, repeated background shortcut, exact-chat question bubble, answer continuation and lock behavior pass.
- Same synthetic UI workload: 181 conversations, 12 writes; median main-thread write **0.203 ms**, maximum **1.399 ms**, p95 frame duration **33.595 ms** across 235 frames. This is a local debug-build UI measurement, not cloud-response latency or a controlled speedup claim.

The previous hosted API 30/35 failures recorded in FEATURE_MATRIX remain separate. This work does not claim that the entire hosted matrix or physical-phone/OEM behavior was reverified.

## Live task regression

The separately opted-in `LiveChatGptTest#automaticAccessOpensTapsAndTypesWithoutApprovals` passed in **35.768 seconds** including harness setup and checks. It used the existing account's GPT-6 Astra with Low thinking and default processing; no paid speed tier was enabled. The actual task completed in **26.314 seconds**, with four model calls (**25.397 s**) and **0.845 s** of tool work. It launched the isolated practice app, tapped Add one exactly once, entered “MagicPhone ready”, and independently verified Counter: 1 and the text. No approval was requested. Unknown-overlay rejection and turning automatic access off from the new chat chip also passed.

This makes **15 emulator checks total** for this artifact. One live run is a functionality regression, not a speed benchmark or guarantee. The test restored the app policy with automatic access off; the same signed-in model profile remains selected.

## Actual account catalog

The emulator's selected ChatGPT connection returned, in order:

| Model ID | Advertised thinking | Advertised speed |
|---|---|---|
| gpt-6-astra | low, medium, high, xhigh, max, ultra | priority (displayed as Fast) |
| gpt-5.6-sol | low, medium, high, xhigh, max, ultra | priority |
| gpt-5.6-terra | low, medium, high, xhigh, max, ultra | priority |
| gpt-5.6-luna | low, medium, high, xhigh, max | priority |
| gpt-5.5 | low, medium, high, xhigh | priority |

Neither Ultrafast processing nor GPT-6.1 Sol was advertised by that connection. A later owner-authorized [direct request test](VERIFICATION-MODEL-ACCESS.md) proves Sol is usable despite catalog omission; Ultrafast requests complete but report default processing. `ultra` thinking is independent of `ultrafast` processing. This is a dated result for the tested connection, not a claim about every account or future availability. Only public model IDs and allowlisted capability names were recorded; no account details, tokens or raw responses were logged.

Official documentation checked on the same date: [account model discovery](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference), [Astra reasoning](https://developers.openai.com/api/docs/models/gpt-6-astra), [GPT-6 guidance](https://developers.openai.com/api/docs/guides/latest-model), [Fast processing](https://developers.openai.com/api/docs/guides/fast-mode), [Ultrafast processing](https://developers.openai.com/api/docs/guides/ultrafast-mode), and [ChatGPT/Codex speed availability](https://learn.chatgpt.com/docs/agent-configuration/speed). Astra Ultrafast uses model `gpt-6-astra` with `service_tier: "ultrafast"`; it is not a separate model. API speed-tier encoding is covered by mock HTTP tests. No paid API-key or live Ultrafast request was made during this original verification; the subsequent direct ChatGPT probes are recorded separately.

## Artifact identity

Local evidence and screenshots: ignored `artifacts/refresh-0.2.0/`.

| Artifact | SHA-256 |
|---|---|
| magicphone-0.2.0-debug.apk | f330d0ac7188ee59604ef9df8732005519a841b53980cadf1260d33a661f84a7 |
| magicphone-0.2.0-release-unsigned.apk | 64e596f827fc72e878e0de39a2bbd2d397a781f0c0cf0957442faa651cf2d59a |

Debug certificate SHA-256: `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`, matching the preceding builds. The debug APK is the installable update; the unsigned release is build/audit evidence only. The public GitHub release is unchanged by this work.

The local installable APK, installed emulator base.apk and versioned LAN HTTP download have the same SHA-256 above. After instrumentation, the emulator’s already-enabled Accessibility service was rebound; final status confirms Accessibility connected, ChatGPT account selected, Low thinking and default processing. The download server is the existing user-requested server; no new public GitHub release was created.
