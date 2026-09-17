package io.github.solcott.dataresult.apollo

import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.CompiledField
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Error as GraphQLError
import com.apollographql.apollo.api.ObjectType
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import com.apollographql.apollo.exception.ApolloOfflineException
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.apollo.exception.DefaultApolloException
import com.apollographql.apollo.exception.HttpCacheMissException
import com.apollographql.apollo.exception.JsonDataException
import com.apollographql.apollo.exception.JsonEncodingException
import com.apollographql.cache.normalized.CacheInfo
import com.benasher44.uuid.uuid4
import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Covers the classification this module exists for: how an [ApolloResponse] becomes an [Outcome],
 * and how Apollo's exception hierarchy collapses into [DataError].
 */
class ApolloOutcomeTest {

  private val caught = mutableListOf<Throwable>()

  private suspend fun ApolloResponse<FakeQuery.Data>.outcomes(): List<Outcome<String>> =
    flowOf(this).mapToOutcome(onException = { caught += it }) { value }.toList()

  // --- Origin tagging -------------------------------------------------------------------------

  @Test
  fun dataIsTaggedNetworkWhenNotFromCache() = runTest {
    assertEquals(listOf(Outcome.Data("hello", Origin.Network)), response(data = data).outcomes())
  }

  @Test
  fun dataIsTaggedCacheWhenFromCache() = runTest {
    val outcomes = response(data = data, fromCache = true).outcomes()

    assertEquals(listOf(Outcome.Data("hello", Origin.Cache)), outcomes)
  }

  // --- Error classification -------------------------------------------------------------------

  @Test
  fun graphQlErrorsMapToApiError() = runTest {
    val response =
      response(
        data = data,
        errors = listOf(GraphQLError.Builder("boom").build(), GraphQLError.Builder("bang").build()),
      )

    assertEquals(
      listOf(Outcome.Error(DataError.Api(persistentListOf("boom", "bang")), Origin.Network)),
      response.outcomes(),
    )
  }

  @Test
  fun graphQlErrorsKeepTheCacheOrigin() = runTest {
    val response = response(errors = listOf(GraphQLError.Builder("boom").build()), fromCache = true)

    assertEquals(
      listOf(Outcome.Error(DataError.Api(persistentListOf("boom")), Origin.Cache)),
      response.outcomes(),
    )
  }

  @Test
  fun networkAndOfflineExceptionsMapToNetworkError() = runTest {
    assertEquals(
      listOf(Outcome.Error(DataError.Network, Origin.Network)),
      response(exception = ApolloNetworkException("offline")).outcomes(),
    )
    assertEquals(
      listOf(Outcome.Error(DataError.Network, Origin.Network)),
      response(exception = ApolloOfflineException()).outcomes(),
    )
  }

  @Test
  fun httpExceptionCarriesTheStatusCode() = runTest {
    val exception =
      ApolloHttpException(statusCode = 503, headers = emptyList(), body = null, message = "nope")

    assertEquals(
      listOf(Outcome.Error(DataError.Http(503), Origin.Network)),
      response(exception = exception).outcomes(),
    )
  }

  @Test
  fun jsonExceptionsMapToSerializationError() = runTest {
    assertEquals(
      listOf(Outcome.Error(DataError.Serialization, Origin.Network)),
      response(exception = JsonDataException("bad shape")).outcomes(),
    )
    assertEquals(
      listOf(Outcome.Error(DataError.Serialization, Origin.Network)),
      response(exception = JsonEncodingException("bad json")).outcomes(),
    )
  }

  @Test
  fun unclassifiedExceptionRetainsCauseAndMessage() = runTest {
    val exception = DefaultApolloException("something else entirely")

    assertEquals(
      listOf(
        Outcome.Error(
          DataError.Unknown(cause = exception, message = "something else entirely"),
          Origin.Network,
        )
      ),
      response(exception = exception).outcomes(),
    )
  }

  // --- Cache misses are dropped, not surfaced -------------------------------------------------

  @Test
  fun cacheMissesAreDropped() = runTest {
    val miss = CacheMissException(key = "Thing:1", fieldName = "value")

    assertTrue(response(exception = miss).outcomes().isEmpty())
    assertTrue(response(exception = HttpCacheMissException("not cached")).outcomes().isEmpty())
  }

  @Test
  fun errorsWinOverACacheMissException() = runTest {
    // hasErrors() is checked before the cache-miss drop, so this must surface rather than vanish.
    val response =
      response(
        errors = listOf(GraphQLError.Builder("boom").build()),
        exception = CacheMissException(key = "Thing:1", fieldName = "value"),
      )

    assertEquals(
      listOf(Outcome.Error(DataError.Api(persistentListOf("boom")), Origin.Network)),
      response.outcomes(),
    )
  }

  // --- What reaches the onException callback ---------------------------------------------------

  @Test
  fun unclassifiedExceptionReachesTheCallback() = runTest {
    val exception = DefaultApolloException("something else entirely")

    response(exception = exception).outcomes()

    assertEquals(listOf<Throwable>(exception), caught)
  }

  @Test
  fun successCacheMissAndGraphQlErrorsDoNotReachTheCallback() = runTest {
    response(data = data).outcomes()
    response(exception = CacheMissException(key = "Thing:1", fieldName = "value")).outcomes()
    response(errors = listOf(GraphQLError.Builder("boom").build())).outcomes()

    // A cache miss is an expected part of a cache-then-network policy, and a GraphQL error is
    // already surfaced as DataError.Api — neither is worth reporting as a failure.
    assertTrue(caught.isEmpty())
  }

  // --- Fixtures --------------------------------------------------------------------------------

  private val data = FakeQuery.Data("hello")

  /**
   * Builds a response the way Apollo would. [CacheInfo] is what `isFromCache` reads, so setting it
   * here exercises [Origin] tagging without a real normalized cache.
   */
  private fun response(
    data: FakeQuery.Data? = null,
    errors: List<GraphQLError>? = null,
    exception: ApolloException? = null,
    fromCache: Boolean = false,
  ): ApolloResponse<FakeQuery.Data> =
    ApolloResponse.Builder(FakeQuery(), uuid4())
      .data(data)
      .errors(errors)
      .exception(exception)
      .addExecutionContext(CacheInfo.Builder().fromCache(fromCache).build())
      .build()
}

/**
 * A stand-in for a generated operation. Only the pieces [ApolloResponse] itself touches need to
 * work; nothing here is ever executed against a server or a cache.
 */
private class FakeQuery : Operation<FakeQuery.Data> {
  data class Data(val value: String) : Operation.Data

  override fun document() = "query Fake { value }"

  override fun name() = "Fake"

  override fun id() = "fake"

  override fun adapter(): Adapter<Data> =
    object : Adapter<Data> {
      override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters): Data =
        throw UnsupportedOperationException("not parsed in these tests")

      override fun toJson(
        writer: JsonWriter,
        customScalarAdapters: CustomScalarAdapters,
        value: Data,
      ) = throw UnsupportedOperationException("not serialized in these tests")
    }

  override fun serializeVariables(
    writer: JsonWriter,
    customScalarAdapters: CustomScalarAdapters,
    withDefaultValues: Boolean,
  ) = Unit

  override fun rootField(): CompiledField =
    CompiledField.Builder("data", ObjectType.Builder("Query").build()).build()
}
