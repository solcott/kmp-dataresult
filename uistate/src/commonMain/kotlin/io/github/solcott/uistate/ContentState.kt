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
 * legitimate result, not a reason to keep showing a spinner. The one exception is an empty value
 * read from cache while its request is still in flight or has failed: that is a cache miss, not a
 * result, and [hasAnswer] tells the two apart.
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

/**
 * True once the held value is an *answer*: like [hasLoaded], except that an empty value from
 * [Origin.Cache] does not count while [ContentState.status] is [LoadStatus.Loading] or
 * [LoadStatus.Failed].
 *
 * An empty cache read on a first visit is a miss, not a result — it is what a database returns for
 * a key it has never fetched, and the request it prompted has not answered yet. Counting it as
 * loaded shows an empty screen while the network is still being asked, and keeps showing it instead
 * of the failure if the network then fails. Sources should not emit a miss at all (see `Outcome`;
 * the Store5 adapter's `asOutcomes(fetching, isEmpty)` holds one back), so this is the backstop for
 * a source that cannot know a fetch is coming, such as a hand-written flow.
 *
 * A settled state is always an answer, whatever its origin: nothing else is coming, so an empty
 * cache is the result. That is what keeps this from hanging a spinner — it can only be false while
 * a request is in flight or has failed.
 *
 * [isEmpty] says what empty means for [T], which the library cannot know: `List<T>::isEmpty` for a
 * list, `{ it == null }` for an optional single value.
 */
fun <T> ContentState<T>.hasAnswer(isEmpty: (T) -> Boolean): Boolean =
  hasLoaded && !(origin == Origin.Cache && status !is LoadStatus.Idle && isEmpty(data))

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
