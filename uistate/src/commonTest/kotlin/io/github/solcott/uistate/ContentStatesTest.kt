package io.github.solcott.uistate

import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import io.github.solcott.dataresult.OutcomeGroup
import io.github.solcott.dataresult.Outcomes2
import io.github.solcott.dataresult.Outcomes3
import io.github.solcott.dataresult.Outcomes4
import io.github.solcott.dataresult.Outcomes5
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentStatesTest {

  private val names = Outcome.Data(listOf("a"), Origin.Cache)
  private val count = Outcome.Data(3, Origin.Network)
  private val failed = Outcome.Error(DataError.Network, Origin.Network)

  private fun loaded() =
    contentStatesOf(emptyList<String>(), 0).applyEmission(Outcomes2(names, count))

  // --- The fold, per source -------------------------------------------------------------------

  @Test
  fun startsNotLoadedHoldingEachPlaceholder() {
    val states = contentStatesOf(emptyList<String>(), 0)

    assertEquals(emptyList(), states.first.data)
    assertEquals(0, states.second.data)
    assertFalse(states.hasLoaded)
    assertTrue(states.isAllLoading)
  }

  @Test
  fun eachOutcomeFoldsIntoItsOwnSource() {
    val states = loaded()

    assertEquals(listOf("a"), states.first.data)
    assertEquals(Origin.Cache, states.first.origin)
    assertEquals(3, states.second.data)
    assertEquals(Origin.Network, states.second.origin)
    assertTrue(states.hasLoaded)
    assertFalse(states.isAnyLoading)
  }

  @Test
  fun aSourceThatAnswersFirstShowsBeforeTheOthers() {
    // The case the group exists for: recents from disk, while suggestions are still on the network.
    val states =
      contentStatesOf(emptyList<String>(), 0).applyEmission(Outcomes2(names, Outcome.Loading))

    assertTrue(states.first.hasLoaded)
    assertFalse(states.second.hasLoaded)
    assertFalse(states.hasLoaded)
  }

  @Test
  fun oneSourceLoadingLeavesTheOtherAlone() {
    val states = loaded().applyEmission(Outcomes2(Outcome.Loading, count))

    assertTrue(states.first.isLoading)
    assertEquals(listOf("a"), states.first.data, "a reload keeps the data it had")
    assertEquals(LoadStatus.Idle, states.second.status)
    assertTrue(states.isAnyLoading)
    assertFalse(states.isAllLoading)
  }

  @Test
  fun oneSourceFailingLeavesTheOtherAlone() {
    val states = loaded().applyEmission(Outcomes2(names, failed))

    assertEquals(3, states.second.data, "a failure keeps the data it had")
    assertEquals(DataError.Network, states.second.errorOrNull)
    assertEquals(LoadStatus.Idle, states.first.status)
    assertTrue(states.hasAnyError)
    assertFalse(states.hasAllErrors)
    assertEquals(listOf<DataError>(DataError.Network), states.errors)
    assertEquals(DataError.Network, states.errorOrNull)
  }

  @Test
  fun reloadingAndSettledReachEverySource() {
    val reloading = loaded().reloading()

    assertTrue(reloading.isAllLoading)
    assertEquals(listOf("a"), reloading.first.data)
    assertEquals(3, reloading.second.data)
    assertFalse(reloading.settled().isAnyLoading)
  }

  // --- Collapsing into one --------------------------------------------------------------------

  @Test
  fun combinedStatusFollowsThePrecedence() {
    val states = loaded().applyEmission(Outcomes2(Outcome.Loading, failed))

    assertEquals(LoadStatus.Failed(DataError.Network), states.combinedStatus())
    assertEquals(LoadStatus.Loading, states.combinedStatus(StatusPrecedence.LoadingFirst))
  }

  @Test
  fun toContentStateAgreesWithCombine() {
    val states = loaded().applyEmission(Outcomes2(Outcome.Loading, count))

    assertEquals(
      combine(states.first, states.second) { n, c -> "${n.size}/$c" },
      states.toContentState { n, c -> "${n.size}/$c" },
    )
  }

  // --- Every arity ----------------------------------------------------------------------------

  @Test
  fun everyArityFoldsEachSourceAndCollapses() {
    val one = Outcome.Data(1, Origin.Network)

    val two = contentStatesOf(0, 0).applyEmission(Outcomes2(one, one))
    val three = contentStatesOf(0, 0, 0).applyEmission(Outcomes3(one, one, one))
    val four = contentStatesOf(0, 0, 0, 0).applyEmission(Outcomes4(one, one, one, one))
    val five = contentStatesOf(0, 0, 0, 0, 0).applyEmission(Outcomes5(one, one, one, one, one))

    assertEquals(List(2) { 1 }, two.states.map { it.data })
    assertEquals(List(3) { 1 }, three.states.map { it.data })
    assertEquals(List(4) { 1 }, four.states.map { it.data })
    assertEquals(List(5) { 1 }, five.states.map { it.data })
    assertEquals(2, two.toContentState { a, b -> a + b }.data)
    assertEquals(3, three.toContentState { a, b, c -> a + b + c }.data)
    assertEquals(4, four.toContentState { a, b, c, d -> a + b + c + d }.data)
    assertEquals(5, five.toContentState { a, b, c, d, e -> a + b + c + d + e }.data)
  }

  @Test
  fun everyArityReturnsItsOwnType() {
    // The producers in uistate-compose cast what these return back to the type they were called on.
    // That cast is only safe while every subclass returns itself, and this is what says it does.
    val one = Outcome.Data(1, Origin.Network)

    assertReturnsItsOwnType(contentStatesOf(0, 0), Outcomes2(one, one))
    assertReturnsItsOwnType(contentStatesOf(0, 0, 0), Outcomes3(one, one, one))
    assertReturnsItsOwnType(contentStatesOf(0, 0, 0, 0), Outcomes4(one, one, one, one))
    assertReturnsItsOwnType(contentStatesOf(0, 0, 0, 0, 0), Outcomes5(one, one, one, one, one))
  }

  private fun <O : OutcomeGroup> assertReturnsItsOwnType(group: ContentStateGroup<O>, outcomes: O) {
    assertEquals(group::class, group.applyEmission(outcomes)::class)
    assertEquals(group::class, group.reloading()::class)
    assertEquals(group::class, group.settled()::class)
  }
}
