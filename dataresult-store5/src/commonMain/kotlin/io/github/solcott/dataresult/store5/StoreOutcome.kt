package io.github.solcott.dataresult.store5

import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin

/**
 * Maps a Store stream to transport-agnostic [Outcome]s, letting a repository expose its results
 * without leaking Store5 into every layer above it.
 *
 * `NoNewData` is dropped, so the stream carries only emissions that change what a consumer should
 * render. Everything else maps through [toOutcomeOrNull].
 */
fun <T> Flow<StoreReadResponse<T>>.asOutcomes(): Flow<Outcome<T>> = mapNotNull {
  it.toOutcomeOrNull()
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
      is StoreReadResponse.Error.Message -> DataError.Api(listOf(message))
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
