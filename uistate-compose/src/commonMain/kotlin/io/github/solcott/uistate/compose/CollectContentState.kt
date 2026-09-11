package io.github.solcott.uistate.compose

import androidx.compose.runtime.MutableState
import io.github.solcott.dataresult.Outcome
import io.github.solcott.dataresult.OutcomeGroup
import io.github.solcott.uistate.ContentState
import io.github.solcott.uistate.ContentStateGroup
import io.github.solcott.uistate.applyEmission
import io.github.solcott.uistate.reloading
import io.github.solcott.uistate.settled
import kotlin.jvm.JvmName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach

/**
 * Folds every emission of [source] into this state, suspending until [source] completes or the
 * caller is cancelled.
 *
 * This is the fold `produceContentState` runs, on its own — for a caller that holds its own
 * [MutableState] and runs its own coroutine. It needs no composition.
 *
 * If [source] completes while the state is still loading — it ended without producing anything —
 * the status is settled to idle, so a spinner can never hang. That happens in `onCompletion`,
 * guarded on `cause == null`, because cancellation completes a flow too: a cancelled collection — a
 * stale request discarded for a newer one, or the caller going away — must leave a loading state
 * loading. Settling it would report a request as finished that was abandoned.
 */
suspend fun <T> MutableState<ContentState<T>>.collectFrom(source: Flow<Outcome<T>>) {
  foldFrom(source, { state, outcome -> state.applyEmission(outcome) }, { it.settled() })
}

/**
 * Collects a source whose parameters change while it is being collected: for each distinct value of
 * [params], cancels the in-flight request, marks this state reloading, and collects [stream] of
 * that value with [collectFrom].
 *
 * `reloading()` is applied before each new request rather than waiting for the source to report
 * [Outcome.Loading], because not every source does — and it keeps the current value on screen under
 * a refresh indicator rather than dropping to a spinner. [params] is deduplicated, so a source that
 * re-reports an unchanged value starts no request. Debouncing is the caller's: apply it to [params]
 * first, since only the caller knows which of several combined inputs deserves it.
 */
suspend fun <P, T> MutableState<ContentState<T>>.collectLatestFrom(
  params: Flow<P>,
  stream: (P) -> Flow<Outcome<T>>,
) {
  foldLatestFrom(
    params,
    stream,
    reload = { it.reloading() },
    apply = { state, outcome -> state.applyEmission(outcome) },
    settle = { it.settled() },
  )
}

/**
 * [collectFrom] for several sources at once: folds every [OutcomeGroup] that [source] emits into
 * this group, each outcome into its own source's [ContentState].
 *
 * Everything the single-source version says about completion and cancellation applies here, to
 * every source in the group. `JvmName` only because the two erase to the same JVM signature.
 */
@JvmName("collectGroupFrom")
suspend fun <S : ContentStateGroup<O>, O : OutcomeGroup> MutableState<S>.collectFrom(
  source: Flow<O>
) {
  foldFrom(
    source,
    { state, outcomes -> state.applyEmission(outcomes).asSelf<S, O>() },
    { it.settled().asSelf<S, O>() },
  )
}

/**
 * [collectLatestFrom] for several sources at once: for each distinct value of [params], cancels the
 * in-flight requests, marks every source reloading, and collects [stream] of that value.
 *
 * Everything the single-source version says applies here, to every source in the group.
 */
@JvmName("collectGroupLatestFrom")
suspend fun <P, S : ContentStateGroup<O>, O : OutcomeGroup> MutableState<S>.collectLatestFrom(
  params: Flow<P>,
  stream: (P) -> Flow<O>,
) {
  foldLatestFrom(
    params,
    stream,
    reload = { it.reloading().asSelf<S, O>() },
    apply = { state, outcomes -> state.applyEmission(outcomes).asSelf<S, O>() },
    settle = { it.settled().asSelf<S, O>() },
  )
}

// The one fold behind every public function here, so the completion rule cannot drift between the
// single-source and group versions. See `collectFrom` for why it is guarded on `cause == null`.
private suspend fun <S, E> MutableState<S>.foldFrom(
  source: Flow<E>,
  apply: (S, E) -> S,
  settle: (S) -> S,
) {
  source
    .onEach { value = apply(value, it) }
    .onCompletion { cause -> if (cause == null) value = settle(value) }
    .collect()
}

private suspend fun <P, S, E> MutableState<S>.foldLatestFrom(
  params: Flow<P>,
  stream: (P) -> Flow<E>,
  reload: (S) -> S,
  apply: (S, E) -> S,
  settle: (S) -> S,
) {
  params.distinctUntilChanged().collectLatest { parameters ->
    // A new parameter starts a fresh request: keep the current value visible but flag loading.
    value = reload(value)
    foldFrom(stream(parameters), apply, settle)
  }
}

// Every ContentStateGroup subclass overrides applyEmission, reloading and settled to return its own
// type -- ContentStates3 returns a ContentStates3 -- so the ContentStateGroup<O> they are declared
// to return is always the S they were called on. The hierarchy is sealed, so no subclass from
// outside the library can break that, and ContentStatesTest pins it for every arity.
@Suppress("UNCHECKED_CAST")
private fun <S : ContentStateGroup<O>, O : OutcomeGroup> ContentStateGroup<O>.asSelf(): S =
  this as S
