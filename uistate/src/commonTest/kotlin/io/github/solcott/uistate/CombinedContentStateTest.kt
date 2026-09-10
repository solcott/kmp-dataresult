package io.github.solcott.uistate

import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CombinedContentStateTest {

  // One of each shape a source can be in. The data types differ on purpose: combining
  // differently-typed states is the whole point.
  private val notYetLoaded = ContentState(emptyList<String>())
  private val loadedFromNetwork = ContentState(3, Origin.Network, LoadStatus.Idle)
  private val loadedFromCache = ContentState(true, Origin.Cache, LoadStatus.Idle)
  private val failedNetwork =
    ContentState(0.5, Origin.Network, LoadStatus.Failed(DataError.Network))
  private val failedHttp = ContentState("x", Origin.Cache, LoadStatus.Failed(DataError.Http(500)))
  private val refreshing = ContentState(listOf(1), Origin.Cache, LoadStatus.Loading)

  private val everyShape =
    listOf(
      notYetLoaded,
      loadedFromNetwork,
      loadedFromCache,
      failedNetwork,
      failedHttp,
      refreshing,
    )

  // --- The aggregate over a list ---

  @Test
  fun isLoadingIsTrueIfAnySourceIsInFlight() {
    assertTrue(listOf(loadedFromNetwork, refreshing).isLoading)
    assertFalse(listOf(loadedFromNetwork, loadedFromCache).isLoading)
  }

  @Test
  fun errorOrNullReportsTheFirstFailureInListOrder() {
    assertEquals(
      DataError.Network,
      listOf(loadedFromNetwork, failedNetwork, failedHttp).errorOrNull,
    )
    assertEquals(DataError.Http(500), listOf(failedHttp, failedNetwork).errorOrNull)
    assertNull(listOf(loadedFromNetwork, loadedFromCache).errorOrNull)
  }

  @Test
  fun loadingAndFailureAreIndependentInTheAggregate() {
    // No precedence applies here: both are true at once, and a screen may want to show both.
    val sources = listOf(failedNetwork, refreshing)

    assertTrue(sources.isLoading)
    assertEquals(DataError.Network, sources.errorOrNull)
  }

  @Test
  fun hasLoadedNeedsEverySource() {
    assertTrue(listOf(loadedFromNetwork, loadedFromCache, refreshing).hasLoaded)
    assertFalse(listOf(loadedFromNetwork, notYetLoaded).hasLoaded)
  }

  @Test
  fun anEmptyListIsSettledAndVacuouslyLoaded() {
    val none = emptyList<ContentState<*>>()

    assertFalse(none.isLoading)
    assertNull(none.errorOrNull)
    assertTrue(none.hasLoaded)
    assertEquals(LoadStatus.Idle, none.combinedStatus())
  }

  // --- combinedStatus ---

  @Test
  fun failedFirstReportsTheFailureOverALoadingSource() {
    assertEquals(
      LoadStatus.Failed(DataError.Network),
      listOf(refreshing, failedNetwork).combinedStatus(StatusPrecedence.FailedFirst),
    )
  }

  @Test
  fun loadingFirstReportsLoadingOverAFailedSource() {
    assertEquals(
      LoadStatus.Loading,
      listOf(refreshing, failedNetwork).combinedStatus(StatusPrecedence.LoadingFirst),
    )
  }

  @Test
  fun failedFirstIsTheDefault() {
    val sources = listOf(refreshing, failedNetwork)

    assertEquals(sources.combinedStatus(StatusPrecedence.FailedFirst), sources.combinedStatus())
  }

  @Test
  fun withNoFailureBothPrecedencesAgree() {
    for (precedence in StatusPrecedence.entries) {
      assertEquals(
        LoadStatus.Loading,
        listOf(loadedFromNetwork, refreshing).combinedStatus(precedence),
      )
      assertEquals(
        LoadStatus.Idle,
        listOf(loadedFromNetwork, loadedFromCache).combinedStatus(precedence),
      )
    }
  }

  @Test
  fun severalFailuresReportTheFirst() {
    for (precedence in StatusPrecedence.entries) {
      assertEquals(
        LoadStatus.Failed(DataError.Http(500)),
        listOf(loadedFromNetwork, failedHttp, failedNetwork).combinedStatus(precedence),
      )
    }
  }

  // --- combine: data and origin ---

  @Test
  fun combineAppliesTheTransform() {
    val combined = combine(loadedFromNetwork, loadedFromCache) { count, flag -> "$count/$flag" }

    assertEquals("3/true", combined.data)
  }

  @Test
  fun combineHasNotLoadedUntilEverySourceHas() {
    val combined = combine(loadedFromNetwork, notYetLoaded) { count, names -> count + names.size }

    assertNull(combined.origin)
    assertFalse(combined.hasLoaded)
  }

  @Test
  fun combineIsCacheIfAnyPartCameFromCache() {
    assertEquals(Origin.Cache, combine(loadedFromNetwork, loadedFromCache) { _, _ -> }.origin)
  }

  @Test
  fun combineIsNetworkOnlyIfEveryPartWas() {
    val alsoFromNetwork = ContentState("y", Origin.Network, LoadStatus.Idle)

    assertEquals(Origin.Network, combine(loadedFromNetwork, alsoFromNetwork) { _, _ -> }.origin)
  }

  @Test
  fun combineStatusFollowsThePrecedence() {
    assertEquals(
      LoadStatus.Failed(DataError.Network),
      combine(refreshing, failedNetwork) { _, _ -> }.status,
    )
    assertEquals(
      LoadStatus.Loading,
      combine(refreshing, failedNetwork, precedence = StatusPrecedence.LoadingFirst) { _, _ -> }
        .status,
    )
  }

  // --- combine: every arity is reachable ---

  @Test
  fun combinesThreeStates() {
    val combined =
      combine(loadedFromNetwork, loadedFromCache, refreshing) { count, flag, list ->
        "$count/$flag/${list.size}"
      }

    assertEquals("3/true/1", combined.data)
    assertEquals(Origin.Cache, combined.origin)
    assertEquals(LoadStatus.Loading, combined.status)
  }

  @Test
  fun combinesFourStates() {
    val combined =
      combine(loadedFromNetwork, loadedFromCache, refreshing, failedNetwork) { a, b, c, d ->
        "$a/$b/${c.size}/$d"
      }

    assertEquals("3/true/1/0.5", combined.data)
    assertEquals(LoadStatus.Failed(DataError.Network), combined.status)
  }

  @Test
  fun combinesFiveStates() {
    val combined =
      combine(loadedFromNetwork, loadedFromCache, refreshing, failedNetwork, failedHttp) {
        a,
        b,
        c,
        d,
        e ->
        "$a/$b/${c.size}/$d/$e"
      }

    assertEquals("3/true/1/0.5/x", combined.data)
    // Two failures: the first in argument order is the one reported.
    assertEquals(LoadStatus.Failed(DataError.Network), combined.status)
  }

  // --- combine cannot drift from the aggregate ---

  @Test
  fun combineAgreesWithTheAggregateForEveryPairOfShapes() {
    // `combine` is documented as `combinedStatus` plus a transform; this pins it for every pair
    // of shapes under both precedences. isLoading and errorOrNull are deliberately not compared:
    // the aggregate keeps them independent, while a combined state collapses them into one.
    for (a in everyShape) {
      for (b in everyShape) {
        for (precedence in StatusPrecedence.entries) {
          val combined = combine(a, b, precedence) { _, _ -> }
          val sources = listOf(a, b)

          assertEquals(sources.combinedStatus(precedence), combined.status, "$a + $b, $precedence")
          assertEquals(sources.hasLoaded, combined.hasLoaded, "$a + $b")
        }
      }
    }
  }
}
