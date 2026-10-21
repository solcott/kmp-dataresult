package io.github.solcott.uistate.circuit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.slack.circuit.retained.rememberRetained
import io.github.solcott.dataresult.Outcome
import io.github.solcott.uistate.LoadStatus
import io.github.solcott.uistate.OnKeyChange
import io.github.solcott.uistate.Page
import io.github.solcott.uistate.PagedState
import io.github.solcott.uistate.applyFirstPage
import io.github.solcott.uistate.applyNextPage
import io.github.solcott.uistate.firstPageCompleted
import io.github.solcott.uistate.nextPageCompleted
import io.github.solcott.uistate.pagedStateOf
import io.github.solcott.uistate.refreshing
import io.github.solcott.uistate.restarted
import io.github.solcott.uistate.updated
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * A list loaded a page at a time, held in Circuit's retained registry: [state] to render, and the
 * requests a screen makes of it. Get one from [rememberRetainedPaging].
 *
 * The methods only queue a request; the holder decides whether it can run. They are safe to call
 * from an event handler, and calling one when it can't proceed (load more at the end, retry with
 * nothing failed) does nothing.
 */
@Stable
class Paging<T, M> internal constructor(internal var idOf: (T) -> Any?) {
  /** The list as it should be rendered. */
  var state: PagedState<T, M> by mutableStateOf(pagedStateOf())
    private set

  // The keys the held pages were loaded for: null before the first load and while disabled. Kept
  // here, in the retained holder, so that a return to the screen can tell "same list, carry on"
  // from "new key, restart".
  private var loadedFor: List<Any?>? = null

  // Every request whose source hadn't completed, by cursor (null for the first page). It outlives
  // the driver on purpose: when the screen leaves, the driver is cancelled and these stay, and on
  // return exactly these are requested again. A request that finished, or failed, is not in here.
  private val pending = mutableSetOf<String?>()

  // The cursor of the last next-page request, for retrying it after it failed.
  private var appendAfter: String? = null

  // UNLIMITED, not CONFLATED: a refresh followed by a load more must not lose the refresh.
  private val commands = Channel<Command>(Channel.UNLIMITED)

  /** Requests the next page, unless one is loading, the last one failed, or the end is reached. */
  fun loadMore() {
    commands.trySend(Command.LoadMore)
  }

  /**
   * Repeats what failed, and only that: the first page if the refresh failed, otherwise the next
   * page if it failed, keeping the pages before it. Does nothing if nothing failed.
   */
  fun retry() {
    commands.trySend(Command.Retry)
  }

  /** Requests the first page again, keeping the items on screen until it replaces them all. */
  fun refresh() {
    commands.trySend(Command.Refresh)
  }

  /**
   * Edits the items shown without a request, such as removing one after unblocking it. The edit
   * lasts until a refresh, a new key, or the page holding it is emitted again.
   */
  fun update(transform: (List<T>) -> List<T>) {
    state = state.updated(idOf, transform)
  }

