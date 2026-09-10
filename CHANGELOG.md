# Changelog

## Unreleased

## 0.1.0

Initial release.

- `dataresult`: `Outcome`, `DataError`, `Origin`, and the accessors `isLoading`,
  `dataOrNull()`, `errorOrNull` and `mapData`.
- `uistate`: `ContentState`, `LoadStatus`, `applyEmission` and friends.
- `dataresult-apollo`: `Flow<ApolloResponse<D>>.mapToOutcome`, `ApolloException.toDataError`.
- `dataresult-store5`: `Flow<StoreReadResponse<T>>.asOutcomes`, `StoreReadResponse.toOutcomeOrNull`.
- `uistate-circuit`: `produceContentState`, and its `Flow<P>` extension for sources whose parameters
  change while collected. Both collect a stream of `Outcome`s into retained `ContentState` inside a
  Circuit presenter, with a `settled()` safety net guarded on `cause == null` — without it, a
  cancelled collection would report an abandoned request as finished.
