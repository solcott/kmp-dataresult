# Changelog

## Unreleased

- Snapshot builds of `main` are published to GitHub Packages. See "Snapshots" in the README.

## 0.1.0

Initial release.

- `dataresult`: `Outcome`, `DataError`, `Origin`, and the accessors `isLoading`,
  `dataOrNull()`, `errorOrNull` and `mapData`.
- `uistate`: `ContentState`, `LoadStatus`, `applyEmission` and friends. For several sources:
  `isLoading`, `errorOrNull`, `hasLoaded` and `combinedStatus` over a list, and `combine` into a
  single `ContentState`, with a configurable `StatusPrecedence` for when one source has failed
  and another is still loading.
- `dataresult-apollo`: `Flow<ApolloResponse<D>>.mapToOutcome`, `ApolloException.toDataError`.
- `dataresult-store5`: `Flow<StoreReadResponse<T>>.asOutcomes`, `StoreReadResponse.toOutcomeOrNull`.
- `uistate-compose`: `produceContentState`, and its `Flow<P>` extension for sources whose parameters
  change while collected. Both collect a stream of `Outcome`s into `ContentState` held with androidx
  `retain`. Built on `collectFrom` / `collectLatestFrom` — the fold on its own, usable without a
  composition — with a `settled()` safety net guarded on `cause == null`; without it, a cancelled
  collection would report an abandoned request as finished.
- `uistate-circuit`: `produceRetainedContentState` and its `Flow<P>` extension — the same, with the
  state held in Circuit's registry.
