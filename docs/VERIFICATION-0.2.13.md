# MagicPhone 0.2.13 verification

Verified on 2026-10-14. Version code 20. This release adds file attachments and retained document context for later screen explanations.

## Behavior

The paperclip opens Android's document picker for every MIME type. Android Share accepts files as well as text. Users can attach up to three files per message, up to 10 MiB per file, including a file-only message. Pending cards show names, sizes and PDF page counts; saved documents open an in-app preview. Existing saved photos remain compatible.

PDFs retain their original bytes and can be recalled one page at a time. Android 15+ extracts a bounded text index; older supported versions and scanned pages use rendered page images. Plain UTF-8/UTF-16 text, source code, CSV/JSON, DOCX, PPTX, XLSX and OpenDocument text are extracted locally. Spreadsheet values resolve shared strings and use stored/cached values; no macros, formulas or uploaded code execute. Unsupported binary formats remain attachable but clearly report unavailable contents. Protected or damaged PDFs do not masquerade as readable documents.

Attachments are encrypted separately from history and remain scoped to their conversation, including replies to ASK, later tasks, branches and process restarts. The Gateway handles every model retrieval. Startup context includes recent files plus a catalog for older ones; RECALL can search text or request a PDF page/text part. An Accessibility explanation combines document definitions, methodology and units with the fresh screen. Only the current screen supplies visual highlight coordinates.

## Build, signing and update

The checked-in Gradle wrapper builds the production release APK and the instrumentation harness using JDK 21, SDK 37 and build-tools 36.0.0. **152 core tests pass** with no failures/errors/skips. Release lint reports **0 errors / 18 warnings**. The release audit passes wrapper verification, component/permission restrictions, non-debuggability, backup/cleartext policy and English/Greek resource parity.

- APK: `magicphone-0.2.13-release.apk`
- SHA-256: `32e5a34ea73c045722dd9e5934503dc6fef2500260ac5c0ce1e36bfc7db4d523`
- Release certificate SHA-256: `e15cef3dd788893f862ff20f7a19f08de96c3450bb2acf9eb1331a40a3b979b2`
- Package: `dev.magicphone.app`; Android 11 / API 30+; non-debuggable.

The dedicated API 35 emulator accepts an in-place update from 0.2.12. The existing account remains signed in. Production signing identity and v3 rotation lineage are unchanged; signing secrets remain outside the repository. No new dependency is added.

## Device verification

The non-debuggable shipping APK was tested on the dedicated Android 15 / API 35 emulator with synthetic documents and the isolated Practice app. The selected 51-case run completed with 41 passes, eight intentionally skipped opt-in cases and two failures. Both failed cases (credential-screen capture with the local filter disabled, and returning to a focused background draft) passed a separate two-case rerun on the same APK. This gives **43 distinct deterministic device cases passing across those runs**, not a claim that the combined run was entirely green.

Coverage includes the real Android PDF picker, file-only submission, named cards and page previews, long-history follow-up, branches/deletion/conversation isolation, a file sent while the model awaits a reply, encrypted reload, UTF-8/UTF-16 and Office extraction, spreadsheet shared strings, unsupported/corrupt files and bounded archive/XML parsing. Existing photo, gesture, popup, highlight, speech, dictation and notification controls were also exercised.

The two opt-in document restart cases pass separately: save the PDF, force-stop the app externally, then load the same conversation in a fresh process and verify that the next model request contains the retained paper's method definitions and evaluation context without reattachment.

**Live ChatGPT test passes in 49.765 seconds**, including the initial upload/read and the later Accessibility request. The synthetic two-page paper defines Method A as the Orion baseline classifier, Method B as the Lyra calibration method, and explains paired samples, per-run timing and uncertainty limits. These details do not appear on the visible table. After the upload, the test removes the model's acknowledgement from history so the retained file must provide that background. A later popup request in the same conversation produces 16 spoken sections combining those definitions with the fresh table values (84.2%/85.5%, 12/8 seconds and 72 samples). It correctly explains the 1.3 percentage-point difference, paired design and unknown statistical significance. The test verifies actual Android TTS startup and model-provided visual regions. The release includes a synthetic screenshot and transcript; neither contains personal documents or account data.

During initial speech verification, the emulator's updated Google TTS package crashed with an illegal CPU instruction in its native library. Restoring the dedicated emulator's factory Google TTS package resolved that environment failure; the unchanged MagicPhone speech implementation then passed English/Greek playback and guided-highlight checks. The force-stop test also cleared the emulator's Accessibility binding; it was explicitly re-enabled before the live screen test. Neither operation changes a user's physical phone.

## Limits

Any file type can be selected; that does not imply every binary format can be interpreted. Audio/video, legacy binary Office files and unsupported archives have no content decoder in this release. PDF page images require an image-capable model. Android 11-14 PDF reading uses visual page retrieval, without the Android 15 text index; this release's new device tests run on API 35, not every supported Android version or a physical foldable.

Text extraction is bounded to 500,000 characters. PDF indexing covers up to 200 pages with up to 16,000 characters per indexed page within that total; any page remains available visually by explicit recall. Initial document excerpts are bounded, labelled and searchable; the model must retrieve additional pages/parts when needed. Office extraction omits embedded diagrams and formatting; PDF is the preferred format when these matter. Large/complex archives can be retained with an extraction-unavailable notice after the 8 MiB expansion bound is reached.

Files stay local and encrypted, but relevant extracted text and page images are sent to the selected provider during the user's task. Backups still include references, not attachment bytes; imported references cannot access unrelated local files. New chats intentionally do not inherit another chat's documents. Unsent attachments can be lost on process death. Model interpretations and selected highlight locations can still be imperfect.
