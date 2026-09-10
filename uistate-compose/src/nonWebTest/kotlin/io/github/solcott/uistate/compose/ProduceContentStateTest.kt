package io.github.solcott.uistate.compose

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import io.github.solcott.uistate.ContentState
import io.github.solcott.uistate.hasLoaded
import io.github.solcott.uistate.isLoading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * The composable wiring: `retain`, the keyed effect, and `rememberUpdatedState`. The fold itself is
 * covered on every target by `CollectContentStateTest`; these need a Compose frame clock.
 *
 * No `RetainedValuesStore` is installed here, so `retain` behaves as `remember` — which is enough,
 * because a key change does not remove anything from composition.
 */
class ProduceContentStateTest {

  private val loaded = Outcome.Data(listOf("a"), Origin.Network)

  @Test
  fun foldsTheSourceIntoState() = runTest {
    moleculeFlow(RecompositionMode.Immediate) {
        produceContentState(emptyList<String>()) { flowOf(loaded) }
      }
      .test {
        assertEquals(listOf("a"), awaitStateWhere { !it.isLoading }.data)
        cancelAndIgnoreRemainingEvents()
      }
  }

  @Test
  fun heldDataSurvivesAKeyChangeAndTheCollectionRestarts() = runTest {
    // The test that catches `retain { }` being "tidied" into `retain(*keys) { }`. Keyed, the hold
    // would be discarded on a key change and re-created from `initial` -- an empty list.
    var queries = 0
    val key = mutableIntStateOf(0)
    val second = MutableSharedFlow<Outcome<List<String>>>(replay = 1)
    val scheduler = testScheduler

    moleculeFlow(RecompositionMode.Immediate) {
        produceContentState(emptyList<String>(), key.intValue) {
          queries++
          if (queries == 1) flowOf(loaded) else second
        }
      }
      .test {
        awaitStateWhere { it.data == listOf("a") }

        key.intValue = 1
        // Written outside the composition, so the recomposer only sees it once notified.
        Snapshot.sendApplyNotifications()
        scheduler.advanceUntilIdle()
        assertEquals(2, queries, "a key change must restart the collection")

        second.emit(Outcome.Data(listOf("b"), Origin.Network))
        while (true) {
          val state = awaitItem()
          assertTrue(state.hasLoaded, "the key change reset the hold to `initial`")
          if (state.data == listOf("b")) break
        }
        cancelAndIgnoreRemainingEvents()
      }
  }

  @Test
  fun aNewParameterReachesTheCurrentLambda() = runTest {
    // rememberUpdatedState. There are no keys, so the effect never restarts; the only way a later
    // parameter can see a lambda from a later composition is through the updated-state indirection.
    // Without it this would read "old-p2".
    val params = MutableSharedFlow<String>(replay = 1)
    params.emit("p1")
    val prefix = mutableStateOf("old")
    val scheduler = testScheduler

    moleculeFlow(RecompositionMode.Immediate) {
        val current = prefix.value
        params.produceContentState(emptyList<String>()) { p ->
          flowOf(Outcome.Data(listOf("$current-$p"), Origin.Network))
        }
      }
      .test {
        awaitStateWhere { it.data == listOf("old-p1") }

        prefix.value = "new"
        Snapshot.sendApplyNotifications()
        scheduler.advanceUntilIdle()
        params.emit("p2")

        val answered = awaitStateWhere { it.data.firstOrNull()?.endsWith("p2") == true }
        assertEquals(listOf("new-p2"), answered.data)
        cancelAndIgnoreRemainingEvents()
      }
  }
}

private suspend fun ReceiveTurbine<ContentState<List<String>>>.awaitStateWhere(
  predicate: (ContentState<List<String>>) -> Boolean
): ContentState<List<String>> {
  while (true) {
    val state = awaitItem()
    if (predicate(state)) return state
  }
}
