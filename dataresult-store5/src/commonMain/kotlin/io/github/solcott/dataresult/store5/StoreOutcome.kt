package io.github.solcott.dataresult.store5

import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin

/**
 * Maps a Store stream to transport-agnostic [Outcome]s, letting a repository expose its results
 * without leaking Store5 into every layer above it.
 *
 * `NoNewData` is dropped, so the stream carries only emissions that change what a consumer should
 * render. Everything else maps through [toOutcomeOrNull].
 *
 * **Cache misses.** Pass [fetching] — the request's `refresh` — and [isEmpty] to keep a cache miss
 * from reaching the consumer as an answer. A source of truth backed by a database cannot return
 * null for a key it has never fetched: its query returns an empty list, and Store emits that as
 * data before it starts the fetch. Forwarded as it is, that empty list looks exactly like "nothing
 * matched", so a first visit shows an empty screen while the network is still being asked — and
 * keeps showing it, rather than the failure, if the network then fails.
 *
 * With [fetching] set, an empty *first* cached value is held back until the fetch settles:
 * - data from the fetcher replaces it;
 * - an error from the fetcher is emitted and the held value discarded, so the consumer shows the
 *   failure instead of an empty result it never really had;
 * - `NoNewData`, or the stream completing, releases it — the cache was the answer after all, and
 *   nothing is left waiting on a value that is never coming.
 *
 * Only a first value can be a miss: once anything has been emitted, an empty cached value is a real
 * change, such as a local delete, and passes straight through. Without [fetching] nothing is held,
 * because no fetch is coming to answer instead. Cancellation ends the stream without releasing a
 * held value, so an abandoned request never reports a miss as its result.
 */
fun <T> Flow<StoreReadResponse<T>>.asOutcomes(
  fetching: Boolean = false,
  isEmpty: (T) -> Boolean = { false },
): Flow<Outcome<T>> = flow {
  var pending = fetching
  var emittedData = false
  var held: Outcome.Data<T>? = null
  this@asOutcomes.collect { response ->
    if (pending && response.settlesFetch()) {
      pending = false
      if (response is StoreReadResponse.NoNewData) {
        held?.let {
          emit(it)
          emittedData = true
        }
      }
      held = null
    }
    val outcome = response.toOutcomeOrNull() ?: return@collect
    if (outcome is Outcome.Data) {
      if (pending && !emittedData && outcome.origin == Origin.Cache && isEmpty(outcome.data)) {
        held = outcome
        return@collect
      }
      held = null
      emittedData = true
    }
    emit(outcome)
  }
  // Reached only when the source completes normally: cancellation throws out of `collect` first.
  held?.let { emit(it) }
}

/** Whether this response ends a fetch: the fetcher answered, failed, or had nothing new. */
private fun StoreReadResponse<*>.settlesFetch(): Boolean =
  when (this) {
    is StoreReadResponse.Data -> origin is StoreReadResponseOrigin.Fetcher
    is StoreReadResponse.Error -> origin is StoreReadResponseOrigin.Fetcher
    is StoreReadResponse.NoNewData -> true
    is StoreReadResponse.Initial,
    is StoreReadResponse.Loading -> false
  }

/**
 * Maps one [StoreReadResponse] to an [Outcome], or null for a response that carries no information
 * a consumer should act on.
 *
 * | [StoreReadResponse]  | [Outcome]                                                     |
 * |----------------------|---------------------------------------------------------------|
 * | `Initial`, `Loading` | [Outcome.Loading]                                             |
 * | `NoNewData`          | null — the fetcher returned nothing, so the held value stands |
 * | `Data`               | [Outcome.Data]                                                |
 * | `Error.Exception`    | [Outcome.Error] with [DataError.Unknown]                      |
 * | `Error.Message`      | [Outcome.Error] with [DataError.Api]                          |
 * | `Error.Custom`       | [Outcome.Error] with [DataError.Unknown]                      |
 *
 * Dropping `NoNewData` deserves a note, because it is the one case that discards a real event. It
 * means a refresh completed and produced nothing new — the source of truth has already re-emitted
 * whatever is current, so a consumer folding these has the right data but would be left with an
 * in-flight [Outcome.Loading] status that never settles. Store always follows `NoNewData` with a
 * source-of-truth emission, which settles it; `ContentState.settled()` is the escape hatch if a
 * particular store does not.
 *
 * Store's own error types are shallower than [DataError] — it never distinguishes a network drop
 * from an HTTP status or a decode failure. A source that can tell those apart should classify at
 * its own boundary (in a `Fetcher`, say) rather than relying on this mapping to recover detail
 * Store never carried.
 */
fun <T> StoreReadResponse<T>.toOutcomeOrNull(): Outcome<T>? =
  when (this) {
    is StoreReadResponse.Initial,
    is StoreReadResponse.Loading -> Outcome.Loading
    is StoreReadResponse.NoNewData -> null
    is StoreReadResponse.Data -> Outcome.Data(value, origin.toOrigin())
    is StoreReadResponse.Error -> Outcome.Error(dataError, origin.toOrigin())
  }

/** Classifies a Store error into the [DataError] vocabulary. */
val StoreReadResponse.Error.dataError: DataError
  get() =
    when (this) {
      is StoreReadResponse.Error.Exception ->
        DataError.Unknown(cause = error, message = error.message)
      is StoreReadResponse.Error.Message -> DataError.Api(persistentListOf(message))
      is StoreReadResponse.Error.Custom<*> ->
        DataError.Unknown(cause = error as? Throwable, message = error.toString())
    }

/**
 * Collapses Store's four origins onto [Origin]'s two: only a fetcher response is fresh from the
 * network, and everything else — the in-memory cache, the source of truth, and the `Initial`
 * placeholder — is local.
 */
private fun StoreReadResponseOrigin.toOrigin(): Origin =
  when (this) {
    is StoreReadResponseOrigin.Fetcher -> Origin.Network
    is StoreReadResponseOrigin.Cache,
    is StoreReadResponseOrigin.SourceOfTruth,
    is StoreReadResponseOrigin.Initial -> Origin.Cache
  }
