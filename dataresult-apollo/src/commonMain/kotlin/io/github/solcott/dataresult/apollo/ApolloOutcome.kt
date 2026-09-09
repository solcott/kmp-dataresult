package io.github.solcott.dataresult.apollo

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import com.apollographql.apollo.exception.ApolloOfflineException
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.apollo.exception.HttpCacheMissException
import com.apollographql.apollo.exception.JsonDataException
import com.apollographql.apollo.exception.JsonEncodingException
import com.apollographql.cache.normalized.isFromCache
import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Maps each Apollo response to a transport-agnostic [Outcome], tagged with the [Origin] it was
 * served from, and applies [mapSuccess] to the data of a successful one.
 *
 * Cache-miss responses are dropped rather than surfaced as errors: under a cache-then-network
 * policy a network response follows, and under a cache-only lookup the empty result is handled
 * upstream. GraphQL `errors` are checked *before* that drop, so a response carrying both an error
 * and a cache miss surfaces the error instead of vanishing.
 *
 * This never emits [Outcome.Loading] — an Apollo flow does not report its own request lifecycle,
 * and a consumer folding these into a `ContentState` starts in a loading state anyway.
 *
 * [onException] is a callback rather than a logger dependency so this module stays agnostic to any
 * one logging framework. It is invoked only for the exceptions that become an [Outcome.Error];
 * dropped cache misses and GraphQL-level errors do not reach it.
 */
fun <T : Operation.Data, R> Flow<ApolloResponse<T>>.mapToOutcome(
  onException: (Throwable) -> Unit = {},
  mapSuccess: T.() -> R,
): Flow<Outcome<R>> = mapNotNull { response ->
  val origin = if (response.isFromCache) Origin.Cache else Origin.Network
  val exception = response.exception
  when {
    response.hasErrors() ->
      Outcome.Error(DataError.Api(response.errors.orEmpty().map { it.message }), origin)
    exception is CacheMissException || exception is HttpCacheMissException -> null
    exception != null -> {
      onException(exception)
      Outcome.Error(exception.toDataError(), origin)
    }
    else -> Outcome.Data(response.dataOrThrow().mapSuccess(), origin)
  }
}

/** Categorizes an [ApolloException] into the transport-agnostic [DataError] vocabulary. */
fun ApolloException.toDataError(): DataError =
  when (this) {
    is ApolloOfflineException,
    is ApolloNetworkException -> DataError.Network
    is ApolloHttpException -> DataError.Http(statusCode)
    is JsonDataException,
    is JsonEncodingException -> DataError.Serialization
    else -> DataError.Unknown(cause = this, message = message)
  }
