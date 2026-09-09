package io.github.solcott.dataresult

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OutcomeTest {

  // --- Accessors ------------------------------------------------------------------------------

  @Test
  fun accessorsReportLoading() {
    val outcome: Outcome<String> = Outcome.Loading

    assertTrue(outcome.isLoading)
    assertNull(outcome.dataOrNull())
    assertNull(outcome.errorOrNull)
  }

  @Test
  fun accessorsReportData() {
    val outcome: Outcome<String> = Outcome.Data("a", Origin.Cache)

    assertFalse(outcome.isLoading)
    assertEquals("a", outcome.dataOrNull())
    assertNull(outcome.errorOrNull)
  }

  @Test
  fun accessorsReportError() {
    val outcome: Outcome<String> = Outcome.Error(DataError.Http(500), Origin.Network)

    assertFalse(outcome.isLoading)
    assertNull(outcome.dataOrNull())
    assertEquals(DataError.Http(500), outcome.errorOrNull)
  }

  @Test
  fun dataOrNullDistinguishesAbsentFromNull() {
    // A source whose value type is nullable can legitimately carry null, and that is not the same
    // as having no value at all -- but `dataOrNull` cannot tell the caller which it was.
    val outcome: Outcome<String?> = Outcome.Data(null, Origin.Network)

    assertNull(outcome.dataOrNull())
    assertFalse(outcome.isLoading)
  }

  // --- mapData --------------------------------------------------------------------------------

  @Test
  fun mapDataTransformsTheValueAndKeepsTheOrigin() {
    val outcome: Outcome<List<String>> = Outcome.Data(listOf("a", "b"), Origin.Cache)

    assertEquals(Outcome.Data(2, Origin.Cache), outcome.mapData { it.size })
  }

  @Test
  fun mapDataPassesLoadingThrough() {
    val outcome: Outcome<List<String>> = Outcome.Loading

    assertSame(Outcome.Loading, outcome.mapData { it.size })
  }

  @Test
  fun mapDataPassesErrorThroughUnchanged() {
    val error = Outcome.Error(DataError.Http(404), Origin.Network)

    val mapped: Outcome<Int> = error.mapData<List<String>, Int> { it.size }

    // Not merely equal: an error carries no value to transform, so there is nothing to rebuild.
    assertSame(error, mapped)
  }

  @Test
  fun mapDataIsNotCalledForANonDataOutcome() {
    var calls = 0
    val transform: (String) -> String = {
      calls++
      it
    }

    Outcome.Loading.mapData(transform)
    Outcome.Error(DataError.Network, Origin.Network).mapData(transform)

    assertEquals(0, calls)
  }
}
