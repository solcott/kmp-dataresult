package io.github.solcott.uistate

import io.github.solcott.dataresult.Outcome

/**
 * Folds one source emission into this state.
 *
 * This is the whole contract between a data source and the UI: hand it every [Outcome] a source
 * produces, in order, and the resulting [ContentState] is what the screen should render.
 *
 * [ContentState.data] is only ever replaced by [Outcome.Data]. A [Outcome.Loading] or an
 * [Outcome.Error] leaves the last known value in place and moves [ContentState.status] instead —
 * that is stale-while-revalidate, and it is why a background refresh shows an indicator over the
 * current content rather than replacing it with a spinner.
 *
 * The fold settles [ContentState.status] on every value rather than waiting for the flow to end, so
 * it makes no assumption about whether the source completes. A one-shot query, a cache watcher that
 * re-emits after local writes, and a never-completing subscription all reach a settled, non-loading
 * state the same way.
 */
fun <T> ContentState<T>.applyEmission(outcome: Outcome<T>): ContentState<T> =
  when (outcome) {
    Outcome.Loading -> copy(status = LoadStatus.Loading)
    is Outcome.Data -> copy(data = outcome.data, origin = outcome.origin, status = LoadStatus.Idle)
    is Outcome.Error -> copy(status = LoadStatus.Failed(outcome.cause))
  }
