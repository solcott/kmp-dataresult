package io.github.solcott.uistate

import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin

/**
 * View state for a piece of content backed by a data source.
 *
 * [data] is always present so the UI can keep showing the last known value while a refresh is in
 * flight (stale-while-revalidate). [origin] records where that value came from, and [status] tracks
 * request activity independently — together they let the UI show, for example, cached data with an
 * "updating" indicator.
 *
 * [origin] is null until the first value arrives, which is what distinguishes "nothing has loaded
 * yet" from "a value loaded and happens to be empty". Prefer [hasLoaded] over inspecting [data] for
 * emptiness when choosing between a first-load placeholder and real content — an empty list is a
 * legitimate result, not a reason to keep showing a spinner.
 *
 * The model is agnostic to whether the source completes. A one-shot query, a cache watcher that
 * re-emits after local writes, and a subscription/SSE stream are all handled the same way: fold
 * each emission with [applyEmission], which settles [status] on every value rather than waiting for
 * the flow to end. That is what lets a never-completing stream still reach a settled, non-loading
 * state.
 */
data class ContentState<T>(
  val data: T,
  val origin: Origin? = null,
  val status: LoadStatus = LoadStatus.Loading,
)

/**
 * Request activity for a [ContentState].
 * - [Loading] — a request or refresh is in flight; makes no assumption about where it will be
 *   served from (a fetch-policy detail the consumer must not depend on).
 * - [Idle] — settled; the held value is current. A live source may still replace it later with
 *   another emission, silently, without returning to [Loading].
 * - [Failed] — the most recent request failed; any previously loaded [ContentState.data] is kept,
 *   and a live source may recover by emitting again.
 */
sealed class LoadStatus {
  data object Idle : LoadStatus()

  data object Loading : LoadStatus()

  data class Failed(val error: DataError) : LoadStatus()
}

/** True while a request is in flight. */
val ContentState<*>.isLoading: Boolean
  get() = status is LoadStatus.Loading

/**
 * True once at least one value has arrived, whatever it was and whether or not the most recent
 * request then failed. False only before the first `Outcome.Data`, i.e. while [ContentState.data]
 * is still the placeholder the state was built with.
 */
val ContentState<*>.hasLoaded: Boolean
  get() = origin != null

/** The failure of the most recent request, or null if it did not fail. */
val ContentState<*>.errorOrNull: DataError?
  get() = (status as? LoadStatus.Failed)?.error

/**
 * Marks a fresh request as in flight while keeping any data already on screen. Call this when
 * *intentionally* re-fetching (a filter change, a retry, pull-to-refresh) so the UI can show a
 * refresh indicator over the current content; the next emission settles it via [applyEmission].
 *
 * A source that reports its own request lifecycle emits `Outcome.Loading` and needs none of this —
 * [applyEmission] does the same thing on its own.
 */
fun <T> ContentState<T>.reloading(): ContentState<T> = copy(status = LoadStatus.Loading)

/**
 * Settles a still-loading request to [LoadStatus.Idle]. Used as a safety net for a source that
 * completes without emitting (so the spinner never hangs); a source that emits settles via
 * [applyEmission] instead, and a never-completing source never needs this.
 */
fun <T> ContentState<T>.settled(): ContentState<T> =
  if (status is LoadStatus.Loading) copy(status = LoadStatus.Idle) else this
