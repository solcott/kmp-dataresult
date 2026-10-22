# Design

## Context

See proposal.md for why. What the design builds on:

- `ContentState<T>` (`uistate`) folds `Outcome<T>` with `applyEmission`. `data` changes only on
  `Outcome.Data`. `Loading` and `Error` move `status` and keep the value. `hasLoaded` is
  `origin != null`, and `hasAnswer(isEmpty)` won't count an empty cache read while a request is
  loading or failed. `status` settles on every emission.
- `uistate` is exported to Swift wholesale. `checkSwiftExport` rejects `sealed interface`, Compose
  types and `kotlinx.collections.immutable` types in its public API. `@Immutable` (annotation only)
  is allowed.
- `uistate-circuit` wraps the fold in `produceRetainedState`. The fold itself lives in
  `uistate-compose` (`collectFrom` / `collectLatestFrom`), with its traps pinned by tests: settle
  only on `cause == null`, keep `rememberUpdatedState(stream)`, and don't key the retained value.
- activity-tracker's `CursorPaging` takes `key`, `idOf`, `enabled` and
  `load: suspend (String?) -> CursorPage<T>`. All three of its `update {}` callers remove items
  (`filterNot`). The comments pager pages threads, then each thread's replies from a
  `repliesCursor` that comes with the thread, under a generation guard.

## Goals / Non-Goals

**Goals:**
- The fold is pure and lives in `uistate`, so any host can drive it (a Circuit presenter, a
  ViewModel, Swift) and it can be tested without composition.
- The holder runs one request at a time per list, and a stale result can never land in the wrong
  key or after a refresh.
- The API covers activity-tracker's five lists and its comment threads with no app-side pager left
  over.

**Non-Goals:**
- A `uistate-compose` (androidx `retain`) holder. The driver is written so it can be added as a thin
  wrapper later. Nothing needs it now.
- A `Flow<P>` parameter variant like `Flow<P>.produceRetainedContentState`. Keys cover the app's
  callers, and AthleteSearch keeps its own 300 ms debounce.
- Paging backwards or in both directions, placeholders, or jumping to a page. Lists here grow
  forward from a cursor.
- Surviving process death. `rememberRetained` doesn't, and neither does `ContentState` today.
- Prepending local items. A screen with unsent local items, like the comments' `sent`, keeps them
  beside the paged list.

## Decisions

### 1. The fold in `uistate`, the holder in `uistate-circuit`

`Page`, `PagedState`, the key-change enum and the fold functions go in `uistate`. They hold no
coroutines and no Compose types, as `ContentState` doesn't. `Paging` and `rememberRetainedPaging`
go in `uistate-circuit`, beside `produceRetainedContentState`.

The holder's driver (the command loop, resuming after a return, discarding stale results) goes in
an internal class in `uistate-circuit`. A later `uistate-compose` holder would move it there and
make it public, as `collectFrom` is.

*Alternative:* the whole holder in `uistate-compose`, with Circuit as a thin wrapper, as
`ContentState` does. That doubles the public API for a variant nobody uses yet.

### 2. API shape

```kotlin
// uistate
@Immutable data class Page<out T, out M>(val items: List<T>, val meta: M, val next: String?)
fun <T> pageOf(items: List<T>, next: String?): Page<T, Unit>

@Immutable class PagedState<T, M> internal constructor(/* page slots, statuses */) {
  val items: List<T>          // every page, in order, de-duplicated
  val meta: M?                // first page's; null until it arrives
  val origin: Origin?         // first page's; null until it arrives
  val refresh: LoadStatus
  val append: LoadStatus
  val endReached: Boolean
}
val PagedState<*, *>.hasLoaded: Boolean      // origin != null
val PagedState<*, *>.hasAnswer: Boolean      // hasLoaded, minus an empty cached first page while loading/failed
val PagedState<*, *>.canLoadMore: Boolean
enum class OnKeyChange { KeepItems, ClearItems }

// uistate-circuit
@Stable class Paging<T, M> {
  val state: PagedState<T, M>
  fun loadMore(); fun retry(); fun refresh()
  fun update(transform: (List<T>) -> List<T>)
}
@Composable fun <T, M> rememberRetainedPaging(
  vararg keys: Any?,
  idOf: (T) -> Any,
  onKeyChange: OnKeyChange = OnKeyChange.KeepItems,
  enabled: Boolean = true,
  initial: Outcome.Data<Page<T, M>>? = null,
  load: (cursor: String?) -> Flow<Outcome<Page<T, M>>>,
): Paging<T, M>
```

