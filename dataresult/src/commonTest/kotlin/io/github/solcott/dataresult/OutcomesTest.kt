package io.github.solcott.dataresult

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf

class OutcomesTest {

  // One of each shape, with different value types: holding differently-typed outcomes together is
  // the whole point.
  private val loading: Outcome<Int> = Outcome.Loading
  private val data = Outcome.Data("a", Origin.Cache)
  private val networkError = Outcome.Error(DataError.Network, Origin.Network)
  private val httpError = Outcome.Error(DataError.Http(500), Origin.Network)

  // --- Each outcome on its own ----------------------------------------------------------------

  @Test
  fun eachOutcomeIsReachableByPositionAndByDestructuring() {
    val outcomes = Outcomes3(loading, data, networkError)
    val (first, second, third) = outcomes

    assertSame(loading, first)
    assertSame(data, second)
    assertSame(networkError, third)
    assertSame(data, outcomes.second)
  }

  @Test
  fun everyArityListsItsOutcomesInArgumentOrder() {
    assertEquals(persistentListOf(data, loading), Outcomes2(data, loading).outcomes)
    assertEquals(
      persistentListOf(data, loading, httpError),
      Outcomes3(data, loading, httpError).outcomes,
    )
    assertEquals(
      persistentListOf(data, loading, httpError, networkError),
      Outcomes4(data, loading, httpError, networkError).outcomes,
    )
    assertEquals(
      persistentListOf(data, loading, httpError, networkError, data),
      Outcomes5(data, loading, httpError, networkError, data).outcomes,
    )
  }

  // --- Loading --------------------------------------------------------------------------------

  @Test
  fun isAnyLoadingNeedsOneSourceInFlight() {
    assertTrue(Outcomes2(data, loading).isAnyLoading)
    assertFalse(Outcomes2(data, networkError).isAnyLoading)
  }

  @Test
  fun isAllLoadingNeedsEverySourceInFlight() {
    assertTrue(Outcomes2(loading, Outcome.Loading).isAllLoading)
    assertFalse(Outcomes2(loading, data).isAllLoading)
  }

  // --- Errors ---------------------------------------------------------------------------------

  @Test
  fun errorsAreReportedInArgumentOrder() {
    val outcomes = Outcomes4(data, httpError, loading, networkError)

    assertTrue(outcomes.hasAnyError)
    assertFalse(outcomes.hasAllErrors)
    assertEquals(listOf(DataError.Http(500), DataError.Network), outcomes.errors)
    assertEquals(DataError.Http(500), outcomes.errorOrNull)
  }

  @Test
  fun hasAllErrorsNeedsEverySourceFailed() {
    assertTrue(Outcomes2(networkError, httpError).hasAllErrors)
  }

  @Test
  fun noFailureMeansNoErrors() {
    val outcomes = Outcomes2(data, loading)

    assertFalse(outcomes.hasAnyError)
    assertTrue(outcomes.errors.isEmpty())
    assertNull(outcomes.errorOrNull)
  }

  @Test
  fun loadingAndFailureAreIndependent() {
    // Both at once: a screen may want to show a refresh indicator and an error together.
    val outcomes = Outcomes2(loading, networkError)

    assertTrue(outcomes.isAnyLoading)
    assertTrue(outcomes.hasAnyError)
  }

  // --- Data -----------------------------------------------------------------------------------

  @Test
  fun hasAllDataNeedsAValueFromEverySource() {
    assertTrue(Outcomes2(data, Outcome.Data(1, Origin.Network)).hasAllData)
    assertFalse(Outcomes2(data, loading).hasAllData)
    assertFalse(Outcomes2(data, networkError).hasAllData)
  }
}
