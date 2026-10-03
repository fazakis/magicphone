# MagicPhone 0.2.8: physical Xiaomi system-caption fix

Version code **15**. Validated on 2026-10-03 as a local phone hotfix; never published separately. Superseded by the general window handling in 0.2.9.

## Confirmed physical-device cause

The connected Xiaomi MIX Fold 2 (`22061218C`, Android 15/API 35, 1914×2160 at 440 dpi) ran 0.2.7 with Accessibility connected, automatic app access on and sensitive-content checks off. Its failed task ended with `verification_required`. Recent action statuses showed app launches and waiting/asking, while a settled Chrome inspection failed the mixed-window check; toggling sensitive checks could not resolve this window-classification problem.

A read-only probe with Chrome settled in the foreground reported `root=true`, `mixedApplications=true`, no active-system blocker and no foreign Accessibility-overlay blocker. Chrome itself was focused/active. A separate application window above it had bounds `(872, 0)–(1043, 117)`, was inactive/unfocused and belonged to system UID 1000. This is Xiaomi's visible three-dot task-caption control. The old rule treated this small system decoration as another application's content and rejected the entire screen.

After installing the corrected APK over the existing app, the same phone reported `mixedApplications=false`, `systemDecorations=1`, `screenReadable=true`, **36 nodes**, and capture readiness. The existing account, conversations, audit history and settings were retained at this check. Lock-screen observation remains blocked, also confirmed on this phone after its display locked.

## Implementation

- Only a system-UID window that is inactive, unfocused, fully on screen, positive-sized and within strict width/height/area bounds can be treated as a passive decoration.
- Its pixels are masked, intersecting nodes are omitted and its rectangle is protected from model taps/swipes. Its root contents are not traversed.
- Other apps, active/focused windows, large or unknown windows, and unknown Accessibility overlays retain their previous handling. App permission and lock checks remain authoritative.
- The existing debug Activity dump gains explicit read-only inspection diagnostics containing counts, geometry and boolean ownership flags, never screen text, raw tool arguments, credentials or images. No production action bypass, new permission or exported component is introduced.

## Verification completed

- **129 core tests passed**, no failures/errors/skips. New cases cover the Xiaomi geometry, readable/tappable app areas outside the decoration, rejection of a tap inside it, and rejecting other-app/active/focused/large/offscreen/empty windows as decoration candidates.
- **14 API 35 tests passed in 121.4 seconds**, including actual Chrome navigation with live ChatGPT, popup tap/type, progress controls, Stop/cancellation, speech input, sensitive-check behavior, keyboard masking and normal/credential document reads.
- **12 API 30 tests passed in 175.797 seconds**; one opt-in live-account case was skipped (13 runner cases). Popup, voice-input, Stop and document/keyboard compatibility checks passed. The temporary API 30 emulator was stopped after evidence collection.
- Debug, unsigned release, test and fixture builds pass via the checked-in wrapper.
- Lint: **0 errors / 12 warnings**. Wrapper, release-manifest and English/Greek audits pass.
- The APK installed on the Xiaomi matches the final build hash below.

## Physical navigation completed

The owner submitted the bounded Chrome task from the Accessibility popup after Xiaomi blocked USB input injection and declined the separate test-runner APK. The actual ChatGPT task reached **COMPLETED**, with no error, after seven model calls. Sanitized action records show text entry, taps, fresh observations and captures. A direct screen check confirmed the `mobile.de` homepage and the completion bubble. The owner also confirmed that it worked.

No phone security setting or Accessibility enablement was changed. This verifies the exact previously failing Chrome workflow on the physical, unfolded MIX Fold 2. It does not claim every OEM layout or fold transition was retested. The general 0.2.9 implementation replaces the local system-caption heuristic with per-window occlusion geometry; its separate test record identifies emulator checks explicitly.

## Artifact

`magicphone-0.2.8-debug.apk` SHA-256:
`c6f6e52ab1ad3479b706d06c7b49650446acf27b5d9b146fd2af2b9f94c9e224`

Same early-access debug signing identity as prior releases. Local evidence is ignored under `artifacts/release-0.2.8/`. No user account data, diagnostic screenshots or raw logs are release assets.
