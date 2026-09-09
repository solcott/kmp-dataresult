package io.github.solcott.dataresult

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class OutcomeTest {

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
