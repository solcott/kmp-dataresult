package io.github.solcott.dataresult.ktor

import app.cash.turbine.test
import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/**
 * Covers how a Ktor call or response becomes an [Outcome], and how Ktor's failures collapse into
 * [DataError]. Requests go to a [MockEngine], so nothing here touches the network.
 */
class KtorOutcomeTest {

  private val caught = mutableListOf<Throwable>()

  private fun client(expectSuccess: Boolean = false, handler: MockRequestHandler) =
    HttpClient(MockEngine(handler)) { this.expectSuccess = expectSuccess }

  private fun respondingWith(status: HttpStatusCode, body: String = "", expectSuccess: Boolean) =
    client(expectSuccess) { respond(body, status) }

  // --- Classification -------------------------------------------------------------------------

  @Test
  fun timeoutsAreNetwork() {
    listOf(
        HttpRequestTimeoutException("https://example.test", 1_000),
        ConnectTimeoutException("connect timed out"),
        SocketTimeoutException("read timed out"),
      )
      .forEach { assertEquals(DataError.Network, it.toDataError(), "$it") }
  }

  @Test
  fun ioExceptionIsNetwork() {
    assertEquals(DataError.Network, IOException("connection reset").toDataError())
  }

  @Test
  fun bodyWithNoConverterIsSerialization() = runTest {
    val failure = runCatching {
      respondingWith(HttpStatusCode.OK, "{}", expectSuccess = false)
        .get("https://example.test")
        .body<Unregistered>()
    }
      .exceptionOrNull()

    assertEquals(DataError.Serialization, failure?.toDataError())
  }

  @Test
  fun jsonConvertExceptionIsSerialization() {
    assertEquals(DataError.Serialization, JsonConvertException("bad json").toDataError())
  }

  @Test
  fun serializationExceptionIsSerialization() {
    assertEquals(DataError.Serialization, SerializationException("missing field").toDataError())
  }

  @Test
  fun unrecognizedExceptionIsUnknownAndKeepsItsCause() {
    val failure = IllegalStateException("surprise")

    assertEquals(DataError.Unknown(cause = failure, message = "surprise"), failure.toDataError())
  }

  // --- httpOutcome ----------------------------------------------------------------------------

  @Test
  fun callReturningAValueIsNetworkData() = runTest {
    assertEquals(Outcome.Data("hello", Origin.Network), httpOutcome { "hello" })
  }

  @Test
  fun successfulRequestIsNetworkData() = runTest {
    val client = respondingWith(HttpStatusCode.OK, "hello", expectSuccess = true)

    val outcome = httpOutcome { client.get("https://example.test").bodyAsText() }

    assertEquals(Outcome.Data("hello", Origin.Network), outcome)
  }

  @Test
  fun thrownResponseExceptionIsHttpErrorAndIsReported() = runTest {
    val client = respondingWith(HttpStatusCode.ServiceUnavailable, expectSuccess = true)

    val outcome =
      httpOutcome(onException = { caught += it }) {
        client.get("https://example.test").bodyAsText()
      }

    assertEquals(Outcome.Error(DataError.Http(503), Origin.Network), outcome)
    assertEquals(1, caught.size)
  }

  @Test
  fun thrownResponseExceptionGoesThroughErrorBody() = runTest {
    val client = respondingWith(HttpStatusCode.BadRequest, XRPC_ERROR, expectSuccess = true)

    val outcome =
      httpOutcome(errorBody = ::decodeXrpcError) { client.get("https://example.test").bodyAsText() }

    assertEquals(Outcome.Error(BAD_CURSOR, Origin.Network), outcome)
  }

  @Test
  fun networkFailureIsReportedWithTheException() = runTest {
    val failure = IOException("connection reset")

    val outcome = httpOutcome<String>(onException = { caught += it }) { throw failure }

    assertEquals(Outcome.Error(DataError.Network, Origin.Network), outcome)
    assertSame(failure, caught.single())
  }

  // Ktor's Js engine fails with a kotlin.Error rather than an Exception; it must still be an
  // outcome.
  @Test
  fun throwableThatIsNotAnExceptionIsStillAnOutcome() = runTest {
    val failure = Error("not an Exception")

    val outcome = httpOutcome<String>(onException = { caught += it }) { throw failure }

    assertEquals(
      Outcome.Error(DataError.Unknown(failure, failure.message), Origin.Network),
      outcome,
    )
    assertSame(failure, caught.single())
  }

  @Test
  fun cancellationPropagatesAndIsNeverAnOutcome() = runTest {
    val requestStarted = CompletableDeferred<Unit>()
    val client =
      client(expectSuccess = true) {
        requestStarted.complete(Unit)
        awaitCancellation()
      }
    var outcome: Outcome<String>? = null

    val job = launch {
      outcome =
        httpOutcome(onException = { caught += it }) {
          client.get("https://example.test").bodyAsText()
        }
    }
    requestStarted.await()
    job.cancelAndJoin()

    assertTrue(job.isCancelled)
    assertNull(outcome)
    assertTrue(caught.isEmpty(), "cancellation reached onException: $caught")
  }

