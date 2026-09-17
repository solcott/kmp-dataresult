package io.github.solcott.dataresult.store5

import app.cash.turbine.test
import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin

class StoreOutcomeTest {

  private val fetcher = StoreReadResponseOrigin.Fetcher()

  // --- Response -> Outcome --------------------------------------------------------------------

  @Test
  fun initialAndLoadingBothBecomeLoading() {
    assertEquals(Outcome.Loading, StoreReadResponse.Initial.toOutcomeOrNull())
    assertEquals(Outcome.Loading, StoreReadResponse.Loading(fetcher).toOutcomeOrNull())
  }

  @Test
  fun noNewDataIsDropped() {
    assertNull(StoreReadResponse.NoNewData(fetcher).toOutcomeOrNull())
  }

  @Test
  fun dataCarriesItsValue() {
    val response = StoreReadResponse.Data("value", fetcher)

    assertEquals(Outcome.Data("value", Origin.Network), response.toOutcomeOrNull())
  }

  // --- Origin collapsing ----------------------------------------------------------------------

  @Test
  fun onlyAFetcherResponseIsNetwork() {
    assertEquals(
      Outcome.Data("v", Origin.Network),
      StoreReadResponse.Data("v", StoreReadResponseOrigin.Fetcher("primary")).toOutcomeOrNull(),
    )
  }

  @Test
  fun cacheAndSourceOfTruthAreBothLocal() {
    assertEquals(
      Outcome.Data("v", Origin.Cache),
      StoreReadResponse.Data("v", StoreReadResponseOrigin.Cache).toOutcomeOrNull(),
    )
    assertEquals(
      Outcome.Data("v", Origin.Cache),
      StoreReadResponse.Data("v", StoreReadResponseOrigin.SourceOfTruth).toOutcomeOrNull(),
    )
  }

  // --- Error classification -------------------------------------------------------------------

  @Test
  fun exceptionErrorRetainsCauseAndMessage() {
    val cause = IllegalStateException("boom")

    val outcome = StoreReadResponse.Error.Exception(cause, fetcher).toOutcomeOrNull()

    assertEquals(
      Outcome.Error(DataError.Unknown(cause = cause, message = "boom"), Origin.Network),
      outcome,
    )
  }

  @Test
  fun messageErrorBecomesAnApiError() {
    val outcome = StoreReadResponse.Error.Message("nope", fetcher).toOutcomeOrNull()

    assertEquals(Outcome.Error(DataError.Api(persistentListOf("nope")), Origin.Network), outcome)
  }

  @Test
  fun customThrowableErrorKeepsItsCause() {
    val cause = IllegalArgumentException("bad")

    val outcome = StoreReadResponse.Error.Custom(cause, fetcher).toOutcomeOrNull()

    assertEquals(
      Outcome.Error(DataError.Unknown(cause = cause, message = cause.toString()), Origin.Network),
      outcome,
    )
  }

  @Test
  fun customNonThrowableErrorHasNoCause() {
    val outcome =
      StoreReadResponse.Error.Custom(404, StoreReadResponseOrigin.Cache).toOutcomeOrNull()

    assertEquals(
      Outcome.Error(DataError.Unknown(cause = null, message = "404"), Origin.Cache),
      outcome,
    )
  }

  @Test
  fun errorsKeepTheirOrigin() {
    val outcome =
      StoreReadResponse.Error.Message("stale", StoreReadResponseOrigin.SourceOfTruth)
        .toOutcomeOrNull()

    assertEquals(Outcome.Error(DataError.Api(persistentListOf("stale")), Origin.Cache), outcome)
  }

  // --- Flow -----------------------------------------------------------------------------------

  @Test
  fun asOutcomesDropsNoNewDataAndKeepsOrder() = runTest {
    val responses =
      flowOf(
        StoreReadResponse.Initial,
        StoreReadResponse.Data("stale", StoreReadResponseOrigin.SourceOfTruth),
        StoreReadResponse.Loading(fetcher),
        StoreReadResponse.NoNewData(fetcher),
        StoreReadResponse.Data("fresh", fetcher),
      )

    responses.asOutcomes().test {
      assertEquals(Outcome.Loading, awaitItem())
      assertEquals(Outcome.Data("stale", Origin.Cache), awaitItem())
      assertEquals(Outcome.Loading, awaitItem())
      assertEquals(Outcome.Data("fresh", Origin.Network), awaitItem())
      awaitComplete()
    }
  }

  // --- Cache misses ---------------------------------------------------------------------------
  //
  // Tests that something is *not* held use a stream that never completes, as a real Store stream
  // never does: completion releases a held value, which would hide a value held by mistake. Tests
  // that a held value is *discarded* use one that completes, for the opposite reason.

  private val sourceOfTruth = StoreReadResponseOrigin.SourceOfTruth

  private fun openStream(
    vararg responses: StoreReadResponse<List<String>>
  ): Flow<StoreReadResponse<List<String>>> = flow {
    responses.forEach { emit(it) }
    awaitCancellation()
  }

  private fun Flow<StoreReadResponse<List<String>>>.whileFetching() =
    asOutcomes(fetching = true) { it.isEmpty() }

