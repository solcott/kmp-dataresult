package io.github.solcott.uistate

import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ContentStateTest {

  private val initial = ContentState(emptyList<String>())

  // --- Starting state -------------------------------------------------------------------------

  @Test
  fun startsLoadingWithNoOrigin() {
    assertTrue(initial.isLoading)
    assertFalse(initial.hasLoaded)
    assertNull(initial.origin)
    assertNull(initial.errorOrNull)
  }

  // --- applyEmission --------------------------------------------------------------------------

  @Test
  fun dataSettlesTheStatusAndRecordsTheOrigin() {
    val state = initial.applyEmission(Outcome.Data(listOf("a"), Origin.Network))

    assertEquals(listOf("a"), state.data)
    assertEquals(Origin.Network, state.origin)
    assertEquals(LoadStatus.Idle, state.status)
    assertTrue(state.hasLoaded)
  }

  @Test
  fun errorKeepsTheDataAlreadyOnScreen() {
    val loaded = initial.applyEmission(Outcome.Data(listOf("a"), Origin.Cache))

    val failed = loaded.applyEmission(Outcome.Error(DataError.Network, Origin.Network))

    // Stale-while-revalidate: a failed refresh must not blank the screen.
    assertEquals(listOf("a"), failed.data)
    assertEquals(Origin.Cache, failed.origin)
    assertEquals(DataError.Network, failed.errorOrNull)
    assertFalse(failed.isLoading)
  }

  @Test
  fun loadingKeepsTheDataAlreadyOnScreen() {
    val loaded = initial.applyEmission(Outcome.Data(listOf("a"), Origin.Cache))

    val refreshing = loaded.applyEmission(Outcome.Loading)

    // This is what drives a refresh indicator over existing content rather than a full spinner.
    assertEquals(listOf("a"), refreshing.data)
    assertEquals(Origin.Cache, refreshing.origin)
    assertTrue(refreshing.isLoading)
    assertTrue(refreshing.hasLoaded)
  }

  @Test
  fun loadingBeforeAnyDataLeavesTheStateUnloaded() {
    val state = initial.applyEmission(Outcome.Loading)

    assertTrue(state.isLoading)
    assertFalse(state.hasLoaded)
  }

  @Test
  fun dataAfterAFailureClearsTheError() {
    val failed = initial.applyEmission(Outcome.Error(DataError.Http(500), Origin.Network))

    val recovered = failed.applyEmission(Outcome.Data(listOf("a"), Origin.Network))

    assertNull(recovered.errorOrNull)
    assertEquals(LoadStatus.Idle, recovered.status)
  }

  @Test
  fun cacheThenNetworkEndsOnTheNetworkValue() {
    val state =
      initial
        .applyEmission(Outcome.Loading)
        .applyEmission(Outcome.Data(listOf("stale"), Origin.Cache))
        .applyEmission(Outcome.Data(listOf("fresh"), Origin.Network))

    assertEquals(listOf("fresh"), state.data)
    assertEquals(Origin.Network, state.origin)
    assertFalse(state.isLoading)
  }

  @Test
  fun anEmptyResultCountsAsLoaded() {
    // The reason `hasLoaded` reads `origin` rather than the data: an empty list is a real answer,
    // and treating it as "nothing yet" would hang a spinner over a legitimately empty screen.
    val state = initial.applyEmission(Outcome.Data(emptyList(), Origin.Network))

    assertTrue(state.hasLoaded)
    assertFalse(state.isLoading)
  }

  // --- reloading / settled --------------------------------------------------------------------

  @Test
  fun reloadingMarksLoadingWithoutTouchingData() {
    val loaded = initial.applyEmission(Outcome.Data(listOf("a"), Origin.Network))

    val reloading = loaded.reloading()

    assertEquals(listOf("a"), reloading.data)
    assertEquals(Origin.Network, reloading.origin)
    assertTrue(reloading.isLoading)
  }

  @Test
  fun reloadingClearsAPreviousFailure() {
    val failed = initial.applyEmission(Outcome.Error(DataError.Network, Origin.Network))

    assertNull(failed.reloading().errorOrNull)
  }

  @Test
  fun settledEndsALoadingStatus() {
    assertEquals(LoadStatus.Idle, initial.settled().status)
  }

  @Test
  fun settledLeavesASettledStateAlone() {
    val failed = initial.applyEmission(Outcome.Error(DataError.Network, Origin.Network))

    assertSame(failed, failed.settled())

    val loaded = initial.applyEmission(Outcome.Data(listOf("a"), Origin.Network))

    assertSame(loaded, loaded.settled())
  }

  // --- errorOrNull ----------------------------------------------------------------------------

  @Test
  fun errorOrNullReportsOnlyTheFailedStatus() {
    val error = DataError.Api(listOf("boom"), code = "E1")

    assertEquals(error, initial.applyEmission(Outcome.Error(error, Origin.Network)).errorOrNull)
    assertNull(initial.applyEmission(Outcome.Loading).errorOrNull)
    assertNull(initial.applyEmission(Outcome.Data(listOf("a"), Origin.Cache)).errorOrNull)
  }
}
