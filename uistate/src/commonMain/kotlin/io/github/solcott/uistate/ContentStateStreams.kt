package io.github.solcott.uistate

import io.github.solcott.dataresult.Outcome
import io.github.solcott.dataresult.OutcomeGroup
import kotlin.jvm.JvmName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Folds every [Outcome] this flow emits into a [ContentState], starting from [initial]: emits
 * [initial] first, then one state per outcome, folded with [applyEmission].
 *
 * This is the fold `uistate-compose` runs into a `MutableState`, as a cold stream, for a host that
 * isn't Compose: SwiftUI observing exported state, Molecule, or a plain `collect`.
 *
 * If the source completes while the state is still loading (it ended without producing anything),
 * one more state is emitted with the status settled to idle, so a spinner can never hang. That
 * happens only when the source completes normally. Cancellation completes a flow too, and a
 * cancelled collection, whether a stale request discarded for a newer one or the collector going
 * away, must leave a loading state loading. Settling it would report an abandoned request as
 * finished. A source that ends after settling emits nothing extra.
 *
 * Don't hold the result with `stateIn(scope, SharingStarted.WhileSubscribed(…), initial)` in a
 * ViewModel or a Decompose component. Each resubscription restarts this cold fold from [initial],
 * so the screen drops back to its placeholder. Hold a `MutableStateFlow` and fold into it with
 * [collectFrom] instead, which continues from the value the holder already has.
 */
fun <T> Flow<Outcome<T>>.scanContentState(initial: ContentState<T>): Flow<ContentState<T>> =
  scanInto(initial) { get, set ->
    foldInto(get, set, this, { state, outcome -> state.applyEmission(outcome) }, { it.settled() })
  }

/**
 * Collects a source whose parameters change while it is being collected: emits [initial], then for
 * each distinct value this flow emits, cancels the in-flight request, emits the current state
 * marked reloading, and folds [stream] of that value with the rules of [scanContentState].
 *
 * `reloading()` is applied before each new request rather than waiting for the source to report
 * [Outcome.Loading], because not every source does, and it keeps the current value on screen under
 * a refresh indicator rather than dropping to a spinner. The parameters are deduplicated, so a
 * source that re-reports an unchanged value starts no request. Debouncing is the caller's: apply it
 * to this flow first, since only the caller knows which of several combined inputs deserves it.
 *
 * The `stateIn` warning on [scanContentState] applies here too. Use [collectLatestFrom] to fold
 * into a holder that outlives the collection.
 */
fun <P, T> Flow<P>.scanContentStateLatest(
  initial: ContentState<T>,
  stream: (P) -> Flow<Outcome<T>>,
): Flow<ContentState<T>> =
  scanLatestInto(initial) { get, set ->
    foldLatestInto(
      this,
      stream,
      get,
      set,
      reload = { it.reloading() },
      apply = { state, outcome -> state.applyEmission(outcome) },
      settle = { it.settled() },
    )
  }

/**
 * Folds every emission of [source] into this state flow, suspending until [source] completes or the
 * caller is cancelled.
 *
 * This is the holder form of [scanContentState], for a ViewModel or a Decompose component that owns
 * the state and launches the collection in its own scope. Every emission is folded into the flow's
 * *current* value, so a collection started again (a retry, a resubscription) carries on from the
 * last state rather than from a placeholder, and a value the caller writes to the flow meanwhile is
 * folded into rather than overwritten. Completion and cancellation follow [scanContentState]: a
 * normal completion settles a still-loading state, and a cancellation leaves it loading.
 */
suspend fun <T> MutableStateFlow<ContentState<T>>.collectFrom(source: Flow<Outcome<T>>) {
  foldInto(
    { value },
    { value = it },
    source,
    { state, outcome -> state.applyEmission(outcome) },
    { it.settled() },
  )
}

/**
 * The holder form of [scanContentStateLatest]: for each distinct value of [params], cancels the
 * in-flight request, marks this state reloading, and folds [stream] of that value with
 * [collectFrom].
 */
suspend fun <P, T> MutableStateFlow<ContentState<T>>.collectLatestFrom(
  params: Flow<P>,
  stream: (P) -> Flow<Outcome<T>>,
) {
  foldLatestInto(
    params,
    stream,
    { value },
    { value = it },
    reload = { it.reloading() },
    apply = { state, outcome -> state.applyEmission(outcome) },
    settle = { it.settled() },
  )
}

/**
 * [scanContentState] for several sources at once: folds every [OutcomeGroup] this flow emits into
 * the group, each outcome into its own source's [ContentState].
 *
 * Everything the single-source version says about completion, cancellation and `stateIn` applies
 * here, to every source in the group.
 */
fun <S : ContentStateGroup<O>, O : OutcomeGroup> Flow<O>.scanContentState(initial: S): Flow<S> =
  scanInto(initial) { get, set ->
    foldInto(
      get,
      set,
      this,
      { state, outcomes -> state.applyEmission(outcomes).asSelf<S, O>() },
      { it.settled().asSelf<S, O>() },
    )
  }

/**
 * [scanContentStateLatest] for several sources at once: for each distinct parameter, cancels the
 * in-flight requests, emits the group with every source reloading, and folds [stream] of that
 * value.
 */
fun <P, S : ContentStateGroup<O>, O : OutcomeGroup> Flow<P>.scanContentStateLatest(
  initial: S,
  stream: (P) -> Flow<O>,
): Flow<S> =
  scanLatestInto(initial) { get, set ->
    foldLatestInto(
      this,
      stream,
      get,
      set,
      reload = { it.reloading().asSelf<S, O>() },
      apply = { state, outcomes -> state.applyEmission(outcomes).asSelf<S, O>() },
      settle = { it.settled().asSelf<S, O>() },
    )
  }

/**
 * [collectFrom] for several sources at once. Everything the single-source version says applies
 * here, to every source in the group. `JvmName` only because the two erase to the same JVM
 * signature.
 */
@JvmName("collectGroupFrom")
suspend fun <S : ContentStateGroup<O>, O : OutcomeGroup> MutableStateFlow<S>.collectFrom(
  source: Flow<O>
) {
  foldInto(
    { value },
    { value = it },
    source,
    { state, outcomes -> state.applyEmission(outcomes).asSelf<S, O>() },
    { it.settled().asSelf<S, O>() },
  )
}

/**
 * [collectLatestFrom] for several sources at once: for each distinct value of [params], cancels the
 * in-flight requests, marks every source reloading, and folds [stream] of that value.
 */
@JvmName("collectGroupLatestFrom")
suspend fun <P, S : ContentStateGroup<O>, O : OutcomeGroup> MutableStateFlow<S>.collectLatestFrom(
  params: Flow<P>,
  stream: (P) -> Flow<O>,
) {
  foldLatestInto(
    params,
    stream,
    { value },
    { value = it },
    reload = { it.reloading().asSelf<S, O>() },
    apply = { state, outcomes -> state.applyEmission(outcomes).asSelf<S, O>() },
    settle = { it.settled().asSelf<S, O>() },
  )
}
