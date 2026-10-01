# MagicPhone

**Your phone. Your say.** An original, open-source Android assistant by Nikos Fazakis.

Describe a task and let MagicPhone operate your apps. By default, you select applications and approve actions; an optional **Allow all apps without asking** button enables automatic app access and execution. MagicPhone runs on an ordinary, non-rooted Android 11+ phone using Android Accessibility APIs. Day-to-day operation requires no computer, ADB, or developer-operated backend. Cloud models receive selected task/screen context; this is **not an entirely offline assistant**. A deterministic practice mode makes no model requests.

The application contains a Compose/Material 3 UI in English and Greek, an actual task runner, Accessibility device tools, externally enforced permissions/approvals, browser-based ChatGPT authentication, Responses and compatible-provider streaming, encrypted conversation history/backups, typed scripts, app-specific memory/playbooks, and optional MCP over Streamable HTTP.

This is an early implementation, not a claim of universal third-party-app safety. Read the [feature/verification matrix](docs/FEATURE_MATRIX.md) and [remaining acceptance checks](docs/ACCEPTANCE.md). Live ChatGPT operation has been verified on a dedicated Android 15 emulator with account-owner consent; eligibility and behavior on other accounts/devices can vary.

## Website

The landing page lives in [`site/`](site/) in this repository and deploys independently with GitHub Pages. The custom domain is [magicphone.org](https://magicphone.org). See [website development and DNS setup](docs/WEBSITE.md).

## Build

Prerequisites: JDK 21, Android SDK **platform 37.0**, build-tools 36.0.0, and network access for pinned build dependencies. Set `ANDROID_HOME` or create the ignored `local.properties` with `sdk.dir=...`. `targetSdk=36`, `minSdk=30`; current Compose needs `compileSdk=37`.

```sh
./gradlew :core:test :app:lint :app:assembleDebug :app:assembleRelease :fixture:assembleDebug
python3 tools/release-check.py
```

The checked-in Gradle 9.4.1 wrapper JAR was checked against Gradle's published SHA-256; the distribution checksum is enforced in its properties. CI actions are pinned by commit. See [release instructions](docs/RELEASING.md) for dependency verification, APK checksums, signatures and installing builds.

Build outputs:

- `app/build/outputs/apk/debug/app-debug.apk`: signed with the build machine's Android debug key.
- `app/build/outputs/apk/release/app-release-unsigned.apk`: **unsigned** release build.
- `fixture/build/outputs/apk/debug/fixture-debug.apk`: isolated practice app, debug signed.
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`: test harness only; do not distribute as the app.

This working session also retrieves outputs to ignored `artifacts/`. `tools/remote-build.sh` is an optional developer build helper for the explicitly supplied SSH host; it is not part of the Android runtime and does not publish anything.

Application ID defaults to `dev.magicphone.app`. Override it with `-Pmagicphone.applicationId=your.reverse.domain`. Kotlin namespace/class names remain stable; the Accessibility settings activity is a fully qualified class name. Renaming the application ID creates a separate Android application and separate Keystore storage. Update test component references and install instructions for your distribution.

## Install and first task

1. Copy the debug APK onto an Android 11+ phone and open it. Grant the installer permission manually when Android asks. Production users should install a release signed by a trusted distributor.
2. Read onboarding. In Settings, open Android Accessibility settings and manually enable MagicPhone. Android may require **Allow restricted settings** in App info for a sideloaded app. The agent cannot approve this itself.
3. Enable notification controls. Pause/Resume and Stop live in an ongoing notification while Accessibility is connected; the old floating bar is removed, including on upgrades. Tap the notification or Android Accessibility shortcut to open the current chat and keyboard. A running task pauses so it cannot take the screen back; tap Resume to continue. Android 14+ can allow dismissing an ongoing notification; opening MagicPhone restores it.
4. Tap the microphone in the message field to dictate through your Android speech provider. Review or edit the returned text, then Send. No OpenAI API key is required. Recognition availability, language support and offline behavior depend on the provider; it may send audio to its own servers.
5. Select applications. **Read**, **Act**, and **Block** are separate choices. Block wins. No app has access by default. Plan-only is initially enabled and prevents execution regardless of model output.
6. Choose **Continue with ChatGPT**, complete consent in the system browser and return to MagicPhone. Load account-available models and select one. Eligibility and usage limits depend on the account. Optional OpenAI-key and compatible/local connections are independent profiles.
7. Describe the task. Approve each mutation, explicitly grant a selected app five minutes of semantic tap/scroll/open access, or enable **Allow all apps without asking** at the top of Settings. This persistent option turns off plan-only, permits ordinary apps without individual Read/Act selection, and skips action approvals, including ordinary text and valid coordinate actions. Explicit Block rules and protected system/MagicPhone screens still apply. Turn it off in Settings or from the task-screen status card; existing individual rules and grants return. Revoke all grants also disables automatic access. Enter passwords/OTPs and authorize payments manually. Stop cancels pending work; a gesture already sent to Android cannot be undone.

For a no-account demonstration, install the practice APK, choose **Practice with a local demo** in Settings, then start a task. The deterministic provider opens the fixture, reads its tree, proposes a tap on its bilingual counter button, and reads again. This is a real device workflow with a mock model, not live model verification.

Version **0.1.2** supplies app/screen context upfront and returns a fresh screen after each action to reduce model round trips. **Faster model decisions** is on by default in Settings; it requests low reasoning effort for supported models while preserving the selected model/account. Turn it off when you want the provider's default reasoning effort. Completed tasks display elapsed, model/network and phone/tool timing. The same open/tap/type task took a median **21.9 seconds versus 49.2 seconds** with the previous workflow in three live ChatGPT emulator runs per version; this is a controlled fixture result, not a guarantee for every app. See [performance verification](docs/VERIFICATION-PERFORMANCE.md).

Version **0.1.3** improves local UI responsiveness with background history persistence, lazy chat/history/app lists, and an input box that stays below the scrolling chat. Assign MagicPhone to Android's Accessibility button/shortcut: pressing it returns to the current chat, preserves the draft, focuses the input and opens the keyboard. The task notification and Quick Settings tile use the same chat entry. These entry points never submit a task automatically. See [UI and shortcut verification](docs/VERIFICATION-UI.md).

Version **0.1.4** adds a microphone for Android voice input, replaces the floating bar with an ongoing notification, and pauses active work when the Accessibility shortcut opens chat. Repeated Pause is safe, and keyboard focus waits for the resumed activity. See [voice and controls verification](docs/VERIFICATION-VOICE.md).

Version **0.1.5** enables screenshots of MagicPhone and shows a message bubble over other apps when the agent needs an answer. Tap it to return to the requesting chat with the draft preserved, input focused and keyboard open. Send answers a waiting question without an extra Resume step. Dismiss hides that bubble; the question remains in the ongoing notification and encrypted history. Bubbles hide while the chat is visible or the screen is locked. See [bubble and screenshot verification](docs/VERIFICATION-BUBBLE.md).

## Other entry points and libraries

Share text/images to MagicPhone, select text and choose its text action, add the Quick Settings tile, or select MagicPhone for an assist intent where supported. Entry points prefill a task; they never start execution silently. Images are bounded, re-encoded to remove metadata, and kept only in memory. Rotation/process death can discard unsent attachments.

History supports search, branching from any retained message, deletion, and explicit continuation after interruption. Old external actions are never automatically replayed. Library lets you review/edit app-scoped memory and playbooks and run typed scripts without a model. See [script format](docs/SCRIPTS.md). MCP is disabled until configured; inspect a server's catalog, review each tool, then explicitly allow it. Changed schemas require fresh review.

Encrypted backups use a passphrase of at least 12 characters. Export excludes all credentials, endpoints, policies and grants. Import shows a preview, assigns new content IDs, disables imported scripts, and marks imported notes unreviewed. Diagnostics show a preview before export and contain operation/status metadata only.

## Documentation and verification

- [Architecture](docs/ARCHITECTURE.md)
- [Threat model](docs/THREAT_MODEL.md)
- [Privacy and destination inventory](docs/PRIVACY.md)
- [Feature matrix / requirements checklist](docs/FEATURE_MATRIX.md)
- [Real-device and OAuth acceptance](docs/ACCEPTANCE.md)
- [Progress](docs/PROGRESS.md), [executed checks, signing identity, APK checksums](docs/VERIFICATION.md), and [Android 15 remote test results](docs/VERIFICATION-API35.md)
- [0.1.2 performance changes, live benchmark and APK hashes](docs/VERIFICATION-PERFORMANCE.md)
- [0.1.3 UI responsiveness and Accessibility shortcut](docs/VERIFICATION-UI.md)
- [Release and signing](docs/RELEASING.md)
- [Contributing](CONTRIBUTING.md), [security](SECURITY.md), [third-party notices](THIRD_PARTY_NOTICES.md)

Functional inspiration: [Fox-Islam/android-agent](https://github.com/Fox-Islam/android-agent). MagicPhone is independently implemented; no code, assets or documentation from that project were copied. There is no affiliation. Original source, interface, icon and website are available under your choice of the [MIT License](LICENSE) or [Apache-2.0](LICENSE-APACHE), Copyright 2026 Nikos Fazakis. Dependencies retain their own licenses.