  @Test
  fun anEmptyFirstReadIsHeldWhileTheFetchIsInFlight() = runTest {
    openStream(
        StoreReadResponse.Data(emptyList(), sourceOfTruth),
        StoreReadResponse.Loading(fetcher),
      )
      .whileFetching()
      .test {
        assertEquals(Outcome.Loading, awaitItem())
        expectNoEvents()
        cancelAndIgnoreRemainingEvents()
      }
  }

  @Test
  fun fetchedDataReplacesAHeldMiss() = runTest {
    flowOf<StoreReadResponse<List<String>>>(
        StoreReadResponse.Data(emptyList(), sourceOfTruth),
        StoreReadResponse.Loading(fetcher),
        StoreReadResponse.Data(listOf("fresh"), fetcher),
      )
      .whileFetching()
      .test {
        assertEquals(Outcome.Loading, awaitItem())
        assertEquals(Outcome.Data(listOf("fresh"), Origin.Network), awaitItem())
        awaitComplete()
      }
  }

  @Test
  fun anEmptyFetchIsAnAnswer() = runTest {
    // Held back only because it came from cache: the same empty list from the network is real.
    flowOf<StoreReadResponse<List<String>>>(
        StoreReadResponse.Data(emptyList(), sourceOfTruth),
        StoreReadResponse.Loading(fetcher),
        StoreReadResponse.Data(emptyList(), fetcher),
      )
      .whileFetching()
      .test {
        assertEquals(Outcome.Loading, awaitItem())
        assertEquals(Outcome.Data(emptyList<String>(), Origin.Network), awaitItem())
        awaitComplete()
      }
  }

  @Test
  fun aFailedFetchDiscardsAHeldMiss() = runTest {
    // Offline on a first visit: the consumer gets the failure, never an empty result before it.
    flowOf<StoreReadResponse<List<String>>>(
        StoreReadResponse.Data(emptyList(), sourceOfTruth),
        StoreReadResponse.Loading(fetcher),
        StoreReadResponse.Error.Message("down", fetcher),
      )
      .whileFetching()
      .test {
        assertEquals(Outcome.Loading, awaitItem())
        assertEquals(
          Outcome.Error(DataError.Api(persistentListOf("down")), Origin.Network),
          awaitItem(),
        )
        awaitComplete()
      }
  }

  @Test
  fun noNewDataReleasesAHeldMiss() = runTest {
    openStream(
        StoreReadResponse.Data(emptyList(), sourceOfTruth),
        StoreReadResponse.Loading(fetcher),
        StoreReadResponse.NoNewData(fetcher),
      )
      .whileFetching()
      .test {
        assertEquals(Outcome.Loading, awaitItem())
        assertEquals(Outcome.Data(emptyList<String>(), Origin.Cache), awaitItem())
        cancelAndIgnoreRemainingEvents()
      }
  }

  @Test
  fun completingReleasesAHeldMiss() = runTest {
    // A source that ends without fetching must still answer, or the consumer's spinner would hang.
    flowOf(StoreReadResponse.Data(emptyList<String>(), sourceOfTruth)).whileFetching().test {
      assertEquals(Outcome.Data(emptyList<String>(), Origin.Cache), awaitItem())
      awaitComplete()
    }
  }

  @Test
  fun aCacheErrorDoesNotSettleTheFetch() = runTest {
    openStream(
        StoreReadResponse.Data(emptyList(), sourceOfTruth),
        StoreReadResponse.Error.Message("read", sourceOfTruth),
        StoreReadResponse.Data(emptyList(), sourceOfTruth),
      )
      .whileFetching()
      .test {
        assertEquals(
          Outcome.Error(DataError.Api(persistentListOf("read")), Origin.Cache),
          awaitItem(),
        )
        expectNoEvents()
        cancelAndIgnoreRemainingEvents()
      }
  }

  @Test
  fun aNonEmptyFirstReadIsNotHeld() = runTest {
    // Stale-while-revalidate: cached content shows at once, under the fetch that follows it.
    openStream(StoreReadResponse.Data(listOf("stale"), sourceOfTruth)).whileFetching().test {
      assertEquals(Outcome.Data(listOf("stale"), Origin.Cache), awaitItem())
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun onlyAFirstReadCanBeAMiss() = runTest {
    // After something has shown, an empty read is a real change -- a local delete, say.
    openStream(
        StoreReadResponse.Data(listOf("a"), sourceOfTruth),
        StoreReadResponse.Data(emptyList(), sourceOfTruth),
      )
      .whileFetching()
      .test {
        assertEquals(Outcome.Data(listOf("a"), Origin.Cache), awaitItem())
        assertEquals(Outcome.Data(emptyList<String>(), Origin.Cache), awaitItem())
        cancelAndIgnoreRemainingEvents()
      }
  }

  @Test
  fun withoutAFetchNothingIsHeld() = runTest {
    // No fetch is coming to answer instead, so the cache is the answer.
    openStream(StoreReadResponse.Data(emptyList(), sourceOfTruth))
      .asOutcomes(isEmpty = { it.isEmpty() })
      .test {
        assertEquals(Outcome.Data(emptyList<String>(), Origin.Cache), awaitItem())
        cancelAndIgnoreRemainingEvents()
      }
  }
}
