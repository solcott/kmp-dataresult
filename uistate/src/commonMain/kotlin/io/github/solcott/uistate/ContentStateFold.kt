package io.github.solcott.uistate

import io.github.solcott.dataresult.OutcomeGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach

// Internal, not private, only so ContentStateStreams.kt holds just the public API: detekt caps the
// functions per file, and these helpers are not something a caller should reach for.

// The one fold behind every public function in ContentStateStreams.kt, so the completion rule
// cannot drift between the
// stream and holder forms or the single-source and group versions. It reads the holder on every
// emission rather than threading a copy through, which is what lets a caller's own write to a
// MutableStateFlow survive. See `scanContentState` for why settling is guarded on `cause == null`.
// uistate-compose's `foldFrom` implements the same rule against a MutableState: change both
// together.
internal suspend fun <S, E> foldInto(
  get: () -> S,
  set: suspend (S) -> Unit,
  source: Flow<E>,
  apply: (S, E) -> S,
  settle: (S) -> S,
) {
  source
    .onEach { set(apply(get(), it)) }
    .onCompletion { cause ->
      if (cause == null) {
        // Skipped when nothing was loading, so a source that ends after Data emits nothing extra.
        val current = get()
        val settled = settle(current)
        if (settled != current) set(settled)
      }
    }
    .collect()
}

internal suspend fun <P, S, E> foldLatestInto(
  params: Flow<P>,
  stream: (P) -> Flow<E>,
  get: () -> S,
  set: suspend (S) -> Unit,
  reload: (S) -> S,
  apply: (S, E) -> S,
  settle: (S) -> S,
) {
  // collectLatest cancels and joins the previous block before starting the next, so two blocks
  // never write the holder at once.
  params.distinctUntilChanged().collectLatest { parameters ->
    // A new parameter starts a fresh request: keep the current value visible but flag loading.
    set(reload(get()))
    foldInto(get, set, stream(parameters), apply, settle)
  }
}

// The stream forms run the fold against a local variable, emitting each state as it is set. A plain
// `flow` is enough here, because the fold emits from the collecting coroutine itself.
internal fun <S> scanInto(
  initial: S,
  fold: suspend (get: () -> S, set: suspend (S) -> Unit) -> Unit,
): Flow<S> = flow {
  var current = initial
  emit(current)
  fold({ current }) {
    current = it
    emit(it)
  }
}

// `channelFlow`, not `flow`: the fold emits from inside collectLatest's child coroutine, which a
// plain `flow` forbids. `transformLatest` and `flatMapLatest` would avoid it but are still
// `@ExperimentalCoroutinesApi`, and this is published API.
internal fun <S> scanLatestInto(
  initial: S,
  fold: suspend (get: () -> S, set: suspend (S) -> Unit) -> Unit,
): Flow<S> = channelFlow {
  var current = initial
  send(current)
  fold({ current }) {
    current = it
    send(it)
  }
}

// Every ContentStateGroup subclass overrides applyEmission, reloading and settled to return its own
// type -- ContentStates3 returns a ContentStates3 -- so the ContentStateGroup<O> they are declared
// to return is always the S they were called on. The hierarchy is sealed, so no subclass from
// outside the library can break that, and ContentStatesTest pins it for every arity.
@Suppress("UNCHECKED_CAST")
internal fun <S : ContentStateGroup<O>, O : OutcomeGroup> ContentStateGroup<O>.asSelf(): S =
  this as S
