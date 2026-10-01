# Security

MagicPhone is an independently implemented early release. Its device-control boundary is designed to fail closed; automated tests do not establish that every Android/OEM behavior or every third-party application is safe.

Report suspected vulnerabilities privately to the repository owner before sharing sensitive reproduction details publicly. No reporting service is configured by this repository. Do not include live passwords, tokens, personal screen content or credentials in issues. Reproduce with the isolated fixture and attach the sanitized diagnostics preview if useful.

Until a fix is available, stop the task, revoke temporary grants, block affected apps/servers and disable Accessibility access if necessary. Revoke compromised ChatGPT sessions in ChatGPT Settings; API keys in their issuing provider's settings. Logout always clears local tokens even when remote revocation is unconfirmed.

See [threat model](docs/THREAT_MODEL.md), [privacy](docs/PRIVACY.md) and [acceptance checks](docs/ACCEPTANCE.md). Release signing keys belong to the person distributing the APK. No production signing key is present.
