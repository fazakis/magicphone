# MagicPhone 0.2.12 verification

Verified on 2026-10-10. Version code 19. This release adds locally enabled spoken screen explanations with synchronized visual emphasis.

## Build and signing

The checked-in Gradle wrapper, JDK 21, Android SDK platform 37 and build-tools 36.0.0 build the app and test harness. `:core:test`, `:app:assembleRelease`, `:app:assembleDebugAndroidTest` and `:app:lintRelease` succeed. Core: **149 tests**, zero failures/errors/skips. Release lint: **zero errors / 16 warnings** (dependency/API/style recommendations, two drawing-allocation warnings and the draggable heading's accessibility-click recommendation). No lint baseline suppresses them.

`tools/release-check.py` passes wrapper SHA-256, exported component, permission, debuggability, backup/cleartext and English/Greek resource-parity checks. `tools/sign-release.sh` validates alignment and the Android v3 signature using the existing production identity and signing lineage.

- APK: `magicphone-0.2.12-release.apk`
- SHA-256: `0ebb5e364436546145ceca285abcd443a3f676954103205c1cd95485e4d031ce`
- Release certificate SHA-256: `e15cef3dd788893f862ff20f7a19f08de96c3450bb2acf9eb1331a40a3b979b2`
- Package: `dev.magicphone.app`, non-debuggable, API 30+.

The dedicated Android 15 emulator accepted an in-place update from 0.2.11; the existing ChatGPT credentials remained usable for the live check. No uninstall was used for the production package. A separate `dev.magicphone.explainqa` debug package was used for earlier development checks. Private signing material is outside the source and release assets.

## Device and live model coverage

Tests use the dedicated `MagicPhone_QA35` Android 15/API 35 ARM64 emulator, a separately installed synthetic fixture, Android's real Accessibility service, PDF renderer, screenshot API and text-to-speech engine. The instrumentation APK is signed with the release identity to test the actual non-debuggable app.

**149 core tests pass. All 11 guided-explanation device tests pass**, including the opt-in live ChatGPT case. The final run with the corrected PDF renderer and glyph-overlap assertions completes in 82.966 seconds on the shipping APK. Separately, the release regression runs pass **7 basic device-acceptance tests, 8 popup workflow tests and 5 voice/notification tests** (20 existing cases). Those checks were executed across separate runs, not one all-green 31-case invocation; the earlier combined run and its corrected harness failures are described below. The extra existing opt-in live action test was skipped in the popup regression; live explanation inference was explicitly enabled and exercised.

Feature coverage includes:

- Accessibility popup switch, fresh capture after Send, saved transcript and Android TTS start.
- Visible, correctly mapped table outlines; translucent equation highlighting and underlining without erasing the glyphs.
- Ordinary native app controls as well as a pixel-only PDF table.
- Touch-through drawing windows; clean Gateway observation/screenshots while playback overlays are temporarily removed.
- Previous, Pause/Resume, Next, Stop, exact-chat entry, Greek automatic section advancement and replay.
- Automatic spoken-cue progression from one numeric cell to another, then the E and c-squared terms in a separate equation below the table, with a tight highlight at each step.
- Scroll and display-size changes clear/re-anchor highlights without duplicating the submitted user message.
- Stop during refresh, screen-off cancellation, blocked apps and missing local opt-in.
- Clarification replaces the player with the ordinary reply bubble, retains local opt-in, and allows a fresh explanation.

The account-owner-authorized live ChatGPT test reads a PDF table whose cell text is only rendered pixels. It returns a real structured EXPLAIN call, spoken sections and model-selected highlight regions. The answer correctly compares 84.2% vs 85.5% (1.3 percentage points), 12 vs 8 seconds (4 seconds / one-third less), and 72 samples for each method. The transcript explicitly identifies the values as synthetic. Screenshots confirm the model's emphasis aligns to the table. This is live inference through the app's configured account; the separate practice-provider checks are deterministic and are not represented as live inference.

The final phrase-level live response separates the two accuracy values, their comparison, the equation, E, m and c squared into focused spoken cues. A deterministic real-TTS sequence independently checks that the visible mark advances between the two numeric cells and the equation terms without pressing Next.

The fixture/API tests also cover ordinary taps/text entry, stale-target rejection, screenshot and credential-screen policy, encrypted storage, Unicode, popup voice input and notification controls. No personal third-party app was operated by these tests.

## Issues found during development

A comparison of Android window layer numbers originally treated our own presentation insertion/removal as changed document content. Stable target/window metadata excluding owned presentation windows fixes the refresh loop. Drawing now honors each shape independently; marker fill remains translucent. Capture metadata includes the actual screenshot snapshot and bounds, preventing geometry from being paired with an earlier tree. The controls clamp their restored position after a size change.

The harness was corrected to retain Accessibility during screenshot capture, wait for the painted frame, drain delayed fixture launch events for direct-Gateway cases, and re-observe known stale proposals. Visual assertions accommodate the fixture's gray text. Existing historical TAP audits are excluded when checking that a new explanation performed no mutations. A disabled QA copy left a second ongoing notification with the same app/action names, making notification selectors ambiguous. The temporary QA copy was removed before the final notification run. The basic acceptance harness now restores settings, history and chat selection after each test. The expanded pixel-only PDF fixture exposes the table and equation as separate image regions while retaining a single rendered page; the tree-only practice provider re-observes a missing table anchor after keyboard transitions. Live image-capable inference does not depend on those labels. Final screenshot review also caught a fixture rendering bug: a height parameter shadowed the measured View height, compressing PDF pixels within the image region. Renaming that parameter restores the true region mapping; the spoken-cue test now asserts both colored emphasis and visible glyphs inside each numeric/symbol region, rather than accepting relative coordinates alone. Initial harness failures are retained in local logs; passing results refer to corrected final runs.

## Limits

Highlights advance per short spoken phrase. The model is asked to separate each change of focus into its own cue: one value, cell, label or equation term at a time, with simultaneous regions only for a comparison or a multi-line passage. This is phrase-level synchronization, not individual-word tracking or freehand polygon drawing. A complete validated model response is required before speech starts. Supported shapes are translucent highlight, underline, rectangle, ellipse and pointer, with up to eight regions per section. Their accuracy depends on visible evidence and the selected model. Text-only models can supply text/tree explanations but do not gain image-reading capability.

Scroll/resize refreshes make another request to the same selected model after a 500 ms settling debounce. Screen observations use the existing Gateway and app/lock/sensitive/secure-window policy. No new backend or separate speech API is added. TTS language/voice availability and online processing depend on the installed Android engine. Explain mode does not navigate or mutate the underlying app; ordinary automation is available with the switch off.

The new feature is verified on API 35, not on a physical foldable, Android 11, secondary displays or every third-party reader. Resize simulation is not a physical hinge test. Android 11 signing migration was verified for the same signing lineage in 0.2.11; this release does not claim a new API 30 device run. Hosted CI and store acceptance are separate from these dedicated-host results.
