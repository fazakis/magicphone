# Contributing

Use JDK 21 and Android SDK platform 37.0/build-tools 36.0.0. Run `./gradlew :core:test :app:lint :app:assembleDebug :app:assembleRelease :fixture:assembleDebug` and `python3 tools/release-check.py`. Use a dedicated emulator for `:fixture:installDebug :app:connectedDebugAndroidTest`.

Security changes need tests for the actual bypass they close. Keep the authoritative policy boundary in `core/Policy.kt`. Do not add device dispatch or MCP HTTP calls directly to UI/model/script code. Do not trust model tool arguments, screen labels, server descriptions, imported notes, or plans as permission. Never log requests, responses, tokens or authorization URLs. Keep English and Greek resources in parity.

Use original work or properly licensed dependencies. Preserve Nikos Fazakis attribution and license original contributions under MIT OR Apache-2.0, matching the project. Update the feature matrix, threat model and progress record when behavior or verification changes. Do not commit `INSTRUCTIONS.md`, signing keys, credentials, screenshots or build outputs. A contribution does not authorize publishing a repository or release.
