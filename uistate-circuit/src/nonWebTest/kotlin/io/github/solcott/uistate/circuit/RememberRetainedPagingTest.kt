package io.github.solcott.uistate.circuit

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import app.cash.turbine.ReceiveTurbine
import com.slack.circuit.retained.rememberRetainedStateHolder
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.test.presenterTestOf
import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import io.github.solcott.uistate.LoadStatus
import io.github.solcott.uistate.OnKeyChange
import io.github.solcott.uistate.Page
import io.github.solcott.uistate.PagedState
import io.github.solcott.uistate.hasLoaded
import io.github.solcott.uistate.pageOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** `presenterTestOf` needs a [CircuitUiState]; [PagedState] deliberately is not one. */
private data class PagingUi(val paged: PagedState<String, Unit>, val shown: Boolean = true) :
  CircuitUiState

/**
 * A paged source the test answers by hand. Each request gets its own channel, so a test decides
 * when a page arrives and whether its source completes, and [requests] records every cursor asked
 * for, in order.
 */
private class Source {
  val requests = mutableListOf<String?>()
  private val open = mutableMapOf<String?, Channel<Outcome<Page<String, Unit>>>>()

  fun load(cursor: String?): Flow<Outcome<Page<String, Unit>>> {
    requests += cursor
    return Channel<Outcome<Page<String, Unit>>>(Channel.UNLIMITED)
      .also { open[cursor] = it }
      .consumeAsFlow()
  }

  /**
   * Answers the latest request for [cursor] with [outcome], and completes it unless told not to.
   */
  fun answer(cursor: String?, outcome: Outcome<Page<String, Unit>>, complete: Boolean = true) {
    val channel = open.getValue(cursor)
    channel.trySend(outcome)
    if (complete) channel.close()
  }

  fun page(cursor: String?, vararg items: String, next: String? = null) =
    answer(cursor, Outcome.Data(pageOf(items.toList(), next), Origin.Network))

  fun fail(cursor: String?) = answer(cursor, Outcome.Error(DataError.Network, Origin.Network))
}

class RememberRetainedPagingTest {

  private val id: (String) -> Any? = { it }

  // --- Paging forward ---------------------------------------------------------------------------

  @Test
  fun loadsTheFirstPageThenTheNextWhenAsked() = runTest {
    val source = Source()
    lateinit var paging: Paging<String, Unit>

    presenterTestOf({
      paging = rememberRetainedPaging(idOf = id, load = source::load)
      PagingUi(paging.state)
    }) {
      awaitPaged { it.refresh is LoadStatus.Loading }
      source.page(null, "A", "B", next = "c1")
      assertEquals(listOf("A", "B"), awaitPaged { it.hasLoaded }.items)

      paging.loadMore()
      paging.loadMore()
      assertEquals(LoadStatus.Loading, awaitPaged { it.append is LoadStatus.Loading }.append)
      assertEquals(listOf(null, "c1"), source.requests, "a second load more while one loads")

      source.page("c1", "C")
      val loaded = awaitPaged { it.items.size == 3 }
      assertEquals(listOf("A", "B", "C"), loaded.items)
      assertTrue(loaded.endReached)

      paging.loadMore()
      testScheduler.advanceUntilIdle()
      assertEquals(listOf(null, "c1"), source.requests, "load more at the end")
    }
  }

  @Test
  fun aFailedPageIsRetriedAloneAndBlocksLoadMoreUntilThen() = runTest {
    val source = Source()
    lateinit var paging: Paging<String, Unit>

    presenterTestOf({
      paging = rememberRetainedPaging(idOf = id, load = source::load)
      PagingUi(paging.state)
    }) {
      source.page(null, "A", next = "c1")
      awaitPaged { it.hasLoaded }
      paging.loadMore()
      awaitPaged { it.append is LoadStatus.Loading }
      source.fail("c1")
      val failed = awaitPaged { it.append is LoadStatus.Failed }
      assertEquals(listOf("A"), failed.items)

      paging.loadMore()
      testScheduler.advanceUntilIdle()
      assertEquals(listOf(null, "c1"), source.requests, "load more after a failed page")

      paging.retry()
      awaitPaged { it.append is LoadStatus.Loading }
      // Only the failed page, from the same cursor: the first page is not reloaded.
      assertEquals(listOf(null, "c1", "c1"), source.requests)
      source.page("c1", "B")
      assertEquals(listOf("A", "B"), awaitPaged { it.items.size == 2 }.items)
    }
  }

