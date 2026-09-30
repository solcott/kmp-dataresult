---
paths:
  - "**/*.gradle.kts"
  - "gradle/libs.versions.toml"
  - "gradle.properties"
  - "build-logic/**"
---

# Gradle build

- **Targets:** `build-logic/src/main/kotlin/kmp-library.gradle.kts` supplies android, jvm, iosArm64,
  iosSimulatorArm64, js and wasmJs. Each module declares `macosArm64()` itself, except
  `dataresult-store5`, because Store5 publishes no macOS artifact. There is no iosX64.
- **AGP 9:** the Android target comes from `com.android.kotlin.multiplatform.library`, with
  `android {}` nested inside `kotlin {}`. Apply the Kotlin and Compose-compiler plugins **by id with no
  version**, because the root `build.gradle.kts` buildscript classpath decides the KGP. Adding a
  version reintroduces AGP's older bundled one.
- Every `api` vs `implementation` line in a module build file carries a comment saying why. `api` is
  for anything that appears in a public signature.
- `kmp-published.gradle.kts` maps each project name to a POM description, and a missing entry fails
  the build.
- **Catalog versions for adapters, Compose, Circuit and coroutines are *floors* for consumers, not
  pins.** Renovate opens PRs for them anyway, labeled `consumer-floor`. Merge one only with a
  reason, and give it a CHANGELOG entry, because it raises every consumer's minimum.
- The toolchain compiles on JDK 25, and the published bytecode targets 17 (`jvm-toolchain` and
  `jvm-compat` in the catalog). After changing `jvm-toolchain`, run `./gradlew updateDaemonJvm`.
  Renovate doesn't track `jvm-toolchain`, so bump it by hand.
- `build-logic/` is an included build, and a task selector doesn't reach into one. The root
  `build.gradle.kts` wires `ktfmtFormat`, `ktfmtCheck`, `sortDependencies`, `checkSortDependencies`
  and `check` to build-logic's own tasks. A check added to build-logic needs wiring there too.
  `./gradlew -p build-logic <task>` runs one of its tasks standalone.
- **`buildHealth` (DAGP)** fails on any finding. It analyzes only the JVM and Android variants of a KMP
  module, and skips Apple, JS and Wasm. Its `fixDependencies` is unreliable on KMP, so fix findings by
  hand from `build/reports/dependency-analysis/build-health-report.txt`.
