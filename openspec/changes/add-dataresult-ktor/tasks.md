# Tasks

## 1. Module scaffolding (follow `/new-module dataresult-ktor`)

- [ ] 1.1 Pick the Ktor 3.x floor with the `kotlin-multiplatform-libraries-expert` skill or klibs.io: the lowest version publishing android, jvm, iosArm64, iosSimulatorArm64, macosArm64, js and wasmJs. Add `ktor-client-core`, `ktor-serialization`, `ktor-client-mock` and `kotlinx-serialization-core` to `gradle/libs.versions.toml`, each with a comment giving the floor's reason. Verify that the chosen version's published targets include all seven.
- [ ] 1.2 Add `include(":dataresult-ktor")` to `settings.gradle.kts`. Create `dataresult-ktor/build.gradle.kts` (`kmp-library`, `kmp-published`, `macosArm64()` with the standard comment) with `api(project(":dataresult"))`, `api(ktor-client-core)`, and `implementation` of `ktor-serialization` and `kotlinx-serialization-core`, each commented. Add the test dependencies (`ktor-client-mock`, coroutines-test, Turbine). Add a `descriptions` entry in `kmp-published.gradle.kts`. Verify that `./gradlew :dataresult-ktor:build` succeeds through the runner agent with an empty source set.
- [ ] 1.3 Add `ktor-client-core`, `ktor-serialization` and `kotlinx-serialization-core` to the `consumer-floor` package rule in `.github/renovate.json5`. Verify that the JSON5 still parses, using `npx --yes json5 .github/renovate.json5` or the Renovate config validator.

## 2. Classification

- [ ] 2.1 Implement `Throwable.toDataError()` in `dataresult-ktor/src/commonMain/.../KtorOutcome.kt`, in design Decision 2's order, with the `expect` platform hook (an `actual` for `UnresolvedAddressException` in `jvmMain`/`androidMain`, `false` elsewhere). Verify with `KtorOutcomeTest` cases for each branch: timeouts, IOException, `NoTransformationFoundException`, `JsonConvertException`, `SerializationException`, an unknown exception, and `UnresolvedAddressException` in a `jvmTest` case.

## 3. Call and response forms

- [ ] 3.1 Implement `httpOutcome(onException, errorBody, call)`, with cancellation rethrown, `ResponseException` routed through the guarded `errorBody`, and `onException` called only for thrown exceptions that become errors. Verify with MockEngine tests for the spec's "Successful call", "Thrown response exception", "Cancellation is never an error" and "Classified exceptions are reported" scenarios, plus a test pinning that `expectSuccess = false` with `body<T>()` on a 404 reports `Serialization` (design Risk 2).
- [ ] 3.2 Implement `HttpResponse.toOutcome(onException, errorBody, mapSuccess)` and `httpOutcomeFlow`. Verify with MockEngine tests for: a 404 with `expectSuccess = false` giving `Http(404)` without calling `mapSuccess`; the XRPC envelope decoded to `Api(["bad cursor"], "InvalidRequest")`; a throwing hook giving `Http(500)`; and the flow emitting `Loading` and then one outcome per collection, with the request running twice for two collections.
- [ ] 3.3 Record what each engine throws for an offline or unresolvable host. Cover OkHttp and CIO on `jvmTest`, Darwin on `iosSimulatorArm64Test`, and Js on `jsNodeTest`, and record whether each maps to `Network`. Add mappings only for exceptions observed there. Verify that the findings are written into the `toDataError()` KDoc, and that any new mapping has a test.

## 4. API, docs and changelog

- [ ] 4.1 Run `./gradlew :dataresult-ktor:updateKotlinAbi` through the runner and commit `dataresult-ktor/api/`. Verify that the dump lists exactly the four public functions from design Decision 1.
- [ ] 4.2 Add a row to the README "Artifacts" table, and a "Ktor" section with REST (`toOutcome`, `expectSuccess = false`), XRPC (an `errorBody` decoding `{"error","message"}`), and Ktorfit (`httpOutcome { api.call() }` with `expectSuccess = true`) examples. Verify that every API name in the snippets matches the ABI dump.
- [ ] 4.3 Add a `dataresult-ktor` entry under `## Unreleased` in `CHANGELOG.md`, stating its Ktor floor. Verify that `/precommit` reports no missing CHANGELOG entry.

## 5. Integration

- [ ] 5.1 Run `/precommit dataresult-ktor`, then the full `/precommit`. Verify that `build` passes on every target, the test count matches the `@Test` count on each runner, and `buildHealth` shows no findings for `:dataresult-ktor` (in particular, the `implementation` choices in Decision 4 hold).
