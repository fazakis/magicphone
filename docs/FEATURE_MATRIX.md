# Requirements checklist and verification matrix

Implementation and verification are separate. **Implemented** identifies actual reachable code; **unit** means deterministic host tests, **device** means instrumentation on an isolated Android emulator, and **external** means the named account/device/server is still required. See PROGRESS, [Android 15 remote verification](VERIFICATION-API35.md), and [the later Xiaomi investigation](VERIFICATION-XIAOMI.md) for exact results; this table never treats a mock provider as live integration.

Latest APK: **0.1.5**, **89 core tests passed**, lint **0 errors / 15 warnings**, and **10 targeted Android 15 emulator tests passed** for this exact artifact. See [the bubble/voice/shortcut verification and APK identity](VERIFICATION-BUBBLE.md). The newer hosted API 30/35 matrix still has failures, detailed below; the local result is not a claim that all hosted tests pass.

| Requirement | Implementation | Verification / remaining check |
|---|---|---|
| Original MIT OR Apache-2.0 source, attribution, icon, independence | LICENSE, NOTICE, vector icon, README | Source review; reference capabilities only, no implementation copied |
| Kotlin, Compose, Material 3, coroutines; API 30+ | app/core/fixture Gradle modules | Debug/release compilation; min/target/compile SDK in manifest |
| English/Greek, dark theme, scalable text | values + values-el, MagicTheme, scrolling panels | Resource parity; device Greek share/resources and dark UI at 1.4× text; complete Greek/OEM layout acceptance remains |
| Manual onboarding/permissions and data disclosure | MainActivity settings/onboarding | Device launch; real-device restricted-settings workflow external |
| Chat, attachments, streaming, questions/corrections | MainActivity, Agent, provider adapters | Agent/empty-reply regressions; completed-message/refusal fallback; share fixture test; live provider images external |
| Waiting-for-input message bubble and chat-specific reply | Runtime InputRequest, PhoneService overlay, chat-bound notification/intent, persisted ASK and text questions | Four device cases pass for question/answer, dismissal, lock and exact-chat routing; included in the 10-test final suite. [Evidence](VERIFICATION-BUBBLE.md) |
| User screenshots of MagicPhone | MainActivity without FLAG_SECURE; masked secret fields retained | Screenshot-enabled activity assertions and synthetic UI captures; model protections are separate |
| Checklist and distinct task states | Agent StateFlow + TaskPage | Unit task transitions, fixture workflow |
| Pause/resume/Stop in an ongoing notification | Agent, Gateway, TaskNotifications, private ControlReceiver; old top bar removed even for existing settings | Device notification-shade Pause/Resume/Stop and no-overlay assertions; core repeated-Pause/reply-during-Pause regressions. Full 0.1.4 verification recorded in VERIFICATION-VOICE.md |
| Share/selection/tile/assist | Manifest and entry handlers | Share instrumentation; tile/assist OEM behavior external |
| Accessibility shortcut to current chat and keyboard | Main-thread service callback, singleTop chat intent, pause before entry, RESUMED/window-aware focus with IME retries, saved draft | Device coverage for repeated background entry during active work, same draft/current chat, keyboard and notification entry; existing Settings/fixture/recreation regression retained |
| Android voice input | VoiceInput Activity Result contract and microphone in composer; no direct audio capture or OpenAI key | Controlled Greek transcript appends without sending; canceled result and activity recreation preserve draft; free-form intent shares no task context and bounds result size. Live acoustic accuracy depends on the installed speech provider |
| Responsive local chat/history/settings UI | Lazy lists, fixed composer, isolated input composition, lifecycle-aware collectors, IO package discovery, ordered background history persistence | Large synthetic-history device benchmark; encrypted flush verifies all updates; audit durability/Stop barrier unit coverage. [UI evidence](VERIFICATION-UI.md) |
| Tree, screenshots, app list/open, semantic/coordinate actions | PhoneService, Action schema | Fixture observation/tap/password/stale, actual screenshot and Unicode text script tests; rotation/every gesture and other OEMs manual |
| Stale references, window/focus/rotation, mixed windows | Screen binding + fresh traversal + dispatch recheck; exact ownership of attached control windows; bounded re-observation/replanning for pre-execution rejection | Policy stale/mixed/overlay tests; live ChatGPT recovers from an injected screen update without executing the stale action; untracked service overlay remains blocked |
| Screen off/lock and disconnection | Service events/receiver, lifecycle cleanup | Policy unit tests; physical lock/OEM background behavior external |
| Continue with ChatGPT using own identity | OAuth PendingAuth/LoginAttempt/IdentityVerifier/ChatGptAuth | PKCE/state/nonce/signature/claims/refresh/logout unit tests; **live consent/renewal/revocation external** |
| Account models + Responses namespace tools/images/context | ResponsesProvider, ToolSchema | **Live ChatGPT task passed:** approved app launch, observation and completion; empty terminal output regression fixed; [live evidence](VERIFICATION-LIVE-CHATGPT.md). Live images and other app workflows remain unverified |
| Reduced action latency and visible timing | Agent upfront permitted context + automatic gateway OBSERVE; supported-model low reasoning setting; bounded screen-readiness polling; payload-free RunMetrics | Three live tasks per build with the same GPT-6 Astra model: median **49.191 → 21.931 s**, model calls **9 → 4**, phone/tools **1.642 → 0.828 s**. Actual open/tap/type verified; read-revocation, failed-read/no-replay, timing and stability unit coverage. [Performance evidence](VERIFICATION-PERFORMANCE.md) |
| Additional isolated profiles, secondary model | Settings/provider adapters, BoundSecret | Origin/redirect/cleartext tests; **live OpenRouter/local inference external** |
| Central boundary, app allow/deny, read vs act | Gateway + Policy | Policy regression suite, deny-before-inspection tests |
| Strict plan mode, batches and script substeps | Gateway preflight and per-substep run | Unadvertised/empty-plan/batch/script unit tests |
| Exact approvals and narrow expiring grants | Gateway + local UI/overlay | Replay/expiry/policy-change/stale/Stop tests |
| Optional all-app execution without approval | Settings button, task-screen status/off button, shared effective policy; defaults off | Live ChatGPT UI test enables mode with no individual fixture permission, opens/taps/types/verifies with zero approvals, recovers from a screen update, persists setting and revokes via UI; actual coordinate tap without approval also passes (historical versions included a floating bar). Rechecked with the optimized 0.1.2 action loop |
| Secret/system-control manual handling | Protected packages, password detector, own-overlay guards | Fixture secret screen without FLAG_SECURE; generic custom controls remain inherently uncertain |
| Run/step/time/failure limits, no ambiguous retries | Agent, Gateway, ScriptRunner, HttpTransport | Budget, cancellation, provider failure and no-retry tests |
| No telemetry, destination inventory, TLS/isolation | Network module, manifest/settings/privacy docs | URL/DNS/redirect regression tests and release audit |
| Keystore encryption and sanitized persistence | Vault, Archive/Sanitizer | Device encryption/tamper/interrupted AtomicFile recovery; raw-argument/echo tests |
| History/search/branch/delete/retention/recovery | Runtime, HistoryPage, schema migration | Schema/interruption unit tests; complete manual UI recovery acceptance remains |
| Passphrase backup and import preview | BackupCrypto, Archives, DataPage, SAF | Roundtrip/tamper/password/path/size/escalation tests; manual SAF provider acceptance |
| Scripts with typed params, editor and manual execution | ScriptRunner + LibraryPage | Hard-budget/schema/cancellation/policy tests; script UI manual acceptance |
| Reviewed app-specific memory/playbooks | Knowledge + LibraryPage + relevance loader | Inert-import tests; UI/model durable proposal review |
| Optional MCP Streamable HTTP + per-tool consent | McpClient + Gateway + Settings | Session/version/changed-schema unit tests; **live external server acceptance external** |
| Sanitized diagnostics preview | DataPage + payload-free Audit schema; debug-only read-only Activity status | Audit no-raw-arguments tests; protocol counters exclude payloads; actual Xiaomi status reads |
| CI compilation/lint/unit/instrumentation | .github/workflows/android.yml | Hosted build/core/lint/release checks pass. Latest hosted device matrix has 2 API 30 shortcut failures and 5 API 35 fixture/keyboard/voice failures; see [run 36932298773](https://github.com/fazakis/magicphone/actions/runs/36932298773). Live-account tests are intentionally skipped in CI |
| Wrapper/dependencies/notices/checksums/signing | Wrapper checksums, verification metadata, release-check, notices | Wrapper official SHA check; debug signature/unsigned release and release audit |
| Threat model, architecture, installation, contribution docs | docs/, README, CONTRIBUTING, SECURITY | Documentation source/code review |

## Deliberate bounded behavior

- Scripts are a constrained finite JSON language rather than Lua. No unbounded program or shell escapes exist.
- MCP is a bounded Streamable HTTP client subset: no stdio, server-initiated sampling/roots/elicitation or action replay. Complex schema forms fail closed.
- Unsafe mixed-window/overlay/password-screen observations fail instead of attempting uncertain redaction. Approval-control bounds stay protected. The former floating Pause/Stop bar is removed; the ongoing notification keeps controls outside the target app. An open notification shade still suppresses unsafe observation.
- Automatic detection cannot recognize every secret/payment control. Users must keep sensitive apps blocked and authorize those operations manually.
- Context compaction is conservative and lossy; a long task may ask for clarification. No monetary estimate is fabricated.
- Android can kill a bound accessibility process. Recovery is explicit; no foreground-service loophole or background restart is used.
- The default production application ID is a renameable reverse-domain working identity, not an assertion of domain ownership or store registration.

## Website and publication

The responsive landing page lives in `site/`, alongside the Android source. `.github/workflows/pages.yml` publishes only that folder to GitHub Pages on `main`; Android CI skips website-only changes. Original work is available under MIT OR Apache-2.0. See [website setup and verification](WEBSITE.md) for deployment and custom-domain status.
