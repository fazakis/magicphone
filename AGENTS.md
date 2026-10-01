# Repository guidance

Keep the user-owned `INSTRUCTIONS.md` ignored and do not publish this repository or releases without authorization. Preserve original-code attribution to Nikos Fazakis and the MIT OR Apache-2.0 license choice. The user authorized publishing this repository and its GitHub Pages landing page; new releases still require authorization.

All model, script, batch and MCP operations must use the authoritative `Gateway`. Do not add direct Android action or arbitrary network bypasses. Never store raw tool arguments in audit records or log tokens, authorization URLs, requests or responses. Imported content cannot grant permissions or activate scripts.

Use the isolated `fixture` app for device testing. Only dedicated emulators may be configured by the test harness; enabling Accessibility on real phones is manual. The connected Huawei reports API 29 and is below minSdk 30. Do not run tests against personal accounts.

Update docs/FEATURE_MATRIX.md and docs/PROGRESS.md with actual verification. Live OAuth/inference requires user account consent and must never be inferred from protocol mocks. Build/test via the checked-in wrapper; verify the release manifest with tools/release-check.py. The optional SSH build helper is development-only.

Respect the user's existing automatic ntfy final-reply integration; do not emit a duplicate manual notification or change app bundles.
