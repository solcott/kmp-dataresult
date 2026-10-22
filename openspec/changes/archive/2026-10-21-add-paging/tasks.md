# Tasks

## 1. Paged state types in `uistate`

- [x] 1.1 Add `Page<T, M>` and `pageOf(items, next)` (`Page<T, Unit>`) in a new `uistate/src/commonMain/.../Paging.kt`, `@Immutable`, `next: String?`. Verify by compiling `:uistate:jvmMainClasses` through the gradle runner.
- [x] 1.2 Add `PagedState<T, M>` as an `@Immutable` class with an internal constructor over page slots (requested cursor, next cursor, items, completed). Expose `items`, `meta`, `origin`, `refresh`, `append` and `endReached`, with `equals`/`hashCode`/`toString`. Add `OnKeyChange { KeepItems, ClearItems }`. Verify with `PagedStateTest` cases for "Nothing loaded yet" and equality.
- [x] 1.3 Add `hasLoaded`, `hasAnswer` and `canLoadMore`, plus `pagedStateOf()` and `pagedStateOf(initial, idOf)`. Verify with tests for the "An empty list is an answer", "An empty cached first page is not an answer" and "A holder can start from a page it already has" scenarios (state level).

## 2. The fold in `uistate`

- [x] 2.1 Implement applying a first-page outcome (`Loading`, `Data`, `Error`) and a next-page outcome tagged with its requested cursor, with de-duplication by `idOf` (first occurrence wins) and header-from-first-page. Verify with `PagedStateTest` covering "Pages fold into one paged state", "The header comes from the first page", "Items are de-duplicated by id" and "Requesting the next page".
- [x] 2.2 Implement the failure rules: a failed next page keeps the earlier pages and sets `append` to `Failed`, and a failed first page keeps items, header and origin and sets `refresh` to `Failed`. Verify with tests for "A failed page keeps the earlier pages" and "A failed refresh keeps the items".
- [x] 2.3 Implement `refreshing()`, `appending()`, settling a slot's status when its source completes without emitting, and the stale-result guard: drop a next page whose requested cursor is no longer the last slot's next cursor. Verify with tests for "Refresh reloads from the first page", "Every emission settles its status" and a stale-append case.
- [x] 2.4 Implement re-emission of a page into its slot: keep later pages when the next cursor is unchanged, drop them and recompute `endReached` when it changed. Verify with tests for both "A page re-emitted by its source replaces that page" scenarios.
- [x] 2.5 Implement `restarted(OnKeyChange)` (keep: hold items, `refresh` loading, `append` idle; clear: drop everything) and `updated(transform, idOf)` with slot reassignment by id. Verify with tests for the keep and clear key-change scenarios at state level, and "Optimistic edits to the items".
- [x] 2.6 Write KDoc giving the *why* and the traps (slot rule, stale guard, `hasAnswer` backstop), matching `ContentState.kt`. Verify that `./gradlew :uistate:allTests` and `:uistate:check` pass, including `checkSwiftExport`, through the gradle runner.

## 3. The retained holder in `uistate-circuit`

- [x] 3.1 Add the internal `PagingHolder` (snapshot-backed `PagedState`, last keys, conflated command channel) and the public `@Stable Paging<T, M>` with `loadMore()`, `retry()`, `refresh()` and `update {}`, delegating to the fold. Verify with `PagingTest` (nonWebTest, `presenterTestOf`) for "Load more is ignored when it cannot proceed" and "Retry repeats only what failed".
- [x] 3.2 Add `rememberRetainedPaging(vararg keys, idOf, onKeyChange, enabled, initial, load)`: an unkeyed `rememberRetained`, `rememberUpdatedState` for `load` and `idOf`, and a `LaunchedEffect(holder, *keys, enabled)` driver. The driver runs at most one first-page and one next-page request, cancels both on refresh or a key change, and settles in `onCompletion` only when `cause == null`. Verify with presenter tests for the keep and clear key-change scenarios, "Old page arrives after key change", and "Refresh cancels a page in flight".
- [x] 3.3 Implement resume on return: same keys means re-request only interrupted requests and incomplete slots, and different keys means restart. Verify with presenter tests for both "A list reached again shows its pages at once" scenarios, using a retained registry that survives leaving composition.
- [x] 3.4 Implement `enabled` and `initial`. Verify with presenter tests for "A disabled holder loads nothing" and "A holder can start from a page it already has" (no request until `loadMore()`).
- [x] 3.5 Write KDoc on `rememberRetainedPaging` covering keys and `onKeyChange`, a never-completing loader, and the paused-record hazard, copying `produceRetainedContentState`'s warning. Add the new invariants to `.claude/rules/compose-integrations.md`. Verify that `./gradlew :uistate-circuit:allTests` passes (jvm, Android host and Apple run the tests; js and wasm run 0 by design).

## 4. Public API and docs

- [x] 4.1 Run `./gradlew updateKotlinAbi` and review the `uistate/api/` and `uistate-circuit/api/` diffs, which should contain only additions, all of them intended. Verify that `checkKotlinAbi` passes.
- [x] 4.2 Add a "Paging" section to `README.md` under "Using it" (a presenter with `rememberRetainedPaging`, rendering `refresh` and `append`, and a seeded per-thread holder). Add design notes for decisions 3, 4, 5 and 8 under "Design notes". Verify that the README snippets match the final signatures.
- [x] 4.3 Add an `## Unreleased` entry to `CHANGELOG.md` for the new paging API in `uistate` and `uistate-circuit`. Verify that `/precommit` reports no missing CHANGELOG entry.

## 5. Integration check

- [x] 5.1 Run `/precommit` (`ktfmtFormat`, `sortDependencies`, `build` across every target with `checkSwiftExport`, detekt and the ABI check) and `./gradlew buildHealth`. Verify that both pass.
- [x] 5.2 Run `./gradlew publishToMavenLocal` and confirm the `uistate` and `uistate-circuit` snapshot artifacts contain the new API. This is a hand-off check for activity-tracker's `adopt-library-paging`, not app work.

## Workflow follow-up

- activity-tracker's `adopt-library-paging` writes its own specs and design against this API once it
  settles.
- Archive this change after implementation and review.
