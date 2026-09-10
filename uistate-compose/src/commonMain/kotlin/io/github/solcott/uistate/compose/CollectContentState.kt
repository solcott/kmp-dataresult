package io.github.solcott.uistate.compose

import androidx.compose.runtime.MutableState
import io.github.solcott.dataresult.Outcome
import io.github.solcott.uistate.ContentState
import io.github.solcott.uistate.applyEmission
import io.github.solcott.uistate.reloading
import io.github.solcott.uistate.settled
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
  source
    .onEach { value = value.applyEmission(it) }
    .onCompletion { cause -> if (cause == null) value = value.settled() }
    .collect()
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
  params.distinctUntilChanged().collectLatest { parameters ->
    // A new parameter starts a fresh request: keep the current value visible but flag loading.
    value = value.reloading()
    collectFrom(stream(parameters))
  }
}
