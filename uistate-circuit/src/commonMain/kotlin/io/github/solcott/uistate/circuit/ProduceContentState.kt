package io.github.solcott.uistate.circuit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.slack.circuit.retained.produceRetainedState
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
 * Collects [stream] into retained [ContentState], restarting on [keys].
 *
 * The retained state is what holds the last loaded value, so a refresh keeps the current content on
 * screen instead of replacing it with a spinner. A caller does not need a second retained variable
 * beside this to do that — [ContentState.data] *is* that hold.
 *
 * [initial] is the value to show before anything has loaded. Distinguish "nothing yet" from a
 * genuinely empty result with `ContentState.hasLoaded`, never by testing [initial]'s type for
 * emptiness — an empty list is a real answer.
 *
 * [keys] restart the producer, as `produceRetainedState`'s do: pass a retry counter, and anything
 * the stream is derived from that does not change while it is collected. The held value survives a
 * restart, so a retry keeps the previous content on screen while the new request runs. Note the
 * status is **not** reset to loading on a restart — a source that reports its own lifecycle emits
 * [Outcome.Loading] and settles it, and one that does not would only flicker. Call
 * `ContentState.reloading()` yourself if a restart should show a refresh indicator.
 *
 * Deduplicate the stream itself if it needs it — `distinctUntilChanged()` on what you pass. That is
 * a statement about a particular source, not something to apply on everyone's behalf.
 *
 * ### Circuit will silently stop collecting a paused record
 *
 * Circuit pauses a record that is not the current one, and `pausableState` drops the producer from
 * composition and replays its last value. In a multi-pane layout — a list beside a detail — that
 * means this stops collecting for the pane that is not on top, with **no error anywhere**: state
 * driving the stream updates and nothing re-queries. Wrap each composed pane in
 * `ProvideRecordLifecycle(isActive = true)` if it should keep running.
 */
@Composable
fun <T> produceContentState(
  initial: T,
  vararg keys: Any?,
  stream: () -> Flow<Outcome<T>>,
): ContentState<T> {
  // The producer restarts on [keys] alone, so it must not close over the lambda it was first
  // composed with -- `rememberUpdatedState` hands it whichever one is current.
  val currentStream by rememberUpdatedState(stream)
  val state by produceRetainedState(ContentState(initial), *keys) { fold(currentStream()) }
  return state
}

/**
 * The variant for a source whose parameters change *while* it is collected — a search term, a
 * filter, a sort order.
 *
 * Each new value of [params] cancels the in-flight request and starts a fresh one from [stream],
 * marking the state `reloading()` first so the current content stays on screen under a refresh
 * indicator rather than dropping to a spinner. Use this rather than putting the parameter in
 * [keys]: a key change restarts the whole producer, which is the wrong shape for something the user
 * changes while looking at the results.
 *
 * [params] is deduplicated with `distinctUntilChanged()`, so a source that re-reports an unchanged
 * value — a child that reports its state again after a configuration change, say — does not restart
 * an identical request. Debouncing is the caller's: apply it to [params] before passing it, since
 * only the caller knows which of several combined inputs deserves it.
 *
 * Everything in [produceContentState]'s documentation about retention, [keys] and the paused-record
 * hazard applies here too.
 *
 * A separate name rather than an overload of [produceContentState], because the two cannot be told
 * apart at a call site: a lambda written `{ repository.foo() }` satisfies `(P) -> Flow<Outcome<T>>`
 * just as well as `() -> Flow<Outcome<T>>`, by ignoring the implicit `it`. As overloads, every call
 * passing a `Flow` would be ambiguous.
 */
@Composable
fun <P, T> produceContentStateFor(
  initial: T,
  params: Flow<P>,
  vararg keys: Any?,
  stream: (P) -> Flow<Outcome<T>>,
): ContentState<T> {
  val currentStream by rememberUpdatedState(stream)
  val state by
    produceRetainedState(ContentState(initial), *keys) {
      params.distinctUntilChanged().collectLatest { parameters ->
        // A new parameter starts a fresh request: keep the current value visible but flag loading.
        // The source may not emit Outcome.Loading of its own, so this cannot wait for one.
        value = value.reloading()
        fold(currentStream(parameters))
      }
    }
  return state
}

/**
 * Folds every emission into the held state, and settles a still-loading status if the source
 * completes without ever emitting.
 *
 * The `cause == null` guard is the whole point of doing this in `onCompletion` rather than after
 * `collect()`: cancellation also completes the flow, and a cancelled collection — `collectLatest`
 * discarding a stale request, or the caller leaving composition — must leave a loading state
 * loading. Settling it would report a request as finished that was abandoned.
 */
private suspend fun <T> ProduceStateScope<ContentState<T>>.fold(source: Flow<Outcome<T>>) {
  source
    .onEach { value = value.applyEmission(it) }
    .onCompletion { cause -> if (cause == null) value = value.settled() }
    .collect()
}
