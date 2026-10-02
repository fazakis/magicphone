# Screen-reading and fold-transition diagnosis — 0.2.3

Date: 2026-10-02. This is a reproduction record, not verification of a fix. The installed production APK is version 0.2.3 (code 10), SHA-256 `27bd0b543b2f22bc1cc1470b553231d5f255c4437f9ccb78a0a3668fcf314325`.

Follow-up: [0.2.4 verification](VERIFICATION-0.2.4.md) records the implemented fixes and rerun results. The measurements below describe the earlier 0.2.3 APK.

## Setup and scope

Added `DocumentFixture` and `ScreenReadDiagnosticTest`. The isolated fixture generates a PDF, renders it into a view without an Accessibility text node, and prints a synthetic four-digit code in its pixels. Variants keep that PDF visible while adding ordinary UI rows, showing a keyboard, or updating a toolbar label. A separate text variant exposes a long document node. No personal document or third-party account is exercised.

Tests use production Agent/Gateway/PhoneService paths. An input-recording provider measures exactly whether the model receives an image; the separately opted-in live check uses the account owner's already authorized ChatGPT connection. Only synthetic images and metadata are retained, not provider payloads or credentials. Settings/history are saved and restored around instrumentation.

## Measured results

| Case | Observed result |
|---|---|
| Plain PDF | Three accessible controls, no PDF body/code in text nodes. One image reaches the model. Stable capture succeeds 4/4 times. |
| Same PDF with 120 ordinary UI rows | `partial=true`, `mixed=false`, `sensitive=false`, no foreign overlay. The image is omitted and the body/code are absent from model input. |
| Same PDF with keyboard | Uncovered text nodes remain available, but `partial=true` prevents the PDF image from reaching the model. |
| Same PDF with a toolbar label changing every 30 ms | All 4 captures return `stale_target`, although the PDF pixels do not change. Attempts are spaced 1.3 seconds apart to exclude the screenshot throttle. |
| Long document text node | Label is truncated to 500 characters, the final code is lost, and `partial` remains false. |
| Live ChatGPT, plain PDF | Completes correctly in one model call, with a captured image and the correct code. |
| Live ChatGPT, large UI tree | Three model calls; OBSERVE succeeds, explicit SCREENSHOT fails, no image is captured, and ASK enters `WAITING_USER` without reading the code. |

The live comparison reproduces the user's question-bubble symptom using the same visible document. It does not establish what Acrobat exposed on the physical phone at the time of the reported failure.

## Folding and unfolding

A dedicated API 35 7.6-inch foldable emulator was created separately from the signed-in phone emulator. Hinge commands changed device state from OPENED (3) to CLOSED (1) and back, but this image kept the app at 1768 × 2208 throughout. All 18 fresh observations and all three final captures succeeded. This is a posture test, not proof of cover-display switching.

A second run combined those hinge commands with a real WindowManager resize: 1768 × 2208 → 884 × 2208 → 1768 × 2208. Both size transitions initially produced `screen_uncertain`; the next five fresh observations in each phase succeeded, and all three final screenshots succeeded with current bounds. Samples were spaced 250 ms apart plus operation time. The same Accessibility service instance survived the whole run; the app window IDs changed. The captured narrow-screen PDF was visually checked and remained readable.

Opening/closing a fold can therefore contribute a brief unavailable-window interval. No persistent stale-size/cropping failure was reproduced after the window settled. Xiaomi's actual inner/outer display behavior and Acrobat remain untested; the generic resize experiment cannot establish their exact behavior.

## Why this happens in the current code

- `Agent.kt` omits the initial image whenever `Screen.partial` is true. `PhoneService.screenshot` independently rejects partial screens. A partial UI tree therefore disables the image fallback precisely when a rendered document may need it.
- Screenshot acceptance compares the entire observation binding after capture. The binding includes every extracted node, so an unrelated changing toolbar can reject an otherwise useful image.
- Node labels are limited to 500 characters without marking that truncation as partial.
- During a resize, the active/focused window can briefly be unavailable. Fresh observations recover on the tested emulator, but a model may ask the user for content before obtaining a useful image.

The two-second temporary-read notice and the model's ASK bubble are separate paths. Making the notice transient does not supply missing document pixels or prevent a model from requesting help.

Follow-up implementation should distinguish incomplete text extraction from unsafe image capture, use current app-window geometry across display changes, and decouple read-only screenshot freshness from unrelated text-node changes while retaining credential/foreign-window checks. These are identified changes to make, not shipped behavior.

## Execution and limits

- Wrapper builds: `:app:assembleDebugAndroidTest :fixture:assembleDebug` passed. Production app/core sources are unchanged.
- Non-live phone diagnostics: final runner reports 5 cases, zero failures; four executed and the live opt-in case was assumption-skipped. The fold opt-in case was added afterward and executed separately.
- Live comparison: final isolated run passes, 1 case in 25.903 seconds. An earlier concurrent run stopped at the second fixture's readiness assertion (`foreground=null`); its incomplete result was not used as a successful comparison. The cause of that harness interruption was not established.
- Fold posture measurement: 1 case passed in 8.524 seconds. Fold-plus-resize measurement: 1 case passed in 8.248 seconds. Passing means the measurement finished, not that every sampled read succeeded.
- Initial fixture readiness failures were corrected by moving the marker below the status bar, then rerunning.
- Final restoration confirms ChatGPT selected, Accessibility connected, 27 original conversations, 350 original audit entries, and the exact production APK hash above. The signed-in emulator is left open; the temporary fold emulator was shut down after restoring its display size.

Local ignored evidence is in `artifacts/screen-diagnosis-0.2.3/`: JSON measurements, synthetic screenshots, build/device logs and final restoration status. No new release was created for this investigation.

To repeat ordinary diagnostics, install the test and fixture APKs on an already configured dedicated emulator and run `ScreenReadDiagnosticTest`. Add `-e liveChatGpt true` only with explicit account-owner consent. Fold diagnostics require `-e foldDevice true` and a host coordinator: wait for each `fold-ready` phase in the app's external `screen-read-diagnostics` directory, change posture, then write the matching phase to `fold-go`. Add `-e resizeTransitions true` for the separate WindowManager-size experiment and reset `wm size` afterward. The live and fold opt-ins are skipped in the ordinary suite.
