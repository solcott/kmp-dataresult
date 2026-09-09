package io.github.solcott.dataresult.store5

import app.cash.turbine.test
import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    assertEquals(Outcome.Error(DataError.Api(listOf("nope")), Origin.Network), outcome)
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

    assertEquals(Outcome.Error(DataError.Api(listOf("stale")), Origin.Cache), outcome)
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
}
