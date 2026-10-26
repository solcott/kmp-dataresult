package io.github.solcott.dataresult.ktor

import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/**
 * Runs [call] and turns its result into an [Outcome]: [Outcome.Data] for a value, or
 * [Outcome.Error] for anything it throws, classified by [toDataError]. Every outcome is tagged
 * [Origin.Network], because Ktor does not report whether `HttpCache` answered.
 *
 * **This needs `expectSuccess = true`**, on the client or on the request. With it, a non-2xx
 * response throws a [ResponseException], which carries the response, and becomes [DataError.Http]
 * (or whatever [errorBody] decodes). With Ktor's default of `false`, a 404 is an ordinary return,
 * so `httpOutcome { client.get(url).body<T>() }` tries to decode the error page as `T` and reports
 * [DataError.Serialization]. Use [toOutcome] on the response for that client, since it reads the
 * status itself.
 *
 * Cancellation is rethrown, never turned into an outcome, so cancelling a collector cancels the
 * request rather than showing an error. [onException] receives every other exception that becomes
 * an [Outcome.Error], thrown response exceptions included.
 *
 * [errorBody] decodes an error response's body, such as an XRPC `{"error", "message"}` envelope,
 * into a [DataError], usually [DataError.Api]. Returning null, or throwing, falls back to
 * [DataError.Http], so a hook that tries a decode needs no guard of its own.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun <T> httpOutcome(
  onException: (Throwable) -> Unit = {},
  errorBody: suspend (HttpResponse) -> DataError? = { null },
  call: suspend () -> T,
): Outcome<T> =
  try {
    Outcome.Data(call(), Origin.Network)
  } catch (
    // Throwable, not Exception: Ktor's Js engine fails a request with a kotlin.Error ("Fail to
    // fetch"), which an Exception catch would let escape to the caller.
    e: Throwable) {
    // Timeouts are excluded as a guard: in Ktor 2 they were CancellationExceptions, and rethrowing
    // one would cancel the collector instead of reporting a network failure.
    if (e is CancellationException && !e.isTimeout()) throw e
    val cause = if (e is ResponseException) e.response.errorCause(errorBody) else e.toDataError()
    onException(e)
    Outcome.Error(cause, Origin.Network)
  }

/**
 * A cold flow that emits [Outcome.Loading] and then [httpOutcome]'s result for [call], then
 * completes. Each collection makes a new request, so re-collecting is a refresh. See [httpOutcome]
 * for the `expectSuccess` requirement and the parameters.
 */
fun <T> httpOutcomeFlow(
  onException: (Throwable) -> Unit = {},
  errorBody: suspend (HttpResponse) -> DataError? = { null },
  call: suspend () -> T,
): Flow<Outcome<T>> = flow {
  emit(Outcome.Loading)
  emit(httpOutcome(onException, errorBody, call))
}

/**
 * Turns this response into an [Outcome], reading the status first, so it is correct whatever the
 * client's `expectSuccess` setting.
 *
 * A 2xx status runs [mapSuccess], typically `body<T>()`, and anything that throws is classified as
 * in [httpOutcome]. Any other status becomes [DataError.Http], or whatever [errorBody] decodes from
 * the body, without running [mapSuccess]. A non-2xx response threw nothing, so it does not reach
 * [onException].
 */
suspend fun <T> HttpResponse.toOutcome(
  onException: (Throwable) -> Unit = {},
  errorBody: suspend (HttpResponse) -> DataError? = { null },
  mapSuccess: suspend HttpResponse.() -> T,
): Outcome<T> =
  if (status.isSuccess()) {
    httpOutcome(onException, errorBody) { mapSuccess() }
  } else {
    Outcome.Error(errorCause(errorBody), Origin.Network)
  }

/**
 * Categorizes a Ktor failure into the transport-agnostic [DataError] vocabulary.
 * - A [ResponseException] is [DataError.Http] with its status. This can't decode the body, which is
 *   what [httpOutcome]'s `errorBody` is for.
 * - Timeouts and any `kotlinx.io.IOException` are [DataError.Network].
 * - A body that no converter could read, or that failed to decode, is [DataError.Serialization].
 * - A platform network failure that isn't an `IOException` is [DataError.Network]: CIO's
 *   `UnresolvedAddressException` on JVM and Android, and the Js engine's "Fail to fetch" on JS.
 * - Anything else is [DataError.Unknown], with this throwable kept.
 *
 * What each engine throws for a host that doesn't resolve, and for a refused connection, as
 * observed on Ktor 3.0.0:
 * - OkHttp: `UnknownHostException` and `ConnectException`, both `IOException`s, so `Network`.
 * - CIO on JVM: `UnresolvedAddressException`, an `IllegalArgumentException` mapped by the platform
 *   hook, and `ConnectException`. Both `Network`.
 * - Darwin: `DarwinHttpRequestException` wrapping the `NSURLErrorDomain` error (-1003, -1004), an
 *   `IOException`, so `Network`.
 * - Js on Node: `kotlin.Error("Fail to fetch")` wrapping the fetch rejection, for both. Not an
 *   `Exception` at all, so it is matched by message in `jsMain`, and `Network`.
 * - Js on wasmJs: not observed. Under Node the engine failed to load ("Do not use top-level
 *   require"), so there is no mapping, and its failures are `Unknown` with the cause kept.
 *
 * This only classifies. It does not single out cancellation, which a caller must rethrow rather
 * than map; [httpOutcome] does.
 */
fun Throwable.toDataError(): DataError =
  when {
    this is ResponseException -> DataError.Http(response.status.value)
    isTimeout() || this is IOException -> DataError.Network
    this is NoTransformationFoundException ||
      this is ContentConvertException ||
      this is SerializationException -> DataError.Serialization
    isPlatformNetworkFailure() -> DataError.Network
    else -> DataError.Unknown(cause = this, message = message)
  }

/** True for a network failure this platform throws that isn't a `kotlinx.io.IOException`. */
internal expect fun Throwable.isPlatformNetworkFailure(): Boolean

private fun Throwable.isTimeout(): Boolean =
  this is HttpRequestTimeoutException ||
    this is ConnectTimeoutException ||
    this is SocketTimeoutException

/**
 * The cause for a non-success response: whatever [errorBody] decodes, or [DataError.Http] when it
 * returns null or throws. A throwing hook is not reported, because the outcome is still the HTTP
 * error the caller expects; only cancellation escapes it.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private suspend fun HttpResponse.errorCause(
  errorBody: suspend (HttpResponse) -> DataError?
): DataError =
  try {
    errorBody(this)
  } catch (e: CancellationException) {
    throw e
  } catch (
    // A failed decode is exactly what this guard exists for, whatever it throws, kotlin.Error from
    // a
    // JS decode included.
    _: Throwable) {
    null
  } ?: DataError.Http(status.value)
