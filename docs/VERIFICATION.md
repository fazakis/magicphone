# Verification record — 2026-10-01

These results belong to the APKs below, built from this source on the user-authorized SSH Mac using JDK 21, Android SDK 37.0/build-tools 36.0.0, AGP 9.2.1 and Gradle 9.4.1. No production signing key or personal service account was used. Build reports and binaries are local, ignored artifacts rather than committed repository content.

## Results

| Check | Result |
|---|---|
| `:core:test` | **65 tests, 0 failures/errors/skips:** Agent 4, Policy 22, Protocol 10, Provider 3, Security 26 |
| `:app:connectedDebugAndroidTest` | **7 tests, 0 failures/errors/skips** on the dedicated MagicPhone_QA30 x86_64 Android 11/API 30 emulator |
| App debug, release, fixture and test APK compilation | **Pass** |
| Android lint | **0 errors, 15 warnings** |
| `tools/release-check.py` | **Pass:** wrapper SHA-256, release exported-component allowlist, prohibited permissions and English/Greek resource parity |
| Gradle dependency verification and locks | **Pass** for the built Mac configuration; official Linux/Windows aapt2 hashes also recorded for portable builds |
| Debug app and fixture signatures | **Pass**, APK signature scheme v2, one Android Debug signer |
| Release signature | **Unsigned as intended**; `apksigner verify` exits 1 with missing JAR manifest/no valid signature |
| Large-text UI visual inspection | **Pass** for the task page in dark mode at 1.4× font scaling; Stop and entry controls visible; screenshot retained locally |
| Hosted CI / live account integrations / supported physical phone | **Not performed**; exact requirements below |

The 15 lint warnings are seven dependency-update suggestions, four unused resource strings, two Kotlin extension style suggestions, the deliberate targetSdk 36 versus latest SDK notice, and the API 31+ accessibility metadata attribute on an app with minSdk 30. No lint baseline hides errors. Node recycle APIs remain used for API 30 compatibility despite deprecation warnings on newer SDKs. The test-only Locale constructor also has a compiler deprecation warning.

The device suite exercises a real fixture tap through the agent, actual local approval buttons, new-chat/share state, stale-target rejection, password-screen blocking without FLAG_SECURE, Keystore encryption/tamper/AtomicFile recovery, Unicode script text entry and a real Accessibility screenshot. It is a controlled mock-model workflow; it does not call a live model or prove third-party app compatibility. A separate one-test render run captured the final layout after the complete seven-test suite passed. The separately installed test APK temporarily clears FLAG_SECURE only to capture this synthetic app UI; the production app retains it.

## Android 15 follow-up and refreshed artifacts

The subsequent user-requested run on `qa-build-host` passed all seven device tests on Android 15/API 35 ARM64 and all 65 core tests. It exposed an Accessibility cache lag that is now fixed by disabling node caching on API 33+ and refreshing nodes on API 30–32. The updated app passed the full seven-test API 30 regression suite as well. The APKs/checksums below were refreshed after this fix, using the original build host’s debug signing identity. The API 30 emulator needed a longer cold boot; an initial install attempt failed before it became responsive, then the complete run passed once boot finished. See [the API 35 record](VERIFICATION-API35.md) for host details, the initially failing regression and the passing results.

## Reproduction

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SERIAL=emulator-5580 # dedicated disposable API 30 emulator only
./gradlew :core:test :app:assembleDebug :app:assembleRelease \
  :app:assembleDebugAndroidTest :app:lint :fixture:installDebug \
  :app:connectedDebugAndroidTest --console=plain
python3 tools/release-check.py
python3 tools/dependency-inventory.py
```

The final combined build after the Accessibility cache fix completed successfully in 1m 34s on the build host. Its log is `artifacts/build-and-test.log`. Human-readable reports are `artifacts/core-reports/test/index.html`, `artifacts/device-reports/connected/debug/index.html` and `artifacts/lint/lint-results-debug.html`. Original XML results are also preserved. The final synthetic screenshot is `artifacts/qa-ui-dark-large.png`.

Wrapper JAR and distribution checksums use Gradle's official values. Runtime/build dependencies are locked and recorded in `gradle/verification-metadata.xml`. The Linux and Windows aapt2 JARs were fetched from Google Maven over HTTPS and matched its published SHA-1 values before recording SHA-256; they were not executed on this Mac. Linux CI and Windows builds remain separate environments to verify. The inventory follows parent POM licenses; three metadata-only parent POMs lack a declared cached license, and no binaries from them are redistributed.

## APKs and signing

Debug app and fixture signer DN: `C=US, O=Android, CN=Android Debug`.

Certificate SHA-256: `276609e12a27cd44ea76695a91e5c8e0a312df27b3bd5498785018e6ce001f3c`.

This is the SSH build machine's ordinary Android debug key, not a Nikos Fazakis production/distribution certificate. Its private key was not copied. The release APK has no signer and must be signed by the distributor before installation. Raw verification output is in `artifacts/debug-signatures.txt` and `artifacts/release-signature-check.txt`.

| File, relative to repository | Bytes | SHA-256 |
|---|---:|---|
| `artifacts/apk/debug/app-debug.apk` | 32,420,455 | `f68c7b4ea25b25734b0c71eef6cb3c88f7cb4ca161b717c1ef7a272311dec628` |
| `artifacts/apk/release/app-release-unsigned.apk` | 24,546,637 | `5f381ff7560ef0869c558163613ece62aad29eedc092b0036df51a436028201b` |
| `artifacts/fixture/fixture-debug.apk` | 2,611,399 | `9290b1c2fd66ea1941e370714e82834f74caf266e9acd3340df80122fea49c95` |

From the repository root, run `shasum -a 256 -c artifacts/SHA256SUMS` to verify the delivered APKs. APK checksums identify this build, not byte-for-byte reproducibility across machines; debug signing keys, toolchain and build metadata may differ.

## Exact limits

- The supplied Huawei VOG-L29 reports Android 10/API 29, below the required API 30. Only the newly created dedicated emulator was installed/configured by the test harness; the physical phone was not modified. Supported-device manual permission/OEM acceptance is still required.
- No eligible ChatGPT account owner completed live consent, account model discovery or plan inference. Refresh/revocation were tested against controlled protocol fixtures, not the live account service. Follow [ACCEPTANCE.md](ACCEPTANCE.md) to verify this integration with real consent.
- No live OpenAI-key/OpenRouter/local endpoint or external MCP deployment was configured. Adapter/security contract tests do not establish a successful deployment-specific integration.
- Hosted GitHub CI, current-API/OEM coverage, full manual English/Greek navigation, SAF storage-provider backup/import, production signing, store review and publication were not performed. CI and release procedures are prepared, and publication/signing require the user's explicit authorization.
