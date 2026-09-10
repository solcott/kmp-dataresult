package io.github.solcott.uistate.circuit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.slack.circuit.retained.produceRetainedState
import io.github.solcott.dataresult.Outcome
import io.github.solcott.uistate.ContentState
import io.github.solcott.uistate.compose.collectFrom
import io.github.solcott.uistate.compose.collectLatestFrom
import kotlinx.coroutines.flow.Flow

/**
 * Collects [stream] into [ContentState] retained in Circuit's registry, restarting on [keys].
 *
 * This is `uistate-compose`'s `produceContentState` for a Circuit presenter. The contract is the
 * same — retention, `hasLoaded`, what [keys] do and do not reset, deduplicating the source yourself
 * — but the state is held by Circuit's `rememberRetained` rather than androidx `retain`. See that
 * function for the details; everything there applies here.
 *
 * The two names mirror the primitive underneath each one: `produceContentState` ↔ Compose's
 * `produceState`, and `produceRetainedContentState` ↔ Circuit's `produceRetainedState`. Both
 * retain. "Retained" says which mechanism does it, not that the other does not.
 *
 * For a source whose parameters change while it is collected, call the
 * `Flow<P>.produceRetainedContentState` extension on those parameters instead.
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
fun <T> produceRetainedContentState(
  initial: T,
  vararg keys: Any?,
  stream: () -> Flow<Outcome<T>>,
): ContentState<T> {
  // The producer restarts on [keys] alone, so it must not close over the lambda it was first
  // composed with -- `rememberUpdatedState` hands it whichever one is current.
  val currentStream by rememberUpdatedState(stream)
  // ProduceStateScope is a MutableState, so the shared fold runs on the implicit receiver.
  val state by produceRetainedState(ContentState(initial), *keys) { collectFrom(currentStream()) }
  return state
}

/**
 * [produceRetainedContentState] for a source whose **parameters change while it is being
 * collected** — a search term, a filter, a sort order.
 *
 * Each distinct value of this flow cancels the in-flight request and starts a fresh one from
 * [stream], marking the state reloading first so the current content stays on screen. This is
 * `uistate-compose`'s `Flow<P>.produceContentState`, retained by Circuit; see it for the full
 * contract, and [produceRetainedContentState] for the paused-record hazard, which applies here too.
 *
 * @receiver the parameters driving the source; the lambda returns the flow to collect for each one.
 */
@Composable
fun <P, T> Flow<P>.produceRetainedContentState(
  initial: T,
  vararg keys: Any?,
  stream: (P) -> Flow<Outcome<T>>,
): ContentState<T> {
  // Captured out here because inside the producer `this` is the ProduceStateScope, which shadows
  // the Flow receiver.
  val params = this
  val currentStream by rememberUpdatedState(stream)
  val state by
    produceRetainedState(ContentState(initial), *keys) {
      collectLatestFrom(params) { currentStream(it) }
    }
  return state
}
