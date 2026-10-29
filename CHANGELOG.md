# Changelog

## Unreleased

## 0.1.0

Initial release.

- `dataresult`: `Outcome`, `DataError`, `Origin`, and the accessors `isLoading`,
  `dataOrNull()`, `errorOrNull` and `mapData`. For several sources, `combineOutcomes` combines two
  to five `Flow<Outcome<…>>`s into a flow of `Outcomes2`…`Outcomes5`, seeding every source with
  `Loading` so the fastest is not held back by the slowest. A group exposes each outcome by
  position or destructuring, and answers for all of them with `isAnyLoading`, `isAllLoading`,
  `hasAnyError`, `hasAllErrors`, `errors`, `errorOrNull` and `hasAllData`. Depends on
  kotlinx-coroutines-core.
- `uistate`: `ContentState`, `LoadStatus`, `applyEmission` and friends. `hasAnswer(isEmpty)` is
  `hasLoaded` that doesn't count an empty cached value while its request is in flight or has
  failed: that's a cache miss, not a result. For several sources:
  `isLoading`, `errorOrNull`, `hasLoaded` and `combinedStatus` over a list, and `combine` into a
  single `ContentState`, with a configurable `StatusPrecedence` for when one source has failed
  and another is still loading. `ContentStates2`…`ContentStates5` hold one `ContentState` per
  source of a group, so each source keeps its own last value, with the same aggregates plus
  `hasLoaded` and `combinedStatus`. `toContentState` collapses a group into one state with
  `combine`'s rules, and `contentStatesOf` builds the initial group.
- `uistate`, without Compose: `Flow<Outcome<T>>.scanContentState(initial)` folds a source into a
  `Flow<ContentState<T>>`, and `Flow<P>.scanContentStateLatest(initial) { p -> … }` restarts it on
  each distinct parameter, keeping the data under a reload. `MutableStateFlow.collectFrom` /
  `collectLatestFrom` fold into a state flow the caller holds, starting from its current value, so
  a ViewModel or Decompose component never drops back to its placeholder on a restart. All four
  accept a group of sources too. They settle a still-loading state when the source completes and
  never on cancellation, the same rules as `uistate-compose`.
- `dataresult-apollo`: `Flow<ApolloResponse<D>>.mapToOutcome`, `ApolloException.toDataError`.
- `dataresult-ktor`: `httpOutcome { call }`, `httpOutcomeFlow { call }` and
  `HttpResponse.toOutcome { mapSuccess }`, plus `Throwable.toDataError`. `toOutcome` reads the
  status itself, so it is right whatever the client's `expectSuccess`. `httpOutcome` needs
  `expectSuccess = true`, which suits Ktorfit. An `errorBody` hook decodes an error envelope into
  `DataError.Api` and falls back to `Http(status)` when it returns null or throws. Cancellation is
  rethrown, and every outcome is `Origin.Network`. DNS and connection failures are `Network` on
  OkHttp, CIO, Darwin and Js. Requires Ktor 3.0.0 or later, and
  kotlinx-serialization-core 1.7.3.
- `dataresult-store5`: `Flow<StoreReadResponse<T>>.asOutcomes` and `StoreReadResponse.toOutcomeOrNull`.
  Given `fetching` and `isEmpty`, `asOutcomes` holds back an empty first read from cache until the
  fetch it prompted answers, so a cache miss never reaches a consumer as an empty result.
- `uistate-compose`: `produceContentState`, and its `Flow<P>` extension for sources whose parameters
  change while collected. Both collect a stream of `Outcome`s into `ContentState` held with androidx
  `retain`. Built on `collectFrom` / `collectLatestFrom` — the fold on its own, usable without a
  composition — with a `settled()` safety net guarded on `cause == null`; without it, a cancelled
  collection would report an abandoned request as finished. `produceContentStates` and its
  `Flow<P>` extension do the same for a group of sources, and `collectFrom` / `collectLatestFrom`
  accept a group too.
- `uistate-circuit`: `produceRetainedContentState` and its `Flow<P>` extension — the same, with the
  state held in Circuit's registry — and `produceRetainedContentStates` for a group of sources.
- Paging. In `uistate`: `Page<T, M>` (items, a header, and a `String?` cursor to the next page),
  `pageOf`, and `PagedState<T, M>`, which folds `Outcome<Page<T, M>>` with `ContentState`'s rules.
  It keeps items de-duplicated by id, the first page's `meta`, `origin`, separate `refresh` and
  `append` statuses, and `endReached`. A failed refresh keeps the items, and a failed page keeps
  the pages before it. A page its source emits again (cache then network, or a watcher) replaces
  only itself. `hasAnswer` doesn't count an empty cached first page while it's loading. In
  `uistate-circuit`: `rememberRetainedPaging`, which returns a retained `Paging` holder with
  `loadMore()`, `retry()` (which repeats only what failed), `refresh()` and `update {}`. It
  restarts on keys, keeping or clearing the items as `OnKeyChange` says. It shows its pages at once
  when the screen is reached again, and can start from a page already in hand (`initial`).
- `Outcome`, `DataError`, `OutcomeGroup`, `ContentState`, `LoadStatus` and `ContentStateGroup` are
  `@Immutable`, so Compose treats them as stable and skips on `equals`. `dataresult` and `uistate`
  therefore depend on `androidx.compose.runtime:runtime-annotation` 1.9.0. It has no Compose
  runtime or Compose types, but androidx group alignment makes a Compose runtime in the same app
  resolve to at least 1.9.0.
- Published to Maven Central, which needs no credentials to read. Snapshot builds of `main` go to
  the Central snapshot repository. See "Installing" and "Snapshots" in the README.
