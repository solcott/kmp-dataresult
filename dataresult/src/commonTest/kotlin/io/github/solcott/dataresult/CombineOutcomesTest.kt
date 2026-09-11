package io.github.solcott.dataresult

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest

class CombineOutcomesTest {

  private val a = Outcome.Data("a", Origin.Cache)
  private val one = Outcome.Data(1, Origin.Network)

  @Test
  fun theGroupEmitsBeforeASlowSourceAnswers() = runTest {
    // The seed. Plain `combine` would wait here until `slow` emitted, holding `a` back with it.
    val slow = MutableSharedFlow<Outcome<Int>>(replay = 1)

    combineOutcomes(flowOf(a), slow).test {
      val beforeSlowAnswers = awaitItemWhere { it.first == a }
      assertEquals(Outcome.Loading, beforeSlowAnswers.second)

      slow.emit(one)

      assertEquals(Outcomes2(a, one), awaitItem())
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun eachEmissionLandsInItsSourcesPosition() = runTest {
    val first = MutableSharedFlow<Outcome<String>>(replay = 1)
    val second = MutableSharedFlow<Outcome<Int>>(replay = 1)
    val failed = Outcome.Error(DataError.Network, Origin.Network)

    combineOutcomes(first, second).test {
      assertEquals(Outcomes2(Outcome.Loading, Outcome.Loading), awaitItem())

      second.emit(one)
      assertEquals(Outcomes2(Outcome.Loading, one), awaitItem())

      first.emit(failed)
      assertEquals(Outcomes2(failed, one), awaitItem())
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun everyArityCombinesInArgumentOrder() = runTest {
    val b = Outcome.Data(true, Origin.Network)

    assertEquals(Outcomes2(a, one), combineOutcomes(flowOf(a), flowOf(one)).last())
    assertEquals(
      Outcomes3(a, one, b),
      combineOutcomes(flowOf(a), flowOf(one), flowOf(b)).last(),
    )
    assertEquals(
      Outcomes4(a, one, b, a),
      combineOutcomes(flowOf(a), flowOf(one), flowOf(b), flowOf(a)).last(),
    )
    assertEquals(
      Outcomes5(a, one, b, a, one),
      combineOutcomes(flowOf(a), flowOf(one), flowOf(b), flowOf(a), flowOf(one)).last(),
    )
  }

  @Test
  fun aSourceThatThrowsCancelsTheGroup() = runTest {
    // Documented rather than handled: each source maps its own failures to Outcome.Error, where
    // its Origin is known.
    val throwing = flow<Outcome<Int>> { throw IllegalStateException("boom") }

    assertFailsWith<IllegalStateException> { combineOutcomes(flowOf(a), throwing).collect() }
  }
}

private suspend fun <T> ReceiveTurbine<T>.awaitItemWhere(predicate: (T) -> Boolean): T {
  while (true) {
    val item = awaitItem()
    if (predicate(item)) return item
  }
}
