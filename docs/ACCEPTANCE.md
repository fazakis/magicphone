# Acceptance and external verification

Use a dedicated Android 11+ test phone/emulator. Never test with real messages, orders, money, credentials or personal accounts. The attached Huawei VOG-L29 reports API 29 (Android 10), so it cannot install this minSdk 30 app. Do not lower minSdk or patch the phone to mask that incompatibility.

## Deterministic suite

```sh
./gradlew :core:test :app:lint :app:assembleDebug :app:assembleRelease :fixture:assembleDebug
# Set ANDROID_SERIAL to a dedicated emulator; do not target an everyday phone.
./gradlew :fixture:installDebug :app:connectedDebugAndroidTest
python3 tools/release-check.py
```

The instrumentation harness enables Accessibility using test-only UiAutomation shell setup. This privilege is confined to the separately installed test APK and is absent from the production APK. It operates only the practice app and MagicPhone. CI refuses device tests on a non-emulator. Users enable accessibility manually during normal use.

## Manual device acceptance

- [ ] Fresh install: read onboarding in English and Greek, use large font and dark theme. All Stop/approval controls remain reachable.
- [ ] Enable Accessibility manually; disconnect/reconnect it and verify the recovery message.
- [ ] Practice mode: approve opening the fixture and tapping its bilingual button; observe Counter: 1. Rejecting either action prevents it.
- [ ] Allow Read but deny Act; observe succeeds and mutations fail. Block overrides both.
- [ ] With app Act access disabled, ask for coordinate taps, batches and scripts. Each remains blocked. MCP side effects still require their separate consent. No plan-only setting remains.
- [ ] Grant a five-minute semantic tap/scroll/open scope; verify expiry/revocation. Text/coordinates still request approval.
- [ ] Rotate or change fixture text while approval waits; approve and verify the stale action is rejected.
- [ ] Pause during inference and approval. No later action dispatches while paused. Resume revalidates. Stop aborts the run.
- [ ] Switch the screen off/lock it. Unlocking does not automatically resume. Verify OEM background restrictions separately.
- [ ] Show the fixture's password field without FLAG_SECURE. Observation must fail; no password field is transmitted.
- [ ] Show keyboard, split-screen or third-party overlay. Unsafe capture must fail. With no approval overlay and a safe single-app screen, capture succeeds.
- [ ] Send a correction while the model plans or an action waits for approval; the old proposal is discarded and the next model round receives the correction.
- [ ] Share text/image and selected text into MagicPhone; verify only a draft appears. Test tile and assist where supported.
- [ ] Force-stop after dispatch then reopen. History says interrupted and does not replay the command. Explicitly resume with inspection.
- [ ] Search, branch, delete, shorten retention and reopen. Deleted conversations are absent.
- [ ] Export encrypted backup, try a wrong passphrase/tampering, import with preview and verify scripts/notes remain inert. Credentials and permissions must be absent.
- [ ] Configure a local test MCP server. Catalog inspection requires local setup; every side-effect call requires consent. Change the tool schema and verify rejection.

## Live ChatGPT acceptance (requires account owner)

1. On a supported device choose Continue with ChatGPT. Observe the system browser's `auth.openai.com` page, the MagicPhone name and requested consent. Never paste third-party client IDs or extract another app's tokens.
2. For first registration confirm the redirect reaches `http://127.0.0.1:<port>/auth/callback`. Return to MagicPhone; confirm the account's email/registration label. Account tokens must not appear in diagnostics, logcat emitted by MagicPhone, exported backups or plaintext app files.
3. Load models. Confirm account-visible display names/slugs, then choose one. Ask for a harmless plan first; verify streaming completes. Inspect server usage settings for actual limits.
4. With the fixture alone permitted, ask the model to inspect it and propose one counter tap. Approve and verify a new observation confirms the changed counter. Attach a harmless synthetic image and verify supported image input.
5. Cancel consent, use an ineligible account/workspace if available, deny plan scope, or revoke access in ChatGPT Settings. Verify clear consent/session/usage errors and no device dispatch after failed inference. Do not claim unlimited usage.
6. Keep the app alive near expiry and confirm a serialized refresh replaces the token set. Sign in again to the same account and confirm its issued client ID is reused, not `dynamic_agent_client`. Add/switch a second account and reload its model catalog.
7. Rotate/background during browser sign-in. Activity recreation may continue; killing the process must abandon the attempt and require a fresh sign-in. A repeated callback must fail.
8. Logout online: verify local tokens disappear and remote renewal is revoked. Logout offline: local tokens disappear and the app reports that remote revocation was unconfirmed, with directions to disconnect in ChatGPT Settings.

Protocol-unit tests cover state, nonce, signature/claims, refresh request/rotation and revocation request construction. They are not a successful live sign-in. No eligible account consent or live plan inference has been performed in this implementation session. OpenRouter/self-hosted live endpoints and production MCP servers likewise require user configuration and have not been claimed as tested integrations.
