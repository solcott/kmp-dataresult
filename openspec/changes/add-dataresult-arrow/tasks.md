# Tasks

## 1. Module scaffolding (follow `/new-module dataresult-arrow`)

- [ ] 1.1 Pick the `arrow-core` 2.x floor with the `kotlin-multiplatform-libraries-expert` skill or klibs.io, and check which of the seven targets it publishes. Add it to `gradle/libs.versions.toml` with a comment giving the floor's reason. Verify that the chosen version's published targets are recorded in that comment, with any missing target noted.
- [ ] 1.2 Add `include(":dataresult-arrow")` to `settings.gradle.kts`. Create `dataresult-arrow/build.gradle.kts` (`kmp-library`, `kmp-published`, `macosArm64()` unless 1.1 found it missing, in which case leave a comment saying why) with `api(project(":dataresult"))` and `api(arrow-core)`, each commented, plus coroutines-test for `commonTest`. Add a `descriptions` entry in `kmp-published.gradle.kts`. Verify that `./gradlew :dataresult-arrow:build` succeeds through the runner agent.
- [ ] 1.3 Add `io.arrow-kt:arrow-core` to the `consumer-floor` package rule in `.github/renovate.json5`. Verify that the JSON5 still parses.

## 2. Conversions

- [ ] 2.1 Implement both `toOutcome` overloads and `toEitherOrNull` in `dataresult-arrow/src/commonMain/.../ArrowOutcome.kt`, with KDoc on the default `Origin` and on `Loading` becoming null. Verify with `ArrowOutcomeTest` for the spec's "Either of DataError to Outcome", "Either of any error to Outcome" (including that the mapping isn't called for `Right`) and "Outcome to Either" scenarios, including the round trip.
- [ ] 2.2 Implement the inline `outcome(origin) { }` builder over `Raise<DataError>`, with KDoc stating that exceptions propagate. Verify with tests for a raised error (code after the raise doesn't run), a thrown exception propagating unchanged, and a suspending block inside `runTest`.

## 3. API, docs and changelog

- [ ] 3.1 Run `./gradlew :dataresult-arrow:updateKotlinAbi` through the runner and commit `dataresult-arrow/api/`. Verify that the dump lists exactly the four functions from design Decision 1.
- [ ] 3.2 Add a README "Artifacts" row, any target exception to the Targets line, and a short "Arrow" section showing `toOutcome`, `toOutcome { mapError }`, `outcome { }`, and `Origin.Cache` for a database read. Verify that every API name in the snippets matches the ABI dump.
- [ ] 3.3 Add a `dataresult-arrow` entry under `## Unreleased` in `CHANGELOG.md`, stating its Arrow floor. Verify that `/precommit` reports no missing CHANGELOG entry.

## 4. Integration

- [ ] 4.1 Run `/precommit dataresult-arrow`, then the full `/precommit`. Verify that `build` passes on every target the module declares, the test count matches the `@Test` count, and `buildHealth` shows no findings for `:dataresult-arrow`.