The fold functions (`pagedStateOf`, applying a first page or a next page, `refreshing`,
`restarted(onKeyChange)`, `updated`, settling) are public, as `applyEmission`, `reloading` and
`settled` are. Their names are settled during implementation against the ABI dump.

- **`PagedState` is a class, not a data class.** It keeps per-page slots: the cursor each page was
  requested with, its next cursor, its items and whether its source has completed. A data class
  `copy` would expose those slots. It implements `equals`/`hashCode` over its slots and statuses.
- **`refresh` and `append` reuse `LoadStatus`.** They are separate because the UI shows them
  differently: a pull-to-refresh indicator or full-screen error versus a footer spinner or inline
  retry. One status couldn't say which request failed, and so `retry()` couldn't either.
- **`meta` is a type parameter, not `Any?`.** Lists without a header use `pageOf`, which gives
  `Page<T, Unit>`.
- **`idOf` is passed to each fold call and not stored.** A stored lambda would break `equals`.
- **The loader returns a `Flow`.** The library's streams are `Flow<Outcome<T>>`, and only a flow can
  carry a cached first page (decision 4). A one-shot read is `flow { emit(repo.page(cursor)) }`.
  There is no `suspend` overload: a lambda returning `Outcome` and one returning `Flow` can't be
  told apart by overload resolution.
- **Naming:** `rememberRetainedPaging` follows Circuit's `rememberRetained`. It returns a holder
  you call methods on, not a state, which is why it isn't `produce…`.

### 3. Open question: generic or `String` cursor → `String`

`Page.next` is `String?`.

- Every source the app pages (atproto XRPC, GraphQL Relay `endCursor`) uses opaque string cursors.
  Offset or numeric keys encode losslessly as strings.
- A cursor type parameter would make it `Page<T, M, K>`, `PagedState<T, M, K>` and
  `Paging<T, M, K>`. That is a third parameter on every signature for no current caller.
- Swift export maps unconstrained generics to `Any?`, so a generic cursor would arrive in Swift
  untyped. `String` arrives as `String`.
- The cursor is never shown, so the UI doesn't care about its type. Only the holder passes it back
  to the loader.

*Alternative:* `K` generic. Adding it later is a breaking change. That trade is accepted, because
an opaque token is what cursor paging means.

### 4. Open question: a cached (Room) first page → supported, through the same fold

No separate mode. A page's loader returns a `Flow`, and each page is a slot that its own emissions
replace:

- A cache-then-network first page emits `Data(Cache)` then `Data(Network)`. The second replaces the
  first page's items in place.
- If a re-emitted page's next cursor equals the one held, later pages are kept. If it differs, the
  pages after it were read from a cursor that no longer exists, so they are dropped and `endReached`
  is recomputed. This covers a Room watcher that re-emits after a local write without dropping the
  user's scroll position.
- An empty cached first page while the refresh is loading or failed isn't an answer (`hasAnswer`).
  This is `ContentState.hasAnswer`, with emptiness known because items are a `List`. A source should
  still hold a miss back, as the Store5 adapter does. This is the backstop.
- A page whose flow hasn't completed when the screen leaves composition is collected again on
  return (decision 6). A Room watcher keeps watching, and a completed network page isn't refetched.

So the feed can page later without new API.

*Alternative:* a separate `firstPage: Flow` parameter plus one-shot later pages. That is two
loading paths with different rules, and it can't express a later page served from cache.

### 5. Open question: nested reply paging → one `Paging` per thread, with `initial`

The library won't nest `PagedState`s. A thread's replies are an independent list: their own
cursor, their own failure, their own retry, and they're shown inside an item of the parent list. The
comments screen holds one `Paging` for threads and one per thread for replies, each inside
`key(thread.uri)`, and keyed on the parent's load so a refresh of the threads drops them.

What the library adds for this is `initial`. A thread arrives with its first replies and a
`repliesCursor`, so the reply holder starts from `Outcome.Data(Page(thread.replies, Unit,
thread.repliesCursor), origin)`. It is loaded at once and requests nothing until `loadMore()`. That
replaces the comments pager's hand-rolled `Replies` bookkeeping and its generation guard.

*Alternative:* first-class nesting (`PagedState<T, M>` whose items carry a child `PagedState`, with
`loadMoreChildren(id)`). Every caller would carry child type parameters, the fold would grow a
second level of rules, and Swift would see nested generics. One screen needs it, and composition
already does it.

### 6. Retention, and coming back