  @Suppress("CyclomaticComplexMethod")
  internal suspend fun drive(
    keys: List<Any?>,
    enabled: Boolean,
    onKeyChange: OnKeyChange,
    initial: Outcome.Data<Page<T, M>>?,
    load: (String?) -> Flow<Outcome<Page<T, M>>>,
  ) = coroutineScope {
    val jobs = HashMap<String?, Job>()

    fun collect(after: String?) {
      jobs.remove(after)?.cancel()
      pending += after
      jobs[after] = launch {
        load(after)
          .onEach { outcome ->
            state =
              if (after == null) state.applyFirstPage(outcome, idOf)
              else state.applyNextPage(after, outcome, idOf)
          }
          // Only on `cause == null`. Cancellation completes the flow too -- a refresh discarding
          // this request, or the screen leaving -- and an abandoned request must neither read as
          // finished nor drop out of `pending`, or a return would not request it again.
          .onCompletion { cause ->
            if (cause == null) {
              pending -= after
              state =
                if (after == null) state.firstPageCompleted() else state.nextPageCompleted(after)
            }
          }
          .collect()
      }
    }

    fun abandonAll() {
      jobs.values.forEach { it.cancel() }
      jobs.clear()
      pending.clear()
    }

    fun refresh() {
      abandonAll()
      state = state.refreshing()
      collect(null)
    }

    if (!enabled) {
      abandonAll()
      loadedFor = null
      state = pagedStateOf<T, M>().firstPageCompleted()
      // Nothing can be requested while disabled; drop what is asked rather than replay it later.
      // This suspends until the effect restarts, which a change to `enabled` does. `receiveAsFlow`,
      // not `consumeAsFlow`: consuming would close the retained channel when the effect is
      // cancelled.
      commands.receiveAsFlow().collect {}
      return@coroutineScope
    }

    if (loadedFor != keys) {
      loadedFor = keys
      abandonAll()
      appendAfter = null
      // Asked of the previous key's list, not this one's.
      while (commands.tryReceive().isSuccess) continue
      if (initial != null) {
        state = pagedStateOf(initial, idOf)
      } else {
        state = state.restarted(onKeyChange)
        collect(null)
      }
    } else {
      // The screen came back. Settled pages are already on screen; ask again only for what the
      // departure interrupted.
      pending.toList().forEach(::collect)
    }

    for (command in commands) {
      when (command) {
        Command.LoadMore ->
          state.nextCursor?.let { after ->
            appendAfter = after
            state = state.applyNextPage(after, Outcome.Loading, idOf)
            collect(after)
          }
        Command.Refresh -> refresh()
        Command.Retry ->
          when {
            state.refresh is LoadStatus.Failed -> refresh()
            state.append is LoadStatus.Failed ->
              appendAfter?.let { after ->
                state = state.applyNextPage(after, Outcome.Loading, idOf)
                collect(after)
              }
          }
      }
    }
  }

  private enum class Command {
    LoadMore,
    Retry,
    Refresh,
  }
}

/**
 * A list loaded a page at a time from [load], retained in Circuit's registry and restarted on
 * [keys]. This is `CursorPaging` done with the rules `produceRetainedContentState` keeps:
 * - A failed refresh keeps the items, and a failed page keeps the pages before it. [Paging.retry]
 *   repeats only what failed.
 * - Coming back to the screen shows the pages at once. Only a request the departure interrupted is
 *   made again.
 * - A new key keeps the old items under a refresh indicator until its first page arrives, or clears
 *   them at once, as [onKeyChange] says. A request for the old key never lands in the new list.
 *
 * [load] returns the flow for the page after `cursor`, or the first page for null. A one-shot read
 * is `flow { emit(repository.page(cursor)) }`. A flow that emits more than once (a cached read then
 * the network, or a database watcher) replaces its page each time; see `PagedState`. A flow that
 * never completes is collected while the screen is composed and collected again on every return,
 * which is right for a watcher. A network read completes, so a return doesn't refetch it.
 *
 * [idOf] de-duplicates items across pages, keeping the first occurrence. While [enabled] is false
 * nothing loads and the list is empty, as for a blank search query. [initial] starts the list from
 * a page already in hand, such as the replies that came with a comment thread, and nothing is
 * requested until [Paging.loadMore]. For nested lists, use one holder per parent inside
 * `key(parent.id)`.
 *
 * ### Circuit will silently stop paging a paused record
 *
 * The same hazard as [produceRetainedContentState]: in a multi-pane layout, the pane that isn't on
 * top is paused, its driver stops, and calls to [Paging.loadMore] queue with **no error anywhere**.
 * Wrap a pane that should keep running in `ProvideRecordLifecycle(isActive = true)`.
 */
@Composable
fun <T, M> rememberRetainedPaging(
  vararg keys: Any?,
  idOf: (T) -> Any?,
  onKeyChange: OnKeyChange = OnKeyChange.KeepItems,
  enabled: Boolean = true,
  initial: Outcome.Data<Page<T, M>>? = null,
  load: (cursor: String?) -> Flow<Outcome<Page<T, M>>>,
): Paging<T, M> {
  // Unkeyed: keying the retained holder would drop the items that KeepItems must keep. Only the
  // driver below restarts on the keys.
  val paging = rememberRetained { Paging<T, M>(idOf) }
  // The driver restarts on keys and enabled alone, so it must read whichever lambdas are current.
  val currentLoad by rememberUpdatedState(load)
  val currentInitial by rememberUpdatedState(initial)
  val currentOnKeyChange by rememberUpdatedState(onKeyChange)
  SideEffect { paging.idOf = idOf }
  LaunchedEffect(paging, enabled, *keys) {
    paging.drive(keys.toList(), enabled, currentOnKeyChange, currentInitial) { currentLoad(it) }
  }
  return paging
}
