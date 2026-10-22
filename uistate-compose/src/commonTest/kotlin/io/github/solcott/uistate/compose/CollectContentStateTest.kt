@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.solcott.uistate.compose

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import io.github.solcott.uistate.ContentState
import io.github.solcott.uistate.LoadStatus
import io.github.solcott.uistate.errorOrNull
import io.github.solcott.uistate.hasLoaded
import io.github.solcott.uistate.isLoading
import io.github.solcott.uistate.scanContentState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

  // --- The same rules as uistate's stream fold ---

  @Test
  fun thisFoldAndScanContentStateReachTheSameStates() = runTest {
    // The completion rule is implemented twice, here and in uistate's ContentStateStreams.kt,
    // because delegating would fold from a stale copy. This pins the two together: change one
    // without the other and a case below diverges.
    val failed = Outcome.Error(DataError.Network, Origin.Network)
    val sources: Map<String, () -> Flow<Outcome<List<String>>>> =
      mapOf(
        "data" to { flowOf(loaded) },
        "loading then error" to { flowOf(Outcome.Loading, failed) },
        "completion without emitting" to { emptyFlow() },
        "cancelled while loading" to
          {
            flow {
              emit(loaded)
              emit(Outcome.Loading)
              emit(failed)
              emit(Outcome.Loading)
              awaitCancellation()
            }
          },
      )

    for ((case, source) in sources) {
      val folded = RecordingState(newState().value)
      foldUntilIdle { folded.collectFrom(source()) }

      val streamed = mutableListOf<ContentState<List<String>>>()
      foldUntilIdle { source().scanContentState(newState().value).collect { streamed += it } }

      assertEquals(streamed.distinctConsecutive(), folded.history.distinctConsecutive(), case)
    }
  }

  // Runs [fold] until it completes or has nothing left to do, then cancels it, which is the
  // "cancelled while loading" case for a source that never completes.
  private suspend fun TestScope.foldUntilIdle(fold: suspend () -> Unit) {
    val job = launch { fold() }
    runCurrent()
    job.cancelAndJoin()
  }

  private fun <T> List<T>.distinctConsecutive(): List<T> = filterIndexed { index, item ->
    index == 0 || item != this[index - 1]
  }

  // A MutableState that remembers every value it is given, so the fold's states can be compared
  // without a snapshot observer.
  private class RecordingState<T>(initial: T) : MutableState<T> {
    val history = mutableListOf(initial)

    override var value: T = initial
      set(value) {
        field = value
        history += value
      }

    override fun component1(): T = value

    override fun component2(): (T) -> Unit = { value = it }
  }
}
