# Architecture and engineering decisions

## Modules and lifecycle

`app` contains Compose UI, manual onboarding/settings, an application-scoped structured coroutine runtime, Android Keystore storage, notification/tile/share/assist entry points, and the Accessibility service. `core` is an Android-independent Kotlin library for typed actions, the policy gateway, scripts, providers/OAuth/MCP, the task state machine and backup validation. `fixture` is an independent Android counter/text application used only for deterministic tests. There is no runtime shell, root, ADB, developer backend or remote-command receiver.

The user starts a bounded run. The bound Accessibility service keeps normal accessibility work available while other applications are in front. There is **no generic foreground service**, boot receiver, alarm restart or background-start workaround. Android can still disconnect/kill the process; recovery requires explicit user action. Screen-off/lock pauses, service interruption pauses, service destruction stops. A task notification and optional accessibility overlay expose Stop. A coroutine job owns model requests, waits and action execution. Stop invalidates the gateway generation and cancels the job. Network calls cancel their underlying OkHttp call. Already-dispatched gestures can finish.

## Dispatch and trust

```text
Task / Script / Model function / MCP request
                    |
             typed validation
                    |
       Gateway (single run generation + mutex)
          |                  |
     app preflight      budgets / cancellation
          |
  fresh local screen metadata + filtered tree
          |
  Policy: deny > allow, read vs act, protected apps
          |
  exact approval, expiring narrow grant, or local automatic-access opt-in
          |
  re-observe and re-evaluate policy after approval
          |
   Android dispatch / consented MCP HTTP call
          |
  metadata audit and observed result (no raw arguments in audit)
```

The model can request a plan/checklist or propose memory; none grants permission. The UI alone changes policies and reviews durable notes. An MCP catalog inspection is a consented read operation through the same gateway; calls are treated as mutations irrespective of server annotations. Batches call the gateway once per substep. Scripts call the same gateway for observations and mutations. Requests cannot change a profile's credential destination.

`PolicyConfig.allowAllApps` defaults to false, including when reading older settings. The local Settings button enables persistent Read/Act access to ordinary apps and skips device-action approval after target/screen validation. `Policy.appRule` supplies the same effective permission to APPS discovery, service observation and the gateway. Explicit Block rules, protected packages, stale/locked/mixed screens and enabled sensitive-content checks, overlay bounds and run budgets retain precedence. MCP consent and approvals are unaffected. Settings and task-screen disable controls stop the active run before saving the change. Individual app rules and scoped grants are preserved; Revoke all grants clears grants and disables this option. The setting is encrypted locally, absent from backups, and unavailable to model tools or imports.

Screen references include a content-derived snapshot ID and node index, tied to window, package, focus, rotation, dimensions and revision. A fresh filtered tree invalidates changed targets. On API 33+ inspection disables Android’s node cache/prefetch; API 30–32 refreshes each visited node and rejects obsolete nodes. The API 35 regression exposed cached values lagging behind an already-visible update. Android performs another check immediately before dispatch. This reduces TOCTOU exposure; it cannot make Android UI state and gesture dispatch atomic. Semantic targets are preferred. Coordinates have bounds checks, current-context requirements and approvals unless automatic access is enabled. Coordinate taps are rejected when they touch a protected control; swipes are conservatively rejected when their bounding box intersects a protected control. Coordinates outside the panel remain usable. Screenshots still require hiding the panel.

The agent can recover from known device rejections before execution: stale targets/approvals, expired approvals, uncertain screens/capture and protected controls. It reports a payload-free rejection status to the model, cancels the remaining proposals from that response (including batch substeps and later calls), and requires a successful OBSERVE for that app before any new device operation. It never replays the old action. A new proposal passes through the full gateway and requires a new approval if ordinary policy calls for one. At most three recovery rounds are allowed without a successful device mutation; the normal request, step and time budgets still apply. Uncertain dispatch, network failures, permission denial and secret/system restrictions are not retried. Typed scripts continue to fail on stale state instead of receiving model replanning.

## Action-loop latency

For non-demo providers, the runner supplies the permitted APPS result before the first model request. It also supplies a fresh foreground screen only when that package has observation permission; foreground-package discovery itself reads metadata, not the tree. Both APPS and OBSERVE still execute through `Gateway`. The extra context is transient and explicitly marked as untrusted data. Short tasks need no PLAN/CHECKLIST round.

