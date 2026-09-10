package io.github.solcott.uistate.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import io.github.solcott.dataresult.Outcome
import io.github.solcott.uistate.ContentState
import kotlinx.coroutines.flow.Flow

/**
 * Collects [stream] into [ContentState], restarting on [keys], with the state retained wherever the
 * host retains values.
 *
 * For a source whose parameters change *while* it is collected — a search term, a filter — call the
 * `Flow<P>.produceContentState` extension on those parameters instead. In a Circuit presenter, use
 * `uistate-circuit`'s `produceRetainedContentState`.
 *
 * ### Retention
 *
 * The state is held with androidx `retain`, so it lasts as long as the host's
 * `LocalRetainedValuesStore` keeps it: across configuration changes on Android, where a
 * lifecycle-aware store is installed at the root by default. With no store installed, `retain`
 * behaves exactly like `remember`. The name mirrors Compose's `produceState` rather than promising
 * more retention than the host provides.
 *
 * Whatever the retention, the held value is what keeps a refresh from blanking the screen, so a
 * caller does not need a second holder beside this — [ContentState.data] *is* the hold.
 *
 * [initial] is the value to show before anything has loaded. Distinguish "nothing yet" from a
 * genuinely empty result with `ContentState.hasLoaded`, never by testing [initial] for emptiness —
 * an empty list is a real answer.
 *
 * ### Keys
 *
 * [keys] restart the collection: pass a retry counter, and anything the stream is derived from that
 * does not change while it is collected. The held value survives a restart, so a retry keeps the
 * previous content on screen while the new request runs. The status is **not** reset to loading on
 * a restart — a source that reports its own lifecycle emits [Outcome.Loading] and settles it, and
 * one that does not would only flicker. Call `ContentState.reloading()` yourself if a restart
 * should show a refresh indicator.
 *
 * Deduplicate the stream itself if it needs it — `distinctUntilChanged()` on what you pass. That is
 * a statement about a particular source, not something to apply on everyone's behalf.
 *
 * ### Why this one is not an extension too
 *
 * Its sibling takes its parameters as a receiver, so this looks like it should take its source the
 * same way — `Flow<Outcome<T>>.produceContentState(initial, keys)`. It can't: `Flow<Outcome<T>>` is
 * a perfectly good `Flow<P>` with `P = Outcome<T>`, so the two extensions would be ambiguous at
 * every call site. One top-level function and one extension is what lets them share a name.
 */
@Composable
fun <T> produceContentState(
  initial: T,
  vararg keys: Any?,
  stream: () -> Flow<Outcome<T>>,
): ContentState<T> {
  // The effect restarts on [keys] alone, so it must not close over the lambda it was first composed
  // with -- `rememberUpdatedState` hands it whichever one is current.
  val currentStream by rememberUpdatedState(stream)
  // No keys on `retain`, deliberately. Its keys *discard* the held value, the way `remember(keys)`
  // does -- and the hold has to outlive a key change, because that is what keeps a retry from
  // blanking the screen. Only the effect restarts on [keys].
  val holder = retain { mutableStateOf(ContentState(initial)) }
  LaunchedEffect(*keys) { holder.collectFrom(currentStream()) }
  return holder.value
}

/**
 * Collects a source whose **parameters change while it is being collected** — a search term, a
 * filter, a sort order — into [ContentState].
 *
 * This is the difference from the receiverless [produceContentState]: there, the source is fixed
 * for the life of the collection; here, each value this flow emits swaps it for a new one. Reach
 * for this whenever the user can change what is being asked for while looking at the answer, and
 * for the plain one otherwise.
 *
 * Every emission cancels the in-flight request and starts a fresh one from [stream], marking the
 * state `reloading()` first so the current content stays on screen under a refresh indicator rather
 * than dropping to a spinner. Prefer this over putting the parameter in [keys]: a key change
 * restarts the whole collection, which is the wrong shape for something that changes under the
 * user's eyes.
 *
 * The receiver is deduplicated with `distinctUntilChanged()`, so a source that re-reports an
 * unchanged value — a child that reports its state again after a configuration change, say — does
 * not restart an identical request. Debouncing is the caller's: apply it to the receiver before
 * calling, since only the caller knows which of several combined inputs deserves it.
 *
 * Everything in [produceContentState]'s documentation about retention and [keys] applies here too.
 *
 * @receiver the parameters driving the source. Its shape mirrors `flatMapLatest`, which this is:
 *   the receiver drives, and the lambda returns the flow to collect for each value.
 */
@Composable
fun <P, T> Flow<P>.produceContentState(
  initial: T,
  vararg keys: Any?,
  stream: (P) -> Flow<Outcome<T>>,
): ContentState<T> {
  val params = this
  // The effect outlives any single composition, so each new parameter must reach the lambda from
  // the
  // latest one -- not the lambda the effect was launched with.
  val currentStream by rememberUpdatedState(stream)
  // Unkeyed for the same reason as the receiverless overload: `retain`'s keys would discard the
  // hold.
  val holder = retain { mutableStateOf(ContentState(initial)) }
  LaunchedEffect(*keys) { holder.collectLatestFrom(params) { currentStream(it) } }
  return holder.value
}