  @Test
  fun retryAfterAFailedRefreshRequestsTheFirstPageAndRetryWithNothingFailedDoesNothing() = runTest {
    val source = Source()
    lateinit var paging: Paging<String, Unit>

    presenterTestOf({
      paging = rememberRetainedPaging(idOf = id, load = source::load)
      PagingUi(paging.state)
    }) {
      source.page(null, "A")
      awaitPaged { it.hasLoaded }

      paging.retry()
      testScheduler.advanceUntilIdle()
      assertEquals(listOf<String?>(null), source.requests, "retry with nothing failed")

      paging.refresh()
      awaitPaged { it.refresh is LoadStatus.Loading }
      source.fail(null)
      val failed = awaitPaged { it.refresh is LoadStatus.Failed }
      assertEquals(listOf("A"), failed.items, "a failed refresh keeps the items")

      paging.retry()
      val retrying = awaitPaged { it.refresh is LoadStatus.Loading }
      assertEquals(listOf("A"), retrying.items)
      assertEquals(listOf<String?>(null, null, null), source.requests)
    }
  }

  @Test
  fun aRefreshAbandonsAPageInFlight() = runTest {
    val source = Source()
    lateinit var paging: Paging<String, Unit>

    presenterTestOf({
      paging = rememberRetainedPaging(idOf = id, load = source::load)
      PagingUi(paging.state)
    }) {
      source.page(null, "A", next = "c1")
      awaitPaged { it.hasLoaded }
      paging.loadMore()
      awaitPaged { it.append is LoadStatus.Loading }

      paging.refresh()
      val refreshing = awaitPaged { it.refresh is LoadStatus.Loading }
      assertEquals(LoadStatus.Idle, refreshing.append)

      // The abandoned page answering late changes nothing.
      source.page("c1", "STALE")
      source.page(null, "X", next = "n1")
      assertEquals(listOf("X"), awaitPaged { it.refresh is LoadStatus.Idle }.items)
    }
  }

  // --- Key changes ------------------------------------------------------------------------------

  @Test
  fun aNewKeyKeepsTheItemsUntilItsFirstPageAndDropsTheOldKeysPage() = runTest {
    val source = Source()
    val key = mutableIntStateOf(0)
    lateinit var paging: Paging<String, Unit>

    presenterTestOf({
      paging = rememberRetainedPaging(key.intValue, idOf = id, load = source::load)
      PagingUi(paging.state)
    }) {
      source.page(null, "ann1", next = "c1")
      awaitPaged { it.hasLoaded }
      paging.loadMore()
      awaitPaged { it.append is LoadStatus.Loading }

      changeKey { key.intValue = 1 }

      val restarting = awaitPaged { it.refresh is LoadStatus.Loading }
      assertEquals(listOf("ann1"), restarting.items)
      assertFalse(restarting.canLoadMore, "the old key's cursor")

      source.page("c1", "STALE")
      source.page(null, "anna1")
      assertEquals(listOf("anna1"), awaitPaged { it.refresh is LoadStatus.Idle }.items)
    }
  }

  @Test
  fun aNewKeyClearsTheItemsWhenAsked() = runTest {
    val source = Source()
    val key = mutableIntStateOf(0)
    lateinit var paging: Paging<String, Unit>

    presenterTestOf({
      paging =
        rememberRetainedPaging(
          key.intValue,
          idOf = id,
          onKeyChange = OnKeyChange.ClearItems,
          load = source::load,
        )
      PagingUi(paging.state)
    }) {
      source.page(null, "A")
      awaitPaged { it.hasLoaded }

      changeKey { key.intValue = 1 }

      val cleared = awaitPaged { !it.hasLoaded }
      assertEquals(emptyList<String>(), cleared.items)
      assertEquals(LoadStatus.Loading, cleared.refresh)
    }
  }

  // --- Coming back ------------------------------------------------------------------------------

  @Test
  fun aListReachedAgainShowsItsPagesWithoutRequestingThem() = runTest {
    val source = Source()
    val shown = mutableStateOf(true)
    lateinit var paging: Paging<String, Unit>

    presenterTestOf({
      val holder = rememberRetainedStateHolder()
      if (shown.value) {
        holder.RetainedStateProvider("list") {
          paging = rememberRetainedPaging(idOf = id, load = source::load)
        }
      }
      PagingUi(paging.state, shown.value)
    }) {
      source.page(null, "A", next = "c1")
      awaitPaged { it.hasLoaded }
      paging.loadMore()
      awaitPaged { it.append is LoadStatus.Loading }
      source.page("c1", "B", next = "c2")
      awaitPaged { it.items.size == 2 }

      show(shown, false)
      awaitItem()
      show(shown, true)

      val back = awaitItem()
      assertTrue(back.shown)
      assertEquals(listOf("A", "B"), back.paged.items)
      assertEquals(listOf(null, "c1"), source.requests, "settled pages are not requested again")
    }
  }

