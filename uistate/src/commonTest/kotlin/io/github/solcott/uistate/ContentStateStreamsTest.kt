@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.solcott.uistate

import app.cash.turbine.test
import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import io.github.solcott.dataresult.Outcomes2
import io.github.solcott.dataresult.Outcomes5
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The fold without Compose: the stream forms and the MutableStateFlow forms, single-source and
 * grouped. uistate-compose's CollectContentStateTest mirrors the completion rule against its own
 * fold, because the two are implemented separately.
 */
class ContentStateStreamsTest {

  private val initial = ContentState(emptyList<String>())
  private val loaded = Outcome.Data(listOf("a"), Origin.Network)
  private val failed = Outcome.Error(DataError.Network, Origin.Network)

  private fun loadedState() = initial.applyEmission(loaded)

  // --- scanContentState ---

  @Test
  fun theInitialStateComesFirst() = runTest {
    MutableSharedFlow<Outcome<List<String>>>().scanContentState(initial).test {
      assertEquals(initial, awaitItem())
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun dataReplacesTheValueAndSettles() = runTest {
    flowOf(loaded).scanContentState(initial).test {
      assertEquals(initial, awaitItem())
      val state = awaitItem()
      assertEquals(listOf("a"), state.data)
      assertEquals(Origin.Network, state.origin)
      assertEquals(LoadStatus.Idle, state.status)
      // Already settled by Data, so completion adds nothing.
      awaitComplete()
    }
  }

  @Test
  fun loadingAndErrorsKeepTheLastValue() = runTest {
    flowOf(Outcome.Loading, failed).scanContentState(loadedState()).test {
      awaitItem()
      val reloading = awaitItem()
      assertEquals(listOf("a"), reloading.data)
      assertEquals(LoadStatus.Loading, reloading.status)
      val failure = awaitItem()
      assertEquals(listOf("a"), failure.data)
      assertEquals(LoadStatus.Failed(DataError.Network), failure.status)
      awaitComplete()
    }
  }

  @Test
  fun aSourceThatCompletesWithoutEmittingSettles() = runTest {
    // Without the onCompletion net the spinner would hang forever.
    assertEquals(
      listOf(initial, initial.settled()),
      emptyFlow<Outcome<List<String>>>().scanContentState(initial).toList(),
    )
  }

  @Test
  fun aCancelledCollectionDoesNotSettle() = runTest {
    // The `cause == null` guard. Cancellation completes the flow too; settling then would report an
    // abandoned request as finished.
    val states = mutableListOf<ContentState<List<String>>>()
    val job = launch {
      MutableSharedFlow<Outcome<List<String>>>().scanContentState(initial).collect { states += it }
    }
    runCurrent()

    job.cancelAndJoin()

    assertEquals(listOf(initial), states)
  }

  // --- scanContentStateLatest ---

  @Test
  fun aNewParameterKeepsTheContentUnderAReloadAndCancelsTheOldStream() = runTest {
    val params = MutableSharedFlow<String>(replay = 1)
    var firstCancelled = false
    val streams =
      mapOf<String, Flow<Outcome<List<String>>>>(
        "first" to
          flow {
            emit(loaded)
            awaitCancellation()
          }
            .onCompletion { firstCancelled = it is CancellationException },
        "second" to MutableSharedFlow(),
      )

    params
      .scanContentStateLatest(initial) { streams.getValue(it) }
      .test {
        assertEquals(initial, awaitItem())
        params.emit("first")
        assertTrue(awaitItem().isLoading)
        assertEquals(listOf("a"), awaitItem().data)

        params.emit("second")
        // reloading(), not a reset: the old list stays visible under the refresh indicator.
        val reloading = awaitItem()
        assertEquals(listOf("a"), reloading.data)
        assertTrue(reloading.isLoading)
        assertTrue(reloading.hasLoaded)
        assertTrue(firstCancelled, "the previous parameter's stream must be cancelled")
        // The cancelled first stream must not settle the reload it was replaced by.
        expectNoEvents()
        cancelAndIgnoreRemainingEvents()
      }
  }

  @Test
  fun aRepeatedParameterStartsNoRequest() = runTest {
    val params = MutableSharedFlow<String>(replay = 1)
    var requests = 0

    params
      .scanContentStateLatest(initial) {
        requests++
        flowOf(loaded)
      }
      .test {
        awaitItem()
        params.emit("same")
        awaitItem()
        awaitItem()

        params.emit("same")

        expectNoEvents()
        assertEquals(1, requests)
        cancelAndIgnoreRemainingEvents()
      }
  }

  @Test
  fun aRequestCancelledByANewerParameterDoesNotSettle() = runTest {
    // Neither request ever answers, so a settled state can only have come from the first one being
    // discarded.
    val params = MutableSharedFlow<Int>(replay = 1)
    val neverAnswers = MutableSharedFlow<Outcome<List<String>>>()

    params
      .scanContentStateLatest(initial) { neverAnswers }
      .test {
        awaitItem()
        params.emit(1)
        assertTrue(awaitItem().isLoading)

        params.emit(2)

        assertTrue(awaitItem().isLoading)
        expectNoEvents()
        cancelAndIgnoreRemainingEvents()
      }
  }

  // --- MutableStateFlow.collectFrom / collectLatestFrom ---

  @Test
  fun collectingIntoAHolderSettlesOnCompletion() = runTest {
    val state = MutableStateFlow(initial)

    state.collectFrom(emptyFlow())

    assertEquals(LoadStatus.Idle, state.value.status)
  }

  @Test
  fun aCancelledCollectionIntoAHolderDoesNotSettle() = runTest {
    val state = MutableStateFlow(initial)
    val job = launch { state.collectFrom(MutableSharedFlow()) }
    runCurrent()

    job.cancelAndJoin()

    assertTrue(state.value.isLoading, "cancellation must not settle")
  }

  @Test
  fun restartingACollectionKeepsTheLastValue() = runTest {
    // What stateIn(WhileSubscribed) over scanContentState gets wrong: a second collection must
    // carry on from the held data, not from a placeholder.
    val state = MutableStateFlow(initial)
    state.collectFrom(flowOf(loaded))

    val source = MutableSharedFlow<Outcome<List<String>>>()
    val job = launch { state.collectFrom(source) }
    runCurrent()
    source.emit(Outcome.Loading)
    runCurrent()

    assertEquals(listOf("a"), state.value.data)
    assertTrue(state.value.isLoading)
    job.cancelAndJoin()
  }

  @Test
  fun aWriteMadeOutsideTheFoldIsFoldedIntoNotOverwritten() = runTest {
    val state = MutableStateFlow(initial)
    val source = MutableSharedFlow<Outcome<List<String>>>()
    val job = launch { state.collectFrom(source) }
    runCurrent()
    source.emit(loaded)
    runCurrent()

    state.value = state.value.copy(data = listOf("edited"))
    source.emit(failed)
    runCurrent()

    assertEquals(listOf("edited"), state.value.data)
    assertEquals(DataError.Network, state.value.errorOrNull)
    job.cancelAndJoin()
  }

  @Test
  fun aNewParameterReloadsTheHolderWithoutDroppingItsData() = runTest {
    val state = MutableStateFlow(loadedState())
    val params = MutableSharedFlow<Int>(replay = 1)
    params.emit(1)
    val neverAnswers = MutableSharedFlow<Outcome<List<String>>>()
    val job = launch { state.collectLatestFrom(params) { neverAnswers } }
    runCurrent()

    params.emit(2)
    runCurrent()

    assertEquals(listOf("a"), state.value.data)
    assertTrue(state.value.isLoading, "a discarded request must not settle")
    job.cancelAndJoin()
  }

  // --- Groups ---

  private val names = Outcome.Data(listOf("a"), Origin.Cache)
  private val count = Outcome.Data(3, Origin.Network)

  private fun loadedPair() =
    contentStatesOf(emptyList<String>(), 0).applyEmission(Outcomes2(names, count))

  private fun loadedFive() =
    contentStatesOf(emptyList<String>(), 0, "", false, 0L)
      .applyEmission(
        Outcomes5(
          names,
          count,
          Outcome.Data("c", Origin.Cache),
          Outcome.Data(true, Origin.Cache),
          Outcome.Data(5L, Origin.Cache),
        )
      )

  @Test
  fun oneSourceFailingKeepsTheOthersInAPair() = runTest {
    flowOf<Outcomes2<List<String>, Int>>(Outcomes2(names, failed))
      .scanContentState(loadedPair())
      .test {
        awaitItem()
        val states = awaitItem()
        assertEquals(listOf("a"), states.first.data)
        assertEquals(LoadStatus.Idle, states.first.status)
        assertEquals(3, states.second.data)
        assertEquals(LoadStatus.Failed(DataError.Network), states.second.status)
        awaitComplete()
      }
  }

  @Test
  fun oneSourceFailingKeepsTheOthersInAFive() = runTest {
    val outcomes =
      Outcomes5<List<String>, Int, String, Boolean, Long>(
        names,
        failed,
        Outcome.Loading,
        Outcome.Loading,
        Outcome.Data(6L, Origin.Network),
      )

    flowOf(outcomes).scanContentState(loadedFive()).test {
      awaitItem()
      val states = awaitItem()
      assertEquals(listOf("a"), states.first.data)
      assertEquals(LoadStatus.Idle, states.first.status)
      assertEquals(3, states.second.data)
      assertEquals(LoadStatus.Failed(DataError.Network), states.second.status)
      assertEquals(6L, states.fifth.data)
      // The two still loading are settled when the source completes, and nothing else changes.
      val settled = awaitItem()
      assertFalse(settled.isAnyLoading)
      assertEquals(LoadStatus.Failed(DataError.Network), settled.second.status)
      awaitComplete()
    }
  }

  @Test
  fun aNewParameterReloadsEverySourceInAPair() = runTest {
    val params = MutableSharedFlow<Int>(replay = 1)

    params
      .scanContentStateLatest(loadedPair()) { MutableSharedFlow<Outcomes2<List<String>, Int>>() }
      .test {
        awaitItem()
        params.emit(1)

        val states = awaitItem()
        assertTrue(states.isAllLoading)
        assertEquals(listOf("a"), states.first.data)
        assertEquals(3, states.second.data)
        cancelAndIgnoreRemainingEvents()
      }
  }

  @Test
  fun aNewParameterReloadsEverySourceInAFive() = runTest {
    val params = MutableSharedFlow<Int>(replay = 1)

    params
      .scanContentStateLatest(loadedFive()) {
        MutableSharedFlow<Outcomes5<List<String>, Int, String, Boolean, Long>>()
      }
      .test {
        awaitItem()
        params.emit(1)

        val states = awaitItem()
        assertTrue(states.isAllLoading)
        assertTrue(states.hasLoaded)
        assertEquals("c", states.third.data)
        assertEquals(5L, states.fifth.data)
        cancelAndIgnoreRemainingEvents()
      }
  }

  @Test
  fun aGroupHolderFoldsAndReloads() = runTest {
    val state = MutableStateFlow(loadedPair())
    state.collectFrom(flowOf<Outcomes2<List<String>, Int>>(Outcomes2(names, failed)))
    assertEquals(3, state.value.second.data)
    assertIs<LoadStatus.Failed>(state.value.second.status)

    val params = MutableSharedFlow<Int>(replay = 1)
    params.emit(1)
    val job = launch {
      state.collectLatestFrom(params) { MutableSharedFlow<Outcomes2<List<String>, Int>>() }
    }
    runCurrent()

    assertTrue(state.value.isAllLoading)
    assertEquals(listOf("a"), state.value.first.data)
    job.cancelAndJoin()
  }

  @Test
  fun aGroupHolderOfFiveSettlesOnCompletionAndNotOnCancellation() = runTest {
    val state = MutableStateFlow(loadedFive().reloading())
    val job = launch {
      state.collectFrom(MutableSharedFlow<Outcomes5<List<String>, Int, String, Boolean, Long>>())
    }
    runCurrent()
    job.cancelAndJoin()
    assertTrue(state.value.isAllLoading, "cancellation must not settle")

    state.collectFrom(emptyFlow())

    assertFalse(state.value.isAnyLoading)
    assertEquals(5L, state.value.fifth.data)
  }
}