After each successfully dispatched device mutation, the runner calls OBSERVE through the gateway and appends the fresh screen to the same function-call result. Navigation can observe the new foreground package if it is permitted. This eliminates a separate model request just to ask for another observation. Snapshot/node references are never guessed or reused across changes. A failed follow-up read is reported separately as `observation_unavailable`: the preceding mutation remains dispatched, must not be replayed, and completion remains blocked until verification succeeds. Permissions are checked again for every automatic read.

Android dispatch uses bounded screen-readiness polling instead of fixed post-action sleeps. A readable, focused, unlocked, unmixed, non-sensitive screen with unchanged snapshot binding must remain stable for 80 ms, checked every 40 ms. Launch waits at most 1,200 ms; other accepted actions wait at most 350 ms. This is a readiness hint, not an authorization or an atomic guarantee of stability. The normal gateway inspection and immediate dispatch checks remain authoritative. Explicit WAIT_FOR and approval-overlay removal retain their separate waits.

The default-on **Faster model decisions** setting requests `reasoning.effort=low` for the known supported Responses models listed in `ResponsesProvider.supportsLowEffort`. It keeps the user's model, account and provider unchanged. Turning it off omits the override; unknown/custom models and compatible-provider requests do not receive an unsupported parameter. ChatGPT still uses `store=false`, `stream=true` and completed-response validation.

Transient run metrics use a monotonic clock and contain phase, operation enum, request/tool counts and milliseconds only. The task screen shows elapsed time, model/network time and phone/tool time after a run. Provider timing includes authentication preparation, HTTP and model work together; it does not distinguish network from server execution. Tool timing includes local approval waits. Scripts are timed as one execution block without a model request. No screen contents, tool arguments or credentials are included in timing samples. See [the controlled emulator comparison](VERIFICATION-PERFORMANCE.md).

## Persistence and recovery

`Vault` stores separately named AES-256-GCM records in `noBackupFilesDir`, using a non-exportable Android Keystore key and the record name as AAD. Android `AtomicFile` handles interrupted writes; a reader accepts only authenticated records. History uses schema 2, with an explicit schema-1 migration. Records are size limited, unknown fields rejected and IDs validated. Schema or authentication failures are reported and existing files are not deleted automatically.

The audit write happens before device dispatch. An interruption leaves its outcome uncertain. Loading history converts in-flight states to `INTERRUPTED`. No persisted pending command is executed on launch. Continuation sends retained sanitized messages and requests fresh inspection. Screenshots and model tool-call payloads are transient. Audit records deliberately have no free-form argument/result field. Conversation prose and scripts pass sanitization before serialization, with documented detection limits.

History updates publish immutable in-memory snapshots immediately and enqueue ordered sanitization, JSON encoding, Keystore encryption and atomic file writes on an IO dispatcher. The gateway awaits a persistence barrier before dispatch, then checks cancellation/pause and app preflight again. A failed write stops the run and surfaces a storage notice; it cannot silently authorize an action without an audit. Redundant unchanged conversation-state saves are skipped. Settings/permission commits retain their existing synchronous semantics. History writes no longer block the main thread that draws the UI and processes touches.

The chat and history use lazy lists, as do app permission cards in Settings. The composer stays below the scrolling conversation, and its draft state is read within its own composition so typing does not invalidate the whole page. Package labels load on IO; screen flows are collected only while the activity is at least started. Opening/selecting a conversation updates its last-used time, and a new process chooses the most recently used conversation.

The service declares `flagRequestAccessibilityButton` in its metadata and registers the Android accessibility-button callback while connected. The shortcut, notification and tile route through a single explicit `OPEN_CHAT` intent with NEW_TASK/CLEAR_TOP/SINGLE_TOP. The activity preserves the current runtime conversation and saved draft, selects the Task tab and requests text focus/keyboard only after its window has focus. Each invocation uses a monotonic request token, including recreation. Opening the chat does not start or approve a task; authentication and system permission UI remain manual.

## Model protocols

ChatGPT uses its own dynamically issued registration, a stable opaque host UUID, browser PKCE/state/nonce, a temporary IPv4 loopback listener, RS256 JWKS validation, verified issuer/audience/sub/expiry, scoped token storage, serialized renewal and logout. A failed new sign-in does not overwrite the active registration. Pending attempts exist only in memory, so activity recreation keeps them through the application runtime; process death abandons them and requires a fresh attempt.

