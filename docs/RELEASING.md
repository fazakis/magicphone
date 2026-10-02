# Releasing and signing

Repository and GitHub Pages publication are authorized. The user also authorized publishing version 0.2.1 to GitHub Releases as a regular Latest release. It remains an early-access, debug-signed development APK; the GitHub release classification does not change its signing identity or verification limits. Future APK releases remain a separate explicit action. Original code is MIT OR Apache-2.0, Copyright 2026 Nikos Fazakis. Release artifacts must preserve LICENSE, LICENSE-APACHE, NOTICE and third-party notices. No production signing material is committed or used.

1. Read the feature matrix and complete outstanding real-device/OAuth acceptance for the intended release audience. Review Accessibility disclosures and current store policy separately; API targeting is not store approval.
2. Use JDK 21 and SDK platform 37.0, build-tools 36.0.0. Verify wrapper with `shasum -a 256 -c gradle/wrapper/gradle-wrapper.jar.sha256`. The distribution checksum is enforced by Gradle.
3. Run the README build/test commands and `python3 tools/release-check.py`. Run instrumentation on the dedicated API 30 emulator and add a current API/OEM device to your release matrix.
4. Review `gradle/verification-metadata.xml` and dependency inventory. Regenerate verification metadata only after independently reviewing dependency updates; a regenerated checksum is not proof of trust. CI pins action commits and has read-only repository permissions.
5. `assembleRelease` produces an **unsigned** APK. The debug and fixture APKs use the build host's standard `~/.android/debug.keystore`, which is not a production identity and is not copied into this repository.
6. The distributor signs the unsigned release using their own securely stored key. Use Android SDK `zipalign` then `apksigner`. Enter passwords interactively or use protected CI secrets; never put secrets in command history or Gradle files.

```sh
zipalign -P 16 -f -v 4 app-release-unsigned.apk magicphone-aligned.apk
apksigner sign --ks /secure/path/distributor-key.jks --out magicphone-release.apk magicphone-aligned.apk
apksigner verify --verbose --print-certs magicphone-release.apk
shasum -a 256 magicphone-release.apk > SHA256SUMS
```

Keep the signing key for updates. A differently signed package cannot replace an installed package with the same application ID without uninstalling and losing its Keystore data. Back up user-selected content first; credentials are deliberately not transferable via MagicPhone backups.

Inspect a built APK's merged manifest (also enforced by the release-check script): only MainActivity, the permission-bound Accessibility service and the permission-bound tile may be exported. The control receiver must remain non-exported. No debug command receiver, shell service, boot receiver, broad file access or global cleartext permission may appear.

`tools/remote-build.sh` is an optional development helper; its SSH address is not embedded in the Android app. Retrieved artifacts under `artifacts/` are ignored. Each final APK set should include SHA256SUMS and the debug certificate's SHA-256 if distributing a development build. Never describe the unsigned APK as installable or production signed.
