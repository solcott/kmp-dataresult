package io.github.solcott.uistate.circuit

import app.cash.turbine.ReceiveTurbine
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.test.presenterTestOf
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import io.github.solcott.dataresult.Outcomes2
import io.github.solcott.uistate.ContentStates2
import io.github.solcott.uistate.contentStatesOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/** `presenterTestOf` needs a [CircuitUiState]; see the wrapper in `ProduceContentStateTest`. */
private data class GroupWrapper(val content: ContentStates2<List<String>, Int>) : CircuitUiState

/**
 * The group producers' wiring. They are the single-source producers with a different fold, so the
 * retention and cancellation cases in `ProduceContentStateTest` are not repeated here.
 */
class ProduceContentStatesTest {

  private val answer =
    Outcomes2(Outcome.Data(listOf("a"), Origin.Network), Outcome.Data(3, Origin.Network))

  @Test
  fun foldsTheGroupIntoOneStatePerSource() = runTest {
    presenterTestOf({
      GroupWrapper(
        produceRetainedContentStates(contentStatesOf(emptyList<String>(), 0)) { flowOf(answer) }
      )
    }) {
      val loaded = awaitGroupWhere { it.hasLoaded }

      assertEquals(listOf("a"), loaded.first.data)
      assertEquals(3, loaded.second.data)
      assertFalse(loaded.isAnyLoading)
    }
  }

  @Test
  fun aNewParameterKeepsEverySourcesContentWhileReloading() = runTest {
    val params = MutableSharedFlow<String>(replay = 1)
    params.emit("first")
    val first = MutableSharedFlow<Outcomes2<List<String>, Int>>(replay = 1)
    first.emit(answer)
    val neverAnswers = MutableSharedFlow<Outcomes2<List<String>, Int>>()

    presenterTestOf({
      GroupWrapper(
        params.produceRetainedContentStates(contentStatesOf(emptyList<String>(), 0)) { p ->
          if (p == "first") first else neverAnswers
        }
      )
    }) {
      awaitGroupWhere { it.hasLoaded && !it.isAnyLoading }

      params.emit("second")

      val reloading = awaitGroupWhere { it.isAllLoading }
      assertEquals(listOf("a"), reloading.first.data)
      assertEquals(3, reloading.second.data)
    }
  }
}

private suspend fun ReceiveTurbine<GroupWrapper>.awaitGroupWhere(
  predicate: (ContentStates2<List<String>, Int>) -> Boolean
): ContentStates2<List<String>, Int> {
  while (true) {
    val states = awaitItem().content
    if (predicate(states)) return states
  }
}