Account model discovery uses the documented `models` list and visibility/slug/display name. ChatGPT requests go only to `https://api.openai.com/v1/responses` with `store=false`, `stream=true`, full `input`, instructions and namespaced client function tools. No unsupported HTTP continuation or hosted MCP/computer-use tools are used. Calls execute only after `response.completed`; partial/incomplete/failed streams do not dispatch tools. Image attachments and captured images use data URLs. OpenAI API-key mode uses Responses; compatible profiles use streamed Chat Completions with explicit capability flags.

Completed `response.output_item.done` items are retained by index. Live ChatGPT streams can leave the terminal response output array empty; in that case the retained items supply calls, text and subsequent conversation context, only after successful stream completion. This behavior was reproduced and verified with a real account-backed app launch; see [live verification](VERIFICATION-LIVE-CHATGPT.md).

Context compaction occurs only between completed rounds. It preserves the original task and an explicit record of attempted operation/status summaries, asks for a fresh observation, and forbids repeating uncertain actions. It is intentionally lossy: complex long tasks may need clarification. Inference/action requests are not blindly retried; token revocation has three bounded attempts. Usage is displayed only when provided, without fabricated prices.

## Bounded extensions

Scripts are a finite JSON instruction list, not arbitrary Lua. They have at most 40 steps, 12 bounded typed parameters, 64 KB source, a 120-second deadline and gateway budgets. No loops, exception handling, recursion, imports, reflection, file/network/shell functions or dynamic loading are available. Exact label lookup must resolve to a single node in a new approved observation.

MCP implements the 2025-11-25 Streamable HTTP request/response transport, including initialization, session/version headers, JSON/SSE responses and bounded paginated catalogs. No unsolicited server requests are executed. No stdio, hosted MCP, sampling, roots or elicitation capabilities are granted. Schemas are conservatively validated with an intentionally bounded subset; unsupported forms fail. A changed tool's name, description or schema changes its consent fingerprint.

## Source checks (2026-10-01)

