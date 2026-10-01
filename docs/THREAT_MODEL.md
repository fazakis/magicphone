# Threat model

Assets: provider credentials, private screen contents, conversation/backup data, local policy authority, and actions in third-party applications. Trust roots: Android OS/Keystore, the user's local UI decisions, trusted distribution/signing, and configured model/server origins. Models, screens/websites, document attachments, provider output, scripts, memory and MCP results are untrusted.

| Threat | Enforced boundary | Test / limitation |
|---|---|---|
| Prompt injection changes permissions | No permission-granting tool; policy outside model | Plan/deny/script/MCP regression tests; user can still manually choose an unsafe grant |
| English keyword bypass or Greek payment label | Approval based on operation/app/context, never button wording | Bilingual-label tests; arbitrary business intent cannot be inferred reliably from Android nodes |
| Coordinate target ambiguity | Snapshot, bounds, overlay guard and default per-action approval | Automatic access skips approval, retains target checks; Android dispatch has a residual state race |
| Replayed/expired approval | Per-request deferred decision, fresh random request ID, exact action/screen digest, 60s expiry | Replay, expiry, state-change, policy-change, stop tests |
| Recovery replays an old action or stale batch tail | Only known pre-execution device rejections can replan; cancel remaining proposals, require OBSERVE, revalidate new proposal | Batch-tail cancellation, mandatory fresh observation, new approval, bounded recovery and ambiguous-dispatch no-retry tests |
| Plan-only bypass via batch/script/unadvertised tool | Dispatch rejects mutation independently of advertised schema and plan | Unit tests; imported plans never modify policy |
| Blocked app leakage | Read preflight before traversal; selected root only; uncertain windows fail closed | Blocked-inspection tests and fixture capture checks |
| Password or OTP leakage | Password/input-type detection suppresses observation; secret fields never receive automated text; secret-pattern sanitization | Fixture password test without FLAG_SECURE; custom-drawn or mislabeled secrets cannot always be detected |
| System privilege/self-approval automation | Own app/settings/permission installer/system UI protected; overlay bounds protected | Protected-package/overlay tests; controls require real local interaction |
| Approval clickjacking | Own app and overlay bounds remain protected from model actions; overlay controls filter obscured touches. User-requested screenshots of MagicPhone are enabled | Other privileged accessibility services and rooted/compromised OS are outside threat model |
| Credential exfiltration via URLs/redirects | Parsed origins, TLS, no userinfo/query/fragment, no redirects; credentials bound to origin | URL confusion and redirect tests; DNS answers checked by actual connection resolver |
| Local network SSRF | Explicit per-profile local opt-in; only literal 127.0.0.1 may use HTTP | Address/cleartext tests; user-configured local TLS trusts Android system CAs |
| Compromised OAuth callback | PKCE/state/nonce, loopback-only listener, exact callback path, one use, ID-token signature/claims | State, replay, nonce, issuer/audience, refresh and logout protocol tests |
| Crash repeats purchase/message | Pre-dispatch audit; no command queue replay; interrupted state | Process-recovery tests; user inspects uncertain actions before continuing |
| Stop ignored by script/model | Coroutine cancellation, gateway generation, serialized dispatch and hard budgets | Cancellation/budget tests; already-dispatched gesture may complete |
| Backup import changes endpoint but retains key | Backups contain no profile/policy/credential/endpoint fields; unknown fields rejected | Hostile endpoint/escalation tests; scripts inert and notes unreviewed |
| ZIP/path traversal | No archive extraction; content-only typed envelope and strict IDs | Traversal/absolute ID tests |
| Stored raw arguments evade redaction | Audit schema has no payload fields; only operation/app/status; prose sanitized before serialization | Raw-argument and echoed-secret tests; arbitrary sensitive prose remains a known limitation |
| MCP expands authority after consent | Per-server/per-tool enablement; capability fingerprint; rediscovery before calls | Changed-schema test; all remote calls treated as side effects |
| Malicious scripts hang or escape | Finite typed AST, no loops/imports/ambient functions, size/step/deadline limits | Budget, schema, cancellation and policy tests |

The local **Allow all apps without asking** option deliberately removes per-action review and individual app allowlisting. It defaults off and cannot be enabled by model output, scripts or imports. It permits ordinary text and valid coordinate actions as well as semantic actions, so an incorrect or injected task can cause unintended changes without a confirmation. Explicit Block rules and protected/sensitive-screen checks still apply. The task screen visibly indicates this mode and offers an immediate disable control; disabling stops the current run before restoring ordinary policy.

Do not allow banking, authenticator, password-manager or other sensitive applications merely because screenshots appear protected. FLAG_SECURE does not guarantee hidden Accessibility text. Block these apps unless you deliberately accept exposure; leave payment authorization and secret entry manual. No implementation can reliably recognize every OTP, secret or payment-confirmation control in arbitrary third-party UI. Per-action review is the default; automatic access and temporary grants remove opportunities to catch mistakes. Screenshots have no general-purpose OCR redaction guarantee; capture is suppressed on known-sensitive/uncertain screens, but an ordinary-looking screen can contain private data.

Other residual risks: unlocked-device local access, malicious signed updates, compromised model accounts, provider retention policies, visual/state races between final validation and OS execution, OEM background restrictions, and unsupported accessibility trees. Imported script content can contain harmful proposed actions even when it is inert; local review is required. Revoking local app access does not delete data already transmitted to a provider.
