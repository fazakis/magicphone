# MagicPhone 0.2.10 verification

Date: 2026-10-04. Version code **17**, application ID `dev.magicphone.app`, Android 11/API 30 minimum. User-authorized release following the reported loss of an uploaded bill on a subsequent task.

## Behavior

Previously only the current run received uploaded image bytes; stored messages had no photo references, and active-task replies forwarded only text. New runs also took only the most recent 20 messages.

Schema 3 adds validated references to separately encrypted uploaded photos. Acknowledged sends have durable photo/message storage, including answers to ASK and active/paused corrections. Recent photos are supplied automatically and older messages/photos remain accessible through conversation-scoped RECALL via Gateway. Context uses bounded recent text, source-labelled excerpts and a deduplicated photo catalog. Images from retrieved documents and live screen captures remain separate through compaction. Branches share retained files; deletion/retention removes unreferenced files. Long document references and leading zeros survive conversation sanitization, while explicit credentials and labelled card numbers remain redacted. Other stored scripts/notes keep their stricter sanitizer.

## Checks

| Check | Result |
|---|---|
| Core JVM suite | **140 passed**, zero failures/errors/skips |
| Android 15 attachment suite | **5 passed**, 2 restart-phase methods skipped in the ordinary class run and executed separately |
| Android 15 separate process restart | **2 passed**: prepare a sent photo; force-stop; reopen and verify the next request contains the retained photo |
| Android 15 existing popup, input bubble and responsiveness suites | **21 passed**, 2 live popup/screen checks intentionally skipped without their opt-in; the new live photo test is run with explicit opt-in |
| Android 11 attachment suite | **4 passed**, 3 explicit skips (live account test and two separately invoked restart phases) |
| Android 11 separate process restart | **2 passed**, same force-stop/reopen sequence |
| Lint | **0 errors, 12 warnings** |
| Build/release audit | Debug, unsigned release and instrumentation APKs build; wrapper integrity, exported-component allowlist, prohibited permissions and English/Greek resource parity pass |
| Signing | Same debug certificate as preceding releases; `apksigner verify --print-certs` succeeds |

The attachment device suite tests real encrypted storage, provider request serialization against a loopback synthetic server, thumbnails and photo preview, follow-ups after 30 intervening messages, branch sharing and deletion, sending a photo while waiting for an answer, unsupported-model rejection, and stopping a submission before dispatch. The API 35 live test uses the owner's existing ChatGPT connection and a generated synthetic bill only. Its follow-up, with no new upload, returns both exact identifiers with leading zeros. No personal bill, payment or external app-account action is performed.

Core tests cover schema migration, invalid references, cross-conversation/import isolation, bounded repeated-photo catalogs, credential/document-number sanitization, waiting and paused photo replies, older text retrieval and retained document images alongside replaced live screenshots during compaction. The existing device regression suites cover popup actions, dictation, question/result bubbles, reply focus, Stop, and large-history responsiveness. The emulator Accessibility service is restored after force-stop before action tests; no production permission-granting mechanism is added.

The final API 35 package receives the attachment/live suite and restart checks; Android 11 receives the same installable APK. Tests use dedicated emulators and restore account/profile/history settings. No new physical Xiaomi test is claimed for this release.

## Limits

Photos discarded by older versions cannot be reconstructed; attach them once after updating. Unsent attachment drafts remain memory-only. Automatic screenshots stay transient. Encrypted backups contain photo references but no photo files; imported references are marked unavailable and cannot resolve to unrelated local files. Retrieval is confined to retained messages in the current conversation, subject to configured retention and existing history limits. Up to three recent photos are included automatically; further retained photos can be retrieved by the model. Excerpts are selected existing text, not a newly generated factual summary. Vision accuracy remains model-dependent; the synthetic live result is not a guarantee for arbitrary documents.

## Artifact

- APK: `magicphone-0.2.10-debug.apk`
- APK SHA-256: `f447bc504324e9b4f6d45dff17aa681728c4e906a7fdb76d02c6bb0d8b07f0d9`
- Signing certificate SHA-256: `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`
- Early-access development artifact; not production signed.
- Local ignored evidence: `artifacts/release-0.2.10/`, including core XML, lint output, device logs, synthetic-photo screenshot, signing verification and release asset checksums.

## Reproduction

Build using the checked-in wrapper with JDK 21 and the configured Android SDK:

```sh
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lint
python3 tools/release-check.py
```

On a dedicated emulator, install the app and instrumentation APKs. Run `dev.magicphone.app.ConversationAttachmentTest`. The live method requires `-e liveChatGpt true` and the account owner's consent. Invoke `prepareProcessRestart` and `verifyProcessRestart` individually with `-e attachmentRestart true`, stopping the app process between them. These restart phases deliberately skip in the ordinary suite so test ordering cannot imitate a real process restart.
