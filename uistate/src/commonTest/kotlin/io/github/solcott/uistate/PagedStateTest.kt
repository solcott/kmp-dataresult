package io.github.solcott.uistate

import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PagedStateTest {

  private val id: (String) -> Any? = { it }

  private fun page(vararg items: String, next: String? = null, meta: String = "H") =
    Page(items.toList(), meta, next)

  private fun network(page: Page<String, String>) = Outcome.Data(page, Origin.Network)

  private fun cache(page: Page<String, String>) = Outcome.Data(page, Origin.Cache)

  private val failure = Outcome.Error(DataError.Network, Origin.Network)

  private fun initial() = pagedStateOf<String, String>()

  /** A, B then C, D: two pages, the second with a next cursor "c2". */
  private fun twoPages(): PagedState<String, String> =
    initial()
      .applyFirstPage(network(page("A", "B", next = "c1")), id)
      .applyNextPage("c1", network(page("C", "D", next = "c2")), id)

  // --- Starting state ---------------------------------------------------------------------------

  @Test
  fun nothingLoadedYet() {
    val state = initial()

    assertFalse(state.hasLoaded)
    assertEquals(emptyList(), state.items)
    assertNull(state.meta)
    assertEquals(LoadStatus.Loading, state.refresh)
    assertEquals(LoadStatus.Idle, state.append)
    assertFalse(state.canLoadMore)
  }

  @Test
  fun statesAreEqualByValue() {
    assertEquals(twoPages(), twoPages())
    assertEquals(twoPages().hashCode(), twoPages().hashCode())
    assertNotEquals(twoPages(), twoPages().refreshing())
  }

  // --- First page ------------------------------------------------------------------------------

  @Test
  fun firstPageArrives() {
    val state = initial().applyFirstPage(network(page("A", "B", next = "c1")), id)

    assertEquals(listOf("A", "B"), state.items)
    assertEquals("H", state.meta)
    assertEquals(Origin.Network, state.origin)
    assertEquals(LoadStatus.Idle, state.refresh)
    assertFalse(state.endReached)
    assertTrue(state.canLoadMore)
    assertEquals("c1", state.nextCursor)
  }

  @Test
  fun lastPageReachesTheEnd() {
    val state = initial().applyFirstPage(network(page("A")), id)

    assertTrue(state.endReached)
    assertFalse(state.canLoadMore)
    assertNull(state.nextCursor)
  }

  @Test
  fun anEmptyFirstPageIsAnAnswer() {
    val state = initial().applyFirstPage(network(page()), id)

    assertTrue(state.hasLoaded)
    assertTrue(state.hasAnswer)
    assertEquals(emptyList(), state.items)
    assertTrue(state.endReached)
  }

  @Test
  fun theHeaderComesFromTheFirstPageOnly() {
    val state =
      initial()
        .applyFirstPage(network(page("A", next = "c1")), id)
        .applyNextPage("c1", network(page("B", meta = "H2")), id)

    assertEquals("H", state.meta)
  }

  // --- Next pages -------------------------------------------------------------------------------

  @Test
  fun theNextPageAppends() {
    val first = initial().applyFirstPage(network(page("A", "B", next = "c1")), id)

    val loading = first.applyNextPage("c1", Outcome.Loading, id)
    assertEquals(LoadStatus.Loading, loading.append)
    assertEquals(listOf("A", "B"), loading.items)
    assertFalse(loading.canLoadMore, "one next page at a time")

    val loaded = loading.applyNextPage("c1", network(page("C", "D", next = "c2")), id)
    assertEquals(listOf("A", "B", "C", "D"), loaded.items)
    assertEquals(LoadStatus.Idle, loaded.append)
    assertEquals("c2", loaded.nextCursor)
  }

  @Test
  fun anItemThatMovedBetweenPagesKeepsItsFirstPlace() {
    val state =
      initial()
        .applyFirstPage(network(page("A", "B", "C", next = "c1")), id)
        .applyNextPage("c1", network(page("C", "D")), id)

    assertEquals(listOf("A", "B", "C", "D"), state.items)
  }

  @Test
  fun aPageFromAStaleCursorIsDropped() {
    val state = twoPages()

    // "c1" was answered and replaced by a refresh; "zz" was never requested.
    assertSame(state, state.applyNextPage("zz", network(page("X")), id))
    val refreshed = state.refreshing().applyFirstPage(network(page("Y", next = "n1")), id)
    assertSame(refreshed, refreshed.applyNextPage("c2", network(page("Z")), id))
  }

  // --- Failures ---------------------------------------------------------------------------------

  @Test
  fun aFailedPageKeepsTheEarlierPages() {
    val state = twoPages().applyNextPage("c2", Outcome.Loading, id).applyNextPage("c2", failure, id)

    assertEquals(listOf("A", "B", "C", "D"), state.items)
    assertEquals(LoadStatus.Failed(DataError.Network), state.append)
    assertEquals(LoadStatus.Idle, state.refresh)
    assertFalse(state.canLoadMore, "a failed page is retried, not skipped")
  }

  @Test
  fun aFailedRefreshKeepsTheItems() {
    val state = twoPages().refreshing().applyFirstPage(failure, id)

    assertEquals(listOf("A", "B", "C", "D"), state.items)
    assertEquals("H", state.meta)
    assertEquals(Origin.Network, state.origin)
    assertEquals(LoadStatus.Failed(DataError.Network), state.refresh)
    // The pages are still this key's, so paging carries on from them.
    assertTrue(state.canLoadMore)
  }

  @Test
  fun aFailedFirstLoadHasLoadedNothing() {
    val state = initial().applyFirstPage(failure, id)

    assertFalse(state.hasLoaded)
    assertEquals(emptyList(), state.items)
    assertEquals(LoadStatus.Failed(DataError.Network), state.refresh)
  }

  // --- Refresh ----------------------------------------------------------------------------------

  @Test
  fun aRefreshReplacesEveryPage() {
    val state = twoPages().refreshing()
    assertEquals(LoadStatus.Loading, state.refresh)
    assertEquals(listOf("A", "B", "C", "D"), state.items)
    assertFalse(state.canLoadMore)

    // Same next cursor as before: still replaces the second page, which was read before.
    val refreshed = state.applyFirstPage(network(page("X", "Y", next = "c1")), id)
    assertEquals(listOf("X", "Y"), refreshed.items)
    assertEquals("c1", refreshed.nextCursor)
  }

  @Test
  fun aRefreshAbandonsAPageInFlight() {
    val state = twoPages().applyNextPage("c2", Outcome.Loading, id).refreshing()

    assertEquals(LoadStatus.Idle, state.append)
  }

  // --- Settling ---------------------------------------------------------------------------------

  @Test
  fun aSourceThatCompletesWithoutEmittingSettles() {
    val loading = twoPages().applyNextPage("c2", Outcome.Loading, id)

    val settled = loading.nextPageCompleted("c2")

    assertEquals(LoadStatus.Idle, settled.append)
    assertEquals(listOf("A", "B", "C", "D"), settled.items)
    assertEquals(LoadStatus.Idle, initial().firstPageCompleted().refresh)
  }

  @Test
  fun aStaleCompletionChangesNothing() {
    val loading = twoPages().applyNextPage("c2", Outcome.Loading, id)

    assertSame(loading, loading.nextPageCompleted("zz"))
  }

  // --- Key changes ------------------------------------------------------------------------------

  @Test
  fun keepingItemsOnANewKeyHoldsThemUntilTheNewFirstPage() {
    val restarted = twoPages().restarted(OnKeyChange.KeepItems)

    assertEquals(listOf("A", "B", "C", "D"), restarted.items)
    assertEquals("H", restarted.meta)
    assertEquals(LoadStatus.Loading, restarted.refresh)
    assertFalse(restarted.canLoadMore, "the next cursor belongs to the old key")
    // The old key's next page never lands in the new key's list.
    assertSame(restarted, restarted.applyNextPage("c2", network(page("Z")), id))

    val settled = restarted.applyFirstPage(network(page("N", next = "n1", meta = "H2")), id)
    assertEquals(listOf("N"), settled.items)
    assertEquals("H2", settled.meta)
    assertTrue(settled.canLoadMore)
  }

  @Test
  fun keptItemsStayWhenTheNewKeysFirstPageFails() {
    val failed = twoPages().restarted(OnKeyChange.KeepItems).applyFirstPage(failure, id)

    assertEquals(listOf("A", "B", "C", "D"), failed.items)
    assertEquals(LoadStatus.Failed(DataError.Network), failed.refresh)
    assertFalse(failed.canLoadMore)
  }

  @Test
  fun clearingItemsOnANewKeyDropsThemAtOnce() {
    val restarted = twoPages().restarted(OnKeyChange.ClearItems)

    assertEquals(emptyList(), restarted.items)
    assertNull(restarted.meta)
    assertFalse(restarted.hasLoaded)
    assertEquals(LoadStatus.Loading, restarted.refresh)
  }

  // --- Re-emitted pages -------------------------------------------------------------------------

  @Test
  fun aCachedFirstPageIsReplacedByTheNetworkInPlace() {
    val cached =
      initial()
        .applyFirstPage(cache(page("A", "B", next = "c1")), id)
        .applyNextPage("c1", network(page("C", next = "c2")), id)

    val fresh = cached.applyFirstPage(network(page("A", "B", "X", next = "c1")), id)

    assertEquals(listOf("A", "B", "X", "C"), fresh.items)
    assertEquals(Origin.Network, fresh.origin)
  }

  @Test
  fun aReEmittedPageWithANewCursorDropsThePagesAfterIt() {
    val state = twoPages().applyFirstPage(network(page("A", "B", next = "other")), id)

    assertEquals(listOf("A", "B"), state.items)
    assertEquals("other", state.nextCursor)
  }

  @Test
  fun aReEmittedLaterPageReplacesOnlyItself() {
    val state =
      twoPages()
        .applyNextPage("c2", network(page("E")), id)
        .applyNextPage("c1", network(page("C", "D2", next = "c2")), id)

    assertEquals(listOf("A", "B", "C", "D2", "E"), state.items)
  }

  // --- hasAnswer --------------------------------------------------------------------------------

  @Test
  fun anEmptyCachedFirstPageIsNotAnAnswerWhileLoading() {
    val state = initial().applyFirstPage(cache(page()), id).applyFirstPage(Outcome.Loading, id)

    assertTrue(state.hasLoaded)
    assertFalse(state.hasAnswer)
  }

  @Test
  fun anEmptyCachedFirstPageIsNotAnAnswerWhenTheNetworkFails() {
    val state =
      initial()
        .applyFirstPage(cache(page()), id)
        .applyFirstPage(Outcome.Loading, id)
        .applyFirstPage(failure, id)

    assertFalse(state.hasAnswer)
    assertEquals(LoadStatus.Failed(DataError.Network), state.refresh)
  }

  @Test
  fun anEmptyCachedFirstPageThatSettlesIsAnAnswer() {
    val state = initial().applyFirstPage(cache(page()), id).firstPageCompleted()

    assertTrue(state.hasAnswer)
  }

  // --- Optimistic edits -------------------------------------------------------------------------

  @Test
  fun anEditRemovesAnItemWithoutARequest() {
    val state = initial().applyFirstPage(network(page("A", "B", "C", next = "c1")), id)

    val edited = state.updated(id) { items -> items.filterNot { it == "B" } }

    assertEquals(listOf("A", "C"), edited.items)
    assertEquals("c1", edited.nextCursor)
  }

  @Test
  fun anEditSurvivesLoadingMore() {
    val edited =
      initial().applyFirstPage(network(page("A", "B", "C", next = "c1")), id).updated(id) { items ->
        items.filterNot { it == "B" }
      }

    val more = edited.applyNextPage("c1", network(page("D")), id)

    assertEquals(listOf("A", "C", "D"), more.items)
  }

  @Test
  fun anEditedItemStaysOnItsPage() {
    // Removing C empties the second page; an item added after D joins D's page, so a re-emitted
    // first page replaces A and B and nothing else.
    val edited = twoPages().updated(id) { items -> (items - "C") + "E" }
    assertEquals(listOf("A", "B", "D", "E"), edited.items)

    val reEmitted = edited.applyFirstPage(network(page("A", next = "c1")), id)
    assertEquals(listOf("A", "D", "E"), reEmitted.items)
  }

  // --- Seeded ---------------------------------------------------------------------------------

  @Test
  fun aStateCanStartFromAPageItAlreadyHas() {
    val state = pagedStateOf(network(page("R1", "R2", next = "r")), id)

    assertEquals(listOf("R1", "R2"), state.items)
    assertTrue(state.hasLoaded)
    assertEquals(LoadStatus.Idle, state.refresh)
    assertEquals("r", state.nextCursor)
  }
}
