# Changelog

## Unreleased

## 0.1.0

Initial release. Extracted from the `Countries` project, where `dataresult` and `uistate` began as
local modules, and generalized so `Recipes` could share them.

- `dataresult`: `Outcome`, `DataError`, `Origin`, `Outcome.mapData`.
- `uistate`: `ContentState`, `LoadStatus`, `applyEmission` and friends.
- `dataresult-apollo`: `Flow<ApolloResponse<D>>.mapToOutcome`, `ApolloException.toDataError`.
- `dataresult-store5`: `Flow<StoreReadResponse<T>>.asOutcomes`, `StoreReadResponse.toOutcomeOrNull`.

Changed during extraction:

- `Outcome` gained a `Loading` case. It was previously absent by design, on the grounds that loading
  is the consumer's concern — but a source that reports its own request lifecycle (Store5 does) has
  real information to pass on, and dropping it costs a background-refresh indicator.
- `ContentState.hasLoaded` is new: it reads `origin`, which is null until the first value arrives,
  so an empty result is distinguishable from nothing-yet.
- `applyEmission` moved from Countries' `:presenter` into `uistate`, where it belongs now that both
  types live in one repo.
- The Apollo mapper takes an `onException` callback in place of a Kermit `Logger`.
