package io.github.solcott.uistate

import androidx.compose.runtime.Immutable
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome

/**
 * One page of a cursor-paged source: its [items], a header ([meta]) and the cursor of the page
 * after it, or null for the last page.
 *
 * [meta] is whatever the source sends with a page that is not an item, such as a leaderboard's
 * entry for the viewer or the account a follow list belongs to. Only the first page's is kept. A
 * list with no header uses [pageOf], whose meta is [Unit].
 *
 * The cursor is a `String` rather than a type parameter. Cursor-paged APIs (atproto, GraphQL Relay)
 * hand out opaque string tokens, and an offset encodes as one losslessly. A type parameter would
 * add a third generic to every signature, and Swift export would show it as an untyped `Any?`.
 *
 * `@Immutable`, as `Outcome` is: never mutate [items] in place.
 */
@Immutable data class Page<out T, out M>(val items: List<T>, val meta: M, val next: String?)

/** A [Page] with no header. */
fun <T> pageOf(items: List<T>, next: String?): Page<T, Unit> = Page(items, Unit, next)

/** What a paged list does with its items when the key it pages changes. */
enum class OnKeyChange {
  /**
   * Keep showing the old key's items, marked refreshing, until the new key's first page arrives or
   * fails. Right for a search, where the old results are a better thing to look at than an empty
   * screen while the new query runs. This is what `ContentState` does on a key change.
   */
  KeepItems,

  /**
   * Drop the old key's items at once and show nothing loaded. Right for a filter, where the old
   * items would be wrong under the new filter's heading.
   */
  ClearItems,
}

/**
 * View state for a list loaded a page at a time: what `ContentState` is for a single value.
 *
 * Fold every page a source emits into it with [applyFirstPage] and [applyNextPage]. The rules are
 * `ContentState`'s, applied per request:
 * - [items] change only when a page arrives. A failure or a new request moves [refresh] or [append]
 *   and keeps the items, so a failed refresh doesn't blank the list and a failed page doesn't lose
 *   the pages before it.
 * - [refresh] is the first page's request and [append] the next page's. They are separate because a
 *   screen shows them differently, a refresh indicator or full-screen error against a footer
 *   spinner or inline retry, and because a retry has to know which of the two failed.
 * - [hasLoaded] is `origin != null`: an empty first page is an answer.
 *
 * ### Pages are slots
 *
 * The state remembers each page separately: the cursor it was requested with, its next cursor and
 * its items. A page's source may emit more than once (a cached read and then the network, or a
 * database watcher), and each emission replaces that page's items in place. If its next cursor is
 * unchanged the pages after it are kept, so a local write doesn't lose the scroll position. If it
 * changed, the pages after it were read from a cursor that no longer exists, so they are dropped.
 *
 * A next page applies only if it was requested with the last page's next cursor, or is a page
 * already held. Anything else is a result that a refresh or key change has made stale, and the fold
 * drops it. That one check is what keeps an old request from landing in a new list.
 *
 * ### Items are de-duplicated
 *
 * An index can move an item from one page to the next between requests. [items] keeps the first
 * occurrence of each id, as given by the `idOf` passed to each fold call. It is passed rather than
 * stored because a stored lambda would break `equals`.
 *
 * `@Immutable` for the same reason as `ContentState`: this module isn't built with the Compose
 * compiler. Never mutate a page's items in place.
 */
