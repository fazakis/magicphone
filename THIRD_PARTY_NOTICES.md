# Third-party notices

Our original source is Copyright 2026 Nikos Fazakis, available under MIT OR Apache-2.0 (your choice; see LICENSE and LICENSE-APACHE). Dependencies retain their own licenses. Reference-project availability is not treated as a license grant; no reference source is included.

| Component | Pinned version | License | Purpose |
|---|---|---|---|
| Kotlin compiler/stdlib/Compose compiler | 2.3.10 | Apache-2.0 | Language/runtime |
| kotlinx.coroutines | 1.10.2 | Apache-2.0 | Structured concurrency |
| kotlinx.serialization | 1.9.0 | Apache-2.0 | Strict typed JSON |
| Android Gradle Plugin | 9.2.1 | Apache-2.0 | Build tooling |
| Gradle wrapper/distribution | 9.4.1 | Apache-2.0 | Reproducible build entry |
| Jetpack Compose BOM | 2026.09.00 | Apache-2.0 | UI dependency alignment |
| Jetpack Activity | 1.12.4 | Apache-2.0 | Activity/Compose integration |
| Jetpack Lifecycle | 2.10.0 | Apache-2.0 | Lifecycle integration |
| OkHttp | 4.12.0 | Apache-2.0 | TLS HTTP/SSE |
| Okio (transitive) | resolved in dependency inventory | Apache-2.0 | Bounded I/O |
| AndroidX Test runner / JUnit adapter | 1.7.0 / 1.3.0 | Apache-2.0 | Instrumentation only |
| AndroidX UI Automator | 2.3.0 | Apache-2.0 | Test harness only |
| JUnit 4 (transitive test dependency) | resolved in dependency inventory | EPL-1.0 | JVM tests only |
| Hamcrest (transitive test dependency) | resolved in dependency inventory | BSD-3-Clause | Assertions, tests only |

Platform cryptography is Android's/JDK's maintained JCA AES-GCM and PBKDF2-HMAC-SHA256 implementation; no custom cryptographic primitive or embedded crypto binary is shipped. Android SDK and JDK are separate development requirements and are not redistributed here. Gradle wrapper source is from the Gradle project and is not original MagicPhone code.

AndroidX and Google library notices: https://developer.android.com/license
Kotlin licenses: https://github.com/JetBrains/kotlin/blob/master/license/README.md
OkHttp license: https://github.com/square/okhttp/blob/parent-4.12.0/LICENSE.txt
Okio license: https://github.com/square/okio/blob/master/LICENSE.txt
Gradle license: https://github.com/gradle/gradle/blob/v9.4.1/LICENSE
JUnit license: https://junit.org/junit4/license.html
Hamcrest license: https://github.com/hamcrest/JavaHamcrest/blob/master/LICENSE.txt

The checked-in [dependency inventory](docs/dependency-inventory.json) and verification metadata record 494 build/runtime/test components, including inherited licenses from cached parent POMs. Three parent-POM-only metadata components (jvnet-parent 3 and oss-parent 7/9) do not declare a cached license; no binary from those components is shipped in the APK. All binary-bearing inventory entries have upstream POM license information. The inventory identifies each license source POM; build/test dependencies are not all packaged in the runtime. Before redistributing a release, review newly introduced dependencies and preserve their notices. There are no advertising, analytics, telemetry, browser automation, root, shell execution, or ADB runtime dependencies in the app.
