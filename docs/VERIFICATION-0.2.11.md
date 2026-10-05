# MagicPhone 0.2.11 distribution verification

Verified on 2026-10-05. Version code 18. This is a packaging/signing release; runtime behavior is unchanged from 0.2.10. The build uses the checked-in Gradle wrapper, JDK 21, SDK platform 37 and build-tools 36.0.0.

## Build and artifact

- `:app:assembleRelease`, `:app:lintRelease` and `:core:test` succeed.
- Core: 140 tests, zero failures/errors/skips. Release lint: zero errors, 12 existing warnings.
- `tools/release-check.py` passes the wrapper checksum, exported-component allowlist, release debuggability/backup/cleartext checks, permission restrictions and English/Greek string parity.
- `apksigner verify --verbose --print-certs --min-sdk-version 30` validates the APK's v3 signature and signing rotation. `zipalign -c -P 16 4` succeeds.
- APK: `magicphone-0.2.11-release.apk`.
- APK SHA-256: `9230dc79f55ed8f97c30239c0282907d9795cbc05a2c77cb326fd915351e22e1`.
- Current certificate SHA-256: `e15cef3dd788893f862ff20f7a19f08de96c3450bb2acf9eb1331a40a3b979b2`.
- Previous official debug certificate SHA-256: `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`.

The APK is non-debuggable. It uses a dedicated RSA-4096 release identity, not the Android development identity. Private keys/passwords are excluded from the repository and public release assets.

## Signing migration and device checks

Dedicated Android 11 (API 30) and Android 15 (API 35) emulators accepted an in-place upgrade from the previously published 0.2.10 debug APK to a release-signed 0.2.10 package using the same unsigned release artifact. Both then accepted the final 0.2.11 release APK. Android 11 retained its original UID and a synthetic app-private migration marker. Android 15 opened the previously encrypted practice-task conversation after the signing change. No uninstall was used for these upgrade checks.

The first test lineage disabled the previous signer's signature-permission capability. Android rejected that upgrade with `INSTALL_FAILED_DUPLICATE_PERMISSION` for the AndroidX generated `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. The final lineage retains installed-data and permission capabilities, disables rollback, and sets rotation minimum SDK 28. That corrected lineage passes both upgrade tests.

The existing instrumentation harness was signed with the release identity and used against the actual, non-debuggable APK. `DeviceAcceptanceTest` passes **7/7 on Android 15** and **7/7 on Android 11** for the final 0.2.11 artifact. Coverage includes actual fixture tapping, stale-target rejection, password-screen detection, encrypted vault persistence, Greek resources/share entry, Unicode text/screenshots through Gateway, and large-text dark-mode rendering. These are deterministic fixture tests, not live account inference. A subsequent fresh Android 11 installation also succeeds and opens onboarding.

The recorded practice demo shows the account-free deterministic provider operating the isolated fixture and its approvals. It is not a live-model latency demonstration. Website screenshots are real app UI captures already used on the public landing page; no user-provided personal screenshots are included.

## Limits

No model/backend/provider behavior changed. The live ChatGPT/photo-context checks recorded for [0.2.10](VERIFICATION-0.2.10.md) were not rerun against personal accounts for this packaging update. Physical-device signing migration, additional OEMs, all external providers and full store review remain unverified. Store acceptance is separate from APK signing and the passing emulator tests. Android restrictions and the app's existing early-access limitations still apply.

The website's Obtainium link uses the documented add-source URI for the GitHub repository. Source/link syntax is checked; an end-to-end Obtainium installation was not part of this verification.
