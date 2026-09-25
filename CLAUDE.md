# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

A Kotlin Multiplatform library: `Outcome` (what a data source emits) and `ContentState` (what a
screen renders), plus adapters for Apollo, Store5, Compose and Circuit. The README's "Design notes"
explain the non-obvious API decisions; read them before changing public API.

## Commands

Run Gradle through the **`dataresult-gradle-runner`** agent, or `/precommit` before committing,
rather than inline Bash. Every task fans out across 7 targets, and the full log is noise that should
never reach this context.

```bash
./gradlew ktfmtFormat sortDependencies   # fix formatting + dependency order; do this before committing
./gradlew build                          # every target, tests, and `check` = ktfmtCheck + checkSortDependencies + detekt + checkKotlinAbi
./gradlew updateKotlinAbi                # after an *intended* public API change; commit the <module>/api/ diff with it
./gradlew buildHealth                    # dependency analysis: fails on any finding; report in build/reports/dependency-analysis/
./gradlew :uistate:jvmTest --tests 'io.github.solcott.uistate.ContentStateTest'   # one test class, fastest runner
./gradlew :uistate:allTests              # one module, every target
./gradlew :uistate:testAndroidHostTest   # Android host tests
./gradlew publishToMavenLocal            # try a change in a consumer (-SNAPSHOT version + mavenLocal())
```

- CI (`.github/workflows/build.yml`) runs `ktfmtCheck`, `checkSortDependencies`, `buildHealth`, then
  `build` on macOS. Apple targets only compile and link on a Mac. On a push to `main`, its
  `publish-snapshot` job then publishes to GitHub Packages, but only while `version` ends in
  `-SNAPSHOT`. A release version published there is immutable and would break `release.yml`. The
  job's Maven Central step is skipped unless the repository variable `PUBLISH_TO_CENTRAL` is `true`.
- DAGP analyzes only the JVM and Android variants of a KMP module; Apple, JS and Wasm are skipped.
  Its `fixDependencies` is unreliable on KMP, so fix `buildHealth` findings by hand.
- The toolchain compiles on JDK 25, and the published bytecode targets 17 (`jvm-toolchain`/`jvm-compat` in
  `gradle/libs.versions.toml`). After changing `jvm-toolchain`, run `./gradlew updateDaemonJvm`.
- Browser test tasks (`jsBrowserTest`, `wasmJsBrowserTest`) need Chrome; the Node ones don't.
- `build-logic/` is an included build, and a task selector doesn't reach into one. So the root
  `build.gradle.kts` wires `ktfmtFormat`, `ktfmtCheck`, `sortDependencies`, `checkSortDependencies`
  and `check` to build-logic's own tasks, and the commands above (and CI) cover it too. A check
  added to build-logic needs wiring there as well. `./gradlew -p build-logic <task>` runs one
  standalone.

## Architecture

```
source ─► adapter ─► Flow<Outcome<T>> ─► ContentState.applyEmission ─► ContentState<T> ─► UI
          (asOutcomes / mapToOutcome)     (fold, one emission at a time)
```

Modules: `dataresult` ← `uistate` ← `uistate-compose` ← `uistate-circuit`. The adapters
`dataresult-apollo` and `dataresult-store5` depend only on `dataresult`. Each module is one or two files
under `src/commonMain`.

- **`ContentState` semantics:** `data` is replaced only by `Outcome.Data`. `Loading` and `Error` move
  `status` and keep the last value (stale-while-revalidate). `hasLoaded` is `origin != null`, which is
  deliberate: an empty list is a real answer. `status` settles on every emission, so a source never has to
  complete.
- **A cache miss is not `Data`.** An empty cached read while a fetch is pending is a miss, and sources
  hold it back. The Apollo adapter drops `CacheMissException`. `asOutcomes(fetching, isEmpty)` holds an
  empty *first* read until the fetch settles: `NoNewData` or completion releases it, and a fetcher error
  discards it. `ContentState.hasAnswer(isEmpty)` is the consumer-side backstop. It is only ever false
  while `Loading` or `Failed`, so it can't hang a spinner.