@Immutable
class PagedState<T, M>
internal constructor(
  internal val slots: List<Slot<T>>,
  /** The first page's header, or null until a first page arrives. */
  val meta: M?,
  /** Where the first page came from, or null until one arrives. */
  val origin: Origin?,
  /** The first page's request: a first load, a refresh or a new key's first page. */
  val refresh: LoadStatus,
  /** The latest next-page request. Idle until a next page is first requested. */
  val append: LoadStatus,
  /**
   * False after a key change that kept the items, until the new key's first page arrives. The slots
   * then belong to the old key, and their next cursor must not be requested for the new one.
   */
  internal val current: Boolean,
  /**
   * Set by a refresh or a key change: the next first page replaces every slot rather than only the
   * first. Without it a refresh that came back with the same next cursor would keep pages read
   * before the refresh.
   */
  internal val replaceOnFirstPage: Boolean,
  /** Every page's items in order, de-duplicated by id. Computed once per fold, where `idOf` is. */
  val items: List<T>,
) {
  internal class Slot<T>(val after: String?, val next: String?, val items: List<T>) {
    override fun equals(other: Any?): Boolean =
      other is Slot<*> && after == other.after && next == other.next && items == other.items

    override fun hashCode(): Int = (after.hashCode() * 31 + next.hashCode()) * 31 + items.hashCode()
  }

  /** True once the last page held has no next cursor. */
  val endReached: Boolean
    get() = slots.isNotEmpty() && slots.last().next == null

  /**
   * The cursor the next page should be requested with, or null if no next page can be requested
   * now. For a host that drives the fold itself; a screen wants [canLoadMore].
   */
  val nextCursor: String?
    get() = if (canLoadMore) slots.last().next else null

  /**
   * True when a next page can be requested now: a first page for the current key has arrived, the
   * end isn't reached, and neither a refresh nor a next page is in flight or failed. A failed page
   * is retried rather than skipped, so it blocks this until the retry.
   */
  val canLoadMore: Boolean
    get() =
      current &&
        slots.isNotEmpty() &&
        slots.last().next != null &&
        refresh !is LoadStatus.Loading &&
        append is LoadStatus.Idle

  internal fun copy(
    slots: List<Slot<T>> = this.slots,
    meta: M? = this.meta,
    origin: Origin? = this.origin,
    refresh: LoadStatus = this.refresh,
    append: LoadStatus = this.append,
    current: Boolean = this.current,
    replaceOnFirstPage: Boolean = this.replaceOnFirstPage,
    items: List<T> = this.items,
  ): PagedState<T, M> =
    PagedState(slots, meta, origin, refresh, append, current, replaceOnFirstPage, items)

  override fun equals(other: Any?): Boolean =
    other is PagedState<*, *> &&
      slots == other.slots &&
      meta == other.meta &&
      origin == other.origin &&
      refresh == other.refresh &&
      append == other.append &&
      current == other.current &&
      replaceOnFirstPage == other.replaceOnFirstPage

  override fun hashCode(): Int {
    var result = slots.hashCode()
    result = 31 * result + meta.hashCode()
    result = 31 * result + origin.hashCode()
    result = 31 * result + refresh.hashCode()
    result = 31 * result + append.hashCode()
    result = 31 * result + current.hashCode()
    result = 31 * result + replaceOnFirstPage.hashCode()
    return result
  }

  override fun toString(): String =
    "PagedState(items=$items, meta=$meta, origin=$origin, refresh=$refresh, append=$append, " +
      "endReached=$endReached)"
}

/** A paged list that has loaded nothing yet, with its first page's request in flight. */
fun <T, M> pagedStateOf(): PagedState<T, M> =
  PagedState(
    slots = emptyList(),
    meta = null,
    origin = null,
    refresh = LoadStatus.Loading,
    append = LoadStatus.Idle,
    current = true,
    replaceOnFirstPage = false,
    items = emptyList(),
  )

/**
 * A paged list that starts from a page it already has, settled, such as the first replies that came
 * with a comment thread. Nothing needs requesting until the next page is asked for.
 */
fun <T, M> pagedStateOf(initial: Outcome.Data<Page<T, M>>, idOf: (T) -> Any?): PagedState<T, M> =
  pagedStateOf<T, M>().applyFirstPage(initial, idOf)

/**
 * True once a first page has arrived, whatever it held and whether or not a later request failed.
 * See `ContentState.hasLoaded`: an empty list is an answer.
 */
val PagedState<*, *>.hasLoaded: Boolean
  get() = origin != null

/**
 * True once the items are an *answer*: like [hasLoaded], except that an empty first page from
 * [Origin.Cache] doesn't count while [PagedState.refresh] is loading or failed. That is a cache
 * miss, not "nothing here". See `ContentState.hasAnswer`, which this is with `List.isEmpty`.
 */
val PagedState<*, *>.hasAnswer: Boolean
  get() = hasLoaded && !(origin == Origin.Cache && refresh !is LoadStatus.Idle && items.isEmpty())

/**
 * Folds one emission of the first page's source.
 *
 * [Outcome.Loading] and [Outcome.Error] move [PagedState.refresh] and keep everything held.
 * [Outcome.Data] sets the header and origin. After [refreshing] or [restarted] it replaces every
 * page; otherwise it is the first page re-emitted (cache then network, a watcher) and replaces only
 * the first page, keeping the pages after it if its next cursor didn't change.
 */
fun <T, M> PagedState<T, M>.applyFirstPage(
  outcome: Outcome<Page<T, M>>,
  idOf: (T) -> Any?,
): PagedState<T, M> =
  when (outcome) {
    Outcome.Loading -> copy(refresh = LoadStatus.Loading)
    is Outcome.Error -> copy(refresh = LoadStatus.Failed(outcome.cause), replaceOnFirstPage = false)
    is Outcome.Data -> {
      val page = outcome.data
      val first = PagedState.Slot(after = null, next = page.next, items = page.items)
      val replaceAll = replaceOnFirstPage || !current || slots.isEmpty()
      val keepRest = !replaceAll && slots.first().next == page.next
      val slots = if (keepRest) listOf(first) + slots.drop(1) else listOf(first)
      copy(
          slots = slots,
          meta = page.meta,
          origin = outcome.origin,
          refresh = LoadStatus.Idle,
          // A next page in flight was requested from a cursor that this page just replaced.
          append = if (keepRest) append else LoadStatus.Idle,
          current = true,
          replaceOnFirstPage = false,
        )
        .withItems(idOf)
    }
  }

