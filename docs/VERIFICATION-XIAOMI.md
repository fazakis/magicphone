# Physical-phone response diagnosis

**Later finding:** the same failure was reproduced and fixed with the user's real ChatGPT account on the emulator. See [live verification](VERIFICATION-LIVE-CHATGPT.md) for the successful run and current APK hashes. The physical phone was disconnected before that final update. The investigation below records the earlier state.

Date: 2026-10-01. The user connected a Xiaomi MIX Fold 2 (Android 15/API 35) to the local Mac and manually authorized USB debugging. The installed application matched the API 35 host's debug signer; updates were installed in place with that signer, preserving app data. The emulator permission harness was not run on this phone.

## Observed failure

The user reported that “Open Instagram” showed planning and no visible reply. Read-only application diagnostics showed a selected ChatGPT profile, configured model, selected account, plan-only disabled, Instagram Read/Act enabled, `WAITING_USER`, and zero action audit entries. Therefore no tool operation had reached the gateway. This does not establish that a useful live model response or an Instagram launch succeeded.

Accessibility was connected during an earlier diagnostic read, but Android subsequently reported the service disabled. After the latest update, the user must manually enable it and submit a new task. We did not change Accessibility privileges through ADB or approve application actions on the user's behalf.

## Changes

- Completed Responses message text and refusals remain visible even when text-delta events are absent.
- A reply containing neither text nor tool calls fails with a visible English/Greek error instead of silently waiting for an empty question. There is no automatic retry or device dispatch on this path.
- Tool guidance explicitly describes APPS → OPEN → OBSERVE. OPEN needs no pre-existing snapshot; other screen actions still require fresh observation. Parameter descriptions identify package, snapshot and node requirements. Gateway policy and approvals remain authoritative.
- Debug builds expose a read-only `magicphone-status` Activity dump. It contains configuration flags and protocol event/item/character counts, never credentials, message text, raw requests/responses, or raw tool arguments. It provides no command or permission-changing interface. Release builds do not expose these diagnostics.

## Verification

- **67 core tests passed**, zero failures/errors/skips, including empty-reply failure and completed-text/refusal fallback regressions.
- **7 device tests passed** on the isolated API 35 emulator, zero failures/errors/skips. The final diagnostic-reset-only follow-up was rebuilt and reran all 67 core tests.
- Debug and unsigned release build; lint **0 errors / 15 warnings**; release manifest, wrapper checksum and Greek resource parity checks pass.
- Updated debug APK installed successfully on the Xiaomi and the API 35 emulator. The local Python download server serves the exact same APK; downloaded bytes were SHA-256 verified.
- Evidence and current artifacts: ignored `artifacts/xiaomi-diagnostics/`. Earlier API 30/API 35 records describe their earlier artifact versions.

Current download artifact hashes:

| File | SHA-256 |
|---|---|
| magicphone-debug.apk | e33010a1dc2dd1aa6d7d551e893c2e48ae7872e7efdf62152d5626e2a99cda5b |
| magicphone-release-unsigned.apk | b7319a4d63278d0a2b950680e26b7b9a255453d11566a6bd1ae27a8b8a319341 |

## Pending live check

Manually enable MagicPhone Accessibility, return to the app, create a new task “Open Instagram,” and inspect the reply or local approval prompt. Approve the launch locally if desired. A successful end-to-end test must show the actual launch and a subsequent permitted observation, not merely a model claim. If it stops, the debug counters distinguish missing response text, missing function calls and other protocol behavior without exporting private content.

Live OAuth renewal/revocation, image inference and general personal-app workflows remain unverified. Do not infer them from account-selection flags or fixture tests.
