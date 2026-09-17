@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.solcott.uistate.compose

import androidx.compose.runtime.mutableStateOf
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import io.github.solcott.dataresult.Outcomes2
import io.github.solcott.uistate.contentStatesOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The group overloads of the fold. They share their body with the single-source ones, so these pin
 * the wiring -- each outcome reaching its own source -- and the completion rule, once more, through
 * the group entry points.
 */
class CollectContentStatesTest {

  private val names = Outcome.Data(listOf("a"), Origin.Network)
  private val count = Outcome.Data(3, Origin.Network)

  private fun newStates() = mutableStateOf(contentStatesOf(emptyList<String>(), 0))

  // --- collectFrom ---

  @Test
  fun foldsEachOutcomeIntoItsOwnSource() = runTest {
    val states = newStates()

    states.collectFrom(flowOf(Outcomes2(names, Outcome.Loading), Outcomes2(names, count)))

    assertEquals(listOf("a"), states.value.first.data)
    assertEquals(3, states.value.second.data)
    assertFalse(states.value.isAnyLoading)
  }

  @Test
  fun aGroupThatCompletesWhileASourceIsLoadingSettlesIt() = runTest {
    val states = newStates()

    states.collectFrom(flowOf(Outcomes2(names, Outcome.Loading)))

    assertFalse(states.value.isAnyLoading, "the source that never answered must settle")
  }

  @Test
  fun aCancelledGroupCollectionDoesNotSettle() = runTest {
    val states = newStates()
    val job = launch { states.collectFrom(MutableSharedFlow<Outcomes2<List<String>, Int>>()) }
    runCurrent()

    job.cancelAndJoin()

    assertTrue(states.value.isAllLoading, "cancellation must not settle")
  }

  // --- collectLatestFrom ---

  @Test
  fun aNewParameterMarksEverySourceReloadingAndKeepsItsData() = runTest {
    val states = newStates()
    val params = MutableSharedFlow<String>(replay = 1)
    params.emit("first")
    val first = MutableSharedFlow<Outcomes2<List<String>, Int>>(replay = 1)
    first.emit(Outcomes2(names, count))
    val neverAnswers = MutableSharedFlow<Outcomes2<List<String>, Int>>()
    val job = launch {
      states.collectLatestFrom(params) { p -> if (p == "first") first else neverAnswers }
    }
    runCurrent()
    assertFalse(states.value.isAnyLoading)

    params.emit("second")
    runCurrent()

    assertTrue(states.value.isAllLoading)
    assertEquals(listOf("a"), states.value.first.data)
    assertEquals(3, states.value.second.data)
    job.cancelAndJoin()
  }
}