- [OpenAI OSS overview](https://developers.openai.com/siwc/token-sharing-open-source), [sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), [models/inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference), [preview limits](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations), [accounts/sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions).
- [Function namespaces](https://developers.openai.com/api/docs/guides/tools); [OpenID discovery](https://auth.openai.com/.well-known/openid-configuration).
- [Android target API requirements](https://developer.android.com/google/play/requirements/target-sdk), [AGP 9.2 compatibility](https://developer.android.com/build/releases/agp-9-2-0-release-notes), [Compose BOM](https://developer.android.com/develop/ui/compose/bom), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).
- [MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports).

Play API targeting is separate from Play's Accessibility-policy review. No store acceptance or policy approval is claimed.

Node freshness APIs: [AccessibilityService cache control](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#setCacheEnabled(boolean)) and [AccessibilityNodeInfo.refresh](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#refresh()).

## Voice input and notification controls (0.1.4)

`VoiceInput` delegates a user-initiated free-form recognition request to Android through Activity Results. No model tool can activate the microphone. The system/provider manages audio and permissions; only a bounded transcript returns to the composer. Pending recognition and conversation identity survive activity recreation. Successful text appends to the current draft, cancellation preserves it, and changed-conversation results are rejected. The draft remains local until Send.

`TaskNotifications` replaces the top Accessibility control bar. It posts an ongoing, quiet, localized notification while the bound service is connected, with Pause/Resume and Stop for active runs. Idle/terminal status retains the chat entry without obsolete actions. Immutable explicit PendingIntents target the private control receiver or the singleTop chat activity. Notifications hide their contents on the lock screen. Permission/channel disablement exposes an Enable control in the app; the system can still dismiss ongoing notifications on newer Android versions. Resume dismisses the notification shade on Android 12+ and briefly waits for its animation before resuming; on Android 11, close the shade manually. Stop during this brief delay prevents resumption. Service destruction cancels the notification. The old encrypted `floating` setting is decoded for compatibility but cannot restore the removed bar. Approval overlays and their bounds protection remain.

Accessibility/notification/assist chat entry pauses active work before requesting foreground input. Repeated Pause is idempotent, and replies arriving during Pause cannot replace that state or dispatch before Resume. Composer focus waits for both RESUMED lifecycle and window focus, with bounded IME retries. Foreground requests never create a new conversation or automatically execute a draft.

## Questions and chat bubbles (0.1.5)

Both explicit ASK tools and text-only responses persist their question before publishing it to the UI. Runtime combines the question, run state and current conversation into a transient InputRequest with a fresh identity for each request. The Accessibility service displays a rounded, non-focusable, bounded preview only while waiting and outside the requesting chat. Tap/dismiss acknowledges that request; Stop, answer, service teardown and screen lock remove the surface. Its exact attached window and bounds participate in the same capture/mutation protections as approval controls. No SYSTEM_ALERT_WINDOW permission, new exported component, or network path is added.

The bubble and notification create an explicit chat Intent whose identity and extra bind it to the requesting conversation. Opening that same active chat leaves WAITING_USER intact; Send supplies the answer to the suspended agent. Other running states retain pause-on-chat behavior, and manually paused/locked tasks still require Resume. The question also remains in encrypted history so an older notification can open the correct conversation after the active request is gone without replaying work. The composer waits for resumed window focus before opening the keyboard.

MainActivity no longer sets FLAG_SECURE, enabling user screenshots throughout MagicPhone. This is independent of the Gateway protections against automated access to MagicPhone and other protected packages.

## Model capabilities and settings migration (0.2.0)

The retired `policy.planOnly` and `fastDecisions` fields are removed only by the encrypted-settings migration; action/script/network JSON remains strictly decoded. Action execution still goes through Gateway and app permissions. Existing fast-decision preferences migrate to per-profile Low/default reasoning. Catalog models retain supported effort and available service-tier metadata; explicit empty effort metadata takes precedence over documented fallbacks. ChatGPT speed options never derive from API availability or upsell tiers. Profile/account changes invalidate in-flight catalog requests and prevent stale results from replacing the current catalog. Account changes also clear account-bound capabilities and speed preferences. Responses validates configured effort/tier before sending an inference request.

## Manual model options and response metadata (0.2.1)

`Profile.manualModelOptions` is an explicit local opt-in for OpenAI/ChatGPT profiles. Dynamic choices remain the default. In manual mode the model picker merges account results with clearly labeled presets and permits custom IDs; capability validation permits the bounded known effort/tier enums without asserting server support. Refreshes and model switches preserve explicitly selected manual settings. Disabling the mode normalizes preferences against fetched/documented capabilities; priority/fast are equivalent aliases. Account switching still clears account-bound speed/capability data.

`ModelRunInfo` carries the requested settings and allowlisted metadata from the final completed Responses object. It contains no prompt or response text. Agent exposes only the current run’s last successful response metadata and clears it on new runs/conversation changes. The UI distinguishes missing metadata, reported default processing, and a matching requested tier; a successful HTTP response never proves Ultrafast by itself. All actual device actions still pass through the unchanged Gateway.

## Popup action continuity (0.2.6)

Gateway brackets device operations in `DevicePort.withUnobstructedScreen`. Android temporarily removes only the working bubble before inspection and keeps it out through validation/approval/dispatch. Restoration is debounced by 200 ms without delaying the next tool; cancellation restores eligibility without resurrecting stopped work. Approval/question controls and the notification remain independent. Popup visual tasks capture after mutations and explicit OBSERVE calls, replacing the previous automatic screenshot in context. Known invalid targets receive fresh-observation feedback; unverified popup completion gets at most two model opportunities to verify, never an automatic mutation replay.

`PolicyConfig.checkSensitiveContent` defaults true. Its Settings → Access switch stops work before changing encrypted local policy; it governs detected sensitive screens/nodes, password text filtering and the credential-scan capture gate. App permission, lock, unknown-window, snapshot/coordinate and Android secure-window checks remain separate. This setting is absent from backups and cannot be changed by tools/imports.

Completed model responses with malformed tool parameters receive matched, payload-free validation feedback. All sibling calls from that response are withheld, including otherwise valid siblings. The model can correct its request twice; repeated invalid responses stop with a specific error. Responses without trustworthy call IDs remain protocol failures. Gateway captures its run generation before suspending for overlay removal, preventing an old request from dispatching after Stop/restart.