- **The fold lives in `uistate-compose`'s `CollectContentState.kt`** (`collectFrom`,
  `collectLatestFrom`). It is plain `suspend` code with no composition, so its tests run on every target. Both
  `produceContentState` (androidx `retain` + a keyed `LaunchedEffect`) and `uistate-circuit`'s
  `produceRetainedContentState` (Circuit's `produceRetainedState`, whose `ProduceStateScope` *is* a
  `MutableState`) call it.
- **Invariants that look like tidy-ups but are bugs**, most of them pinned by tests:
  - `retain { }` stays unkeyed, and only the effect is keyed. Keying `retain` discards the held value,
    so a retry would blank the screen.
  - `settled()` in `onCompletion` only when `cause == null`. Cancellation completes a flow too.
  - `rememberUpdatedState(stream)`, because the effect restarts on keys alone.
  - The receiverless `produceContentState` is top-level and the params variant is a `Flow<P>` extension.
    Two extensions would be ambiguous (`Flow<Outcome<T>>` is a valid `Flow<P>`).
- Names mirror the primitive underneath: `produceContentState` ↔ `produceState`,
  `produceRetainedContentState` ↔ `produceRetainedState`. `mapData`, not `map`, because these live in
  `Flow`s.

## Constraints that fail silently

- **Swift export:** consumers export `dataresult` and `uistate` to Swift wholesale. Use `sealed class`,
  never `sealed interface`, and keep every Compose type out of those two modules. A Compose type in
  the reachable API breaks the iOS build with no warning. The same goes for kotlinx-collections-immutable:
  public collections stay `List`, and `@Immutable` on the class already makes Compose treat it as stable.
- **Targets:** `build-logic/.../kmp-library.gradle.kts` supplies android, jvm, iosArm64,
  iosSimulatorArm64, js and wasmJs. Each module declares `macosArm64()` itself, except
  `dataresult-store5` (Store5 publishes no macOS artifact). There is no iosX64.
- **Compose-clock tests go in `nonWebTest`**, not `commonTest`. Molecule's frame clock is browser-only,
  so under Node recomposition never advances and the test hangs until it times out. The `nonWeb` source
  set group is defined in `kmp-library`.
- **AGP 9:** the Android target comes from `com.android.kotlin.multiplatform.library`, with `android {}`
  nested inside `kotlin {}`. Kotlin and Compose-compiler plugins are applied **by id with no version**, because the
  root `build.gradle.kts` buildscript classpath decides the KGP. Adding a version reintroduces AGP's
  older bundled one.
- `kmp-published.gradle.kts` maps each project name to a POM description, and a missing entry fails the
  build.
- Adapter, Compose, Circuit and coroutines versions in the catalog are *floors* for consumers, not pins.
  Renovate opens PRs for them anyway, labeled `consumer-floor`. Merge one only with a reason, and give
  it a CHANGELOG entry, because it raises every consumer's minimum.

## Conventions

- Comments and KDoc explain *why* and record traps. Every `api` vs `implementation` line in a module
  build file carries a comment saying why; keep that pattern.
- Tests use `kotlin.test`, `runTest`, Turbine, Molecule (`moleculeFlow(RecompositionMode.Immediate)`),
  and Circuit's `presenterTestOf`.
- Commits: an imperative subject line, then a body wrapped at ~72 characters giving the reasoning and any trap
  found along the way.
- User-visible changes go under `## Unreleased` in `CHANGELOG.md`. The release process is in `RELEASING.md`
  (GitHub Packages; Maven Central is wired but gated behind `-PpublishToCentral`).
- Adding a module: use `/new-module`.
- Dependency updates come from the hosted Renovate app, configured in `.github/renovate.json5`. It
  opens a PR as soon as a version is published, with no rate limits and scans about every 4 hours.
  `jvm-toolchain` and setup-java's `java-version` aren't tracked; bump those by hand.

## Keeping context small

- The codebase is ~2k lines, so read the specific file directly instead of spawning an Explore agent.
- Don't read `detekt/detekt.yml` (929 lines, almost all defaults), `build/`, `.gradle/`, `.kotlin/` or
  `kotlin-js-store/`.