  @Test
  fun aPageInterruptedByLeavingIsRequestedAgainOnReturn() = runTest {
    val source = Source()
    val shown = mutableStateOf(true)
    lateinit var paging: Paging<String, Unit>

    presenterTestOf({
      val holder = rememberRetainedStateHolder()
      if (shown.value) {
        holder.RetainedStateProvider("list") {
          paging = rememberRetainedPaging(idOf = id, load = source::load)
        }
      }
      PagingUi(paging.state, shown.value)
    }) {
      source.page(null, "A", next = "c1")
      awaitPaged { it.hasLoaded }
      paging.loadMore()
      awaitPaged { it.append is LoadStatus.Loading }

      show(shown, false)
      awaitItem()
      show(shown, true)
      awaitItem()

      assertEquals(listOf(null, "c1", "c1"), source.requests)
      assertEquals(LoadStatus.Loading, paging.state.append)
      source.page("c1", "B")
      assertEquals(listOf("A", "B"), awaitPaged { it.items.size == 2 }.items)
    }
  }

  // --- enabled and initial ----------------------------------------------------------------------

  @Test
  fun aDisabledListLoadsNothingUntilEnabled() = runTest {
    val source = Source()
    val enabled = mutableStateOf(false)
    lateinit var paging: Paging<String, Unit>

    presenterTestOf({
      paging = rememberRetainedPaging(idOf = id, enabled = enabled.value, load = source::load)
      PagingUi(paging.state)
    }) {
      val disabled = awaitPaged { it.refresh is LoadStatus.Idle }
      assertEquals(emptyList<String>(), disabled.items)
      assertFalse(disabled.hasLoaded)
      assertEquals(LoadStatus.Idle, disabled.append)
      paging.loadMore()
      testScheduler.advanceUntilIdle()
      assertEquals(emptyList<String?>(), source.requests)

      changeKey { enabled.value = true }

      awaitPaged { it.refresh is LoadStatus.Loading }
      assertEquals(listOf<String?>(null), source.requests)
    }
  }

  @Test
  fun aListStartedFromAPageItHasRequestsNothingUntilLoadMore() = runTest {
    val source = Source()
    val replies = Outcome.Data(pageOf(listOf("R1", "R2"), next = "r"), Origin.Network)
    lateinit var paging: Paging<String, Unit>

    presenterTestOf({
      paging = rememberRetainedPaging(idOf = id, initial = replies, load = source::load)
      PagingUi(paging.state)
    }) {
      val seeded = awaitPaged { it.hasLoaded }
      assertEquals(listOf("R1", "R2"), seeded.items)
      testScheduler.advanceUntilIdle()
      assertEquals(emptyList<String?>(), source.requests)

      paging.loadMore()
      awaitPaged { it.append is LoadStatus.Loading }
      assertEquals(listOf<String?>("r"), source.requests)
    }
  }

  // --- update -----------------------------------------------------------------------------------

  @Test
  fun anEditShowsAtOnceWithoutARequest() = runTest {
    val source = Source()
    lateinit var paging: Paging<String, Unit>

    presenterTestOf({
      paging = rememberRetainedPaging(idOf = id, load = source::load)
      PagingUi(paging.state)
    }) {
      source.page(null, "A", "B", "C")
      awaitPaged { it.hasLoaded }

      paging.update { items -> items.filterNot { it == "B" } }

      assertEquals(listOf("A", "C"), awaitPaged { it.items.size == 2 }.items)
      assertEquals(listOf<String?>(null), source.requests)
    }
  }
}

// The writes below happen outside the composition, so the recomposer only sees them once apply
// notifications are sent.
private fun TestScope.changeKey(change: () -> Unit) {
  change()
  Snapshot.sendApplyNotifications()
  testScheduler.advanceUntilIdle()
}

private fun TestScope.show(shown: MutableState<Boolean>, value: Boolean) = changeKey {
  shown.value = value
}

private suspend fun ReceiveTurbine<PagingUi>.awaitPaged(
  predicate: (PagedState<String, Unit>) -> Boolean
): PagedState<String, Unit> {
  while (true) {
    val state = awaitItem().paged
    if (predicate(state)) return state
  }
}