`rememberRetainedPaging` holds a `PagingHolder` with `rememberRetained`, unkeyed. It holds the
`PagedState` as snapshot state, the keys it last loaded for, and which requests are in flight. A
`LaunchedEffect(holder, *keys, enabled)` runs the driver, which reads commands from a conflated
channel so that `loadMore()`/`retry()`/`refresh()` are safe to call from event handlers.

When the effect starts:
- **Keys differ from the held ones** (or it's the first start): apply `restarted(onKeyChange)` and
  request the first page.
- **Keys are the same** (the screen came back): request again only what was interrupted. That
  means the first page if `refresh` is `Loading`, the next page if `append` is `Loading`, and any
  slot whose flow hadn't completed. Settled pages show at once and aren't requested.

The existing invariants carry over, and tests pin them:
- Don't key `rememberRetained`, or a key change drops the items `KeepItems` must keep.
- Settle a slot in `onCompletion` only when `cause == null`, or an abandoned request reads as
  finished and wouldn't be re-requested on return.
- Use `rememberUpdatedState(load)` and `rememberUpdatedState(idOf)`.

Circuit's paused-record hazard (a non-top pane stops collecting, silently) applies, and the KDoc
carries the same warning as `produceRetainedContentState`.

### 7. One request at a time, and stale results

The driver runs at most one first-page request and one next-page request. A refresh, a retry of the
refresh, or a key change cancels both, so a page in flight never lands on a new first page. Each
next-page result is applied against the cursor it was requested with. If that cursor isn't the last
slot's next cursor any more, the result is dropped. That replaces `CursorPaging`'s channel drain and
the comments pager's generation number with one check in the fold, where it can be unit-tested.

### 8. Open question: keep or clear on a new key → a parameter, defaulting to keep

`onKeyChange: OnKeyChange = OnKeyChange.KeepItems`.

- Keep and clear differ in one step of the fold: what `restarted` does to the slots. Everything
  else is the same. Two entry points (`rememberRetainedPaging` and `rememberRetainedClearingPaging`)
  would duplicate every parameter and every KDoc paragraph, and double again if a `Flow<P>` or
  `uistate-compose` variant is added.
- **Keep is the default** because it's what the library already does on a key change.
  `produceRetainedContentState` and `Flow<P>.produceRetainedContentState` keep the value and mark it
  reloading. Clearing is the exception a caller asks for: a filter, where the old items would be
  wrong under the new header.
- An `enum`, not a `Boolean`. `onKeyChange = ClearItems` reads at the call site, and an enum exports
  to Swift (as `Origin` does), so a Swift-driven fold can use it too.

*Alternative:* a `Boolean` `clearOnKeyChange`. It reads worse at call sites and can't grow a third
behavior, such as keeping only the header.

### 9. Optimistic edits with page slots

`update(transform)` takes the whole list, as `CursorPaging.update` does, so the app's `filterNot`
callers move over unchanged. The result is mapped back onto slots by id. Each kept item stays in the
slot that held it. A new id goes into the slot of the item before it, or the first slot. An edit
lasts until that slot's source emits again, a refresh, or a new key, which is the same lifetime
`ContentState.data` has.

### 10. `enabled`

Kept from `CursorPaging`. AthleteSearch (blank query) and StarredSegments (feature unavailable)
use it, and Compose can't call `rememberRetainedPaging` conditionally without losing what it
retained. While disabled, the state has no items, isn't loaded, and both statuses are `Idle`.

## Risks / Trade-offs

- **The slot rules are more machinery than `CursorPaging`'s flat list.** → All of it is in the
  pure fold, covered scenario by scenario in `commonTest`, before the holder exists.
- **A page whose source never completes is collected for as long as the screen is composed, and
  again on every return.** → That's what a Room watcher should do. A network loader completes
  after one emission, so returning doesn't refetch. KDoc says so.
- **`String` cursors can't change without breaking the API.** → Accepted (decision 3).
- **Per-thread holders cost one retained slot and one idle effect per loaded thread.** → Threads
  load a page at a time (tens, not thousands), and an idle effect waiting on a channel is cheap.
  If it shows up in profiling, a keyed holder map can be added without changing `PagedState`.
- **Swift export of lambda parameters (`idOf`) in `uistate`'s public fold.** →
  `ContentState.hasAnswer(isEmpty: (T) -> Boolean)` already exports, and `checkSwiftExport` plus
  the consumer build are the check.

## Migration Plan

Additive only. Existing API and ABI dumps don't change, apart from new entries. Release it in a
`-SNAPSHOT` (published from `main`) or a release, then activity-tracker's `adopt-library-paging`
moves its screens over. Rolling back means not upgrading: nothing existing depends on the new
types.