  /**
   * Pins the trap in [httpOutcome]'s KDoc: with `expectSuccess = false`, a 404 is an ordinary
   * return, so decoding it as the success type reports [DataError.Serialization], not
   * [DataError.Http]. Use [toOutcome] for that client.
   */
  @Test
  fun withoutExpectSuccessAnErrorPageDecodedAsTheSuccessTypeIsSerialization() = runTest {
    val client = respondingWith(HttpStatusCode.NotFound, "Not Found", expectSuccess = false)

    val outcome = httpOutcome { client.get("https://example.test").body<Unregistered>() }

    assertEquals(Outcome.Error(DataError.Serialization, Origin.Network), outcome)
  }

  // --- HttpResponse.toOutcome ---------------------------------------------------------------

  @Test
  fun successStatusRunsMapSuccess() = runTest {
    val response =
      respondingWith(HttpStatusCode.OK, "hello", expectSuccess = false).get("https://example.test")

    assertEquals(Outcome.Data("hello", Origin.Network), response.toOutcome { bodyAsText() })
  }

  @Test
  fun notFoundWithoutExpectSuccessIsHttpErrorAndSkipsMapSuccess() = runTest {
    val response =
      respondingWith(HttpStatusCode.NotFound, expectSuccess = false).get("https://example.test")
    var mapped = false

    val outcome =
      response.toOutcome(onException = { caught += it }) {
        mapped = true
        bodyAsText()
      }

    assertEquals(Outcome.Error(DataError.Http(404), Origin.Network), outcome)
    assertEquals(false, mapped)
    assertTrue(caught.isEmpty(), "a response that threw nothing reached onException: $caught")
  }

  @Test
  fun xrpcEnvelopeIsDecodedIntoApiError() = runTest {
    val response =
      respondingWith(HttpStatusCode.BadRequest, XRPC_ERROR, expectSuccess = false)
        .get("https://example.test")

    val outcome = response.toOutcome(errorBody = ::decodeXrpcError) { bodyAsText() }

    assertEquals(Outcome.Error(BAD_CURSOR, Origin.Network), outcome)
  }

  @Test
  fun errorBodyReturningNullFallsBackToHttp() = runTest {
    val response =
      respondingWith(HttpStatusCode.BadRequest, "not an envelope", expectSuccess = false)
        .get("https://example.test")

    val outcome = response.toOutcome(errorBody = ::decodeXrpcError) { bodyAsText() }

    assertEquals(Outcome.Error(DataError.Http(400), Origin.Network), outcome)
  }

  @Test
  fun throwingErrorBodyFallsBackToHttpAndIsNotReported() = runTest {
    val response =
      respondingWith(HttpStatusCode.InternalServerError, "<html>", expectSuccess = false)
        .get("https://example.test")

    val outcome =
      response.toOutcome(onException = { caught += it }, errorBody = { error("not JSON") }) {
        bodyAsText()
      }

    assertEquals(Outcome.Error(DataError.Http(500), Origin.Network), outcome)
    assertTrue(caught.isEmpty(), "the hook's failure reached onException: $caught")
  }

  @Test
  fun failingMapSuccessIsClassified() = runTest {
    val response =
      respondingWith(HttpStatusCode.OK, "{}", expectSuccess = false).get("https://example.test")

    val outcome = response.toOutcome(onException = { caught += it }) { body<Unregistered>() }

    assertEquals(Outcome.Error(DataError.Serialization, Origin.Network), outcome)
    assertEquals(1, caught.size)
  }

  // --- httpOutcomeFlow ------------------------------------------------------------------------

  @Test
  fun flowEmitsLoadingThenOneOutcomePerCollection() = runTest {
    var requests = 0
    val client =
      client(expectSuccess = true) {
        requests++
        respond("hello #$requests", HttpStatusCode.OK)
      }
    val outcomes = httpOutcomeFlow { client.get("https://example.test").bodyAsText() }

    outcomes.test {
      assertEquals(Outcome.Loading, awaitItem())
      assertEquals(Outcome.Data("hello #1", Origin.Network), awaitItem())
      awaitComplete()
    }
    outcomes.test {
      assertEquals(Outcome.Loading, awaitItem())
      assertEquals(Outcome.Data("hello #2", Origin.Network), awaitItem())
      awaitComplete()
    }
    assertEquals(2, requests)
  }

  @Test
  fun flowEmitsTheFailureAsAnError() = runTest {
    val client = respondingWith(HttpStatusCode.NotFound, expectSuccess = true)

    httpOutcomeFlow { client.get("https://example.test").bodyAsText() }
      .test {
        assertEquals(Outcome.Loading, awaitItem())
        assertIs<Outcome.Error>(awaitItem())
        awaitComplete()
      }
  }

  /** No converter is installed for this, so reading a body as it always fails. */
  private class Unregistered

  private companion object {
    const val XRPC_ERROR = """{"error":"InvalidRequest","message":"bad cursor"}"""
    val BAD_CURSOR = DataError.Api(listOf("bad cursor"), "InvalidRequest")

    // A stand-in for `body<XrpcError>()`. The module builds without the serialization plugin, so
    // the test decodes the envelope by hand.
    private val envelope = Regex(""""error":"([^"]*)","message":"([^"]*)"""")

    suspend fun decodeXrpcError(response: HttpResponse): DataError? =
      envelope.find(response.bodyAsText())?.let { match ->
        val (error, message) = match.destructured
        DataError.Api(listOf(message), error)
      }
  }
}
