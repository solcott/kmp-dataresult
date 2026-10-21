---
paths:
  - "uistate-compose/src/**"
  - "uistate-circuit/src/**"
---

# Compose and Circuit integrations

The fold lives in `uistate-compose`'s `CollectContentState.kt` (`collectFrom`, `collectLatestFrom`).
It is plain `suspend` code with no composition, so its tests run on every target. Two functions call
it:
- `produceContentState`, which uses androidx `retain` and a keyed `LaunchedEffect`.
- `uistate-circuit`'s `produceRetainedContentState`, which uses Circuit's `produceRetainedState`.
  That function's `ProduceStateScope` *is* a `MutableState`.

## Invariants that look like tidy-ups but are bugs

Tests pin most of these.

- `retain { }` stays unkeyed, and only the effect is keyed. Keying `retain` discards the held
  value, so a retry would blank the screen.
- Call `settled()` in `onCompletion` only when `cause == null`. Cancellation completes a flow too,
  and without the check an abandoned request would be reported as finished.
- Keep `rememberUpdatedState(stream)`, because the effect restarts on its keys alone.
- `rememberRetainedPaging`: its `rememberRetained` stays unkeyed (keying it drops what `KeepItems`
  keeps), and only the `LaunchedEffect` is keyed. A request leaves `pending` only on
  `cause == null`. `pending` is what a return to the screen requests again, so clearing it on
  cancellation would leave an interrupted page loading forever. The stale-result check lives in
  `PagedState`'s fold (`applyNextPage` ignores a cursor it didn't ask for), not in the holder.
- The receiverless `produceContentState` is top-level, and the variant that takes parameters is a
  `Flow<P>` extension. Two extensions would be ambiguous, because `Flow<Outcome<T>>` is a valid
  `Flow<P>`.
