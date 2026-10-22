# Design

## Context

The fold lives in `uistate-compose/.../CollectContentState.kt`. Its private `foldFrom` and
`foldLatestFrom` work on a Compose `MutableState`. Each emission does `value = apply(value, it)`
against the holder's current value, and `onCompletion` settles the state only when `cause == null`.
`collectLatestFrom` adds `distinctUntilChanged().collectLatest { value = reload(value); foldFrom(…) }`.
The group versions reuse the same helpers through the `asSelf()` cast. That cast is safe because
`ContentStateGroup` is sealed, and every subclass returns its own type.

`uistate` depends only on `dataresult` and `runtime-annotation`. Its public API is exported to Swift
wholesale, and `checkSwiftExport` guards it. `dataresult` already exposes `Flow` in its public API
(`combineOutcomes`), so coroutines types are known to be safe for the consumer's Swift export build.

## Goals / Non-Goals

**Goals:**
- One implementation in `uistate` serves the stream forms and the `MutableStateFlow` forms, for both
  single sources and groups.
- ViewModel and Decompose hosts get state that survives a restart, without the caller juggling
  `initial` values.

**Non-Goals:**
- Host modules (`uistate-viewmodel`, `uistate-decompose`). With the forms here, each would be about
  one line around `stateIn` or `launch`. Revisit only if a host turns out to need its own retention
  workaround, the way Circuit does.
- Paging. `PagedState` keeps its own holder, `rememberRetainedPaging`. A paging fold that isn't
  Compose is a separate change.
- Rerouting `uistate-compose` through the new code (see Decision 4).

## Decisions

### 1. Two shapes: a stream operator and a state-flow collector

- **Stream operator**: `Flow<Outcome<T>>` → `Flow<ContentState<T>>`. It is cold and composes with
  other operators. It suits SwiftUI observation, Molecule, and a direct `collect`.
- **Collector**: `suspend fun MutableStateFlow<ContentState<T>>.collectFrom(source)`, and
  `collectLatestFrom(params, stream)`. It folds from the flow's *current* value.

The operator alone isn't enough. The natural ViewModel idiom is
`source.scanContentState(initial).stateIn(scope, WhileSubscribed(5_000), initial)`. With that idiom,
resubscribing restarts upstream, and the restarted fold begins again from `initial`, so the screen
returns to its placeholder. That's the same class of bug as keying `retain`. The collector reads
the holder each time, so a restart continues from the last value. The README steers ViewModel and
Decompose users to the collector, and says why.

Alternative considered: only the collector. Rejected, because SwiftUI and stream composition want
a cold `Flow<ContentState>`, not a holder.

### 2. Names mirror the primitive underneath

- `Flow<Outcome<T>>.scanContentState(initial)` ↔ `scan`, which is what it is.
- `Flow<P>.scanContentStateLatest(initial) { p -> source(p) }` ↔ the `…Latest` operators. Like the
  Compose `Flow<P>` producers, the receiver drives and the lambda returns the flow to collect.
- `MutableStateFlow<…>.collectFrom` / `collectLatestFrom` share their names with
  `uistate-compose`'s `MutableState` versions, so moving a screen between hosts changes only the
  holder type. The receivers differ, so there's no overload ambiguity, even with both imported.
- The group versions use the same names, with `@JvmName`, which `CollectContentState.kt` already
  does, because they erase to the same JVM signatures.

Rejected names: `toContentState`, which is already the group collapse; `contentStates()`, which
says nothing about the fold; and `foldContentState`, because `fold` in `Flow` is terminal.

### 3. One private fold in uistate, over a get/set pair

`uistate` gets one private `foldInto(get, set, …)` and one `foldLatestInto(…)`, the same shape as
the `MutableState` helpers. The `MutableStateFlow` collectors pass the flow's `value` accessors. The
stream operators run the same fold against a local variable inside `channelFlow`, emitting each
state as it is set.

- **Read-modify-write against the holder on every emission** is what satisfies the spec's "writes
  made outside the fold are not discarded" scenario. It is also how the Compose fold already works.
- **`channelFlow` plus `collectLatest`, not `transformLatest` or `flatMapLatest`.** Those two are
  still `@ExperimentalCoroutinesApi`, and this is published API with consumer floors. With a plain
  `flow { }`, emitting from inside `collectLatest`'s child coroutine would break the flow
  invariant. `collectLatest` cancels and joins the previous block before starting the next, so the
  local variable is never written by two blocks at once.
- **The settled emission is skipped when `settled()` returns the same state**, so a source that
  ends after `Data` emits nothing extra.

### 4. uistate-compose keeps its own fold, for now

Delegating `MutableState.collectFrom` to `scanContentState(value).collect { value = it }` would
fold from a copy captured at the start. That silently discards a consumer's own write to its
`MutableState` while the fold runs, which the current code honors. Sharing the get/set helper across
modules would need it to be public API. So the completion rule is now implemented twice, in
`uistate` and in `uistate-compose`. That's pinned by mirrored tests (the spec's "Same rules as the
Compose hosts" requirement) and by a note in the `compose-integrations` rule. If the duplication
ever matters, the follow-up is a public, documented `MutableState` adapter onto the `uistate`
helper.

### 5. Emission details

- The stream forms emit `initial` first, matching `scan`, so a StateFlow or a SwiftUI observer has a
  value at once.
- `scanContentStateLatest` emits `initial`, then on each distinct parameter emits `reloading()` of
  the current state before collecting.
- Parameters are deduplicated with `distinctUntilChanged()`. Debouncing stays the caller's job, as in
  the Compose version.

## Risks / Trade-offs

- **[Swift export of `MutableStateFlow` in signatures]** `Flow` is proven safe in the consumer's
  export, but `MutableStateFlow` is not. → Before merging, publish to mavenLocal and build the
  consumer's iOS target with Swift export. If it fails, keep the collectors out of `uistate` and
  move them to a small module that isn't exported. The spec allows that, because it requires only
  that the capability not depend on Compose.
- **[Two implementations of the completion rule]** → The mirrored test matrix runs the same
  scenarios against both folds, and the `compose-integrations` rule tells an editor to change both.
- **[Users pick the operator plus `stateIn` and hit the restart reset]** → The README's ViewModel and
  Decompose examples use the collector, and the operator's KDoc names the trap.

## Migration Plan

Additive only. There is no migration and no rollback concern beyond reverting the commit.