/**
 * Folds one emission of the source of the page requested with cursor [after].
 *
 * It applies if [after] is the last page's next cursor (the page being requested) or the cursor of
 * a page already held (that page re-emitted). Any other emission is from a request that a refresh
 * or key change made stale, and is dropped. [Outcome.Loading] and [Outcome.Error] move
 * [PagedState.append] and keep every page. [Outcome.Data] appends the page or replaces the one
 * held, dropping the pages after it if its next cursor changed.
 */
fun <T, M> PagedState<T, M>.applyNextPage(
  after: String,
  outcome: Outcome<Page<T, M>>,
  idOf: (T) -> Any?,
): PagedState<T, M> {
  if (!current) return this
  val held = slots.indexOfFirst { it.after == after }
  val requested = slots.isNotEmpty() && slots.last().next == after
  if (held < 0 && !requested) return this
  return when (outcome) {
    Outcome.Loading -> copy(append = LoadStatus.Loading)
    is Outcome.Error -> copy(append = LoadStatus.Failed(outcome.cause))
    is Outcome.Data -> {
      val page = outcome.data
      val slot = PagedState.Slot(after = after, next = page.next, items = page.items)
      val slots =
        when {
          held < 0 -> slots + slot
          slots[held].next == page.next -> slots.toMutableList().also { it[held] = slot }
          else -> slots.take(held) + slot
        }
      copy(slots = slots, append = LoadStatus.Idle).withItems(idOf)
    }
  }
}

/**
 * Marks a refresh in flight: the first page is about to be requested again. The items stay on
 * screen under [PagedState.refresh], the next page's request is abandoned, and the first page that
 * arrives replaces every page.
 */
fun <T, M> PagedState<T, M>.refreshing(): PagedState<T, M> =
  copy(refresh = LoadStatus.Loading, append = LoadStatus.Idle, replaceOnFirstPage = true)

/**
 * The state for a new key, about to request its first page. [onKeyChange] says whether the old
 * key's items stay on screen until then. Kept items are marked as the old key's, so their next
 * cursor can't be requested for the new one.
 */
fun <T, M> PagedState<T, M>.restarted(onKeyChange: OnKeyChange): PagedState<T, M> =
  when (onKeyChange) {
    OnKeyChange.ClearItems -> pagedStateOf()
    OnKeyChange.KeepItems -> refreshing().copy(current = false)
  }

/**
 * Settles the first page's request when its source completes. Call it only when the source
 * completed by itself, never when it was cancelled: an abandoned request is not a finished one. See
 * `ContentState.settled`.
 */
fun <T, M> PagedState<T, M>.firstPageCompleted(): PagedState<T, M> =
  if (refresh is LoadStatus.Loading) copy(refresh = LoadStatus.Idle, replaceOnFirstPage = false)
  else this

/**
 * Settles the request for the page after [after] when its source completes by itself. As with
 * [applyNextPage], a stale request changes nothing.
 */
fun <T, M> PagedState<T, M>.nextPageCompleted(after: String): PagedState<T, M> {
  val applies = current && (slots.any { it.after == after } || slots.lastOrNull()?.next == after)
  return if (applies && append is LoadStatus.Loading) copy(append = LoadStatus.Idle) else this
}

/**
 * Edits the items shown without a request, such as removing one after unblocking it. [transform]
 * gets every item and returns the list to show.
 *
 * Each item in the result goes back to the page that held its id, so a later page or a re-emitted
 * page lands where it should. A new id joins the page of the item before it. An edit lasts until
 * the page holding it is emitted again, a refresh, or a new key, as `ContentState.data` does.
 */
fun <T, M> PagedState<T, M>.updated(
  idOf: (T) -> Any?,
  transform: (List<T>) -> List<T>,
): PagedState<T, M> {
  if (slots.isEmpty()) return this
  val slotOf = HashMap<Any?, Int>()
  slots.forEachIndexed { index, slot ->
    slot.items.forEach { item -> slotOf.getOrPut(idOf(item)) { index } }
  }
  val regrouped = List(slots.size) { mutableListOf<T>() }
  var previous = 0
  transform(items).forEach { item ->
    val index = slotOf[idOf(item)] ?: previous
    regrouped[index] += item
    previous = index
  }
  return copy(
      slots =
        slots.mapIndexed { index, slot -> PagedState.Slot(slot.after, slot.next, regrouped[index]) }
    )
    .withItems(idOf)
}

private fun <T, M> PagedState<T, M>.withItems(idOf: (T) -> Any?): PagedState<T, M> =
  copy(items = slots.flatMap { it.items }.distinctBy(idOf))
