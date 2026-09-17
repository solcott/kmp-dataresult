# Changelog

## Unreleased

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
- `dataresult-apollo`: `Flow<ApolloResponse<D>>.mapToOutcome`, `ApolloException.toDataError`.
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
- `Outcome`, `DataError`, `OutcomeGroup`, `ContentState`, `LoadStatus` and `ContentStateGroup` are
  `@Immutable`, so Compose treats them as stable and skips on `equals`. `dataresult` and `uistate`
  therefore depend on `androidx.compose.runtime:runtime-annotation` 1.9.0. It has no Compose
  runtime or Compose types, but androidx group alignment makes a Compose runtime in the same app
  resolve to at least 1.9.0.
- Snapshot builds of `main` are published to GitHub Packages. See "Snapshots" in the README.
