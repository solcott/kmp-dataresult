package io.github.solcott.uistate.circuit

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import app.cash.turbine.ReceiveTurbine
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.test.presenterTestOf
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
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * `presenterTestOf` needs a [CircuitUiState], and [ContentState] deliberately is not one — the
 * library does not depend on `circuit-runtime`. Wrapping it is all the harness needs.
 */
private data class Wrapper(val content: ContentState<List<String>>) : CircuitUiState

class ProduceContentStateTest {

  private val loaded = Outcome.Data(listOf("a"), Origin.Network)

  // --- The basic fold ---------------------------------------------------------------------------

  @Test
  fun dataSettlesTheStateAndRecordsTheOrigin() = runTest {
    presenterTestOf({ Wrapper(produceContentState(emptyList<String>()) { flowOf(loaded) }) }) {
      val settled = awaitSettled()

      assertEquals(listOf("a"), settled.data)
      assertEquals(Origin.Network, settled.origin)
      assertEquals(LoadStatus.Idle, settled.status)
    }
  }

  @Test
  fun errorKeepsTheDataAlreadyOnScreen() = runTest {
    val source = flowOf(loaded, Outcome.Error(DataError.Network, Origin.Network))

    presenterTestOf({ Wrapper(produceContentState(emptyList<String>()) { source }) }) {
      val failed = awaitStateWhere { it.errorOrNull != null }

      assertEquals(listOf("a"), failed.data)
      assertEquals(DataError.Network, failed.errorOrNull)
    }
  }

  // --- The settled() safety net, and its cancellation guard --------------------------------------

  @Test
  fun aSourceThatCompletesWithoutEmittingStillSettles() = runTest {
    // Without the onCompletion net the spinner would hang forever: nothing ever called
    // applyEmission, so nothing ever moved the status off Loading.
    presenterTestOf({ Wrapper(produceContentState(emptyList<String>()) { emptyFlow() }) }) {
      assertEquals(LoadStatus.Idle, awaitSettled().status)
    }
  }

  @Test
  fun aCancelledCollectionDoesNotSettleALoadingState() = runTest {
    // The `cause == null` guard. Switching params cancels the in-flight collection; its
    // onCompletion fires with a cause, and settling there would report an abandoned request as
    // finished. The second source never emits, so anything but Loading here is the guard failing.
    val params = MutableSharedFlow<Int>(replay = 1)
    params.emit(1)
    val neverEmits = MutableSharedFlow<Outcome<List<String>>>()

    presenterTestOf({
      Wrapper(produceContentStateFor(emptyList<String>(), params) { neverEmits })
    }) {
      assertTrue(awaitItem().content.isLoading)

      params.emit(2)

      // Silence is the assertion. reloading() on an already-loading state is the same value and
      // the second source never emits, so nothing legitimate can arrive here. If the guard were
      // missing, the cancelled first collection would settle to Idle -- and that is a change,
      // which would show up as an emission.
      expectNoEvents()
    }
  }

  // --- Retention across a key change -------------------------------------------------------------

  @Test
  fun heldDataSurvivesAKeyChangeAndTheProducerRestarts() = runTest {
    var queries = 0
    val key = mutableIntStateOf(0)
    val second = MutableSharedFlow<Outcome<List<String>>>()
    val scheduler = testScheduler

    presenterTestOf({
      Wrapper(
        produceContentState(emptyList<String>(), key.intValue) {
          queries++
          if (queries == 1) flowOf(loaded) else second
        }
      )
    }) {
      assertEquals(listOf("a"), awaitSettled().data)
      assertEquals(1, queries)

      key.intValue = 1
      // The write happens outside the composition, so the recomposer only sees it once apply
      // notifications are sent.
      Snapshot.sendApplyNotifications()
      scheduler.advanceUntilIdle()

      assertEquals(2, queries, "a key change must restart the producer")

      // The second request now answers. `presenterTestOf`'s turbine only surfaces *distinct*
      // states, so this asserts by what it does not see: had the restart re-created the retained
      // holder from `initial`, an empty-list state would have arrived between the two loads.
      // Going straight from [a] to [b] is what says the loaded value survived the restart -- which
      // is what keeps a retry from blanking the screen while the new request runs.
      second.emit(Outcome.Data(listOf("b"), Origin.Network))

      val afterRestart = awaitItem().content
      assertEquals(listOf("b"), afterRestart.data)
      assertTrue(afterRestart.hasLoaded)
    }
  }

  // --- produceContentStateFor
  // ----------------------------------------------------------------------

  @Test
  fun aNewParameterFlagsLoadingWithoutDroppingTheCurrentContent() = runTest {
    val params = MutableSharedFlow<String>(replay = 1)
    params.emit("first")
    val sources = mutableMapOf<String, MutableSharedFlow<Outcome<List<String>>>>()

    presenterTestOf({
      Wrapper(
        produceContentStateFor(emptyList<String>(), params) { p ->
          sources.getOrPut(p) { MutableSharedFlow(replay = 1) }
        }
      )
    }) {
      awaitItem()
      sources.getValue("first").emit(loaded)
      assertEquals(listOf("a"), awaitStateWhere { it.hasLoaded }.data)

      params.emit("second")

      val reloading = awaitStateWhere { it.isLoading }
      // reloading(), not a reset: the old list stays visible under the refresh indicator.
      assertEquals(listOf("a"), reloading.data)
      assertTrue(reloading.hasLoaded)
    }
  }

  @Test
  fun anUnchangedParameterStartsNoNewRequest() = runTest {
    // distinctUntilChanged on params. A child re-reporting its unchanged filter after a
    // configuration change must not restart an identical query -- and must not blink through
    // reloading either.
    var queries = 0
    val params = MutableSharedFlow<String>(replay = 1)
    params.emit("same")

    presenterTestOf({
      Wrapper(
        produceContentStateFor(emptyList<String>(), params) {
          queries++
          flowOf(loaded)
        }
      )
    }) {
      awaitStateWhere { it.hasLoaded }
      assertEquals(1, queries)

      params.emit("same")

      expectNoEvents()
      assertEquals(1, queries)
    }
  }
}

/** Awaits the first state whose request has settled, skipping the loading emissions before it. */
private suspend fun ReceiveTurbine<Wrapper>.awaitSettled(): ContentState<List<String>> =
  awaitStateWhere {
    !it.isLoading
  }

private suspend fun ReceiveTurbine<Wrapper>.awaitStateWhere(
  predicate: (ContentState<List<String>>) -> Boolean
): ContentState<List<String>> {
  while (true) {
    val state = awaitItem().content
    if (predicate(state)) return state
  }
}
