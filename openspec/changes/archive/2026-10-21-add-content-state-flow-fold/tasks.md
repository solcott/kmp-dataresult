# Tasks

## 1. Dependencies

- [x] 1.1 In `uistate/build.gradle.kts`, add `api(libs.kotlinx.coroutines.core)` to `commonMain` with a comment explaining `api` (`Flow` and `MutableStateFlow` appear in signatures), and add `kotlinx-coroutines-test` and Turbine to `commonTest`. Verify with `./gradlew buildHealth` through the runner agent: no findings for `:uistate`.

## 2. Single-source fold

- [x] 2.1 Add the private `foldInto` and `foldLatestInto` helpers (get/set, `onCompletion` guarded on `cause == null`) and `scanContentState(initial)` in a new `uistate/src/commonMain/.../ContentStateStreams.kt`. Its KDoc explains the completion rule, and warns that `stateIn(WhileSubscribed)` with a cold fold restarts from `initial`. Verify with new tests in `uistate/src/commonTest/.../ContentStateStreamsTest.kt` covering the spec's "Stream of states" and "Settle on normal completion only" scenarios, including cancellation with no settle and no extra emission after `Data`.
- [x] 2.2 Add `Flow<P>.scanContentStateLatest(initial, stream)` on `channelFlow` plus `distinctUntilChanged().collectLatest`. Verify with tests for the "Restart on a new parameter" scenarios: a reloading state that keeps its data, the previous stream cancelled, a duplicate parameter starting nothing, and a cancelled stream not settling.
- [x] 2.3 Add `MutableStateFlow<ContentState<T>>.collectFrom` / `collectLatestFrom` on the same helpers. Verify with tests for the "Fold into a caller-held state flow" scenarios: a restart keeps the last value, and a write made outside the fold during collection is folded into rather than overwritten.

## 3. Group fold

- [x] 3.1 Add the group overloads of all three forms for `ContentStateGroup` (`@JvmName` where they erase alike, the same `asSelf()` cast and justification comment as `CollectContentState.kt`). Verify with tests in `ContentStateStreamsTest` for the "Groups of sources" scenarios, at arities 2 and 5.

## 4. Cross-host consistency

- [x] 4.1 Add a test to `uistate-compose/src/commonTest/.../CollectContentStateTest.kt` that feeds identical outcome sequences (data, loading, error, completion, cancellation) to `MutableState.collectFrom` and `scanContentState`, and asserts the same distinct-state sequence. Verify that it passes on `jvmTest` and on the js/wasm Node runners.
- [x] 4.2 In `.claude/rules/compose-integrations.md`, add a note that the completion rule is now implemented in both `CollectContentState.kt` and `uistate`'s `ContentStateStreams.kt`, so both must change together. Verify by re-reading the rule against design Decision 4.

## 5. API, docs and changelog

- [x] 5.1 Run `./gradlew :uistate:updateKotlinAbi` through the runner and review the `uistate/api/` diff: only the new functions are added, and nothing else changes.
- [x] 5.2 Add a "Without Compose" section to the README with three examples: a ViewModel holding a `MutableStateFlow` with `collectFrom` and a retry through `collectLatestFrom`, a Decompose component using `retainedInstance` with its own scope, and SwiftUI observing `scanContentState`. Say why the ViewModel and Decompose examples use the collector rather than `stateIn`. Verify that every API name in the snippets matches the ABI dump.
- [x] 5.3 Add a bullet to `uistate`'s entry under `## Unreleased` in `CHANGELOG.md` listing the new functions. Verify that `/precommit` no longer reports a missing CHANGELOG entry.

## 6. Integration

- [x] 6.1 Run `/precommit`. Verify that `build` passes on every target, `uistate`'s test count matches its `@Test` count, `checkSwiftExport` and `buildHealth` are clean.
- [x] 6.2 Publish with `publishToMavenLocal` and build a Swift-exporting consumer's iOS target against the snapshot (design Risk 1). Verify that the iOS build succeeds with the `MutableStateFlow` collectors exported. If it fails, stop and revisit the design before merging.
