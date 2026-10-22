# Proposal

## Why

The library folds one `Outcome<T>` stream into one `ContentState<T>`, but has nothing for a list
loaded a page at a time. activity-tracker pages five lists with its own `CursorPaging` and a
hand-rolled comments pager, and both break the rules `ContentState` keeps everywhere else: a failed
first page empties the list, a failed later page is retried from page 1, and a list reached again
reloads from scratch. Its `adopt-library-paging` change (step 5 of moving the app onto this
library) needs paging here first, so the app has one load pattern.

## What Changes

- **`uistate`: a paged state and its fold.** `Page<T, M>` is what a data source emits for one
  page: its items, an optional header (`meta`) and the cursor of the next page. `PagedState<T, M>`
  folds `Outcome<Page<T, M>>` the way `ContentState` folds `Outcome<T>`. It holds the items so far
  (de-duplicated by id), the first page's `meta`, `origin`, separate `refresh` and `append`
  `LoadStatus`es, and `endReached`. The fold is pure and has no Compose types, so it exports to
  Swift and any host can drive it.
- **`uistate-circuit`: a retained `Paging` holder.** `rememberRetainedPaging` returns a `Paging<T, M>`
  held in Circuit's retained registry. It has `loadMore()`, `retry()` (which retries only what
  failed: the refresh, or the next page), `refresh()` and `update {}` for optimistic edits. It
  restarts on keys, and the caller chooses whether a new key keeps or clears the items.
- **The rules it keeps:**
  - A failed refresh keeps the items.
  - A failed page keeps the earlier pages and retries only itself.
  - A list reached again shows its pages at once, without reloading.
  - A cached empty first page is not an answer.
- **Decisions on the four questions the app's proposal left open** (cursor type, a cached first
  page, nested reply paging, keep or clear on a new key) are recorded in `design.md`.
- README, CHANGELOG and ABI dumps for the new API. No existing API changes.

Out of scope: the app-side work (repository page reads, moving its screens, removing
`CursorPaging`). activity-tracker's `adopt-library-paging` covers it.

## Capabilities

### New Capabilities

- `uistate/paging`: folding the pages of a cursor-paged source into one paged view state, and the
  retained holder that requests pages, retries what failed, refreshes and restarts on a new key.

### Modified Capabilities

None. The repository has no baseline specs, and `ContentState`'s behavior does not change.

## Impact

- **`uistate`:** new public API (`Page`, `PagedState`, its fold and helpers, the key-change
  choice). It must pass `checkSwiftExport`: sealed classes or enums only, no Compose types, `List`
  in the public API.
- **`uistate-circuit`:** new public API (`Paging`, `rememberRetainedPaging`). No new dependencies:
  it already depends on `uistate`, `circuit-retained`, Compose runtime and coroutines.
- **`api/` dumps** for both modules, updated with `updateKotlinAbi`.
- **Tests:** the fold in `uistate`'s `commonTest`, and the holder in `uistate-circuit`'s
  `nonWebTest` with `presenterTestOf`.
- **Docs:** a README section and design notes, and an `## Unreleased` CHANGELOG entry.
- **Downstream:** activity-tracker's `adopt-library-paging` consumes this through a snapshot or a
  release.
