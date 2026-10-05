# Releasing and signing

Original code is MIT OR Apache-2.0. Release artifacts must preserve LICENSE, LICENSE-APACHE, NOTICE and third-party notices. Version 0.2.11 starts distribution of non-debuggable APKs signed with the dedicated MagicPhone release key. It remains early-access software; signing is not store approval or a guarantee of app compatibility. Signing keys and passwords must stay outside the repository and public artifacts. Publishing a new release requires the maintainer's authorization.

1. Read the feature matrix and complete outstanding real-device/OAuth acceptance for the intended release audience. Review Accessibility disclosures and current store policy separately; API targeting is not store approval.
2. Use JDK 21 and SDK platform 37.0, build-tools 36.0.0. Verify wrapper with `shasum -a 256 -c gradle/wrapper/gradle-wrapper.jar.sha256`. The distribution checksum is enforced by Gradle.
3. Run the README build/test commands and `python3 tools/release-check.py`. Run instrumentation on the dedicated API 30 emulator and add a current API/OEM device to your release matrix.
4. Review `gradle/verification-metadata.xml` and dependency inventory. Regenerate verification metadata only after independently reviewing dependency updates; a regenerated checksum is not proof of trust. CI pins action commits and has read-only repository permissions.
5. `assembleRelease` produces an **unsigned** APK. The debug and fixture APKs use the build host's standard `~/.android/debug.keystore`, which is not a production identity and is not copied into this repository.
6. The official distributor signs with the existing MagicPhone release key and reviewed signing lineage. `tools/sign-release.sh` aligns the APK, signs it with rotation enabled on all supported Android versions, and verifies its signature/alignment. Enter passwords interactively or use protected files/CI secrets; never put release secrets in command history or Gradle files.

```sh
export MAGICPHONE_SIGNING_DIR=/secure/path/magicphone-signing
tools/sign-release.sh app/build/outputs/apk/release/app-release-unsigned.apk magicphone-release.apk
shasum -a 256 magicphone-release.apk > SHA256SUMS
```

Keep the signing key, password and rotation lineage backed up for updates. The private directory contains `release.p12`, `release-password`, `legacy-debug.keystore` and `debug-to-release.lineage`. The script does not generate keys or silently replace a missing identity. The legacy debug password is the standard Android development password; the private release password is never embedded in the script.

The release certificate's SHA-256 is `e15cef3dd788893f862ff20f7a19f08de96c3450bb2acf9eb1331a40a3b979b2`. The former official debug certificate is `611ac24f17a2c6b0473838c3dc1752f39d70cd814ce67c8a30640bc4f091375b`. Android v3 proof-of-rotation links those certificates. The lineage retains installed-data and signature-permission capabilities for the former signer, including the AndroidX generated receiver permission, and does not permit rollback to that signer. The rotation minimum is explicitly 28; relying on the signing tool's default would not rotate the key on all supported Android versions.

Users of the previous official APK can install the release over it without uninstalling. Android 11 and 15 upgrade checks are recorded in [0.2.11 verification](VERIFICATION-0.2.11.md). A build signed with an unrelated developer key is not covered by this lineage. Do not suggest uninstalling as a routine fix: uninstalling deletes local Keystore data, and backups deliberately exclude credentials and photo files. After migration, install official release builds for subsequent updates; an old debug-signed build is not a supported downgrade.

Inspect a built APK's merged manifest (also enforced by the release-check script): only MainActivity, the permission-bound Accessibility service and the permission-bound tile may be exported. The control receiver must remain non-exported. No debug command receiver, shell service, boot receiver, broad file access or global cleartext permission may appear.

`tools/remote-build.sh` is an optional development helper; its SSH address is not embedded in the Android app. Retrieved artifacts under `artifacts/` are ignored. Each final APK set should include SHA256SUMS and the debug certificate's SHA-256 if distributing a development build. Never describe the unsigned APK as installable or production signed.
