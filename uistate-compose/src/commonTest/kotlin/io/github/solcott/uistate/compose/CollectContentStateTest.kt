@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.solcott.uistate.compose

import androidx.compose.runtime.mutableStateOf
import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import io.github.solcott.uistate.ContentState
import io.github.solcott.uistate.LoadStatus
import io.github.solcott.uistate.errorOrNull
import io.github.solcott.uistate.hasLoaded
import io.github.solcott.uistate.isLoading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The fold on its own. A `MutableState` is a plain snapshot holder and no composition is involved,
 * so unlike anything that needs a Compose frame clock, these run on every target — web included.
 */
class CollectContentStateTest {

  private val loaded = Outcome.Data(listOf("a"), Origin.Network)

  private fun newState() = mutableStateOf(ContentState(emptyList<String>()))

  // --- collectFrom ---

  @Test
  fun dataSettlesTheStateAndRecordsTheOrigin() = runTest {
    val state = newState()

    state.collectFrom(flowOf(loaded))

    assertEquals(listOf("a"), state.value.data)
    assertEquals(Origin.Network, state.value.origin)
    assertEquals(LoadStatus.Idle, state.value.status)
  }

  @Test
  fun errorKeepsTheDataAlreadyHeld() = runTest {
    val state = newState()

    state.collectFrom(flowOf(loaded, Outcome.Error(DataError.Network, Origin.Network)))

    assertEquals(listOf("a"), state.value.data)
    assertEquals(DataError.Network, state.value.errorOrNull)
  }

  @Test
  fun loadingKeepsTheDataAlreadyHeld() = runTest {
    // A source that goes back to the network in the background: the held list must stay, flagged.
    val state = newState()
    val source = MutableSharedFlow<Outcome<List<String>>>()
    val job = launch { state.collectFrom(source) }
    runCurrent()

    source.emit(loaded)
    source.emit(Outcome.Loading)
    runCurrent()

    assertEquals(listOf("a"), state.value.data)
    assertTrue(state.value.isLoading)
    job.cancelAndJoin()
  }

  @Test
  fun aSourceThatCompletesWithoutEmittingSettles() = runTest {
    // Without the onCompletion net the spinner would hang forever: nothing ever called
    // applyEmission, so nothing ever moved the status off Loading.
    val state = newState()

    state.collectFrom(emptyFlow())

    assertEquals(LoadStatus.Idle, state.value.status)
  }

  @Test
  fun aCancelledCollectionDoesNotSettle() = runTest {
    // The `cause == null` guard. Cancellation completes the flow too; settling then would report an
    // abandoned request as finished.
    val state = newState()
    val job = launch { state.collectFrom(MutableSharedFlow()) }
    runCurrent()

    job.cancelAndJoin()

    assertTrue(state.value.isLoading, "cancellation must not settle")
  }

  // --- collectLatestFrom ---

  @Test
  fun aNewParameterFlagsLoadingWithoutDroppingTheHeldData() = runTest {
    val state = newState()
    val params = MutableSharedFlow<String>(replay = 1)
    params.emit("first")
    val sources = mutableMapOf<String, MutableSharedFlow<Outcome<List<String>>>>()
    val job = launch {
      state.collectLatestFrom(params) { p -> sources.getOrPut(p) { MutableSharedFlow(replay = 1) } }
    }
    runCurrent()
    sources.getValue("first").emit(loaded)
    runCurrent()
    assertFalse(state.value.isLoading)

    params.emit("second")
    runCurrent()

    // reloading(), not a reset: the old list stays visible under the refresh indicator.
    assertEquals(listOf("a"), state.value.data)
    assertTrue(state.value.isLoading)
    assertTrue(state.value.hasLoaded)
    job.cancelAndJoin()
  }

  @Test
  fun aNewParameterCancelsTheStaleRequestWithoutSettlingIt() = runTest {
    // The guard again, reached the way it is in practice: collectLatest discarding the old request.
    // The second request never answers, so anything but Loading means the first one settled.
    val state = newState()
    val params = MutableSharedFlow<Int>(replay = 1)
    params.emit(1)
    val neverAnswers = MutableSharedFlow<Outcome<List<String>>>()
    val job = launch { state.collectLatestFrom(params) { neverAnswers } }
    runCurrent()

    params.emit(2)
    runCurrent()

    assertTrue(state.value.isLoading, "a discarded request must not settle")
    job.cancelAndJoin()
  }

  @Test
  fun anUnchangedParameterStartsNoNewRequest() = runTest {
    // A child re-reporting its unchanged filter after a configuration change must not restart an
    // identical query.
    var queries = 0
    val state = newState()
    val params = MutableSharedFlow<String>(replay = 1)
    params.emit("same")
    val job = launch {
      state.collectLatestFrom(params) {
        queries++
        flowOf(loaded)
      }
    }
    runCurrent()

    params.emit("same")
    runCurrent()

    assertEquals(1, queries)
    job.cancelAndJoin()
  }
}
